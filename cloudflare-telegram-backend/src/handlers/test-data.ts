// ============================================================
// Test-data cleanup API — temporary admin tool for test cycles.
//   POST /api/test-data/preview  { studentIds: ["..."] }
//   POST /api/test-data/purge    { studentIds: ["..."], confirm: true }
//
// PURPOSE
//   The academy owner repeatedly tests the full registration → telegram
//   connect → contract → sign cycle with throwaway students. This endpoint
//   removes EXPLICITLY selected students and every row provably belonging
//   to them (resolved through the real database relations — academy_id +
//   foreign keys — never through names/phones) in ONE atomic D1 batch.
//
// HARD SAFETY RULES
//   * Bearer key of the academy (API_SECRET_KEY) + tenant scope required.
//   * Nothing is ever matched by name or phone — only explicit student ids
//     that must EXIST in THIS academy's students table.
//   * Academy settings, backups, academy bot credentials, migration ledger
//     and every row of any other student are NEVER touched.
//   * The whole purge is a single env.DB.batch() → all-or-nothing.
//   * After the batch the same counts are re-run and must all be zero; the
//     report is stored under the academy's settings namespace
//     ("test_cleanup:last") for traceability.
//   * Re-running is safe: with nothing left, the purge reports zero and
//     does not run any statement.
//   * Old links die by construction: /start link hashes live on the student
//     row, sign tokens in contracts/contract_pending_links, /pay tokens in
//     payment_link_tokens — deleting those rows invalidates every link.
// ============================================================

import { Env } from '../types';
import { AcademyDatabase } from '../db';
import { bearerAuthorized, academyIdOf } from './contracts-api';

const MAX_STUDENTS = 25;
const IN_CHUNK = 80; // stay well under the D1 bound-parameter limit

function json(data: unknown, status = 200): Response {
    return new Response(JSON.stringify(data), {
        status,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }
    });
}

function ok(message: string, data: unknown): Response {
    return json({ success: true, message, data });
}

function fail(message: string, status = 400): Response {
    return json({ success: false, message, error: message }, status);
}

function chunk<T>(values: T[], size = IN_CHUNK): T[][] {
    const out: T[][] = [];
    for (let i = 0; i < values.length; i += size) out.push(values.slice(i, i + size));
    return out;
}

/** Parses and validates the request body. Only well-formed id lists pass. */
async function readStudentIds(request: Request): Promise<{ ids: string[]; confirm: boolean } | Response> {
    let body: any;
    try {
        body = await request.json();
    } catch {
        return fail('درخواست باید JSON باشد.');
    }
    const raw = body?.studentIds;
    if (!Array.isArray(raw) || raw.length === 0) {
        return fail('فهرست studentIds الزامی است (حداقل یک شناسه هنرجوی آزمایشی).');
    }
    const seen = new Set<string>();
    for (const item of raw) {
        const id = String(item ?? '').trim();
        if (!id || id.length > 128) return fail('شناسهٔ هنرجو نامعتبر است.');
        seen.add(id);
    }
    if (seen.size > MAX_STUDENTS) {
        return fail(`حداکثر ${MAX_STUDENTS} هنرجو در هر درخواست قابل پاک‌سازی است.`);
    }
    return { ids: [...seen], confirm: body?.confirm === true };
}

async function scalar(db: D1Database, sql: string, params: unknown[]): Promise<number> {
    const row = await db.prepare(sql).bind(...params).first<{ n: number | bigint }>();
    return Number(row?.n ?? 0);
}

/** Concatenated id-list placeholder helper: "?,?,?" */
function ph(n: number): string {
    return Array.from({ length: n }, () => '?').join(',');
}

interface Category {
    key: string;
    label: string;
    sql: string;
    params: unknown[];
}

/**
 * Every row category that provably belongs to the selected students.
 * Params follow SQL TEXT order: outer ? first, then subquery ?s.
 */
