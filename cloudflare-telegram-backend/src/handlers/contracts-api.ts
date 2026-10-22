// ============================================================
// Contract API (app side) — bearer key of the academy, scoped by X-Academy-Id
//   POST /api/contracts            upload PDF + metadata, returns the sign link
//   GET  /api/contracts/health     Worker + D1 + R2 readiness
//   GET  /api/contracts/:id        status of one contract
//   GET  /api/contracts/:id/pdf    original (?signed=1 for the signed copy)
//   GET  /api/students/:studentId/contracts/:contractId/(pdf|signed-pdf)
//        secure per-student download: the contract must belong to that student
//   POST /api/contracts/:id/revoke invalidate the link (signed contracts are final)
// ============================================================

import { Env } from '../types';
import { ContractStore } from '../contracts-store';
import { sha256Hex, sha256HexBytes, sha256HexLegacyLatin1, maskPhone, maskNational, timingSafeEqual, randomToken } from '../crypto';
import { AcademyDatabase } from '../db';
import { academyBotUsername, telegramDeepLink } from '../telegram';

const MAX_PDF_BYTES = 6 * 1024 * 1024;
const MAX_SEAL_BYTES = 512 * 1024;
/** One-time /start link tokens live for 30 days or until used. */
const LINK_TOKEN_TTL_DAYS = 30;

/** Persian welcome message shown/queued for a newly registered student.
 *  Stage 6: the WELCOME template's fallback builder — the per-academy
 *  override (when valid) wins at the call site. Output unchanged. */
export function welcomeMessageText(academyName: string, fullName: string, botLink: string): string {
    const name = (fullName || '').trim() || 'هنرجو';
    return `سلام ${name} عزیز! 👋\n\n` +
        `به خانواده <b>${academyName}</b> خوش آمدید. 🌱\n` +
        `برای دریافت اعلان‌های اقساط، رسیدها و قرارداد آموزشی خود در تلگرام، روی لینک زیر بزنید و Start را بزنید:\n\n${botLink}\n\n` +
        `🔒 این لینک مخصوص شماست و فقط یک بار قابل استفاده است.`;
}

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

/** Bearer key of the academy; compared in constant time. Nobody else may read contracts. */
export function bearerAuthorized(request: Request, env: Env): boolean {
    const header = request.headers.get('Authorization') || '';
    if (!header.startsWith('Bearer ')) return false;
    const expected = (env.API_SECRET_KEY || '').trim();
    const given = header.slice(7).trim();
    return Boolean(expected) && timingSafeEqual(given, expected);
}

