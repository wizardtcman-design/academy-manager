#!/usr/bin/env node
// Preflight gate for production Worker deploys — FAIL-SAFE.
//
// Compares wrangler.toml against the CURRENT production Worker settings and
// aborts the deploy if it would silently change production behavior:
//   - a plain-text var whose production value differs from wrangler.toml
//   - a production plain-text var that wrangler.toml would DELETE
//   - a toml var that collides with a production secret name
// The D1 database binding is NEVER rebound: the workflow pins wrangler.toml
// to the production database id before backup/migrations/deploy.
//
// Usage: node preflight-bindings.mjs <wrangler.toml> <prod-settings.json>
// stdout line "PROD_DB_ID=<uuid>" is consumed by the deploy workflow.
// Exit 0 = safe to continue, 1 = mismatch found (deploy aborted), 2 = bad input.

import { readFileSync } from 'node:fs';

function fail(msg) { console.error('FAIL: ' + msg); }
function ok(msg) { console.log('OK: ' + msg); }
function info(msg) { console.log('INFO: ' + msg); }

const args = process.argv.slice(2);
if (args.length < 2) {
  console.error('usage: node preflight-bindings.mjs <wrangler.toml> <prod-settings.json>');
  process.exit(2);
}

let toml;
let settings;
try {
  toml = readFileSync(args[0], 'utf8');
  settings = JSON.parse(readFileSync(args[1], 'utf8'));
} catch (e) {
  console.error('FAIL: input unreadable: ' + e.message);
  process.exit(2);
}

const bindings = Array.isArray(settings?.result?.bindings) ? settings.result.bindings : [];
if (bindings.length === 0) {
  fail('هیچ بایندینگی از Worker تولیدی خوانده نشد — توکن/شناسهٔ حساب را بررسی کنید.');
  process.exit(1);
}

// ---------- parse wrangler.toml ----------
function parseVars(src) {
  const vars = {};
  let section = '';
  for (const raw of src.split(/\r?\n/)) {
    const line = raw.trim();
    const sec = line.match(/^\[\[?([^\]]+)\]\]?$/);
    if (sec) { section = sec[1].trim(); continue; }
    if (section !== 'vars') continue;
    if (line.startsWith('#') || line === '') continue;
    const m = line.match(/^([A-Za-z0-9_]+)\s*=\s*"(.*)"\s*(?:#.*)?$/);
    if (m) vars[m[1]] = m[2];
  }
  return vars;
}

const tomlVars = parseVars(toml);
const tomlWorkerName = (toml.match(/^name\s*=\s*"([^"]+)"/m) || [])[1] || '';
const tomlD1Id = (toml.match(/database_id\s*=\s*"([^"]+)"/) || [])[1] || '';

// ---------- production state ----------
const prodVars = {};
const prodD1 = {};
const prodSecretNames = new Set();
for (const b of bindings) {
  if (b.type === 'plain_text') prodVars[b.name] = b.text ?? '';
  else if (b.type === 'd1') prodD1[b.name] = b.id || '';
  else if (b.type === 'secret_text' || b.type === 'secret') prodSecretNames.add(b.name);
}

info(`Worker تولیدی: ${settings?.result?.id || '(نامشخص)'}`);
if (tomlWorkerName && settings?.result?.id && settings.result.id !== tomlWorkerName) {
  info(`هشدار: نام Worker در toml (${tomlWorkerName}) با تولیدی (${settings.result.id}) فرق دارد — deploy اسکریپت toml را به‌روز می‌کند.`);
}

let failures = 0;

// ---------- var comparison ----------
for (const [k, v] of Object.entries(tomlVars)) {
  if (prodSecretNames.has(k)) {
    fail(`متغیر ${k} در toml با یک Secret هم‌نام در تولیدی برخورد می‌کند — نام را عوض کنید.`);
    failures++;
    continue;
  }
  if (!(k in prodVars)) {
    info(`متغیر جدید (در تولیدی وجود ندارد، اضافه می‌شود): ${k} = "${v}"`);
  } else if (prodVars[k] !== v) {
    fail(`مقدار ${k} در تولیدی با wrangler.toml فرق دارد؛ deploy آن را بی‌صدا تغییر می‌دهد (ممنوع).`);
    fail(`  → برای هم‌ترازی، در wrangler.toml بگذارید:  ${k} = "${prodVars[k]}"  (مقدار فعلی toml: "${v}")`);
    failures++;
  }
}
for (const [k, v] of Object.entries(prodVars)) {
  if (!(k in tomlVars)) {
    fail(`متغیر ${k} در تولیدی هست ولی در wrangler.toml نیست؛ deploy آن را حذف می‌کند (ممنوع).`);
    fail(`  → این خط را به بخش [vars] اضافه کنید:  ${k} = "${v}"`);
    failures++;
  }
}

// ---------- D1 continuity ----------
const prodDbId = prodD1.DB || '';
if (!prodDbId) {
  fail('بایندینگ D1 با نام DB در Worker تولیدی پیدا نشد — استقرار متوقف شد.');
  failures++;
} else {
  if (tomlD1Id && tomlD1Id !== prodDbId) {
    info(`database_id در toml (${tomlD1Id}) با تولیدی فرق دارد → در ادامهٔ همین استقرار به دیتابیس تولیدی سنجاق می‌شود (هیچ‌وقت دیتابیس عوض نمی‌شود).`);
  } else {
    ok('بایندینگ D1 با تولیدی یکسان است.');
  }
  console.log(`PROD_DB_ID=${prodDbId}`);
}

info('Secrets (مثل API_SECRET_KEY) با deploy دست نمی‌خورند و سر جایشان می‌مانند.');

if (failures > 0) {
  fail(`${failures} ناسازگاری پیدا شد — استقرار متوقف شد. wrangler.toml را مطابق گزارش بالا اصلاح کنید، commit/push بزنید و دوباره Run بزنید.`);
  process.exit(1);
}
ok('Preflight پاس شد — ادامهٔ استقرار مجاز است.');
