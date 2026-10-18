// ============================================================
// Public signing endpoints + mobile page (no login)
//   GET  /sign?s=<token>
//   POST /sign/:token/open     contract payload shown to the student
//   POST /sign/:token/sign     { phone, nationalCode, signatureDataUrl, accepted }
//
// The token is random, stored only as SHA-256, single-use (after signing) and
// expires. Failed verifications are rate-limited per contract and per IP and
// are written to contract_events.
// ============================================================

import { Env } from '../types';
import { ContractStore, ContractRow } from '../contracts-store';
import { sha256Hex, maskPhone, maskNational } from '../crypto';
import { appendSignatureLayer, toPersianDigits } from '../pdf';
import { readContractFile, writeContractFile } from './contracts-api';

const MAX_FAILED = 5;
const LOCK_WINDOW_MS = 30 * 60 * 1000;
const MAX_SIG_BYTES = 900 * 1024;

function page(status: number, html: string): Response {
    return new Response(html, {
        status,
        headers: {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'no-store',
            'Referrer-Policy': 'no-referrer',
            'X-Frame-Options': 'DENY',
            'X-Content-Type-Options': 'nosniff',
            'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: blob:; connect-src 'self'; base-uri 'none'; form-action 'self'"
        }
    });
}

function api(data: unknown, status = 200): Response {
    return new Response(JSON.stringify(data), {
        status,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }
    });
}

let tablesReady = false;
async function ensureMigration(env: Env): Promise<boolean> {
    if (tablesReady) return true;
    if (!env.DB) return false;
    try {
        await env.DB.prepare('SELECT 1 FROM contracts LIMIT 1').first();
        tablesReady = true;
        return true;
    } catch {
        try {
            await env.DB.prepare('SELECT 1 FROM contract_files LIMIT 1').first();
            tablesReady = true;
        } catch {
            // no-op: health endpoint reports the missing migration
        }
        return false;
    }
}

async function loadByToken(env: Env, token: string): Promise<ContractRow | null> {
    const clean = (token || '').trim();
    if (!/^[A-Za-z0-9_-]{20,64}$/.test(clean)) return null;
    const store = new ContractStore(env);
    return store.findByTokenHash(await sha256Hex(clean));
}

function expireIfNeeded(row: ContractRow): string {
    if (['SIGNED', 'REVOKED'].includes(row.status)) return row.status;
    if (row.expires_at_epoch > 0 && row.expires_at_epoch < Date.now()) return 'EXPIRED';
    return row.status;
}

const CLOSED_MARKUP = (kind: string, title: string, body: string) => `<!doctype html>
<html lang="fa" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title><style>${BASE_CSS}</style></head>
<body><main><div class="card state"><div class="icon ${kind}">${kind === 'ok' ? '✓' : kind === 'wait' ? '…' : '!'}</div>
<h1>${title}</h1><p>${body}</p></div></main></body></html>`;

const BASE_CSS = `
:root{--bg:#f7f7f8;--card:#fff;--line:#e4e4e7;--text:#18181b;--soft:#71717a;--ok:#15803d;--err:#b91c1c}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.8 Vazirmatn,Tahoma,system-ui,sans-serif}
main{max-width:620px;margin:0 auto;padding:16px 14px 120px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin-bottom:12px}
h1{font-size:19px;margin:0 0 6px}
h2{font-size:15px;margin:18px 0 8px;color:var(--soft)}
p{margin:0 0 8px;color:#3f3f46}
.muted{color:var(--soft);font-size:13px}
.row{display:flex;justify-content:space-between;gap:10px;padding:7px 0;border-bottom:1px dashed var(--line);font-size:14px}
.row:last-child{border-bottom:0}
.row b{font-weight:600}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:7px 6px;border-bottom:1px solid var(--line);text-align:right}
th{color:var(--soft);font-weight:600}
.state{text-align:center;padding:34px 18px}
.icon{width:56px;height:56px;border-radius:50%;display:grid;place-items:center;margin:0 auto 12px;font-size:26px;font-weight:700}
.icon.ok{background:#f0fdf4;color:var(--ok)}.icon.err{background:#fef2f2;color:var(--err)}.icon.wait{background:#f4f4f5;color:var(--soft)}
label{display:block;font-size:13px;font-weight:600;margin:12px 0 4px}
input{width:100%;padding:11px 12px;border:1px solid var(--line);border-radius:10px;font:inherit;background:#fff;direction:ltr;text-align:left}
input:focus{outline:2px solid #a5b4fc;border-color:#a5b4fc}
.pad{border:2px dashed #c7cad1;border-radius:12px;background:#fff;touch-action:none;width:100%;height:200px;display:block}
.actions{position:fixed;right:0;left:0;bottom:0;background:rgba(255,255,255,.96);border-top:1px solid var(--line);padding:12px 14px calc(12px + env(safe-area-inset-bottom))}
.actions .inner{max-width:620px;margin:0 auto;display:flex;gap:8px}
button{flex:1;border:0;border-radius:12px;padding:13px;font:inherit;font-weight:700;cursor:pointer}
button.primary{background:#18181b;color:#fff}
button.ghost{background:#f4f4f5;color:#18181b;border:1px solid var(--line);flex:0 0 auto;padding:13px 16px}
button:disabled{opacity:.5}
.err{color:var(--err);font-size:13px;margin-top:8px;font-weight:600}
.chk{display:flex;gap:8px;align-items:flex-start;font-size:13.5px;margin-top:14px}
.chk input{width:20px;height:20px;margin:2px 0 0}
.badge{display:inline-block;background:#f4f4f5;border-radius:999px;padding:1px 9px;font-size:12px;color:var(--soft)}
`;

