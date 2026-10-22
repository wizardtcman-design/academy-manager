// ============================================================
// Digital contracts + per-channel delivery status (D1)
// All statements are scoped by academy_id so two academies sharing one
// deployment can never read each other's contracts or students.
// ============================================================

import { Env } from './types';

export type ContractStatus = 'ISSUED' | 'OPENED' | 'SIGNED' | 'EXPIRED' | 'REVOKED';

export interface AcademyRow {
    id: string;
    name: string;
    created_at_epoch: number;
    bot_token: string;
    bot_username: string;
    bot_id: string;
    webhook_secret: string;
    bot_connected_at_epoch: number;
    last_update_id: number;
}

export interface ContractRow {
    id: string;
    academy_id: string;
    enrollment_id: string;
    student_id: string;
    student_name: string;
    course_title: string;
    status: ContractStatus;
    token_hash: string;
    expires_at_epoch: number;
    issued_at_epoch: number;
    opened_at_epoch: number;
    signed_at_epoch: number;
    signed_at_shamsi: string;
    signer_name: string;
    signer_title: string;
    seal_key: string;
    seal_sha256: string;
    seal_width: number;
    seal_height: number;
    sig_key: string;
    sig_sha256: string;
    sig_width: number;
    sig_height: number;
    pdf_key: string;
    signed_pdf_key: string;
    pdf_sha256: string;
    signed_pdf_sha256: string;
    enrollment_json: string;
    phone_mask: string;
    national_mask: string;
    verify_hash: string;
    rejected_attempts: number;
    last_attempt_epoch: number;
    last_ip: string;
    last_user_agent: string;
    created_at_epoch: number;
    updated_at_epoch: number;
}

export interface ContractEvent {
    type: string;
    detail?: string;
    ip?: string;
    userAgent?: string;
}

export class ContractStore {
    constructor(private env: Env) {}

    get configured(): boolean {
        return Boolean(this.env.DB && this.env.CONTRACTS);
    }

    /** `true` when the tables from migration 0002 exist and R2 is bound. */
    async health(): Promise<{ d1: boolean; r2: boolean; error?: string }> {
        if (!this.env.DB) return { d1: false, r2: Boolean(this.env.CONTRACTS), error: 'D1 binding DB is missing' };
        try {
            await this.env.DB.prepare('SELECT COUNT(*) AS n FROM contracts').first();
            return { d1: true, r2: Boolean(this.env.CONTRACTS) };
        } catch (e: any) {
            return { d1: false, r2: Boolean(this.env.CONTRACTS), error: String(e?.message || e) };
        }
    }

    async findByEnrollment(academyId: string, enrollmentId: string): Promise<ContractRow | null> {
        return this.env.DB!
            .prepare('SELECT * FROM contracts WHERE academy_id = ? AND enrollment_id = ?')
            .bind(academyId, enrollmentId)
            .first<ContractRow>();
    }

    /** Every contract of one student, newest first. */
    async findByStudent(academyId: string, studentId: string): Promise<ContractRow[]> {
        const res = await this.env.DB!
            .prepare('SELECT * FROM contracts WHERE academy_id = ? AND student_id = ? ORDER BY issued_at_epoch DESC')
            .bind(academyId, studentId)
            .all<ContractRow>();
        return (res.results || []) as ContractRow[];
    }

    /** One row per academy: its own bot token, username and webhook secret. */
    async getAcademy(id: string): Promise<AcademyRow | null> {
        return this.env.DB!
            .prepare('SELECT * FROM academies WHERE id = ?')
            .bind(id)
            .first<AcademyRow>();
    }

    async findAcademyByBotId(botId: string): Promise<AcademyRow | null> {
        if (!botId) return null;
        return this.env.DB!
            .prepare('SELECT * FROM academies WHERE bot_id = ?')
            .bind(botId)
            .first<AcademyRow>();
    }

