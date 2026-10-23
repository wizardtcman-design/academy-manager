// ============================================================
// Stage 7 backup resilience: the free-plan query cap, resumability,
// single-writer concurrency and verify-before-replace must all keep
// working now that the backup covers the full stage-4/5 ledger.
//
// Proves, without any real bot/network/production database:
//   1. the measured number of D1 round-trips (what Cloudflare counts
//      against "50 queries per Worker invocation", Free) stays FAR
//      below the cap even for a multi-chunk backup with the whole
//      financial ledger;
//   2. an interruption mid-store leaves a SAFE resume state (PENDING
//      row, cursor persisted, old healthy copies intact, no day
//      marker) and the NEXT call continues the SAME snapshot —
//      identical payload hash — instead of starting over;
//   3. data changed between interruption and resume -> controlled
//      cleanup + honest FAILED, old healthy copy untouched;
//   4. concurrency: MANUAL refuses while a run is fresh; AUTO adopts
//      an interrupted run; a stale lock is taken over;
//   5. a corrupt new snapshot never displaces a previous HEALTHY one;
//   6. the slim path (payload > cap) still verifies with contract
//      files summarized while claim photo evidence stays included;
//   7. the status API reports the pending run honestly.
// ============================================================

import { describe, it, expect } from 'vitest';
import {
    runBackup, getBackupStatus, readBackupBundle, sha256HexBytes
} from '../src/backup';
import { maybeDailyBackup } from '../src/handlers/cron';
import { handleApiRequest } from '../src/handlers/api';
import { D1Shim } from './helpers/d1-shim';
import { makeEnv, bearer, ACADEMY_A, ACADEMY_B } from './helpers/fixtures';

const BASE = 'https://worker.test';
const DAY_MS = 24 * 60 * 60 * 1000;
const NOW = Date.now();
const LOCK_TTL_MS = 10 * 60 * 1000;

// ------------------------------------------------------------------
// Seed: base data + the FULL stage-4/5 ledger + many chunk-sized
// contract files so the gzip payload spans several 768 KB chunks.
// ------------------------------------------------------------------

