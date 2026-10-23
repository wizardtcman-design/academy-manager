// ============================================================
// Stage 8 hardening: regression tests for the defects found during
// the stage-8 integration review and fixed in place:
//   1. tenant-guarded upserts (students / installments / summaries)
//   2. reserved backup keys refused on /api/sync/settings
//   3. confirmed-payment pull reports the REAL applied amount
//   4. epochToShamsi uses the Tehran calendar day, not UTC
//   5. template validation refuses a lone '<' (Telegram HTML killer)
//   6. manager claim notifications escape student-controlled strings
//   7. gateway verify transition is a real CAS (PENDING -> PAID only)
// ============================================================

import { describe, it, expect, beforeAll } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { AcademyDatabase } from '../src/db';
import { epochToShamsi } from '../src/shamsi';
import { validateTemplateText } from '../src/messaging';
import { renderClaimNotification } from '../src/claims';
import { D1Shim } from './helpers/d1-shim';
import { makeEnv, ACADEMY_A, ACADEMY_B, API_KEY } from './helpers/fixtures';
import type { Env } from '../src/types';
import type { StudentRecord, InstallmentRecord, FinancialSummaryRecord, PaymentClaimRecord } from '../src/types';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);

function auth(): Record<string, string> {
    return { Authorization: `Bearer ${API_KEY}`, 'X-Academy-Id': ACADEMY_A, 'Content-Type': 'application/json' };
}

async function api(env: Env, method: string, path: string, body?: unknown): Promise<Response> {
    return handleApiRequest(
        new Request(`${BASE}${path}`, {
            method,
            headers: auth(),
            body: body === undefined ? undefined : JSON.stringify(body)
        }),
        env, URL_(path)
    );
}

// ============================================================
// 1. Tenant-guarded upserts
// ============================================================
describe('stage 8 hardening: tenant-guarded upserts', () => {
    let env: Env; let db: D1Shim;

    beforeAll(() => {
        ({ env, db } = makeEnv());
    });

    it('a colliding student id from academy B can never overwrite academy A rows', async () => {
        const a = new AcademyDatabase(env, ACADEMY_A);
        const b = new AcademyDatabase(env, ACADEMY_B);
        await a.upsertStudent({
            id: 'dup-stu', student_code: 'A-1', full_name: 'هنرجوی آکادمی آ', phone: '09120000001',
            national_code: '001', telegram_chat_id: '', telegram_username: '', is_telegram_connected: 0,
            linked_at_epoch: 0, created_at_epoch: 1, updated_at_epoch: 1
        } as StudentRecord);
        // Academy B pushes the SAME app-side id (data corruption before the guard)
        await b.upsertStudent({
            id: 'dup-stu', student_code: 'B-1', full_name: 'هنرجوی آکادمی ب', phone: '09120000002',
            national_code: '002', telegram_chat_id: '', telegram_username: '', is_telegram_connected: 0,
            linked_at_epoch: 0, created_at_epoch: 2, updated_at_epoch: 2
        } as StudentRecord);
        const rows = ((await db.prepare(`SELECT academy_id, full_name, student_code FROM students WHERE id = 'dup-stu'`).all()).results || []) as any[];
        expect(rows.length).toBe(1);
        expect(rows[0].academy_id).toBe(ACADEMY_A);
        expect(rows[0].full_name).toBe('هنرجوی آکادمی آ');
        expect(rows[0].student_code).toBe('A-1');
    });

    it('a colliding installment id from academy B cannot touch A financial fields', async () => {
        const a = new AcademyDatabase(env, ACADEMY_A);
        const b = new AcademyDatabase(env, ACADEMY_B);
        const base: Partial<InstallmentRecord> = {
            installment_number: 1, total_installments: 2, course_title: 'دوره آ', amount: 1_000_000,
            due_date_shamsi: '۱۴۰۴/۰۸/۰۱', due_date_epoch: Date.now() + 86_400_000, updated_at_epoch: 1
        };
        await a.upsertInstallments([{ id: 'dup-ins', student_id: 'dup-stu', paid_amount: 400_000,
            remaining_amount: 600_000, is_paid: 0, paid_date_shamsi: '', ...base } as InstallmentRecord]);
        // B pushes the same id with DIFFERENT financial truth: must be dropped
        await b.upsertInstallments([{ id: 'dup-ins', student_id: 'dup-stu', paid_amount: 999_999,
            remaining_amount: 1, is_paid: 1, paid_date_shamsi: '۱۴۰۴/۰۱/۰۱', ...base } as InstallmentRecord]);
        const rows = ((await db.prepare(`SELECT academy_id, paid_amount, remaining_amount, is_paid FROM installments WHERE id = 'dup-ins'`).all()).results || []) as any[];
        expect(rows.length).toBe(1);
        expect(rows[0].academy_id).toBe(ACADEMY_A);
        expect(rows[0].paid_amount).toBe(400_000);
        expect(rows[0].remaining_amount).toBe(600_000);
        expect(rows[0].is_paid).toBe(0);
        // Same-academy re-push still updates normally (guard is identity-scoped)
        await a.upsertInstallments([{ id: 'dup-ins', student_id: 'dup-stu', paid_amount: 500_000,
            remaining_amount: 500_000, is_paid: 0, paid_date_shamsi: '', ...base } as InstallmentRecord]);
        const same = db.get(`SELECT paid_amount FROM installments WHERE id = 'dup-ins'`) as any;
        expect(same.paid_amount).toBe(500_000);
    });

    it('a colliding student id cannot overwrite another academy financial summary', async () => {
        const a = new AcademyDatabase(env, ACADEMY_A);
        const b = new AcademyDatabase(env, ACADEMY_B);
        await a.upsertFinancialSummary({
            student_id: 'dup-stu', total_tuition: 3_000_000, total_paid: 500_000, remaining_debt: 2_500_000,
            active_courses_count: 1, courses_summary: '[]', settlement_status: 'IN_PROGRESS', updated_at_epoch: 1
        } as FinancialSummaryRecord);
        await b.upsertFinancialSummary({
            student_id: 'dup-stu', total_tuition: 1, total_paid: 2, remaining_debt: 3,
            active_courses_count: 0, courses_summary: '[]', settlement_status: 'SETTLED', updated_at_epoch: 2
        } as FinancialSummaryRecord);
        const rows = ((await db.prepare(
            `SELECT academy_id, total_tuition, total_paid FROM financial_summaries WHERE student_id = 'dup-stu'`).all()).results || []) as any[];
        expect(rows.length).toBe(1);
        expect(rows[0].academy_id).toBe(ACADEMY_A);
        expect(rows[0].total_tuition).toBe(3_000_000);
        expect(rows[0].total_paid).toBe(500_000);
    });
});

