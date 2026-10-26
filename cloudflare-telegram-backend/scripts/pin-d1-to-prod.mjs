#!/usr/bin/env node
// Pin wrangler.toml's D1 binding to the CURRENT production database, so a
// deploy can never silently rebind the Worker to a different database.
//
// Usage: node pin-d1-to-prod.mjs <wrangler.toml> <prod_db_id> [prod_db_name]
// The prod db id comes from the Worker's live settings (preflight), never
// from wrangler.toml itself.

import { readFileSync, writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
if (args.length < 3) {
  console.error('usage: node pin-d1-to-prod.mjs <wrangler.toml> <prod_db_id> [prod_db_name]');
  process.exit(2);
}

const [, prodDbId, prodDbName] = args;
if (!/^[0-9a-fA-F-]{20,}$/.test(prodDbId)) {
  console.error('FAIL: شناسهٔ دیتابیس تولیدی معتبر به نظر نمی‌رسد — سنجاق متوقف شد.');
  process.exit(1);
}

let toml = readFileSync(args[0], 'utf8');
if (!/\[\[d1_databases\]\]/.test(toml)) {
  console.error('FAIL: بخش [[d1_databases]] در wrangler.toml پیدا نشد.');
  process.exit(1);
}

toml = toml.replace(/database_id\s*=\s*"[^"]*"/, `database_id = "${prodDbId}"`);
if (prodDbName) {
  toml = toml.replace(/database_name\s*=\s*"[^"]*"/, `database_name = "${prodDbName}"`);
}
writeFileSync(args[0], toml);
console.log(`OK: wrangler.toml به دیتابیس تولیدی سنجاق شد (id=${prodDbId}${prodDbName ? ', name=' + prodDbName : ''}).`);