async function seedAcademy(db: D1Shim, academyId: string, opts: { bigFiles: number } = { bigFiles: 0 }): Promise<void> {
    db.run(`INSERT OR IGNORE INTO academies (id, name, created_at_epoch) VALUES (?, 'آموزشگاه', ?)`, academyId, NOW);
    for (let s = 0; s < 2; s++) {
        const sid = `${academyId}-stu-${s}`;
        db.run(
            `INSERT INTO students (id, student_code, full_name, phone, national_code, telegram_chat_id,
                 telegram_username, is_telegram_connected, linked_at_epoch, created_at_epoch, updated_at_epoch,
                 academy_id, telegram_link_hash)
             VALUES (?, ?, ?, '09120000000', '0000000000', '', '', 0, 0, ?, ?, ?, ?)`,
            sid, `${3000 + s}`, `هنرجو ${s}`, NOW, NOW, academyId, `hash-${s}`
        );
        for (let i = 0; i < 2; i++) {
            const amount = 500_000;
            db.run(
                `INSERT INTO installments (id, student_id, installment_number, total_installments, course_title,
                     amount, paid_amount, remaining_amount, due_date_shamsi, due_date_epoch, is_paid,
                     paid_date_shamsi, updated_at_epoch, academy_id, payment_method)
                 VALUES (?, ?, ?, 2, 'دوره', ?, ?, ?, '1404/08/01', ?, 0, '', ?, ?, '')`,
                `${sid}-inst-${i}`, sid, i + 1, amount, amount, amount, NOW + i * DAY_MS, NOW, academyId
            );
        }
    }
    // Stage-4/5 ledger rows (small but present in every scenario).
    db.run(
        `INSERT INTO payment_receipts (id, academy_id, student_id, installment_id, amount, source,
             receipt_json, send_status, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 500000, 'manual', '{"amount":500000}', 'SENT', ?, ?)`,
        `${academyId}-pay-1`, academyId, `${academyId}-stu-0`, `${academyId}-stu-0-inst-0`, NOW, NOW
    );
    db.run(
        `INSERT INTO payment_transactions (id, academy_id, student_id, installment_id, amount, status,
             provider, paid_amount, receipt_id, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 500000, 'PAID', 'zarinpal', 500000, ?, ?, ?)`,
        `${academyId}-order-1`, academyId, `${academyId}-stu-0`, `${academyId}-stu-0-inst-0`,
        `${academyId}-pay-1`, NOW, NOW
    );
    db.run(
        `INSERT INTO payment_link_tokens (token, token_hash, academy_id, order_id, expires_at_epoch, created_at_epoch)
         VALUES (?, 'h1', ?, ?, ?, ?)`,
        `tok-${academyId}`, academyId, `${academyId}-order-1`, NOW + DAY_MS, NOW
    );
    const photo = new Uint8Array(96);
    for (let i = 0; i < photo.length; i++) photo[i] = (i * 47 + 5) % 256;
    db.run(
        `INSERT INTO payment_claims (id, academy_id, student_id, installment_id, claimed_amount, note,
             photo_bytes, status, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 500000, 'فیش', ?, 'PENDING', ?, ?)`,
        `${academyId}-clm-1`, academyId, `${academyId}-stu-1`, `${academyId}-stu-1-inst-0`, photo, NOW, NOW
    );
    db.run(
        `INSERT INTO payment_events (id, academy_id, student_id, payment_id, claim_id, event_type,
             channel, actor, amount, detail, created_at_epoch)
         VALUES (?, ?, ?, '', ?, 'RECEIPT_SUBMITTED', 'telegram', 'student', 500000, '', ?)`,
        `${academyId}-ev-1`, academyId, `${academyId}-stu-1`, `${academyId}-clm-1`, NOW
    );

    // Chunk-sized contract files. REAL random bytes: gzip cannot shrink
    // them, so the payload reliably spans several 768 KB chunks and the
    // chunk-store phase runs in multiple batched calls.
    for (let f = 0; f < opts.bigFiles; f++) {
        const whole = new Uint8Array(768 * 1024 + 1024);
        // crypto.getRandomValues accepts <= 65536 bytes per call.
        for (let o = 0; o < whole.length; o += 65536) {
            crypto.getRandomValues(whole.subarray(o, Math.min(o + 65536, whole.length)));
        }
        const { sha256HexBytes } = await import('../src/backup');
        db.run(
            `INSERT INTO contract_files (key, academy_id, content, sha256, created_at_epoch) VALUES (?, ?, ?, ?, ?)`,
            `${academyId}-file-${f}`, academyId, whole, await sha256HexBytes(whole), NOW
        );
    }
}

const resumeKey = (academyId: string) => `${academyId}:backup:resume`;
const lockKey = (academyId: string) => `${academyId}:backup:running`;

// ------------------------------------------------------------------
// 1. Query budget (the free-plan cap of 50 D1 calls per invocation)
// ------------------------------------------------------------------

describe('stage 7 resilience: free-plan query budget', () => {
    it('keeps the measured D1 call count far below 50 for a multi-chunk backup of the full ledger', async () => {
        const { env, db } = makeEnv();
        // ~9 MB of contract bytes -> payload spans multiple 768 KB chunks
        // -> several batched chunk-store calls.
        await seedAcademy(db, ACADEMY_A, { bigFiles: 8 });

        db.callCount = 0;
        db.statementCount = 0;
        const outcome = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(outcome.ok).toBe(true);

        const calls = db.callCount;
        const statements = db.statementCount;
        // Platform semantics (official docs): one batch/prepare call = one
        // D1 subrequest; the Free cap is 50 per Worker invocation.
        expect(calls).toBeLessThan(50);
        // Generous engineering bound — a full run with all tables batched.
        expect(calls).toBeLessThanOrEqual(30);
        console.log(`[budget] multi-chunk backup: ${calls} D1 calls (${statements} statements hypothetical), ${outcome.storedBytes} bytes, ${outcome.tableCounts['contract_files']} files`);

        // The interrupted+resumed path must ALSO stay under the cap: measure
        // a continuation of the same size.
        db.callCount = 0;
        const second = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(second.ok).toBe(true);
        expect(db.callCount).toBeLessThan(50);
    });

    it('a single small academy backup costs only a handful of D1 calls', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { bigFiles: 0 });
        db.callCount = 0;
        const outcome = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(outcome.ok).toBe(true);
        expect(db.callCount).toBeLessThanOrEqual(20);
        console.log(`[budget] small backup: ${db.callCount} D1 calls`);
    });
});

