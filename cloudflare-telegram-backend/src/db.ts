// ============================================================
// Database & Storage Layer using Cloudflare D1 and KV (Graceful Fallback)
// ============================================================

import {
    Env,
    StudentRecord,
    InstallmentRecord,
    FinancialSummaryRecord,
    ReminderRecord,
    MessageLogRecord
} from './types';

// In-Memory Fallback Map, used ONLY by the local test server (`npm run dev`).
// Production always writes to D1; the authenticated health endpoint reports
// `d1` so the app can refuse to issue contracts while data would be volatile.
const memoryStudents = new Map<string, StudentRecord>();
const memoryChatMap = new Map<string, string>(); // chatId -> studentId
const memoryInstallments = new Map<string, InstallmentRecord[]>(); // studentId -> installments
const memoryFinancials = new Map<string, FinancialSummaryRecord>(); // studentId -> summary
const memoryReminders = new Map<string, ReminderRecord>(); // id -> reminder
const memoryLogs: MessageLogRecord[] = [];
const memorySettings = new Map<string, string>();

export class AcademyDatabase {
    private db?: D1Database;
    private kv?: KVNamespace;
    /** Tenant of this database handle: every contract query is scoped by it. */
    public readonly academyId: string;
    /**
     * When true the in-memory maps are never read or written: a D1 failure is
     * reported instead of pretending the write succeeded on a volatile isolate.
     */
    private readonly strict: boolean;

    constructor(env: Env, academyId: string = 'default') {
        this.db = env.DB;
        this.kv = env.STUDENT_KV;
        this.academyId = academyId;
        const flag = (env.STRICT_STORAGE || '').trim().toLowerCase();
        this.strict = flag === 'true' || (flag === '' && env.ENVIRONMENT !== 'development');
    }

    public isD1Configured(): boolean {
        return Boolean(this.db);
    }

    public isKVConfigured(): boolean {
        return Boolean(this.kv);
    }

    // ============================================================
    // Student Methods
    // ============================================================

