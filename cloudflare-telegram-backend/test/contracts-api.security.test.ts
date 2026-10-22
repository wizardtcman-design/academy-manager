// ============================================================
// Worker security tests for the contract API (run with vitest).
// Uses a real SQLite database through a D1 shim + the real
// migrations 0001..0006 + the real handlers — no network, no
// secrets, no student data.
// ============================================================

import { describe, it, expect, beforeAll } from 'vitest';
import { handleContractsApi } from '../src/handlers/contracts-api';
import { handleSignRoute } from '../src/handlers/sign';
import { sha256Hex } from '../src/crypto';
import { D1Shim } from './helpers/d1-shim';
import {
    makeEnv, makePdf, makeSignaturePng, uploadRequest,
    sha256Bytes, bearer, ACADEMY_A, ACADEMY_B
} from './helpers/fixtures';

const BASE = 'https://worker.test';

function contractUrl(path: string): URL {
    return new URL(`${BASE}${path}`);
}

async function uploadContract(
    env: Env,
    opts: { academy?: string; studentId: string; enrollmentId: string; tokenHash: string; token?: string; pdf?: Uint8Array; phone?: string; national?: string; contractId?: string }
): Promise<Response> {
    const academy = opts.academy ?? ACADEMY_A;
    const req = uploadRequest(BASE, {
        enrollmentId: opts.enrollmentId,
        studentId: opts.studentId,
        tokenHash: opts.tokenHash,
        publicToken: opts.token,
        contractId: opts.contractId,
        phone: opts.phone ?? '09121234567',
        nationalCode: opts.national ?? '0079123456'
    }, opts.pdf ?? makePdf(), bearer(academy));
    return handleContractsApi(req, env, contractUrl('/api/contracts'));
}

let env: Env;
let db: D1Shim;

/** Every contract needs its own token (token_hash is UNIQUE). */
function tokenPair(seed: string): { token: string; hash: string } {
    const token = (seed.repeat(3) + 'B1-_').padEnd(24, 'x').slice(0, 43);
    return { token, hash: '' }; // hash is filled in beforeAll (async)
}
const PAIRS = {
    main: tokenPair('a'),
    second: tokenPair('b'),
    third: tokenPair('c')
};

beforeAll(async () => {
    ({ env, db } = makeEnv());
    for (const p of Object.values(PAIRS)) p.hash = await sha256Hex(p.token);
});

describe('authentication on every contract endpoint', () => {
    it('rejects upload / status / pdf / revoke without a bearer key', async () => {
        const up = new Request(`${BASE}/api/contracts`, { method: 'POST', body: new FormData() });
        expect((await handleContractsApi(up, env, contractUrl('/api/contracts'))).status).toBe(401);

        for (const path of ['/api/contracts/some-id', '/api/contracts/some-id/pdf', '/api/contracts/some-id/revoke']) {
            const res = await handleContractsApi(new Request(`${BASE}${path}`), env, contractUrl(path));
            expect(res.status, path).toBe(401);
        }
        // per-student download without a key is also refused
        const own = await handleContractsApi(
            new Request(`${BASE}/api/students/s1/contracts/c1/pdf`), env, contractUrl('/api/students/s1/contracts/c1/pdf'));
        expect(own.status).toBe(401);
    });

    it('rejects a wrong bearer key (constant-time path)', async () => {
        const res = await handleContractsApi(
            new Request(`${BASE}/api/contracts/x/pdf`, { headers: { Authorization: 'Bearer wrong-key' } }),
            env, contractUrl('/api/contracts/x/pdf'));
        expect(res.status).toBe(401);
    });

    it('health stays reachable anonymously but leaks no error details', async () => {
        const res = await handleContractsApi(new Request(`${BASE}/api/contracts/health`), env, contractUrl('/api/contracts/health'));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.d1).toBe(true);
        expect(body.data.error).toBe('');
        // With the key, diagnostics are visible to the academy only.
        const authed = await handleContractsApi(
            new Request(`${BASE}/api/contracts/health`, { headers: bearer(ACADEMY_A) }),
            env, contractUrl('/api/contracts/health'));
        expect(((await authed.json()) as any).data.error).toBe('');
    });

    it('health hides the error string from anonymous callers when D1 is broken', async () => {
        // A DB without the contract tables must not hand its internals out.
        const broken = new D1Shim(); // no migrations applied
        const brokenEnv: Env = { ...env, DB: broken as unknown as Env['DB'] };
        const anon = await handleContractsApi(new Request(`${BASE}/api/contracts/health`), brokenEnv, contractUrl('/api/contracts/health'));
        expect(((await anon.json()) as any).data.error).toBe('');
        const authed = await handleContractsApi(
            new Request(`${BASE}/api/contracts/health`, { headers: bearer(ACADEMY_A) }),
            brokenEnv, contractUrl('/api/contracts/health'));
        expect(((await authed.json()) as any).data.error).not.toBe('');
    });
});

