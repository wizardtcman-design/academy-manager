// ============================================================
// Contract API (app side) — bearer key of the academy, scoped by X-Academy-Id
//   POST /api/contracts            upload PDF + metadata, returns the sign link
//   GET  /api/contracts/health     Worker + D1 + R2 readiness
//   GET  /api/contracts/:id        status of one contract
//   GET  /api/contracts/:id/pdf    original (?signed=1 for the signed copy)
//   POST /api/contracts/:id/revoke invalidate the link (signed contracts are final)
// ============================================================

import { Env } from '../types';
import { ContractStore } from '../contracts-store';
import { sha256Hex, maskPhone, maskNational } from '../crypto';

const MAX_PDF_BYTES = 6 * 1024 * 1024;
const MAX_SEAL_BYTES = 512 * 1024;

function json(data: unknown, status = 200): Response {
    return new Response(JSON.stringify(data), {
        status,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }
    });
}

function ok<T>(message: string, data: T): Response {
    return json({ success: true, message, data });
}

function fail(message: string, status = 400, extra: Record<string, unknown> = {}): Response {
    return json({ success: false, message, error: message, ...extra }, status);
}

function academyIdOf(request: Request, env: Env): string {
    const header = request.headers.get('X-Academy-Id') || '';
    const allowed = (env.ACADEMY_IDS || 'default').split(',').map((s) => s.trim()).filter(Boolean);
    const candidate = header.trim() || 'default';
    return allowed.includes(candidate) ? candidate : '';
}

function publicBase(request: Request, env: Env): string {
    return (env.PUBLIC_BASE_URL || new URL(request.url).origin).replace(/\/+$/, '');
}

function signUrlFor(base: string, token: string): string {
    return `${base}/sign?s=${encodeURIComponent(token)}`;
}

function rowToApi(row: any, base: string, token?: string) {
    return {
        id: row.id,
        enrollmentId: row.enrollment_id,
        status: row.status,
        expiresAtEpoch: row.expires_at_epoch,
        issuedAtEpoch: row.issued_at_epoch,
        openedAtEpoch: row.opened_at_epoch,
        signedAtEpoch: row.signed_at_epoch,
        signedAtShamsi: row.signed_at_shamsi,
        signerName: row.signer_name,
        r2Key: row.pdf_key,
        signedR2Key: row.signed_pdf_key,
        pdfSha256: row.pdf_sha256,
        signedPdfSha256: row.signed_pdf_sha256,
        rejectedAttempts: row.rejected_attempts,
        phoneMask: row.phone_mask,
        nationalMask: row.national_mask,
        signUrl: token ? signUrlFor(base, token) : ''
    };
}

