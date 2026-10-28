#!/usr/bin/env node
// D1 maintenance: inspect / backup / purge-student-data / verify (PRODUCTION).
//
// WHY THIS EXISTS
//   The in-app "test-data cleanup" feature was REMOVED (v3.2.2, owner decision).
//   Removing student data from production is now a deliberate, auditable,
//   backup-gated ops task executed ONLY through the GitHub workflow
//   "Maintenance — student data purge", never from the app.
//
// SUBCOMMANDS
//   inspect <db>            read-only: table inventory + categorized row counts
//   backup  <db> --output F remote export + local import + integrity_check
//   purge   <db>            DELETE all student-scoped rows (+ backup history)
//                           REFUSES to run unless env PURGE_CONFIRM === 'yes'
//   verify  <db>            read-only: post-purge assertions (all zero, settings intact)
//
// WHAT IS DELETED (and only this — full-table, idempotent, ordered child→parent)
//   students and every student-scoped table:
//     payment_link_tokens, payment_events, payment_claims, payment_receipts,
//     payment_transactions, contract_file_chunks, contract_files,
//     contract_pending_links, contract_deliveries, contract_events, contracts,
//     deliveries, sms_outbox, message_logs, financial_summaries, installments,
//     reminders, pending_students
//   the server-side daily-backup HISTORY tables (backups, backup_chunks,
//     backup_runs) because old snapshots contain student PII; the schema and
//     the daily backup feature itself stay (the next cron run snapshots the
//     already-clean database).
//   academy_settings rows of the removed feature only: key LIKE '%:test_cleanup%'.
//
// WHAT IS NEVER TOUCHED
//   academies (academy + bot credentials), academy_settings (real settings),
//   d1_migrations (migration history), any table not in the list above
//   (unknown tables are REPORTED, never deleted).
//
// SAFETY RULES
//   * inspect/verify are strictly read-only.
//   * backup must succeed (import + integrity_check) before a purge is allowed
//     to run; the workflow enforces it via job dependency, and this script
//     additionally refuses purge without PURGE_CONFIRM=yes.
//   * Every DELETE is reported with its meta.changes count. No values, no PII,
//     no secrets are ever printed.
//
// Usage:
//   CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=... \
//     node scripts/d1-maintenance.mjs <inspect|backup|purge|verify> <database-name> [--output FILE]

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';

function ok(msg)   { console.log('OK: ' + msg); }
function info(msg) { console.log('INFO: ' + msg); }
function warn(msg) { console.log('WARN: ' + msg); }
function fail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

const [cmd, DB] = process.argv.slice(2);
const valid = ['inspect', 'backup', 'purge', 'verify'];
if (!valid.includes(cmd) || !DB) {
    console.error('usage: node scripts/d1-maintenance.mjs <inspect|backup|purge|verify> <database-name> [--output FILE]');
    process.exit(2);
}
for (const v of ['CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_ACCOUNT_ID']) {
    if (!process.env[v]) { fail(`متغیر محیطی ${v} تنظیم نشده است.`); }
}

/** Runs SQL via wrangler (remote) and returns the parsed batch array. */
function d1raw(sql) {
    const out = execFileSync('npx', ['wrangler', 'd1', 'execute', DB, '--remote', '--json', '--command', sql], {
        encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'inherit']
    });
    return JSON.parse(out);
}

/** Returns [{ table, name }] rows of a SELECT. */
function d1(sql) {
    const rows = [];
    for (const r of (d1raw(sql) || [])) for (const row of (r?.results || [])) rows.push(row);
    return rows;
}

function count(table) {
    const rows = d1(`SELECT COUNT(*) AS n FROM ${table}`);
    return Number(rows[0]?.n ?? 0);
}

// ----- canonical storage map (derived from schema.sql + migrations 0001-0012) -----
const STUDENT_TABLES = [
    'payment_link_tokens', 'payment_events', 'payment_claims', 'payment_receipts',
    'payment_transactions', 'contract_file_chunks', 'contract_files',
    'contract_pending_links', 'contract_deliveries', 'contract_events', 'contracts',
    'deliveries', 'sms_outbox', 'message_logs', 'financial_summaries', 'installments',
    'reminders', 'pending_students'
];
// In-D1 daily-backup history: schema stays, old snapshots (student PII) are purged.
const BACKUP_HISTORY_TABLES = ['backup_chunks', 'backups', 'backup_runs'];
const PRESERVED_TABLES = ['academies', 'academy_settings', 'd1_migrations'];
const PURGE_ORDER = [...STUDENT_TABLES, ...BACKUP_HISTORY_TABLES, 'students'];

