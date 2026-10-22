// ============================================================
// Secure HTTPS REST API Handlers for Android App
// ============================================================

import {
    Env,
    ApiResponse,
    SendMessageRequest,
    ScheduleReminderRequest,
    SyncStudentRequest,
    InstallmentRecord,
    FinancialSummaryRecord
} from '../types';
import { TelegramBotClient, academyBotToken, academyBotUsername, academyWebhookSecret, allowedAcademies } from '../telegram';
import { AcademyDatabase } from '../db';
import { ContractStore } from '../contracts-store';
import { readContractFileForStudent } from './contracts-api';
import { timingSafeEqual, sha256Hex, randomToken } from '../crypto';

// JSON response helper
function jsonResponse<T>(data: ApiResponse<T>, status: number = 200): Response {
    return new Response(JSON.stringify(data), {
        status,
        headers: {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, Authorization'
        }
    });
}

/**
 * The academy's own bot token when it has one; the deployment secret otherwise.
 * Shared implementation lives in telegram.ts (also used by the signed-PDF
 * auto-delivery after signing).
 */
async function botTokenFor(env: Env, academyId: string): Promise<string> {
    return academyBotToken(env, academyId);
}

/**
 * Tenant for the legacy app API. Historical clients never sent X-Academy-Id
 * and every row they wrote carries the schema default 'default' (the D1
 * column default), so an ABSENT header must resolve to 'default' — the same
 * value AcademyDatabase always used. An explicit header is validated against
 * ACADEMY_IDS and refused when the tenant is not served here.
 */
function legacyAcademyFor(request: Request, env: Env): string | null {
    const header = (request.headers.get('X-Academy-Id') || '').trim();
    if (!header) return 'default';
    return allowedAcademies(env).includes(header) ? header : null;
}

// Authentication middleware
function authenticate(request: Request, env: Env): boolean {
    const authHeader = request.headers.get('Authorization') || '';
    if (!authHeader.startsWith('Bearer ')) return false;
    const token = authHeader.replace('Bearer ', '').trim();
    // Constant-time compare: the bearer key guards PDF delivery too.
    return Boolean(env.API_SECRET_KEY) && timingSafeEqual(token, env.API_SECRET_KEY.trim());
}

