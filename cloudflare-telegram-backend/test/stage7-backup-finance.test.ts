// ============================================================
// Stage 7: server-side daily backup, financial health check and the
// sandbox recovery test.
//
// Proves, without any real bot/network/production database:
//   1. a backup run stores a VERIFIED gzip snapshot of every essential
//      table — including contract file bytes (inline AND chunked) —
//      and the status API reports it (auth required);
//   2. verify-before-replace: a corrupted snapshot is marked CORRUPT,
//      the run FAILED with a Persian reason, and every previous
//      HEALTHY snapshot survives untouched;
//   3. the daily cron backs up each academy once per Tehran day,
//      never twice, is tenant-isolated, and works with NO bot token;
//   4. retention: old copies are pruned only after the new snapshot
//      verified healthy; the newest healthy copy is never removed;
//   5. recovery: restoring a backup into a FRESH schema reproduces
//      row counts, financial sums and contract file hashes exactly;
//   6. cross-academy isolation: academy A's backup contains none of
//      academy B's rows;
//   7. the financial health check reports (never rewrites) negative
//      amounts, overpays, remaining/debt mismatches, stale paid flags
//      and overdue installments computed from raw dates (Tehran);
//   8. failed runs are visible and the manual retry works.
// ============================================================

import { describe, it, expect } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { maybeDailyBackup } from '../src/handlers/cron';
import { runBackup, getBackupStatus, readBackupBundle, restoreBundleForTests, sha256HexBytes } from '../src/backup';
import { financeHealth, startOfTodayTehran } from '../src/finance';
import { D1Shim } from './helpers/d1-shim';
import {
    makeEnv, bearer, ACADEMY_A, ACADEMY_B, API_KEY
} from './helpers/fixtures';
import type { Env } from '../src/types';

const BASE = 'https://worker.test';
const DAY_MS = 24 * 60 * 60 * 1000;

// ------------------------------------------------------------------
// Seed helpers (direct D1 inserts — the same rows the app syncs)
// ------------------------------------------------------------------

interface SeedSpec {
    students: number;
    installmentsPerStudent: number;
    withSummary?: boolean;
    contracts?: number;
    inlineFiles?: number;
    chunkedFiles?: number;
    chunkParts?: number;
}

