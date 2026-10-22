// ============================================================
// Stage 4: /pay/<token> entry page + provider callback.
//
// Opening the link NEVER confirms a payment. The page either redirects
// to the connected provider's hosted checkout or explains the state.
// The callback only triggers a FRESH SERVER-SIDE verification of the
// gateway's own API — the only trust source (gateway.ts).
// ============================================================

import { Env } from '../types';
import { AcademyDatabase } from '../db';
import { resolvePayLink, verifyTransaction } from '../gateway';
import { TelegramBotClient } from '../telegram';
import { academyBotToken } from '../telegram';

function page(title: string, body: string, status = 200): Response {
    const html = `<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<style>
  body { font-family: -apple-system, "Vazirmatn", Tahoma, sans-serif; background: #f4f6f9;
         display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
  .card { background: #fff; border-radius: 16px; box-shadow: 0 4px 24px rgba(16,24,40,.08);
          padding: 32px 28px; max-width: 420px; width: calc(100% - 32px); text-align: center; }
  .icon { font-size: 44px; margin-bottom: 12px; }
  h1 { font-size: 18px; color: #14181f; margin: 0 0 12px; }
  p { font-size: 14px; color: #4a5563; line-height: 1.9; margin: 0 0 8px; }
  a.btn { display: inline-block; margin-top: 14px; padding: 10px 22px; border-radius: 10px;
          background: #0f766e; color: #fff; text-decoration: none; font-size: 14px; }
  @media print { body { background: #fff; } .card { box-shadow: none; } }
</style>
</head>
<body><div class="card"><div class="icon">${body.startsWith('__ok__') ? '✅' : body.startsWith('__fail__') ? '⚠️' : 'ℹ️'}</div>
<h1>${title}</h1>${body.replace(/^__(ok|fail)__/, '')}</div></body></html>`;
    return new Response(html, { status, headers: { 'Content-Type': 'text/html; charset=utf-8' } });
}

export async function handlePayRoute(request: Request, env: Env, url: URL): Promise<Response> {
    const path = url.pathname;

    // ---------- entry: /pay/<token> ----------
    if (request.method === 'GET' && path.startsWith('/pay/') && !path.startsWith('/pay/callback/')) {
        const token = decodeURIComponent(path.slice('/pay/'.length)).trim();
        const db = new AcademyDatabase(env, 'default');
        const view = await resolvePayLink(env, db, token);
        switch (view.kind) {
            case 'redirect':
                return new Response(null, { status: 302, headers: { Location: view.to } });
            case 'paid':
                return page('پرداخت انجام شده', '__ok__این قسط قبلاً با موفقیت پرداخت شده و رسید آن برای شما ارسال شده است. نیازی به پرداخت دوباره نیست.');
            case 'expired':
                return page('لینک منقضی شده', '__fail__اعتبار این لینک پرداخت به پایان رسیده است. برای دریافت لینک جدید با آموزشگاه تماس بگیرید یا دکمه «اقساط من» ربات را بزنید.', 410);
            case 'not-found':
                return page('لینک نامعتبر', '__fail__این لینک پرداخت معتبر نیست.', 404);
            case 'not-configured':
                return page('درگاه متصل نیست', view.message);
        }
    }

    // ---------- provider callback: /pay/callback/<provider>?orderId=... ----------
    // The provider redirects here after its own checkout. We NEVER trust the
    // redirect itself: a fresh server-to-server verification decides.
    if (path.startsWith('/pay/callback/')) {
        const orderId = (url.searchParams.get('orderId') || url.searchParams.get('order_id') || '').trim();
        if (!orderId) {
            return page('پارامتر نامعتبر', '__fail__شناسه سفارش یافت نشد.', 400);
        }
        const db = new AcademyDatabase(env, 'default');
        // The order id belongs to exactly one academy; resolve its tenant safely.
        const txn = await (env.DB as any)?.prepare('SELECT academy_id FROM payment_transactions WHERE id = ?').bind(orderId).first() as { academy_id: string } | null;
        if (!txn) return page('سفارش پیدا نشد', '__fail__این سفارش در سیستم ثبت نشده است.', 404);
        const dbA = new AcademyDatabase(env, txn.academy_id);
        const token = await academyBotToken(env, txn.academy_id);
        const bot = token ? new TelegramBotClient(token) : null;
        const res = await verifyTransaction(env, dbA, txn.academy_id, orderId, bot);
        if (res.state === 'PAID') {
            return page('پرداخت تأیید شد', `__ok__پرداخت شما با موفقیت تأیید شد و رسید آن در تلگرام ارسال گردید.<br><small>کد سفارش: ${orderId.slice(0, 8).toUpperCase()}</small>`);
        }
        if (res.state === 'PENDING') {
            return page('در حال بررسی', `ℹ️ پرداخت شما دریافت شده اما هنوز تأیید نهایی نشده است؛ نتیجه از طریق تلگرام اطلاع داده می‌شود.`);
        }
        return page('پرداخت ناموفق', `__fail__پرداخت تأیید نشده است (${res.state === 'EXPIRED' ? 'لینک منقضی شده' : res.state === 'CANCELED' ? 'لغو شده' : res.state === 'AMOUNT_MISMATCH' ? 'مبلغ مغایر است' : 'ناموفق'}). در صورت کسر مبلغ، حداکثر تا ۷۲ ساعت به‌صورت خودکار بازمی‌گردد.`);
    }

    return page('مسیر نامعتبر', '__fail__', 404);
}