export async function handleContractsApi(request: Request, env: Env, url: URL): Promise<Response> {
    const store = new ContractStore(env);

    // Health only reports readiness, never data. Error details are reserved
    // for the academy key holder: an anonymous caller gets booleans only.
    if (url.pathname === '/api/contracts/health') {
        const health = await store.health();
        const authed = bearerAuthorized(request, env);
        return ok('contract service', {
            version: 'contracts-1',
            d1: health.d1,
            r2: health.r2,
            r2Enabled: Boolean(env.CONTRACTS),
            error: authed ? (health.error || '') : '',
            academyScoped: (env.ACADEMY_IDS || '').includes(','),
            time: new Date().toISOString()
        });
    }

    if (!bearerAuthorized(request, env)) return fail('دسترسی غیرمجاز.', 401);

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
        if (existing && existing.student_id !== studentId) {
            // An enrollment belongs to exactly one student; never let an upload
            // re-point a contract to another student.
            return fail('این ثبت‌نام به هنرجوی دیگری تعلق دارد.', 409);
        }
        if (existing?.status === 'SIGNED') {
            // Never replace a signed contract: the app is told it is final.
            return ok('این قرارداد قبلاً امضا شده و تغییر نمی‌کند.', rowToApi(existing, publicBase(request, env)));
        }

        const pdfBytes = new Uint8Array(await (file as any).arrayBuffer());
        // Standard SHA-256 over the raw bytes: the app verifies exactly this
        // digest, so hashing through a latin1 string would break every PDF.
        const pdfSha = await sha256HexBytes(pdfBytes);
        const info = meta.enrollment?.info || {};
        const student = meta.enrollment?.student || {};
        const verifySeed = `${student.phone || ''}|${student.nationalCode || ''}|${enrollmentId}`;
        const verifyHash = await sha256Hex(verifySeed);
        // The signing page reads the info block, so it must never contain the
        // raw phone / national code (the public page would hand them out).
        const publicInfo: Record<string, unknown> = { ...info };
        delete publicInfo.phone;
        delete publicInfo.nationalCode;
        delete publicInfo.national_code;

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
            const sha = await sha256HexBytes(bytes);
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
            enrollmentJson: JSON.stringify(publicInfo),
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
        if (appToken && (res.row.status === 'ISSUED' || res.row.status === 'OPENED')) {
            // Keep the hash on the contract row; the raw token only lives in a
            // separate row so the SAME link can be re-sent before signing.
            await store.setAppToken(academyId, res.row.id, await sha256Hex(appToken));
            await store.storePendingToken(academyId, res.row.id, appToken);
        }
        // Never trust a client-built link: hand back the server's own URL.
        const keptToken = await store.pendingToken(academyId, res.row.id, res.row.token_hash);
        const signUrl = keptToken ? signUrlFor(publicBase(request, env), keptToken) : '';

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

    // ---- backup of this tenant (contracts, signatures, seal, file links) ----
    // Read-only. Never deletes or rewrites anything. Files can be downloaded
    // separately through the per-student route, which checks ownership.
    if (url.pathname === '/api/admin/backup' && request.method === 'GET') {
        const read = async (sql: string) => ((await env.DB!.prepare(sql).bind(academyId).all<any>()).results || []);
        const contracts = await read('SELECT * FROM contracts WHERE academy_id = ?');
        const events = await read('SELECT * FROM contract_events WHERE academy_id = ? ORDER BY id ASC');
        const files = await read(
            `SELECT f.key, f.sha256, f.created_at_epoch,
                    (SELECT COUNT(*) FROM contract_file_chunks c WHERE c.file_key = f.key) AS chunk_count,
                    length(f.content) AS legacy_bytes
             FROM contract_files f WHERE f.academy_id = ?`
        );
        const manifest = {
            format: 'academy-contracts-backup',
            version: 1,
            academyId,
            generatedAt: new Date().toISOString(),
            counts: { contracts: contracts.length, events: events.length, files: files.length },
            contracts,
            events,
            files
        };
        const body = JSON.stringify(manifest);
        return new Response(body, {
            headers: {
                'Content-Type': 'application/json; charset=utf-8',
                'Content-Disposition': `attachment; filename="contracts-backup-${academyId}-${Date.now()}.json"`,
                'Cache-Control': 'no-store'
            }
        });
    }

    // ---- raw copy of one stored file, part by part (backup / restore) ----
    //   GET /api/admin/file?key=...&sha256=...&from=0&count=3
    // Returns the exact bytes of parts [from, from+count) as base64, plus the
    // total part count, so a 6 MB PDF can be pulled in pieces and re-joined.
    if (url.pathname === '/api/admin/file' && request.method === 'GET') {
        const key = url.searchParams.get('key') || '';
        const sha = url.searchParams.get('sha256') || '';
        const from = Math.max(0, Number(url.searchParams.get('from') || 0));
        const count = Math.min(6, Math.max(1, Number(url.searchParams.get('count') || 3)));
        const record = await env.DB!.prepare(
            'SELECT key, sha256 FROM contract_files WHERE academy_id = ? AND key = ?'
        ).bind(academyId, key).first<any>();
        if (!record) return fail('فایل در این آموزشگاه نیست.', 404);
        if (sha && record.sha256 !== sha) return fail('هش فایل با نسخه ثبت‌شده نمی‌خواند.', 409);
        const { results } = await env.DB!.prepare(
            'SELECT part, content FROM contract_file_chunks WHERE file_key = ? AND part >= ? AND part < ? ORDER BY part ASC'
        ).bind(key, from, from + count).all<any>();
        const totalRow = await env.DB!.prepare(
            'SELECT COUNT(*) AS n FROM contract_file_chunks WHERE file_key = ?'
        ).bind(key).first<{ n: number }>();
        const parts = (results || []).map((r) => ({ part: Number(r.part), data: b64(toBinary(r.content)) }));
        return ok('بخش فایل', { key, sha256: record.sha256, totalParts: Number(totalRow?.n || 0), from, parts });
    }

    // ---- Stage 2: secure /start link token + welcome SMS preparation ----
    //   POST /api/students/:studentId/telegram-link  -> fresh one-time bot link
    //   POST /api/students/:studentId/welcome        -> welcome text + bot link,
    //      queued in sms_outbox as DISABLED (no SMS provider is configured yet,
    //      so nothing is ever sent from here).
    const linkMatch = /^\/api\/students\/([^/]+)\/(telegram-link|welcome)$/.exec(url.pathname);
    if (linkMatch) {
        if (request.method !== 'POST') return fail('روش پشتیبانی نمی‌شود.', 405);
        const studentId = decodeURIComponent(linkMatch[1]);
        const kind = linkMatch[2];
        const student = await env.DB!.prepare(
            'SELECT id, full_name, phone FROM students WHERE id = ? AND academy_id = ?'
        ).bind(studentId, academyId).first<any>();
        if (!student) return fail('هنرجو پیدا نشد.', 404);

        let body: any = {};
        try { body = await request.json(); } catch (_) {}

        const db = new AcademyDatabase(env, academyId);

        // Resolve the bot username FIRST: without it no working link can be
        // built, and we must not rotate the student's token for nothing.
        const botUsername = await academyBotUsername(env, academyId, String(body?.botUsername || ''));
        if (!botUsername) {
            return fail('نام کاربری ربات تلگرام تنظیم نشده است؛ ابتدا ربات را متصل کنید.', 409);
        }

        if (kind === 'welcome') {
            // Idempotent: a second call returns the ALREADY queued text (with its
            // original, still-working link) instead of minting a new token that
            // would silently kill the link inside the queued SMS.
            const existingOutbox = await env.DB!.prepare(
                'SELECT body, status, error FROM sms_outbox WHERE id = ?'
            ).bind(`welcome-${studentId}`).first<any>();
            if (existingOutbox) {
                const storedLink = /(https:\/\/t\.me\/[^\s]+)/.exec(existingOutbox.body || '')?.[1] || '';
                return ok('پیام خوش‌آمد قبلاً آماده شده است.', {
                    message: existingOutbox.body,
                    botLink: storedLink,
                    sms: { status: existingOutbox.status, reason: existingOutbox.error },
                    deduped: true
                });
            }
        }

        // Fresh one-time token (rotates any previous link of this student).
        const token = randomToken(24);
        const expiresAt = Date.now() + LINK_TOKEN_TTL_DAYS * 86400000;
        await env.DB!.prepare(
            'UPDATE students SET telegram_link_hash = ?, telegram_link_expires = ?, updated_at_epoch = ? WHERE id = ? AND academy_id = ?'
        ).bind(await sha256Hex(token), expiresAt, Date.now(), studentId, academyId).run();

        const botLink = telegramDeepLink(botUsername, token);
        if (!botLink) return fail('ساخت لینک ربات ممکن نشد.', 500);

        if (kind === 'telegram-link') {
            return ok('لینک اختصاصی یک‌بارمصرف ساخته شد.', { botLink, expiresAtEpoch: expiresAt });
        }

        // ---- welcome (SMS prepared but DISABLED) ----
        const academyName = await db.getSetting('academy_name', 'آموزشگاه');
        const legacyWelcome = welcomeMessageText(academyName, student.full_name || '', botLink);
        // Stage 6: the WELCOME template (override when valid, default =
        // legacyWelcome); auto=false prepares nothing (no outbox row).
        const { getMessagingConfig, renderEvent } = await import('../messaging');
        const cfg = await getMessagingConfig(db);
        const text = cfg.events.WELCOME.auto
            ? renderEvent(cfg, 'WELCOME', {
                studentName: (student.full_name || '').trim() || 'هنرجو',
                academyName,
                botLink
            }, legacyWelcome).text
            : '';
        const outboxId = `welcome-${studentId}`;
        if (text) {
            await env.DB!.prepare(
                `INSERT INTO sms_outbox (id, academy_id, reminder_id, phone, body, status, error, created_at_epoch)
                 VALUES (?,?,?,?,?,?,?,?)`
            ).bind(outboxId, academyId, 'WELCOME', student.phone || '', text, 'DISABLED',
                'sms_provider_not_configured', Date.now()).run();
            await db.logMessage({
                id: crypto.randomUUID(),
                student_id: studentId,
                student_name: student.full_name || '',
                chat_id: '',
                message_type: 'WELCOME_PREPARED',
                content: 'welcome sms queued as DISABLED (no provider)',
                status: 'SUCCESS',
                error_message: '',
                timestamp_epoch: Date.now(),
                timestamp_shamsi: ''
            });
        }
        return ok(text
            ? 'پیام خوش‌آمد آماده شد؛ ارسال پیامک فعال نیست.'
            : 'ارسال خودکار پیام خوش‌آمد در تنظیمات پیام‌رسانی غیرفعال است.', {
            message: text,
            botLink,
            tokenExpiresAtEpoch: expiresAt,
            sms: text
                ? { status: 'DISABLED', reason: 'sms_provider_not_configured' }
                : { status: 'BLOCKED', reason: 'welcome_auto_disabled_by_settings' },
            deduped: false
        });
    }

    // ---- per-student secure download ----
    const owned = /^\/api\/students\/([^/]+)\/contracts\/([^/]+)\/(pdf|signed-pdf)$/.exec(url.pathname);
    if (owned) {
        if (request.method !== 'GET') return fail('روش پشتیبانی نمی‌شود.', 405);
        const studentId = decodeURIComponent(owned[1]);
        const contractId = decodeURIComponent(owned[2]);
        const wantSigned = owned[3] === 'signed-pdf';
        const row = await store.findById(academyId, contractId);
        if (!row || row.student_id !== studentId) return fail('قرارداد پیدا نشد.', 404);
        if (wantSigned && row.status !== 'SIGNED') return fail('این قرارداد هنوز امضا نشده است.', 409);
        const key = wantSigned ? row.signed_pdf_key : row.pdf_key;
        const sha = wantSigned ? row.signed_pdf_sha256 : row.pdf_sha256;
        const file = await readContractFileForStudent(env, academyId, contractId, studentId, key, sha);
        if (!file.bytes) return fail(file.error, file.status);
        await store.event(academyId, row.id, {
            type: 'PDF_VIEW',
            detail: wantSigned ? 'signed:app' : 'original:app',
            ip: request.headers.get('CF-Connecting-IP') || '',
            userAgent: request.headers.get('User-Agent') || ''
        });
        return new Response(file.bytes, {
            headers: {
                'Content-Type': 'application/pdf',
                'Content-Disposition': `attachment; filename="${wantSigned ? 'signed' : 'contract'}-${row.id.slice(0, 8)}.pdf"`,
                'Cache-Control': 'no-store',
                'X-Content-Type-Options': 'nosniff',
                // Standard SHA-256 of the exact bytes served: lets the app verify
                // the download even when the row still carries a legacy hash.
                'X-Content-SHA256': file.sha256 || ''
            }
        });
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
        // Legacy app path (bearer-protected, tenant-scoped): served through the
        // same ownership/integrity reader as the per-student route, so a wrong
        // key can never read a file and a damaged file is never served.
        const signed = url.searchParams.get('signed') === '1';
        if (signed && row.status !== 'SIGNED') return fail('این قرارداد هنوز امضا نشده است.', 409);
        const key = signed ? row.signed_pdf_key : row.pdf_key;
        const sha = signed ? row.signed_pdf_sha256 : row.pdf_sha256;
        const file = await readContractFileForStudent(env, academyId, row.id, row.student_id, key, sha);
        if (!file.bytes) return fail(file.error || 'فایل در فضای ذخیره‌سازی نیست.', file.status);
        return new Response(file.bytes, {
            headers: {
                'Content-Type': 'application/pdf',
                'Content-Disposition': `attachment; filename="${signed ? 'signed' : 'contract'}-${row.id.slice(0, 8)}.pdf"`,
                'Cache-Control': 'no-store',
                'X-Content-Type-Options': 'nosniff',
                'X-Content-SHA256': file.sha256 || ''
            }
        });
    }

    return fail('روش پشتیبانی نمی‌شود.', 405);
}

