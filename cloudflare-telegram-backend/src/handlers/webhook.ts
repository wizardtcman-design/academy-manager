// ============================================================
// Telegram Webhook Handler for Cloudflare Workers
// ============================================================

import { Env, TelegramUpdate, StudentRecord } from '../types';
import { TelegramBotClient } from '../telegram';
import { AcademyDatabase } from '../db';
import { ContractStore, ContractRow } from '../contracts-store';
import { readContractFile } from './contracts-api';
import { timingSafeEqual, sha256Hex } from '../crypto';

/** Telegram stops retrying an update after ~1 minute; finish sending before then. */
const SEND_TIMEOUT_MS = 25_000;

export async function handleTelegramWebhook(
    request: Request,
    env: Env,
    url?: URL
): Promise<Response> {
    if (request.method !== 'POST') {
        return new Response('Method Not Allowed', { status: 405 });
    }

    // One webhook URL per academy (`/webhook/<academyId>`); each academy has its
    // own bot token and its own webhook secret, so a bot can never be hijacked
    // by another tenant. The legacy `/webhook` keeps using the global secret.
    const pathAcademy = url?.pathname.startsWith('/webhook/')
        ? decodeURIComponent(url.pathname.slice('/webhook/'.length))
        : '';
    let botToken: string | undefined = env.TELEGRAM_BOT_TOKEN;
    let webhookSecret: string | undefined = env.WEBHOOK_SECRET;
    let academyId = 'default';
    const store = new ContractStore(env);

    if (pathAcademy) {
        const allowed = (env.ACADEMY_IDS || 'default').split(',').map((s) => s.trim());
        if (!allowed.includes(pathAcademy)) {
            return new Response('Unknown academy', { status: 404 });
        }
        academyId = pathAcademy;
        let row = null;
        try {
            row = await store.getAcademy(pathAcademy);
        } catch (_) {}
        if (!row?.bot_token) return new Response('Bot not connected', { status: 404 });
        botToken = row.bot_token;
        webhookSecret = row.webhook_secret || env.WEBHOOK_SECRET;
    }

    // Verify Telegram Webhook Secret Token if configured
    if (webhookSecret) {
        const receivedToken = request.headers.get('X-Telegram-Bot-Api-Secret-Token') || '';
        if (!timingSafeEqual(receivedToken, webhookSecret)) {
            return new Response('Unauthorized Webhook Source', { status: 401 });
        }
    }

    if (!botToken) {
        return new Response('Telegram Bot Token not configured on server', { status: 500 });
    }

    let update: TelegramUpdate;
    try {
        update = await request.json() as TelegramUpdate;
    } catch (_) {
        return new Response('Invalid JSON payload', { status: 400 });
    }

    const bot = new TelegramBotClient(botToken);
    const db = new AcademyDatabase(env, academyId);

    try {
        // Bounded processing: never let Telegram see a timeout and retry the update,
        // which would send the same reply twice.
        await Promise.race([
            processUpdate(update, bot, db, env, academyId),
            new Promise<void>((resolve) => setTimeout(resolve, SEND_TIMEOUT_MS))
        ]);
    } catch (err: any) {
        console.error('Error processing Telegram update:', err);
    }

    // Telegram requires 200 OK immediately
    return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
    });
}

function dbAcademy(env: Env): string {
    return (env.ACADEMY_IDS || 'default').split(',')[0].trim() || 'default';
}

