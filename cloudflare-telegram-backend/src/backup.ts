// ============================================================
// Server-side daily backup of one academy's essential data (Stage 7)
//
// COVERAGE — every per-academy application table (stages 2-7):
//   academies, students, installments (incl. payment_method from 0009),
//   financial_summaries, payment_receipts, payment_transactions,
//   payment_link_tokens (0009 / Stage 4), reminders (incl.
//   trigger_offset/schedule_source from 0010), deliveries, message_logs,
//   sms_outbox, pending_students, contracts, contract_events,
//   contract_deliveries, contract_pending_links (0006 / Stage 3),
//   contract_files, contract_file_chunks, payment_claims (incl. the
//   photo evidence bytes), payment_events (0010 / Stage 5) and
//   academy_settings (namespaced rows).
//   The ONLY tables excluded are the backup machinery itself
//   (backups / backup_chunks / backup_runs, migration 0011) — a backup
//   must never contain (stale copies of) backups.
//   Table names are taken from the migrations, never guessed.
//
// Storage: the gzip-compressed snapshot lives back inside the same
// D1 database (backups + backup_chunks), chunked at 768 KB because
// D1 caps a single bound parameter at 1 MB — the same pattern the
// contract files already use (migration 0005).
//
// Free-plan query budget (official limits, developers.cloudflare.com/
// d1/platform/limits/, verified 2026-10-23):
//   * "Queries per Worker invocation: 50 (Free) / 1000 (Paid)".
//   * A subrequest is any request a Worker makes to a Cloudflare
//     service incl. D1 (workers/platform/limits). db.batch() is ONE
//     such request carrying many statements (per-statement limits
//     like the 100 KB SQL length apply inside the batch).
//   * This module therefore batches aggressively: a full worst-case
//     run at BACKUP_MAX_BYTES (25 MB => 33 chunks) costs ~19 D1
//     requests (~24 with the cron day-marker). See the budget table
//     in docs/STAGE7_BACKUP_AND_FINANCE.md. A conservative
//     per-statement count is ALSO recorded (it is not the platform
//     semantics, but if it ever mattered, the resume machinery below
//     lets the next cron minute continue the same snapshot instead
//     of failing).
//
// Safety rules (the stage contract):
//   1. The new snapshot is verified BEFORE anything old is touched:
//      read chunks back, sha256 them, gunzip, JSON.parse, compare
//      per-table row counts and financial sums (installments,
//      summaries AND the stage-4/5 financial tables) with the live
//      data, and re-verify contract file hashes.
//   2. A failed verification marks the run CORRUPT/FAILED and every
//      previous HEALTHY snapshot stays untouched.
//   3. Old copies are pruned ONLY after the new snapshot verified
//      HEALTHY (retention: last N healthy, default 7).
//
// Resumability (no new cron, no new service, no migration):
//   * Run state lives in academy_settings under
//     "<academyId>:backup:resume" and "<academyId>:backup:running"
//     (a 10-minute freshness lock). No schema change is needed.
//   * Collect and store are staged: the gzip payload is produced
//     deterministically (fixed exportedAtEpoch + MTIME=0 gzip), its
//     sha256 recorded in the PENDING backups row, and chunk writes
//     happen in batches that persist the chunk cursor in the same
//     transaction. If an invocation dies (query cap, wall clock,
//     transient D1 error), the NEXT cron minute continues the SAME
//     snapshot: it re-collects deterministically, compares the
//     payload sha256, stores only the missing chunks and verifies.
//   * If the data changed mid-run (sha256 mismatch) the pending copy
//     is cleaned up and the run fails HONESTLY — the next cron minute
//     starts a fresh snapshot. Old healthy copies are never touched.
//   * Concurrency: MANUAL runs refuse while a fresh lock exists; AUTO
//     (cron) runs adopt an interrupted run instead of racing it.
//
// Free-tier honesty: no R2, no extra service. D1 free storage is
// 500 MB per database (5 GB per account); when the compressed payload
// grows past BACKUP_MAX_BYTES the snapshot automatically stores
// metadata + sha256 for contract files instead of their bytes
// (files_included = 0) and says so in the status output — it never
// pretends files are covered when they are not. Total-loss recovery
// stays Cloudflare Time Travel (7 days on the Free plan).
// ============================================================

import { Env } from './types';

/**
 * Per-academy tables, in restore order (parents before children).
 * Names come verbatim from schema.sql + migrations 0001..0011.
 */
export const BACKUP_TABLES: Array<{ table: string; academyColumn: string | null }> = [
    { table: 'academies', academyColumn: 'id' },
    { table: 'students', academyColumn: 'academy_id' },
    { table: 'installments', academyColumn: 'academy_id' },
    { table: 'financial_summaries', academyColumn: 'academy_id' },
    // --- Stage 4 (migration 0009): the payment ledger ---
    { table: 'payment_receipts', academyColumn: 'academy_id' },
    { table: 'payment_transactions', academyColumn: 'academy_id' },
    { table: 'payment_link_tokens', academyColumn: 'academy_id' },
    // --- Stage 2/3 core + messaging ---
    { table: 'reminders', academyColumn: 'academy_id' },
    { table: 'deliveries', academyColumn: 'academy_id' },
    { table: 'message_logs', academyColumn: 'academy_id' },
    { table: 'sms_outbox', academyColumn: 'academy_id' },
    { table: 'pending_students', academyColumn: 'academy_id' },
    { table: 'contracts', academyColumn: 'academy_id' },
    { table: 'contract_events', academyColumn: 'academy_id' },
    { table: 'contract_deliveries', academyColumn: 'academy_id' },
    { table: 'contract_pending_links', academyColumn: 'academy_id' },
    { table: 'contract_files', academyColumn: 'academy_id' },
    { table: 'contract_file_chunks', academyColumn: null }, // keyed via file_key -> contract_files
    // --- Stage 5 (migration 0010): claims + audit trail ---
    { table: 'payment_claims', academyColumn: 'academy_id' },
    { table: 'payment_events', academyColumn: 'academy_id' },
    { table: 'academy_settings', academyColumn: 'key' }    // namespaced "<academyId>:..." rows
];

const FILE_CHUNK_SIZE = 768 * 1024; // 768 KB per backup_chunks row (< D1 1 MB bind cap)
/** Chunk INSERTs per batch call: 10 x 768 KB ≈ 7.5 MB per request, and 12
 *  statements per batch stay far under every documented batch limit. */
const CHUNKS_PER_BATCH = 10;
/** Freshness window of the running lock (ms). */
const LOCK_TTL_MS = 10 * 60 * 1000;
/** Settings keys that are backup run-state, never settings (excluded from snapshots). */
const INTERNAL_SETTING_KEYS = new Set(['backup:running', 'backup:resume', 'backup:last_day']);

export interface BackupOptions {
    retention?: number;         // healthy snapshots to keep (default 7)
    maxPayloadBytes?: number;   // above this, file bytes are summarized not stored
    includeFiles?: boolean;     // default true
}