async function putContractFile(env: Env, key: string, bytes: Uint8Array, sha256: string, academyId: string) {
    await writeContractFile(env, key, bytes, sha256, academyId, key.endsWith('.png') ? 'image/png' : 'image/jpeg');
}

function b64(bytes: Uint8Array): string {
    let bin = '';
    const step = 0x8000;
    for (let i = 0; i < bytes.length; i += step) {
        bin += String.fromCharCode(...bytes.subarray(i, i + step));
    }
    return btoa(bin);
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
    const meta = await env.DB!.prepare('SELECT sha256 FROM contract_files WHERE key = ?').bind(key).first<any>();
    // The metadata row is written last: without it the file is not complete yet.
    if (!meta) return null;
    const row = await env.DB!.prepare('SELECT content FROM contract_files WHERE key = ?').bind(key).first<any>();
    if (row?.content) {
        // Legacy rows: the whole file in one BLOB, written before chunking.
        return await verifyStoredBytes(env, key, meta, toBinary(row.content));
    }
    const { results } = await env.DB!.prepare(
        'SELECT part, content FROM contract_file_chunks WHERE file_key = ? ORDER BY part ASC'
    ).bind(key).all<any>();
    if (!results || results.length === 0) return null;
    // Parts are numbered 0..n-1 with no gaps or duplicates: a damaged or
    // half-written file is refused instead of being served as if it were whole.
    for (let i = 0; i < results.length; i++) {
        if (Number(results[i].part) !== i) return null;
    }
    const parts = results.map((r) => toBinary(r.content));
    const total = parts.reduce((n, p) => n + p.length, 0);
    const out = new Uint8Array(total);
    let offset = 0;
    for (const p of parts) {
        out.set(p, offset);
        offset += p.length;
    }
    // Verify against the recorded SHA-256 (end-to-end integrity of the download).
    return await verifyStoredBytes(env, key, meta, out);
}