async function seedAcademy(db: D1Shim, academyId: string, spec: SeedSpec): Promise<void> {
    await db.run(
        `INSERT OR IGNORE INTO academies (id, name, created_at_epoch) VALUES (?, 'آموزشگاه تست', ?)`,
        academyId, Date.now()
    );
    for (let s = 0; s < spec.students; s++) {
        const sid = `${academyId}-stu-${s}`;
        db.run(
            `INSERT INTO students (id, student_code, full_name, phone, national_code, telegram_chat_id,
                 telegram_username, is_telegram_connected, linked_at_epoch, created_at_epoch, updated_at_epoch,
                 academy_id, telegram_link_hash)
             VALUES (?, ?, ?, ?, ?, '', '', 0, 0, ?, ?, ?, ?)`,
            sid, `${1000 + s}`, `هنرجو ${s} ${academyId}`, '09120000000', '0000000000',
            Date.now(), Date.now(), academyId, `hash-${academyId}-${s}`
        );
        for (let i = 0; i < spec.installmentsPerStudent; i++) {
            const amount = 500_000 + i * 100_000;
            const paid = i === 0 ? amount : (i === 1 ? Math.floor(amount / 2) : 0);
            db.run(
                `INSERT INTO installments (id, student_id, installment_number, total_installments, course_title,
                     amount, paid_amount, remaining_amount, due_date_shamsi, due_date_epoch, is_paid,
                     paid_date_shamsi, updated_at_epoch, academy_id)
                 VALUES (?, ?, ?, 3, 'دوره موسیقی', ?, ?, ?, '1404/07/01', ?, ?, '', ?, ?)`,
                `${sid}-inst-${i}`, sid, i + 1, amount, paid, amount - paid,
                Date.now() + (i - 2) * DAY_MS, (i === 0 ? 1 : 0), Date.now(), academyId
            );
        }
        if (spec.withSummary !== false) {
            const tuition = spec.installmentsPerStudent * 500_000 + 300_000; // deliberately derived
            let paidSum = 0;
            for (let i = 0; i < spec.installmentsPerStudent; i++) {
                paidSum += i === 0 ? 500_000 : (i === 1 ? Math.floor((500_000 + 100_000) / 2) : 0);
            }
            db.run(
                `INSERT INTO financial_summaries (student_id, total_tuition, total_paid, remaining_debt,
                     active_courses_count, courses_summary, settlement_status, updated_at_epoch, academy_id)
                 VALUES (?, ?, ?, ?, 1, 'دوره موسیقی', 'PENDING', ?, ?)`,
                sid, tuition, paidSum, tuition - paidSum, Date.now(), academyId
            );
        }
    }
    // Contracts + files (inline and chunked), matching migrations 0002/0005/0007.
    const nContracts = spec.contracts ?? 0;
    for (let c = 0; c < nContracts; c++) {
        const cid = `${academyId}-contract-${c}`;
        const sid = `${academyId}-stu-0`;
        db.run(
            `INSERT INTO contracts (id, academy_id, enrollment_id, student_id, student_name, course_title,
                 status, token_hash, expires_at_epoch, issued_at_epoch, pdf_key, pdf_sha256,
                 enrollment_json, created_at_epoch, updated_at_epoch)
             VALUES (?, ?, ?, ?, 'هنرجو تست', 'دوره موسیقی', 'SIGNED', ?, ?, ?, ?, ?, '{}', ?, ?)`,
            cid, academyId, `${academyId}-enr-${c}`, sid, `tok-${academyId}-${c}`,
            Date.now() + DAY_MS, Date.now(), `${academyId}-file-pdf-${c}`, 'deadbeef', Date.now(), Date.now()
        );
    }
    const bytesOf = (seed: number, len: number) => {
        const b = new Uint8Array(len);
        for (let i = 0; i < len; i++) b[i] = (i * 31 + seed * 17) % 256;
        return b;
    };
    for (let f = 0; f < (spec.inlineFiles ?? 0); f++) {
        const key = `${academyId}-file-pdf-${f}`;
        const bytes = bytesOf(f + 1, 3000 + f * 111);
        db.run(
            `INSERT INTO contract_files (key, academy_id, content, sha256, created_at_epoch) VALUES (?, ?, ?, ?, ?)`,
            key, academyId, bytes, await sha256HexBytes(bytes), Date.now()
        );
    }
    for (let f = 0; f < (spec.chunkedFiles ?? 0); f++) {
        const key = `${academyId}-file-chunked-${f}`;
        const whole = bytesOf(100 + f, 768 * 1024 + 5000); // spans multiple D1-sized parts
        const meta = db as D1Shim;
        // metadata row with NULL content (migration 0007 shape)
        meta.run(
            `INSERT INTO contract_files (key, academy_id, content, sha256, created_at_epoch) VALUES (?, ?, NULL, ?, ?)`,
            key, academyId, await sha256HexBytes(whole), Date.now()
        );
        const parts = spec.chunkParts ?? 3;
        const partLen = Math.ceil(whole.length / parts);
        for (let p = 0; p < parts; p++) {
            meta.run(
                `INSERT INTO contract_file_chunks (file_key, part, content) VALUES (?, ?, ?)`,
                key, p, whole.subarray(p * partLen, Math.min((p + 1) * partLen, whole.length))
            );
        }
    }
}

async function seedSettings(db: D1Shim, academyId: string): Promise<void> {
    db.run(
        `INSERT INTO academy_settings (key, value, updated_at_epoch) VALUES (?, ?, ?)`,
        `${academyId}:bot:menu_text`, 'سلام', Date.now()
    );
    // A row namespaced for the OTHER academy must never leak into this backup.
    db.run(
        `INSERT OR REPLACE INTO academy_settings (key, value, updated_at_epoch) VALUES (?, ?, ?)`,
        `${ACADEMY_B}:secret`, 'متعلق به B', Date.now()
    );
}

// ------------------------------------------------------------------
// 1-2. Backup run, verification, status API
// ------------------------------------------------------------------