    async getStudentById(id: string, academyId: string = this.academyId): Promise<StudentRecord | null> {
        // Fast-path: Check KV cache first if available. The cache key carries the
        // tenant so two academies can never read each other's cached row.
        if (this.kv) {
            try {
                const cached = await this.kv.get(`student:${academyId}:${id}`, 'json');
                if (cached) return cached as StudentRecord;
            } catch (_) {}
        }

        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM students WHERE id = ? AND academy_id = ?').bind(id, academyId);
                const record = await stmt.first<StudentRecord>();
                if (record && this.kv) {
                    await this.kv.put(`student:${academyId}:${id}`, JSON.stringify(record), { expirationTtl: 3600 });
                }
                return record;
            } catch (e) {
                console.warn('[DB] D1 query failed, checking memory store:', e);
            }
        }

        // Memory fallback (dev only; strict mode never reads it)
        return (memoryStudents.get(`${academyId}:${id}`) || memoryStudents.get(id) || null) as StudentRecord | null;
    }

    async getStudentByCode(studentCode: string, academyId: string = this.academyId): Promise<StudentRecord | null> {
        if (!studentCode) return null;
        if (this.db) {
            try {
                return await this.db.prepare('SELECT * FROM students WHERE student_code = ? AND academy_id = ?').bind(studentCode, academyId).first<StudentRecord>() ?? null;
            } catch (e) {
                console.warn('[DB] D1 getStudentByCode failed:', e);
            }
        }
        for (const s of memoryStudents.values()) {
            if (s.student_code === studentCode) return s;
        }
        return null;
    }

    /** All students of THIS academy that are linked to Telegram (broadcast / link pull). */
    async getAllConnectedStudents(academyId: string = this.academyId): Promise<StudentRecord[]> {
        if (this.db) {
            try {
                const { results } = await this.db.prepare(
                    "SELECT * FROM students WHERE is_telegram_connected = 1 AND telegram_chat_id != '' AND academy_id = ?"
                ).bind(academyId).all<StudentRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 getAllConnectedStudents failed:', e);
            }
        }
        return Array.from(memoryStudents.values()).filter(s => s.is_telegram_connected === 1 && s.telegram_chat_id);
    }

    async getStudentByChatId(chatId: string, academyId: string = this.academyId): Promise<StudentRecord | null> {
        if (!chatId) return null;
        if (this.kv) {
            try {
                const studentId = await this.kv.get(`chat:${academyId}:${chatId}`);
                if (studentId) {
                    const student = await this.getStudentById(studentId, academyId);
                    if (student) return student;
                }
            } catch (_) {}
        }

        if (this.db) {
            try {
                // Scoped by academy: one chat can only ever see its own tenant.
                const stmt = this.db
                    .prepare('SELECT * FROM students WHERE telegram_chat_id = ? AND academy_id = ? LIMIT 1')
                    .bind(chatId, this.academyId);
                const record = await stmt.first<StudentRecord>();
                if (record && this.kv) {
                    await this.kv.put(`chat:${academyId}:${chatId}`, record.id, { expirationTtl: 3600 });
                }
                return record;
            } catch (e) {
                console.warn('[DB] D1 getStudentByChatId failed:', e);
            }
        }
        if (this.strict) return null;

        // Memory fallback (local dev only)
        const memStudentId = memoryChatMap.get(chatId);
        if (memStudentId) {
            return memoryStudents.get(memStudentId) || null;
        }
        for (const s of memoryStudents.values()) {
            if (s.telegram_chat_id === chatId) return s;
        }
        return null;
    }

    async upsertStudent(student: Partial<StudentRecord> & {
        id: string;
        full_name: string;
        /** Optional secure /start link token hash (Stage 2). Empty/omitted keeps the stored one. */
        telegramLinkHash?: string;
        telegramLinkExpires?: number;
    }): Promise<boolean> {
        const now = Date.now();
        // Tenant-aware lookup: the preserve-existing logic below must see THIS
        // academy's row, or a bot link made in the webhook would be wiped by
        // the next app sync for every non-default academy.
        const existing = await this.getStudentById(student.id, this.academyId);
        const incomingLinkHash = (student.telegramLinkHash || '').trim().toLowerCase();
        const incomingLinkExpires = Number(student.telegramLinkExpires || 0);
        const storedLinkHash = incomingLinkHash || (existing as any)?.telegram_link_hash || '';
        const storedLinkExpires = incomingLinkHash ? (incomingLinkExpires || 0) : (Number((existing as any)?.telegram_link_expires) || 0);

        const record: StudentRecord = {
            id: student.id,
            student_code: student.student_code ?? existing?.student_code ?? '',
            full_name: student.full_name,
            phone: student.phone ?? existing?.phone ?? '',
            national_code: student.national_code ?? existing?.national_code ?? '',
            // Never let a sync from the app erase a link the student created in the bot
            telegram_chat_id: student.telegram_chat_id || existing?.telegram_chat_id || '',
            telegram_username: student.telegram_username || existing?.telegram_username || '',
            is_telegram_connected: (student.is_telegram_connected === 1 || (student.telegram_chat_id ?? '') !== '')
                ? 1
                : (existing?.is_telegram_connected ?? 0),
            linked_at_epoch: student.linked_at_epoch ?? existing?.linked_at_epoch ?? 0,
            created_at_epoch: existing?.created_at_epoch ?? now,
            updated_at_epoch: now
        };

        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO students (
                        id, academy_id, student_code, full_name, phone, national_code,
                        telegram_chat_id, telegram_username, is_telegram_connected,
                        linked_at_epoch, created_at_epoch, updated_at_epoch,
                        telegram_link_hash, telegram_link_expires
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        student_code = excluded.student_code,
                        full_name = excluded.full_name,
                        phone = excluded.phone,
                        national_code = excluded.national_code,
                        telegram_chat_id = excluded.telegram_chat_id,
                        telegram_username = excluded.telegram_username,
                        is_telegram_connected = excluded.is_telegram_connected,
                        linked_at_epoch = excluded.linked_at_epoch,
                        updated_at_epoch = excluded.updated_at_epoch,
                        telegram_link_hash = excluded.telegram_link_hash,
                        telegram_link_expires = excluded.telegram_link_expires
                `).bind(
                    record.id, this.academyId, record.student_code, record.full_name, record.phone, record.national_code,
                    record.telegram_chat_id, record.telegram_username, record.is_telegram_connected,
                    record.linked_at_epoch, record.created_at_epoch, record.updated_at_epoch,
                    storedLinkHash, storedLinkExpires
                ).run();
            } catch (e) {
                // Surfaced to the caller (sync API) so a failed row is reported
                // instead of a silent 200 with nothing stored.
                console.warn('[DB] D1 upsertStudent failed:', e);
                return false;
            }
        }

        // Update the dev-only memory store (never used in production).
        if (!this.strict) {
            memoryStudents.set(record.id, record);
            if (record.telegram_chat_id) {
                memoryChatMap.set(record.telegram_chat_id, record.id);
            }
        }

        // Update KV cache if present
        if (this.kv) {
            try {
                await this.kv.put(`student:${this.academyId}:${record.id}`, JSON.stringify(record), { expirationTtl: 3600 });
                if (record.telegram_chat_id) {
                    await this.kv.put(`chat:${this.academyId}:${record.telegram_chat_id}`, record.id, { expirationTtl: 3600 });
                }
            } catch (_) {}
        }
        return true;
    }

    async linkTelegramChat(studentId: string, chatId: string, username: string): Promise<boolean> {
        // Tenant-aware: the student must belong to THIS academy's bot.
        const student = await this.getStudentById(studentId, this.academyId);
        if (!student) return false;

        const now = Date.now();
        if (this.db) {
            try {
                await this.db.prepare(`
                    UPDATE students SET
                        telegram_chat_id = ?,
                        telegram_username = ?,
                        is_telegram_connected = 1,
                        linked_at_epoch = CASE WHEN linked_at_epoch > 0 THEN linked_at_epoch ELSE ? END,
                        updated_at_epoch = ?
                    WHERE id = ? AND academy_id = ?
                `).bind(chatId, username, now, now, studentId, this.academyId).run();
            } catch (e) {
                console.warn('[DB] D1 linkTelegramChat failed:', e);
            }
        }

        student.telegram_chat_id = chatId;
        student.telegram_username = username;
        student.is_telegram_connected = 1;
        student.updated_at_epoch = now;

        if (!this.strict) {
            memoryStudents.set(studentId, student);
            memoryChatMap.set(chatId, studentId);
        }

        if (this.kv) {
            try {
                await this.kv.put(`student:${this.academyId}:${studentId}`, JSON.stringify(student), { expirationTtl: 3600 });
                await this.kv.put(`chat:${this.academyId}:${chatId}`, studentId, { expirationTtl: 3600 });
            } catch (_) {}
        }
        return true;
    }

    /**
     * Stage 2 secure linking: the /start payload is a one-time random token
     * whose SHA-256 is stored on the student row. A raw student id can never
     * be used to grab somebody else's chat.
     */
    async getStudentByLinkHash(linkHash: string, academyId: string = this.academyId): Promise<StudentRecord | null> {
        const hash = (linkHash || '').trim().toLowerCase();
        if (!hash || !this.db) return null;
        try {
            return await this.db
                .prepare('SELECT * FROM students WHERE telegram_link_hash = ? AND academy_id = ?')
                .bind(hash, academyId)
                .first<StudentRecord>() ?? null;
        } catch (e) {
            console.warn('[DB] D1 getStudentByLinkHash failed:', e);
            return null;
        }
    }

    /** Stores (or rotates) the /start link token of one student (hash + expiry). */
    async setTelegramLinkHash(studentId: string, linkHash: string, expiresAtEpoch: number): Promise<void> {
        if (!this.db) return;
        try {
            await this.db.prepare(
                'UPDATE students SET telegram_link_hash = ?, telegram_link_expires = ?, updated_at_epoch = ? WHERE id = ? AND academy_id = ?'
            ).bind((linkHash || '').trim().toLowerCase(), expiresAtEpoch || 0, Date.now(), studentId, this.academyId).run();
        } catch (e) {
            console.warn('[DB] D1 setTelegramLinkHash failed:', e);
        }
    }

    /** Single-use semantics: the token stops working right after one successful binding. */
    async consumeTelegramLinkToken(studentId: string): Promise<void> {
        if (!this.db) return;
        try {
            await this.db.prepare(
                'UPDATE students SET telegram_link_hash = ?, telegram_link_expires = 0, updated_at_epoch = ? WHERE id = ? AND academy_id = ?'
            ).bind('', Date.now(), studentId, this.academyId).run();
        } catch (e) {
            console.warn('[DB] D1 consumeTelegramLinkToken failed:', e);
        }
    }

    // ============================================================
    // Installments & Financial Summaries
    // ============================================================

    async getInstallmentsForStudent(studentId: string, academyId: string = this.academyId): Promise<InstallmentRecord[]> {
        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM installments WHERE student_id = ? AND academy_id = ? ORDER BY due_date_epoch ASC, installment_number ASC').bind(studentId, academyId);
                const { results } = await stmt.all<InstallmentRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 getInstallments failed:', e);
            }
        }
        return this.strict ? [] : (memoryInstallments.get(studentId) || []);
    }

    async upsertInstallments(installments: InstallmentRecord[]): Promise<void> {
        if (!installments || installments.length === 0) return;
        const now = Date.now();

        if (this.db) {
            try {
                const stmts = installments.map(inst => this.db!.prepare(`
                    INSERT INTO installments (
                        id, academy_id, student_id, installment_number, total_installments, course_title,
                        amount, paid_amount, remaining_amount, due_date_shamsi, due_date_epoch,
                        is_paid, paid_date_shamsi, updated_at_epoch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        installment_number = excluded.installment_number,
                        total_installments = excluded.total_installments,
                        course_title = excluded.course_title,
                        amount = excluded.amount,
                        paid_amount = excluded.paid_amount,
                        remaining_amount = excluded.remaining_amount,
                        due_date_shamsi = excluded.due_date_shamsi,
                        due_date_epoch = excluded.due_date_epoch,
                        is_paid = excluded.is_paid,
                        paid_date_shamsi = excluded.paid_date_shamsi,
                        updated_at_epoch = ?
                `).bind(
                    inst.id, this.academyId, inst.student_id, inst.installment_number, inst.total_installments, inst.course_title,
                    inst.amount, inst.paid_amount, inst.remaining_amount, inst.due_date_shamsi, inst.due_date_epoch,
                    inst.is_paid, inst.paid_date_shamsi, now, now
                ));

                await this.db.batch(stmts);
            } catch (e) {
                console.warn('[DB] D1 upsertInstallments failed:', e);
            }
        }

        const studentId = installments[0].student_id;
        if (!this.strict) memoryInstallments.set(studentId, installments);
    }

    async getFinancialSummary(studentId: string, academyId: string = this.academyId): Promise<FinancialSummaryRecord | null> {
        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM financial_summaries WHERE student_id = ? AND academy_id = ?').bind(studentId, academyId);
                return await stmt.first<FinancialSummaryRecord>() ?? null;
            } catch (e) {
                console.warn('[DB] D1 getFinancialSummary failed:', e);
            }
        }
        return this.strict ? null : (memoryFinancials.get(studentId) || null);
    }

    async upsertFinancialSummary(summary: FinancialSummaryRecord): Promise<void> {
        const now = Date.now();
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO financial_summaries (
                        student_id, academy_id, total_tuition, total_paid, remaining_debt,
                        active_courses_count, courses_summary, settlement_status, updated_at_epoch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(student_id) DO UPDATE SET
                        total_tuition = excluded.total_tuition,
                        total_paid = excluded.total_paid,
                        remaining_debt = excluded.remaining_debt,
                        active_courses_count = excluded.active_courses_count,
                        courses_summary = excluded.courses_summary,
                        settlement_status = excluded.settlement_status,
                        updated_at_epoch = excluded.updated_at_epoch
                `).bind(
                    summary.student_id, this.academyId, summary.total_tuition, summary.total_paid, summary.remaining_debt,
                    summary.active_courses_count, summary.courses_summary, summary.settlement_status, now
                ).run();
            } catch (e) {
                console.warn('[DB] D1 upsertFinancialSummary failed:', e);
            }
        }
        if (!this.strict) memoryFinancials.set(summary.student_id, summary);
    }

    // ============================================================
    // Reminders & Scheduled Messages Queue
    // ============================================================

    async addReminder(reminder: ReminderRecord): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO reminders (
                        id, academy_id, student_id, student_name, chat_id, installment_id,
                        reminder_type, message_text, scheduled_epoch, scheduled_time_str,
                        status, error_message, retry_count, created_at_epoch, sent_at_epoch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                `).bind(
                    reminder.id, this.academyId, reminder.student_id, reminder.student_name, reminder.chat_id, reminder.installment_id,
                    reminder.reminder_type, reminder.message_text, reminder.scheduled_epoch, reminder.scheduled_time_str,
                    reminder.status, reminder.error_message, reminder.retry_count, reminder.created_at_epoch, reminder.sent_at_epoch
                ).run();
            } catch (e) {
                console.warn('[DB] D1 addReminder failed:', e);
            }
        }
        if (!this.strict) memoryReminders.set(reminder.id, reminder);
    }

    async getDueReminders(maxEpoch: number, limit: number = 50, academyId: string = this.academyId): Promise<ReminderRecord[]> {
        if (this.db) {
            try {
                const stmt = this.db.prepare(`
                    SELECT * FROM reminders
                    WHERE status = 'SCHEDULED' AND scheduled_epoch <= ? AND academy_id = ?
                    ORDER BY scheduled_epoch ASC
                    LIMIT ?
                `).bind(maxEpoch, academyId, limit);
                const { results } = await stmt.all<ReminderRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 getDueReminders failed:', e);
            }
        }

        const due: ReminderRecord[] = [];
        for (const r of memoryReminders.values()) {
            if (r.status === 'SCHEDULED' && r.scheduled_epoch <= maxEpoch) {
                due.push(r);
                if (due.length >= limit) break;
            }
        }
        return due;
    }

    async updateReminderStatus(id: string, status: 'SENT' | 'FAILED' | 'CANCELLED', errorMessage: string = ''): Promise<void> {
        const now = Date.now();
        if (this.db) {
            try {
                await this.db.prepare(`
                    UPDATE reminders SET
                        status = ?,
                        error_message = ?,
                        sent_at_epoch = CASE WHEN ? = 'SENT' THEN ? ELSE sent_at_epoch END,
                        retry_count = retry_count + 1
                    WHERE id = ? AND academy_id = ?
                `).bind(status, errorMessage, status, now, id, this.academyId).run();
            } catch (e) {
                console.warn('[DB] D1 updateReminderStatus failed:', e);
            }
        }

        const r = memoryReminders.get(id);
        if (r) {
            r.status = status;
            r.error_message = errorMessage;
            if (status === 'SENT') r.sent_at_epoch = now;
            r.retry_count++;
        }
    }

    async getReminderById(id: string): Promise<ReminderRecord | null> {
        if (this.db) {
            try {
                return await this.db.prepare('SELECT * FROM reminders WHERE id = ? AND academy_id = ?').bind(id, this.academyId).first<ReminderRecord>() ?? null;
            } catch (e) {
                console.warn('[DB] D1 getReminderById failed:', e);
            }
        }
        return memoryReminders.get(id) || null;
    }

    async retryReminder(id: string, nextEpoch: number, errorMessage: string = ''): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    UPDATE reminders SET
                        status = 'SCHEDULED',
                        scheduled_epoch = ?,
                        error_message = ?,
                        retry_count = retry_count + 1
                    WHERE id = ? AND academy_id = ?
                `).bind(nextEpoch, errorMessage, id, this.academyId).run();
            } catch (e) {
                console.warn('[DB] D1 retryReminder failed:', e);
            }
        }
        const r = memoryReminders.get(id);
        if (r) {
            r.status = 'SCHEDULED';
            r.scheduled_epoch = nextEpoch;
            r.error_message = errorMessage;
            r.retry_count++;
        }
    }

    async cancelPendingRemindersForInstallment(installmentId: string): Promise<void> {
        if (!installmentId) return;
        if (this.db) {
            try {
                await this.db.prepare(`
                    UPDATE reminders SET status = 'CANCELLED', error_message = 'Installment already paid'
                    WHERE installment_id = ? AND status = 'SCHEDULED' AND academy_id = ?
                `).bind(installmentId, this.academyId).run();
            } catch (e) {
                console.warn('[DB] D1 cancelPendingReminders failed:', e);
            }
        }
        for (const r of memoryReminders.values()) {
            if (r.installment_id === installmentId && r.status === 'SCHEDULED') {
                r.status = 'CANCELLED';
            }
        }
    }

    /** Cancels reminders that are stale (installment paid / past due by more than graceDays). */
    async pruneStaleReminders(nowEpoch: number, graceDays: number = 2): Promise<number> {
        const cutoff = nowEpoch - graceDays * 24 * 60 * 60 * 1000;
        let cancelled = 0;
        if (this.db) {
            try {
                const res = await this.db.prepare(`
                    UPDATE reminders SET status = 'CANCELLED', error_message = 'Stale: missed schedule window'
                    WHERE status = 'SCHEDULED' AND scheduled_epoch < ? AND academy_id = ?
                `).bind(cutoff, this.academyId).run();
                cancelled = res.meta?.changes || 0;
            } catch (e) {
                console.warn('[DB] D1 pruneStaleReminders failed:', e);
            }
        }
        for (const r of memoryReminders.values()) {
            if (r.status === 'SCHEDULED' && r.scheduled_epoch < cutoff) {
                r.status = 'CANCELLED';
                cancelled++;
            }
        }
        return cancelled;
    }

    async deleteStudent(id: string, academyId: string = this.academyId): Promise<void> {
        if (this.db) {
            try {
                await this.db.batch([
                    this.db.prepare('DELETE FROM reminders WHERE student_id = ? AND academy_id = ?').bind(id, academyId),
                    this.db.prepare('DELETE FROM installments WHERE student_id = ? AND academy_id = ?').bind(id, academyId),
                    this.db.prepare('DELETE FROM financial_summaries WHERE student_id = ? AND academy_id = ?').bind(id, academyId),
                    this.db.prepare('DELETE FROM students WHERE id = ? AND academy_id = ?').bind(id, academyId)
                ]);
            } catch (e) {
                console.warn('[DB] D1 deleteStudent failed:', e);
            }
        }
        memoryStudents.delete(id);
        memoryInstallments.delete(id);
        memoryFinancials.delete(id);
        for (const [chatId, sid] of memoryChatMap.entries()) {
            if (sid === id) memoryChatMap.delete(chatId);
        }
    }

    // ============================================================
    // Logs & Settings
    // ============================================================

    async logMessage(log: MessageLogRecord): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO message_logs (
                        id, academy_id, student_id, student_name, chat_id, message_type,
                        content, status, error_message, timestamp_epoch, timestamp_shamsi
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                `).bind(
                    log.id, this.academyId, log.student_id, log.student_name, log.chat_id, log.message_type,
                    log.content, log.status, log.error_message, log.timestamp_epoch, log.timestamp_shamsi
                ).run();
            } catch (e) {
                console.warn('[DB] D1 logMessage failed:', e);
            }
        }
        if (!this.strict) {
            memoryLogs.unshift(log);
            if (memoryLogs.length > 200) memoryLogs.pop();
        }
    }

    async getRecentLogs(limit: number = 100, academyId: string = this.academyId): Promise<MessageLogRecord[]> {
        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM message_logs WHERE academy_id = ? ORDER BY timestamp_epoch DESC LIMIT ?').bind(academyId, limit);
                const { results } = await stmt.all<MessageLogRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 getRecentLogs failed:', e);
            }
        }
        return this.strict ? [] : memoryLogs.slice(0, limit);
    }

    // ------------------------------------------------------------
    // Settings are PER ACADEMY without a schema change: rows are stored
    // under the namespaced key "<academyId>:<key>" so two academies can
    // never overwrite or read each other's contact info. Reads fall back
    // to the legacy unprefixed row so data written before this change
    // (single-tenant deployments) keeps working. Writes are always
    // namespaced, so a tenant can never clobber a global/other row.
    // ------------------------------------------------------------
    private scopedSettingKey(key: string): string {
        return `${this.academyId}:${key}`;
    }

    async getSetting(key: string, defaultValue: string = ''): Promise<string> {
        if (this.db) {
            try {
                const record = await this.db
                    .prepare('SELECT value FROM academy_settings WHERE key = ?')
                    .bind(this.scopedSettingKey(key))
                    .first<{ value: string }>();
                if (record?.value !== undefined && record?.value !== null) return record.value;
                // Legacy fallback: settings written before per-academy scoping.
                const legacy = await this.db
                    .prepare('SELECT value FROM academy_settings WHERE key = ?')
                    .bind(key)
                    .first<{ value: string }>();
                if (legacy?.value !== undefined && legacy?.value !== null) return legacy.value;
                return defaultValue;
            } catch (e) {
                console.warn('[DB] D1 getSetting failed:', e);
            }
        }
        return (this.strict ? undefined : memorySettings.get(this.scopedSettingKey(key)))
            ?? (this.strict ? undefined : memorySettings.get(key))
            ?? defaultValue;
    }

    async setSetting(key: string, value: string): Promise<void> {
        const now = Date.now();
        const scoped = this.scopedSettingKey(key);
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO academy_settings (key, value, updated_at_epoch)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at_epoch = excluded.updated_at_epoch
                `).bind(scoped, value, now).run();
            } catch (e) {
                console.warn('[DB] D1 setSetting failed:', e);
            }
        }
        if (!this.strict) memorySettings.set(scoped, value);
    }
}
