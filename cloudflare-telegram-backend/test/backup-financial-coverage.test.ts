// ============================================================
// Stage 7 financial coverage: the backup must carry the COMPLETE
// stage-4/5 financial ledger, not just installments/summaries.
//
// Proves, without any real bot/network/production database:
//   1. the snapshot covers payment_receipts (incl. the link to the
//      main payment + reversal state), payment_transactions (gateway
//      orders + final status + verification payload),
//      payment_link_tokens, payment_claims (incl. the PHOTO evidence
//      bytes) and payment_events (the approve/reject audit history),
//      plus contract_pending_links found during the stage-3 match;
//   2. a restore into a FRESH schema reproduces every financial row:
//      identical counts, sums, status distributions, UNIQUE ids,
//      intact id relations (receipt<->payment<->transaction<->token,
//      claim<->installment, event<->claim/payment) and byte-equal
//      claim photos;
//   3. tenant isolation extends to the financial tables;
//   4. verify-before-replace includes the new financial sums.
// ============================================================

import { describe, it, expect } from 'vitest';
import { runBackup, readBackupBundle, restoreBundleForTests, sha256HexBytes, verifyBundleAgainstLive, BACKUP_TABLES } from '../src/backup';
import type { FinancialChecks } from '../src/backup';
import { D1Shim } from './helpers/d1-shim';
import { makeEnv, ACADEMY_A, ACADEMY_B } from './helpers/fixtures';

const DAY_MS = 24 * 60 * 60 * 1000;
const NOW = Date.now();

// ------------------------------------------------------------------
// Seed: full financial history for one academy (same rows the app and
// bot produce — ids and relations follow migrations 0009/0010).
// ------------------------------------------------------------------

async function seedBase(db: D1Shim, academyId: string, students: number): Promise<void> {
    db.run(
        `INSERT OR IGNORE INTO academies (id, name, created_at_epoch) VALUES (?, 'آموزشگاه مالی', ?)`,
        academyId, NOW
    );
    for (let s = 0; s < students; s++) {
        const sid = `${academyId}-stu-${s}`;
        db.run(
            `INSERT INTO students (id, student_code, full_name, phone, national_code, telegram_chat_id,
                 telegram_username, is_telegram_connected, linked_at_epoch, created_at_epoch, updated_at_epoch,
                 academy_id, telegram_link_hash)
             VALUES (?, ?, ?, '09120000000', '0000000000', '', '', 0, 0, ?, ?, ?, ?)`,
            sid, `${2000 + s}`, `هنرجو ${s}`, NOW, NOW, academyId, `hash-${s}`
        );
        for (let i = 0; i < 2; i++) {
            const amount = 500_000 + i * 200_000;
            db.run(
                `INSERT INTO installments (id, student_id, installment_number, total_installments, course_title,
                     amount, paid_amount, remaining_amount, due_date_shamsi, due_date_epoch, is_paid,
                     paid_date_shamsi, updated_at_epoch, academy_id, payment_method)
                 VALUES (?, ?, ?, 2, 'دوره سنتور', ?, ?, ?, '1404/08/01', ?, 0, '', ?, ?, ?)`,
                `${sid}-inst-${i}`, sid, i + 1, amount, amount, amount,
                NOW + i * DAY_MS, NOW, academyId, i === 0 ? 'ONLINE' : ''
            );
        }
    }
}