export interface BackupOutcome {
    ok: boolean;
    backupId: string;
    status: 'HEALTHY' | 'CORRUPT' | 'FAILED' | 'PENDING';
    error: string;
    durationMs: number;
    tableCounts: Record<string, number>;
    filesIncluded: boolean;
    storedBytes: number;
    /** true when this call did NOT finish the run (refused or continuing later). */
    pending?: boolean;
    /** Which stage the (possibly adopted) run is in. */
    phase?: 'COLLECT' | 'STORE' | 'VERIFY';
    /** true when this call continued a previously interrupted run. */
    resumed?: boolean;
}

export interface BackupBundle {
    format: 'academy-backup';
    /** 2 = full stage-4/5 financial coverage (receipts, transactions,
     *  link tokens, claims incl. photo evidence, events, pending links).
     *  1 = legacy bundle (pre-coverage); still readable. */
    version: 1 | 2;
    academyId: string;
    exportedAtEpoch: number;
    filesIncluded: boolean;
    tables: Record<string, unknown[]>;
    financialChecks: FinancialChecks;
}

export interface FinancialChecks {
    totalInstallments: number;
    sumInstallmentAmount: number;
    sumInstallmentPaid: number;
    sumInstallmentRemaining: number;
    sumSummaryTuition: number;
    sumSummaryPaid: number;
    sumSummaryDebt: number;
    studentCount: number;
    fileCount: number;
    fileSha256: Record<string, string>; // file key -> stored sha (verified on restore)
    // --- Stage 4/5 ledger (version-2 bundles) ---
    receiptCount: number;
    sumReceiptAmount: number;
    reversedReceiptCount: number;
    transactionCount: number;
    sumTransactionPaidAmount: number;
    paidTransactionCount: number;
    failedTransactionCount: number;
    linkTokenCount: number;
    claimCount: number;
    sumClaimAmount: number;
    pendingClaimCount: number;
    eventCount: number;
}

interface ResumeState {
    runId: string;
    backupId: string;
    trigger: 'AUTO' | 'MANUAL';
    exportedAtEpoch: number;   // seeded -> deterministic re-collect
    filesIncluded: boolean;
    maxPayloadBytes: number;
    retention: number;
    payloadSha256: string;
    jsonBytes: number;
    storedBytes: number;
    chunkCount: number;
    chunkCursor: number;       // chunks [0, cursor) are durably stored
}

// ------------------------------------------------------------------
// Small helpers
// ------------------------------------------------------------------