export async function handleApiRequest(
    request: Request,
    env: Env,
    url: URL
): Promise<Response> {
    const path = url.pathname;

    // Health check endpoint (Public or Auth). Anonymous callers only ever see
    // the bot USERNAME (public by design in t.me links) — never a token.
    if (path === '/api/status' || path === '/health') {
        let academyId = 'default';
        try { academyId = legacyAcademyFor(request, env) || 'default'; } catch (_) {}
        let academyBotUsernameValue = '';
        let academyBotConnected = false;
        if (env.DB) {
            try {
                const row = await env.DB.prepare('SELECT bot_username, bot_token FROM academies WHERE id = ?')
                    .bind(academyId).first<{ bot_username: string; bot_token: string }>();
                academyBotUsernameValue = (row?.bot_username || '').replace('@', '').trim();
                academyBotConnected = Boolean(row?.bot_token);
            } catch (_) {}
        }
        const token = await botTokenFor(env, academyId);
        let botInfo = null;
        if (token) {
            try {
                const me = await new TelegramBotClient(token).getMe();
                if (me.ok) botInfo = me.result;
            } catch (_) {}
        }
        return jsonResponse({
            success: true,
            message: 'Academy Telegram Cloudflare Backend is operational',
            data: {
                status: 'ONLINE',
                environment: env.ENVIRONMENT || 'production',
                isBotConfigured: Boolean(token),
                academyBotConnected,
                academyBotUsername: academyBotUsernameValue || botInfo?.username || '',
                botUsername: botInfo?.username || academyBotUsernameValue || env.BOT_USERNAME || '',
                botName: botInfo?.first_name || ''
            }
        });
    }

    // Require API Authentication for all other API operations
    if (!authenticate(request, env)) {
        return jsonResponse({
            success: false,
            message: 'Unauthorized: Invalid or missing API_SECRET_KEY bearer token',
            errorCode: 401
        }, 401);
    }

    // Tenant of every D1 row written below: the header the app sends, or the
    // historical 'default' when absent (see legacyAcademyFor).
    const academyId = legacyAcademyFor(request, env);
    if (!academyId) {
        return jsonResponse({
            success: false,
            message: 'شناسه آموزشگاه معتبر نیست.',
            errorCode: 403
        }, 403);
    }

    // Contract files are delivered by the academy's own bot (D1) or by the
    // deployment secret; only the routes that send text need a token up front.
    if (path === '/api/send-document') {
        return handleSendDocument(request, env, new AcademyDatabase(env, academyId));
    }

    // Per-academy bot token (D1 of THIS academy, deployment secret as legacy
    // fallback). Data-sync routes below never require it: students,
    // installments and reminders are stored regardless of the bot connection,
    // and the cron sends them later through the academy's own bot.
    const botToken = await botTokenFor(env, academyId);
    const bot = new TelegramBotClient(botToken || '');
    const db = new AcademyDatabase(env, academyId);

    // ============================================================
    // 1. Bot Connection & Webhook Management
    // ============================================================
    if (path === '/api/test-connection' && request.method === 'POST') {
        if (!botToken) {
            return jsonResponse({
                success: false,
                message: 'ربات تلگرام این آموزشگاه متصل نیست؛ ابتدا از بخش تنظیمات، توکن ربات را متصل کنید.',
                errorCode: 409
            }, 409);
        }
        try {
            const meRes = await bot.getMe();
            if (meRes.ok && meRes.result) {
                return jsonResponse({
                    success: true,
                    message: `اتصال به تلگرام با موفقیت برقرار شد!\nنام ربات: ${meRes.result.first_name}\nنام کاربری: @${meRes.result.username || ''}`,
                    data: meRes.result
                });
            } else {
                return jsonResponse({
                    success: false,
                    message: `خطای تلگرام: ${meRes.description || 'توکن نامعتبر است'}`,
                    errorCode: meRes.error_code || 400
                }, 400);
            }
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا در ارتباط با سرور تلگرام: ${err.message}`,
                errorCode: 502
            }, 502);
        }
    }

    if (path === '/api/set-webhook' && request.method === 'POST') {
        if (!botToken) {
            return jsonResponse({
                success: false,
                message: 'ربات تلگرام این آموزشگاه متصل نیست؛ ابتدا توکن ربات را متصل کنید.',
                errorCode: 409
            }, 409);
        }
        try {
            const body = await request.json() as { webhookUrl?: string };
            // One webhook URL per academy; the secret Telegram echoes back is
            // the academy's own (D1), not another tenant's.
            const webhookUrl = body.webhookUrl || `${url.origin}/webhook/${encodeURIComponent(academyId)}`;
            const secret = await academyWebhookSecret(env, academyId);
            const hookRes = await bot.setWebhook(webhookUrl, secret || undefined);
            if (hookRes.ok) {
                return jsonResponse({
                    success: true,
                    message: `وبهوک تلگرام با موفقیت روی آدرس ${webhookUrl} تنظیم شد.`,
                    data: { webhookUrl }
                });
            } else {
                return jsonResponse({
                    success: false,
                    message: `خطا در تنظیم وبهوک تلگرام: ${hookRes.description}`,
                    errorCode: hookRes.error_code || 400
                }, 400);
            }
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    // ============================================================
    // 2. Messaging Endpoints
    // ============================================================
    if (path === '/api/send-message' && request.method === 'POST') {
        if (!botToken) {
            return jsonResponse({
                success: false,
                message: 'ربات تلگرام این آموزشگاه متصل نیست؛ ابتدا توکن ربات را متصل کنید.',
                errorCode: 409
            }, 409);
        }
        try {
            const payload = await request.json() as SendMessageRequest;
            if (!payload.chatId || !payload.message) {
                return jsonResponse({
                    success: false,
                    message: 'پارامترهای chatId و message الزامی هستند.',
                    errorCode: 400
                }, 400);
            }

            const sendRes = await bot.sendMessage({
                chatId: payload.chatId,
                text: payload.message,
                parseMode: payload.parseMode || 'HTML',
                replyMarkup: payload.withMenu !== false ? TelegramBotClient.getStudentReplyKeyboard() : undefined
            });

            const logId = crypto.randomUUID();
            if (sendRes.ok) {
                await db.logMessage({
                    id: logId,
                    student_id: payload.studentId || '',
                    student_name: payload.studentName || '',
                    chat_id: payload.chatId,
                    message_type: 'DIRECT_MESSAGE',
                    content: payload.message,
                    status: 'SUCCESS',
                    error_message: '',
                    timestamp_epoch: Date.now(),
                    timestamp_shamsi: ''
                });

                return jsonResponse({
                    success: true,
                    message: 'پیام با موفقیت از طریق ربات تلگرام ارسال شد.',
                    data: { messageId: sendRes.result?.message_id, logId }
                });
            } else {
                await db.logMessage({
                    id: logId,
                    student_id: payload.studentId || '',
                    student_name: payload.studentName || '',
                    chat_id: payload.chatId,
                    message_type: 'DIRECT_MESSAGE',
                    content: payload.message,
                    status: 'FAILED',
                    error_message: sendRes.description || 'Unknown Telegram error',
                    timestamp_epoch: Date.now(),
                    timestamp_shamsi: ''
                });

                return jsonResponse({
                    success: false,
                    message: `خطای ارسال تلگرام: ${sendRes.description}`,
                    errorCode: sendRes.error_code || 400
                }, 400);
            }
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطای داخلی سرور: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    // ============================================================
    // 3. Reminders Management
    // ============================================================
    if (path === '/api/schedule-reminder' && request.method === 'POST') {
        try {
            const body = await request.json() as ScheduleReminderRequest;
            const reminderId = body.id || crypto.randomUUID();

            await db.addReminder({
                id: reminderId,
                student_id: body.studentId,
                student_name: body.studentName,
                chat_id: body.chatId,
                installment_id: body.installmentId || '',
                reminder_type: body.reminderType || 'REMINDER',
                message_text: body.message,
                scheduled_epoch: body.scheduledEpoch,
                scheduled_time_str: body.scheduledTimeStr || '',
                status: 'SCHEDULED',
                error_message: '',
                retry_count: 0,
                created_at_epoch: Date.now(),
                sent_at_epoch: 0
            });

            return jsonResponse({
                success: true,
                message: 'یادآوری با موفقیت در صف ارسال سرور ثبت شد.',
                data: { reminderId }
            });
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا در زمان‌بندی یادآوری: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    // Bulk upsert of reminders (used by the app to publish ALL upcoming
    // reminders to the server, so they are still sent while the app is closed)
    if (path === '/api/schedule-reminders' && request.method === 'POST') {
        try {
            const body = await request.json() as { reminders?: ScheduleReminderRequest[] };
            const list = Array.isArray(body.reminders) ? body.reminders : [];
            let upserted = 0;
            let skippedPast = 0;
            const now = Date.now();

            for (const item of list) {
                if (!item.scheduledEpoch || item.scheduledEpoch <= now - 60 * 60 * 1000) {
                    skippedPast++;
                    continue;
                }
                await db.addReminder({
                    id: item.id || crypto.randomUUID(),
                    student_id: item.studentId || '',
                    student_name: item.studentName || '',
                    chat_id: item.chatId || '',
                    installment_id: item.installmentId || '',
                    reminder_type: item.reminderType || 'INSTALLMENT',
                    message_text: item.message || '',
                    scheduled_epoch: item.scheduledEpoch,
                    scheduled_time_str: item.scheduledTimeStr || '',
                    status: 'SCHEDULED',
                    error_message: '',
                    retry_count: 0,
                    created_at_epoch: now,
                    sent_at_epoch: 0
                });
                upserted++;
            }

            return jsonResponse({
                success: true,
                message: `${upserted} یادآوری روی سرور ثبت شد${skippedPast ? ` (${skippedPast} مورد گذشته نادیده گرفته شد)` : ''}.`,
                data: { upserted, skippedPast }
            });
        } catch (err: any) {
            return jsonResponse({ success: false, message: `خطا: ${err.message}`, errorCode: 500 }, 500);
        }
    }

    if (path === '/api/cancel-reminders' && request.method === 'POST') {
        try {
            const body = await request.json() as { installmentId?: string };
            if (!body.installmentId) {
                return jsonResponse({ success: false, message: 'installmentId الزامی است.', errorCode: 400 }, 400);
            }
            await db.cancelPendingRemindersForInstallment(body.installmentId);
            return jsonResponse({ success: true, message: 'یادآوری‌های این قسط لغو شدند.' });
        } catch (err: any) {
            return jsonResponse({ success: false, message: `خطا: ${err.message}`, errorCode: 500 }, 500);
        }
    }

    // ============================================================
    // 4. Data Synchronization (Students, Installments, Financials)
    // ============================================================
    if (path === '/api/sync/student' && request.method === 'POST') {
        try {
            const body = await request.json() as SyncStudentRequest;
            // The server is the source of truth for the Telegram link. An empty
            // chat id must NOT wipe a connection made by the student in the bot.
            const stored = await db.upsertStudent({
                id: body.id,
                student_code: body.studentCode || '',
                full_name: body.fullName,
                phone: body.phone || '',
                national_code: body.nationalCode || '',
                telegram_chat_id: body.telegramChatId || '',
                telegram_username: body.telegramUsername || '',
                is_telegram_connected: body.isTelegramConnected ? 1 : 0,
                telegramLinkHash: body.telegramLinkHash || '',
                telegramLinkExpires: body.telegramLinkExpires || 0
            });
            if (!stored) {
                return jsonResponse({
                    success: false,
                    message: `ذخیرهٔ هنرجو ${body.fullName} در پایگاه‌داده ناموفق بود (Migration 0008 را بررسی کنید).`,
                    errorCode: 500
                }, 500);
            }

            const synced = await db.getStudentById(body.id, db.academyId);

            return jsonResponse({
                success: true,
                message: `اطلاعات هنرجو ${body.fullName} در سرور همگام‌سازی شد.`,
                data: {
                    telegramChatId: synced?.telegram_chat_id || '',
                    telegramUsername: synced?.telegram_username || '',
                    isTelegramConnected: synced?.is_telegram_connected === 1
                }
            });
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا در ذخیره اطلاعات هنرجو: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    if (path === '/api/sync/students-batch' && request.method === 'POST') {
        try {
            const body = await request.json() as { students: SyncStudentRequest[] };
            const list = Array.isArray(body.students) ? body.students : [];
            let failed = 0;
            let firstError = '';
            for (const s of list) {
                const ok = await db.upsertStudent({
                    id: s.id,
                    student_code: s.studentCode || '',
                    full_name: s.fullName,
                    phone: s.phone || '',
                    national_code: s.nationalCode || '',
                    telegram_chat_id: s.telegramChatId || '',
                    telegram_username: s.telegramUsername || '',
                    is_telegram_connected: s.isTelegramConnected ? 1 : 0,
                    telegramLinkHash: s.telegramLinkHash || '',
                    telegramLinkExpires: s.telegramLinkExpires || 0
                });
                if (!ok) {
                    failed++;
                    if (!firstError) firstError = s.id;
                }
            }
            if (failed > 0) {
                // The most common historical cause: migration 0008 not applied,
                // so the UNIQUE index on empty link hashes rejects the 2nd student.
                return jsonResponse({
                    success: false,
                    message: `${list.length - failed} از ${list.length} هنرجو ذخیره شد؛ ${failed} مورد ناموفق بود. ` +
                        `اگر بیش از یک هنرجو ذخیره نشد، اجرای Migration 0008 روی پایگاه‌داده لازم است (اولین شناسه ناموفق: ${firstError}).`,
                    errorCode: 500,
                    data: { stored: list.length - failed, failed }
                }, 500);
            }
            return jsonResponse({
                success: true,
                message: `${list.length} هنرجو با موفقیت همگام‌سازی شدند.`,
                data: { stored: list.length, failed: 0 }
            });
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا در همگام‌سازی دسته‌ای هنرجویان: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    if (path === '/api/sync/installments' && request.method === 'POST') {
        try {
            const body = await request.json() as { installments: InstallmentRecord[] };
            await db.upsertInstallments(body.installments || []);
            return jsonResponse({
                success: true,
                message: `${body.installments?.length || 0} قسط با موفقیت همگام‌سازی شدند.`
            });
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا در همگام‌سازی اقساط: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    if (path === '/api/sync/financials' && request.method === 'POST') {
        try {
            const body = await request.json() as { summaries: FinancialSummaryRecord[] };
            if (Array.isArray(body.summaries)) {
                for (const summary of body.summaries) {
                    await db.upsertFinancialSummary(summary);
                }
            }
            return jsonResponse({
                success: true,
                message: 'خلاصه وضعیت مالی با موفقیت همگام‌سازی شد.'
            });
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا در همگام‌سازی وضعیت مالی: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    if (path === '/api/sync/settings' && request.method === 'POST') {
        try {
            const body = await request.json() as Record<string, string>;
            for (const [k, v] of Object.entries(body)) {
                await db.setSetting(k, String(v));
            }
            return jsonResponse({
                success: true,
                message: 'تنظیمات آموزشگاه با موفقیت در سرور ذخیره شد.'
            });
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    // Returns every student link created in the bot, so the app can update Room
    if (path === '/api/sync/telegram-links' && (request.method === 'POST' || request.method === 'GET')) {
        try {
            const students = await db.getAllConnectedStudents();
            return jsonResponse({
                success: true,
                message: `${students.length} لینک تلگرام فعال`,
                data: {
                    links: students.map(s => ({
                        id: s.id,
                        telegramChatId: s.telegram_chat_id,
                        telegramUsername: s.telegram_username,
                        linkedAt: s.linked_at_epoch
                    }))
                }
            });
        } catch (err: any) {
            return jsonResponse({ success: false, message: `خطا: ${err.message}`, errorCode: 500 }, 500);
        }
    }

    if (path === '/api/sync/student-delete' && request.method === 'POST') {
        try {
            const body = await request.json() as { id?: string };
            if (!body.id) {
                return jsonResponse({ success: false, message: 'id الزامی است.', errorCode: 400 }, 400);
            }
            await db.deleteStudent(body.id);
            return jsonResponse({ success: true, message: 'هنرجو از سرور حذف شد.' });
        } catch (err: any) {
            return jsonResponse({ success: false, message: `خطا: ${err.message}`, errorCode: 500 }, 500);
        }
    }

    // ============================================================
    // 5. Deep Link Generation Endpoint (Stage 2: token-based, never raw ids)
    // ============================================================
    if (path === '/api/generate-link' && request.method === 'POST') {
        try {
            const body = await request.json() as { studentId: string; botUsername?: string };
            if (!body.studentId) {
                return jsonResponse({ success: false, message: 'studentId الزامی است.', errorCode: 400 }, 400);
            }
            const student = await db.getStudentById(body.studentId, db.academyId);
            if (!student) {
                return jsonResponse({ success: false, message: 'هنرجو در این آموزشگاه پیدا نشد.', errorCode: 404 }, 404);
            }
            const botUsername = await academyBotUsername(env, db.academyId, body.botUsername || '');
            if (!botUsername) {
                return jsonResponse({
                    success: false,
                    message: 'نام کاربری ربات تلگرام تنظیم نشده است؛ ابتدا ربات را متصل کنید.',
                    errorCode: 409
                }, 409);
            }
            // One-time token: the raw value only ever lives inside the returned
            // link; the server keeps the SHA-256 and burns it after first use.
            const token = randomToken(24);
            await db.setTelegramLinkHash(
                student.id,
                await sha256Hex(token),
                Date.now() + 30 * 86400000
            );
            const link = `https://t.me/${botUsername}?start=${token}`;

            return jsonResponse({
                success: true,
                message: 'لینک اختصاصی یک‌بارمصرف تلگرام تولید شد',
                data: { link, botUsername }
            });
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا در تولید لینک: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    // ============================================================
    // 6. Logs & Auditing
    // ============================================================
    if (path === '/api/logs' && request.method === 'GET') {
        try {
            const logs = await db.getRecentLogs(100);
            return jsonResponse({
                success: true,
                message: 'لیست لاگ‌های پیام دریافت شد',
                data: logs
            });
        } catch (err: any) {
            return jsonResponse({
                success: false,
                message: `خطا: ${err.message}`,
                errorCode: 500
            }, 500);
        }
    }

    return jsonResponse({
        success: false,
        message: `مسیر یافت نشد: ${path}`,
        errorCode: 404
    }, 404);
}

/**
 * Puts the SIGNED contract file of one student in that student's own chat.
 * Nothing public is returned: the file never leaves through a URL.
 */
async function handleSendDocument(request: Request, env: Env, db: AcademyDatabase): Promise<Response> {
    if (request.method !== 'POST') {
        return jsonResponse({ success: false, message: 'روش پشتیبانی نمی‌شود.', errorCode: 405 }, 405);
    }
    let body: any;
    try {
        body = await request.json();
    } catch {
        return jsonResponse({ success: false, message: 'بدنهٔ درخواست معتبر نیست.', errorCode: 400 }, 400);
    }
    const contractId = String(body.contractId || '').trim();
    const studentId = String(body.studentId || '').trim();
    const chatId = String(body.chatId || '').trim();
    if (!contractId || !studentId || !chatId) {
        return jsonResponse({ success: false, message: 'شناسه قرارداد، هنرجو و چت الزامی است.', errorCode: 400 }, 400);
    }
    const student = await db.getStudentById(studentId, db.academyId);
    if (!student) {
        return jsonResponse({ success: false, message: 'هنرجو در این آموزشگاه پیدا نشد.', errorCode: 404 }, 404);
    }
    // The chat must be the one THIS student linked in Telegram.
    if (!student.telegram_chat_id || student.telegram_chat_id !== chatId) {
        return jsonResponse({ success: false, message: 'این چت تلگرام به این هنرجو متصل نیست.', errorCode: 403 }, 403);
    }
    const botToken = await botTokenFor(env, db.academyId);
    if (!botToken) {
        return jsonResponse({ success: false, message: 'ربات تلگرام این آموزشگاه متصل نیست.', errorCode: 409 }, 409);
    }
    const bot = new TelegramBotClient(botToken);
    const store = new ContractStore(env);
    const row = await store.findById(db.academyId, contractId);
    if (!row || row.student_id !== studentId) {
        return jsonResponse({ success: false, message: 'قرارداد پیدا نشد.', errorCode: 404 }, 404);
    }
    if (row.status !== 'SIGNED') {
        return jsonResponse({ success: false, message: 'این قرارداد هنوز امضا نشده است.', errorCode: 409 }, 409);
    }
    const file = await readContractFileForStudent(
        env, db.academyId, contractId, studentId, row.signed_pdf_key, row.signed_pdf_sha256
    );
    if (!file.bytes) {
        return jsonResponse({ success: false, message: file.error, errorCode: file.status }, file.status);
    }
    const sent = await bot.sendDocument({
        chatId,
        fileName: `contract-${contractId.slice(0, 8)}.pdf`,
        bytes: file.bytes,
        caption: `📄 قرارداد امضاشدهٔ شما (${row.course_title || 'دوره آموزشی'})`
    });
    if (!sent.ok) {
        return jsonResponse({ success: false, message: 'ارسال فایل در تلگرام ناموفق بود: ' + (sent.description || ''), errorCode: 502 }, 502);
    }
    try {
        await store.event(db.academyId, row.id, { type: 'PDF_SENT', detail: 'telegram:api' });
    } catch (_) {}
    return jsonResponse({ success: true, message: 'فایل قرارداد در تلگرام ارسال شد.', data: { messageId: sent.result?.message_id } });
}