function inventory() {
    // _cf_KV is a Cloudflare-internal D1 table that cannot be queried directly
    // (APIError on COUNT) — exclude it (and sqlite_*) from the user inventory.
    const tables = d1("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != '_cf_KV' ORDER BY name").map(r => r.name);
    const counts = {};
    for (const t of tables) counts[t] = count(t);
    const unknown = tables.filter(t => !PURGE_ORDER.includes(t) && !PRESERVED_TABLES.includes(t));
    return { tables, counts, unknown };
}

function botPresence() {
    // Numbers only — never token values.
    const rows = d1("SELECT COUNT(*) AS n FROM academies WHERE bot_token != ''");
    return Number(rows[0]?.n ?? 0);
}

function cleanupResidue() {
    const rows = d1("SELECT COUNT(*) AS n FROM academy_settings WHERE key LIKE '%:test_cleanup%'");
    return Number(rows[0]?.n ?? 0);
}

function printInventory(inv, extra = {}) {
    console.log(JSON.stringify({ at: new Date().toISOString(), database: DB, ...inv, ...extra }, null, 2));
    info('تعداد ردیف‌ها: ' + Object.entries(inv.counts).map(([t, n]) => `${t}=${n}`).join(', '));
    if (inv.unknown.length > 0) {
        warn('جدول‌های خارج از نقشهٔ شناخته‌شده (هیچ‌گاه حذف نمی‌شوند، فقط گزارش می‌شوند): ' + inv.unknown.join(', '));
    }
}

// ---------- inspect ----------
if (cmd === 'inspect') {
    const inv = inventory();
    const extra = {
        botConfiguredAcademies: botPresence(),
        removedFeatureResidueRows: cleanupResidue(),
        purgeScopeTables: PURGE_ORDER,
        preservedTables: PRESERVED_TABLES
    };
    printInventory(inv, extra);
    ok('inspect فقط-خواندنی انجام شد؛ هیچ داده‌ای تغییر نکرد.');
    process.exit(0);
}

// ---------- backup ----------
if (cmd === 'backup') {
    const outIdx = process.argv.indexOf('--output');
    const outFile = outIdx > -1 ? process.argv[outIdx + 1] : 'd1-backup.sql';
    execFileSync('npx', ['wrangler', 'd1', 'export', DB, '--remote', '--output', outFile], {
        encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, stdio: ['inherit', 'pipe', 'inherit']
    });
    if (!existsSync(outFile)) fail('فایل خروجی export ساخته نشد.');
    const size = statSync(outFile).size;
    const sql = readFileSync(outFile, 'utf8');
    const createTables = (sql.match(/CREATE TABLE/g) || []).length;
    const inserts = (sql.match(/INSERT INTO/g) || []).length;
    if (createTables < 20 || size < 1024) {
        fail(`بکاپ نامعتبر است (${createTables} جدول، ${size} بایت) — احتمالاً export محلی/ناقص. اجرا متوقف شد.`);
    }
    let integrity = 'sqlite3-not-available';
    let imported = {};
    if (execFileSync('bash', ['-lc', 'command -v sqlite3 || true'], { encoding: 'utf8' }).trim()) {
        const tmpDb = outFile + '.check.db';
        execFileSync('bash', ['-lc', `rm -f '${tmpDb}' && sqlite3 '${tmpDb}' < '${outFile}'`], { encoding: 'utf8' });
        integrity = execFileSync('sqlite3', [tmpDb, 'PRAGMA integrity_check;'], { encoding: 'utf8' }).trim();
        const q = (t) => execFileSync('sqlite3', [tmpDb, `SELECT COUNT(*) FROM ${t};`], { encoding: 'utf8' }).trim();
        for (const t of ['students', 'academies', 'academy_settings', 'd1_migrations']) {
            try { imported[t] = Number(q(t)); } catch { imported[t] = 'n/a'; }
        }
        execFileSync('bash', ['-lc', `rm -f '${tmpDb}'`], { encoding: 'utf8' });
        if (integrity !== 'ok') fail(`integrity_check بکاپ: ${integrity} — بکاپ سالم نیست.`);
    } else {
        warn('sqlite3 در دسترس نیست؛ فقط بررسی سطح فایل انجام شد.');
    }
    const sha256 = createHash('sha256').update(sql).digest('hex');
    console.log(JSON.stringify({ backup: outFile, bytes: size, createTables, inserts, integrity, importedCounts: imported, sha256 }, null, 2));
    ok(`بکاپ واقعی تولیدی تأیید شد: ${createTables} جدول، ${inserts} INSERT، integrity_check=${integrity}`);
    process.exit(0);
}