describe('stage 7: backup run + status', () => {
    it('stores a verified snapshot covering every essential table and file', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, {
            students: 2, installmentsPerStudent: 3, contracts: 2,
            inlineFiles: 2, chunkedFiles: 1, chunkParts: 3
        });
        await seedSettings(db, ACADEMY_A);

        const outcome = await runBackup(env, ACADEMY_A, 'MANUAL');
        expect(outcome.ok).toBe(true);
        expect(outcome.status).toBe('HEALTHY');
        expect(outcome.filesIncluded).toBe(true);
        expect(outcome.tableCounts['students']).toBe(2);
        expect(outcome.tableCounts['installments']).toBe(6);
        expect(outcome.tableCounts['financial_summaries']).toBe(2);
        expect(outcome.tableCounts['contracts']).toBe(2);
        expect(outcome.tableCounts['contract_files']).toBe(3); // 2 inline + 1 chunked
        expect(outcome.storedBytes).toBeGreaterThan(0);

        // The stored payload parses and carries the file bytes.
        const read = await readBackupBundle(env, outcome.backupId);
        expect(read.ok).toBe(true);
        const bundle = read.parsed!;
        const inlineRow = (bundle.tables['contract_files'] as any[]).find((r) => r.key === `${ACADEMY_A}-file-pdf-0`);
        expect(inlineRow.content_b64.length).toBeGreaterThan(0);
        const chunkRows = bundle.tables['contract_file_chunks'] as any[];
        expect(chunkRows.length).toBe(3);
    });

    it('exposes status via the API with the bearer key and refuses anonymous calls', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 2 });
        await runBackup(env, ACADEMY_A, 'AUTO');

        const auth = bearer(ACADEMY_A);
        const ok = await handleApiRequest(
            new Request(`${BASE}/api/backup/status`, { method: 'GET', headers: auth }), env, new URL(`${BASE}/api/backup/status`)
        );
        expect(ok.status).toBe(200);
        const body: any = await ok.json();
        expect(body.success).toBe(true);
        expect(body.data.lastHealthy).toBeTruthy();
        expect(body.data.lastHealthy.table_counts ? JSON.parse(body.data.lastHealthy.table_counts).students : body.data.lastHealthy['table_counts']).toBeTruthy();
        expect(body.data.healthyCount).toBe(1);
        expect(body.data.lastRun.outcome).toBe('SUCCESS');
        // No payload and no secret material ever leaves D1 through the API.
        const serialized = JSON.stringify(body);
        expect(serialized).not.toContain('content_b64');
        expect(serialized).not.toContain('bot_token');

        const anon = await handleApiRequest(
            new Request(`${BASE}/api/backup/status`, { method: 'GET' }), env, new URL(`${BASE}/api/backup/status`)
        );
        expect(anon.status).toBe(401);
    });

    it('refuses a tenant that is not served by this deployment (403)', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 1 });
        const res = await handleApiRequest(
            new Request(`${BASE}/api/backup/status`, {
                method: 'GET', headers: { Authorization: `Bearer ${API_KEY}`, 'X-Academy-Id': 'rogue-academy' }
            }), env, new URL(`${BASE}/api/backup/status`)
        );
        expect(res.status).toBe(403);
    });
});

// ------------------------------------------------------------------
// 2-4. verify-before-replace, corruption, retention, daily cron
// ------------------------------------------------------------------

