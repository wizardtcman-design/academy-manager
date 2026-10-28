#!/usr/bin/env node
// ============================================================
// Read-only Worker Logs diagnosis via the Cloudflare
// Observability telemetry query API (Workers Logs).
//
// Answers, with evidence and without touching any data:
//   * Did requests from the app actually reach the Worker?
//   * Which paths/statuses/outcomes were served?
//   * What did the Worker log on failures (e.g. [TEST-DATA])?
//
// SAFETY:
//   * Read-only HTTP GET-equivalent query; no D1 writes, no purge.
//   * Never prints the API token or account id.
//   * Prints request PATHS only (query strings are dropped, so
//     sign/pay/one-time tokens never appear).
//   * Log/exception messages are additionally redacted for
//     anything that looks like a bearer key or a bot token.
//
// Usage:
//   node scripts/diagnose-worker-logs.mjs [--hours 6] [--service academy-manager1] [--limit 500]
// Env:
//   CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID
// ============================================================

const TOKEN = process.env.CLOUDFLARE_API_TOKEN || '';
const ACCOUNT = process.env.CLOUDFLARE_ACCOUNT_ID || '';
if (!TOKEN || !ACCOUNT) {
    console.error('FATAL: CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID environment variables are missing.');
    process.exit(1);
}