function errorPage(title: string, body: string, status = 400) {
    return page(status, CLOSED_MARKUP('err', title, body));
}

export async function handleSignRoute(request: Request, env: Env, url: URL): Promise<Response> {
    if (!(await ensureMigration(env))) {
        return errorPage('سرویس قرارداد آماده نیست', 'جدول‌های قرارداد روی سرور ساخته نشده‌اند. با مدیر آموزشگاه تماس بگیرید.', 503);
    }
    const store = new ContractStore(env);
    const ip = request.headers.get('CF-Connecting-IP') || '';
    const ua = request.headers.get('User-Agent') || '';

    // ---- POST /sign/:token/open | /sign ----
    if (request.method === 'POST') {
        let token = '';
        let action = 'open';
        const m = /^\/sign\/([A-Za-z0-9_-]{20,64})\/(open|sign)$/.exec(url.pathname);
        if (m) {
            token = m[1];
            action = m[2];
        } else if (url.pathname === '/sign') {
            const body = await request.json().catch(() => ({})) as any;
            token = String(body.token || '');
            action = 'open';
        } else {
            return api({ success: false, message: 'مسیر نامعتبر' }, 404);
        }

        const row = await loadByToken(env, token);
        if (!row) return api({ success: false, message: 'لینک معتبر نیست.' }, 404);
        const status = expireIfNeeded(row);

        if (status === 'REVOKED') return api({ success: false, message: 'این لینک توسط آموزشگاه لغو شده است.' }, 410);
        if (status === 'EXPIRED') return api({ success: false, message: 'مهلت این لینک به پایان رسیده است. لطفاً از آموزشگاه لینک جدید بخواهید.' }, 410);

        if (action === 'open') {
            const opened = row.opened_at_epoch;
            if (!opened) await store.markOpened(row.academy_id, row, ip, ua);
            if (!opened) {
                await store.event(row.academy_id, row.id, { type: 'OPENED', ip, userAgent: ua });
            }
            const info = JSON.parse(row.enrollment_json || '{}');
            return api({
                success: true,
                data: {
                    contractNumber: info.contractNumber || row.id.slice(0, 8),
                    academyName: info.academyName || 'آموزشگاه',
                    studentName: row.student_name,
                    courseTitle: row.course_title,
                    startDate: info.startDate || '',
                    endDate: info.endDate || '',
                    totalFee: info.totalFee || 0,
                    discount: info.discount || 0,
                    finalFee: info.finalFee || 0,
                    currency: info.currency || 'تومان',
                    installmentCount: info.installmentCount || 0,
                    installments: info.installments || [],
                    phoneMask: row.phone_mask,
                    nationalMask: row.national_mask,
                    status,
                    expiresAtEpoch: row.expires_at_epoch,
                    signedAtShamsi: row.signed_at_shamsi,
                    // Final acceptance method is a product decision: when the owner
                    // turns on SMS OTP the app sends requiresOtp and the server only
                    // accepts a code it produced. Today it stays false.
                    requiresOtp: false,
                    pdfUrl: `/sign/${token}/pdf`
                }
            });
        }

        // ---- sign ----
        if (row.status === 'SIGNED') {
            return api({ success: false, message: 'این قرارداد قبلاً امضا شده است.', alreadySigned: true }, 409);
        }
        const locked = row.rejected_attempts >= MAX_FAILED &&
            Date.now() - row.last_attempt_epoch < LOCK_WINDOW_MS;
        if (locked) {
            return api({ success: false, message: 'به دلیل تلاش‌های ناموفق، ورود موقتاً قفل شده است. بعداً تلاش کنید.' }, 429);
        }

        const body = await request.json().catch(() => ({})) as any;
        const phone = String(body.phone || '').replace(/\D/g, '');
        const national = String(body.nationalCode || '').replace(/\D/g, '');
        const accepted = body.accepted === true;
        const sig = String(body.signatureDataUrl || '');
        if (!accepted) return api({ success: false, message: 'پذیرش مفاد قرارداد الزامی است.' }, 400);
        if (!phone || !national) return api({ success: false, message: 'شماره تماس و کد ملی را کامل وارد کنید.' }, 400);
        if (!sig.startsWith('data:image/png;base64,') && !sig.startsWith('data:image/jpeg;base64,')) {
            return api({ success: false, message: 'امضای دستی ثبت نشده است.' }, 400);
        }

        const verifyHash = await sha256Hex(`${phone}|${national}|${row.enrollment_id}`);
        if (verifyHash !== row.verify_hash) {
            const attempts = await store.recordFailedAttempt(row.academy_id, row, ip, ua, 'identity mismatch');
            return api({
                success: false,
                message: 'شماره تماس یا کد ملی با سوابق این هنرجو مطابقت ندارد.',
                attemptsLeft: Math.max(0, MAX_FAILED - attempts)
            }, 403);
        }

        const base64 = sig.split(',')[1] || '';
        let imageBytes: Uint8Array;
        try {
            imageBytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
        } catch {
            return api({ success: false, message: 'تصویر امضا خوانده نشد.' }, 400);
        }
        if (imageBytes.length > MAX_SIG_BYTES) return api({ success: false, message: 'حجم امضا زیاد است.' }, 413);

        const original = await readContractFile(env, row.pdf_key);
        if (!original) return api({ success: false, message: 'فایل قرارداد روی سرور نیست.' }, 500);

        const dims = imageSize(imageBytes);
        const isPng = imageBytes[0] === 0x89 && imageBytes[1] === 0x50 && imageBytes[2] === 0x4e && imageBytes[3] === 0x47;
        const info = JSON.parse(row.enrollment_json || '{}');
        const faTime = toPersianDigits(new Date().toLocaleString('fa-IR', { timeZone: 'Asia/Tehran' }));
        const printName = (row.student_name || '').replace(/[^\u0600-\u06FF\s]/g, '').trim() || 'هنرجو';
        const printCourse = (row.course_title || '').replace(/[^\u0600-\u06FF\s]/g, '').trim() || 'دوره';
        const lines = [
            `قرارداد ${toPersianDigits(String(info.contractNumber || row.id.slice(0, 8)))} - امضای دیجیتال هنرجو`,
            `هنرجو: ${printName} - دوره: ${printCourse}`,
            `زمان امضا: ${faTime}`,
            `تطبیق شماره ${toPersianDigits(maskPhone(phone))} و کد ملی ${maskNational(national)}`
        ];
        const verifyLines = [
            `sig sha256 ${(await sha256Hex(base64)).slice(0, 24)}`,
            `orig sha256 ${row.pdf_sha256.slice(0, 24)}`,
            `ip ${ip.slice(0, 20)}`,
            `enrollment ${row.enrollment_id.slice(0, 8)}`
        ];

        // Academy seal + manager signature uploaded by the app (optional).
        const sealKey = (row as any).seal_key as string;
        const sigKey = (row as any).sig_key as string;
        const sealBytes = sealKey ? await readContractFile(env, sealKey) : null;
        const managerSigBytes = sigKey ? await readContractFile(env, sigKey) : null;
        const isPngBytes = (bytes: Uint8Array | null) =>
            !!bytes && bytes[0] === 0x89 && bytes[1] === 0x50;
        const asJpeg = (bytes: Uint8Array | null) =>
            bytes && bytes[0] === 0xff && bytes[1] === 0xd8 ? bytes : undefined;

        let signed: Uint8Array;
        try {
            signed = appendSignatureLayer(original, {
                signatureBytes: imageBytes,
                imageIsPng: isPng,
                imageWidth: dims.width,
                imageHeight: dims.height,
                sealPng: isPngBytes(sealBytes) ? sealBytes! : undefined,
                sealJpeg: asJpeg(sealBytes),
                sealWidth: Number((row as any).seal_width) || 0,
                sealHeight: Number((row as any).seal_height) || 0,
                managerSignaturePng: isPngBytes(managerSigBytes) ? managerSigBytes! : undefined,
                managerSignatureJpeg: asJpeg(managerSigBytes),
                managerSignatureWidth: Number((row as any).sig_width) || 0,
                managerSignatureHeight: Number((row as any).sig_height) || 0,
                lines: lines.map(toDisplayDigits),
                verifyLines
            });
        } catch (e: any) {
            await store.event(row.academy_id, row.id, { type: 'FAILED', detail: `pdf: ${e?.message || e}` });
            return api({ success: false, message: 'ترکیب امضا با PDF ناموفق بود.' }, 500);
        }

        const signedSha = await sha256Hex(new TextDecoder('latin1').decode(signed));
        const signedKey = `contracts/${row.academy_id}/${row.enrollment_id}/signed.pdf`;
        await writeContractFile(env, signedKey, signed, signedSha, row.academy_id);

        const shamsi = new Intl.DateTimeFormat('fa-IR', {
            dateStyle: 'full', timeStyle: 'short', timeZone: 'Asia/Tehran'
        }).format(new Date());
        await store.markSigned(row.academy_id, row, {
            signedPdfKey: signedKey,
            signedSha,
            signerName: row.student_name,
            signedAtShamsi: shamsi,
            ip,
            ua
        });
        await store.event(row.academy_id, row.id, { type: 'SIGNED', detail: `sha=${signedSha.slice(0, 16)}`, ip, userAgent: ua });

        return api({
            success: true,
            message: 'قرارداد با موفقیت امضا شد.',
            data: { signedAtShamsi: shamsi, status: 'SIGNED', pdfUrl: `/sign/${token}/pdf` }
        });
    }

    // ---- GET /sign/:token/pdf ----
    const pdfMatch = /^\/sign\/([A-Za-z0-9_-]{20,64})\/pdf$/.exec(url.pathname);
    if (pdfMatch) {
        const row = await loadByToken(env, pdfMatch[1]);
        if (!row) return errorPage('لینک معتبر نیست', 'این لینک منقضی یا نامعتبر است.', 404);
        const status = expireIfNeeded(row);
        if (status === 'REVOKED') return errorPage('لینک لغو شده است', 'برای دریافت نسخه جدید با آموزشگاه تماس بگیرید.', 410);
        const key = status === 'SIGNED' ? row.signed_pdf_key : row.pdf_key;
        const bytes = key ? await readContractFile(env, key) : null;
        if (!bytes) return errorPage('فایل در دسترس نیست', 'فایل قرارداد پیدا نشد.', 404);
        return new Response(bytes, {
            headers: {
                'Content-Type': 'application/pdf',
                'Content-Disposition': 'inline; filename="contract.pdf"',
                'Cache-Control': 'no-store'
            }
        });
    }

    // ---- GET /sign?s=... (page) ----
    if (url.pathname === '/sign') {
        const token = url.searchParams.get('s') || url.searchParams.get('token') || '';
        const row = await loadByToken(env, token);
        if (!row) return errorPage('لینک نامعتبر', 'این لینک اشتباه است یا منقضی شده. لطفاً پیام ارسال‌شده از آموزشگاه را باز کنید.', 404);
        const status = expireIfNeeded(row);
        if (status === 'SIGNED') {
            return page(200, CLOSED_MARKUP('ok', 'قرارداد امضا شده است',
                `این قرارداد در تاریخ ${row.signed_at_shamsi} امضا شده و قابل تغییر نیست. نسخه امضاشده در آموزشگاه موجود است.`));
        }
        if (status === 'REVOKED') {
            return page(410, CLOSED_MARKUP('err', 'لینک لغو شده است', 'این لینک توسط آموزشگاه لغو شده. لطفاً لینک جدید بخواهید.'));
        }
        if (status === 'EXPIRED') {
            return page(410, CLOSED_MARKUP('err', 'مهلت لینک تمام شده', 'برای دریافت لینک جدید با آموزشگاه تماس بگیرید.'));
        }
        // The token is injected as a JSON string literal inside the page script.
        const html = SIGN_PAGE_HTML.replace('__SIGN_TOKEN__', token.replace(/[^A-Za-z0-9_-]/g, ''));
        return page(200, html);
    }

    return errorPage('صفحه پیدا نشد', 'آدرس درخواستی وجود ندارد.', 404);
}

/** Persian text rendered for a canvas PDF is laid out LTR; show digits as-is. */
function toDisplayDigits(line: string): string {
    return line;
}

function imageSize(bytes: Uint8Array): { width: number; height: number } {
    // PNG
    if (bytes[0] === 0x89 && bytes[1] === 0x50) {
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        return { width: view.getUint32(16), height: view.getUint32(20) };
    }
    // JPEG
    let i = 2;
    while (i < bytes.length - 9) {
        if (bytes[i] !== 0xff) { i++; continue; }
        const marker = bytes[i + 1];
        const len = (bytes[i + 2] << 8) | bytes[i + 3];
        if (marker >= 0xc0 && marker <= 0xc3) {
            const h = (bytes[i + 5] << 8) | bytes[i + 6];
            const w = (bytes[i + 7] << 8) | bytes[i + 8];
            return { width: w, height: h };
        }
        i += 2 + len;
    }
    return { width: 600, height: 300 };
}

import SIGN_PAGE_TEMPLATE from '../templates/sign.html';
const SIGN_PAGE_HTML = SIGN_PAGE_TEMPLATE;