// ---------- purge ----------
if (cmd === 'purge') {
    if (process.env.PURGE_CONFIRM !== 'yes') {
        fail('حذف داده قفل است. این اسکریپت فقط از طریق workflow با ورودی تأیید صریح اجرا می‌شود.');
    }
    info('پیش‌بررسی (فقط-خواندنی)...');
    const inv = inventory();
    const missing = PURGE_ORDER.filter(t => !inv.tables.includes(t));
    if (missing.length > 0) fail('جدول(های) مورد انتظار موجود نیست: ' + missing.join(', ') + ' — وضعیت غیرمنتظره، توقف.');
    if (!inv.tables.includes('academies') || inv.counts.academies < 1) {
        fail('جدول academies خالی/ناموجود است — این با وضعیت شناخته‌شده هم‌خوان نیست؛ توقف.');
    }
    if (inv.unknown.length > 0) {
        warn('جدول‌های ناشناخته دست‌نخورده می‌مانند: ' + inv.unknown.join(', '));
    }
    const before = { academies: inv.counts.academies, academySettings: inv.counts.academy_settings, migrations: inv.counts.d1_migrations, botConfiguredAcademies: botPresence() };
    info('قبل از حذف — academies=' + before.academies + ', academy_settings=' + before.academySettings + ', d1_migrations=' + before.migrations + ', botConfiguredAcademies=' + before.botConfiguredAcademies);

    const deleted = {};
    let total = 0;
    for (const t of PURGE_ORDER) {
        const batches = d1raw(`DELETE FROM ${t}`);
        const changes = Number(batches?.[0]?.meta?.changes ?? 0);
        deleted[t] = changes;
        total += changes;
        info(`DELETE FROM ${t} -> ${changes}`);
    }
    const residueBatches = d1raw("DELETE FROM academy_settings WHERE key LIKE '%:test_cleanup%'");
    deleted['academy_settings(test_cleanup residue)'] = Number(residueBatches?.[0]?.meta?.changes ?? 0);
    console.log(JSON.stringify({ deleted, deletedTotal: total, before }, null, 2));
    ok(`حذف انجام شد: ${total} ردیف از ${PURGE_ORDER.length + 1} هدف.`);
    process.exit(0);
}

// ---------- verify ----------
if (cmd === 'verify') {
    const inv = inventory();
    // The Worker cron (every minute) writes fresh daily-backup run rows, so the
    // backup-history tables refill with snapshots of the ALREADY-CLEAN database.
    // They are reported as information, never asserted to be zero. Only student
    // tables are hard-asserted to be empty.
    const zeroTargets = [...STUDENT_TABLES, 'students'];
    const nonzero = zeroTargets.filter(t => (inv.counts[t] ?? 0) !== 0);
    const backupHistoryNow = Object.fromEntries(BACKUP_HISTORY_TABLES.map(t => [t, inv.counts[t] ?? 0]));
    const residue = cleanupResidue();
    const now = {
        academies: inv.counts.academies ?? 0,
        academySettings: inv.counts.academy_settings ?? 0,
        migrations: inv.counts.d1_migrations ?? 0,
        botConfiguredAcademies: botPresence()
    };
    console.log(JSON.stringify({ nonzeroLeft: nonzero, removedFeatureResidueRows: residue, preservedNow: now, backupHistoryNow: backupHistoryNow, unknown: inv.unknown, counts: inv.counts }, null, 2));
    if (nonzero.length > 0) fail('این جدول‌ها هنوز ردیف دارند: ' + nonzero.join(', '));
    if (now.academies < 1) fail('جدول academies خالی است — انتظار حداقل یک آموزشگاه.');
    if (now.migrations < 12) fail(`سابقه مهاجرت‌ها ناقص است (${now.migrations} ردیف).`);
    if (residue !== 0) fail('ردپای قابلیت حذف‌شده در academy_settings باقی است.');
    ok(`تأیید نهایی: همهٔ جدول‌های وابسته به هنرجو خالی‌اند؛ academies=${now.academies}, academy_settings=${now.academySettings}, d1_migrations=${now.migrations}, آموزشگاه‌های با ربات فعال=${now.botConfiguredAcademies}. تاریخچهٔ بکاپ سرور (پس از پاک‌سازی، از دیتابیس تمیز): ` + JSON.stringify(backupHistoryNow));
    process.exit(0);
}
