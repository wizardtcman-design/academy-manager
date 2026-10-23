// ============================================================
// Telegram Webhook Handler for Cloudflare Workers
// ============================================================

import { Env, TelegramUpdate, StudentRecord } from '../types';
import { TelegramBotClient } from '../telegram';
import { AcademyDatabase } from '../db';
import { ContractStore, ContractRow } from '../contracts-store';
import { readContractFile } from './contracts-api';
import { timingSafeEqual, sha256Hex } from '../crypto';
import {
    submitReceiptClaim, notifyManagersOfClaim, approveClaim, rejectClaim,
    isManager, CLAIM_PHOTO_MAX_BYTES
} from '../claims';
import { dueStatusFa } from '../reminders';
import { createInstallmentPaymentLink } from '../gateway';
import { getMessagingConfig, renderEvent } from '../messaging';

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

    // Verify Telegram Webhook Secret Token — REQUIRED, fail closed.
    // A webhook without a secret would accept forged updates from anyone,
    // so an academy (or the legacy global bot) with no secret configured is
    // refused (403) instead of silently trusting every caller.
    if (!webhookSecret) {
        return new Response('Webhook secret not configured', { status: 403 });
    }
    const receivedToken = request.headers.get('X-Telegram-Bot-Api-Secret-Token') || '';
    if (!timingSafeEqual(receivedToken, webhookSecret)) {
        return new Response('Unauthorized Webhook Source', { status: 401 });
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
            processUpdate(update, bot, db, env, academyId, url ? url.origin : ''),
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
    academyOfBot = dbAcademy(env),
    requestOrigin = ''
): Promise<void> {
    // ============================================================
    // 0. Stage 5: inline-button callbacks (manager approve/reject)
    //    Authorization is verified SERVER-SIDE from the clicker's chat id —
    //    the button being visible is never the permission check.
    // ============================================================
    if (update.callback_query) {
        await handleClaimCallback(update.callback_query, bot, db, env, academyOfBot);
        return;
    }

    const message = update.message || update.edited_message;
    if (!message || !message.chat) return;

    const chatId = message.chat.id.toString();
    const username = message.from?.username || message.chat.username || '';
    const firstName = message.from?.first_name || message.chat.first_name || 'هنرجو';
    const text = (message.text || message.caption || '').trim();

    // ============================================================
    // 0.b Stage 5: manager reply to a claim notification = rejection reason
    //    («رد: علت») — recorded on the claim, no payment state changes.
    // ============================================================
    if (message.reply_to_message && text) {
        const claim = await db.findPendingClaimByManagerMessage(chatId, message.reply_to_message.message_id, db.academyId);
        if (claim) {
            const reason = text.replace(/^رد\s*[:：]?\s*/u, '').trim();
            const out = await rejectClaim(env, db, db.academyId, claim.id, chatId, reason);
            if (out.state === 'REJECTED') {
                await bot.editMessageText(chatId, message.reply_to_message.message_id,
                    renderClaimSettled('REJECTED', claim.claimed_amount, reason));
                await bot.sendMessage({ chatId, text: `❌ رسید هنرجو ${claim.student_name} رد شد.${reason ? `\nعلت: ${reason}` : ''}\nپرداختی ثبت نشد و وضعیت قسط بدون تغییر ماند.`, parseMode: 'HTML' });
                await notifyStudentOfRejection(db, bot, claim.student_id, db.academyId, claim.installment_number, reason);
            } else if (out.state === 'FORBIDDEN') {
                await bot.sendMessage({ chatId, text: '⛔️ شما مجاز به بررسی رسیدهای این آموزشگاه نیستید.', parseMode: 'HTML' });
            } else if (out.state === 'ALREADY_DECIDED') {
                await bot.sendMessage({ chatId, text: 'ℹ️ این رسید قبلاً بررسی شده است (بدون تغییر دوباره).', parseMode: 'HTML' });
            }
            return;
        }
    }

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

                const welcomeRender = await (async () => {
                    // Stage 6: CONNECT_WELCOME is configurable per academy
                    // (default = the original text, byte for byte).
                    try {
                        const cfg = await getMessagingConfig(db);
                        if (!cfg.events.CONNECT_WELCOME.auto) return null; // auto sending OFF: skip
                        return renderEvent(cfg, 'CONNECT_WELCOME', {
                            studentName: tokenStudent.full_name,
                            codeLine: tokenStudent.student_code ? `🆔 <b>کد هنرجویی:</b> ${tokenStudent.student_code}\n` : ''
                        }, welcomeMsg).text;
                    } catch (_) { return welcomeMsg; }
                })();

                if (welcomeRender) {
                    await bot.sendMessage({
                        chatId,
                        text: welcomeRender,
                        parseMode: 'HTML',
                        replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
                    });
                }

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

    // ============================================================
    // 1.b Stage 5: a submitted RECEIPT PHOTO (evidence only — the manager
    //     decides; nothing becomes "paid" here).
    // ============================================================
    if (message.photo && message.photo.length > 0) {
        await handleReceiptPhoto(bot, db, env, academyOfBot, student, chatId, message.photo, text);
        return;
    }

    if (normalized === '💰 وضعیت مالی' || normalized.includes('وضعیت مالی') || normalized === '/finance') {
        await handleFinancialStatus(bot, db, student, chatId);
    } else if (normalized === '📅 اقساط من' || normalized.includes('اقساط من') || normalized.includes('اقساط') || normalized === '/installments') {
        await handleInstallments(bot, db, student, chatId, env, requestOrigin);
    } else if (normalized === '🧾 رسیدهای من' || normalized.includes('رسیدهای من') || normalized === '/receipts') {
        await handleReceipts(bot, db, student, chatId);
    } else if (normalized === '📄 قرارداد من' || normalized.includes('قرارداد من') || normalized === '/contract') {
        await handleContract(bot, env, student, chatId, academyOfBot);
    } else if (normalized === '📤 ارسال رسید پرداخت' || normalized.includes('ارسال رسید پرداخت') || normalized === '/send-receipt') {
        await handleSendReceiptGuide(bot, db, student, chatId);
    } else if (normalized === '📞 تماس با آموزشگاه' || normalized.includes('تماس با آموزشگاه') || normalized === '/contact') {
        await handleContactInfo(bot, db, chatId);
    } else if (normalized === '🔄 بروزرسانی اطلاعات' || normalized.includes('بروزرسانی') || normalized === '/refresh') {
        await handleRefresh(bot, db, student, chatId);
    } else if (normalized.startsWith('/') && (isAdmin(chatId, env) || (await isManager(db, env, academyOfBot, chatId)))) {
        // Admin commands (deployment ADMIN_CHAT_ID and this academy's manager list)
        await handleAdminCommand(normalized, bot, db, chatId, env, academyOfBot);
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

// ============================================================
// Stage 5: claim decision callbacks («ثبت و تأیید پرداخت» / «رد رسید»)
// ============================================================
async function handleClaimCallback(
    cq: NonNullable<TelegramUpdate['callback_query']>,
    bot: TelegramBotClient,
    db: AcademyDatabase,
    env: Env,
    academyId: string
): Promise<void> {
    const data = cq.data || '';
    const m = /^clm:(ok|no):([A-Za-z0-9-]+)$/.exec(data);
    if (!m) {
        await bot.answerCallbackQuery(cq.id);
        return;
    }
    const decision = m[1];
    const claimId = m[2];
    const managerChatId = String(cq.from?.id || '');

    if (decision === 'ok') {
        const out = await approveClaim(env, db, academyId, claimId, managerChatId, bot);
        switch (out.state) {
            case 'APPROVED': {
                await bot.answerCallbackQuery(cq.id, `✅ پرداخت ${out.amount.toLocaleString()} ثبت شد`);
                const claim = await db.getPaymentClaim(claimId, academyId);
                if (cq.message && claim) {
                    await bot.editMessageText(cq.message.chat.id.toString(), cq.message.message_id,
                        renderClaimSettled('APPROVED', claim.claimed_amount, ''));
                }
                await bot.sendMessage({
                    chatId: managerChatId,
                    text: `✅ پرداخت قسط هنرجو <b>${claim?.student_name || ''}</b> به مبلغ <b>${out.amount.toLocaleString()}</b> ثبت شد.\n` +
                        `🧾 رسید رسمی ${out.receiptSent ? 'برای هنرجو ارسال شد' : 'در صف ارسال قرار گرفت'} و یادآوری‌های این قسط متوقف شدند.\n` +
                        `📱 اپ آموزشگاه با همگام‌سازی بعدی این تغییر را نمایش می‌دهد.`,
                    parseMode: 'HTML'
                });
                break;
            }
            case 'FORBIDDEN':
                await bot.answerCallbackQuery(cq.id, '⛔️ شما مجاز به این عملیات نیستید.', true);
                break;
            case 'ALREADY_DECIDED':
                await bot.answerCallbackQuery(cq.id, 'ℹ️ این رسید قبلاً بررسی شده است (بدون ثبت تکراری).', true);
                break;
            case 'ALREADY_PAID':
                await bot.answerCallbackQuery(cq.id, `ℹ️ ${out.reason}`, true);
                break;
            default:
                await bot.answerCallbackQuery(cq.id, '❌ رسید پیدا نشد.', true);
        }
        return;
    }

    // reject — reason arrives later as a REPLY (optional); reject now with empty note
    const out = await rejectClaim(env, db, academyId, claimId, managerChatId, '');
    switch (out.state) {
        case 'REJECTED': {
            await bot.answerCallbackQuery(cq.id, '❌ رسید رد شد (پرداختی ثبت نشد).');
            const claim = await db.getPaymentClaim(claimId, academyId);
            if (cq.message && claim) {
                await bot.editMessageText(cq.message.chat.id.toString(), cq.message.message_id,
                    renderClaimSettled('REJECTED', claim.claimed_amount, ''));
            }
            await bot.sendMessage({
                chatId: managerChatId,
                text: `❌ رسید هنرجو <b>${claim?.student_name || ''}</b> رد شد.\nپرداختی ثبت نشد و وضعیت قسط بدون تغییر ماند.\nسابقهٔ رسید در گزارش‌ها حفظ می‌شود.`,
                parseMode: 'HTML'
            });
            break;
        }
        case 'FORBIDDEN':
            await bot.answerCallbackQuery(cq.id, '⛔️ شما مجاز به این عملیات نیستید.', true);
            break;
        case 'ALREADY_DECIDED':
            await bot.answerCallbackQuery(cq.id, 'ℹ️ این رسید قبلاً بررسی شده است.', true);
            break;
        default:
            await bot.answerCallbackQuery(cq.id, '❌ رسید پیدا نشد.', true);
    }
}

function renderClaimSettled(state: 'APPROVED' | 'REJECTED', amount: number, reason: string): string {
    if (state === 'APPROVED') {
        return `📥 رسید پرداخت (${amount.toLocaleString()})\n\n✅ <b>ثبت و تأیید شد</b> — پرداخت واقعی ثبت و رسید رسمی صادر گردید.`;
    }
    return `📥 رسید پرداخت (${amount.toLocaleString()})\n\n❌ <b>رد شد</b>${reason ? `\nعلت: ${reason}` : ''}\nپرداختی ثبت نشد.`;
}

async function notifyStudentOfRejection(
    db: AcademyDatabase, bot: TelegramBotClient,
    studentId: string, academyId: string, installmentNumber: number, reason: string
): Promise<void> {
    try {
        const student = await db.getStudentById(studentId, academyId);
        if (!student?.telegram_chat_id) return; // not connected: nothing to notify
        // Stage 6: RECEIPT_REJECTED is a configurable template (default = the
        // original wording); auto=false silences the notice, nothing is sent.
        const cfg = await getMessagingConfig(db);
        if (!cfg.events.RECEIPT_REJECTED.auto) return;
        const legacy = `❌ رسید ارسالی شما برای قسط ${installmentNumber} تأیید نشد.${reason ? `\nعلت: ${reason}` : ''}\nدر صورت نیاز با آموزشگاه تماس بگیرید یا رسید جدید ارسال کنید.`;
        const text = renderEvent(cfg, 'RECEIPT_REJECTED', {
            installmentNumber: String(installmentNumber),
            reasonLine: reason ? `\nعلت: ${reason}` : ''
        }, legacy).text;
        await bot.sendMessage({
            chatId: student.telegram_chat_id,
            text,
            parseMode: 'HTML',
            replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
        });
    } catch (_) { /* notification must never break the decision */ }
}

// ============================================================
// Stage 5: student receipt photo → claim (evidence only)
// ============================================================
async function handleReceiptPhoto(
    bot: TelegramBotClient,
    db: AcademyDatabase,
    env: Env,
    academyId: string,
    student: StudentRecord,
    chatId: string,
    photo: Array<{ file_id: string; file_size?: number }>,
    caption: string
): Promise<void> {
    const installments = (await db.getInstallmentsForStudent(student.id)) || [];
    const unpaid = installments.filter((i) => i.is_paid !== 1 && (i.remaining_amount || 0) > 0);
    if (unpaid.length === 0) {
        await bot.sendMessage({
            chatId,
            text: 'در حال حاضر قسط پرداخت‌نشده‌ای برای شما ثبت نشده است؛ نیازی به ارسال رسید نیست.',
            parseMode: 'HTML',
            replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
        });
        return;
    }

    // Which installment? The caption addresses it: «قسط ۲» / «قسط 2».
    const m = /قسط\s*([۰-۹0-9]+)/u.exec(caption || '');
    const target = m
        ? unpaid.find((i) => String(i.installment_number) === normalizeNum(m[1]))
        : (unpaid.length === 1 ? unpaid[0] : null);
    if (!target) {
        const list = unpaid.map((i) => `• قسط ${i.installment_number} — ${(i.remaining_amount || 0).toLocaleString()}`).join('\n');
        await bot.sendMessage({
            chatId,
            text: `📥 برای ارسال رسید، لطفاً تصویر رسید بانکی را همراه با شمارهٔ قسط بفرستید.\nمثال: «قسط ۲»\n\nاقساط پرداخت‌نشدهٔ شما:\n${list}`,
            parseMode: 'HTML',
            replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
        });
        return;
    }

    // Largest photo variant within the evidence budget (D1-storable).
    const pick = [...photo].reverse().find((p) => (p.file_size || 0) <= CLAIM_PHOTO_MAX_BYTES)
        || photo.find((p) => (p.file_size || 0) <= CLAIM_PHOTO_MAX_BYTES);
    if (!pick) {
        await bot.sendMessage({ chatId, text: '⚠️ حجم تصویر بیش از حد مجاز است. لطفاً تصویر کوچک‌تری بفرستید.', parseMode: 'HTML' });
        return;
    }
    const bytes = await bot.downloadFile(pick.file_id);

    const res = await submitReceiptClaim(db, academyId, student, target, bytes, pick.file_id, caption.replace(/قسط\s*[۰-۹0-9]+/u, '').trim());
    if (!res.ok) {
        await bot.sendMessage({ chatId, text: `ℹ️ ${res.reason}`, parseMode: 'HTML', replyMarkup: TelegramBotClient.getStudentReplyKeyboard() });
        return;
    }

    const claim = await db.getPaymentClaim(res.claimId, academyId);
    if (claim) await notifyManagersOfClaim(env, db, bot, academyId, claim, bytes);

    // Stage 6: CLAIM_ACK is a configurable template (default = the original
    // wording); auto=false silences the acknowledgement.
    try {
        const cfg = await getMessagingConfig(db);
        if (cfg.events.CLAIM_ACK.auto) {
            const ackLegacy = `✅ رسید شما برای <b>قسط ${target.installment_number}</b> دریافت شد و برای بررسی مدیریت ارسال گردید.\n\n⚠️ توجه: تا تأیید مدیریت، این قسط پرداخت‌شده محسوب نمی‌شود و رسید رسمی صادر نمی‌گردد. نتیجهٔ بررسی از همین ربات اطلاع‌رسانی می‌شود.`;
            const ackText = renderEvent(cfg, 'CLAIM_ACK', {
                installmentNumber: String(target.installment_number)
            }, ackLegacy).text;
            await bot.sendMessage({
                chatId,
                text: ackText,
                parseMode: 'HTML',
                replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
            });
        }
    } catch (_) { /* the claim itself is already stored; ack is best-effort */ }
}

function normalizeNum(s: string): string {
    let out = '';
    for (const ch of s) {
        const c = ch.codePointAt(0) || 0;
        if (c >= 0x06f0 && c <= 0x06f9) out += String.fromCharCode(c - 0x06f0 + 0x30);
        else out += ch;
    }
    return out;
}

// 🛠 Admin commands: /admin, /stats, /status, /broadcast, /claims, /paylog
async function handleAdminCommand(
    text: string,
    bot: TelegramBotClient,
    db: AcademyDatabase,
    chatId: string,
    env: Env,
    academyIdIn = dbAcademy(env)
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
                `/link کد ، شماره — وضعیت اتصال یک هنرجو\n` +
                `/claims — رسیدهای در انتظار بررسی\n` +
                `/paylog کد هنرجو — تاریخچهٔ ثبت/تأیید/رد/برگشت پرداخت`,
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

    if (cmd === '/claims') {
        // Stage 5: the manager's review queue — every pending receipt claim.
        const claims = await db.listPendingClaims(academyIdIn, 20);
        if (claims.length === 0) {
            await bot.sendMessage({ chatId, text: '✅ رسید در انتظار بررسی وجود ندارد.', parseMode: 'HTML' });
            return;
        }
        let body = `📥 <b>رسیدهای در انتظار بررسی (${claims.length})</b>\n\n`;
        claims.forEach((c, i) => {
            body += `${i + 1}. <b>${c.student_name}</b> — قسط ${c.installment_number} — ${c.claimed_amount.toLocaleString()}\n`;
            body += `   کد بررسی: <code>${c.id.slice(0, 8)}</code>\n`;
        });
        body += `\nتصویر رسید + دکمه‌های تأیید/رد برای هر مورد به‌صورت خودکار برای مدیران ارسال شده است.`;
        await bot.sendMessage({ chatId, text: body, parseMode: 'HTML' });
        return;
    }

    if (cmd === '/paylog') {
        // Stage 5: payment lifecycle history (register/approve/reject/reverse) —
        // financial facts only; tokens/keys are never logged anywhere.
        const code = rest.join(' ').trim();
        if (!code) {
            await bot.sendMessage({ chatId, text: '⚠️ کد هنرجو یا شناسه را وارد کنید: /paylog کد', parseMode: 'HTML' });
            return;
        }
        const student = await db.getStudentById(code, academyIdIn) || await db.getStudentByCode(code, academyIdIn);
        if (!student) {
            await bot.sendMessage({ chatId, text: '❌ هنرجو پیدا نشد.', parseMode: 'HTML' });
            return;
        }
        const events = await db.listPaymentEvents({ studentId: student.id, limit: 15 }, academyIdIn);
        if (events.length === 0) {
            await bot.sendMessage({ chatId, text: `📜 رویداد پرداختی برای ${student.full_name} ثبت نشده است.`, parseMode: 'HTML' });
            return;
        }
        const typeLabel: Record<string, string> = {
            RECEIPT_SUBMITTED: '📥 ارسال رسید',
            RECEIPT_APPROVED: '✅ تأیید رسید',
            RECEIPT_REJECTED: '❌ رد رسید',
            PAYMENT_REGISTERED: '💰 ثبت پرداخت',
            PAYMENT_REVERSED: '↩️ برگشت پرداخت',
            ONLINE_VERIFIED: '🌐 تأیید آنلاین',
            REMINDER_CANCELLED_PAID: '🔔 توقف یادآوری'
        };
        let body = `📜 <b>تاریخچهٔ پرداخت — ${student.full_name}</b>\n\n`;
        for (const e of events) {
            body += `${typeLabel[e.event_type] || e.event_type}${e.amount > 0 ? ` — ${e.amount.toLocaleString()}` : ''}\n`;
            if (e.detail) body += `   ${e.detail}\n`;
        }
        await bot.sendMessage({ chatId, text: body, parseMode: 'HTML' });
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

// 📅 Installments Handler — Stage 5 statuses (uniform with the app):
// paid / near-due / due-today / overdue / receipt-awaiting-review /
// receipt-rejected / failed-reminder / online-awaiting-confirmation,
// plus method-specific guidance (card-to-card card info, online link).
async function handleInstallments(bot: TelegramBotClient, db: AcademyDatabase, student: StudentRecord, chatId: string, env?: Env, requestOrigin = '') {
    const installments = await db.getInstallmentsForStudent(student.id);
    const currency = await db.getSetting('currency_unit', 'تومان');

    if (!installments || installments.length === 0) {
        const msg = `📅 <b>اقساط من</b>\n\nهنرجوی گرامی ${student.full_name}، در حال حاضر هیچ قسط فعالی در سامانه برای شما ثبت نشده است.`;
        await bot.sendMessage({ chatId, text: msg, parseMode: 'HTML', replyMarkup: TelegramBotClient.getStudentReplyKeyboard() });
        return;
    }

    const paidCount = installments.filter(i => i.is_paid === 1).length;
    const pendingCount = installments.length - paidCount;

    // Card/account details come from the academy's REAL settings only.
    const [cardNumber, sheba, accountOwner] = await Promise.all([
        db.getSetting('bank_card_number', ''),
        db.getSetting('bank_sheba', ''),
        db.getSetting('bank_account_owner', '')
    ]);

    let res = `📅 <b>جدول و وضعیت اقساط</b>\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `👤 <b>هنرجو:</b> ${student.full_name}\n` +
        `📊 کل اقساط: ${installments.length} (${paidCount} پرداخت‌شده | ${pendingCount} مانده)\n\n`;

    for (const [idx, inst] of installments.entries()) {
        const numStr = inst.total_installments > 0 ? `${inst.installment_number} از ${inst.total_installments}` : `${idx + 1}`;
        const title = inst.course_title ? ` (${inst.course_title})` : '';
        res += `🔹 <b>قسط شماره ${numStr}</b>${title}\n`;
        const shownAmount = (inst.remaining_amount || 0) > 0 ? inst.remaining_amount : inst.amount;
        res += `   💵 مبلغ: <b>${shownAmount.toLocaleString()} ${currency}</b>\n`;
        res += `   📆 سررسید: <b>${inst.due_date_shamsi}</b>\n`;

        if (inst.is_paid === 1) {
            res += `   وضعیت: <b>✅ پرداخت شده</b>\n`;
        } else {
            // Claim state first: a submitted receipt is NOT a payment.
            const [pendingClaims, rejectedClaims] = await Promise.all([
                db.listClaimsForInstallment(inst.id, ['PENDING'], db.academyId),
                db.listClaimsForInstallment(inst.id, ['REJECTED'], db.academyId)
            ]);
            const txn = await db.findActiveTxnForInstallment(db.academyId, inst.id, Date.now());
            const rows = await db.listRemindersForInstallment(inst.id, db.academyId);
            const latestFailed = rows.filter((r) => r.status === 'FAILED').sort((a, b) => b.created_at_epoch - a.created_at_epoch)[0];

            if (pendingClaims.length > 0) {
                res += `   وضعیت: <b>📨 رسید ارسالی — در انتظار بررسی مدیر</b>\n`;
                res += `   ⚠️ تا تأیید مدیر، این قسط پرداخت‌شده محسوب نمی‌شود.\n`;
            } else if (txn && txn.status === 'PENDING') {
                res += `   وضعیت: <b>🌐 پرداخت آنلاین در انتظار تأیید</b>\n`;
            } else if (rejectedClaims.length > 0) {
                const last = rejectedClaims[0];
                res += `   وضعیت: <b>❌ رسید ردشده</b>${last?.decision_note ? ` (علت: ${last.decision_note})` : ''}\n`;
                res += `   💡 می‌توانید رسید جدید ارسال کنید.\n`;
            } else {
                res += `   وضعیت: <b>${dueStatusFa(inst, Date.now())}</b>\n`;
            }
            if (latestFailed) {
                res += `   ⚠️ ارسال پیام یادآوری این قسط ناموفق بوده است؛ پس از اتصال مجدد تلاش می‌شود.\n`;
            }

            // Method guidance — the REAL academy account, never fabricated.
            const method = ((inst as any).payment_method || '').toUpperCase();
            if (method === 'TRANSFER' || method === 'BANK_DEPOSIT') {
                res += `   💳 روش: کارت‌به‌کارت\n`;
                if (cardNumber) res += `   🔢 شماره کارت: <code>${cardNumber}</code>\n`;
                if (sheba) res += `   🏦 شبا: <code>${sheba}</code>\n`;
                if (accountOwner) res += `   👤 به نام: ${accountOwner}\n`;
                res += `   📤 پس از واریز، تصویر رسید را با کپشن «قسط ${inst.installment_number}» از دکمهٔ «ارسال رسید پرداخت» بفرستید.\n`;
            } else if (method === 'POS') {
                res += `   🖥 روش: کارتخوان — در محل آموزشگاه پرداخت کنید.\n`;
            } else if (method === 'ONLINE' && !txn && env) {
                // The base URL must be REAL: a bare path is not a usable link
                // for the student. Prefer the configured public base, then the
                // request origin of this webhook call.
                const base = env.PUBLIC_BASE_URL || requestOrigin;
                if (base) {
                    const link = await createInstallmentPaymentLink(env, db, db.academyId, student.id, inst.id, base);
                    if (link.ok) {
                        res += `   💳 روش: آنلاین — لینک اختصاصی همین قسط:\n${link.url}\n`;
                    }
                } else {
                    console.warn('[Bot] PUBLIC_BASE_URL unset: online payment link omitted for the installment menu.');
                }
                // no gateway configured → no link, no "active" claim (honest default)
            }
        }
        res += `\n`;
    }

    res += `━━━━━━━━━━━━━━━━━━━\n💡 برای پرداخت کارت‌به‌کارت، مشخصات کارت آموزشگاه در همین بخش نمایش داده می‌شود و رسید را می‌توانید از دکمهٔ «ارسال رسید پرداخت» بفرستید.`;

    await bot.sendMessage({
        chatId,
        text: res,
        parseMode: 'HTML',
        replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
    });
}

// 📤 «ارسال رسید پرداخت» — card-to-card path: real account info + how to submit
async function handleSendReceiptGuide(bot: TelegramBotClient, db: AcademyDatabase, student: StudentRecord, chatId: string) {
    const installments = (await db.getInstallmentsForStudent(student.id)) || [];
    const unpaid = installments.filter((i) => i.is_paid !== 1 && (i.remaining_amount || 0) > 0);
    const currency = await db.getSetting('currency_unit', 'تومان');
    const [cardNumber, sheba, accountOwner] = await Promise.all([
        db.getSetting('bank_card_number', ''),
        db.getSetting('bank_sheba', ''),
        db.getSetting('bank_account_owner', '')
    ]);

    let res = `📤 <b>ارسال رسید پرداخت (کارت‌به‌کارت)</b>\n━━━━━━━━━━━━━━━━━━━\n`;
    if (cardNumber || sheba) {
        res += `💳 مشخصات پرداخت آموزشگاه:\n`;
        if (accountOwner) res += `👤 به نام: <b>${accountOwner}</b>\n`;
        if (cardNumber) res += `🔢 شماره کارت: <code>${cardNumber}</code>\n`;
        if (sheba) res += `🏦 شبا: <code>${sheba}</code>\n`;
    } else {
        res += `💳 شماره کارت آموزشگاه هنوز در تنظیمات ثبت نشده است؛ لطفاً با آموزشگاه هماهنگ کنید.\n`;
    }
    if (unpaid.length > 0) {
        res += `\nاقساط پرداخت‌نشدهٔ شما:\n`;
        for (const i of unpaid) {
            res += `• قسط ${i.installment_number} — <b>${(i.remaining_amount || 0).toLocaleString()} ${currency}</b> (سررسید ${i.due_date_shamsi})\n`;
        }
        res += `\n📥 پس از واریز، <b>تصویر رسید بانکی</b> را بفرستید و در توضیحات بنویسید «قسط <شماره>».\n`;
        res += `مثال: تصویر رسید + کپشن «قسط ۲»\n\n`;
        res += `⚠️ رسید شما مدرک بررسی است؛ پس از تأیید مدیریت، پرداخت ثبت و رسید رسمی برایتان ارسال می‌شود.`;
    } else {
        res += `\n✅ همهٔ اقساط شما پرداخت شده است. نیازی به ارسال رسید نیست.`;
    }
    await bot.sendMessage({ chatId, text: res, parseMode: 'HTML', replyMarkup: TelegramBotClient.getStudentReplyKeyboard() });
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
            // Stage 6: CONTRACT_DELIVERED template (default = the original
            // wording); auto=false sends no follow-up text (the PDF itself
            // is always delivered).
            const legacyFollowUp = `✅ این نسخهٔ نهایی و امضاشدهٔ قرارداد شماست.\nکد قرارداد: <code>${signed.id.slice(0, 8)}</code>`;
            let followUp = legacyFollowUp;
            try {
                const cfg = await getMessagingConfig(new AcademyDatabase(env, academyId));
                if (cfg.events.CONTRACT_DELIVERED.auto) {
                    followUp = renderEvent(cfg, 'CONTRACT_DELIVERED', { contractCode: signed.id.slice(0, 8) }, legacyFollowUp).text;
                } else {
                    followUp = '';
                }
            } catch (_) { /* keep the legacy text */ }
            if (followUp) {
                await bot.sendMessage({
                    chatId,
                    text: followUp,
                    parseMode: 'HTML',
                    replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
                });
            }
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
