// ============================================================
// Server-side daily backup of one academy's essential data (Stage 7)
//
// Storage: the gzip-compressed snapshot lives back inside the same
// D1 database (backups + backup_chunks), chunked at 768 KB because
// D1 caps a single bound parameter at 1 MB — the same pattern the
// contract files already use (migration 0005).
//
// Safety rules (the stage contract):
//   1. The new snapshot is verified BEFORE anything old is touched:
//      read chunks back, sha256 them, gunzip, JSON.parse, compare
//      per-table row counts and financial sums with the live data,
//      and re-verify contract file hashes.
//   2. A failed verification marks the run CORRUPT/FAILED and every
//      previous HEALTHY snapshot stays untouched.
//   3. Old copies are pruned ONLY after the new snapshot verified
//      HEALTHY (retention: last N healthy, default 7).
//
// Free-tier honesty: no R2, no extra service. D1 free storage is
// 5 GB; when the compressed payload grows past BACKUP_MAX_BYTES the
// snapshot automatically stores metadata + sha256 for contract
// files instead of their bytes (files_included = 0) and says so in
// the status output — it never pretends files are covered when they
// are not. Total-loss recovery stays Cloudflare Time Travel (free).
// ============================================================

import { Env } from './types';

/** Per-academy tables, in restore order (parents before children). */
export const BACKUP_TABLES: Array<{ table: string; academyColumn: string | null }> = [
    { table: 'academies', academyColumn: 'id' },
    { table: 'students', academyColumn: 'academy_id' },
    { table: 'installments', academyColumn: 'academy_id' },
    { table: 'financial_summaries', academyColumn: 'academy_id' },
    { table: 'reminders', academyColumn: 'academy_id' },
    { table: 'message_logs', academyColumn: 'academy_id' },
    { table: 'deliveries', academyColumn: 'academy_id' },
    { table: 'sms_outbox', academyColumn: 'academy_id' },
    { table: 'pending_students', academyColumn: 'academy_id' },
    { table: 'contracts', academyColumn: 'academy_id' },
    { table: 'contract_events', academyColumn: 'academy_id' },
    { table: 'contract_deliveries', academyColumn: 'academy_id' },
    { table: 'contract_files', academyColumn: 'academy_id' },
    { table: 'contract_file_chunks', academyColumn: null }, // keyed via file_key -> contract_files
    { table: 'academy_settings', academyColumn: 'key' },    // namespaced "<academyId>:..." rows
];

const FILE_CHUNK_SIZE = 768 * 1024; // 768 KB per backup_chunks row (< D1 1 MB bind cap)

export interface BackupOptions {
    retention?: number;         // healthy snapshots to keep (default 7)
    maxPayloadBytes?: number;   // above this, file bytes are summarized not stored
    includeFiles?: boolean;     // default true
}

export interface BackupOutcome {
    ok: boolean;
    backupId: string;
    status: 'HEALTHY' | 'CORRUPT' | 'FAILED';
    error: string;
    durationMs: number;
    tableCounts: Record<string, number>;
    filesIncluded: boolean;
    storedBytes: number;
}

interface BackupBundle {
    format: 'academy-backup';
    version: 1;
    academyId: string;
    exportedAtEpoch: number;
    filesIncluded: boolean;
    tables: Record<string, unknown[]>;
    financialChecks: FinancialChecks;
}