// ============================================================
// 2. Reserved backup keys on the settings sync API
// ============================================================
describe('stage 8 hardening: reserved settings keys', () => {
    it('/api/sync/settings refuses backup-run-state keys (no forged day marker)', async () => {
        const { env, db } = makeEnv();
        const res = await api(env, 'POST', '/api/sync/settings', {
            academy_name: 'آکادمی نمونه',
            'backup:last_day': '2099-01-01'
        });
        expect(res.status).toBe(400);
        const body = await res.json() as any;
        expect(body.success).toBe(false);
        // The legitimate key was NOT written either (all-or-nothing refusal)
        const name = db.get(`SELECT value FROM academy_settings WHERE key = ?`, `${ACADEMY_A}:academy_name`) as any;
        expect(name).toBeFalsy();
        // and the day marker was never created
        const marker = db.get(`SELECT value FROM academy_settings WHERE key = ?`, `${ACADEMY_A}:backup:last_day`) as any;
        expect(marker).toBeFalsy();
    });
});

// ============================================================
// 3. Confirmed-payment pull: REAL applied amount
// ============================================================
describe('stage 8 hardening: applied amount in confirmed pull', () => {
    it('listApprovedClaimsSince reports the receipt amount, not the claimed amount', async () => {
        const { env, db } = makeEnv();
        const now = Date.now();
        // A claim approved with a capped applied amount: claimed 500k, applied 400k
        db.run(
            `INSERT INTO payment_claims (id, academy_id, student_id, installment_id, claimed_amount, status,
                 decided_at_epoch, payment_id, created_at_epoch, updated_at_epoch)
             VALUES ('clm-app', ?, 'stu-x', 'ins-x', 500000, 'APPROVED', ?, 'clm-app', ?, ?)`,
            ACADEMY_A, now, now, now
        );
        db.run(
            `INSERT INTO payment_receipts (id, academy_id, student_id, amount, source, created_at_epoch, updated_at_epoch)
             VALUES ('clm-app', ?, 'stu-x', 400000, 'manual', ?, ?)`,
            ACADEMY_A, now, now
        );
        const dbA = new AcademyDatabase(env, ACADEMY_A);
        const rows = await dbA.listApprovedClaimsSince(ACADEMY_A, 0);
        expect(rows.length).toBe(1);
        expect((rows[0] as any).applied_amount).toBe(400000);
        expect(rows[0].claimed_amount).toBe(500000); // original claim preserved
    });
});

