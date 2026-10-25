// ============================================================
// Stage 4: independent online-payment infrastructure.
//
// NO payment provider is activated here — that is an owner decision
// requiring merchant onboarding (API keys at minimum). What this module
// provides is the complete provider-agnostic contract:
//
//   - one dedicated payment link per (student, installment, exact
//     remaining amount), backed by a payment_transactions row;
//   - a /pay/<token> entry page that redirects to the connected
//     provider's hosted checkout, or explains (politely, in Persian)
//     that no gateway is connected yet;
//   - SERVER-SIDE verification: a redirect back, an opened link or a
//     user's claim NEVER confirms a payment — only the provider check
//     does (verifyPayment), with the expected amount compared exactly;
//   - an explicit state machine (PENDING | PAID | FAILED | CANCELED |
//     EXPIRED | AMOUNT_MISMATCH) with idempotent transitions: verifying
//     twice can never double-apply or double-receipt;
//   - after a verified PAID: the installment's paid/remaining/status
//     update automatically (no manual re-entry anywhere) and a receipt
//     is queued for Telegram with source 'online' and the provider ref
//     as its tracking number.
//
// Provider adapter checklist (for whoever connects a real gateway later):
//   1. implement GatewayProvider (createPaymentLink + verifyPayment);
//   2. register it in PROVIDER_FACTORIES;
//   3. the manager enters gateway_provider / gateway_merchant_id /
//      gateway_api_key in the app settings — stored per academy in D1,
//      never returned by any endpoint, never logged.
//
// Stage 9 — the gateway is COMPLETELY HIDDEN until an explicit owner
// activation. onlinePaymentsEnabled() is the single kill-switch: every
// online-payment entry point (API routes, /pay pages, reminder/bot link
// enrichment, channel status) consults it, and it defaults to OFF.
// Activation therefore requires ALL THREE: the env flag
// ONLINE_PAYMENTS_ENABLED="true" set at deploy time, a provider factory
// registered in code, and the manager's stored credentials — none of
// which exist today. No UI, menu, reminder or API response may even
// mention online payment while the switch is off.
// ============================================================

import { Env, PaymentTxnRecord, InstallmentRecord } from './types';
import { AcademyDatabase } from './db';
import { randomToken, sha256Hex } from './crypto';
import { epochToShamsi } from './shamsi';
import { dispatchReceipts } from './receipts';
import { TelegramBotClient } from './telegram';

export const LINK_TTL_MS = 72 * 3600_000;

/**
 * THE online-payment kill-switch (Stage 9). Defaults to DISABLED: without
 * the explicit deploy-time flag nothing online-payment related can run,
 * mint a link or be mentioned to a student. The card-to-card → receipt →
 * manager-approval flow is completely unaffected.
 */
export function onlinePaymentsEnabled(env: Env): boolean {
    return String((env as any)?.ONLINE_PAYMENTS_ENABLED || '').trim().toLowerCase() === 'true';
}

/** The one honest reason every blocked path returns while disabled. */
export const GATEWAY_DISABLED_REASON = 'پرداخت آنلاین در این نسخه فعال نیست؛ روش پرداخت، کارت‌به‌کارت با تأیید رسید توسط مدیریت است.';

export type TxnRow = PaymentTxnRecord;

export interface GatewayProvider {
    id: string;
    /** Provider-specific hosted checkout URL for this order. */
    createPaymentLink(txn: TxnRow, callbackUrl: string): Promise<{ redirectUrl: string }>;
    /**
     * THE only trust source. Must be a server-to-server call to the
     * provider's verification endpoint using the stored merchant key.
     */
    verifyPayment(txn: TxnRow): Promise<
        | { ok: true; paidAmount: number; ref: string; raw: string }
        | { ok: false; error: string; status?: 'FAILED' | 'CANCELED' | 'PENDING' }
    >;
}