export async function handleContractsApi(request: Request, env: Env, url: URL): Promise<Response> {
    const store = new ContractStore(env);

    if (url.pathname === '/api/contracts/health') {
        const health = await store.health();
        return ok('contract service', {
            version: 'contracts-1',
            d1: health.d1,
            r2: health.r2,
            r2Enabled: Boolean(env.CONTRACTS),
            error: health.error || '',
            academyScoped: (env.ACADEMY_IDS || '').includes(','),
            time: new Date().toISOString()
        });
    }

    const academyId = academyIdOf(request, env);
    if (!academyId) return fail('شناسه آموزشگاه مجاز نیست.', 403);

    if (!env.DB) return fail('D1 متصل نیست؛ قراردادها ذخیره نمی‌شوند.', 503);

    // ---- upload / re-issue ----
    if (url.pathname === '/api/contracts' && request.method === 'POST') {
        let form: FormData;
        try {
            form = await request.formData();
        } catch {
            return fail('درخواست باید multipart باشد.', 400);
        }
        const metaRaw = form.get('meta');
        const file = form.get('file');
        if (typeof metaRaw !== 'string' || !file || typeof (file as any).arrayBuffer !== 'function') {
            return fail('اطلاعات قرارداد یا فایل PDF ناقص است.', 400);
        }
        const fileSize = Number((file as any).size || 0);
        if (fileSize > MAX_PDF_BYTES) return fail('حجم PDF بیش از حد مجاز (۶ مگابایت) است.', 413);

        let meta: any;
        try {
            meta = JSON.parse(metaRaw);
        } catch {
            return fail('meta معتبر نیست.', 400);
        }
        const enrollmentId = String(meta.enrollmentId || '');
        const studentId = String(meta.studentId || '');
        const tokenHash = String(meta.tokenHash || '');
        if (!enrollmentId || !studentId || !/^[0-9a-f]{64}$/.test(tokenHash)) {
            return fail('شناسه ثبت‌نام، هنرجو یا توکن نامعتبر است.', 400);
        }

        const existing = await store.findByEnrollment(academyId, enrollmentId);
        if (existing?.status === 'SIGNED') {
            // Never replace a signed contract: the app is told it is final.
            return ok('این قرارداد قبلاً امضا شده و تغییر نمی‌کند.', rowToApi(existing, publicBase(request, env)));
        }

        const pdfBytes = new Uint8Array(await (file as any).arrayBuffer());
        const pdfSha = await sha256Hex(new TextDecoder('latin1').decode(pdfBytes));
        const info = meta.enrollment?.info || {};
        const student = meta.enrollment?.student || {};
        const verifySeed = `${student.phone || ''}|${student.nationalCode || ''}|${enrollmentId}`;
        const verifyHash = await sha256Hex(verifySeed);

        // Optional academy seal + manager signature, drawn on the signed PDF.
        const storeImage = async (part: any, slug: string) => {
            if (!part || typeof (part as any).arrayBuffer !== 'function') return undefined;
            const bytes = new Uint8Array(await (part as any).arrayBuffer());
            if (!bytes.length) return undefined;
            const png = bytes[0] === 0x89 && bytes[1] === 0x50;
            const jpeg = bytes[0] === 0xff && bytes[1] === 0xd8;
            if (!png && !jpeg) return undefined;
            if (bytes.length > MAX_SEAL_BYTES) return undefined;
            const dims = imageDims(bytes);
            const sha = await sha256Hex(new TextDecoder('latin1').decode(bytes));
            const key = `contracts/${academyId}/${enrollmentId}/${slug}.${png ? 'png' : 'jpg'}`;
            await putContractFile(env, key, bytes, sha, academyId);
            return { key, sha256: sha, width: dims.width, height: dims.height, png };
        };
        let sealInfo, sigInfo;
        try {
            sealInfo = await storeImage(form.get('seal'), 'seal');
            sigInfo = await storeImage(form.get('signature'), 'manager-signature');
        } catch (e: any) {
            sealInfo = undefined;
            sigInfo = undefined;
        }

        // One path for both backends: R2 when bound, otherwise chunked D1 rows.
        const pdfKey = `contracts/${academyId}/${enrollmentId}/original.pdf`;
        await writeContractFile(env, pdfKey, pdfBytes, pdfSha, academyId, 'application/pdf');

        // The link is issued by the app; the Worker never needs the token itself.
        const res = await store.upsert({
            academyId,
            id: String(meta.contractId || ''),
            enrollmentId,
            studentId,
            studentName: String(student.fullName || info.studentName || ''),
            courseTitle: String(info.courseTitle || ''),
            tokenHash,
            expiresAt: Number(meta.expiresAtEpoch || Date.now() + 30 * 86400000),
            pdfKey,
            pdfSha256: pdfSha,
            enrollmentJson: JSON.stringify(info),
            phoneMask: maskPhone(String(student.phone || '')),
            nationalMask: maskNational(String(student.nationalCode || '')),
            verifyHash,
            signerName: String(meta.signerName || ''),
            signerTitle: String(meta.signerTitle || ''),
            seal: sealInfo && { key: sealInfo.key, sha256: sealInfo.sha256, width: sealInfo.width, height: sealInfo.height },
            signature: sigInfo && { key: sigInfo.key, sha256: sigInfo.sha256, width: sigInfo.width, height: sigInfo.height },
            rotateToken: true
        });

        const appToken = String(meta.publicToken || '');
        const signUrl = appToken ? signUrlFor(publicBase(request, env), appToken) : '';
        if (appToken && (res.row.status === 'ISSUED' || res.row.status === 'OPENED')) {
            // Keep only a hash; the raw token lives solely in the signal link.
            await store.setAppToken(academyId, res.row.id, await sha256Hex(appToken));
        }

        await store.event(academyId, res.row.id, {
            type: res.created ? 'CREATED' : 'LINK_ROTATED',
            detail: `enrollment=${enrollmentId}`,
            ip: request.headers.get('CF-Connecting-IP') || '',
            userAgent: request.headers.get('User-Agent') || ''
        });

        return ok(
            res.created ? 'قرارداد ساخته شد.' : 'لینک امضای جدید برای همان قرارداد صادر شد.',
            {
                ...rowToApi(res.row, publicBase(request, env)),
                signUrl,
                created: res.created,
                storage: env.CONTRACTS ? 'R2' : 'D1'
            }
        );
    }

    const match = /^\/api\/contracts\/([^/]+)(\/pdf|\/revoke)?$/.exec(url.pathname);
    if (!match) return fail('مسیر نامعتبر است.', 404);
    const id = decodeURIComponent(match[1]);

    const row = await store.findById(academyId, id);
    if (!row) return fail('قرارداد پیدا نشد.', 404);

    if (request.method === 'GET' && !match[2]) {
        const base = publicBase(request, env);
        const res = ok('وضعیت قرارداد', rowToApi(row, base, row.status === 'ISSUED' || row.status === 'OPENED' ? 'link-in-app' : undefined));
        return res;
    }

    if (request.method === 'POST' && match[2] === '/revoke') {
        if (row.status === 'SIGNED') return fail('قرارداد امضاشده قابل لغو نیست.', 409);
        await store.revoke(academyId, row);
        await store.event(academyId, row.id, { type: 'REVOKED', ip: request.headers.get('CF-Connecting-IP') || '' });
        return ok('لینک امضا لغو شد.', { id: row.id, status: 'REVOKED' });
    }

    if (request.method === 'GET' && match[2] === '/pdf') {
        const signed = url.searchParams.get('signed') === '1';
        if (signed && row.status !== 'SIGNED') return fail('این قرارداد هنوز امضا نشده است.', 409);
        const key = signed ? row.signed_pdf_key : row.pdf_key;
        if (!key) return fail('فایلی برای این قرارداد ثبت نشده است.', 404);
        const bytes = await readContractFile(env, key);
        if (!bytes) return fail('فایل در فضای ذخیره‌سازی نیست.', 404);
        return new Response(bytes, {
            headers: {
                'Content-Type': 'application/pdf',
                'Content-Disposition': `attachment; filename="${signed ? 'signed' : 'contract'}-${row.id.slice(0, 8)}.pdf"`,
                'Cache-Control': 'no-store'
            }
        });
    }

    return fail('روش پشتیبانی نمی‌شود.', 405);
}