// ============================================================
// 4. Shamsi dates follow the Tehran day
// ============================================================
describe('stage 8 hardening: Tehran-day Shamsi conversion', () => {
    it('00:30 Tehran (21:00 UTC of the previous day) prints the NEW day', () => {
        // 2024-03-19 22:00 UTC == 2024-03-20 01:30 Tehran == 1 Farvardin 1403
        const epoch = Date.UTC(2024, 2, 19, 22, 0, 0);
        expect(epochToShamsi(epoch)).toBe('۱۴۰۳/۰۱/۰۱');
    });
    it('mid-day timestamps are unchanged by the shift', () => {
        // 2024-06-21 08:00 UTC == 11:30 Tehran — same day either way
        const epoch = Date.UTC(2024, 5, 21, 8, 0, 0);
        expect(epochToShamsi(epoch)).toBe('۱۴۰۳/۰۴/۰۱');
    });
});

// ============================================================
// 5. Template validation: lone '<'
// ============================================================
describe('stage 8 hardening: template validation', () => {
    it('refuses a lone < that Telegram HTML would choke on', () => {
        const errors = validateTemplateText('INSTALLMENT_REMINDER', 'سلام <b>هنرجو</b> عزیز <');
        expect(errors.some((e) => e.includes('<'))).toBe(true);
    });
    it('accepts a normal template with allowed tags', () => {
        const errors = validateTemplateText('INSTALLMENT_REMINDER', 'سلام <b>{studentName}</b>، قسط {installmentLine} یادت نره.');
        expect(errors).toEqual([]);
    });
});

// ============================================================
// 6. Manager claim notifications escape student-controlled text
// ============================================================
describe('stage 8 hardening: claim notification escaping', () => {
    it('a student caption with HTML cannot break or forge the manager message', () => {
        const claim = {
            id: 'clm-esc', academy_id: ACADEMY_A, student_id: 'stu-e', student_name: 'هنرجو <b>تزئینی</b>',
            installment_id: 'ins-e', installment_number: 1, course_title: 'دوره <i>x</i>',
            claimed_amount: 100000, note: 'پرداخت شد <script>alert(1)</script>', status: 'PENDING'
        } as unknown as PaymentClaimRecord;
        const text = renderClaimNotification(claim, 'تومان');
        expect(text).not.toContain('<script>');
        expect(text).toContain('&lt;script&gt;');
        expect(text).toContain('هنرجو &lt;b&gt;تزئینی&lt;/b&gt;');
        // the legitimate wrapper markup is untouched
        expect(text).toContain('<b>رسید پرداخت دریافتی');
    });
});

// ============================================================
// 7. Gateway verify transition is a CAS
// ============================================================
describe('stage 8 hardening: transaction CAS transition', () => {
    it('transitions only from the expected status and reports the loser', async () => {
        const { env, db: shim } = makeEnv();
        const db = new AcademyDatabase(env, ACADEMY_A);
        const now = Date.now();
        shim.run(
            `INSERT INTO payment_transactions (id, academy_id, student_id, installment_id, amount, status,
                 created_at_epoch, updated_at_epoch)
             VALUES ('cas-order', ?, 'stu-c', 'ins-c', 100000, 'PENDING', ?, ?)`,
            ACADEMY_A, now, now
        );
        // winner: PENDING -> PAID
        const won = await db.transitionPaymentTransaction('cas-order', ACADEMY_A, 'PENDING', {
            status: 'PAID', paid_amount: 100000
        });
        expect(won).toBe(true);
        // a second concurrent verifier coming from PENDING loses (no changes)
        const lost = await db.transitionPaymentTransaction('cas-order', ACADEMY_A, 'PENDING', {
            status: 'PAID', paid_amount: 100000
        });
        expect(lost).toBe(false);
        // the row is paid exactly once
        const row = shim.get(`SELECT status, paid_amount FROM payment_transactions WHERE id = 'cas-order'`) as any;
        expect(row.status).toBe('PAID');
        expect(row.paid_amount).toBe(100000);
    });
});