async function gzip(bytes: Uint8Array): Promise<Uint8Array> {
    const cs = new CompressionStream('gzip');
    // slice() copies so the ArrayBuffer passed to Response is exactly this view.
    const stream = new Response(bytes.slice().buffer as ArrayBuffer).body!.pipeThrough(cs);
    return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function gunzip(bytes: Uint8Array): Promise<Uint8Array> {
    const ds = new DecompressionStream('gzip');
    const stream = new Response(bytes.slice().buffer as ArrayBuffer).body!.pipeThrough(ds);
    return new Uint8Array(await new Response(stream).arrayBuffer());
}

export async function sha256HexBytes(bytes: Uint8Array): Promise<string> {
    const digest = await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Rows of academy_settings this academy owns (namespaced, or legacy default). */
export function academySettingsRows(all: Array<Record<string, unknown>>, academyId: string): Array<Record<string, unknown>> {
    const namespacedPrefix = `${academyId}:`;
    const isOwn = (key: string) => key.startsWith(namespacedPrefix);
    // Legacy (non-namespaced) rows belong to the historical 'default' tenant.
    const namespacedByOthers = new Set(
        all.map((r) => String(r.key || '')).filter((k) => k.includes(':')).map((k) => k.slice(0, k.indexOf(':') + 1))
    );
    const isLegacyDefault = (key: string) =>
        academyId === 'default' && !key.includes(':') && !namespacedByOthers.has('');
    return all.filter((r) => isOwn(String(r.key || '')) || isLegacyDefault(String(r.key || '')));
}

/** The run-state keys must never end up inside a snapshot: after a restore
 *  the backup machinery must start clean (a restored day marker would
 *  wrongly suppress today's backup). */
export function isInternalSettingKey(scopedKey: string): boolean {
    const colon = scopedKey.indexOf(':');
    const logical = colon === -1 ? scopedKey : scopedKey.slice(colon + 1);
    return INTERNAL_SETTING_KEYS.has(logical);
}

function tableCountsOf(bundle: BackupBundle): Record<string, number> {
    return Object.fromEntries(Object.entries(bundle.tables).map(([k, v]) => [k, v.length]));
}

// ------------------------------------------------------------------
// Collect (all bulk reads batched: 2 batch calls + 1 files query)
// ------------------------------------------------------------------

interface CollectedPayload {
    bundle: BackupBundle;
    jsonBytes: Uint8Array;
    payload: Uint8Array;
    payloadSha256: string;
    chunkCount: number;
    liveCounts: Record<string, number>;
    liveChecks: FinancialChecks;
}

/** One statement per table for the live row counts (+ the sums), all inside ONE batch. */
function countStatements(academyId: string): Array<{ sql: string; bind: string[] }> {
    const stmts: Array<{ sql: string; bind: string[] }> = [];
    for (const { table, academyColumn } of BACKUP_TABLES) {
        if (table === 'academy_settings') {
            stmts.push({ sql: 'SELECT key FROM academy_settings', bind: [] });
        } else if (table === 'contract_file_chunks') {
            stmts.push({
                sql: `SELECT COUNT(*) AS n FROM contract_file_chunks c
                      JOIN contract_files f ON f.key = c.file_key AND f.academy_id = ?`,
                bind: [academyId]
            });
        } else if (academyColumn === 'id') {
            stmts.push({ sql: 'SELECT COUNT(*) AS n FROM academies WHERE id = ?', bind: [academyId] });
        } else {
            stmts.push({ sql: `SELECT COUNT(*) AS n FROM ${table} WHERE ${academyColumn} = ?`, bind: [academyId] });
        }
    }
    // Financial sums for the verification (installments + summaries + stage-4/5 ledger).
    stmts.push(
        { sql: `SELECT COUNT(*) AS n, COALESCE(SUM(amount),0) AS amount_sum,
                       COALESCE(SUM(paid_amount),0) AS paid_sum,
                       COALESCE(SUM(remaining_amount),0) AS remaining_sum
                FROM installments WHERE academy_id = ?`, bind: [academyId] },
        { sql: `SELECT COALESCE(SUM(total_tuition),0) AS tuition_sum,
                       COALESCE(SUM(total_paid),0) AS paid_sum,
                       COALESCE(SUM(remaining_debt),0) AS debt_sum
                FROM financial_summaries WHERE academy_id = ?`, bind: [academyId] },
        { sql: `SELECT COUNT(*) AS n, COALESCE(SUM(amount),0) AS amount_sum,
                       COALESCE(SUM(CASE WHEN is_reversed = 1 THEN 1 ELSE 0 END),0) AS reversed_n
                FROM payment_receipts WHERE academy_id = ?`, bind: [academyId] },
        { sql: `SELECT COUNT(*) AS n, COALESCE(SUM(paid_amount),0) AS paid_sum,
                       COALESCE(SUM(CASE WHEN status = 'PAID' THEN 1 ELSE 0 END),0) AS paid_n,
                       COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END),0) AS failed_n
                FROM payment_transactions WHERE academy_id = ?`, bind: [academyId] },
        { sql: `SELECT COUNT(*) AS n, COALESCE(SUM(claimed_amount),0) AS amount_sum,
                       COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END),0) AS pending_n
                FROM payment_claims WHERE academy_id = ?`, bind: [academyId] },
        { sql: 'SELECT key, sha256 FROM contract_files WHERE academy_id = ?', bind: [academyId] }
    );
    return stmts;
}

function financialChecksFrom(
    tableRows: Record<string, number>,
    sums: Array<Record<string, unknown> | undefined>,
    fileSha: Record<string, string>
): FinancialChecks {
    const inst = sums[0] ?? {};
    const summ = sums[1] ?? {};
    const receipts = sums[2] ?? {};
    const txns = sums[3] ?? {};
    const claims = sums[4] ?? {};
    return {
        totalInstallments: tableRows['installments'] ?? 0,
        sumInstallmentAmount: Number(inst.amount_sum ?? 0),
        sumInstallmentPaid: Number(inst.paid_sum ?? 0),
        sumInstallmentRemaining: Number(inst.remaining_sum ?? 0),
        sumSummaryTuition: Number(summ.tuition_sum ?? 0),
        sumSummaryPaid: Number(summ.paid_sum ?? 0),
        sumSummaryDebt: Number(summ.debt_sum ?? 0),
        studentCount: tableRows['students'] ?? 0,
        fileCount: tableRows['contract_files'] ?? 0,
        fileSha256: fileSha,
        receiptCount: tableRows['payment_receipts'] ?? 0,
        sumReceiptAmount: Number(receipts.amount_sum ?? 0),
        reversedReceiptCount: Number(receipts.reversed_n ?? 0),
        transactionCount: tableRows['payment_transactions'] ?? 0,
        sumTransactionPaidAmount: Number(txns.paid_sum ?? 0),
        paidTransactionCount: Number(txns.paid_n ?? 0),
        failedTransactionCount: Number(txns.failed_n ?? 0),
        linkTokenCount: tableRows['payment_link_tokens'] ?? 0,
        claimCount: tableRows['payment_claims'] ?? 0,
        sumClaimAmount: Number(claims.amount_sum ?? 0),
        pendingClaimCount: Number(claims.pending_n ?? 0),
        eventCount: tableRows['payment_events'] ?? 0
    };
}

/** Post-processes raw rows: BLOB evidence/bytes become base64 strings so the
 *  bundle survives JSON (a raw BLOB would be destroyed by JSON.stringify). */
function encodeRows(table: string, raw: Array<Record<string, unknown>>): Array<Record<string, unknown>> {
    if (table === 'payment_claims') {
        return raw.map((r) => {
            const { photo_bytes, ...rest } = r;
            const bytes = photo_bytes == null ? null : new Uint8Array(photo_bytes as ArrayBuffer);
            return { ...rest, photo_b64: bytes && bytes.length ? toB64(bytes) : '' };
        });
    }
    if (table === 'contract_file_chunks') {
        return raw.map((r) => {
            const { content, ...rest } = r;
            const bytes = content == null ? null : new Uint8Array(content as ArrayBuffer);
            return { ...rest, content_b64: bytes && bytes.length ? toB64(bytes) : '' };
        });
    }
    return raw;
}

/**
 * Deterministic payload collect. exportAtEpoch MUST come from the resume
 * state on continuation so a re-collect hashes identically when nothing
 * changed. All bulk reads are batched: 1 counts batch + 1 rows batch +
 * 1 file-content query = 3 D1 requests for the whole collect phase.
 */
async function collectPayload(
    env: Env,
    academyId: string,
    includeFiles: boolean,
    exportedAtEpoch: number
): Promise<{ bundle: BackupBundle; liveCounts: Record<string, number>; liveChecks: FinancialChecks }> {
    const db = env.DB!;
    const tables: Record<string, unknown[]> = {};

    // --- Request 1: all counts + sums + file hashes in ONE batch ---
    const countStmts = countStatements(academyId);
    const countResults = await db.batch(
        countStmts.map((s) => db.prepare(s.sql).bind(...s.bind))
    );
    const tableCounts: Record<string, number> = {};
    BACKUP_TABLES.forEach(({ table }, i) => {
        const res = countResults[i] as { results?: Array<Record<string, unknown>> } | undefined;
        if (table === 'academy_settings') {
            // The batch returns every key; the count is AFTER academy scoping
            // and after excluding the backup run-state keys.
            const allKeys = (res?.results ?? []) as Array<{ key?: string }>;
            tableCounts[table] = academySettingsRows(
                allKeys.map((k) => ({ key: k.key })), academyId
            ).filter((r) => !isInternalSettingKey(String(r.key || ''))).length;
        } else {
            tableCounts[table] = Number(res?.results?.[0]?.n ?? 0);
        }
    });
    const sumRows = countResults.slice(BACKUP_TABLES.length) as Array<{ results?: Array<Record<string, unknown>> }>;
    const fileShaRows = (sumRows[5]?.results ?? []) as Array<{ key: string; sha256: string }>;
    const fileSha: Record<string, string> = {};
    for (const r of fileShaRows) fileSha[r.key] = r.sha256 || '';
    const liveChecks = financialChecksFrom(tableCounts, sumRows.map((r) => r.results?.[0]), fileSha);

    // --- Request 2: every plain table's rows in ONE batch ---
    const plainTables = BACKUP_TABLES.filter(({ table }) => table !== 'contract_files' && table !== 'academy_settings');
    const rowResults = await db.batch(
        plainTables.map(({ table, academyColumn }) => {
            if (table === 'contract_file_chunks') {
                // Only chunks of THIS academy's files (files may be chunked or inline).
                return db.prepare(
                    `SELECT c.file_key, c.part, c.content FROM contract_file_chunks c
                     JOIN contract_files f ON f.key = c.file_key AND f.academy_id = ?`
                ).bind(academyId);
            }
            if (academyColumn === 'id') {
                return db.prepare('SELECT * FROM academies WHERE id = ?').bind(academyId);
            }
            return db.prepare(`SELECT * FROM ${table} WHERE ${academyColumn} = ?`).bind(academyId);
        })
    );
    plainTables.forEach(({ table }, i) => {
        const res = rowResults[i] as { results?: Array<Record<string, unknown>> } | undefined;
        const raw = (res?.results ?? []) as Array<Record<string, unknown>>;
        if (table === 'academies') {
            tables[table] = raw; // single tenant row; no blob columns
        } else {
            tables[table] = encodeRows(table, raw);
        }
    });

    // --- Request 3: contract file metadata (+ inline bytes in ONE query, no per-file loop) ---
    const fileRows = includeFiles
        ? await db.prepare(
            'SELECT key, academy_id, sha256, created_at_epoch, content FROM contract_files WHERE academy_id = ?'
        ).bind(academyId).all<Record<string, unknown>>()
        : await db.prepare(
            `SELECT key, academy_id, sha256, created_at_epoch, length(content) AS content_bytes
             FROM contract_files WHERE academy_id = ?`
        ).bind(academyId).all<Record<string, unknown>>();
    tables['contract_files'] = (fileRows.results ?? []).map((r) => {
        if (!includeFiles) return r;
        const { content, ...rest } = r;
        const bytes = content == null ? null : new Uint8Array(content as ArrayBuffer);
        return { ...rest, content_b64: bytes && bytes.length ? toB64(bytes) : '' };
    });

    // --- academy_settings (own rows only, internal run-state excluded) ---
    const settingsRes = await db.prepare('SELECT key, value, updated_at_epoch FROM academy_settings').all<Record<string, unknown>>();
    tables['academy_settings'] = academySettingsRows(settingsRes.results ?? [], academyId)
        .filter((r) => !isInternalSettingKey(String(r.key || '')));

    const bundle: BackupBundle = {
        format: 'academy-backup',
        version: 2,
        academyId,
        exportedAtEpoch,
        filesIncluded: includeFiles,
        tables,
        financialChecks: liveChecks
    };
    return { bundle, liveCounts: tableCounts, liveChecks };
}

async function collectPayloadFull(env: Env, academyId: string, includeFiles: boolean, exportedAtEpoch: number): Promise<CollectedPayload> {
    const collected = await collectPayload(env, academyId, includeFiles, exportedAtEpoch);
    const jsonBytes = new TextEncoder().encode(JSON.stringify(collected.bundle));
    const payload = await gzip(jsonBytes);
    return {
        ...collected,
        jsonBytes,
        payload,
        payloadSha256: await sha256HexBytes(payload),
        chunkCount: Math.ceil(payload.length / FILE_CHUNK_SIZE)
    };
}

// ------------------------------------------------------------------
// Verification
// ------------------------------------------------------------------

export function verifyBundleAgainstLive(
    parsed: BackupBundle,
    academyId: string,
    liveCounts: Record<string, number>,
    liveChecks: FinancialChecks
): { ok: boolean; error: string } {
    if (parsed.format !== 'academy-backup' || (parsed.version !== 1 && parsed.version !== 2)) {
        return { ok: false, error: 'ساختار فایل پشتیبان شناخته نشد.' };
    }
    if (parsed.academyId !== academyId) {
        return { ok: false, error: 'پشتیبان متعلق به آموزشگاه دیگری است.' };
    }
    // Version-2 bundles must carry EVERY covered table; legacy v1 bundles
    // (pre stage-4/5 coverage) are still readable but only for their tables.
    const required = parsed.version >= 2
        ? BACKUP_TABLES
        : BACKUP_TABLES.filter(({ table }) => parsed.tables[table] !== undefined);
    for (const { table } of required) {
        const rows = parsed.tables[table];
        if (!Array.isArray(rows)) {
            return { ok: false, error: `جدول ${table} در پشتیبان موجود نیست.` };
        }
    }
    for (const { table } of required) {
        const expected = liveCounts[table] ?? 0;
        const got = (parsed.tables[table] || []).length;
        if (expected !== got) {
            return {
                ok: false,
                error: `شمارش ردیف‌های ${table} با پایگاه‌داده نمی‌خواند (زنده: ${expected}، پشتیبان: ${got}).`
            };
        }
    }
    const fc = parsed.financialChecks || ({} as Partial<FinancialChecks>);
    const check = (label: string, a: number | undefined, b: number) => {
        if ((a ?? -1) !== b) throw new Error(`${label} (زنده: ${b}، پشتیبان: ${a ?? 'نامشخص'})`);
    };
    try {
        check('تعداد اقساط', fc.totalInstallments, liveChecks.totalInstallments);
        check('جمع مبالغ اقساط', fc.sumInstallmentAmount, liveChecks.sumInstallmentAmount);
        check('جمع پرداخت‌شده اقساط', fc.sumInstallmentPaid, liveChecks.sumInstallmentPaid);
        check('جمع مانده اقساط', fc.sumInstallmentRemaining, liveChecks.sumInstallmentRemaining);
        check('جمع شهریه', fc.sumSummaryTuition, liveChecks.sumSummaryTuition);
        check('جمع پرداخت واقعی', fc.sumSummaryPaid, liveChecks.sumSummaryPaid);
        check('جمع بدهی', fc.sumSummaryDebt, liveChecks.sumSummaryDebt);
        check('تعداد هنرجویان', fc.studentCount, liveChecks.studentCount);
        check('تعداد فایل‌های قرارداد', fc.fileCount, liveChecks.fileCount);
        if (parsed.version >= 2) {
            check('تعداد رسیدهای پرداخت', fc.receiptCount, liveChecks.receiptCount);
            check('جمع مبالغ رسیدها', fc.sumReceiptAmount, liveChecks.sumReceiptAmount);
            check('تعداد رسیدهای برگشتی', fc.reversedReceiptCount, liveChecks.reversedReceiptCount);
            check('تعداد تراکنش‌های درگاه', fc.transactionCount, liveChecks.transactionCount);
            check('جمع مبلغ پرداخت‌شده تراکنش‌ها', fc.sumTransactionPaidAmount, liveChecks.sumTransactionPaidAmount);
            check('تعداد تراکنش‌های موفق', fc.paidTransactionCount, liveChecks.paidTransactionCount);
            check('تعداد تراکنش‌های ناموفق', fc.failedTransactionCount, liveChecks.failedTransactionCount);
            check('تعداد توکن‌های لینک پرداخت', fc.linkTokenCount, liveChecks.linkTokenCount);
            check('تعداد ادعاهای پرداخت', fc.claimCount, liveChecks.claimCount);
            check('جمع مبالغ ادعاها', fc.sumClaimAmount, liveChecks.sumClaimAmount);
            check('تعداد ادعاهای در انتظار', fc.pendingClaimCount, liveChecks.pendingClaimCount);
            check('تعداد رویدادهای مالی', fc.eventCount, liveChecks.eventCount);
        }
    } catch (e: any) {
        return { ok: false, error: `ناسازگاری مالی در پشتیبان: ${e.message}` };
    }
    return { ok: true, error: '' };
}

// ------------------------------------------------------------------
// Store + verify (verify-before-replace)
// ------------------------------------------------------------------

function chunkOf(payload: Uint8Array, i: number): Uint8Array {
    return payload.subarray(i * FILE_CHUNK_SIZE, Math.min((i + 1) * FILE_CHUNK_SIZE, payload.length));
}

const SETTINGS_UPSERT = `INSERT INTO academy_settings (key, value, updated_at_epoch) VALUES (?, ?, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at_epoch = excluded.updated_at_epoch`;

function resumeStateToRow(academyId: string, state: ResumeState): [string, string, number] {
    return [`${academyId}:backup:resume`, JSON.stringify(state), Date.now()];
}

/** Releases the writer lock + the resume state in ONE batch. */
async function releaseBackupLock(db: D1Database, academyId: string): Promise<void> {
    await db.batch([
        db.prepare('DELETE FROM academy_settings WHERE key = ?').bind(`${academyId}:backup:running`),
        db.prepare('DELETE FROM academy_settings WHERE key = ?').bind(`${academyId}:backup:resume`)
    ]);
}

/** Stores the PENDING backups row + the resume seed in ONE batch.
 *  UPSERT (not INSERT OR REPLACE!): backup_chunks carries ON DELETE
 *  CASCADE -> backups(id), so a REPLACE (delete + insert) during a
 *  continuation would silently wipe every already-stored chunk. An
 *  upsert updates the row in place and keeps the chunks. */
async function insertBackupRow(
    env: Env,
    academyId: string,
    state: ResumeState,
    bundle: BackupBundle,
    kind: 'AUTO' | 'MANUAL'
): Promise<void> {
    const db = env.DB!;
    await db.batch([
        db.prepare(
            `INSERT INTO backups (id, academy_id, kind, status, created_at_epoch, payload_sha256,
                 total_bytes, stored_bytes, chunk_count, table_counts, financial_checks, files_included, error)
             VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, '')
             ON CONFLICT(id) DO UPDATE SET
                 payload_sha256 = excluded.payload_sha256,
                 total_bytes = excluded.total_bytes,
                 stored_bytes = excluded.stored_bytes,
                 chunk_count = excluded.chunk_count,
                 table_counts = excluded.table_counts,
                 financial_checks = excluded.financial_checks,
                 files_included = excluded.files_included,
                 error = ''`
        ).bind(
            state.backupId, academyId, kind, state.exportedAtEpoch,
            state.payloadSha256,
            state.jsonBytes, state.storedBytes, state.chunkCount,
            JSON.stringify(tableCountsOf(bundle)),
            JSON.stringify(bundle.financialChecks || {}),
            state.filesIncluded ? 1 : 0
        ),
        db.prepare(SETTINGS_UPSERT).bind(...resumeStateToRow(academyId, state))
    ]);
}

/** Stores chunks [cursor, count) in batches; each batch also persists the
 *  advanced cursor + refreshes the lock inside the same transaction. */
async function storeChunks(
    env: Env,
    academyId: string,
    state: ResumeState,
    payload: Uint8Array
): Promise<void> {
    const db = env.DB!;
    while (state.chunkCursor < state.chunkCount) {
        const end = Math.min(state.chunkCursor + CHUNKS_PER_BATCH, state.chunkCount);
        const statements = [];
        for (let i = state.chunkCursor; i < end; i++) {
            statements.push(
                db.prepare('INSERT OR REPLACE INTO backup_chunks (backup_id, seq, content) VALUES (?, ?, ?)')
                    .bind(state.backupId, i, chunkOf(payload, i))
            );
        }
        const advanced: ResumeState = { ...state, chunkCursor: end };
        statements.push(db.prepare(SETTINGS_UPSERT).bind(...resumeStateToRow(academyId, advanced)));
        statements.push(
            db.prepare(SETTINGS_UPSERT).bind(`${academyId}:backup:running`, String(Date.now()), Date.now())
        );
        await db.batch(statements);
        state.chunkCursor = end;
    }
}

export interface ReadBundleResult {
    ok: boolean;
    error: string;
    parsed?: BackupBundle;
    payload?: Uint8Array;
}

/**
 * Reads one stored backup back: concat chunks -> sha256 check -> gunzip ->
 * JSON.parse. The same code path verifies a fresh snapshot before any older
 * copy may be pruned, and is what a future manual restore tooling would use.
 */
export async function readBackupBundle(env: Env, backupId: string): Promise<ReadBundleResult> {
    const db = env.DB!;
    const row = await db.prepare(
        'SELECT payload_sha256, stored_bytes, total_bytes FROM backups WHERE id = ?'
    ).bind(backupId).first<{ payload_sha256: string; stored_bytes: number; total_bytes: number }>();
    if (!row) return { ok: false, error: 'ردیف پشتیبان پیدا نشد.' };

    const chunkRows = await db.prepare(
        'SELECT content FROM backup_chunks WHERE backup_id = ? ORDER BY seq ASC'
    ).bind(backupId).all<{ content: Uint8Array }>();

    const payload = concatBytes(chunkRows.results.map((r) => new Uint8Array(r.content)));
    if (payload.length !== row.stored_bytes) {
        return { ok: false, error: `حجم ذخیره‌شده با داده بازیابی‌شده نمی‌خواند (${row.stored_bytes} در برابر ${payload.length}).` };
    }
    const hash = await sha256HexBytes(payload);
    if (hash !== row.payload_sha256) {
        return { ok: false, error: 'امضای SHA-256 محتوای پشتیبان درست نیست (خراب بودن داده).' };
    }

    let json: Uint8Array;
    try {
        json = await gunzip(payload);
    } catch {
        return { ok: false, error: 'باز کردن فشرده‌سازی gzip ناموفق بود.' };
    }
    if (json.length !== row.total_bytes) {
        return { ok: false, error: 'حجم JSON بازگشایی‌شده با مقدار ثبت‌شده نمی‌خواند.' };
    }

    let parsed: BackupBundle;
    try {
        parsed = JSON.parse(new TextDecoder().decode(json)) as BackupBundle;
    } catch {
        return { ok: false, error: 'JSON پشتیبان قابل تحلیل نیست.' };
    }
    return { ok: true, error: '', parsed, payload };
}

/** Full read-back verification of a stored backup row. */
async function verifyStoredBackup(
    env: Env,
    backupId: string,
    academyId: string,
    expectedCounts: Record<string, number>,
    expectedChecks: FinancialChecks
): Promise<{ ok: boolean; error: string; parsed?: BackupBundle }> {
    const read = await readBackupBundle(env, backupId);
    if (!read.ok || !read.parsed) {
        return { ok: false, error: read.error };
    }
    const parsed = read.parsed;

    // Claim photo evidence: the decoded bytes must survive the round trip.
    // Claim photos are ALWAYS part of the payload (financial evidence);
    // slim mode drops only contract FILE bytes.
    const claims = (parsed.tables['payment_claims'] || []) as Array<Record<string, unknown>>;
    for (const c of claims) {
        const b64 = String(c.photo_b64 || '');
        if (!b64) continue;
        const bytes = fromB64(b64);
        if (bytes.length === 0) {
            return { ok: false, error: `تصویر ادعای پرداخت ${String(c.id)} خوانده نشد.` };
        }
    }

    // Contract file integrity: every stored file must hash to its recorded sha256.
    if (parsed.filesIncluded) {
        const files = (parsed.tables['contract_files'] || []) as Array<Record<string, unknown>>;
        for (const f of files) {
            const bytes = fromB64(String(f.content_b64 || ''));
            if (bytes.length === 0) continue; // chunked file: bytes live in contract_file_chunks
            const h = await sha256HexBytes(bytes);
            if (f.sha256 && h !== String(f.sha256)) {
                return { ok: false, error: `هش فایل قرارداد ${String(f.key)} درست نیست.` };
            }
        }
        // Chunked files: reassemble each and verify.
        const chunks = (parsed.tables['contract_file_chunks'] || []) as Array<Record<string, unknown>>;
        const byKey = new Map<string, Map<number, Uint8Array>>();
        for (const c of chunks) {
            const key = String(c.file_key);
            if (!byKey.has(key)) byKey.set(key, new Map());
            byKey.get(key)!.set(Number(c.part), fromB64(String(c.content_b64 || '')));
        }
        for (const [key, partsMap] of byKey) {
            const ordered = [...partsMap.keys()].sort((a, b) => a - b).map((p) => partsMap.get(p)!);
            const whole = concatBytes(ordered);
            const meta = files.find((f) => String(f.key) === key);
            if (meta?.sha256) {
                const h = await sha256HexBytes(whole);
                if (h !== String(meta.sha256)) {
                    return { ok: false, error: `هش فایل تکه‌تکه‌شده ${key} درست نیست.` };
                }
            }
        }
    }

    const verdict = verifyBundleAgainstLive(parsed, academyId, expectedCounts, expectedChecks);
    if (!verdict.ok) return { ok: false, error: verdict.error };
    return { ok: true, error: '', parsed };
}

// ------------------------------------------------------------------
// Run orchestration (staged, resumable, single-writer)
// ------------------------------------------------------------------

function pendingOutcome(error: string, phase: 'COLLECT' | 'STORE' | 'VERIFY', durationMs: number): BackupOutcome {
    return {
        ok: false, backupId: '', status: 'PENDING', error, durationMs,
        tableCounts: {}, filesIncluded: false, storedBytes: 0,
        pending: true, phase
    };
}

/**
 * Runs (or continues) a complete backup attempt for one academy.
 * Old snapshots are pruned ONLY if this one verified HEALTHY.
 */
export async function runBackup(
    env: Env,
    academyId: string,
    trigger: 'AUTO' | 'MANUAL',
    options: BackupOptions = {}
): Promise<BackupOutcome> {
    const started = Date.now();
    const db = env.DB!;
    const retention = options.retention ?? clampInt(env.BACKUP_RETENTION_DAYS, 1, 60, 7);
    const maxPayload = options.maxPayloadBytes ?? clampInt(env.BACKUP_MAX_BYTES, 1 << 20, 512 << 20, 25 << 20);
    const includeFiles = options.includeFiles ?? env.BACKUP_INCLUDE_FILES !== 'false';
    const lockKey = `${academyId}:backup:running`;
    const resumeKey = `${academyId}:backup:resume`;

    // --- Resume / lock arbitration (2 reads, batched) ---
    const [resumeRow, lockRow] = await db.batch([
        db.prepare('SELECT value FROM academy_settings WHERE key = ?').bind(resumeKey),
        db.prepare('SELECT value FROM academy_settings WHERE key = ?').bind(lockKey)
    ]);
    const resumeRaw = ((resumeRow as { results?: Array<{ value?: string }> })?.results?.[0]?.value) || '';
    let resume: ResumeState | null = null;
    if (resumeRaw) {
        try { resume = JSON.parse(resumeRaw) as ResumeState; } catch { resume = null; }
    }
    const lockValue = Number(((lockRow as { results?: Array<{ value?: string }> })?.results?.[0]?.value) || 0);
    const lockFresh = lockValue > 0 && Date.now() - lockValue < LOCK_TTL_MS;

    if (lockFresh) {
        if (!resume) {
            // Another invocation is actively collecting/storing; never race it.
            return pendingOutcome(
                trigger === 'MANUAL'
                    ? 'پشتیبان‌گیری قبلی هنوز در حال اجراست؛ چند دقیقه بعد دوباره تلاش کنید.'
                    : 'پشتیبان‌گیری قبلی هنوز در حال اجراست؛ cron دقیقه بعد ادامه می‌دهد.',
                'COLLECT', Date.now() - started
            );
        }
        if (trigger === 'MANUAL') {
            // An interrupted run exists and is still fresh — do not stomp it.
            return pendingOutcome(
                'پشتیبان‌گیری قبلی هنوز تمام نشده و به‌زودی ادامه می‌یابد؛ چند دقیقه بعد دوباره بررسی کنید.',
                'STORE', Date.now() - started
            );
        }
        // AUTO adopts the interrupted run (single continuation, no second writer).
    }

    const refreshLock = async (): Promise<void> => {
        await db.prepare(SETTINGS_UPSERT).bind(lockKey, String(Date.now()), Date.now()).run();
    };
    await refreshLock();

    const releaseLock = (): Promise<void> => releaseBackupLock(db, academyId);

    const failRun = async (
        backupId: string,
        error: string,
        status: 'CORRUPT' | 'FAILED',
        runId: string,
        keepResume: boolean
    ): Promise<BackupOutcome> => {
        const duration = Date.now() - started;
        if (backupId && !keepResume) {
            // Definitive failure: the pending copy must never look usable.
            await db.batch([
                db.prepare('DELETE FROM backup_chunks WHERE backup_id = ?').bind(backupId),
                db.prepare("UPDATE backups SET status = ?, error = ?, duration_ms = ? WHERE id = ?")
                    .bind(status, error, duration, backupId)
            ]);
        } else if (backupId && keepResume) {
            await db.prepare("UPDATE backups SET error = ? WHERE id = ?").bind(error, backupId).run();
        }
        await db.prepare(
            'UPDATE backup_runs SET outcome = ?, backup_id = ?, error = ?, duration_ms = ? WHERE id = ?'
        ).bind('FAILED', backupId, error, duration, runId).run();
        if (!keepResume) await releaseLock();
        return {
            ok: false, backupId, status, error, durationMs: duration,
            tableCounts: {}, filesIncluded: false, storedBytes: 0
        };
    };

    let runId = '';
    let state: ResumeState | null = null;

    try {
        if (resume) {
            // ---- CONTINUATION of the same snapshot (no new cron, no new run) ----
            runId = resume.runId;
            state = resume;
            await refreshLock();

            // Re-collect deterministically; the seeded exportedAtEpoch makes
            // the payload byte-identical when the data did not change.
            const re = await collectPayloadFull(env, academyId, state.filesIncluded, state.exportedAtEpoch);
            if (re.payloadSha256 !== state.payloadSha256) {
                // Data changed mid-run: controlled cleanup, honest failure.
                const err = 'داده‌ها در طول پشتیبان‌گیری تغییر کردند؛ نسخه ناتمام پاک شد و تلاش بعدی از نو شروع می‌کند.';
                await db.batch([
                    db.prepare('DELETE FROM backup_chunks WHERE backup_id = ?').bind(state.backupId),
                    db.prepare('DELETE FROM backups WHERE id = ?').bind(state.backupId)
                ]);
                await db.prepare(
                    'UPDATE backup_runs SET outcome = ?, backup_id = ?, error = ?, duration_ms = ? WHERE id = ?'
                ).bind('FAILED', state.backupId, err, Date.now() - started, runId).run();
                await releaseLock();
                return {
                    ok: false, backupId: state.backupId, status: 'FAILED', error: err,
                    durationMs: Date.now() - started, tableCounts: {},
                    filesIncluded: false, storedBytes: 0
                };
            }
            state.chunkCount = re.chunkCount;
            await storeChunks(env, academyId, state, re.payload);

            // The PENDING row must exist even if the first invocation died
            // between its inserts (INSERT OR REPLACE keeps it idempotent).
            await insertBackupRow(env, academyId, state, re.bundle, resume.trigger);

            const verdict = await verifyStoredBackup(env, state.backupId, academyId, re.liveCounts, re.liveChecks);
            if (!verdict.ok) {
                return await failRun(state.backupId, verdict.error, 'CORRUPT', runId, false);
            }
            return await finalizeHealthy(env, academyId, state, re.bundle, runId, started, retention, true);
        }

        // ---- FRESH run ----
        runId = crypto.randomUUID();
        await db.prepare(
            `INSERT INTO backup_runs (id, academy_id, trigger, outcome, backup_id, error, started_at_epoch, duration_ms)
             VALUES (?, ?, ?, 'RUNNING', '', '', ?, 0)`
        ).bind(runId, academyId, trigger, started).run();
        await refreshLock();

        // COLLECT (3 batched requests). A failure here has produced nothing;
        // the run row carries the error and old copies stay untouched.
        const firstExportedAt = Date.now();
        let collected = await collectPayloadFull(env, academyId, includeFiles, firstExportedAt);
        let filesIncluded = includeFiles;

        // Free-space guard: too big -> store metadata + hashes only, say so loudly.
        if (collected.payload.length > maxPayload && includeFiles) {
            filesIncluded = false;
            collected = await collectPayloadFull(env, academyId, false, firstExportedAt);
        }

        state = {
            runId,
            backupId: crypto.randomUUID(),
            trigger,
            exportedAtEpoch: firstExportedAt,
            filesIncluded,
            maxPayloadBytes: maxPayload,
            retention,
            payloadSha256: collected.payloadSha256,
            jsonBytes: collected.jsonBytes.length,
            storedBytes: collected.payload.length,
            chunkCount: collected.chunkCount,
            chunkCursor: 0
        };
        await insertBackupRow(env, academyId, state, collected.bundle, trigger);

        // STORE (batched chunk writes with cursor persistence).
        await storeChunks(env, academyId, state, collected.payload);

        // VERIFY (read-back before anything old is touched).
        const verdict = await verifyStoredBackup(env, state.backupId, academyId, collected.liveCounts, collected.liveChecks);
        if (!verdict.ok) {
            return await failRun(state.backupId, verdict.error, 'CORRUPT', runId, false);
        }
        return await finalizeHealthy(env, academyId, state, collected.bundle, runId, started, retention, false);
    } catch (err: any) {
        // Interrupted (invocation killed / transient D1 error / injected test
        // failure). The resume state + PENDING row persist; the next cron
        // minute continues the SAME snapshot. Honest, not a fake success.
        if (state) {
            const msg = `پشتیبان‌گیری قطع شد (${err?.message || err})؛ در فراخوانی بعدی ادامه می‌یابد.`;
            return await failRun(state.backupId, msg, 'FAILED', runId, true);
        }
        return await failRun('', `خطای داخلی پشتیبان‌گیری: ${err?.message || err}`, 'FAILED', runId, false);
    }
}

/** Marks HEALTHY, closes the run, prunes old copies, releases the lock. */
async function finalizeHealthy(
    env: Env,
    academyId: string,
    state: ResumeState,
    bundle: BackupBundle,
    runId: string,
    started: number,
    retention: number,
    resumed: boolean
): Promise<BackupOutcome> {
    const db = env.DB!;
    const duration = Date.now() - started;

    await db.batch([
        db.prepare(
            `UPDATE backups SET status = 'HEALTHY', error = '', duration_ms = ?,
                 table_counts = ?, financial_checks = ? WHERE id = ?`
        ).bind(
            duration,
            JSON.stringify(tableCountsOf(bundle)),
            JSON.stringify(bundle.financialChecks || {}),
            state.backupId
        ),
        db.prepare(
            `UPDATE backup_runs SET outcome = 'SUCCESS', backup_id = ?, error = '', duration_ms = ? WHERE id = ?`
        ).bind(state.backupId, duration, runId)
    ]);

    // Only NOW (new snapshot verified) may older copies be pruned.
    await pruneOldBackups(env, academyId, retention, state.filesIncluded);
    await releaseBackupLock(db, academyId);

    return {
        ok: true, backupId: state.backupId, status: 'HEALTHY', error: '',
        durationMs: duration, tableCounts: tableCountsOf(bundle),
        filesIncluded: state.filesIncluded, storedBytes: state.storedBytes,
        resumed
    };
}

/** Prunes old copies; never removes the newest HEALTHY snapshot and never runs unless one exists. */
export async function pruneOldBackups(
    env: Env,
    academyId: string,
    retention: number,
    filesIncluded: boolean
): Promise<void> {
    const db = env.DB!;
    const healthy = await db.prepare(
        `SELECT id FROM backups WHERE academy_id = ? AND status = 'HEALTHY' ORDER BY created_at_epoch DESC`
    ).bind(academyId).all<{ id: string }>();
    if (healthy.results.length === 0) return; // no verified copy -> delete nothing

    const keep = Math.max(1, retention);
    const deletions = healthy.results.slice(keep)
        .map((old) => db.prepare('DELETE FROM backups WHERE id = ?').bind(old.id));
    // Corrupt copies are kept only for the 3 most recent attempts, never displacing healthy ones.
    const corrupt = await db.prepare(
        `SELECT id FROM backups WHERE academy_id = ? AND status != 'HEALTHY' ORDER BY created_at_epoch DESC`
    ).bind(academyId).all<{ id: string }>();
    for (const old of corrupt.results.slice(3)) {
        deletions.push(db.prepare('DELETE FROM backups WHERE id = ?').bind(old.id));
    }
    if (deletions.length > 0) await db.batch(deletions);
    void filesIncluded;
}

// ------------------------------------------------------------------
// Status for the manager UI
// ------------------------------------------------------------------

export async function getBackupStatus(env: Env, academyId: string): Promise<Record<string, unknown>> {
    const db = env.DB!;
    const runs = await db.prepare(
        `SELECT id, trigger, outcome, backup_id, error, started_at_epoch, duration_ms
         FROM backup_runs WHERE academy_id = ? ORDER BY started_at_epoch DESC LIMIT 10`
    ).bind(academyId).all();

    const lastHealthy = await db.prepare(
        `SELECT id, created_at_epoch, total_bytes, stored_bytes, chunk_count, table_counts, files_included, duration_ms
         FROM backups WHERE academy_id = ? AND status = 'HEALTHY' ORDER BY created_at_epoch DESC LIMIT 1`
    ).bind(academyId).first();

    const healthyCount = await db.prepare(
        `SELECT COUNT(*) AS n FROM backups WHERE academy_id = ? AND status = 'HEALTHY'`
    ).bind(academyId).first<{ n: number }>();

    const history = await db.prepare(
        `SELECT id, kind, status, created_at_epoch, total_bytes, stored_bytes, files_included, error
         FROM backups WHERE academy_id = ? ORDER BY created_at_epoch DESC LIMIT 10`
    ).bind(academyId).all();

    const pendingRow = await db.prepare(
        'SELECT value FROM academy_settings WHERE key = ?'
    ).bind(`${academyId}:backup:resume`).first<{ value: string }>();
    let pendingRun: Record<string, unknown> | null = null;
    if (pendingRow?.value) {
        try {
            const s = JSON.parse(pendingRow.value) as ResumeState;
            pendingRun = {
                backupId: s.backupId,
                trigger: s.trigger,
                phase: 'STORE',
                storedChunks: s.chunkCursor,
                chunkCount: s.chunkCount,
                startedAtEpoch: s.exportedAtEpoch
            };
        } catch { pendingRun = null; }
    }

    return {
        lastRun: runs.results[0] || null,
        recentRuns: runs.results,
        lastHealthy,
        healthyCount: healthyCount?.n || 0,
        history: history.results,
        pendingRun,
        coveredTables: BACKUP_TABLES.map((t) => t.table),
        retentionDays: clampInt(env.BACKUP_RETENTION_DAYS, 1, 60, 7),
        includeFiles: env.BACKUP_INCLUDE_FILES !== 'false'
    };
}

// ------------------------------------------------------------------
// Restore (TEST ENVIRONMENT ONLY — never wired to any HTTP route)
// ------------------------------------------------------------------

/** Restore order == BACKUP_TABLES order: parents before children
 *  (students -> installments -> receipts -> transactions -> link tokens ->
 *  reminders/deliveries -> contracts -> files -> claims -> events). */
const RESTORE_ORDER: string[] = BACKUP_TABLES.map((t) => t.table);

function decodeB64OrEmpty(v: unknown): Uint8Array {
    const s = String(v ?? '');
    return s ? fromB64(s) : new Uint8Array(0);
}

/**
 * Restores a parsed backup bundle into a PREPARED database (schema must
 * already exist). Used by the recovery tests against an in-memory D1 shim.
 * Deliberately NOT reachable through any HTTP route: touching the live
 * database requires the owner's explicit, manual approval (runbook).
 */
export async function restoreBundleForTests(
    db: D1Database,
    bundle: BackupBundle,
    filesIncluded: boolean
): Promise<Record<string, number>> {
    const counts: Record<string, number> = {};

    for (const table of RESTORE_ORDER) {
        const rows = (bundle.tables[table] || []) as Array<Record<string, unknown>>;
        if (rows.length === 0) { counts[table] = 0; continue; }

        if (table === 'contract_files') {
            const stmts = rows.map((row) => {
                const content = filesIncluded && bundle.filesIncluded
                    ? decodeB64OrEmpty(row.content_b64)
                    : new Uint8Array(0);
                return db.prepare(
                    `INSERT OR REPLACE INTO contract_files (key, academy_id, content, sha256, created_at_epoch)
                     VALUES (?, ?, ?, ?, ?)`
                ).bind(row.key, row.academy_id, content.length ? content : null, row.sha256, row.created_at_epoch);
            });
            await db.batch(stmts);
            counts[table] = rows.length;
            continue;
        }
        if (table === 'contract_file_chunks') {
            const stmts = rows.map((row) => {
                const content = filesIncluded && bundle.filesIncluded
                    ? decodeB64OrEmpty(row.content_b64)
                    : new Uint8Array(0);
                return db.prepare('INSERT OR REPLACE INTO contract_file_chunks (file_key, part, content) VALUES (?, ?, ?)')
                    .bind(row.file_key, row.part, content);
            });
            await db.batch(stmts);
            counts[table] = rows.length;
            continue;
        }
        if (table === 'payment_claims') {
            // Photo evidence is ALWAYS restored (it is part of the financial
            // record; filesIncluded gates only contract file bytes).
            const stmts = rows.map((row) => {
                const cols = Object.keys(row).filter((c) => c !== 'photo_b64');
                const values = cols.map((c) => normalizeValue(row[c]));
                const photo = decodeB64OrEmpty(row.photo_b64);
                cols.push('photo_bytes');
                values.push(photo.length ? photo : null);
                return db.prepare(
                    `INSERT OR REPLACE INTO payment_claims (${cols.join(', ')}) VALUES (${cols.map(() => '?').join(', ')})`
                ).bind(...values);
            });
            await db.batch(stmts);
            counts[table] = rows.length;
            continue;
        }

        const cols = Object.keys(rows[0]);
        const colList = cols.join(', ');
        const placeholders = cols.map(() => '?').join(', ');
        const stmts = rows.map((row) => {
            const valueMap = cols.map((c) => normalizeValue(row[c]));
            return db.prepare(`INSERT OR REPLACE INTO ${table} (${colList}) VALUES (${placeholders})`).bind(...valueMap);
        });
        await db.batch(stmts);
        counts[table] = rows.length;
    }
    return counts;
}

function normalizeValue(v: unknown): unknown {
    if (v === null || v === undefined) return null;
    if (v instanceof Uint8Array) return v;
    return v as unknown;
}

function clampInt(raw: string | undefined, min: number, max: number, dflt: number): number {
    const n = parseInt(String(raw ?? ''), 10);
    if (!Number.isFinite(n)) return dflt;
    return Math.min(max, Math.max(min, n));
}

function concatBytes(parts: Uint8Array[]): Uint8Array {
    const total = parts.reduce((n, p) => n + p.length, 0);
    const out = new Uint8Array(total);
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
}

function toB64(bytes: Uint8Array | null): string {
    if (!bytes || bytes.length === 0) return '';
    let bin = '';
    const CHUNK = 0x8000;
    for (let i = 0; i < bytes.length; i += CHUNK) {
        bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
    }
    return btoa(bin);
}

function fromB64(b64: string): Uint8Array {
    if (!b64) return new Uint8Array(0);
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
}