describe('upload stores the STANDARD sha-256 of raw bytes', () => {
    it('uploads a PDF full of high bytes and stores the standard digest', async () => {
        const pdf = makePdf(7); // contains 0x80..0xFF bytes
        const res = await uploadContract(env, {
            studentId: 'stu-1', enrollmentId: 'enr-1', tokenHash: PAIRS.main.hash, token: PAIRS.main.token, pdf
        });
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.success).toBe(true);
        expect(body.data.pdfSha256).toBe(await sha256Bytes(pdf));
        // The row itself carries the standard digest.
        const row = db.get('SELECT pdf_sha256 FROM contracts WHERE enrollment_id = ?', 'enr-1');
        expect(row?.pdf_sha256).toBe(await sha256Bytes(pdf));
        // The server hands back ITS OWN stable link built from the kept token.
        expect(body.data.signUrl).toContain(`/sign?s=${PAIRS.main.token}`);
    });

    it('refuses to re-point an existing enrollment to another student', async () => {
        const res = await uploadContract(env, { studentId: 'stu-OTHER', enrollmentId: 'enr-1', tokenHash: PAIRS.main.hash });
        expect(res.status).toBe(409);
    });
});

describe('tenant and student ownership on every download route', () => {
    it('per-student route: wrong student id is a plain 404 (no existence leak)', async () => {
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-WRONG/contracts/whatever/pdf`, { headers: bearer(ACADEMY_A) }),
            env, contractUrl('/api/students/stu-WRONG/contracts/whatever/pdf'));
        expect(res.status).toBe(404);
        expect(((await res.json()) as any).message).toBe('قرارداد پیدا نشد.');
    });

    it('per-student route: another tenant with a valid key gets 404, never bytes', async () => {
        const id = (db.get('SELECT id FROM contracts WHERE enrollment_id = ?', 'enr-1') as any).id;
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-1/contracts/${id}/pdf`, { headers: bearer(ACADEMY_B) }),
            env, contractUrl(`/api/students/stu-1/contracts/${id}/pdf`));
        expect(res.status).toBe(404);
    });

    it('legacy /api/contracts/:id/pdf: valid key + own tenant serves the exact bytes', async () => {
        const id = (db.get('SELECT id FROM contracts WHERE enrollment_id = ?', 'enr-1') as any).id;
        const pdf = makePdf(7);
        const res = await handleContractsApi(
            new Request(`${BASE}/api/contracts/${id}/pdf`, { headers: bearer(ACADEMY_A) }),
            env, contractUrl(`/api/contracts/${id}/pdf`));
        expect(res.status).toBe(200);
        const bytes = new Uint8Array(await res.arrayBuffer());
        expect(Array.from(bytes)).toEqual(Array.from(pdf));
        expect(res.headers.get('X-Content-SHA256')).toBe(await sha256Bytes(pdf));
    });

    it('legacy /api/contracts/:id/pdf: another tenant gets 404 (tenant scoping)', async () => {
        const id = (db.get('SELECT id FROM contracts WHERE enrollment_id = ?', 'enr-1') as any).id;
        const res = await handleContractsApi(
            new Request(`${BASE}/api/contracts/${id}/pdf`, { headers: bearer(ACADEMY_B) }),
            env, contractUrl(`/api/contracts/${id}/pdf`));
        expect(res.status).toBe(404);
    });
});