async function seedFinancialLedger(db: D1Shim, academyId: string): Promise<void> {
    const stu0 = `${academyId}-stu-0`;
    const stu1 = `${academyId}-stu-1`;
    const inst00 = `${academyId}-stu-0-inst-0`;
    const inst10 = `${academyId}-stu-1-inst-0`;

    // --- payment_receipts: manual paid + online paid + REVERSED ---
    db.run(
        `INSERT INTO payment_receipts (id, academy_id, student_id, installment_id, installment_number,
             course_title, transaction_id, tracking_number, amount, student_name, paid_date_shamsi, paid_time,
             payment_method, destination_account, notes, is_reversed, reversal_reason, source, receipt_json,
             send_status, send_reason, eligible_at_epoch, attempts, last_attempt_epoch, telegram_message_id,
             last_error, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 1, 'دوره سنتور', '', 'TRK-1001', 500000, 'هنرجو ۰', '1404/08/01', '10:30',
                 'CASH', 'صندوق', '', 0, '', 'manual', '{"amount":500000,"method":"CASH"}',
                 'SENT', 'payment', ?, 1, ?, 'tg-msg-1', '', ?, ?)`,
        `${academyId}-pay-1`, academyId, stu0, inst00, NOW, NOW, NOW, NOW
    );
    db.run(
        `INSERT INTO payment_receipts (id, academy_id, student_id, installment_id, installment_number,
             course_title, transaction_id, tracking_number, amount, student_name, paid_date_shamsi, paid_time,
             payment_method, destination_account, notes, is_reversed, reversal_reason, source, receipt_json,
             send_status, send_reason, eligible_at_epoch, attempts, last_attempt_epoch, telegram_message_id,
             last_error, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 1, 'دوره سنتور', ?, 'TRK-1002', 700000, 'هنرجو ۱', '1404/08/02', '12:00',
                 'ONLINE', 'درگاه', '', 0, '', 'online', '{"amount":700000,"ref":"AUTH-9"}',
                 'QUEUED', 'online', ?, 0, 0, '', '', ?, ?)`,
        `${academyId}-pay-2`, academyId, stu1, inst10, `${academyId}-order-1`, NOW, NOW, NOW
    );
    db.run(
        `INSERT INTO payment_receipts (id, academy_id, student_id, installment_id, installment_number,
             course_title, transaction_id, tracking_number, amount, student_name, paid_date_shamsi, paid_time,
             payment_method, destination_account, notes, is_reversed, reversal_reason, source, receipt_json,
             send_status, send_reason, eligible_at_epoch, attempts, last_attempt_epoch, telegram_message_id,
             last_error, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 1, 'دوره سنتور', '', 'TRK-1003', 300000, 'هنرجو ۰', '1404/08/03', '09:15',
                 'TRANSFER', 'کارت ۶۰۳۷', 'ثبت اشتباه', 1, 'اشتباه در ثبت مبلغ', 'manual',
                 '{"amount":300000,"reversed":true}', 'REVERSED', 'payment', 0, 1, ?, 'tg-msg-3', '', ?, ?)`,
        `${academyId}-pay-3`, academyId, stu0, inst00, NOW, NOW, NOW
    );
    // Claim approval registers a REAL payment with the deterministic id
    // 'clm-<claim id>' (migration 0010) — itself a payment_receipts row.
    db.run(
        `INSERT INTO payment_receipts (id, academy_id, student_id, installment_id, installment_number,
             course_title, transaction_id, tracking_number, amount, student_name, paid_date_shamsi, paid_time,
             payment_method, destination_account, notes, is_reversed, reversal_reason, source, receipt_json,
             send_status, send_reason, eligible_at_epoch, attempts, last_attempt_epoch, telegram_message_id,
             last_error, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 1, 'دوره سنتور', '', 'TRK-1004', 700000, 'هنرجو ۱', '1404/08/04', '18:40',
                 'TRANSFER', 'کارت ۶۰۳۷', 'تأیید ادعای کارت به کارت', 0, '', 'manual',
                 '{"amount":700000,"via":"claim"}', 'QUEUED', 'payment', ?, 0, 0, '', '', ?, ?)`,
        `clm-${academyId}-clm-2`, academyId, stu1, inst10, NOW, NOW, NOW
    );

    // --- payment_transactions: one PAID (linked to its receipt) + one PENDING ---
    db.run(
        `INSERT INTO payment_transactions (id, academy_id, student_id, installment_id, amount, status,
             provider, provider_ref, link_token_hash, redirect_url, link_created_at_epoch, expires_at_epoch,
             paid_amount, verified_payload, verified_at_epoch, receipt_id, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 700000, 'PAID', 'zarinpal', 'AUTH-9', 'h1', 'https://pay.test/1', ?, ?,
                 700000, '{"ref":"AUTH-9","code":100}', ?, ?, ?, ?)`,
        `${academyId}-order-1`, academyId, stu1, inst10,
        NOW - 3600_000, NOW + DAY_MS, NOW - 1800_000, `${academyId}-pay-2`, NOW - 3600_000, NOW
    );
    db.run(
        `INSERT INTO payment_transactions (id, academy_id, student_id, installment_id, amount, status,
             provider, provider_ref, link_token_hash, redirect_url, link_created_at_epoch, expires_at_epoch,
             paid_amount, verified_payload, verified_at_epoch, receipt_id, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 500000, 'PENDING', 'none', '', 'h2', 'https://pay.test/2', ?, ?, 0, '', 0, '', ?, ?)`,
        `${academyId}-order-2`, academyId, stu0, inst00, NOW, NOW + DAY_MS, NOW, NOW
    );

    // --- payment_link_tokens: the RAW capability token of the paid order ---
    db.run(
        `INSERT INTO payment_link_tokens (token, token_hash, academy_id, order_id, expires_at_epoch, created_at_epoch)
         VALUES (?, 'h1', ?, ?, ?, ?)`,
        `raw-token-${academyId}`, academyId, `${academyId}-order-1`, NOW + DAY_MS, NOW
    );

    // --- payment_claims: PENDING (with photo evidence) + APPROVED + REJECTED ---
    const photo = new Uint8Array(160);
    for (let i = 0; i < photo.length; i++) photo[i] = (i * 89 + 13) % 256; // includes 0x80..0xFF
    db.run(
        `INSERT INTO payment_claims (id, academy_id, student_id, student_name, installment_id, installment_number,
             course_title, claimed_amount, note, photo_bytes, photo_file_id, status, decided_by, decision_note,
             decided_at_epoch, payment_id, telegram_chat_id, manager_message_id, manager_chat_id,
             created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, 'هنرجو ۰', ?, 1, 'دوره سنتور', 500000, 'فیش واریزی', ?, 'photo-file-1',
                 'PENDING', '', '', 0, '', 'chat-1', 'mgr-msg-1', 'mgr-chat', ?, ?)`,
        `${academyId}-clm-1`, academyId, stu0, inst00, photo, NOW, NOW
    );
    db.run(
        `INSERT INTO payment_claims (id, academy_id, student_id, student_name, installment_id, installment_number,
             course_title, claimed_amount, note, photo_bytes, photo_file_id, status, decided_by, decision_note,
             decided_at_epoch, payment_id, telegram_chat_id, manager_message_id, manager_chat_id,
             created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, 'هنرجو ۱', ?, 1, 'دوره سنتور', 700000, 'کارت به کارت', NULL, 'photo-file-2',
                 'APPROVED', 'mgr-chat', '', ?, ?, 'chat-2', 'mgr-msg-2', 'mgr-chat', ?, ?)`,
        `${academyId}-clm-2`, academyId, stu1, inst10, NOW - 1000, `clm-${academyId}-clm-2`, NOW - 950, NOW - 900
    );
    db.run(
        `INSERT INTO payment_claims (id, academy_id, student_id, student_name, installment_id, installment_number,
             course_title, claimed_amount, note, photo_bytes, photo_file_id, status, decided_by, decision_note,
             decided_at_epoch, payment_id, telegram_chat_id, manager_message_id, manager_chat_id,
             created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, 'هنرجو ۰', ?, 2, 'دوره سنتور', 200000, 'مبلغ ناقص', NULL, '',
                 'REJECTED', 'mgr-chat', 'رسید ناخوانا است', ?, '', 'chat-1', 'mgr-msg-3', 'mgr-chat', ?, ?)`,
        `${academyId}-clm-3`, academyId, stu0, `${academyId}-stu-0-inst-1`, NOW - 800, NOW - 750, NOW - 700
    );

    // --- payment_events: full approve/reject/register/reverse/verify history ---
    const events: Array<[string, string, string, string, string, string, number]> = [
        [`${academyId}-ev-1`, 'RECEIPT_SUBMITTED', 'telegram', 'student', `${academyId}-clm-1`, '', 500000],
        [`${academyId}-ev-2`, 'RECEIPT_APPROVED', 'telegram', 'mgr-chat', `${academyId}-clm-2`, `clm-${academyId}-clm-2`, 700000],
        [`${academyId}-ev-3`, 'RECEIPT_REJECTED', 'telegram', 'mgr-chat', `${academyId}-clm-3`, '', 200000],
        [`${academyId}-ev-4`, 'PAYMENT_REGISTERED', 'app', 'mgr-chat', '', `${academyId}-pay-1`, 500000],
        [`${academyId}-ev-5`, 'PAYMENT_REVERSED', 'app', 'mgr-chat', '', `${academyId}-pay-3`, 300000],
        [`${academyId}-ev-6`, 'ONLINE_VERIFIED', 'gateway', 'system', '', `${academyId}-pay-2`, 700000]
    ];
    for (const [id, type, channel, actor, claimId, paymentId, amount] of events) {
        db.run(
            `INSERT INTO payment_events (id, academy_id, student_id, installment_id, payment_id, claim_id,
                 event_type, channel, actor, amount, detail, created_at_epoch)
             VALUES (?, ?, ?, '', ?, ?, ?, ?, ?, ?, 'رویداد آزمایشی', ?)`,
            id, academyId, claimId === '' ? stu0 : (claimId === `${academyId}-clm-2` ? stu1 : stu0),
            paymentId, claimId, type, channel, actor, amount, NOW
        );
    }

    // --- Stage-3 completeness: an unsigned contract keeps its resendable link ---
    db.run(
        `INSERT INTO contracts (id, academy_id, enrollment_id, student_id, student_name, course_title,
             status, token_hash, expires_at_epoch, issued_at_epoch, pdf_key, pdf_sha256,
             enrollment_json, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 'هنرجو ۰', 'دوره سنتور', 'ISSUED', 'ctok', ?, ?, '', '', '{}', ?, ?)`,
        `${academyId}-contract-0`, academyId, `${academyId}-enr-0`, stu0,
        NOW + DAY_MS, NOW, NOW, NOW
    );
    db.run(
        `INSERT INTO contract_pending_links (contract_id, academy_id, sha256, token, expires_at_epoch)
         VALUES (?, ?, 'sha-ct', 'raw-contract-token', ?)`,
        `${academyId}-contract-0`, academyId, NOW + DAY_MS
    );
}

