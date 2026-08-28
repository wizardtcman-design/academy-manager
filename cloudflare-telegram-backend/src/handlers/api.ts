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
import { TelegramBotClient } from '../telegram';
import { AcademyDatabase } from '../db';

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

// Authentication middleware
function authenticate(request: Request, env: Env): boolean {
    const authHeader = request.headers.get('Authorization') || '';
    if (!authHeader.startsWith('Bearer ')) return false;
    const token = authHeader.replace('Bearer ', '').trim();
    return Boolean(env.API_SECRET_KEY && token === env.API_SECRET_KEY.trim());
}

export async function handleApiRequest(
    request: Request,
    env: Env,
    url: URL
): Promise<Response> {
    const path = url.pathname;

    // Health check endpoint (Public or Auth)
    if (path === '/api/status' || path === '/health') {
        const bot = new TelegramBotClient(env.TELEGRAM_BOT_TOKEN || '');
        let botInfo = null;
        if (env.TELEGRAM_BOT_TOKEN) {
            try {
                const me = await bot.getMe();
                if (me.ok) botInfo = me.result;
            } catch (_) {}
        }
        return jsonResponse({
            success: true,
            message: 'Academy Telegram Cloudflare Backend is operational',
            data: {
                status: 'ONLINE',
                environment: env.ENVIRONMENT || 'production',
                isBotConfigured: Boolean(env.TELEGRAM_BOT_TOKEN),
                botUsername: botInfo?.username || env.BOT_USERNAME || '',
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

    const botToken = env.TELEGRAM_BOT_TOKEN;
    if (!botToken) {
        return jsonResponse({
            success: false,
            message: 'Server Error: TELEGRAM_BOT_TOKEN secret is not configured on Cloudflare Worker',
            errorCode: 500
        }, 500);
    }

    const bot = new TelegramBotClient(botToken);
    const db = new AcademyDatabase(env);

    // ============================================================
    // 1. Bot Connection & Webhook Management
    // ============================================================
    if (path === '/api/test-connection' && request.method === 'POST') {
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
        try {
            const body = await request.json() as { webhookUrl?: string };
            const webhookUrl = body.webhookUrl || `${url.origin}/webhook`;
            const hookRes = await bot.setWebhook(webhookUrl, env.WEBHOOK_SECRET);
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

    // ============================================================
    // 4. Data Synchronization (Students, Installments, Financials)
    // ============================================================
    if (path === '/api/sync/student' && request.method === 'POST') {
        try {
            const body = await request.json() as SyncStudentRequest;
            await db.upsertStudent({
                id: body.id,
                student_code: body.studentCode || '',
                full_name: body.fullName,
                phone: body.phone || '',
                national_code: body.nationalCode || '',
                telegram_chat_id: body.telegramChatId || '',
                telegram_username: body.telegramUsername || '',
                is_telegram_connected: body.isTelegramConnected ? 1 : 0
            });

            return jsonResponse({
                success: true,
                message: `اطلاعات هنرجو ${body.fullName} در سرور همگام‌سازی شد.`
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
            if (Array.isArray(body.students)) {
                for (const s of body.students) {
                    await db.upsertStudent({
                        id: s.id,
                        student_code: s.studentCode || '',
                        full_name: s.fullName,
                        phone: s.phone || '',
                        national_code: s.nationalCode || '',
                        telegram_chat_id: s.telegramChatId || '',
                        telegram_username: s.telegramUsername || '',
                        is_telegram_connected: s.isTelegramConnected ? 1 : 0
                    });
                }
            }
            return jsonResponse({
                success: true,
                message: `${body.students?.length || 0} هنرجو با موفقیت همگام‌سازی شدند.`
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

    // ============================================================
    // 5. Deep Link Generation Endpoint
    // ============================================================
    if (path === '/api/generate-link' && request.method === 'POST') {
        try {
            const body = await request.json() as { studentId: string; botUsername?: string };
            let botUsername = (body.botUsername || env.BOT_USERNAME || '').replace('@', '').trim();
            if (!botUsername) {
                const me = await bot.getMe();
                if (me.ok && me.result?.username) {
                    botUsername = me.result.username;
                }
            }

            const link = botUsername
                ? `https://t.me/${botUsername}?start=${body.studentId}`
                : `https://t.me/?start=${body.studentId}`;

            return jsonResponse({
                success: true,
                message: 'لینک اتصال اختصاصی تلگرام تولید شد',
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