    /**
     * Stores the bot of one academy. Only this academy's row is touched, so
     * connecting a new bot never affects the other tenants.
     */
    async saveAcademyBot(input: {
        id: string;
        botToken: string;
        botUsername: string;
        botId: string;
        webhookSecret: string;
    }): Promise<void> {
        await this.env.DB!.prepare(
            `INSERT INTO academies (id, name, created_at_epoch, bot_token, bot_username, bot_id, webhook_secret, bot_connected_at_epoch)
             VALUES (?,?,?,?,?,?,?,?)
             ON CONFLICT(id) DO UPDATE SET
                bot_token = excluded.bot_token,
                bot_username = excluded.bot_username,
                bot_id = excluded.bot_id,
                webhook_secret = excluded.webhook_secret,
                bot_connected_at_epoch = excluded.bot_connected_at_epoch`
        ).bind(input.id, input.id, Date.now(), input.botToken, input.botUsername, input.botId, input.webhookSecret, Date.now()).run();
    }

    async findByTokenHash(tokenHash: string): Promise<ContractRow | null> {
        return this.env.DB!
            .prepare('SELECT * FROM contracts WHERE token_hash = ?')
            .bind(tokenHash)
            .first<ContractRow>();
    }

    async findById(academyId: string, id: string): Promise<ContractRow | null> {
        return this.env.DB!
            .prepare('SELECT * FROM contracts WHERE academy_id = ? AND id = ?')
            .bind(academyId, id)
            .first<ContractRow>();
    }