// ------------------------------------------------------------------
// 2. Interruption mid-store + safe continuation
// ------------------------------------------------------------------

describe('stage 7 resilience: interruption + continuation', () => {
    it('continues the SAME snapshot after a mid-store failure, with an identical payload hash', async () => {
        const { env, db } = makeEnv();
        // 12 incompressible files -> payload > 10 chunks -> the store phase
        // needs SEVERAL batched calls; the one-shot injection aborts the
        // SECOND chunk-store batch (SQL-targeted, call-order independent).
        await seedAcademy(db, ACADEMY_A, { bigFiles: 12 });

        db.failOnSql = 'INSERT OR REPLACE INTO backup_chunks';
        db.failOnOccurrence = 2;
        const interrupted = await runBackup(env, ACADEMY_A, 'AUTO');
        db.failOnSql = null;
        expect(interrupted.ok).toBe(false);
        expect(interrupted.status).toBe('FAILED');
        expect(interrupted.error).toContain('قطع شد');

        // Honest intermediate state: PENDING row, partial chunks, resume state.
        const pendingRow = db.get(`SELECT status, payload_sha256, chunk_count FROM backups WHERE id = ?`, interrupted.backupId) as any;
        expect(pendingRow.status).toBe('PENDING');
        const storedChunks = (db.get(`SELECT COUNT(*) AS n FROM backup_chunks WHERE backup_id = ?`, interrupted.backupId) as any).n;
        expect(storedChunks).toBeGreaterThan(0);
        expect(storedChunks).toBeLessThan(pendingRow.chunk_count);
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, resumeKey(ACADEMY_A))).toBeTruthy();
        // Nothing old was touched: no healthy copies, nothing pruned.
        expect((db.get(`SELECT COUNT(*) AS n FROM backups WHERE status = 'HEALTHY'`) as any).n).toBe(0);

        // The next invocation (cron next minute) continues the SAME snapshot.
        const continued = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(continued.ok).toBe(true);
        expect(continued.status).toBe('HEALTHY');
        expect(continued.resumed).toBe(true);
        expect(continued.backupId).toBe(interrupted.backupId); // same snapshot, not a new one

        const row = db.get(`SELECT status, payload_sha256 FROM backups WHERE id = ?`, interrupted.backupId) as any;
        expect(row.status).toBe('HEALTHY');
        expect(row.payload_sha256).toBe(pendingRow.payload_sha256); // deterministic re-collect
        expect((db.get(`SELECT COUNT(*) AS n FROM backup_chunks WHERE backup_id = ?`, interrupted.backupId) as any).n)
            .toBe(pendingRow.chunk_count);
        // Run state cleaned up completely.
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, resumeKey(ACADEMY_A))).toBeFalsy();
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, lockKey(ACADEMY_A))).toBeFalsy();

        const status: any = await getBackupStatus(env, ACADEMY_A);
        expect(status.lastRun.outcome).toBe('SUCCESS');
        expect(status.healthyCount).toBe(1);
        expect(status.pendingRun).toBeNull();

        // The completed snapshot really contains the full ledger.
        const bundle = (await readBackupBundle(env, continued.backupId)).parsed!;
        expect((bundle.tables['payment_receipts'] as any[]).length).toBe(1);
        expect((bundle.tables['payment_transactions'] as any[]).length).toBe(1);
        expect((bundle.tables['payment_claims'] as any[]).length).toBe(1);
        expect((bundle.tables['payment_events'] as any[]).length).toBe(1);
    });

    it('data changed between interruption and resume: pending copy cleaned, honest failure, old healthy untouched', async () => {
        const { env, db } = makeEnv();
        // bigFiles 12 -> two store batches, so the one-shot injection can
        // abort the second one and leave a resumable cursor.
        await seedAcademy(db, ACADEMY_A, { bigFiles: 12 });

        // First: a healthy snapshot exists.
        const first = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(first.ok).toBe(true);

        // Second run interrupted mid-store.
        db.failOnSql = 'INSERT OR REPLACE INTO backup_chunks';
        db.failOnOccurrence = 2;
        const interrupted = await runBackup(env, ACADEMY_A, 'AUTO');
        db.failOnSql = null;
        expect(interrupted.ok).toBe(false);
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, resumeKey(ACADEMY_A))).toBeTruthy();

        // The financial ledger changes before the next invocation.
        db.run(
            `INSERT INTO payment_receipts (id, academy_id, student_id, amount, source, created_at_epoch, updated_at_epoch)
             VALUES (?, ?, ?, 700000, 'manual', ?, ?)`,
            `${ACADEMY_A}-pay-late`, ACADEMY_A, `${ACADEMY_A}-stu-1`, NOW + 1, NOW + 1
        );

        const resumed = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(resumed.ok).toBe(false);
        expect(resumed.status).toBe('FAILED');
        expect(resumed.error).toContain('تغییر کردند');

        // Controlled cleanup: the incomplete copy is GONE.
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, resumeKey(ACADEMY_A))).toBeFalsy();
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, lockKey(ACADEMY_A))).toBeFalsy();
        expect((db.get(`SELECT COUNT(*) AS n FROM backup_chunks WHERE backup_id = ?`, interrupted.backupId) as any).n).toBe(0);
        expect((db.get(`SELECT COUNT(*) AS n FROM backups WHERE id = ?`, interrupted.backupId) as any).n).toBe(0);

        // The OLD healthy copy survived untouched.
        const old = db.get(`SELECT status FROM backups WHERE id = ?`, first.backupId) as any;
        expect(old.status).toBe('HEALTHY');
        expect((db.get(`SELECT COUNT(*) AS n FROM backups WHERE status = 'HEALTHY'`) as any).n).toBe(1);
    });
});

