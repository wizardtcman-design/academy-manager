#!/usr/bin/env node
// Bootstrap the missing d1_migrations ledger on the PRODUCTION D1 database.
//
// WHY THIS EXISTS
//   The live production database had migrations 0001-0005 + 0007 physically
//   applied BEFORE the d1_migrations ledger existed (historical manual setup).
//   Running `wrangler d1 migrations apply` on it would try to re-run 0001 and
//   fail on the first duplicate column (fail-safe, but a dead end).
//   The documented fix (docs/BACKUP_AND_MIGRATION_PLAN.md §5-A) is to create
//   the ledger and RECORD the already-applied migrations so that a later
//   `migrations apply` only runs the genuinely missing ones
//   (0006, 0008, 0009, 0010, 0011, 0012).
//
// SAFETY RULES (fail-safe, idempotent)
//   * ALL precondition checks are READ-ONLY and must pass before any write.
//   * The write is exactly two statements: CREATE TABLE d1_migrations + one
//     INSERT of the six already-applied names. No schema/data of any other
//     table is touched.
//   * 0007 (the only DROP/recreate migration) is NEVER executed here — it is
//     only RECORDED, and only if the live schema proves it was applied
//     (contract_files.content must be nullable).
//   * If the ledger already exists: print its content and exit 0 (re-run safe).
//   * Any unexpected schema state → abort with a Persian explanation, no write.
//
// Usage:
//   CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=... \
//     node scripts/bootstrap-d1-ledger.mjs <database-name>

import { execFileSync } from 'node:child_process';

function ok(msg)   { console.log('OK: ' + msg); }
function info(msg) { console.log('INFO: ' + msg); }
function fail(msg) { console.error('FAIL: ' + msg); }

const args = process.argv.slice(2);
if (args.length < 1) {
    console.error('usage: node scripts/bootstrap-d1-ledger.mjs <database-name>');
    process.exit(2);
}
const DB = args[0];
for (const v of ['CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_ACCOUNT_ID']) {
    if (!process.env[v]) { fail(`متغیر محیطی ${v} تنظیم نشده است.`); process.exit(2); }
}

/** Runs a read-only or write SQL batch via wrangler and returns parsed rows. */
function d1(sql) {
    const out = execFileSync('npx', ['wrangler', 'd1', 'execute', DB, '--remote', '--json', '--command', sql], {
        encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'inherit']
    });
    const parsed = JSON.parse(out);
    const rows = [];
    for (const r of (parsed || [])) for (const row of (r?.results || [])) rows.push(row);
    return rows;
}

// ---------- 1) table inventory (read-only) ----------
const tables = d1("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").map(r => r.name);
info('جدول‌های موجود: ' + tables.join(', '));

// ---------- 2) decide state ----------
if (tables.includes('d1_migrations')) {
    const rows = d1('SELECT name FROM d1_migrations ORDER BY id').map(r => r.name);
    ok('دفتر d1_migrations از قبل وجود دارد؛ bootstrap لازم نیست.');
    info('ثبت‌شده: ' + rows.join(', '));
    info('این اسکریپت هیچ تغییری نداد. برای دیدن مهاجرت‌های در انتظار: npx wrangler d1 migrations list ' + DB + ' --remote');
    process.exit(0);
}

// Tables that MUST exist (proof that 0001-0005 are physically applied).
const required = ['students', 'academies', 'deliveries', 'sms_outbox',
    'contracts', 'contract_events', 'contract_files',
    'pending_students', 'contract_deliveries', 'contract_file_chunks'];
const missingRequired = required.filter(t => !tables.includes(t));

// Tables that must NOT exist yet (0006/0009/0010/0011 unapplied).
const forbidden = ['contract_pending_links',
    'payment_receipts', 'payment_transactions', 'payment_link_tokens', 'payment_claims', 'payment_events',
    'backups', 'backup_chunks', 'backup_runs'];
const presentForbidden = forbidden.filter(t => tables.includes(t));

// 0007 proof: contract_files.content must be nullable on the live DB.
// Primary: PRAGMA table_info. Fallback: the CREATE TABLE text in sqlite_master.
let contentNullable = null;
try {
    const fileCols = d1("PRAGMA table_info(contract_files)");
    const contentCol = fileCols.find(c => c.name === 'content');
    if (contentCol) contentNullable = contentCol.notnull === 0;
} catch { /* fall through to text-based proof */ }
if (contentNullable === null) {
    const sqlRow = d1("SELECT sql FROM sqlite_master WHERE type='table' AND name='contract_files'");
    const sqlText = String(sqlRow[0]?.sql || '');
    const m = sqlText.match(/content\s+BLOB\s*(NOT NULL)?/i);
    contentNullable = Boolean(m && !m[1]);
}

// 0012 must NOT be applied yet.
const studentCols = d1("PRAGMA table_info(students)").map(c => c.name);
const linkUsedApplied = studentCols.includes('telegram_link_used');

let bad = false;
if (missingRequired.length) { fail('جدول‌های الزامی غایب‌اند (دفتر با این حالت هم‌خوان نیست): ' + missingRequired.join(', ')); bad = true; }
if (presentForbidden.length) { fail('جدول‌های متعلق به مهاجرت‌های 0006/0009/0010/0011 از قبل وجود دارند (وضعیت ناشناخته): ' + presentForbidden.join(', ')); bad = true; }
if (!contentNullable) { fail('contract_files.content nullable نیست → 0007 عملاً اعمال نشده؛ ثبت آن مجاز نیست (ممیزی را دوباره انجام دهید).'); bad = true; }
if (linkUsedApplied) { fail('students.telegram_link_used از قبل وجود دارد → 0012 اعمال شده است؛ ثبت‌های این اسکریپت با این حالت هم‌خوان نیست.'); bad = true; }
if (bad) { fail('هیچ تغییری اعمال نشد.'); process.exit(1); }

ok('پیش‌شرط‌ها برقرارند: 0001-0005 اعمال‌شده، 0007 عملاً اعمال‌شده (content nullable)، 0006/0008-0012 در انتظار.');

// ---------- 3) write the ledger (exactly two statements) ----------
d1(`CREATE TABLE IF NOT EXISTS d1_migrations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);`);
d1(`INSERT INTO d1_migrations (name) VALUES
 ('0001_tenancy.sql'),('0002_contracts.sql'),('0003_seal.sql'),
 ('0004_academy_bots.sql'),('0005_file_chunks.sql'),
 ('0007_file_content_nullable.sql');`);

// ---------- 4) verify (read-only) ----------
const recorded = d1('SELECT name FROM d1_migrations ORDER BY id').map(r => r.name);
const expect = ['0001_tenancy.sql', '0002_contracts.sql', '0003_seal.sql',
    '0004_academy_bots.sql', '0005_file_chunks.sql', '0007_file_content_nullable.sql'];
const match = expect.length === recorded.length && expect.every((v, i) => v === recorded[i]);
if (!match) { fail('خواندن مجدد دفتر با انتظار هم‌خوان نیست: ' + recorded.join(', ')); process.exit(1); }
ok('دفتر ساخته و مقداردهی شد: ' + recorded.join(', '));
info('گام بعدی (مسیر مجاز): اجرای workflow «Bootstrap D1 ledger» سپس «Deploy Worker (production)» — یا محلی: npx wrangler d1 migrations list ' + DB + ' --remote');