async function seedAcademyBIsolation(db: D1Shim): Promise<void> {
    await seedBase(db, ACADEMY_B, 1);
    db.run(
        `INSERT INTO payment_receipts (id, academy_id, student_id, amount, source, created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, 111, 'manual', ?, ?)`,
        `${ACADEMY_B}-pay-x`, ACADEMY_B, `${ACADEMY_B}-stu-0`, NOW, NOW
    );
    db.run(
        `INSERT INTO payment_claims (id, academy_id, student_id, installment_id, claimed_amount, status,
             created_at_epoch, updated_at_epoch)
         VALUES (?, ?, ?, ?, 222, 'PENDING', ?, ?)`,
        `${ACADEMY_B}-clm-x`, ACADEMY_B, `${ACADEMY_B}-stu-0`, `${ACADEMY_B}-stu-0-inst-0`, NOW, NOW
    );
}

// ------------------------------------------------------------------
// 1. Coverage of the snapshot itself
// ------------------------------------------------------------------

describe('stage 7 financial coverage: snapshot contents', () => {
    it('includes receipts, gateway transactions, link tokens, claims and audit events', async () => {
        const { env, db } = makeEnv();
        await seedBase(db, ACADEMY_A, 2);
        await seedFinancialLedger(db, ACADEMY_A);

        const outcome = await runBackup(env, ACADEMY_A, 'MANUAL');
        expect(outcome.ok).toBe(true);
        expect(outcome.status).toBe('HEALTHY');

        expect(outcome.tableCounts['payment_receipts']).toBe(4);
        expect(outcome.tableCounts['payment_transactions']).toBe(2);
        expect(outcome.tableCounts['payment_link_tokens']).toBe(1);
        expect(outcome.tableCounts['payment_claims']).toBe(3);
        expect(outcome.tableCounts['payment_events']).toBe(6);
        expect(outcome.tableCounts['contract_pending_links']).toBe(1);
        expect(outcome.tableCounts['installments']).toBe(4);

        // The covered-table list itself names every stage-2..7 table.
        const covered = BACKUP_TABLES.map((t) => t.table);
        for (const t of ['payment_receipts', 'payment_transactions', 'payment_link_tokens',
            'payment_claims', 'payment_events', 'contract_pending_links', 'academy_settings']) {
            expect(covered).toContain(t);
        }
        // Only the backup machinery is excluded — nothing else.
        expect(covered).not.toContain('backups');
        expect(covered).not.toContain('backup_chunks');
        expect(covered).not.toContain('backup_runs');

        const bundle = (await readBackupBundle(env, outcome.backupId)).parsed!;

        // Receipts: reversal state + the link to the gateway order survive.
        const receipts = bundle.tables['payment_receipts'] as any[];
        const reversed = receipts.find((r) => r.id === `${ACADEMY_A}-pay-3`);
        expect(reversed.is_reversed).toBe(1);
        expect(reversed.reversal_reason).toBe('اشتباه در ثبت مبلغ');
        const online = receipts.find((r) => r.id === `${ACADEMY_A}-pay-2`);
        expect(online.transaction_id).toBe(`${ACADEMY_A}-order-1`);
        expect(JSON.parse(online.receipt_json).ref).toBe('AUTH-9');

        // Gateway transactions: FINAL status + verification audit payload.
        const txns = bundle.tables['payment_transactions'] as any[];
        expect(txns.find((t) => t.id === `${ACADEMY_A}-order-1`).status).toBe('PAID');
        expect(txns.find((t) => t.id === `${ACADEMY_A}-order-1`).receipt_id).toBe(`${ACADEMY_A}-pay-2`);
        expect(JSON.parse(txns.find((t) => t.id === `${ACADEMY_A}-order-1`).verified_payload).code).toBe(100);
        expect(txns.find((t) => t.id === `${ACADEMY_A}-order-2`).status).toBe('PENDING');

        // The raw link token (needed so reminders can re-send the SAME link).
        expect((bundle.tables['payment_link_tokens'] as any[])[0].order_id).toBe(`${ACADEMY_A}-order-1`);

        // Claims: statuses + the PHOTO EVIDENCE bytes (base64) are inside.
        const claims = bundle.tables['payment_claims'] as any[];
        expect(claims.find((c) => c.id === `${ACADEMY_A}-clm-1`).status).toBe('PENDING');
        expect(claims.find((c) => c.id === `${ACADEMY_A}-clm-2`).status).toBe('APPROVED');
        expect(claims.find((c) => c.id === `${ACADEMY_A}-clm-3`).decision_note).toBe('رسید ناخوانا است');
        const photoB64 = claims.find((c) => c.id === `${ACADEMY_A}-clm-1`).photo_b64;
        expect(photoB64.length).toBeGreaterThan(0);

        // Events: the approve/reject history with claim/payment references.
        const events = bundle.tables['payment_events'] as any[];
        expect(events.length).toBe(6);
        expect(events.find((e) => e.id === `${ACADEMY_A}-ev-2`).event_type).toBe('RECEIPT_APPROVED');
        expect(events.find((e) => e.id === `${ACADEMY_A}-ev-2`).claim_id).toBe(`${ACADEMY_A}-clm-2`);
        expect(events.find((e) => e.id === `${ACADEMY_A}-ev-5`).event_type).toBe('PAYMENT_REVERSED');
    });
});