async function processUpdate(
    update: TelegramUpdate,
    bot: TelegramBotClient,
    db: AcademyDatabase,
    env: Env,
    academyOfBot = dbAcademy(env)
): Promise<void> {
    const message = update.message || update.edited_message;
    if (!message || !message.chat) return;

    const chatId = message.chat.id.toString();
    const username = message.from?.username || message.chat.username || '';
    const firstName = message.from?.first_name || message.chat.first_name || 'هنرجو';
    const text = (message.text || message.caption || '').trim();

    // ============================================================
    // 1. Handle /start and Deep Links
    //    Stage 2 secure linking: the payload must be a ONE-TIME random token
    //    whose SHA-256 the server stores on the student row. A raw student id
    //    is never accepted — a forwarded/guessed id must not hand somebody
    //    else's financial data to another chat.
    // ============================================================
    if (text.startsWith('/start')) {
        const cleaned = text.replace('/start', '').trim();
        const startParam = cleaned.startsWith('=')
            ? cleaned.substring(1).trim()
            : cleaned.split(/\s+/)[0]?.trim() || '';

        if (!startParam) {
            // User clicked /start without deep link parameter
            const existingStudent = await db.getStudentByChatId(chatId);
            if (existingStudent) {
                const greeting = `سلام <b>${existingStudent.full_name}</b> عزیز! 👋\n\n` +
                    `به پنل اختصاصی هنرجو در ربات آموزشگاه خوش آمدید.\n` +
                    `از منوی زیر می‌توانید وضعیت مالی، اقساط و سوابق خود را مشاهده نمایید:`;
                await bot.sendMessage({
                    chatId,
                    text: greeting,
                    parseMode: 'HTML',
                    replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
                });
            } else {
                const academyName = await db.getSetting('academy_name', 'آموزشگاه کامپیوتر پیشگامان');
                const defaultMsg = `سلام ${firstName} عزیز! 👋\n\n` +
                    `به ربات هوشمند <b>${academyName}</b> خوش آمدید.\n\n` +
                    `جهت اتصال حساب کاربری و دریافت خودکار اعلان‌ها و اقساط، لطفاً از لینک اختصاصی ارسال‌شده توسط آموزشگاه وارد شوید.`;
                await bot.sendMessage({ chatId, text: defaultMsg, parseMode: 'HTML' });
            }
            return;
        }

        // ---- Secure token flow (Stage 2) ----
        if (/^[A-Za-z0-9_-]{20,64}$/.test(startParam)) {
            const linkHash = await sha256Hex(startParam);
            const tokenStudent = await db.getStudentByLinkHash(linkHash, db.academyId);
            if (tokenStudent) {
                const expires = Number((tokenStudent as any).telegram_link_expires || 0);
                if (expires > 0 && expires < Date.now()) {
                    await bot.sendMessage({
                        chatId,
                        text: '⏳ مهلت این لینک اتصال به پایان رسیده است. لطفاً از آموزشگاه لینک جدید بگیرید.',
                        parseMode: 'HTML'
                    });
                    await db.logMessage({
                        id: crypto.randomUUID(),
                        student_id: tokenStudent.id,
                        student_name: tokenStudent.full_name,
                        chat_id: chatId,
                        message_type: 'START_LINK_EXPIRED',
                        content: 'link token expired',
                        status: 'FAILED',
                        error_message: 'Telegram link token expired',
                        timestamp_epoch: Date.now(),
                        timestamp_shamsi: ''
                    });
                    return;
                }

                // Prevent linking same Telegram Chat ID to a different student
                const conflicting = await db.getStudentByChatId(chatId);
                if (conflicting && conflicting.id !== tokenStudent.id) {
                    const conflictMsg = `⚠️ این حساب تلگرام قبلاً به هنرجوی دیگری (<b>${conflicting.full_name}</b>) متصل شده است.\nجهت تغییر ابتدا باید اتصال قبلی در سیستم لغو گردد.`;
                    await bot.sendMessage({ chatId, text: conflictMsg, parseMode: 'HTML' });
                    return;
                }

                // Bind the chat, then burn the token: exactly one successful use.
                await db.linkTelegramChat(tokenStudent.id, chatId, username);
                await db.consumeTelegramLinkToken(tokenStudent.id);

                const welcomeMsg = `🎉 سلام <b>${tokenStudent.full_name}</b> عزیز!\n\n` +
                    `اتصال حساب تلگرام شما به سامانه هوشمند آموزشگاه با موفقیت برقرار شد. ✅\n\n` +
                    `👤 <b>نام هنرجو:</b> ${tokenStudent.full_name}\n` +
                    (tokenStudent.student_code ? `🆔 <b>کد هنرجویی:</b> ${tokenStudent.student_code}\n` : '') +
                    `\nاز منوی زیر می‌توانید وضعیت مالی، اقساط، سوابق پرداختی و قراردادهای آموزشی خود را مشاهده نمایید 👇`;

                await bot.sendMessage({
                    chatId,
                    text: welcomeMsg,
                    parseMode: 'HTML',
                    replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
                });

                await db.logMessage({
                    id: crypto.randomUUID(),
                    student_id: tokenStudent.id,
                    student_name: tokenStudent.full_name,
                    chat_id: chatId,
                    message_type: 'START_LINK_SUCCESS',
                    content: 'Connected via one-time secure link token',
                    status: 'SUCCESS',
                    error_message: '',
                    timestamp_epoch: Date.now(),
                    timestamp_shamsi: ''
                });
                return;
            }
        }

        // ---- Raw student ids are no longer accepted (spoofing guard) ----
        // The same neutral answer whether the id exists or not: no existence leak.
        await bot.sendMessage({
            chatId,
            text: '🔒 برای اتصال حساب، لطفاً از <b>لینک اختصاصی شخصی</b> استفاده کنید که آموزشگاه برای شما ارسال کرده است.\nهر لینک فقط یک بار و فقط برای هنرجوی خودش کار می‌کند.',
            parseMode: 'HTML'
        });
        await db.logMessage({
            id: crypto.randomUUID(),
            student_id: '',
            student_name: firstName,
            chat_id: chatId,
            message_type: 'START_LINK_REJECTED',
            content: 'legacy raw-id start refused',
            status: 'FAILED',
            error_message: 'Raw student id linking is disabled',
            timestamp_epoch: Date.now(),
            timestamp_shamsi: ''
        });
        return;
    }

    // ============================================================
    // 2. Handle Interactive Menu Buttons
    // ============================================================
    const student = await db.getStudentByChatId(chatId);
    if (!student) {
        const notLinked = `⚠️ حساب تلگرام شما هنوز به سیستم آموزشگاه متصل نشده است.\nلطفاً از طریق لینک اختصاصی ارسالی از آموزشگاه وارد شوید و دکمه Start را بزنید.`;
        await bot.sendMessage({ chatId, text: notLinked, parseMode: 'HTML' });
        return;
    }

    const normalized = text.trim();

    if (normalized === '💰 وضعیت مالی' || normalized.includes('وضعیت مالی') || normalized === '/finance') {
        await handleFinancialStatus(bot, db, student, chatId);
    } else if (normalized === '📅 اقساط من' || normalized.includes('اقساط من') || normalized.includes('اقساط') || normalized === '/installments') {
        await handleInstallments(bot, db, student, chatId);
    } else if (normalized === '🧾 رسیدهای من' || normalized.includes('رسیدهای من') || normalized === '/receipts') {
        await handleReceipts(bot, db, student, chatId);
    } else if (normalized === '📄 قرارداد من' || normalized.includes('قرارداد من') || normalized === '/contract') {
        await handleContract(bot, env, student, chatId, academyOfBot);
    } else if (normalized === '📞 تماس با آموزشگاه' || normalized.includes('تماس با آموزشگاه') || normalized === '/contact') {
        await handleContactInfo(bot, db, chatId);
    } else if (normalized === '🔄 بروزرسانی اطلاعات' || normalized.includes('بروزرسانی') || normalized === '/refresh') {
        await handleRefresh(bot, db, student, chatId);
    } else if (isAdmin(chatId, env)) {
        // Admin commands (only from the chat id configured in ADMIN_CHAT_ID)
        await handleAdminCommand(normalized, bot, db, chatId, env);
    } else {
        const menuMsg = `سلام <b>${student.full_name}</b> عزیز 🌸\n\nلطفاً یکی از گزینه‌های زیر را انتخاب فرمایید:`;
        await bot.sendMessage({
            chatId,
            text: menuMsg,
            parseMode: 'HTML',
            replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
        });
    }
}