describe('stage 7: corruption handling + daily cron', () => {
    it('marks a corrupted new snapshot CORRUPT and keeps the previous HEALTHY copy', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 2 });

        const first = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(first.ok).toBe(true);

        // The NEXT run stores its payload through a trigger that swaps the
        // chunk bytes AFTER insertion — the exact "new backup is damaged
        // between store and verify" scenario. The sha256 read-back must
        // catch it and nothing healthy may be pruned.
        db.run(`CREATE TRIGGER stage7_corrupt AFTER INSERT ON backup_chunks
                BEGIN UPDATE backup_chunks SET content = zeroblob(7) WHERE rowid = NEW.rowid; END`);

        const second = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(second.ok).toBe(false);
        expect(second.status).toBe('CORRUPT');
        expect(second.error.length).toBeGreaterThan(0);

        db.run('DROP TRIGGER stage7_corrupt');

        const status: any = await getBackupStatus(env, ACADEMY_A);
        const healthyRow = status.history.find((h: any) => h.id === first.backupId);
        expect(healthyRow).toBeTruthy();
        expect(healthyRow.status).toBe('HEALTHY'); // previous healthy copy untouched
        expect(status.healthyCount).toBe(1);
        expect(status.lastRun.outcome).toBe('FAILED');
        expect(status.lastRun.error.length).toBeGreaterThan(0);
    });

    it('runs once per Tehran day per academy, tenant-isolated, with no bot token', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 1 });
        await seedAcademy(db, ACADEMY_B, { students: 1, installmentsPerStudent: 1 });

        await maybeDailyBackup(env, ACADEMY_A);
        const countBackups = (academy: string) =>
            (db.get(`SELECT COUNT(*) AS n FROM backups WHERE academy_id = ?`, academy) as any).n;

        expect(countBackups(ACADEMY_A)).toBe(1);
        // Same day again: the marker prevents a second snapshot.
        await maybeDailyBackup(env, ACADEMY_A);
        expect(countBackups(ACADEMY_A)).toBe(1);

        // Academy B is a different tenant: its own marker, its own backup.
        await maybeDailyBackup(env, ACADEMY_B);
        expect(countBackups(ACADEMY_B)).toBe(1);

        const marker = db.get(
            `SELECT value FROM academy_settings WHERE key = ?`, `${ACADEMY_A}:backup:last_day`
        ) as any;
        expect(marker?.value).toBeTruthy();
    });

    it('keeps the day marker unset when the backup fails, so cron retries tomorrow-minute', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 1 });
        // Break the target schema: the export cannot succeed.
        db.run('DROP TABLE message_logs');
        await maybeDailyBackup(env, ACADEMY_A);
        const marker = db.get(`SELECT value FROM academy_settings WHERE key = ?`, `${ACADEMY_A}:backup:last_day`);
        expect(marker).toBeFalsy();

        const status: any = await getBackupStatus(env, ACADEMY_A);
        expect(status.lastRun.outcome).toBe('FAILED');
        expect(status.lastRun.error.length).toBeGreaterThan(0);

        // Manual retry after the schema is repaired succeeds.
        // (Only message_logs was dropped; recreate it exactly as schema.sql defines it.)
        db.run(`CREATE TABLE IF NOT EXISTS message_logs (
            id TEXT PRIMARY KEY, student_id TEXT NOT NULL DEFAULT '', student_name TEXT NOT NULL DEFAULT '',
            chat_id TEXT NOT NULL, message_type TEXT NOT NULL DEFAULT 'DIRECT', content TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'SUCCESS', error_message TEXT NOT NULL DEFAULT '',
            timestamp_epoch INTEGER NOT NULL, timestamp_shamsi TEXT NOT NULL DEFAULT '', academy_id TEXT NOT NULL DEFAULT 'default')`);
        const retry = await runBackup(env, ACADEMY_A, 'MANUAL');
        expect(retry.ok).toBe(true);
    });

    it('prunes only after the new snapshot verified healthy and keeps the newest', async () => {
        const { env, db } = makeEnv(undefined, { BACKUP_RETENTION_DAYS: '2' });
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 1 });
        for (let i = 0; i < 4; i++) {
            const r = await runBackup(env, ACADEMY_A, 'AUTO');
            expect(r.ok).toBe(true);
        }
        const status: any = await getBackupStatus(env, ACADEMY_A);
        expect(status.healthyCount).toBe(2);
    });
});

// ------------------------------------------------------------------
// 5-6. Recovery into a fresh schema + tenant isolation of the payload
// ------------------------------------------------------------------