function buildCategories(aid: string, sids: string[]): Category[] {
    const n = sids.length;
    const list = ph(n);
    const base = [aid, ...sids];
    const aidAndList = [aid, aid, ...sids]; // outer academy_id + subquery (academy_id + ids)
    const sub = (inner: string) => `SELECT id FROM ${inner} WHERE academy_id = ? AND student_id IN (${list})`;
    // Contract-owned storage keys (original PDF, signed PDF, seal, manager signature).
    const fileUnion = {
        sql: `
        SELECT pdf_key FROM contracts WHERE academy_id = ? AND student_id IN (${list}) AND pdf_key != ''
        UNION SELECT signed_pdf_key FROM contracts WHERE academy_id = ? AND student_id IN (${list}) AND signed_pdf_key != ''
        UNION SELECT seal_key FROM contracts WHERE academy_id = ? AND student_id IN (${list}) AND seal_key != ''
        UNION SELECT sig_key FROM contracts WHERE academy_id = ? AND student_id IN (${list}) AND sig_key != ''`,
        params: [...base, ...base, ...base, ...base]
    };
    return [
        { key: 'contracts', label: 'قراردادها', sql: `SELECT COUNT(*) AS n FROM contracts WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'contractEvents', label: 'رویدادهای قرارداد', sql: `SELECT COUNT(*) AS n FROM contract_events WHERE academy_id = ? AND contract_id IN (${sub('contracts')})`, params: aidAndList },
        { key: 'contractFiles', label: 'فایل‌های PDF/مهر/امضا', sql: `SELECT COUNT(*) AS n FROM contract_files WHERE key IN (${fileUnion.sql})`, params: fileUnion.params },
        { key: 'contractFileChunks', label: 'قطعات فایل', sql: `SELECT COUNT(*) AS n FROM contract_file_chunks WHERE file_key IN (${fileUnion.sql})`, params: fileUnion.params },
        { key: 'contractPendingLinks', label: 'لینک‌های امضای فعال', sql: `SELECT COUNT(*) AS n FROM contract_pending_links WHERE academy_id = ? AND contract_id IN (${sub('contracts')})`, params: aidAndList },
        { key: 'contractDeliveries', label: 'سوابق تحویل قرارداد', sql: `SELECT COUNT(*) AS n FROM contract_deliveries WHERE academy_id = ? AND contract_id IN (${sub('contracts')})`, params: aidAndList },
        { key: 'installments', label: 'اقساط', sql: `SELECT COUNT(*) AS n FROM installments WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'financialSummaries', label: 'خلاصه‌های مالی', sql: `SELECT COUNT(*) AS n FROM financial_summaries WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'reminders', label: 'یادآوری‌ها', sql: `SELECT COUNT(*) AS n FROM reminders WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'deliveries', label: 'سوابق تحویل پیام', sql: `SELECT COUNT(*) AS n FROM deliveries WHERE academy_id = ? AND reminder_id IN (${sub('reminders')})`, params: aidAndList },
        { key: 'smsOutbox', label: 'صف پیامک', sql: `SELECT COUNT(*) AS n FROM sms_outbox WHERE academy_id = ? AND reminder_id IN (${sub('reminders')})`, params: aidAndList },
        { key: 'messageLogs', label: 'گزارش‌های پیام', sql: `SELECT COUNT(*) AS n FROM message_logs WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'pendingStudents', label: 'هنرجوهای در انتظار', sql: `SELECT COUNT(*) AS n FROM pending_students WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'paymentReceipts', label: 'رسیدهای پرداخت', sql: `SELECT COUNT(*) AS n FROM payment_receipts WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'paymentTransactions', label: 'تراکنش‌های پرداخت آنلاین', sql: `SELECT COUNT(*) AS n FROM payment_transactions WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'paymentClaims', label: 'ادعاهای رسید هنرجو', sql: `SELECT COUNT(*) AS n FROM payment_claims WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'paymentEvents', label: 'رویدادهای مالی', sql: `SELECT COUNT(*) AS n FROM payment_events WHERE academy_id = ? AND student_id IN (${list})`, params: base },
        { key: 'paymentLinkTokens', label: 'توکن‌های لینک پرداخت', sql: `SELECT COUNT(*) AS n FROM payment_link_tokens WHERE academy_id = ? AND order_id IN (${sub('payment_transactions')})`, params: aidAndList }
    ];
}

interface StudentRow {
    id: string;
    full_name: string;
    student_code: string;
    phone: string;
    telegram_chat_id: string;
    is_telegram_connected: number;
    linked_at_epoch: number;
}

async function loadStudents(db: D1Database, aid: string, sids: string[]): Promise<StudentRow[]> {
    const res = await db.prepare(
        `SELECT id, full_name, student_code, phone, telegram_chat_id, is_telegram_connected, linked_at_epoch
         FROM students WHERE academy_id = ? AND id IN (${ph(sids.length)}) ORDER BY created_at_epoch ASC`
    ).bind(aid, ...sids).all<StudentRow>();
    return res.results || [];
}

export async function handleTestDataApi(request: Request, env: Env, url: URL): Promise<Response> {
    if (request.method !== 'POST') return fail('فقط POST مجاز است.', 405);
    if (!bearerAuthorized(request, env)) return fail('دسترسی غیرمجاز.', 401);
    const academyId = academyIdOf(request, env);
    if (!academyId) return fail('شناسه آموزشگاه مجاز نیست.', 403);
    if (!env.DB) return fail('D1 متصل نیست.', 503);
    const db = env.DB;

    const parsed = await readStudentIds(request);
    if (parsed instanceof Response) return parsed;
    const sids = parsed.ids;
    const isPurge = url.pathname === '/api/test-data/purge';
    if (isPurge && !parsed.confirm) {
        return fail('برای اجرای پاک‌سازی، تأیید صریح (confirm: true) الزامی است.', 400);
    }

    try {
        const students = await loadStudents(db, academyId, sids);
        const foundIds = [...new Set(students.map((s) => s.id))];
        const unknownIds = sids.filter((id) => !foundIds.includes(id));

        // Per-student contract counts for the preview dialog.
        const contractByStudent = new Map<string, number>();
        if (foundIds.length > 0) {
            const rows = await db.prepare(
                `SELECT student_id, COUNT(*) AS n FROM contracts WHERE academy_id = ? AND student_id IN (${ph(foundIds.length)}) GROUP BY student_id`
            ).bind(academyId, ...foundIds).all<{ student_id: string; n: number }>();
            for (const r of rows.results || []) contractByStudent.set(r.student_id, Number(r.n));
        }

        // ---------- collect concrete id/key lists (needed for the purge) ----------
        let contractIds: string[] = [];
        let reminderIds: string[] = [];
        let reminderChats: string[] = [];
        let txnIds: string[] = [];
        let fileKeys: string[] = [];
        if (foundIds.length > 0) {
            const base = [academyId, ...foundIds];
            contractIds = (await db.prepare(
                `SELECT id FROM contracts WHERE academy_id = ? AND student_id IN (${ph(foundIds.length)})`
            ).bind(...base).all<{ id: string }>()).results?.map((r) => r.id) || [];
            const reminderRows = (await db.prepare(
                `SELECT id, chat_id FROM reminders WHERE academy_id = ? AND student_id IN (${ph(foundIds.length)})`
            ).bind(...base).all<{ id: string; chat_id: string }>()).results || [];
            reminderIds = reminderRows.map((r) => r.id);
            reminderChats = reminderRows.map((r) => r.chat_id).filter(Boolean);
            txnIds = (await db.prepare(
                `SELECT id FROM payment_transactions WHERE academy_id = ? AND student_id IN (${ph(foundIds.length)})`
            ).bind(...base).all<{ id: string }>()).results?.map((r) => r.id) || [];
            const keyRows = (await db.prepare(
                `SELECT pdf_key, signed_pdf_key, seal_key, sig_key FROM contracts WHERE academy_id = ? AND student_id IN (${ph(foundIds.length)})`
            ).bind(...base).all<{ pdf_key: string; signed_pdf_key: string; seal_key: string; sig_key: string }>()).results || [];
            const keys = new Set<string>();
            for (const r of keyRows) {
                for (const k of [r.pdf_key, r.signed_pdf_key, r.seal_key, r.sig_key]) {
                    if (k && String(k).trim() !== '') keys.add(String(k));
                }
            }
            fileKeys = [...keys];
        }

        // Shared-chat guard: a chat id is only purged from unscoped message
        // logs when NO remaining student still owns it. Chats are collected
        // from BOTH the student rows and the reminders queue (delivery logs
        // may be keyed by chat alone, e.g. a welcome message before binding).
        const chatIds = [...new Set([
            ...students.map((s) => s.telegram_chat_id).filter(Boolean),
            ...reminderChats
        ])];
        let chatsSafeToClean: string[] = [];
        if (chatIds.length > 0) {
            const others = (await db.prepare(
                `SELECT DISTINCT telegram_chat_id FROM students WHERE academy_id = ? AND telegram_chat_id != '' AND id NOT IN (${ph(foundIds.length)})`
            ).bind(academyId, ...foundIds).all<{ telegram_chat_id: string }>()).results || [];
            const protectedChats = new Set(others.map((r) => r.telegram_chat_id));
            chatsSafeToClean = [...new Set(chatIds)].filter((c) => !protectedChats.has(c));
        }

        // ---------- counts (preview + post-purge verification) ----------
        const categories = buildCategories(academyId, foundIds.length > 0 ? foundIds : sids);
        const chatLogCount = async (): Promise<number> => {
            let n = 0;
            for (const part of chunk(chatsSafeToClean)) {
                n += await scalar(db,
                    `SELECT COUNT(*) AS n FROM message_logs WHERE academy_id = ? AND student_id = '' AND chat_id IN (${ph(part.length)})`,
                    [academyId, ...part]);
            }
            return n;
        };
        const counts: Record<string, number> = {};
        const labels: Record<string, string> = {};
        for (const cat of categories) {
            counts[cat.key] = await scalar(db, cat.sql, cat.params);
            labels[cat.key] = cat.label;
        }
        counts.messageLogsUnscoped = await chatLogCount();
        labels.messageLogsUnscoped = 'پیام‌های ثبت‌شده فقط با شناسهٔ گفتگو';
        let fileBytes = 0;
        if (foundIds.length > 0) {
            const s = foundIds.length;
            const base = [academyId, ...foundIds];
            const fu = {
                sql: `
        SELECT pdf_key FROM contracts WHERE academy_id = ? AND student_id IN (${ph(s)}) AND pdf_key != ''
        UNION SELECT signed_pdf_key FROM contracts WHERE academy_id = ? AND student_id IN (${ph(s)}) AND signed_pdf_key != ''
        UNION SELECT seal_key FROM contracts WHERE academy_id = ? AND student_id IN (${ph(s)}) AND seal_key != ''
        UNION SELECT sig_key FROM contracts WHERE academy_id = ? AND student_id IN (${ph(s)}) AND sig_key != ''`,
                params: [...base, ...base, ...base, ...base]
            };
            fileBytes =
                (await scalar(db, `SELECT COALESCE(SUM(LENGTH(content)),0) AS n FROM contract_files WHERE key IN (${fu.sql})`, fu.params)) +
                (await scalar(db, `SELECT COALESCE(SUM(LENGTH(content)),0) AS n FROM contract_file_chunks WHERE file_key IN (${fu.sql})`, fu.params));
        }
        const totalRecords = Object.values(counts).reduce((a, b) => a + b, 0);

        const studentInfos = students.map((s) => ({
            id: s.id,
            fullName: s.full_name,
            studentCode: s.student_code,
            phone: s.phone,
            telegramChatId: s.telegram_chat_id,
            isTelegramConnected: s.is_telegram_connected === 1,
            contracts: contractByStudent.get(s.id) || 0
        }));

        if (!isPurge) {
            return ok(
                students.length === 0
                    ? 'هیچ هنرجویی با این شناسه‌ها در این آموزشگاه پیدا نشد.'
                    : `بررسی انجام شد: ${totalRecords} رکورد و ${Math.round(fileBytes / 1024)} کیلوبایت فایل برای ${students.length} هنرجوی آزمایشی.`,
                { mode: 'preview', students: studentInfos, unknownIds, counts, labels, fileBytes, totalRecords }
            );
        }

        // ---------- purge ----------
        if (students.length === 0) {
            const emptyReport = {
                mode: 'purge', ranAtEpoch: Date.now(), deleted: {}, deletedTotal: 0,
                remaining: counts, remainingStudents: 0, remainingClean: true, students: [], unknownIds,
                message: 'رکورد آزمایشی‌ای برای این شناسه‌ها وجود نداشت؛ چیزی حذف نشد (پاک‌سازی مجدد امن است).'
            };
            await new AcademyDatabase(env, academyId).setSetting('test_cleanup:last', JSON.stringify(emptyReport));
            return ok('رکورد آزمایشی‌ای برای این شناسه‌ها وجود نداشت؛ چیزی حذف نشد (پاک‌سازی مجدد امن است).', emptyReport);
        }

        interface Del { table: string; sql: string; params: unknown[]; }
        const deletes: Del[] = [];
        const pushIn = (table: string, column: string, values: string[], scoped: boolean) => {
            for (const part of chunk(values)) {
                deletes.push({
                    table,
                    sql: `DELETE FROM ${table} WHERE ${scoped ? 'academy_id = ? AND ' : ''}${column} IN (${ph(part.length)})`,
                    params: scoped ? [academyId, ...part] : [...part]
                });
            }
        };
        // Children first — within one batch this order is irrelevant for
        // correctness (single transaction), it only keeps the report readable.
        pushIn('contract_file_chunks', 'file_key', fileKeys, false);
        pushIn('contract_files', 'key', fileKeys, false);
        pushIn('contract_pending_links', 'contract_id', contractIds, true);
        pushIn('contract_deliveries', 'contract_id', contractIds, true);
        pushIn('contract_events', 'contract_id', contractIds, true);
        pushIn('deliveries', 'reminder_id', reminderIds, true);
        pushIn('sms_outbox', 'reminder_id', reminderIds, true);
        pushIn('payment_link_tokens', 'order_id', txnIds, true);
        pushIn('payment_events', 'student_id', foundIds, true);
        pushIn('payment_claims', 'student_id', foundIds, true);
        pushIn('payment_receipts', 'student_id', foundIds, true);
        pushIn('payment_transactions', 'student_id', foundIds, true);
        pushIn('reminders', 'student_id', foundIds, true);
        pushIn('message_logs', 'student_id', foundIds, true);
        for (const part of chunk(chatsSafeToClean)) {
            deletes.push({
                table: 'message_logs_unscoped',
                sql: `DELETE FROM message_logs WHERE academy_id = ? AND student_id = '' AND chat_id IN (${ph(part.length)})`,
                params: [academyId, ...part]
            });
        }
        pushIn('financial_summaries', 'student_id', foundIds, true);
        pushIn('installments', 'student_id', foundIds, true);
        pushIn('pending_students', 'student_id', foundIds, true);
        pushIn('contracts', 'student_id', foundIds, true);
        pushIn('students', 'id', foundIds, true);

        const batchResults = await db.batch(deletes.map((d) => db.prepare(d.sql).bind(...d.params)) as any);
        const deleted: Record<string, number> = {};
        let deletedTotal = 0;
        deletes.forEach((d, i) => {
            const changes = Number((batchResults as any[])[i]?.meta?.changes ?? 0);
            deleted[d.table] = (deleted[d.table] || 0) + changes;
            deletedTotal += changes;
        });

        // ---------- verify: the same counts must all be zero now ----------
        const remaining: Record<string, number> = {};
        let remainingTotal = 0;
        for (const cat of categories) {
            const n = await scalar(db, cat.sql, cat.params);
            remaining[cat.key] = n;
            remainingTotal += n;
        }
        remaining.messageLogsUnscoped = await chatLogCount();
        remainingTotal += remaining.messageLogsUnscoped;
        const remainingStudents = await scalar(db,
            `SELECT COUNT(*) AS n FROM students WHERE academy_id = ? AND id IN (${ph(sids.length)})`,
            [academyId, ...sids]);
        const remainingClean = remainingTotal === 0 && remainingStudents === 0;

        const report = {
            mode: 'purge',
            ranAtEpoch: Date.now(),
            students: studentInfos.map((s) => ({ id: s.id, fullName: s.fullName })),
            unknownIds,
            deleted, deletedTotal,
            fileBytesFreed: fileBytes,
            remaining, remainingStudents, remainingClean,
            labels
        };
        await new AcademyDatabase(env, academyId).setSetting('test_cleanup:last', JSON.stringify(report));

        const message = remainingClean
            ? `پاک‌سازی کامل انجام شد: ${deletedTotal} رکورد از ${students.length} هنرجوی آزمایشی حذف شد. کنترل نهایی: هیچ رکورد وابسته‌ای باقی نمانده است.`
            : `پاک‌سازی انجام شد (${deletedTotal} رکورد) اما ${remainingTotal + remainingStudents} رکورد باقی مانده است — دوباره بررسی کنید.`;
        return ok(message, report);
    } catch (err: any) {
        console.warn('[TEST-DATA] cleanup failed:', err?.message || err);
        return fail(`خطا در عملیات پاک‌سازی: ${err?.message || 'خطای ناشناخته'}`, 500);
    }
}