function isAdmin(chatId: string, env: Env): boolean {
    const admin = (env.ADMIN_CHAT_ID || '').trim();
    return admin !== '' && admin === chatId;
}

// 🛠 Admin commands: /admin, /stats, /status, /broadcast <متن>
async function handleAdminCommand(
    text: string,
    bot: TelegramBotClient,
    db: AcademyDatabase,
    chatId: string,
    env: Env
): Promise<void> {
    const [command, ...rest] = text.split(/\s+/);
    const cmd = command.toLowerCase();

    if (cmd === '/admin' || cmd === '/help') {
        await bot.sendMessage({
            chatId,
            text: `🛠 <b>دستورات مدیر</b>\n` +
                `━━━━━━━━━━━━━━━━━━━\n` +
                `/stats — آمار کلی ربات\n` +
                `/status — وضعیت سرور و اتصال تلگرام\n` +
                `/broadcast متن — ارسال پیام به همهٔ هنرجویان متصل\n` +
                `/link کد ، شماره — وضعیت اتصال یک هنرجو`,
            parseMode: 'HTML'
        });
        return;
    }

    if (cmd === '/status') {
        const me = await bot.getMe();
        await bot.sendMessage({
            chatId,
            text: `🟢 <b>وضعیت سرور</b>\n` +
                `━━━━━━━━━━━━━━━━━━━\n` +
                `🤖 ربات: ${me.ok ? '@' + (me.result?.username || '') : '❌ خطا در ارتباط'}\n` +
                `🗄 پایگاه‌داده D1: ${db.isD1Configured() ? '✅ متصل' : '⚠️ متصل نیست (حافظه موقت)'}\n` +
                `⚡ KV: ${db.isKVConfigured() ? '✅ متصل' : '➖ غیرفعال'}\n` +
                `🌐 محیط: ${env.ENVIRONMENT || 'production'}\n` +
                `🕒 زمان سرور (UTC): ${new Date().toISOString()}`,
            parseMode: 'HTML'
        });
        return;
    }

    if (cmd === '/stats') {
        const logs = await db.getRecentLogs(200);
        const todayStart = new Date();
        todayStart.setUTCHours(0, 0, 0, 0);
        const sentToday = logs.filter(l => l.status === 'SUCCESS' && l.timestamp_epoch >= todayStart.getTime()).length;
        const failedToday = logs.filter(l => l.status === 'FAILED' && l.timestamp_epoch >= todayStart.getTime()).length;

        await bot.sendMessage({
            chatId,
            text: `📊 <b>آمار ربات</b>\n` +
                `━━━━━━━━━━━━━━━━━━━\n` +
                `✅ پیام موفق امروز: <b>${sentToday}</b>\n` +
                `❌ پیام ناموفق امروز: <b>${failedToday}</b>\n` +
                `📜 مجموع لاگ‌های اخیر: ${logs.length}`,
            parseMode: 'HTML'
        });
        return;
    }

    if (cmd === '/broadcast') {
        const body = rest.join(' ').trim();
        if (!body) {
            await bot.sendMessage({ chatId, text: '⚠️ متن پیام را بعد از /broadcast بنویسید.', parseMode: 'HTML' });
            return;
        }
        // Broadcast is intentionally limited to students already listed in the DB.
        const students = await db.getAllConnectedStudents();
        let sent = 0;
        for (const s of students) {
            const res = await bot.sendMessage({ chatId: s.telegram_chat_id, text: body, parseMode: 'HTML' });
            if (res.ok) sent++;
        }
        await bot.sendMessage({
            chatId,
            text: `📣 پیام به ${sent} از ${students.length} هنرجوی متصل ارسال شد.`,
            parseMode: 'HTML'
        });
        return;
    }

    if (cmd === '/link') {
        const code = rest.join(' ').trim();
        if (!code) {
            await bot.sendMessage({ chatId, text: '⚠️ کد هنرجو یا شناسه را وارد کنید.', parseMode: 'HTML' });
            return;
        }
        const student = await db.getStudentById(code) || await db.getStudentByCode(code);
        if (!student) {
            await bot.sendMessage({ chatId, text: '❌ هنرجو پیدا نشد.', parseMode: 'HTML' });
            return;
        }
        await bot.sendMessage({
            chatId,
            text: `👤 <b>${student.full_name}</b>\n` +
                `کد: ${student.student_code || '-'}\n` +
                `اتصال تلگرام: ${student.is_telegram_connected === 1 ? `✅ متصل (${student.telegram_chat_id})` : '➖ متصل نیست'}`,
            parseMode: 'HTML'
        });
        return;
    }

    await bot.sendMessage({ chatId, text: 'دستور ناشناخته. /help را بزنید.', parseMode: 'HTML' });
}