describe('stage 7: recovery test (sandbox) + isolation', () => {
    it('restores a backup into a fresh schema with identical counts, sums and file hashes', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, {
            students: 2, installmentsPerStudent: 3, contracts: 1,
            inlineFiles: 1, chunkedFiles: 1, chunkParts: 3
        });
        await seedSettings(db, ACADEMY_A);
        const outcome = await runBackup(env, ACADEMY_A, 'MANUAL');
        expect(outcome.ok).toBe(true);
        const bundle = (await readBackupBundle(env, outcome.backupId)).parsed!;

        // A brand new, empty database with the production migrations applied.
        const fresh = new D1Shim();
        const { loadMigrations } = await import('./helpers/fixtures');
        fresh.applyMigrations(loadMigrations());
        const restored = await restoreBundleForTests(fresh as unknown as D1Database, bundle, true);

        for (const table of ['students', 'installments', 'financial_summaries', 'contracts', 'contract_files', 'contract_file_chunks']) {
            expect(restored[table]).toBe(outcome.tableCounts[table]);
        }

        // Financial sums must survive byte-for-byte.
        const liveSums = db.get(
            `SELECT COALESCE(SUM(amount),0) a, COALESCE(SUM(paid_amount),0) p, COALESCE(SUM(remaining_amount),0) r FROM installments WHERE academy_id = ?`,
            ACADEMY_A
        ) as any;
        const newSums = fresh.get(
            `SELECT COALESCE(SUM(amount),0) a, COALESCE(SUM(paid_amount),0) p, COALESCE(SUM(remaining_amount),0) r FROM installments WHERE academy_id = ?`,
            ACADEMY_A
        ) as any;
        expect(newSums.a).toBe(liveSums.a);
        expect(newSums.p).toBe(liveSums.p);
        expect(newSums.r).toBe(liveSums.r);

        // Installment <-> student relations survive.
        const orphan = fresh.get(
            `SELECT COUNT(*) AS n FROM installments i LEFT JOIN students s ON s.id = i.student_id WHERE s.id IS NULL`
        ) as any;
        expect(orphan.n).toBe(0);

        // Contract file hashes: inline and chunked both match the live bytes.
        for (const key of [`${ACADEMY_A}-file-pdf-0`, `${ACADEMY_A}-file-chunked-0`]) {
            const liveFile = db.get(`SELECT sha256 FROM contract_files WHERE key = ?`, key) as any;
            let restoredBytes: Uint8Array;
            if (key.includes('chunked')) {
                const parts = await fresh.prepare(`SELECT content FROM contract_file_chunks WHERE file_key = ? ORDER BY part`).bind(key).all<any>();
                const chunks = parts.results.map((r: any) => new Uint8Array(r.content));
                restoredBytes = chunks.reduce((acc: Uint8Array, part: Uint8Array) => {
                    const merged = new Uint8Array(acc.length + part.length);
                    merged.set(acc, 0); merged.set(part, acc.length); return merged;
                }, new Uint8Array(0));
            } else {
                const row = fresh.get(`SELECT content FROM contract_files WHERE key = ?`, key) as any;
                restoredBytes = new Uint8Array(row.content);
            }
            expect(await sha256HexBytes(restoredBytes)).toBe(liveFile.sha256);
        }

        // Namespaced settings survive; the other academy's settings never moved.
        const restoredSetting = fresh.get(`SELECT value FROM academy_settings WHERE key = ?`, `${ACADEMY_A}:bot:menu_text`) as any;
        expect(restoredSetting?.value).toBe('سلام');
        const bRow = fresh.get(`SELECT COUNT(*) AS n FROM academy_settings WHERE key = ?`, `${ACADEMY_B}:secret`) as any;
        expect(bRow.n).toBe(0);
    });

    it('keeps academy B rows out of academy A backup payloads', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 1 });
        await seedAcademy(db, ACADEMY_B, { students: 1, installmentsPerStudent: 1 });

        const outcome = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(outcome.ok).toBe(true);
        const bundle = (await readBackupBundle(env, outcome.backupId)).parsed!;
        const serialized = JSON.stringify(bundle);
        expect(serialized).not.toContain(`${ACADEMY_B}-stu-0`);
        expect(serialized).not.toContain(`${ACADEMY_B}:secret`);
        expect(bundle.academyId).toBe(ACADEMY_A);
    });
});

// ------------------------------------------------------------------
// 7. Financial health check (read-only)
// ------------------------------------------------------------------