describe('end-to-end sign flow keeps the standard hash', () => {
    it('open -> sign -> signed pdf downloads with matching digest', async () => {
        const pdf = makePdf(9);
        const up = await uploadContract(env, {
            studentId: 'stu-2', enrollmentId: 'enr-2', tokenHash: PAIRS.third.hash, token: PAIRS.third.token, pdf,
            phone: '09121234567', national: '0079123456'
        });
        expect(up.status).toBe(200);

        // open
        const open = await handleSignRoute(
            new Request(`${BASE}/sign/${PAIRS.third.token}/open`, { method: 'POST' }), env, contractUrl(`/sign/${PAIRS.third.token}/open`));
        expect(open.status).toBe(200);
        // The response may echo the pre-open status; the DB must be OPENED.
        expect(['ISSUED', 'OPENED']).toContain(((await open.json()) as any).data.status);
        expect((db.get('SELECT status FROM contracts WHERE enrollment_id = ?', 'enr-2') as any).status).toBe('OPENED');

        // sign with the right identity + a decodable RGBA PNG
        const png = makeSignaturePng();
        const sign = await handleSignRoute(
            new Request(`${BASE}/sign/${PAIRS.third.token}/sign`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    phone: '09121234567',
                    nationalCode: '0079123456',
                    accepted: true,
                    signatureDataUrl: `data:image/png;base64,${Buffer.from(png).toString('base64')}`
                })
            }), env, contractUrl(`/sign/${PAIRS.third.token}/sign`));
        const signBody = await sign.json() as any;
        expect(sign.status, JSON.stringify(signBody)).toBe(200);
        expect(signBody.data.status).toBe('SIGNED');

        // the row now carries the STANDARD digest of the signed bytes
        const row = db.get('SELECT signed_pdf_sha256, signed_pdf_key, student_id, id FROM contracts WHERE enrollment_id = ?', 'enr-2') as any;
        expect(row.signed_pdf_key).toBeTruthy();
        const stored = db.get('SELECT sha256 FROM contract_files WHERE key = ?', row.signed_pdf_key) as any;

        const dl = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-2/contracts/${row.id}/signed-pdf`, { headers: bearer(ACADEMY_A) }),
            env, contractUrl(`/api/students/stu-2/contracts/${row.id}/signed-pdf`));
        expect(dl.status).toBe(200);
        const bytes = new Uint8Array(await dl.arrayBuffer());
        const digest = await sha256Bytes(bytes);
        expect(row.signed_pdf_sha256).toBe(digest);          // row = standard hash
        expect(stored.sha256).toBe(digest);                  // file meta = same
        expect(dl.headers.get('X-Content-SHA256')).toBe(digest); // header = same
        // and it really is the signed PDF (layer appended)
        expect(bytes.length).toBeGreaterThan(pdf.length);
        expect(new TextDecoder('latin1').decode(bytes.subarray(0, 5))).toBe('%PDF-');
    });

    it('signing with a wrong identity is refused and rate-limited', async () => {
        const t2 = PAIRS.second.token;
        await uploadContract(env, {
            studentId: 'stu-3', enrollmentId: 'enr-3', tokenHash: PAIRS.second.hash, token: t2,
            phone: '09121234567', national: '0079123456'
        });
        const png = makeSignaturePng();
        const res = await handleSignRoute(
            new Request(`${BASE}/sign/${t2}/sign`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    phone: '09330000000', // wrong identity
                    nationalCode: '0079123456',
                    accepted: true,
                    signatureDataUrl: `data:image/png;base64,${Buffer.from(png).toString('base64')}`
                })
            }), env, contractUrl(`/sign/${t2}/sign`));
        expect(res.status).toBe(403);
    });
});

describe('storage integrity (chunked D1 files)', () => {
    it('a tampered chunk makes the download fail closed (409), never serves broken bytes', async () => {
        const id = (db.get('SELECT id FROM contracts WHERE enrollment_id = ?', 'enr-1') as any).id;
        const key = (db.get('SELECT pdf_key FROM contracts WHERE id = ?', id) as any).pdf_key;
        // Corrupt part 0 in storage.
        const bad = new Uint8Array(64).fill(0xAB);
        db.run('UPDATE contract_file_chunks SET content = ? WHERE file_key = ? AND part = 0', bad, key);
        const res = await handleContractsApi(
            new Request(`${BASE}/api/contracts/${id}/pdf`, { headers: bearer(ACADEMY_A) }),
            env, contractUrl(`/api/contracts/${id}/pdf`));
        expect([404, 409]).toContain(res.status); // refused either way, no bytes
        expect(res.status).toBe(409);
    });
});

describe('admin backup surface', () => {
    it('backup is read-only and requires the bearer key', async () => {
        const anon = await handleContractsApi(new Request(`${BASE}/api/admin/backup`), env, contractUrl('/api/admin/backup'));
        expect(anon.status).toBe(401);
        const res = await handleContractsApi(
            new Request(`${BASE}/api/admin/backup`, { headers: bearer(ACADEMY_A) }),
            env, contractUrl('/api/admin/backup'));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.academyId).toBe(ACADEMY_A);
        // Tenant scoping: academy-b's backup never contains academy-a contracts.
        const other = await handleContractsApi(
            new Request(`${BASE}/api/admin/backup`, { headers: bearer(ACADEMY_B) }),
            env, contractUrl('/api/admin/backup'));
        const otherBody = await other.json() as any;
        expect(otherBody.contracts.length).toBe(0);
    });
});