// 💰 Financial Status Handler
async function handleFinancialStatus(bot: TelegramBotClient, db: AcademyDatabase, student: StudentRecord, chatId: string) {
    const summary = await db.getFinancialSummary(student.id);
    const currency = await db.getSetting('currency_unit', 'تومان');

    const totalTuition = summary?.total_tuition || 0;
    const totalPaid = summary?.total_paid || 0;
    const remaining = summary?.remaining_debt || 0;

    let statusText = '⏳ دارای اقساط جاری در حال پرداخت';
    if (remaining === 0 && totalTuition > 0) {
        statusText = '✅ تسویه کامل شده (بدون بدهی)';
    } else if (totalTuition === 0) {
        statusText = 'ثبت‌نام جاری یا اطلاعات مالی در حال بررسی است';
    }

    const text = `📊 <b>وضعیت مالی و شهریه</b>\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `👤 <b>هنرجو:</b> ${student.full_name}\n` +
        (student.student_code ? `🆔 <b>کد هنرجویی:</b> ${student.student_code}\n` : '') +
        (summary?.courses_summary ? `📚 <b>دوره:</b> ${summary.courses_summary}\n` : '') +
        `\n` +
        `💳 <b>خلاصه مالی:</b>\n` +
        `• کل شهریه: <b>${totalTuition.toLocaleString()} ${currency}</b>\n` +
        `• پرداختی تا کنون: <b>${totalPaid.toLocaleString()} ${currency}</b>\n` +
        `• مانده بدهی: <b>${remaining.toLocaleString()} ${currency}</b>\n` +
        `• وضعیت: <b>${statusText}</b>\n` +
        `━━━━━━━━━━━━━━━━━━━`;

    await bot.sendMessage({
        chatId,
        text,
        parseMode: 'HTML',
        replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
    });
}

// 📅 Installments Handler
async function handleInstallments(bot: TelegramBotClient, db: AcademyDatabase, student: StudentRecord, chatId: string) {
    const installments = await db.getInstallmentsForStudent(student.id);
    const currency = await db.getSetting('currency_unit', 'تومان');

    if (!installments || installments.length === 0) {
        const msg = `📅 <b>اقساط من</b>\n\nهنرجوی گرامی ${student.full_name}، در حال حاضر هیچ قسط فعالی در سامانه برای شما ثبت نشده است.`;
        await bot.sendMessage({ chatId, text: msg, parseMode: 'HTML', replyMarkup: TelegramBotClient.getStudentReplyKeyboard() });
        return;
    }

    const paidCount = installments.filter(i => i.is_paid === 1).length;
    const pendingCount = installments.length - paidCount;

    let res = `📅 <b>جدول و وضعیت اقساط</b>\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `👤 <b>هنرجو:</b> ${student.full_name}\n` +
        `📊 کل اقساط: ${installments.length} (${paidCount} پرداخت‌شده | ${pendingCount} مانده)\n\n`;

    installments.forEach((inst, idx) => {
        const numStr = inst.total_installments > 0 ? `${inst.installment_number} از ${inst.total_installments}` : `${idx + 1}`;
        const title = inst.course_title ? ` (${inst.course_title})` : '';
        res += `🔹 <b>قسط شماره ${numStr}</b>${title}\n`;
        res += `   💵 مبلغ: <b>${inst.amount.toLocaleString()} ${currency}</b>\n`;
        res += `   📆 سررسید: <b>${inst.due_date_shamsi}</b>\n`;

        if (inst.is_paid === 1) {
            res += `   وضعیت: <b>✅ پرداخت شده</b>\n`;
        } else if (inst.due_date_epoch > 0 && inst.due_date_epoch < Date.now()) {
            const daysOverdue = Math.max(1, Math.floor((Date.now() - inst.due_date_epoch) / (1000 * 60 * 60 * 24)));
            res += `   وضعیت: <b>🚨 سررسید گذشته (${daysOverdue} روز تاخیر)</b>\n`;
        } else {
            res += `   وضعیت: <b>⏳ در انتظار پرداخت</b>\n`;
        }
        res += `\n`;
    });

    res += `━━━━━━━━━━━━━━━━━━━\n💡 جهت پرداخت اقساط یا دریافت شماره شبا/کارت با آموزشگاه در تماس باشید.`;

    await bot.sendMessage({
        chatId,
        text: res,
        parseMode: 'HTML',
        replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
    });
}