// ------------------------------------------------------------------
// 2. Recovery equality (THE mandated test): restore + compare
// ------------------------------------------------------------------

describe('stage 7 financial coverage: recovery equality', () => {
    it('restores the financial ledger into a fresh schema with identical rows, sums, relations, unique ids and photo bytes', async () => {
        const { env, db } = makeEnv();
        await seedBase(db, ACADEMY_A, 2);
        await seedFinancialLedger(db, ACADEMY_A);
        const originalPhoto = db.get(
            `SELECT photo_bytes FROM payment_claims WHERE id = ?`, `${ACADEMY_A}-clm-1`
        ) as any;

        const outcome = await runBackup(env, ACADEMY_A, 'MANUAL');
        expect(outcome.ok).toBe(true);
        const bundle = (await readBackupBundle(env, outcome.backupId)).parsed!;

        const fresh = new D1Shim();
        const { loadMigrations } = await import('./helpers/fixtures');
        fresh.applyMigrations(loadMigrations());
        const restored = await restoreBundleForTests(fresh as unknown as D1Database, bundle, true);

        // --- every financial table restored with the same row count ---
        for (const table of ['payment_receipts', 'payment_transactions', 'payment_link_tokens',
            'payment_claims', 'payment_events', 'contract_pending_links']) {
            expect(restored[table]).toBe(outcome.tableCounts[table]);
        }

        // --- financial sums survive byte-for-byte ---
        const sumPairs: Array<[string, string]> = [
            ['payment_receipts', 'amount'],
            ['payment_transactions', 'paid_amount'],
            ['payment_claims', 'claimed_amount']
        ];
        for (const [table, col] of sumPairs) {
            const live = db.get(`SELECT COALESCE(SUM(${col}),0) s FROM ${table} WHERE academy_id = ?`, ACADEMY_A) as any;
            const back = fresh.get(`SELECT COALESCE(SUM(${col}),0) s FROM ${table} WHERE academy_id = ?`, ACADEMY_A) as any;
            expect(back.s).toBe(live.s);
        }

        // --- status distributions (final statuses of every transaction) ---
        for (const [table, col] of [['payment_transactions', 'status'], ['payment_claims', 'status'], ['payment_receipts', 'send_status']] as const) {
            const live = db.get(
                `SELECT COALESCE(SUM(CASE WHEN ${col} = 'PAID' THEN 1 ELSE 0 END),0) paid,
                        COALESCE(SUM(CASE WHEN ${col} = 'PENDING' THEN 1 ELSE 0 END),0) pending,
                        COALESCE(SUM(CASE WHEN ${col} = 'REJECTED' THEN 1 ELSE 0 END),0) rejected,
                        COALESCE(SUM(CASE WHEN ${col} = 'APPROVED' THEN 1 ELSE 0 END),0) approved
                 FROM ${table} WHERE academy_id = ?`, ACADEMY_A) as any;
            const back = fresh.get(
                `SELECT COALESCE(SUM(CASE WHEN ${col} = 'PAID' THEN 1 ELSE 0 END),0) paid,
                        COALESCE(SUM(CASE WHEN ${col} = 'PENDING' THEN 1 ELSE 0 END),0) pending,
                        COALESCE(SUM(CASE WHEN ${col} = 'REJECTED' THEN 1 ELSE 0 END),0) rejected,
                        COALESCE(SUM(CASE WHEN ${col} = 'APPROVED' THEN 1 ELSE 0 END),0) approved
                 FROM ${table} WHERE academy_id = ?`, ACADEMY_A) as any;
            expect([back.paid, back.pending, back.rejected, back.approved])
                .toEqual([live.paid, live.pending, live.rejected, live.approved]);
        }

        // --- UNIQUE ids: COUNT(*) == COUNT(DISTINCT id) in every ledger table ---
        for (const table of ['payment_receipts', 'payment_transactions', 'payment_claims', 'payment_events']) {
            const row = fresh.get(`SELECT COUNT(*) c, COUNT(DISTINCT id) d FROM ${table}`) as any;
            expect(row.c).toBe(row.d);
        }

        // --- RELATIONS survive: no orphans anywhere in the ledger ---
        const orphanChecks: Array<[string, string, string, string]> = [
            // [child table, child col, parent table, parent col]
            ['payment_receipts', 'student_id', 'students', 'id'],
            ['payment_receipts', 'installment_id', 'installments', 'id'],
            ['payment_transactions', 'student_id', 'students', 'id'],
            ['payment_transactions', 'installment_id', 'installments', 'id'],
            ['payment_claims', 'student_id', 'students', 'id'],
            ['payment_claims', 'installment_id', 'installments', 'id'],
            ['payment_link_tokens', 'order_id', 'payment_transactions', 'id'],
            ['contract_pending_links', 'contract_id', 'contracts', 'id']
        ];
        for (const [child, childCol, parent, parentCol] of orphanChecks) {
            const row = fresh.get(
                `SELECT COUNT(*) AS n FROM ${child} c LEFT JOIN ${parent} p ON p.${parentCol} = c.${childCol}
                 WHERE p.${parentCol} IS NULL`
            ) as any;
            expect(row.n).toBe(0);
        }
        // Conditional references: transaction->receipt, event->claim, event->payment/receipt
        const brokenTxn = fresh.get(
            `SELECT COUNT(*) AS n FROM payment_transactions t WHERE t.receipt_id != ''
             AND NOT EXISTS (SELECT 1 FROM payment_receipts r WHERE r.id = t.receipt_id)`
        ) as any;
        expect(brokenTxn.n).toBe(0);
        const brokenEvClaim = fresh.get(
            `SELECT COUNT(*) AS n FROM payment_events e WHERE e.claim_id != ''
             AND NOT EXISTS (SELECT 1 FROM payment_claims c WHERE c.id = e.claim_id)`
        ) as any;
        expect(brokenEvClaim.n).toBe(0);
        const brokenEvPay = fresh.get(
            `SELECT COUNT(*) AS n FROM payment_events e WHERE e.payment_id != ''
             AND NOT EXISTS (SELECT 1 FROM payment_receipts r WHERE r.id = e.payment_id)`
        ) as any;
        expect(brokenEvPay.n).toBe(0);
        // Receipt -> gateway order link
        const brokenReceiptTxn = fresh.get(
            `SELECT COUNT(*) AS n FROM payment_receipts r WHERE r.transaction_id != ''
             AND NOT EXISTS (SELECT 1 FROM payment_transactions t WHERE t.id = r.transaction_id)`
        ) as any;
        expect(brokenReceiptTxn.n).toBe(0);

        // --- claim PHOTO bytes round-trip byte-equal ---
        const restoredPhoto = fresh.get(
            `SELECT photo_bytes FROM payment_claims WHERE id = ?`, `${ACADEMY_A}-clm-1`
        ) as any;
        expect(restoredPhoto.photo_bytes).toBeTruthy();
        expect(await sha256HexBytes(new Uint8Array(restoredPhoto.photo_bytes)))
            .toBe(await sha256HexBytes(new Uint8Array(originalPhoto.photo_bytes)));

        // --- reversal state + decisions survive verbatim ---
        const rev = fresh.get(
            `SELECT is_reversed, reversal_reason, send_status FROM payment_receipts WHERE id = ?`,
            `${ACADEMY_A}-pay-3`
        ) as any;
        expect(rev.is_reversed).toBe(1);
        expect(rev.reversal_reason).toBe('اشتباه در ثبت مبلغ');
        expect(rev.send_status).toBe('REVERSED');
        const approved = fresh.get(
            `SELECT status, payment_id, decided_by FROM payment_claims WHERE id = ?`,
            `${ACADEMY_A}-clm-2`
        ) as any;
        expect(approved.status).toBe('APPROVED');
        expect(approved.decided_by).toBe('mgr-chat');
    });
});