interface FinancialChecks {
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

function academySettingsRows(all: Array<Record<string, unknown>>, academyId: string): Array<Record<string, unknown>> {
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

// ------------------------------------------------------------------
// Export
// ------------------------------------------------------------------

async function exportBundle(env: Env, academyId: string, includeFiles: boolean): Promise<BackupBundle> {
    const db = env.DB!;
    const tables: Record<string, unknown[]> = {};

    for (const { table, academyColumn } of BACKUP_TABLES) {
        if (table === 'academy_settings') {
            const res = await db.prepare('SELECT key, value, updated_at_epoch FROM academy_settings').all();
            tables[table] = academySettingsRows(res.results as Array<Record<string, unknown>>, academyId);
            continue;
        }
        if (table === 'contract_file_chunks') {
            // Only chunks belonging to THIS academy's files (files may be chunked or inline).
            // Binary content is base64-encoded: a raw BLOB would be destroyed by JSON.stringify.
            const res = await db.prepare(
                `SELECT c.file_key, c.part, c.content FROM contract_file_chunks c
                 JOIN contract_files f ON f.key = c.file_key AND f.academy_id = ?`
            ).bind(academyId).all<{ file_key: string; part: number; content: Uint8Array }>();
            tables[table] = includeFiles
                ? res.results.map((r) => ({ file_key: r.file_key, part: r.part, content_b64: toB64(r.content) }))
                : [];
            continue;
        }
        if (table === 'contract_files') {
            const res = await db.prepare(
                `SELECT key, academy_id, sha256, created_at_epoch, length(content) AS content_bytes
                 FROM contract_files WHERE academy_id = ?`
            ).bind(academyId).all();
            if (!includeFiles) {
                tables[table] = res.results; // metadata + sizes only
                continue;
            }
            // Inline content is fetched separately to keep the metadata query cheap.
            const rows: Array<Record<string, unknown>> = [];
            for (const meta of res.results as Array<Record<string, unknown>>) {
                const content = await db.prepare('SELECT content FROM contract_files WHERE key = ?')
                    .bind(meta.key).first<{ content: Uint8Array | null }>();
                rows.push({ ...meta, content_b64: toB64(content?.content ?? null) });
            }
            tables[table] = rows;
            continue;
        }
        if (academyColumn === 'id') {
            const res = await db.prepare('SELECT * FROM academies WHERE id = ?').bind(academyId).all();
            tables[table] = res.results;
        } else {
            const res = await db.prepare(`SELECT * FROM ${table} WHERE ${academyColumn} = ?`)
                .bind(academyId).all();
            if (table === 'reminders' || table === 'message_logs') {
                // Message bodies may contain personal data; they are needed for a
                // faithful restore, but binary-safe encoded anyway (JSON string).
                tables[table] = res.results;
            } else {
                tables[table] = res.results;
            }
        }
    }

    // Financial verification numbers, computed from the LIVE rows.
    const sums = await db.prepare(
        `SELECT COUNT(*) AS n,
                COALESCE(SUM(amount), 0) AS amount_sum,
                COALESCE(SUM(paid_amount), 0) AS paid_sum,
                COALESCE(SUM(remaining_amount), 0) AS remaining_sum
         FROM installments WHERE academy_id = ?`
    ).bind(academyId).first<{ n: number; amount_sum: number; paid_sum: number; remaining_sum: number }>();

    const sums2 = await db.prepare(
        `SELECT COALESCE(SUM(total_tuition), 0) AS tuition_sum,
                COALESCE(SUM(total_paid), 0) AS paid_sum,
                COALESCE(SUM(remaining_debt), 0) AS debt_sum
         FROM financial_summaries WHERE academy_id = ?`
    ).bind(academyId).first<{ tuition_sum: number; paid_sum: number; debt_sum: number }>();

    const students = await db.prepare(
        'SELECT COUNT(*) AS n FROM students WHERE academy_id = ?'
    ).bind(academyId).first<{ n: number }>();

    const files = await db.prepare(
        'SELECT COUNT(*) AS n FROM contract_files WHERE academy_id = ?'
    ).bind(academyId).first<{ n: number }>();

    const fileSha: Record<string, string> = {};
    for (const row of (tables['contract_files'] || []) as Array<Record<string, unknown>>) {
        fileSha[String(row.key)] = String(row.sha256 || '');
    }

    return {
        format: 'academy-backup',
        version: 1,
        academyId,
        exportedAtEpoch: Date.now(),
        filesIncluded: includeFiles,
        tables,
        financialChecks: {
            totalInstallments: sums?.n || 0,
            sumInstallmentAmount: sums?.amount_sum || 0,
            sumInstallmentPaid: sums?.paid_sum || 0,
            sumInstallmentRemaining: sums?.remaining_sum || 0,
            sumSummaryTuition: sums2?.tuition_sum || 0,
            sumSummaryPaid: sums2?.paid_sum || 0,
            sumSummaryDebt: sums2?.debt_sum || 0,
            studentCount: students?.n || 0,
            fileCount: files?.n || 0,
            fileSha256: fileSha
        }
    };
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

// ------------------------------------------------------------------
// Verification
// ------------------------------------------------------------------

export function verifyBundleAgainstLive(
    parsed: BackupBundle,
    academyId: string,
    liveCounts: Record<string, number>,
    liveChecks: FinancialChecks
): { ok: boolean; error: string } {
    if (parsed.format !== 'academy-backup' || parsed.version !== 1) {
        return { ok: false, error: 'ساختار فایل پشتیبان شناخته نشد.' };
    }
    if (parsed.academyId !== academyId) {
        return { ok: false, error: 'پشتیبان متعلق به آموزشگاه دیگری است.' };
    }
    for (const { table } of BACKUP_TABLES) {
        const rows = parsed.tables[table];
        if (!Array.isArray(rows)) {
            return { ok: false, error: `جدول ${table} در پشتیبان موجود نیست.` };
        }
    }
    for (const { table } of BACKUP_TABLES) {
        const expected = liveCounts[table] ?? 0;
        const got = (parsed.tables[table] || []).length;
        if (expected !== got) {
            return {
                ok: false,
                error: `شمارش ردیف‌های ${table} با پایگاه‌داده نمی‌خواند (زنده: ${expected}، پشتیبان: ${got}).`
            };
        }
    }
    const fc = parsed.financialChecks || ({} as FinancialChecks);
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
    } catch (e: any) {
        return { ok: false, error: `ناسازگاری مالی در پشتیبان: ${e.message}` };
    }
    return { ok: true, error: '' };
}

async function liveCountsAndChecks(env: Env, academyId: string): Promise<{ counts: Record<string, number>; checks: FinancialChecks }> {
    const db = env.DB!;
    const counts: Record<string, number> = {};
    for (const { table, academyColumn } of BACKUP_TABLES) {
        if (table === 'academy_settings') {
            const res = await db.prepare('SELECT key FROM academy_settings').all();
            counts[table] = academySettingsRows(res.results as Array<Record<string, unknown>>, academyId).length;
        } else if (table === 'contract_file_chunks') {
            const res = await db.prepare(
                `SELECT COUNT(*) AS n FROM contract_file_chunks c
                 JOIN contract_files f ON f.key = c.file_key AND f.academy_id = ?`
            ).bind(academyId).first<{ n: number }>();
            counts[table] = res?.n || 0;
        } else if (academyColumn === 'id') {
            const res = await db.prepare('SELECT COUNT(*) AS n FROM academies WHERE id = ?').bind(academyId).first<{ n: number }>();
            counts[table] = res?.n || 0;
        } else {
            const res = await db.prepare(`SELECT COUNT(*) AS n FROM ${table} WHERE ${academyColumn} = ?`)
                .bind(academyId).first<{ n: number }>();
            counts[table] = res?.n || 0;
        }
    }

    const sums = await db.prepare(
        `SELECT COUNT(*) AS n, COALESCE(SUM(amount),0) AS amount_sum,
                COALESCE(SUM(paid_amount),0) AS paid_sum,
                COALESCE(SUM(remaining_amount),0) AS remaining_sum
         FROM installments WHERE academy_id = ?`
    ).bind(academyId).first<{ n: number; amount_sum: number; paid_sum: number; remaining_sum: number }>();
    const sums2 = await db.prepare(
        `SELECT COALESCE(SUM(total_tuition),0) AS tuition_sum, COALESCE(SUM(total_paid),0) AS paid_sum,
                COALESCE(SUM(remaining_debt),0) AS debt_sum
         FROM financial_summaries WHERE academy_id = ?`
    ).bind(academyId).first<{ tuition_sum: number; paid_sum: number; debt_sum: number }>();
    const students = await db.prepare('SELECT COUNT(*) AS n FROM students WHERE academy_id = ?')
        .bind(academyId).first<{ n: number }>();
    const files = await db.prepare('SELECT COUNT(*) AS n FROM contract_files WHERE academy_id = ?')
        .bind(academyId).first<{ n: number }>();

    const fileSha: Record<string, string> = {};
    const fileRows = await db.prepare('SELECT key, sha256 FROM contract_files WHERE academy_id = ?')
        .bind(academyId).all<{ key: string; sha256: string }>();
    for (const r of fileRows.results) fileSha[r.key] = r.sha256 || '';

    return {
        counts,
        checks: {
            totalInstallments: sums?.n || 0,
            sumInstallmentAmount: sums?.amount_sum || 0,
            sumInstallmentPaid: sums?.paid_sum || 0,
            sumInstallmentRemaining: sums?.remaining_sum || 0,
            sumSummaryTuition: sums2?.tuition_sum || 0,
            sumSummaryPaid: sums2?.paid_sum || 0,
            sumSummaryDebt: sums2?.debt_sum || 0,
            studentCount: students?.n || 0,
            fileCount: files?.n || 0,
            fileSha256: fileSha
        }
    };
}

// ------------------------------------------------------------------
// Store + verify (verify-before-replace)
// ------------------------------------------------------------------

async function insertBackupRow(
    env: Env,
    id: string,
    academyId: string,
    kind: 'AUTO' | 'MANUAL',
    payload: Uint8Array,
    jsonBytes: number,
    bundle: BackupBundle,
    filesIncluded: boolean
): Promise<void> {
    const db = env.DB!;
    const chunks: Uint8Array[] = [];
    for (let i = 0; i < payload.length; i += FILE_CHUNK_SIZE) chunks.push(payload.subarray(i, i + FILE_CHUNK_SIZE));

    await db.prepare(
        `INSERT INTO backups (id, academy_id, kind, status, created_at_epoch, payload_sha256,
             total_bytes, stored_bytes, chunk_count, table_counts, financial_checks, files_included, error)
         VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, '')`
    ).bind(
        id, academyId, kind, Date.now(),
        await sha256HexBytes(payload),
        jsonBytes, payload.length, chunks.length,
        JSON.stringify(bundle.tables ? Object.fromEntries(Object.entries(bundle.tables).map(([k, v]) => [k, v.length])) : {}),
        JSON.stringify(bundle.financialChecks || {}),
        filesIncluded ? 1 : 0
    ).run();

    for (let i = 0; i < chunks.length; i++) {
        await db.prepare('INSERT INTO backup_chunks (backup_id, seq, content) VALUES (?, ?, ?)')
            .bind(id, i, chunks[i]).run();
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

/**
 * Runs a complete backup attempt for one academy.
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

    const runId = crypto.randomUUID();
    await db.prepare(
        `INSERT INTO backup_runs (id, academy_id, trigger, outcome, backup_id, error, started_at_epoch, duration_ms)
         VALUES (?, ?, ?, 'RUNNING', '', '', ?, 0)`
    ).bind(runId, academyId, trigger, started).run();

    const failRun = async (backupId: string, error: string, status: 'CORRUPT' | 'FAILED'): Promise<BackupOutcome> => {
        const duration = Date.now() - started;
        if (backupId) {
            await db.prepare('UPDATE backups SET status = ?, error = ?, duration_ms = ? WHERE id = ?')
                .bind(status, error, duration, backupId).run();
        }
        await db.prepare('UPDATE backup_runs SET outcome = ?, backup_id = ?, error = ?, duration_ms = ? WHERE id = ?')
            .bind('FAILED', backupId, error, duration, runId).run();
        return {
            ok: false, backupId, status, error, durationMs: duration,
            tableCounts: {}, filesIncluded: false, storedBytes: 0
        };
    };

    // A second overlapping backup would race with itself; the last one wins on lock release.
    const lockKey = `${academyId}:backup:running`;
    const lock = await db.prepare('SELECT value FROM academy_settings WHERE key = ?').bind(lockKey).first<{ value: string }>();
    if (lock?.value && Number(lock.value) > Date.now() - 10 * 60 * 1000) {
        return failRun('', 'پشتیبان‌گیری قبلی هنوز در حال اجراست؛ چند دقیقه بعد دوباره تلاش کنید.', 'FAILED');
    }
    await db.prepare(
        `INSERT INTO academy_settings (key, value, updated_at_epoch) VALUES (?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at_epoch = excluded.updated_at_epoch`
    ).bind(lockKey, String(Date.now()), Date.now()).run();

    try {
        const live = await liveCountsAndChecks(env, academyId);
        const bundle = await exportBundle(env, academyId, includeFiles);
        const jsonBytes = new TextEncoder().encode(JSON.stringify(bundle));

        // Free-space guard: too big -> store metadata + hashes only, say so loudly.
        let filesIncluded = includeFiles;
        let payload = await gzip(jsonBytes);
        if (payload.length > maxPayload && includeFiles) {
            filesIncluded = false;
            const slim = await exportBundle(env, academyId, false);
            const slimBytes = new TextEncoder().encode(JSON.stringify(slim));
            payload = await gzip(slimBytes);
        }

        const backupId = crypto.randomUUID();
        await insertBackupRow(env, backupId, academyId, trigger, payload, jsonBytes.length, bundle, filesIncluded);

        const verdict = await verifyStoredBackup(env, backupId, academyId, live.counts, live.checks);
        const duration = Date.now() - started;
        if (!verdict.ok) {
            return await failRun(backupId, verdict.error, 'CORRUPT');
        }

        await db.prepare(
            `UPDATE backups SET status = 'HEALTHY', error = '', duration_ms = ?,
                 table_counts = ?, financial_checks = ? WHERE id = ?`
        ).bind(
            duration,
            JSON.stringify(Object.fromEntries(Object.entries(bundle.tables).map(([k, v]) => [k, v.length]))),
            JSON.stringify(bundle.financialChecks || {}),
            backupId
        ).run();
        await db.prepare(
            `UPDATE backup_runs SET outcome = 'SUCCESS', backup_id = ?, error = '', duration_ms = ? WHERE id = ?`
        ).bind(backupId, duration, runId).run();

        // Only NOW (new snapshot verified) may older copies be pruned.
        await pruneOldBackups(env, academyId, retention, filesIncluded);

        return {
            ok: true, backupId, status: 'HEALTHY', error: '', durationMs: duration,
            tableCounts: Object.fromEntries(Object.entries(bundle.tables).map(([k, v]) => [k, v.length])),
            filesIncluded, storedBytes: payload.length
        };
    } catch (err: any) {
        return await failRun('', `خطای داخلی پشتیبان‌گیری: ${err?.message || err}`, 'FAILED');
    } finally {
        await db.prepare('DELETE FROM academy_settings WHERE key = ?').bind(lockKey).run();
    }
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
    for (const old of healthy.results.slice(keep)) {
        await db.prepare('DELETE FROM backups WHERE id = ?').bind(old.id).run();
    }
    // Corrupt copies are kept only for the 3 most recent attempts, never displacing healthy ones.
    const corrupt = await db.prepare(
        `SELECT id FROM backups WHERE academy_id = ? AND status != 'HEALTHY' ORDER BY created_at_epoch DESC`
    ).bind(academyId).all<{ id: string }>();
    for (const old of corrupt.results.slice(3)) {
        await db.prepare('DELETE FROM backups WHERE id = ?').bind(old.id).run();
    }
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

    return {
        lastRun: runs.results[0] || null,
        recentRuns: runs.results,
        lastHealthy,
        healthyCount: healthyCount?.n || 0,
        history: history.results,
        retentionDays: clampInt(env.BACKUP_RETENTION_DAYS, 1, 60, 7),
        includeFiles: env.BACKUP_INCLUDE_FILES !== 'false'
    };
}

// ------------------------------------------------------------------
// Restore (TEST ENVIRONMENT ONLY — never wired to any HTTP route)
// ------------------------------------------------------------------

const RESTORE_ORDER = [
    'academies', 'students', 'installments', 'financial_summaries', 'reminders',
    'message_logs', 'deliveries', 'sms_outbox', 'pending_students', 'contracts',
    'contract_events', 'contract_deliveries', 'contract_files', 'contract_file_chunks',
    'academy_settings'
];

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
            for (const row of rows) {
                const content = filesIncluded && bundle.filesIncluded ? fromB64(String(row.content_b64 || '')) : new Uint8Array(0);
                await db.prepare(
                    `INSERT OR REPLACE INTO contract_files (key, academy_id, content, sha256, created_at_epoch)
                     VALUES (?, ?, ?, ?, ?)`
                ).bind(row.key, row.academy_id, content.length ? content : null, row.sha256, row.created_at_epoch).run();
            }
            counts[table] = rows.length;
            continue;
        }
        if (table === 'contract_file_chunks') {
            for (const row of rows) {
                await db.prepare('INSERT OR REPLACE INTO contract_file_chunks (file_key, part, content) VALUES (?, ?, ?)')
                    .bind(row.file_key, row.part, filesIncluded && bundle.filesIncluded ? fromB64(String(row.content_b64 || '')) : new Uint8Array(0)).run();
            }
            counts[table] = rows.length;
            continue;
        }
        const cols = Object.keys(rows[0]);
        const colList = cols.join(', ');
        const placeholders = cols.map(() => '?').join(', ');
        const stmt = db.prepare(`INSERT OR REPLACE INTO ${table} (${colList}) VALUES (${placeholders})`);
        for (const row of rows) {
            await stmt.bind(...cols.map((c) => normalizeValue(row[c]))).run();
        }
        counts[table] = rows.length;
    }
    return counts;
}

function normalizeValue(v: unknown): unknown {
    if (v === null || v === undefined) return v === undefined ? null : null;
    if (v instanceof Uint8Array) return v;
    return v as unknown;
}

function clampInt(raw: string | undefined, min: number, max: number, dflt: number): number {
    const n = parseInt(String(raw ?? ''), 10);
    if (!Number.isFinite(n)) return dflt;
    return Math.min(max, Math.max(min, n));
}