// 🧾 Receipts Handler — itemized from the academy's own pushed installment data.
// Every installment marked paid IS a receipt (amount + paid date + course);
// it is read from D1 with the student's own id only, never another student's.
async function handleReceipts(bot: TelegramBotClient, db: AcademyDatabase, student: StudentRecord, chatId: string) {
    const [summary, installments, currency] = await Promise.all([
        db.getFinancialSummary(student.id),
        db.getInstallmentsForStudent(student.id),
        db.getSetting('currency_unit', 'تومان')
    ]);
    const totalPaid = summary?.total_paid || 0;

    const paid = (installments || []).filter((i) => i.is_paid === 1);

    let res = `🧾 <b>رسیدهای پرداخت</b>\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `👤 <b>هنرجو:</b> ${student.full_name}\n` +
        `💰 کل مبالغ واریز شده: <b>${totalPaid.toLocaleString()} ${currency}</b>\n`;

    if (paid.length > 0) {
        res += `\n<b>پرداخت‌های ثبت‌شده (${paid.length} مورد):</b>\n`;
        paid.forEach((inst, idx) => {
            const numStr = inst.total_installments > 0 ? `قسط ${inst.installment_number} از ${inst.total_installments}` : `قسط ${idx + 1}`;
            const paidAmount = inst.paid_amount > 0 ? inst.paid_amount : inst.amount;
            const course = inst.course_title ? ` — ${inst.course_title}` : '';
            res += `\n${idx + 1}. ✅ <b>${numStr}</b>${course}\n`;
            res += `   💵 مبلغ: <b>${paidAmount.toLocaleString()} ${currency}</b>\n`;
            if (inst.paid_date_shamsi) res += `   📆 تاریخ پرداخت: <b>${inst.paid_date_shamsi}</b>\n`;
        });
    } else {
        res += `\nهنوز پرداختی در سامانه برای شما ثبت نشده است.\n`;
    }

    res += `\n━━━━━━━━━━━━━━━━━━━\n` +
        `📄 رسیدهای تفصیلی و فاکتورهای هر تراکنش در اپلیکیشن آموزشگاه نگهداری می‌شود و با همگام‌سازی آموزشگاه اینجا به‌روز می‌گردد.`;

    await bot.sendMessage({
        chatId,
        text: res,
        parseMode: 'HTML',
        replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
    });

    // Stage 4: the PRINTABLE version of the same receipts — the very same
    // rows, rendered as PDF — is delivered here so the student can view or
    // print them. Only the student's OWN receipts are ever attached.
    try {
        const receiptsMod = await import('../receipts');
        const rows = await receiptsMod.getReceiptsForStudent(db, db.academyId, student.id);
        const printable = rows.filter((r) => !r.is_reversed).slice(0, 5);
        if (printable.length > 0) {
            const academyName = await db.getSetting('academy_name', 'آموزشگاه');
            const academyPhone = await db.getSetting('phone', '');
            const currency = await db.getSetting('currency_unit', 'تومان');
            for (const r of printable) {
                const pdf = receiptsMod.composeReceiptPdfFor(r, academyName, academyPhone, currency);
                await bot.sendDocument({
                    chatId,
                    fileName: `receipt-${(r.transaction_id || r.id).slice(0, 12)}.pdf`,
                    bytes: pdf,
                    caption: `🧾 رسید ${r.transaction_id || r.id.slice(0, 8)} — ${r.amount.toLocaleString()} ${currency}`
                });
            }
        }
    } catch (e) {
        console.error('receipt PDF attach failed:', e);
    }
}

// 📄 Contract Handler
async function handleContract(bot: TelegramBotClient, env: Env, student: StudentRecord, chatId: string, academyIdIn: string) {
    // The academy of the bot that received the message: never another tenant's rows.
    let academyId = academyIdIn;
    if (!academyId || (env.ACADEMY_IDS || 'default').split(',').map((s) => s.trim()).indexOf(academyId) < 0) {
        academyId = 'default';
    }
    const store = new ContractStore(env);
    let rows: ContractRow[] = [];
    try {
        rows = await store.findByStudent(academyId, student.id);
    } catch {
        rows = [];
    }
    const signed = rows.find((r) => r.status === 'SIGNED');
    if (signed) {
        const bytes = await readContractFile(env, signed.signed_pdf_key);
        if (bytes) {
            await bot.sendDocument({
                chatId,
                fileName: `contract-${signed.id.slice(0, 8)}.pdf`,
                bytes,
                caption: `📄 قرارداد امضاشدهٔ شما (${signed.course_title || 'دوره آموزشی'})`
            });
            // Forensics: the student asked for the file again from the bot menu.
            try {
                await store.event(academyId, signed.id, { type: 'PDF_SENT', detail: 'telegram:bot-menu' });
            } catch (_) {}
            await bot.sendMessage({
                chatId,
                text: `✅ این نسخهٔ نهایی و امضاشدهٔ قرارداد شماست.\nکد قرارداد: <code>${signed.id.slice(0, 8)}</code>`,
                parseMode: 'HTML',
                replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
            });
            return;
        }
    }
    const open = rows.find((r) => r.status === 'ISSUED' || r.status === 'OPENED');
    if (open) {
        await bot.sendMessage({
            chatId,
            text: `📝 قرارداد شما هنوز امضا نشده است.\n` +
                `لینک امضای ارسال‌شده توسط آموزشگاه را باز کنید، شماره و کد ملی را وارد کنید و با انگشت امضا کنید.\n` +
                `بعد از امضا، نسخهٔ نهایی خودکار همین‌جا ارسال می‌شود.`,
            parseMode: 'HTML',
            replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
        });
        return;
    }
    const res = `📄 <b>قرارداد آموزشی</b>\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `👤 <b>هنرجو:</b> ${student.full_name}\n\n` +
        `✅ قرارداد رسمی شما در سیستم آموزشگاه ثبت گردیده است. جهت دریافت نسخه چاپی به دفتر آموزشگاه مراجعه فرمایید.`;
    await bot.sendMessage({ chatId, text: res, parseMode: 'HTML', replyMarkup: TelegramBotClient.getStudentReplyKeyboard() });
}