/**
 * Accepts stored bytes only when they match the recorded digest. Rows written
 * from 2.7.2 on carry the standard SHA-256 of the raw bytes; rows written by
 * older builds carry the legacy latin1 digest. Anything else is refused.
 */
async function verifyStoredBytes(env: Env, key: string, meta: { sha256?: string } | null, out: Uint8Array): Promise<Uint8Array | null> {
    if (meta?.sha256) {
        const got = await sha256HexBytes(out);
        if (got !== meta.sha256) {
            const legacy = await sha256HexLegacyLatin1(out);
            if (legacy !== meta.sha256) {
                console.error(`contract file hash mismatch key=${key} expected=${meta.sha256.slice(0, 12)} got=${got.slice(0, 12)}`);
                return null;
            }
        }
    }
    // A PDF must start and end with its trailer, otherwise it is truncated.
    if (key.endsWith('.pdf')) {
        const head = new TextDecoder('latin1').decode(out.subarray(0, 5));
        const tail = new TextDecoder('latin1').decode(out.subarray(Math.max(0, out.length - 1024)));
        if (head !== '%PDF-' || !tail.includes('%%EOF')) return null;
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
    // 0. metadata row is dropped first, so no reader sees the old row with new parts.
    await env.DB!.prepare('DELETE FROM contract_files WHERE key = ?').bind(key).run();
    await env.DB!.prepare('DELETE FROM contract_file_chunks WHERE file_key = ?').bind(key).run();
    for (let i = 0; i < parts; i++) {
        const slice = bytes.subarray(i * chunkSize, Math.min(bytes.length, (i + 1) * chunkSize));
        await env.DB!.prepare(
            'INSERT OR REPLACE INTO contract_file_chunks (file_key, part, content) VALUES (?,?,?)'
        ).bind(key, i, slice).run();
    }
    // Read the parts back and compare the whole-file hash before publishing.
    const { results } = await env.DB!.prepare(
        'SELECT part, content FROM contract_file_chunks WHERE file_key = ? ORDER BY part ASC'
    ).bind(key).all<any>();
    const back = (results || []).map((r) => toBinary(r.content));
    const total = back.reduce((n, p) => n + p.length, 0);
    const joined = new Uint8Array(total);
    let off = 0;
    for (const p of back) { joined.set(p, off); off += p.length; }
    const backSha = await sha256HexBytes(joined);
    if (backSha !== sha256) {
        await env.DB!.prepare('DELETE FROM contract_file_chunks WHERE file_key = ?').bind(key).run();
        throw new Error('contract file verification failed after write');
    }
    await env.DB!.prepare(
        `INSERT OR REPLACE INTO contract_files (key, academy_id, content, sha256, created_at_epoch)
         VALUES (?,?,NULL,?,?)`
    ).bind(key, academyId, sha256, Date.now()).run();
}

/**
 * Reads a file that belongs to ONE student of ONE academy. Both the contract id
 * and the student id must match, the key must live under that contract folder,
 * and the stored hash must equal the one recorded on the contract row.
 */
export async function readContractFileForStudent(
    env: Env, academyId: string, contractId: string, studentId: string, key: string, expectedSha: string
): Promise<{ bytes: Uint8Array | null; error: string; status: number; sha256?: string }> {
    if (!key) return { bytes: null, error: 'فایلی ثبت نشده است.', status: 404 };
    const { results } = await env.DB!.prepare(
        'SELECT id, student_id, pdf_key, signed_pdf_key FROM contracts WHERE academy_id = ? AND student_id = ? AND id = ?'
    ).bind(academyId, studentId, contractId).all<any>();
    const row = (results || [])[0];
    if (!row) return { bytes: null, error: 'قرارداد پیدا نشد.', status: 404 };
    const allowed = [row.pdf_key, row.signed_pdf_key].filter(Boolean);
    if (!allowed.includes(key)) {
        return { bytes: null, error: 'این فایل متعلق به قرارداد دیگری است.', status: 403 };
    }
    const bytes = await readContractFile(env, key);
    if (!bytes) return { bytes: null, error: 'فایل سالم نیست یا ناقص است.', status: 409 };
    // The digest the caller recorded must match; legacy rows (pre-2.7.2) carry
    // the latin1 digest and are accepted only through that exact fallback.
    if (expectedSha) {
        const got = await sha256HexBytes(bytes);
        if (got !== expectedSha) {
            const legacy = await sha256HexLegacyLatin1(bytes);
            if (legacy !== expectedSha) return { bytes: null, error: 'فایل با نسخه ثبت‌شده مطابقت ندارد.', status: 409 };
        }
    }
    return { bytes, error: '', status: 200, sha256: await sha256HexBytes(bytes) };
}