// ------------------------------------------------------------------
// 3. Tenant isolation of the financial tables
// ------------------------------------------------------------------

describe('stage 7 financial coverage: tenant isolation', () => {
    it('keeps academy B receipts/claims out of academy A backup payloads', async () => {
        const { env, db } = makeEnv();
        await seedBase(db, ACADEMY_A, 2);
        await seedFinancialLedger(db, ACADEMY_A);
        await seedAcademyBIsolation(db);

        const outcome = await runBackup(env, ACADEMY_A, 'AUTO');
        expect(outcome.ok).toBe(true);
        const bundle = (await readBackupBundle(env, outcome.backupId)).parsed!;
        const serialized = JSON.stringify(bundle);
        expect(serialized).not.toContain(`${ACADEMY_B}-pay-x`);
        expect(serialized).not.toContain(`${ACADEMY_B}-clm-x`);
        expect(serialized).not.toContain(`${ACADEMY_B}-stu-0`);
        expect(bundle.academyId).toBe(ACADEMY_A);
        expect(outcome.tableCounts['payment_receipts']).toBe(4); // A only, not B's
        expect(outcome.tableCounts['payment_claims']).toBe(3);
    });
});

// ------------------------------------------------------------------
// 4. verify-before-replace includes the stage-4/5 sums
// ------------------------------------------------------------------