    /**
     * Inserts or updates the contract of one enrollment.
     * - A newly inserted row is always a new contract.
     * - An existing SIGNED contract is never rewritten by a later upload.
     * - An unsigned contract gets its token renewed in place.
     */
    async upsert(input: {
        academyId: string;
        id: string;
        enrollmentId: string;
        studentId: string;
        studentName: string;
        courseTitle: string;
        tokenHash: string;
        expiresAt: number;
        pdfKey: string;
        pdfSha256: string;
        enrollmentJson: string;
        phoneMask: string;
        nationalMask: string;
        verifyHash: string;
        signerName: string;
        rotateToken: boolean;
        signerTitle?: string;
        seal?: { key: string; sha256: string; width: number; height: number };
        signature?: { key: string; sha256: string; width: number; height: number };
    }): Promise<{ row: ContractRow; created: boolean; signerName: string }> {
        const now = Date.now();
        const existing = await this.findByEnrollment(input.academyId, input.enrollmentId);
        if (!existing) {
            const id = input.id || crypto.randomUUID();
            await this.env.DB!.prepare(
                `INSERT INTO contracts (id, academy_id, enrollment_id, student_id, student_name, course_title,
                    status, token_hash, expires_at_epoch, issued_at_epoch, enrollment_json, pdf_key, pdf_sha256,
                    phone_mask, national_mask, verify_hash, created_at_epoch, updated_at_epoch,
                    signer_name, signer_title, seal_key, seal_sha256, seal_width, seal_height,
                    sig_key, sig_sha256, sig_width, sig_height)
                 VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
            ).bind(
                id, input.academyId, input.enrollmentId, input.studentId, input.studentName, input.courseTitle,
                'ISSUED', input.tokenHash, input.expiresAt, now, input.enrollmentJson, input.pdfKey, input.pdfSha256,
                input.phoneMask, input.nationalMask, input.verifyHash, now, now,
                input.signerName || '', input.signerTitle || '',
                input.seal?.key || '', input.seal?.sha256 || '', input.seal?.width || 0, input.seal?.height || 0,
                input.signature?.key || '', input.signature?.sha256 || '', input.signature?.width || 0, input.signature?.height || 0
            ).run();
            const row = await this.findById(input.academyId, id);
            return { row: row!, created: true, signerName: input.signerName };
        }

        if (existing.status === 'SIGNED') {
            return { row: existing, created: false, signerName: input.signerName };
        }

        // Re-issue: the contract row and its id stay the same; only the token,
        // the PDF and the expiry change (a signed contract never reaches here).
        // Without a new token the stored hash must be kept; it is always present.
        const newTokenHash =
            (input.rotateToken && input.tokenHash) ? input.tokenHash : (existing.token_hash || input.tokenHash);
        // Same token => the pending link row keeps its hash; a rotated one drops it.
        if (input.rotateToken) {
            await this.env.DB!.prepare('DELETE FROM contract_pending_links WHERE contract_id = ?').bind(existing.id).run();
        }
        await this.env.DB!.prepare(
            `UPDATE contracts SET
                student_name = ?, course_title = ?, status = 'ISSUED', token_hash = ?, expires_at_epoch = ?,
                issued_at_epoch = ?, enrollment_json = ?, pdf_key = ?, pdf_sha256 = ?,
                phone_mask = ?, national_mask = ?, verify_hash = ?, rejected_attempts = 0,
                updated_at_epoch = ?,
                signer_name = ?, signer_title = ?,
                seal_key = ?, seal_sha256 = ?, seal_width = ?, seal_height = ?,
                sig_key = ?, sig_sha256 = ?, sig_width = ?, sig_height = ?
             WHERE academy_id = ? AND id = ?`
        ).bind(
            input.studentName, input.courseTitle, newTokenHash, input.expiresAt, now,
            input.enrollmentJson, input.pdfKey, input.pdfSha256, input.phoneMask, input.nationalMask,
            input.verifyHash, now,
            input.signerName || '', input.signerTitle || '',
            input.seal?.key || '', input.seal?.sha256 || '', input.seal?.width || 0, input.seal?.height || 0,
            input.signature?.key || '', input.signature?.sha256 || '', input.signature?.width || 0, input.signature?.height || 0,
            input.academyId, existing.id
        ).run();
        const row = await this.findById(input.academyId, existing.id);
        return { row: row!, created: false, signerName: input.signerName };
    }

    /**
     * The app generates the link token (so it can show a stable, offline-built
     * link) and already sends its hash in `upsert`. That hash is stored there;
     * the raw token is never sent. Kept for compatibility with old clients.
     */
    async setAppToken(academyId: string, id: string, tokenHash: string): Promise<void> {
        await this.env.DB!.prepare(
            'UPDATE contracts SET token_hash = ?, updated_at_epoch = ? WHERE academy_id = ? AND id = ? AND status != ?'
        ).bind(tokenHash, Date.now(), academyId, id, 'SIGNED').run();
    }

    /** Raw token of an untouched contract, so the server can resend the same, stable link. */
    async storePendingToken(academyId: string, id: string, token: string): Promise<void> {
        const h = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token));
        const hex = [...new Uint8Array(h)].map((b) => b.toString(16).padStart(2, '0')).join('');
        await this.env.DB!.prepare(
            `INSERT OR REPLACE INTO contract_pending_links (contract_id, academy_id, sha256, token, expires_at_epoch)
             VALUES (?,?,?,?, (SELECT expires_at_epoch FROM contracts WHERE academy_id = ? AND id = ?))`
        ).bind(id, academyId, hex, token, academyId, id).run();
    }

    /** The exact link the app generated, only while the contract is unsigned and unexpired. */
    async pendingToken(academyId: string, id: string, sha256: string): Promise<string> {
        const row = await this.env.DB!.prepare(
            `SELECT p.token AS token FROM contract_pending_links p
             JOIN contracts c ON c.id = p.contract_id AND c.academy_id = p.academy_id
             WHERE p.academy_id = ? AND p.contract_id = ? AND p.sha256 = ?
               AND c.status IN ('ISSUED','OPENED') AND c.expires_at_epoch > ?`
        ).bind(academyId, id, sha256, Date.now()).first<{ token: string }>();
        return row?.token || '';
    }

    async event(academyId: string, contractId: string, ev: ContractEvent): Promise<void> {
        await this.env.DB!.prepare(
            `INSERT INTO contract_events (academy_id, contract_id, type, detail, ip, user_agent, at_epoch)
             VALUES (?,?,?,?,?,?,?)`
        ).bind(
            academyId, contractId, ev.type, ev.detail || '', ev.ip || '', ev.userAgent || '', Date.now()
        ).run();
    }

    async markOpened(academyId: string, row: ContractRow, ip: string, ua: string): Promise<void> {
        const now = Date.now();
        await this.env.DB!.prepare(
            `UPDATE contracts SET opened_at_epoch = CASE WHEN opened_at_epoch = 0 THEN ? ELSE opened_at_epoch END,
                status = CASE WHEN status = 'ISSUED' THEN 'OPENED' ELSE status END,
                last_ip = ?, last_user_agent = ?, updated_at_epoch = ?
             WHERE academy_id = ? AND id = ?`
        ).bind(now, ip, ua, now, academyId, row.id).run();
    }

    async recordFailedAttempt(academyId: string, row: ContractRow, ip: string, ua: string, detail: string): Promise<number> {
        await this.env.DB!.prepare(
            `UPDATE contracts SET rejected_attempts = rejected_attempts + 1, last_attempt_epoch = ?,
                last_ip = ?, last_user_agent = ?, updated_at_epoch = ?
             WHERE academy_id = ? AND id = ?`
        ).bind(Date.now(), ip, ua, Date.now(), academyId, row.id).run();
        await this.event(academyId, row.id, { type: 'VERIFY_FAILED', detail, ip, userAgent: ua });
        const fresh = await this.findById(academyId, row.id);
        return fresh?.rejected_attempts ?? row.rejected_attempts + 1;
    }

    async markSigned(
        academyId: string,
        row: ContractRow,
        input: { signedPdfKey: string; signedSha: string; signerName: string; signedAtShamsi: string; ip: string; ua: string }
    ): Promise<void> {
        const now = Date.now();
        await this.env.DB!.prepare(
            `UPDATE contracts SET status = 'SIGNED', signed_at_epoch = ?, signed_at_shamsi = ?, signer_name = ?,
                signed_pdf_key = ?, signed_pdf_sha256 = ?, last_ip = ?, last_user_agent = ?, updated_at_epoch = ?
             WHERE academy_id = ? AND id = ? AND status != 'SIGNED'`
        ).bind(now, input.signedAtShamsi, input.signerName, input.signedPdfKey, input.signedSha,
            input.ip, input.ua, now, academyId, row.id).run();
        // Signed: the signing link is no longer needed anywhere.
        await this.env.DB!.prepare('DELETE FROM contract_pending_links WHERE contract_id = ?').bind(row.id).run();
    }

    async revoke(academyId: string, row: ContractRow): Promise<void> {
        await this.env.DB!.prepare(
            `UPDATE contracts SET status = 'REVOKED', updated_at_epoch = ? WHERE academy_id = ? AND id = ? AND status != 'SIGNED'`
        ).bind(Date.now(), academyId, row.id).run();
        await this.env.DB!.prepare('DELETE FROM contract_pending_links WHERE contract_id = ?').bind(row.id).run();
    }

    async listForStudent(academyId: string, studentId: string): Promise<ContractRow[]> {
        const { results } = await this.env.DB!
            .prepare('SELECT * FROM contracts WHERE academy_id = ? AND student_id = ? ORDER BY created_at_epoch DESC')
            .bind(academyId, studentId)
            .all<ContractRow>();
        return results || [];
    }

    // ---------- delivery status (Telegram + SMS tracked separately) ----------

    /**
     * Claims the delivery slot of one contract+kind (Stage 2). The unique
     * primary key makes retries and Telegram webhook re-deliveries safe: only
     * the FIRST caller may send. A slot left at sent_at_epoch = 0 (crashed
     * attempt) is atomically re-claimed via compare-and-set; a finished slot
     * (-1 = skipped, >0 = sent) can never be claimed again.
     */
    async claimContractDelivery(academyId: string, contractId: string, kind: string): Promise<boolean> {
        const key = `${kind}:${contractId}`;
        const insert = await this.env.DB!.prepare(
            `INSERT OR IGNORE INTO contract_deliveries (key, academy_id, contract_id, kind, sent_at_epoch)
             VALUES (?,?,?,?,0)`
        ).bind(key, academyId, contractId, kind).run();
        if ((insert.meta?.changes ?? 0) > 0) return true;
        // Crashed attempt: an unfinished slot (0) is reclaimable exactly once.
        const reclaim = await this.env.DB!.prepare(
            'UPDATE contract_deliveries SET sent_at_epoch = -1 WHERE academy_id = ? AND key = ? AND sent_at_epoch = 0'
        ).bind(academyId, key).run();
        return (reclaim.meta?.changes ?? 0) > 0;
    }

    /** Marks a claimed delivery as finished (sent or permanently skipped). */
    async finishContractDelivery(academyId: string, contractId: string, kind: string, sent: boolean): Promise<void> {
        const key = `${kind}:${contractId}`;
        await this.env.DB!.prepare(
            'UPDATE contract_deliveries SET sent_at_epoch = ? WHERE academy_id = ? AND key = ?'
        ).bind(sent ? Date.now() : -1, academyId, key).run();
    }

    /** Claims a delivery slot; returns false when another run already claimed it. */
    async claimDelivery(academyId: string, reminderId: string, channel: string): Promise<boolean> {
        const res = await this.env.DB!.prepare(
            `INSERT OR IGNORE INTO deliveries (id, academy_id, reminder_id, channel, status, attempts, created_at_epoch, updated_at_epoch)
             VALUES (?,?,?,?, 'PENDING', 1, ?, ?)`
        ).bind(crypto.randomUUID(), academyId, reminderId, channel, Date.now(), Date.now()).run();
        return (res.meta?.changes ?? 0) > 0;
    }

    async finishDelivery(
        academyId: string, reminderId: string, channel: string,
        status: 'SENT' | 'FAILED' | 'SKIPPED', error = '', providerMessageId = ''
    ): Promise<void> {
        await this.env.DB!.prepare(
            `UPDATE deliveries SET status = ?, error = ?, provider_message_id = ?, sent_at_epoch = ?,
                updated_at_epoch = ? WHERE academy_id = ? AND reminder_id = ? AND channel = ?`
        ).bind(status, error, providerMessageId, status === 'SENT' ? Date.now() : 0, Date.now(),
            academyId, reminderId, channel).run();
    }

    async deliveryStatuses(academyId: string, reminderIds: string[]): Promise<Record<string, Record<string, { status: string; error: string }>>> {
        const out: Record<string, Record<string, { status: string; error: string }>> = {};
        if (!reminderIds.length) return out;
        const placeholders = reminderIds.map(() => '?').join(',');
        const { results } = await this.env.DB!
            .prepare(`SELECT reminder_id, channel, status, error FROM deliveries WHERE academy_id = ? AND reminder_id IN (${placeholders})`)
            .bind(academyId, ...reminderIds)
            .all<{ reminder_id: string; channel: string; status: string; error: string }>();
        for (const r of results || []) {
            out[r.reminder_id] = out[r.reminder_id] || {};
            out[r.reminder_id][r.channel] = { status: r.status, error: r.error };
        }
        return out;
    }

    async updateSmsStatus(academyId: string, reminderId: string, status: string, error = ''): Promise<void> {
        await this.env.DB!.prepare(
            `UPDATE reminders SET sms_status = ?, sms_error = ?, sms_attempts = sms_attempts + 1,
                sms_sent_at_epoch = CASE WHEN ? = 'SENT' THEN ? ELSE sms_sent_at_epoch END
             WHERE academy_id = ? AND id = ?`
        ).bind(status, error, status, Date.now(), academyId, reminderId).run();
    }
}
