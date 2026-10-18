// ============================================================
// Main Cloudflare Worker Entry Point
// ============================================================

import { Env } from './types';
import { handleTelegramWebhook } from './handlers/webhook';
import { handleApiRequest } from './handlers/api';
import { handleScheduledEvent } from './handlers/cron';
import { handleContractsApi } from './handlers/contracts-api';
import { handleSignRoute } from './handlers/sign';

export default {
    async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
        const url = new URL(request.url);

        // Handle CORS Preflight
        if (request.method === 'OPTIONS') {
            return new Response(null, {
                status: 204,
                headers: {
                    'Access-Control-Allow-Origin': '*',
                    'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
                    'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Telegram-Bot-Api-Secret-Token',
                    'Access-Control-Max-Age': '86400'
                }
            });
        }

        // 0. Digital contracts: public signing page (no auth, token in URL) and
        //    the app API (bearer key). Both are handled before the legacy API.
        if (url.pathname === '/sign' || url.pathname.startsWith('/sign/')) {
            return await handleSignRoute(request, env, url);
        }
        if (url.pathname.startsWith('/api/contracts')) {
            return await handleContractsApi(request, env, url);
        }

        // 1. Route Telegram Webhook
        if (url.pathname === '/webhook' || url.pathname === '/telegram/webhook') {
            return await handleTelegramWebhook(request, env);
        }

        // 2. Route Android App REST API
        if (url.pathname.startsWith('/api') || url.pathname === '/health') {
            return await handleApiRequest(request, env, url);
        }

        // Default Welcome / Landing Page
        return new Response(JSON.stringify({
            service: 'Academy Telegram Cloudflare Backend',
            status: 'ONLINE',
            endpoints: {
                webhook: '/webhook',
                health: '/api/status',
                sendMessage: '/api/send-message',
                scheduleReminder: '/api/schedule-reminder',
                syncStudent: '/api/sync/student'
            }
        }, null, 2), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
        });
    },

    // 3. Cloudflare Cron Triggers for Automated Reminders
    async scheduled(event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
        ctx.waitUntil(handleScheduledEvent(event, env, ctx));
    }
};