// 📞 Contact Info Handler
async function handleContactInfo(bot: TelegramBotClient, db: AcademyDatabase, chatId: string) {
    const academyName = await db.getSetting('academy_name', 'آموزشگاه کامپیوتر پیشگامان');
    const phone = await db.getSetting('phone', '۰۲۱-۸۸۸۸۹۹۹۹');
    const address = await db.getSetting('address', 'تهران، میدان انقلاب، خیابان کارگر شمالی، پلاک ۱۲');

    const res = `🏢 <b>${academyName}</b>\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `📞 <b>تلفن تماس:</b> <code>${phone}</code>\n` +
        `📍 <b>نشانی:</b>\n${address}\n\n` +
        `⏰ <b>ساعات کاری:</b> همه‌روزه از ۹:۰۰ الی ۲۰:۳۰\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `🌸 با افتخار پاسخگوی سوالات شما هنرجویان گرامی هستیم.`;

    await bot.sendMessage({
        chatId,
        text: res,
        parseMode: 'HTML',
        replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
    });
}

// 🔄 Refresh Handler — re-reads the student's OWN rows (identity, summary and
// installments) as last synced by the academy; nothing from another chat or
// tenant can ever appear here.
async function handleRefresh(bot: TelegramBotClient, db: AcademyDatabase, student: StudentRecord, chatId: string) {
    const [refreshed, summary, installments, currency] = await Promise.all([
        db.getStudentById(student.id, db.academyId),
        db.getFinancialSummary(student.id),
        db.getInstallmentsForStudent(student.id),
        db.getSetting('currency_unit', 'تومان')
    ]);
    const s = refreshed || student;
    const paidCount = (installments || []).filter((i) => i.is_paid === 1).length;
    const pendingCount = (installments || []).length - paidCount;

    const res = `🔄 <b>اطلاعات حساب کاربری شما به‌روزرسانی شد</b> ✅\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `👤 <b>نام:</b> ${s.full_name}\n` +
        (s.student_code ? `🆔 <b>کد هنرجویی:</b> ${s.student_code}\n` : '') +
        (s.phone ? `📱 <b>تلفن:</b> ${s.phone}\n` : '') +
        `💰 <b>مانده بدهی:</b> ${(summary?.remaining_debt || 0).toLocaleString()} ${currency}\n` +
        `📅 <b>اقساط:</b> ${installments.length} ثبت‌شده (${paidCount} پرداخت‌شده | ${pendingCount} مانده)\n` +
        `🟢 <b>وضعیت اتصال:</b> متصل و فعال\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `💡 اطلاعات مالی شما هر بار که آموزشگاه سامانه را همگام‌سازی کند، خودکار به‌روز می‌شود.`;

    await bot.sendMessage({
        chatId,
        text: res,
        parseMode: 'HTML',
        replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
    });
}