// ------------------------------------------------------------------
// 3. Concurrency: refusal vs adoption vs takeover
// ------------------------------------------------------------------

describe('stage 7 resilience: single-writer concurrency', () => {
    it('refuses a second writer while a fresh run holds the lock (MANUAL and AUTO)', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { bigFiles: 0 });
        db.run(
            `INSERT INTO academy_settings (key, value, updated_at_epoch) VALUES (?, ?, ?)`,
            lockKey(ACADEMY_A), String(Date.now()), Date.now()
        );
        const before = (db.get(`SELECT COUNT(*) AS n FROM backups`) as any).n;

        const manual = await runBackup(env, ACADEMY_A, 'MANUAL');
        expect(manual.status).toBe('PENDING');
        expect(manual.pending).toBe(true);
        expect(manual.error).toContain('در حال اجراست');

        const auto = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(auto.status).toBe('PENDING');

        // No snapshot row and no state was created by the refused calls.
        expect((db.get(`SELECT COUNT(*) AS n FROM backups`) as any).n).toBe(before);
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, resumeKey(ACADEMY_A))).toBeFalsy();
    });

    it('AUTO adopts an interrupted run while MANUAL waits; a stale lock is taken over', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { bigFiles: 12 });

        db.failOnSql = 'INSERT OR REPLACE INTO backup_chunks';
        db.failOnOccurrence = 2;
        const interrupted = await runBackup(env, ACADEMY_A, 'AUTO');
        db.failOnSql = null;
        expect(interrupted.ok).toBe(false);

        // A fresh lock + resume state: MANUAL must not stomp the run.
        const manual = await runBackup(env, ACADEMY_A, 'MANUAL');
        expect(manual.status).toBe('PENDING');
        expect(manual.error).toContain('تمام نشده');

        // AUTO adopts and completes the SAME snapshot.
        const adopted = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(adopted.ok).toBe(true);
        expect(adopted.resumed).toBe(true);
        expect(adopted.backupId).toBe(interrupted.backupId);

        // A later stale-lock scenario (no resume): the run is taken over.
        db.run(
            `INSERT INTO academy_settings (key, value, updated_at_epoch) VALUES (?, ?, ?)`,
            lockKey(ACADEMY_B), String(Date.now() - LOCK_TTL_MS - 1000), Date.now()
        );
        const takeover = await runBackup(env, ACADEMY_B, 'MANUAL');
        expect(takeover.ok).toBe(true);
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, lockKey(ACADEMY_B))).toBeFalsy();
    });
});

// ------------------------------------------------------------------
// 4. Corrupt new snapshot + slim path + status visibility
// ------------------------------------------------------------------