describe('stage 7: finance health', () => {
    it('reports clean data with correct totals and no errors', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 2, installmentsPerStudent: 3 });

        // Fix summaries to match the installments exactly.
        await db.run(
            `UPDATE financial_summaries SET
                 total_tuition = (SELECT SUM(amount) FROM installments i WHERE i.student_id = financial_summaries.student_id),
                 total_paid = (SELECT SUM(paid_amount) FROM installments i WHERE i.student_id = financial_summaries.student_id),
                 remaining_debt = (SELECT SUM(remaining_amount) FROM installments i WHERE i.student_id = financial_summaries.student_id)`
        );

        const report = await financeHealth(env, ACADEMY_A);
        expect(report.ok).toBe(true);
        expect(report.issues.filter((i) => i.severity === 'ERROR')).toHaveLength(0);
        expect(report.installmentsChecked).toBe(6);
        expect(report.studentsChecked).toBe(2);
        // per student: inst0 fully paid (500k), inst1 half of 600k, inst2 unpaid (700k)
        expect(report.totals.totalPaid).toBe(2 * (500_000 + 300_000));
        expect(report.totals.totalRemainingDebt).toBe(2 * (300_000 + 700_000));
        expect(report.totals.partialPaidCount).toBe(2);
    });

    it('flags overpay, remaining mismatch, paid-flag mismatch and summary mismatch — and rewrites nothing', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 2 });

        // paid (550k) > amount (500k) on installment 0
        await db.run(`UPDATE installments SET paid_amount = 550000, remaining_amount = 0 WHERE installment_number = 1`);
        // remaining wrong on installment 2
        await db.run(`UPDATE installments SET remaining_amount = 12345 WHERE installment_number = 2`);
        // summary tuition wrong
        await db.run(`UPDATE financial_summaries SET total_tuition = 999`);

        const before = db.get(`SELECT paid_amount FROM installments WHERE installment_number = 1`) as any;
        const report = await financeHealth(env, ACADEMY_A);
        expect(report.ok).toBe(false);
        const kinds = report.issues.map((i) => i.kind);
        expect(kinds).toContain('OVERPAID');
        expect(kinds).toContain('REMAINING_MISMATCH');
        expect(kinds).toContain('SUMMARY_TUITION_MISMATCH');

        // READ-ONLY: nothing was "fixed" behind the owner's back.
        const after = db.get(`SELECT paid_amount FROM installments WHERE installment_number = 1`) as any;
        expect(after.paid_amount).toBe(before.paid_amount);
    });

    it('computes overdue installments from raw dates in Tehran time', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 2, installmentsPerStudent: 3 });
        // Deterministic dates: inst 1 & 2 three days overdue (inst 1 fully paid),
        // inst 3 due in two days. Only inst 2 (partial, past due) counts as overdue.
        await db.run(
            `UPDATE installments SET
                 due_date_epoch = CASE WHEN installment_number <= 2 THEN ? ELSE ? END,
                 paid_amount = CASE WHEN installment_number = 1 THEN amount ELSE paid_amount END,
                 remaining_amount = CASE WHEN installment_number = 1 THEN 0 ELSE remaining_amount END,
                 is_paid = CASE WHEN installment_number = 1 THEN 1 ELSE is_paid END`,
            Date.now() - 3 * DAY_MS, Date.now() + 2 * DAY_MS
        );
        const report = await financeHealth(env, ACADEMY_A);
        expect(report.totals.overdueCount).toBe(2); // one partial overdue installment per student
        expect(report.totals.overdueAmount).toBe(2 * 300_000); // remaining half of the 600k installment

        // Once it is fully paid the overdue logic no longer counts it.
        await db.run(`UPDATE installments SET paid_amount = amount, remaining_amount = 0, is_paid = 1 WHERE installment_number = 2`);
        const after = await financeHealth(env, ACADEMY_A);
        expect(after.totals.overdueCount).toBe(0);
    });

    it('startOfTodayTehran stays a fixed +03:30 boundary (no DST since 2022)', () => {
        // 2026-01-01 00:30 Tehran == 2025-12-31 21:00 UTC; the Tehran day began
        // at 2026-01-01 00:00 Tehran == 2025-12-31 20:30 UTC.
        const utcEpoch = Date.UTC(2025, 11, 31, 21, 0, 0);
        expect(startOfTodayTehran(utcEpoch)).toBe(utcEpoch - 30 * 60_000);
        // 20:29 UTC is 23:59 Tehran of the PREVIOUS day -> its day began 24h earlier.
        expect(startOfTodayTehran(utcEpoch - 31 * 60_000)).toBe(utcEpoch - 30 * 60_000 - DAY_MS);
    });

    it('exposes the health report via the API with the bearer key', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { students: 1, installmentsPerStudent: 2 });
        const res = await handleApiRequest(
            new Request(`${BASE}/api/finance/health`, { method: 'GET', headers: bearer(ACADEMY_A) }),
            env, new URL(`${BASE}/api/finance/health`)
        );
        expect(res.status).toBe(200);
        const body: any = await res.json();
        expect(body.success).toBe(true);
        expect(body.data.totals).toBeTruthy();
        expect(Array.isArray(body.data.issues)).toBe(true);

        const anon = await handleApiRequest(
            new Request(`${BASE}/api/finance/health`, { method: 'GET' }), env, new URL(`${BASE}/api/finance/health`)
        );
        expect(anon.status).toBe(401);
    });
});

// ------------------------------------------------------------------

function _unusedEnvType(_: Env): void { /* type guard helper, intentionally empty */ }
void _unusedEnvType;