async function putContractFile(env: Env, key: string, bytes: Uint8Array, sha256: string, academyId: string) {
    await writeContractFile(env, key, bytes, sha256, academyId, key.endsWith('.png') ? 'image/png' : 'image/jpeg');
}

function imageDims(bytes: Uint8Array): { width: number; height: number } {
    if (bytes[0] === 0x89 && bytes[1] === 0x50) {
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        return { width: view.getUint32(16), height: view.getUint32(20) };
    }
    let i = 2;
    while (i < bytes.length - 9) {
        if (bytes[i] !== 0xff) { i++; continue; }
        const marker = bytes[i + 1];
        const len = (bytes[i + 2] << 8) | bytes[i + 3];
        if (marker >= 0xc0 && marker <= 0xc3) {
            return { width: (bytes[i + 7] << 8) | bytes[i + 8], height: (bytes[i + 5] << 8) | bytes[i + 6] };
        }
        if (len <= 0) break;
        i += 2 + len;
    }
    return { width: 0, height: 0 };
}

export function toBinary(value: unknown): Uint8Array {
    if (value == null) return new Uint8Array(0);
    if (value instanceof Uint8Array) return value;
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    if (Array.isArray(value)) return Uint8Array.from(value as number[]);
    if (typeof value === 'string') {
        // Some D1 clients hand back binary columns as a latin1 string.
        const out = new Uint8Array(value.length);
        for (let i = 0; i < value.length; i++) out[i] = value.charCodeAt(i) & 0xff;
        return out;
    }
    return new Uint8Array(0);
}

export async function readContractFile(env: Env, key: string): Promise<Uint8Array | null> {
    if (env.CONTRACTS) {
        const obj = await env.CONTRACTS.get(key);
        if (!obj) return null;
        return new Uint8Array(await obj.arrayBuffer());
    }
    const row = await env.DB!.prepare('SELECT content FROM contract_files WHERE key = ?').bind(key).first<any>();
    if (row?.content) {
        // Legacy rows: the whole file in one BLOB, written before chunking.
        return toBinary(row.content);
    }
    const { results } = await env.DB!.prepare(
        'SELECT part, content FROM contract_file_chunks WHERE file_key = ? ORDER BY part ASC'
    ).bind(key).all<any>();
    if (!results || results.length === 0) return null;
    const parts = results.map((r) => toBinary(r.content));
    const total = parts.reduce((n, p) => n + p.length, 0);
    const out = new Uint8Array(total);
    let offset = 0;
    for (const p of parts) {
        out.set(p, offset);
        offset += p.length;
    }
    return out;
}

export async function writeContractFile(
    env: Env, key: string, bytes: Uint8Array, sha256: string, academyId: string, contentType = 'application/pdf'
): Promise<void> {
    if (env.CONTRACTS) {
        await env.CONTRACTS.put(key, bytes, {
            httpMetadata: { contentType },
            customMetadata: { sha256, academyId }
        });
        return;
    }
    // D1 caps a row at 2 MB, so a file is split into 256 KB parts. The metadata
    // row is written last: a half-written file is never visible to readers.
    const chunkSize = 256 * 1024;
    const parts = Math.max(1, Math.ceil(bytes.length / chunkSize));
    await env.DB!.prepare('DELETE FROM contract_file_chunks WHERE file_key = ?').bind(key).run();
    for (let i = 0; i < parts; i++) {
        const slice = bytes.subarray(i * chunkSize, Math.min(bytes.length, (i + 1) * chunkSize));
        await env.DB!.prepare(
            'INSERT OR REPLACE INTO contract_file_chunks (file_key, part, content) VALUES (?,?,?)'
        ).bind(key, i, slice).run();
    }
    await env.DB!.prepare(
        `INSERT OR REPLACE INTO contract_files (key, academy_id, content, sha256, created_at_epoch)
         VALUES (?,?,NULL,?,?)`
    ).bind(key, academyId, sha256, Date.now()).run();
}