// No provider is wired by default. Adding one later touches ONLY this map
// (via registerProviderFactory) plus the manager's settings — the link,
// ledger, verification contract, installment update and receipt pipeline
// below are already complete. Nothing is activated without both: a factory
// registered in code AND gateway_provider/gateway_merchant_id/gateway_api_key
// configured by the manager.
const PROVIDER_FACTORIES: Record<string, ((merchantId: string, apiKey: string) => GatewayProvider) | undefined> = {};

/** The single extension point for connecting a real gateway adapter. */
export function registerProviderFactory(id: string, factory: (merchantId: string, apiKey: string) => GatewayProvider): void {
    PROVIDER_FACTORIES[id.toLowerCase()] = factory;
}

export async function resolveProvider(env: Env, db: AcademyDatabase): Promise<{ provider: GatewayProvider | null; reason: string }> {
    // Kill-switch FIRST: even a fully configured provider can never be
    // resolved while the deploy-time flag is absent (Stage 9 hiding).
    if (!onlinePaymentsEnabled(env)) {
        return { provider: null, reason: GATEWAY_DISABLED_REASON };
    }
    const id = ((await db.getSetting('gateway_provider', '')) || '').trim().toLowerCase();
    if (!id || id === 'none') {
        return { provider: null, reason: 'درگاه پرداخت آنلاین هنوز متصل نشده است. لطفاً از روش کارت‌به‌کارت یا کارتخوان استفاده کنید یا با آموزشگاه هماهنگ شوید.' };
    }
    const factory = PROVIDER_FACTORIES[id];
    const merchantId = await db.getSetting('gateway_merchant_id', '');
    const apiKey = await db.getSetting('gateway_api_key', '');
    if (!factory) {
        // Manager-set value echoed on a PUBLIC page: strip markup characters.
        const safeId = id.replace(/[<>&"']/g, '');
        return { provider: null, reason: `درگاه «${safeId}» هنوز پشتیبانی یا تأیید نشده است.` };
    }
    if (!merchantId || !apiKey) {
        return { provider: null, reason: 'کلیدهای درگاه پرداخت تکمیل نشده‌اند؛ مدیر آموزشگاه باید تنظیمات درگاه را کامل کند.' };
    }
    return { provider: factory(merchantId, apiKey), reason: '' };
}

// ============================================================
// Link creation (dedicated per student + installment + exact amount)
// ============================================================
export type LinkResult =
    | { ok: true; orderId: string; url: string; amount: number; reused: boolean }
    | { ok: false; reason: string; errorCode: number };

export async function createInstallmentPaymentLink(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    studentId: string,
    installmentId: string,
    publicBaseUrl: string
): Promise<LinkResult> {
    // The installment MUST belong to this academy AND this student — a link
    // always carries the student's OWN debt, never another row's amount.
    const inst = await db.getInstallmentById(installmentId, academyId);
    if (!inst || inst.student_id !== studentId) {
        return { ok: false, reason: 'قسط موردنظر برای این هنرجو پیدا نشد.', errorCode: 404 };
    }
    if (inst.is_paid === 1 || (inst.remaining_amount || 0) <= 0) {
        return { ok: false, reason: 'این قسط قبلاً به‌طور کامل پرداخت شده است.', errorCode: 409 };
    }
    const { provider, reason } = await resolveProvider(env, db);
    if (!provider) {
        return { ok: false, reason, errorCode: 409 };
    }

    const now = Date.now();

    // Idempotent: an ACTIVE (pending, unexpired) link for the same
    // installment is REUSED, so reminders never mint new orders.
    const active = await db.findActiveTxnForInstallment(academyId, installmentId, now);
    if (active) {
        const raw = await db.getLinkTokenByHash(active.link_token_hash);
        if (raw) {
            // Same contract as the fresh path: a full URL, never the bare token.
            return { ok: true, orderId: active.id, url: `${publicBaseUrl}/pay/${raw}`, amount: active.amount, reused: true };
        }
        // Token record lost (should not happen): cancel the orphan and mint fresh.
        await db.updatePaymentTransaction(active.id, academyId, { status: 'CANCELED', updated_at_epoch: now });
    }

    const orderId = crypto.randomUUID();
    const token = randomToken(32);
    const txn: TxnRow = {
        id: orderId,
        academy_id: academyId,
        student_id: inst.student_id,
        installment_id: inst.id,
        amount: inst.remaining_amount,
        status: 'PENDING',
        provider: provider.id,
        provider_ref: '',
        link_token_hash: await sha256Hex(token),
        redirect_url: '',
        link_created_at_epoch: now,
        expires_at_epoch: now + LINK_TTL_MS,
        paid_amount: 0,
        verified_payload: '',
        verified_at_epoch: 0,
        receipt_id: '',
        created_at_epoch: now,
        updated_at_epoch: now
    };
    await db.insertPaymentTransaction(txn);

    let redirectUrl = '';
    try {
        const res = await provider.createPaymentLink(txn, `${publicBaseUrl}/pay/callback/${provider.id}`);
        redirectUrl = res.redirectUrl;
    } catch (e: any) {
        await db.updatePaymentTransaction(orderId, academyId, {
            status: 'FAILED', updated_at_epoch: Date.now(),
            verified_payload: `link-create error: ${e?.message || 'unknown'}`
        });
        return { ok: false, reason: 'ایجاد لینک پرداخت در درگاه ناموفق بود؛ بعداً دوباره تلاش کنید.', errorCode: 502 };
    }
    await db.updatePaymentTransaction(orderId, academyId, { redirect_url: redirectUrl, updated_at_epoch: Date.now() });
    await db.insertLinkToken(token, txn.link_token_hash, academyId, orderId, now + LINK_TTL_MS);

    return { ok: true, orderId, url: `${publicBaseUrl}/pay/${token}`, amount: txn.amount, reused: false };
}

// ============================================================
// /pay/<token> entry — never confirms anything by itself
// ============================================================
export type PayLinkView =
    | { kind: 'redirect'; to: string }
    | { kind: 'paid' }
    | { kind: 'expired' }
    | { kind: 'not-configured'; message: string }
    | { kind: 'not-found' };

export async function resolvePayLink(env: Env, db: AcademyDatabase, token: string): Promise<PayLinkView> {
    if (!/^[A-Za-z0-9_-]{20,64}$/.test(token)) return { kind: 'not-found' };
    const txn = await db.findTxnByTokenHash(await sha256Hex(token));
    if (!txn) return { kind: 'not-found' };

    if (txn.status === 'PAID') return { kind: 'paid' };
    if (txn.status === 'PENDING' && txn.expires_at_epoch > 0 && txn.expires_at_epoch < Date.now()) {
        await db.updatePaymentTransaction(txn.id, txn.academy_id, { status: 'EXPIRED', updated_at_epoch: Date.now() });
        return { kind: 'expired' };
    }
    if (txn.status !== 'PENDING') return { kind: 'expired' }; // FAILED / CANCELED / AMOUNT_MISMATCH

    if (txn.redirect_url) return { kind: 'redirect', to: txn.redirect_url };

    const db2 = new AcademyDatabase(env, txn.academy_id);
    const { provider, reason } = await resolveProvider(env, db2);
    if (!provider) return { kind: 'not-configured', message: reason };
    try {
        const { redirectUrl } = await provider.createPaymentLink(txn, `${new URL(env.PUBLIC_BASE_URL || 'https://worker.test').origin}/pay/callback/${provider.id}`);
        await db.updatePaymentTransaction(txn.id, txn.academy_id, { redirect_url: redirectUrl, updated_at_epoch: Date.now() });
        return { kind: 'redirect', to: redirectUrl };
    } catch (_) {
        return { kind: 'not-configured', message: 'اتصال به درگاه پرداخت در این لحظه ممکن نیست؛ بعداً تلاش کنید.' };
    }
}

// ============================================================
// Server-side verification (the ONLY path that marks a payment real)
// ============================================================
export type VerifyOutcome =
    | { state: 'PAID'; alreadyPaid: boolean; orderId: string; amount: number; ref: string; receiptSent: boolean }
    | { state: 'FAILED' | 'CANCELED' | 'EXPIRED' | 'AMOUNT_MISMATCH' | 'PENDING'; orderId: string; error?: string };

export async function verifyTransaction(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    orderId: string,
    bot: TelegramBotClient | null
): Promise<VerifyOutcome> {
    const txn = await db.getPaymentTransaction(orderId, academyId);
    if (!txn) return { state: 'FAILED', orderId, error: 'تراکنش پیدا نشد.' };

    // Idempotency: an already-PAID order is reported, never re-applied.
    if (txn.status === 'PAID') {
        return { state: 'PAID', alreadyPaid: true, orderId, amount: txn.paid_amount, ref: txn.provider_ref, receiptSent: true };
    }
    if (txn.status === 'EXPIRED') return { state: 'EXPIRED', orderId };
    if (txn.status === 'CANCELED') return { state: 'CANCELED', orderId };
    if (txn.status === 'AMOUNT_MISMATCH') return { state: 'AMOUNT_MISMATCH', orderId, error: 'مبلغ تأییدشده با مبلغ قسط یکی نیست؛ با آموزشگاه هماهنگ کنید.' };

    // Expired while PENDING?
    if (txn.expires_at_epoch > 0 && txn.expires_at_epoch < Date.now()) {
        await db.updatePaymentTransaction(orderId, academyId, { status: 'EXPIRED', updated_at_epoch: Date.now() });
        return { state: 'EXPIRED', orderId };
    }

    const { provider, reason } = await resolveProvider(env, db);
    if (!provider) return { state: 'FAILED', orderId, error: reason || 'درگاه متصل نیست.' };

    let verified: Awaited<ReturnType<GatewayProvider['verifyPayment']>>;
    try {
        verified = await provider.verifyPayment(txn);
    } catch (e: any) {
        return { state: 'PENDING', orderId, error: `بررسی در درگاه ناموفق بود: ${e?.message || 'unknown'}` };
    }

    const now = Date.now();
    if (!verified.ok) {
        if (verified.status === 'CANCELED' || verified.status === 'FAILED') {
            await db.updatePaymentTransaction(orderId, academyId, {
                status: verified.status, updated_at_epoch: now,
                verified_payload: verified.error || ''
            });
            return { state: verified.status, orderId, error: verified.error };
        }
        return { state: 'PENDING', orderId, error: verified.error };
    }

    // Amount check: the provider's confirmed amount MUST equal the expected
    // remaining — otherwise nothing is auto-applied and the manager resolves.
    if (Number(verified.paidAmount) !== Number(txn.amount)) {
        await db.updatePaymentTransaction(orderId, academyId, {
            status: 'AMOUNT_MISMATCH', paid_amount: Number(verified.paidAmount) || 0,
            provider_ref: verified.ref || '', verified_payload: verified.raw || '',
            verified_at_epoch: now, updated_at_epoch: now
        });
        return { state: 'AMOUNT_MISMATCH', orderId, error: 'مبلغ پرداخت‌شده با مبلغ قسط یکی نیست.' };
    }

    // ---------- transition to PAID (CAS: only PENDING -> PAID) ----------
    // Two concurrent verifies (provider redirect + app pull) must never BOTH
    // credit the installment: the winner is decided by the database itself.
    const won = await db.transitionPaymentTransaction(orderId, academyId, 'PENDING', {
        status: 'PAID', paid_amount: verified.paidAmount,
        provider_ref: verified.ref || '', verified_payload: verified.raw || '',
        verified_at_epoch: now, updated_at_epoch: now,
        receipt_id: `online-${txn.id}`
    });
    if (!won) {
        const fresh = await db.getPaymentTransaction(orderId, academyId);
        return {
            state: 'PAID', alreadyPaid: true, orderId,
            amount: fresh?.paid_amount ?? 0, ref: fresh?.provider_ref || '', receiptSent: true
        };
    }

    // Auto-update the installment (no manual re-registration anywhere) and
    // queue the online receipt; then attempt an immediate Telegram send.
    const receiptId = await applyOnlinePaymentToInstallment(db, academyId, txn, verified.ref || '', now);
    let receiptSent = false;
    if (bot && receiptId) {
        await dispatchReceipts(env, db, bot, academyId, now);
        const row = await db.getPaymentReceipt(receiptId, academyId);
        receiptSent = row?.send_status === 'SENT';
    }
    return { state: 'PAID', alreadyPaid: false, orderId, amount: verified.paidAmount, ref: verified.ref || '', receiptSent };
}

/** Applies a verified online payment to the installment and queues its receipt. */
export async function applyOnlinePaymentToInstallment(
    db: AcademyDatabase,
    academyId: string,
    txn: TxnRow,
    providerRef: string,
    now: number
): Promise<string> {
    const inst = await db.getInstallmentById(txn.installment_id, academyId);
    if (!inst) return '';

    const student = await db.getStudentById(txn.student_id, academyId);
    const paidTotal = (inst.paid_amount || 0) + txn.amount;
    const remaining = Math.max(0, (inst.amount || 0) - paidTotal);
    const fullyPaid = remaining <= 0;
    await db.updateInstallmentAfterPayment(inst.id, academyId, {
        paid_amount: paidTotal,
        remaining_amount: remaining,
        is_paid: fullyPaid ? 1 : inst.is_paid || 0,
        paid_date_shamsi: fullyPaid ? epochToShamsi(now) : inst.paid_date_shamsi || ''
    });

    // Stage 5: a VERIFIED online payment stops the installment's reminders
    // immediately (they re-arm on the next sync only if it was partial) and
    // is audited in the payment lifecycle history.
    await db.cancelPendingRemindersForInstallment(inst.id);
    try {
        await db.insertPaymentEvent({
            id: crypto.randomUUID(),
            academy_id: academyId,
            student_id: txn.student_id,
            installment_id: txn.installment_id,
            payment_id: `online-${txn.id}`,
            claim_id: '',
            event_type: 'ONLINE_VERIFIED',
            channel: 'gateway',
            actor: txn.provider,
            amount: txn.amount,
            detail: `provider ref ${providerRef || txn.id}`,
            created_at_epoch: now
        });
    } catch (_) { /* auditing must never block the payment */ }

    // The receipt IS the verified transaction, marked source 'online'.
    const receiptId = `online-${txn.id}`;
    await db.insertPaymentReceipt({
        id: receiptId,
        academy_id: academyId,
        student_id: txn.student_id,
        installment_id: txn.installment_id,
        installment_number: inst.installment_number,
        course_title: inst.course_title || '',
        transaction_id: txn.id.slice(0, 8).toUpperCase(),
        tracking_number: providerRef || txn.id,
        amount: txn.amount,
        student_name: student?.full_name || '',
        paid_date_shamsi: epochToShamsi(now),
        paid_time: '',
        payment_method: 'ONLINE',
        destination_account: `درگاه ${txn.provider}`,
        notes: '',
        is_reversed: 0,
        reversal_reason: '',
        source: 'online',
        receipt_json: JSON.stringify({ orderId: txn.id, providerRef, amount: txn.amount }),
        send_status: 'QUEUED',
        send_reason: 'online',
        eligible_at_epoch: now,
        attempts: 0,
        last_attempt_epoch: 0,
        telegram_message_id: '',
        last_error: '',
        created_at_epoch: now,
        updated_at_epoch: now
    });
    return receiptId;
}
