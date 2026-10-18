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

    constructor(env: Env, academyId: string = 'default') {
        this.db = env.DB;
        this.kv = env.STUDENT_KV;
        this.academyId = academyId;
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

    async getStudentById(id: string, academyId: string = 'default'): Promise<StudentRecord | null> {
        // Fast-path: Check KV cache first if available
        if (this.kv) {
            try {
                const cached = await this.kv.get(`student:${id}`, 'json');
                if (cached) return cached as StudentRecord;
            } catch (_) {}
        }

        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM students WHERE id = ? AND academy_id = ?').bind(id, academyId);
                const record = await stmt.first<StudentRecord>();
                if (record && this.kv) {
                    await this.kv.put(`student:${id}`, JSON.stringify(record), { expirationTtl: 3600 });
                }
                return record;
            } catch (e) {
                console.warn('[DB] D1 query failed, checking memory store:', e);
            }
        }

        // Memory fallback
        return memoryStudents.get(id) || null;
    }

    async getStudentByCode(studentCode: string): Promise<StudentRecord | null> {
        if (!studentCode) return null;
        if (this.db) {
            try {
                return await this.db.prepare('SELECT * FROM students WHERE student_code = ?').bind(studentCode).first<StudentRecord>();
            } catch (e) {
                console.warn('[DB] D1 getStudentByCode failed:', e);
            }
        }
        for (const s of memoryStudents.values()) {
            if (s.student_code === studentCode) return s;
        }
        return null;
    }

    /** All students that are linked to Telegram (used for admin broadcast). */
    async getAllConnectedStudents(): Promise<StudentRecord[]> {
        if (this.db) {
            try {
                const { results } = await this.db.prepare(
                    "SELECT * FROM students WHERE is_telegram_connected = 1 AND telegram_chat_id != ''"
                ).all<StudentRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 getAllConnectedStudents failed:', e);
            }
        }
        return Array.from(memoryStudents.values()).filter(s => s.is_telegram_connected === 1 && s.telegram_chat_id);
    }

    async getStudentByChatId(chatId: string): Promise<StudentRecord | null> {
        if (!chatId) return null;
        if (this.kv) {
            try {
                const studentId = await this.kv.get(`chat:${chatId}`);
                if (studentId) {
                    const student = await this.getStudentById(studentId);
                    if (student) return student;
                }
            } catch (_) {}
        }

        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM students WHERE telegram_chat_id = ?').bind(chatId);
                const record = await stmt.first<StudentRecord>();
                if (record && this.kv) {
                    await this.kv.put(`chat:${chatId}`, record.id, { expirationTtl: 3600 });
                }
                return record;
            } catch (e) {
                console.warn('[DB] D1 query failed, checking memory store:', e);
            }
        }

        // Memory fallback
        const memStudentId = memoryChatMap.get(chatId);
        if (memStudentId) {
            return memoryStudents.get(memStudentId) || null;
        }
        for (const s of memoryStudents.values()) {
            if (s.telegram_chat_id === chatId) return s;
        }
        return null;
    }

    async upsertStudent(student: Partial<StudentRecord> & { id: string; full_name: string }): Promise<void> {
        const now = Date.now();
        const existing = await this.getStudentById(student.id);

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
                        id, student_code, full_name, phone, national_code,
                        telegram_chat_id, telegram_username, is_telegram_connected,
                        linked_at_epoch, created_at_epoch, updated_at_epoch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        student_code = excluded.student_code,
                        full_name = excluded.full_name,
                        phone = excluded.phone,
                        national_code = excluded.national_code,
                        telegram_chat_id = excluded.telegram_chat_id,
                        telegram_username = excluded.telegram_username,
                        is_telegram_connected = excluded.is_telegram_connected,
                        linked_at_epoch = excluded.linked_at_epoch,
                        updated_at_epoch = excluded.updated_at_epoch
                `).bind(
                    record.id, record.student_code, record.full_name, record.phone, record.national_code,
                    record.telegram_chat_id, record.telegram_username, record.is_telegram_connected,
                    record.linked_at_epoch, record.created_at_epoch, record.updated_at_epoch
                ).run();
            } catch (e) {
                console.warn('[DB] D1 upsertStudent failed:', e);
            }
        }

        // Always update memory store
        memoryStudents.set(record.id, record);
        if (record.telegram_chat_id) {
            memoryChatMap.set(record.telegram_chat_id, record.id);
        }

        // Update KV cache if present
        if (this.kv) {
            try {
                await this.kv.put(`student:${record.id}`, JSON.stringify(record), { expirationTtl: 3600 });
                if (record.telegram_chat_id) {
                    await this.kv.put(`chat:${record.telegram_chat_id}`, record.id, { expirationTtl: 3600 });
                }
            } catch (_) {}
        }
    }

    async linkTelegramChat(studentId: string, chatId: string, username: string): Promise<boolean> {
        const student = await this.getStudentById(studentId);
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
                    WHERE id = ?
                `).bind(chatId, username, now, now, studentId).run();
            } catch (e) {
                console.warn('[DB] D1 linkTelegramChat failed:', e);
            }
        }

        student.telegram_chat_id = chatId;
        student.telegram_username = username;
        student.is_telegram_connected = 1;
        student.updated_at_epoch = now;

        memoryStudents.set(studentId, student);
        memoryChatMap.set(chatId, studentId);

        if (this.kv) {
            try {
                await this.kv.put(`student:${studentId}`, JSON.stringify(student), { expirationTtl: 3600 });
                await this.kv.put(`chat:${chatId}`, studentId, { expirationTtl: 3600 });
            } catch (_) {}
        }
        return true;
    }

    // ============================================================
    // Installments & Financial Summaries
    // ============================================================

    async getInstallmentsForStudent(studentId: string, academyId: string = 'default'): Promise<InstallmentRecord[]> {
        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM installments WHERE student_id = ? AND academy_id = ? ORDER BY due_date_epoch ASC, installment_number ASC').bind(studentId, academyId);
                const { results } = await stmt.all<InstallmentRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 getInstallments failed:', e);
            }
        }
        return memoryInstallments.get(studentId) || [];
    }

    async upsertInstallments(installments: InstallmentRecord[]): Promise<void> {
        if (!installments || installments.length === 0) return;
        const now = Date.now();

        if (this.db) {
            try {
                const stmts = installments.map(inst => this.db!.prepare(`
                    INSERT INTO installments (
                        id, student_id, installment_number, total_installments, course_title,
                        amount, paid_amount, remaining_amount, due_date_shamsi, due_date_epoch,
                        is_paid, paid_date_shamsi, updated_at_epoch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    inst.id, inst.student_id, inst.installment_number, inst.total_installments, inst.course_title,
                    inst.amount, inst.paid_amount, inst.remaining_amount, inst.due_date_shamsi, inst.due_date_epoch,
                    inst.is_paid, inst.paid_date_shamsi, now, now
                ));

                await this.db.batch(stmts);
            } catch (e) {
                console.warn('[DB] D1 upsertInstallments failed:', e);
            }
        }

        const studentId = installments[0].student_id;
        memoryInstallments.set(studentId, installments);
    }

    async getFinancialSummary(studentId: string): Promise<FinancialSummaryRecord | null> {
        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM financial_summaries WHERE student_id = ?').bind(studentId);
                return await stmt.first<FinancialSummaryRecord>();
            } catch (e) {
                console.warn('[DB] D1 getFinancialSummary failed:', e);
            }
        }
        return memoryFinancials.get(studentId) || null;
    }

    async upsertFinancialSummary(summary: FinancialSummaryRecord): Promise<void> {
        const now = Date.now();
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO financial_summaries (
                        student_id, total_tuition, total_paid, remaining_debt,
                        active_courses_count, courses_summary, settlement_status, updated_at_epoch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(student_id) DO UPDATE SET
                        total_tuition = excluded.total_tuition,
                        total_paid = excluded.total_paid,
                        remaining_debt = excluded.remaining_debt,
                        active_courses_count = excluded.active_courses_count,
                        courses_summary = excluded.courses_summary,
                        settlement_status = excluded.settlement_status,
                        updated_at_epoch = excluded.updated_at_epoch
                `).bind(
                    summary.student_id, summary.total_tuition, summary.total_paid, summary.remaining_debt,
                    summary.active_courses_count, summary.courses_summary, summary.settlement_status, now
                ).run();
            } catch (e) {
                console.warn('[DB] D1 upsertFinancialSummary failed:', e);
            }
        }
        memoryFinancials.set(summary.student_id, summary);
    }

    // ============================================================
    // Reminders & Scheduled Messages Queue
    // ============================================================

    async addReminder(reminder: ReminderRecord): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO reminders (
                        id, student_id, student_name, chat_id, installment_id,
                        reminder_type, message_text, scheduled_epoch, scheduled_time_str,
                        status, error_message, retry_count, created_at_epoch, sent_at_epoch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                `).bind(
                    reminder.id, reminder.student_id, reminder.student_name, reminder.chat_id, reminder.installment_id,
                    reminder.reminder_type, reminder.message_text, reminder.scheduled_epoch, reminder.scheduled_time_str,
                    reminder.status, reminder.error_message, reminder.retry_count, reminder.created_at_epoch, reminder.sent_at_epoch
                ).run();
            } catch (e) {
                console.warn('[DB] D1 addReminder failed:', e);
            }
        }
        memoryReminders.set(reminder.id, reminder);
    }

    async getDueReminders(maxEpoch: number, limit: number = 50): Promise<ReminderRecord[]> {
        if (this.db) {
            try {
                const stmt = this.db.prepare(`
                    SELECT * FROM reminders
                    WHERE status = 'SCHEDULED' AND scheduled_epoch <= ?
                    ORDER BY scheduled_epoch ASC
                    LIMIT ?
                `).bind(maxEpoch, limit);
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
                    WHERE id = ?
                `).bind(status, errorMessage, status, now, id).run();
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
                return await this.db.prepare('SELECT * FROM reminders WHERE id = ?').bind(id).first<ReminderRecord>();
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
                    WHERE id = ?
                `).bind(nextEpoch, errorMessage, id).run();
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
                    WHERE installment_id = ? AND status = 'SCHEDULED'
                `).bind(installmentId).run();
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
                    WHERE status = 'SCHEDULED' AND scheduled_epoch < ?
                `).bind(cutoff).run();
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

    async deleteStudent(id: string): Promise<void> {
        if (this.db) {
            try {
                await this.db.batch([
                    this.db.prepare('DELETE FROM reminders WHERE student_id = ?').bind(id),
                    this.db.prepare('DELETE FROM installments WHERE student_id = ?').bind(id),
                    this.db.prepare('DELETE FROM financial_summaries WHERE student_id = ?').bind(id),
                    this.db.prepare('DELETE FROM students WHERE id = ?').bind(id)
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
                        id, student_id, student_name, chat_id, message_type,
                        content, status, error_message, timestamp_epoch, timestamp_shamsi
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                `).bind(
                    log.id, log.student_id, log.student_name, log.chat_id, log.message_type,
                    log.content, log.status, log.error_message, log.timestamp_epoch, log.timestamp_shamsi
                ).run();
            } catch (e) {
                console.warn('[DB] D1 logMessage failed:', e);
            }
        }
        memoryLogs.unshift(log);
        if (memoryLogs.length > 200) memoryLogs.pop();
    }

    async getRecentLogs(limit: number = 100): Promise<MessageLogRecord[]> {
        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT * FROM message_logs ORDER BY timestamp_epoch DESC LIMIT ?').bind(limit);
                const { results } = await stmt.all<MessageLogRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 getRecentLogs failed:', e);
            }
        }
        return memoryLogs.slice(0, limit);
    }

    async getSetting(key: string, defaultValue: string = ''): Promise<string> {
        if (this.db) {
            try {
                const stmt = this.db.prepare('SELECT value FROM academy_settings WHERE key = ?').bind(key);
                const record = await stmt.first<{ value: string }>();
                return record?.value ?? defaultValue;
            } catch (e) {
                console.warn('[DB] D1 getSetting failed:', e);
            }
        }
        return memorySettings.get(key) || defaultValue;
    }

    async setSetting(key: string, value: string): Promise<void> {
        const now = Date.now();
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO academy_settings (key, value, updated_at_epoch)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at_epoch = excluded.updated_at_epoch
                `).bind(key, value, now).run();
            } catch (e) {
                console.warn('[DB] D1 setSetting failed:', e);
            }
        }
        memorySettings.set(key, value);
    }
}