describe('stage 7 financial coverage: verification of the new sums', () => {
    it('rejects a bundle whose financial ledger sums do not match the live data', async () => {
        const { env, db } = makeEnv();
        await seedBase(db, ACADEMY_A, 2);
        await seedFinancialLedger(db, ACADEMY_A);

        const outcome = await runBackup(env, ACADEMY_A, 'MANUAL');
        expect(outcome.ok).toBe(true);
        const bundle = (await readBackupBundle(env, outcome.backupId)).parsed!;
        const liveCounts = outcome.tableCounts;

        // Control: the untampered pair verifies.
        const ok = verifyBundleAgainstLive(bundle, ACADEMY_A, liveCounts, bundle.financialChecks);
        expect(ok.ok).toBe(true);

        // Tamper the RECEIPT count -> verification must fail.
        const tampered1: BackupBundle = JSON.parse(JSON.stringify(bundle));
        (tampered1.financialChecks as FinancialChecks).receiptCount += 1;
        const v1 = verifyBundleAgainstLive(tampered1, ACADEMY_A, liveCounts, bundle.financialChecks);
        expect(v1.ok).toBe(false);
        expect(v1.error).toContain('رسید');

        // Tamper the CLAIM sum -> verification must fail.
        const tampered2: BackupBundle = JSON.parse(JSON.stringify(bundle));
        (tampered2.financialChecks as FinancialChecks).sumClaimAmount += 1000;
        const v2 = verifyBundleAgainstLive(tampered2, ACADEMY_A, liveCounts, bundle.financialChecks);
        expect(v2.ok).toBe(false);
        expect(v2.error).toContain('ادعا');

        // Tamper the EVENT count -> verification must fail.
        const tampered3: BackupBundle = JSON.parse(JSON.stringify(bundle));
        (tampered3.financialChecks as FinancialChecks).eventCount -= 1;
        const v3 = verifyBundleAgainstLive(tampered3, ACADEMY_A, liveCounts, bundle.financialChecks);
        expect(v3.ok).toBe(false);
        expect(v3.error).toContain('رویداد');
    });
});