function arg(name, def) {
    const i = process.argv.indexOf(name);
    return i > 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const hours = Math.max(1, Math.min(Number(arg('--hours', '6')) || 6, 72));
const service = arg('--service', 'academy-manager1');
const limit = Math.max(1, Math.min(Number(arg('--limit', '500')) || 500, 1000));
const to = Date.now();
const from = to - hours * 3600 * 1000;

const REDACTIONS = [
    [/Bearer\s+[A-Za-z0-9._\-]+/gi, 'Bearer ***'],
    [/bot\d{6,}:[A-Za-z0-9_-]+/g, 'bot***'],
    [/[A-Za-z0-9+/=_-]{40,}/g, '***opaque***']
];

function redact(s) {
    let out = String(s);
    for (const [re, rep] of REDACTIONS) out = out.replace(re, rep);
    return out;
}

function pathOnly(u) {
    try { return new URL(u).pathname; } catch { return '<unparsable-url>'; }
}

function msgOf(l) {
    const m = l?.message;
    return Array.isArray(m) ? m.map((x) => (typeof x === 'string' ? x : JSON.stringify(x))).join(' ') : String(m ?? '');
}

function tsOf(e) {
    const cands = [e?.$metadata?.messageTimestamp, e?.eventTimestamp, e?.timestamp, e?.$metadata?.time];
    for (const c of cands) { const n = Number(c); if (Number.isFinite(n) && n > 0) return n; }
    return 0;
}

async function queryEvents() {
    const body = {
        timeframe: { from, to },
        parameters: {
            datasets: ['cloudflare-workers'],
            filters: [{ key: '$metadata.service', type: 'string', operation: 'eq', value: service }],
            limit,
            limitSampling: 1,
            clock: 'event'
        },
        view: 'events'
    };
    const res = await fetch(
        `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/workers/observability/telemetry/query`,
        {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }
    );
    const text = await res.text();
    let json;
    try { json = JSON.parse(text); } catch {
        throw new Error(`HTTP ${res.status} non-JSON response: ${text.slice(0, 300)}`);
    }
    if (!json.success) {
        const errs = (json.errors || []).map((e) => `${e.code || ''} ${e.message || ''}`).join('; ');
        throw new Error(`API error HTTP ${res.status}: ${errs || JSON.stringify(json).slice(0, 300)}`);
    }
    const payload = json.result?.events;
    return Array.isArray(payload) ? payload : (Array.isArray(payload?.events) ? payload.events : []);
}

/** Lists Worker script names + dates (no code, no secrets). */
async function listScripts() {
    const res = await fetch(
        `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/workers/scripts`,
        { headers: { 'Authorization': `Bearer ${TOKEN}` } }
    );
    const json = await res.json().catch(() => null);
    if (!json?.success) {
        console.log('== Workers Scripts inventory ==');
        console.log(`API error HTTP ${res.status}: ${JSON.stringify(json?.errors || json).slice(0, 300)}`);
        return;
    }
    console.log('== Workers Scripts in this account (names only, no code/secrets) ==');
    for (const s of json.result || []) {
        console.log(`- ${s.id} | modified: ${s.modified_on} | created: ${s.created_on}`);
    }
}

/** Lists secret NAMES + last-modified dates for the worker (never values). */
async function secretsMeta(service) {
    const res = await fetch(
        `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/workers/scripts/${encodeURIComponent(service)}/secrets`,
        { headers: { 'Authorization': `Bearer ${TOKEN}` } }
    );
    const json = await res.json().catch(() => null);
    console.log(`== Secret metadata for ${service} (names + dates only, values are never returned) ==`);
    if (!json?.success) {
        console.log(`API error HTTP ${res.status}: ${JSON.stringify(json?.errors || json).slice(0, 300)}`);
        return;
    }
    for (const s of json.result || []) {
        console.log(`- ${s.name} | type: ${s.type} | modified: ${s.modified_on}`);
    }
}

async function main() {
    if (process.argv.includes('--list-scripts')) {
        await listScripts();
        return;
    }
    const svc = process.argv.indexOf('--secrets-meta');
    if (svc > 0) {
        await secretsMeta(process.argv[svc + 1] || service);
        return;
    }
    console.log(`Window: last ${hours}h  service=${service}  limit=${limit}`);
    console.log(`(UTC now: ${new Date(to).toISOString()})`);
    let events;
    try {
        events = await queryEvents();
    } catch (e) {
        console.error(`FAILED to query Workers Logs: ${e.message}`);
        console.error('NOTE: the API token may lack the "Workers Observability : Read" permission.');
        process.exit(2);
    }
    console.log(`EVENTS: ${events.length}`);

    const hist = new Map();
    const interesting = [];
    for (const e of events) {
        const url = e?.event?.request?.url || '';
        const path = url ? pathOnly(url) : '(non-http invocation)';
        const method = e?.event?.request?.method || '?';
        const status = e?.event?.response?.status ?? e?.event?.response?.status_code ?? '';
        const outcome = e?.outcome ?? e?.$metadata?.outcome ?? '?';
        const logs = (Array.isArray(e?.logs) ? e.logs : []).map(msgOf).filter(Boolean).join(' | ');
        const excs = (Array.isArray(e?.exceptions) ? e.exceptions : [])
            .map((x) => `${x?.name || 'Error'}: ${x?.message || ''}`).filter((s) => s.trim() !== ': ').join(' | ');
        const key = `${method} ${path}${status ? ` -> ${status}` : ''}  outcome=${outcome}`;
        hist.set(key, (hist.get(key) || 0) + 1);
        const isTestData = path.startsWith('/api/test-data') || /TEST-DATA/.test(logs);
        const isBad = outcome !== 'ok' || (excs && excs.length > 0);
        if (isTestData || isBad) interesting.push({ t: tsOf(e), key, logs: redact(logs), excs: redact(excs) });
    }

    console.log('--- per-path histogram (path only, query strings dropped) ---');
    for (const [k, v] of [...hist.entries()].sort((a, b) => b[1] - a[1])) {
        console.log(`${String(v).padStart(5)}  ${k}`);
    }

    console.log('--- interesting events: /api/test-data/*, non-ok outcomes, exceptions ---');
    if (interesting.length === 0) {
        console.log('(none found in this window)');
        console.log('HINT: an empty histogram above would mean NO requests reached this Worker in the window.');
    }
    for (const i of interesting.sort((a, b) => a.t - b.t).slice(0, 100)) {
        const ts = i.t ? new Date(i.t).toISOString() : 'ts?';
        console.log(`${ts}  ${i.key}`);
        if (i.logs) console.log(`   logs: ${i.logs.slice(0, 600)}`);
        if (i.excs) console.log(`   exceptions: ${i.excs.slice(0, 600)}`);
    }
    console.log('DONE (read-only; nothing was modified).');
}

main().catch((e) => {
    console.error(`FATAL: ${e?.message || e}`);
    process.exit(3);
});