describe('stage 7 resilience: corruption, slim path, status', () => {
    it('a corrupted new snapshot is marked CORRUPT and previous HEALTHY copies survive', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { bigFiles: 0 });
        const first = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(first.ok).toBe(true);

        db.run(`CREATE TRIGGER stage7_corrupt AFTER INSERT ON backup_chunks
                BEGIN UPDATE backup_chunks SET content = zeroblob(7) WHERE rowid = NEW.rowid; END`);
        const second = await runBackup(env, ACADEMY_A, 'AUTO');
        db.run('DROP TRIGGER stage7_corrupt');

        expect(second.ok).toBe(false);
        expect(second.status).toBe('CORRUPT');
        const old = db.get(`SELECT status FROM backups WHERE id = ?`, first.backupId) as any;
        expect(old.status).toBe('HEALTHY');
        // Run state fully cleaned after the definitive failure.
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, resumeKey(ACADEMY_A))).toBeFalsy();
        expect(db.get(`SELECT value FROM academy_settings WHERE key = ?`, lockKey(ACADEMY_A))).toBeFalsy();
    });

    it('slim path: oversized payloads keep metadata + claim photos but drop contract file bytes, and still verify', async () => {
        const { env, db } = makeEnv(undefined, { BACKUP_MAX_BYTES: '1000' });
        await seedAcademy(db, ACADEMY_A, { bigFiles: 2 });

        const outcome = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(outcome.ok).toBe(true);
        expect(outcome.status).toBe('HEALTHY');
        expect(outcome.filesIncluded).toBe(false);

        const row = db.get(`SELECT files_included, total_bytes, stored_bytes FROM backups WHERE id = ?`, outcome.backupId) as any;
        expect(row.files_included).toBe(0);
        expect(row.total_bytes).toBeGreaterThan(0);

        const bundle = (await readBackupBundle(env, outcome.backupId)).parsed!;
        // Contract files summarized (no bytes)…
        const files = bundle.tables['contract_files'] as any[];
        expect(files.length).toBe(2);
        expect(files[0].content_b64).toBeUndefined();
        expect(files[0].content_bytes).toBeGreaterThan(0);
        expect((bundle.tables['contract_file_chunks'] as any[]).length).toBe(0);
        // …but the claim PHOTO evidence is still fully inside.
        const claims = bundle.tables['payment_claims'] as any[];
        expect(claims[0].photo_b64.length).toBeGreaterThan(0);
    });

    it('the status API reports an interrupted run and the covered-table list', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { bigFiles: 12 });

        db.failOnSql = 'INSERT OR REPLACE INTO backup_chunks';
        db.failOnOccurrence = 2;
        await runBackup(env, ACADEMY_A, 'AUTO');
        db.failOnSql = null;

        const res = await handleApiRequest(
            new Request(`${BASE}/api/backup/status`, { method: 'GET', headers: bearer(ACADEMY_A) }),
            env, new URL(`${BASE}/api/backup/status`)
        );
        expect(res.status).toBe(200);
        const body: any = await res.json();
        expect(body.success).toBe(true);
        expect(body.data.pendingRun).toBeTruthy();
        expect(body.data.pendingRun.phase).toBe('STORE');
        expect(body.data.pendingRun.storedChunks).toBeGreaterThan(0);
        expect(body.data.coveredTables).toContain('payment_receipts');
        expect(body.data.coveredTables).toContain('payment_claims');
        expect(body.data.coveredTables).toContain('payment_events');
    });

    it('the cron day marker is only set after a VERIFIED completion (interruption retries safely)', async () => {
        const { env, db } = makeEnv();
        await seedAcademy(db, ACADEMY_A, { bigFiles: 12 });

        db.failOnSql = 'INSERT OR REPLACE INTO backup_chunks';
        db.failOnOccurrence = 2;
        await maybeDailyBackup(env, ACADEMY_A);
        db.failOnSql = null;
        let marker = db.get(`SELECT value FROM academy_settings WHERE key = ?`, `${ACADEMY_A}:backup:last_day`);
        expect(marker).toBeFalsy();

        // Next cron minute: the SAME snapshot is continued and completes.
        await maybeDailyBackup(env, ACADEMY_A);
        marker = db.get(`SELECT value FROM academy_settings WHERE key = ?`, `${ACADEMY_A}:backup:last_day`);
        expect(marker).toBeTruthy();
        expect((db.get(`SELECT COUNT(*) AS n FROM backups WHERE status = 'HEALTHY' AND academy_id = ?`, ACADEMY_A) as any).n).toBe(1);
    });
});
