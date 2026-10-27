// ============================================================
// Database & Storage Layer using Cloudflare D1 and KV (Graceful Fallback)
// ============================================================

import {
    Env,
    StudentRecord,
    InstallmentRecord,
    FinancialSummaryRecord,
    ReminderRecord,
    MessageLogRecord,
    PaymentReceiptRecord,
    PaymentTxnRecord,
    PaymentClaimRecord,
    PaymentEventRecord
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
        // A freshly rotated link (new hash pushed by the app or a manager
        // re-issue) is UNUSED again: the old link died, the new one must be
        // claimable exactly once.
        const linkWasRotated = incomingLinkHash !== '' && incomingLinkHash !== ((existing as any)?.telegram_link_hash || '');

        if (this.db) {
            try {
                // Migration 0012 may not be applied yet (deploy-order safety):
                // the used-flag re-arm clause is only emitted when the column exists.
                const usedCol = await this.hasLinkUsedColumn();
                const usedClause = usedCol
                    ? ', telegram_link_used = CASE WHEN ? = 1 THEN 0 ELSE students.telegram_link_used END'
                    : '';
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
                        telegram_link_expires = excluded.telegram_link_expires${usedClause}
                    WHERE academy_id = excluded.academy_id
                `).bind(
                    record.id, this.academyId, record.student_code, record.full_name, record.phone, record.national_code,
                    record.telegram_chat_id, record.telegram_username, record.is_telegram_connected,
                    record.linked_at_epoch, record.created_at_epoch, record.updated_at_epoch,
                    storedLinkHash, storedLinkExpires,
                    ...(usedCol ? [linkWasRotated ? 1 : 0] : [])
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

    /** Stores (or rotates) the /start link token of one student (hash + expiry).
     *  A rotation makes the link claimable again (used = 0): the previous link
     *  died with the old hash, the new one must work exactly once. */
    async setTelegramLinkHash(studentId: string, linkHash: string, expiresAtEpoch: number): Promise<void> {
        if (!this.db) return;
        try {
            await this.db.prepare(
                'UPDATE students SET telegram_link_hash = ?, telegram_link_expires = ?, telegram_link_used = 0, updated_at_epoch = ? WHERE id = ? AND academy_id = ?'
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

    /**
     * Migration-0012 graceful degradation: a database that has not received
     * the telegram_link_used column yet (deploy-order safety) must keep
     * working — the code falls back to the legacy non-atomic claim until the
     * migration is applied. The probe result is cached per instance.
     */
    private linkUsedColumn: boolean | null = null;
    private async hasLinkUsedColumn(): Promise<boolean> {
        if (this.linkUsedColumn !== null) return this.linkUsedColumn;
        if (!this.db) {
            this.linkUsedColumn = false;
            return false;
        }
        try {
            await this.db.prepare('SELECT telegram_link_used FROM students LIMIT 1').first();
            this.linkUsedColumn = true;
        } catch {
            this.linkUsedColumn = false;
        }
        return this.linkUsedColumn;
    }

    /**
     * Atomic one-time link claim (migration 0012): binds the chat AND burns the
     * token in a single conditional UPDATE. Exactly one caller can ever see
     * changes === 1, so two accounts racing on the same /start link cannot both
     * bind — the loser is told the link was just used by someone else.
     * The hash stays on the row (with used=1) purely so a replay can be
     * reported as "already used"; a used hash can never bind again.
     * On a database without migration 0012 the legacy two-step path runs so
     * connecting keeps working until the migration lands.
     */
    async claimTelegramLink(studentId: string, chatId: string, username: string, linkHash: string): Promise<boolean> {
        const now = Date.now();
        const hash = (linkHash || '').trim().toLowerCase();
        if (!hash) return false;

        if (this.db) {
            try {
                if (!(await this.hasLinkUsedColumn())) {
                    // Legacy schema: bind + burn as two statements (pre-0012 semantics).
                    await this.linkTelegramChat(studentId, chatId, username);
                    await this.consumeTelegramLinkToken(studentId);
                    return true;
                }
                const res = await this.db.prepare(`
                    UPDATE students SET
                        telegram_chat_id = ?,
                        telegram_username = ?,
                        is_telegram_connected = 1,
                        linked_at_epoch = CASE WHEN linked_at_epoch > 0 THEN linked_at_epoch ELSE ? END,
                        telegram_link_used = 1,
                        telegram_link_expires = 0,
                        updated_at_epoch = ?
                    WHERE id = ? AND academy_id = ?
                      AND telegram_link_hash = ?
                      AND telegram_link_used = 0
                      AND (telegram_link_expires = 0 OR telegram_link_expires > ?)
                `).bind(chatId, username, now, now, studentId, this.academyId, hash, now).run();
                const changes = Number((res as any)?.meta?.changes ?? (res as any)?.changes ?? 0);
                if (changes !== 1) return false;
            } catch (e) {
                console.warn('[DB] D1 claimTelegramLink failed:', e);
                return false;
            }
        }

        const student = await this.getStudentById(studentId, this.academyId);
        if (!student) return false;
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

    /** True when the link token of this student row was already consumed once. */
    isLinkUsed(student: StudentRecord | null | undefined): boolean {
        // Pre-0012 databases carry no used flag: nothing is ever "used" there.
        if (this.linkUsedColumn === false) return false;
        return Number((student as any)?.telegram_link_used || 0) === 1;
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
                        is_paid, paid_date_shamsi, payment_method, updated_at_epoch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        payment_method = excluded.payment_method,
                        updated_at_epoch = ?
                    WHERE academy_id = excluded.academy_id
                `).bind(
                    inst.id, this.academyId, inst.student_id, inst.installment_number, inst.total_installments, inst.course_title,
                    inst.amount, inst.paid_amount, inst.remaining_amount, inst.due_date_shamsi, inst.due_date_epoch,
                    inst.is_paid, inst.paid_date_shamsi, (inst as any).payment_method || '', now, now
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
                    WHERE academy_id = excluded.academy_id
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
                        status, error_message, retry_count, created_at_epoch, sent_at_epoch, student_phone,
                        trigger_offset, schedule_source
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                `).bind(
                    reminder.id, this.academyId, reminder.student_id, reminder.student_name, reminder.chat_id, reminder.installment_id,
                    reminder.reminder_type, reminder.message_text, reminder.scheduled_epoch, reminder.scheduled_time_str,
                    reminder.status, reminder.error_message, reminder.retry_count, reminder.created_at_epoch, reminder.sent_at_epoch,
                    (reminder as any).student_phone || '',
                    Number((reminder as any).trigger_offset ?? 0) || 0,
                    (reminder as any).schedule_source || 'app'
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

    async cancelPendingRemindersForInstallment(installmentId: string): Promise<number> {
        if (!installmentId) return 0;
        let cancelled = 0;
        if (this.db) {
            try {
                const res = await this.db.prepare(`
                    UPDATE reminders SET status = 'CANCELLED', error_message = 'Installment already paid'
                    WHERE installment_id = ? AND status = 'SCHEDULED' AND academy_id = ?
                `).bind(installmentId, this.academyId).run();
                cancelled = res.meta?.changes || 0;
            } catch (e) {
                console.warn('[DB] D1 cancelPendingReminders failed:', e);
            }
        }
        for (const r of memoryReminders.values()) {
            if (r.installment_id === installmentId && r.status === 'SCHEDULED') {
                r.status = 'CANCELLED';
                cancelled++;
            }
        }
        return cancelled;
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

    // ============================================================
    // Stage 4: installments lookup / post-payment update
    // ============================================================

    async getInstallmentById(id: string, academyId: string = this.academyId): Promise<InstallmentRecord | null> {
        if (!id) return null;
        if (this.db) {
            try {
                return await this.db
                    .prepare('SELECT * FROM installments WHERE id = ? AND academy_id = ?')
                    .bind(id, academyId)
                    .first<InstallmentRecord>() ?? null;
            } catch (e) {
                console.warn('[DB] D1 getInstallmentById failed:', e);
            }
        }
        if (this.strict) return null;
        for (const list of memoryInstallments.values()) {
            const hit = list.find((i) => i.id === id);
            if (hit) return hit;
        }
        return null;
    }

    /** Post-payment update after a VERIFIED online payment (auto, no manual re-entry). */
    async updateInstallmentAfterPayment(
        id: string,
        academyId: string,
        patch: { paid_amount: number; remaining_amount: number; is_paid: number; paid_date_shamsi: string }
    ): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    UPDATE installments
                    SET paid_amount = ?, remaining_amount = ?, is_paid = ?, paid_date_shamsi = ?, updated_at_epoch = ?
                    WHERE id = ? AND academy_id = ?
                `).bind(patch.paid_amount, patch.remaining_amount, patch.is_paid, patch.paid_date_shamsi, Date.now(), id, academyId).run();
            } catch (e) {
                console.warn('[DB] D1 updateInstallmentAfterPayment failed:', e);
            }
        }
    }

    // ============================================================
    // Stage 4: payment receipts (one row per app payment id — dedupe by PK)
    // ============================================================

    async insertPaymentReceipt(r: PaymentReceiptRecord): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO payment_receipts (
                        id, academy_id, student_id, installment_id, installment_number, course_title,
                        transaction_id, tracking_number, amount, student_name, paid_date_shamsi, paid_time,
                        payment_method, destination_account, notes, is_reversed, reversal_reason, source,
                        receipt_json, send_status, send_reason, eligible_at_epoch, attempts,
                        last_attempt_epoch, telegram_message_id, last_error, created_at_epoch, updated_at_epoch
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                `).bind(
                    r.id, r.academy_id, r.student_id, r.installment_id, r.installment_number, r.course_title,
                    r.transaction_id, r.tracking_number, r.amount, r.student_name, r.paid_date_shamsi, r.paid_time,
                    r.payment_method, r.destination_account, r.notes, r.is_reversed, r.reversal_reason, r.source,
                    r.receipt_json, r.send_status, r.send_reason, r.eligible_at_epoch, r.attempts,
                    r.last_attempt_epoch, r.telegram_message_id, r.last_error, r.created_at_epoch, r.updated_at_epoch
                ).run();
            } catch (e) {
                console.warn('[DB] D1 insertPaymentReceipt failed:', e);
            }
        }
    }

    async getPaymentReceipt(id: string, academyId: string = this.academyId): Promise<PaymentReceiptRecord | null> {
        if (!id) return null;
        if (this.db) {
            try {
                return await this.db
                    .prepare('SELECT * FROM payment_receipts WHERE id = ? AND academy_id = ?')
                    .bind(id, academyId)
                    .first<PaymentReceiptRecord>() ?? null;
            } catch (e) {
                console.warn('[DB] D1 getPaymentReceipt failed:', e);
            }
        }
        return null;
    }

    async updatePaymentReceipt(id: string, academyId: string, patch: Partial<PaymentReceiptRecord>): Promise<void> {
        const sets: string[] = [];
        const vals: unknown[] = [];
        for (const [k, v] of Object.entries(patch)) {
            sets.push(`${k} = ?`);
            vals.push(v);
        }
        if (!sets.length) return;
        sets.push('updated_at_epoch = ?');
        vals.push(Date.now(), id, academyId);
        if (this.db) {
            try {
                await this.db.prepare(`UPDATE payment_receipts SET ${sets.join(', ')} WHERE id = ? AND academy_id = ?`)
                    .bind(...(vals as never[])).run();
            } catch (e) {
                console.warn('[DB] D1 updatePaymentReceipt failed:', e);
            }
        }
    }

    /** All receipts of one student (bot menu "رسیدهای من") — never another student's. */
    async listPaymentReceiptsForStudent(academyId: string, studentId: string): Promise<PaymentReceiptRecord[]> {
        if (this.db) {
            try {
                const { results } = await this.db.prepare(
                    'SELECT * FROM payment_receipts WHERE academy_id = ? AND student_id = ? ORDER BY created_at_epoch DESC LIMIT 50'
                ).bind(academyId, studentId).all<PaymentReceiptRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listPaymentReceiptsForStudent failed:', e);
            }
        }
        return [];
    }

    async listPaymentReceiptsByStatus(academyId: string, status: string, studentId?: string): Promise<PaymentReceiptRecord[]> {
        if (this.db) {
            try {
                const { results } = studentId
                    ? await this.db.prepare('SELECT * FROM payment_receipts WHERE academy_id = ? AND send_status = ? AND student_id = ?')
                        .bind(academyId, status, studentId).all<PaymentReceiptRecord>()
                    : await this.db.prepare('SELECT * FROM payment_receipts WHERE academy_id = ? AND send_status = ?')
                        .bind(academyId, status).all<PaymentReceiptRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listPaymentReceiptsByStatus failed:', e);
            }
        }
        return [];
    }

    /** Dispatch queue: QUEUED (incl. reversal notices) whose eligibility time has passed. */
    async listDuePaymentReceipts(academyId: string, now: number, limit: number): Promise<PaymentReceiptRecord[]> {
        if (this.db) {
            try {
                const { results } = await this.db.prepare(`
                    SELECT * FROM payment_receipts
                    WHERE academy_id = ? AND send_status IN ('QUEUED', 'REVERSAL_QUEUED') AND eligible_at_epoch <= ?
                    ORDER BY eligible_at_epoch ASC LIMIT ?
                `).bind(academyId, now, limit).all<PaymentReceiptRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listDuePaymentReceipts failed:', e);
            }
        }
        return [];
    }

    // ============================================================
    // Stage 4: online payment transactions (link ledger)
    // ============================================================

    async insertPaymentTransaction(t: PaymentTxnRecord): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO payment_transactions (
                        id, academy_id, student_id, installment_id, amount, status, provider,
                        provider_ref, link_token_hash, redirect_url, link_created_at_epoch, expires_at_epoch,
                        paid_amount, verified_payload, verified_at_epoch, receipt_id, created_at_epoch, updated_at_epoch
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                `).bind(
                    t.id, t.academy_id, t.student_id, t.installment_id, t.amount, t.status, t.provider,
                    t.provider_ref, t.link_token_hash, t.redirect_url, t.link_created_at_epoch, t.expires_at_epoch,
                    t.paid_amount, t.verified_payload, t.verified_at_epoch, t.receipt_id, t.created_at_epoch, t.updated_at_epoch
                ).run();
            } catch (e) {
                console.warn('[DB] D1 insertPaymentTransaction failed:', e);
            }
        }
    }

    async getPaymentTransaction(id: string, academyId: string = this.academyId): Promise<PaymentTxnRecord | null> {
        if (!id) return null;
        if (this.db) {
            try {
                return await this.db
                    .prepare('SELECT * FROM payment_transactions WHERE id = ? AND academy_id = ?')
                    .bind(id, academyId)
                    .first<PaymentTxnRecord>() ?? null;
            } catch (e) {
                console.warn('[DB] D1 getPaymentTransaction failed:', e);
            }
        }
        return null;
    }

    async updatePaymentTransaction(id: string, academyId: string, patch: Partial<PaymentTxnRecord>): Promise<void> {
        const sets: string[] = [];
        const vals: unknown[] = [];
        for (const [k, v] of Object.entries(patch)) {
            sets.push(`${k} = ?`);
            vals.push(v);
        }
        if (!sets.length) return;
        sets.push('updated_at_epoch = ?');
        vals.push(Date.now(), id, academyId);
        if (this.db) {
            try {
                await this.db.prepare(`UPDATE payment_transactions SET ${sets.join(', ')} WHERE id = ? AND academy_id = ?`)
                    .bind(...(vals as never[])).run();
            } catch (e) {
                console.warn('[DB] D1 updatePaymentTransaction failed:', e);
            }
        }
    }

    /** CAS status transition for gateway verification: the row moves to
     *  `patch.status` ONLY from `fromStatus`. Returns false when another
     *  concurrent verifier won the race (no partial double-apply). */
    async transitionPaymentTransaction(
        id: string, academyId: string, fromStatus: string, patch: Partial<PaymentTxnRecord>
    ): Promise<boolean> {
        const sets: string[] = [];
        const vals: unknown[] = [];
        for (const [k, v] of Object.entries(patch)) {
            sets.push(`${k} = ?`);
            vals.push(v);
        }
        if (!sets.length) return false;
        sets.push('updated_at_epoch = ?');
        vals.push(Date.now(), id, academyId, fromStatus);
        if (this.db) {
            try {
                const res = await this.db.prepare(
                    `UPDATE payment_transactions SET ${sets.join(', ')} WHERE id = ? AND academy_id = ? AND status = ?`
                ).bind(...(vals as never[])).run();
                return (res.meta?.changes ?? 0) > 0;
            } catch (e) {
                console.warn('[DB] D1 transitionPaymentTransaction failed:', e);
                return false;
            }
        }
        return false;
    }

    async findActiveTxnForInstallment(academyId: string, installmentId: string, now: number): Promise<PaymentTxnRecord | null> {
        if (this.db) {
            try {
                return await this.db.prepare(`
                    SELECT * FROM payment_transactions
                    WHERE academy_id = ? AND installment_id = ? AND status = 'PENDING' AND expires_at_epoch > ?
                    ORDER BY created_at_epoch DESC LIMIT 1
                `).bind(academyId, installmentId, now).first<PaymentTxnRecord>() ?? null;
            } catch (e) {
                console.warn('[DB] D1 findActiveTxnForInstallment failed:', e);
            }
        }
        return null;
    }

    async findTxnByTokenHash(hash: string): Promise<PaymentTxnRecord | null> {
        if (!hash) return null;
        if (this.db) {
            try {
                return await this.db
                    .prepare('SELECT * FROM payment_transactions WHERE link_token_hash = ?')
                    .bind(hash)
                    .first<PaymentTxnRecord>() ?? null;
            } catch (e) {
                console.warn('[DB] D1 findTxnByTokenHash failed:', e);
            }
        }
        return null;
    }

    /** Confirmed (PAID) online payments the app has not pulled yet — auto-sync into Room. */
    async listConfirmedTxnsSince(academyId: string, since: number, limit = 200): Promise<PaymentTxnRecord[]> {
        if (this.db) {
            try {
                const { results } = await this.db.prepare(`
                    SELECT * FROM payment_transactions
                    WHERE academy_id = ? AND status = 'PAID' AND verified_at_epoch > ?
                    ORDER BY verified_at_epoch ASC LIMIT ?
                `).bind(academyId, since, limit).all<PaymentTxnRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listConfirmedTxnsSince failed:', e);
            }
        }
        return [];
    }

    // ---- link token registry (raw token kept ONLY to re-send the SAME link) ----

    async insertLinkToken(token: string, hash: string, academyId: string, orderId: string, expiresAt: number): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO payment_link_tokens (token, token_hash, academy_id, order_id, expires_at_epoch, created_at_epoch)
                    VALUES (?,?,?,?,?,?)
                `).bind(token, hash, academyId, orderId, expiresAt, Date.now()).run();
            } catch (e) {
                console.warn('[DB] D1 insertLinkToken failed:', e);
            }
        }
    }

    async getLinkTokenByHash(hash: string): Promise<string> {
        if (!hash || !this.db) return '';
        try {
            const row = await this.db
                .prepare('SELECT token, expires_at_epoch FROM payment_link_tokens WHERE token_hash = ?')
                .bind(hash)
                .first<{ token: string; expires_at_epoch: number }>();
            if (!row) return '';
            if (row.expires_at_epoch > 0 && row.expires_at_epoch < Date.now()) return '';
            return row.token;
        } catch (e) {
            console.warn('[DB] D1 getLinkTokenByHash failed:', e);
            return '';
        }
    }

    // ============================================================
    // Stage 5 (migration 0010): server-scheduled reminders, claims, events
    // ============================================================

    /** Every reminder row of ONE installment (both sources, any status). */
    async listRemindersForInstallment(installmentId: string, academyId: string = this.academyId): Promise<ReminderRecord[]> {
        if (!installmentId) return [];
        if (this.db) {
            try {
                const { results } = await this.db.prepare(
                    'SELECT * FROM reminders WHERE installment_id = ? AND academy_id = ? ORDER BY scheduled_epoch ASC'
                ).bind(installmentId, academyId).all<ReminderRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listRemindersForInstallment failed:', e);
            }
        }
        const rows: ReminderRecord[] = [];
        for (const r of memoryReminders.values()) {
            if (r.installment_id === installmentId) rows.push(r);
        }
        return rows;
    }

    /** The SCHEDULED row of one (installment, offset) nob, any source. */
    async findScheduledReminderForOffset(
        installmentId: string,
        triggerOffset: number,
        academyId: string = this.academyId
    ): Promise<ReminderRecord | null> {
        if (!installmentId) return null;
        if (this.db) {
            try {
                const row = await this.db.prepare(`
                    SELECT * FROM reminders
                    WHERE installment_id = ? AND academy_id = ? AND trigger_offset = ? AND status = 'SCHEDULED'
                    ORDER BY CASE WHEN schedule_source = 'server' THEN 0 ELSE 1 END, created_at_epoch ASC
                    LIMIT 1
                `).bind(installmentId, academyId, triggerOffset).first<ReminderRecord>();
                return row ?? null;
            } catch (e) {
                console.warn('[DB] D1 findScheduledReminderForOffset failed:', e);
            }
        }
        for (const r of memoryReminders.values()) {
            if (r.installment_id === installmentId
                && (r as any).trigger_offset === triggerOffset
                && r.status === 'SCHEDULED') return r;
        }
        return null;
    }

    // ============================================================
    // Stage 6: daily-overdue continuation + manager reminder view
    // ============================================================

    /**
     * UNPAID installments whose due date has passed (oldest first) — the
     * input of the daily-overdue continuation of the SAME Stage-5 scheduler
     * (no separate system). Bounded per tick.
     */
    async listOverdueUnpaidInstallments(
        academyId: string = this.academyId,
        nowEpoch: number = Date.now(),
        limit: number = 100
    ): Promise<InstallmentRecord[]> {
        if (this.db) {
            try {
                const { results } = await this.db.prepare(`
                    SELECT * FROM installments
                    WHERE academy_id = ? AND is_paid = 0 AND remaining_amount > 0
                      AND due_date_epoch > 0 AND due_date_epoch < ?
                    ORDER BY due_date_epoch ASC
                    LIMIT ?
                `).bind(academyId, nowEpoch, Math.min(500, Math.max(1, limit))).all<InstallmentRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listOverdueUnpaidInstallments failed:', e);
            }
        }
        if (this.strict) return [];
        const out: InstallmentRecord[] = [];
        for (const list of memoryInstallments.values()) {
            for (const i of list) {
                if (i.is_paid === 0 && (i.remaining_amount || 0) > 0
                    && (i.due_date_epoch || 0) > 0 && i.due_date_epoch < nowEpoch) {
                    out.push(i);
                    if (out.length >= limit) return out;
                }
            }
        }
        return out;
    }

    /**
     * Manager reminder-status view: all rows of one installment, one student
     * or the whole academy (bounded), any status — the data behind
     * GET /api/messaging/reminders (schedule, last send, status, error).
     */
    async listRemindersForView(
        academyId: string = this.academyId,
        opts: { studentId?: string; installmentId?: string; limit?: number } = {}
    ): Promise<ReminderRecord[]> {
        const limit = Math.min(500, Math.max(1, opts.limit ?? 200));
        if (this.db) {
            try {
                if (opts.installmentId) {
                    const { results } = await this.db.prepare(
                        'SELECT * FROM reminders WHERE academy_id = ? AND installment_id = ? ORDER BY scheduled_epoch ASC LIMIT ?'
                    ).bind(academyId, opts.installmentId, limit).all<ReminderRecord>();
                    return results || [];
                }
                if (opts.studentId) {
                    const { results } = await this.db.prepare(
                        'SELECT * FROM reminders WHERE academy_id = ? AND student_id = ? ORDER BY scheduled_epoch DESC LIMIT ?'
                    ).bind(academyId, opts.studentId, limit).all<ReminderRecord>();
                    return results || [];
                }
                const { results } = await this.db.prepare(
                    'SELECT * FROM reminders WHERE academy_id = ? ORDER BY scheduled_epoch DESC LIMIT ?'
                ).bind(academyId, limit).all<ReminderRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listRemindersForView failed:', e);
            }
        }
        if (this.strict) return [];
        const rows: ReminderRecord[] = [];
        for (const r of memoryReminders.values()) {
            if (opts.installmentId && r.installment_id !== opts.installmentId) continue;
            if (opts.studentId && r.student_id !== opts.studentId) continue;
            rows.push(r);
            if (rows.length >= limit) break;
        }
        return rows;
    }

    /** Rewrites schedule + message of a SCHEDULED row (due-date reconciliation). */
    async rescheduleReminder(id: string, scheduledEpoch: number, timeStr: string, messageText: string): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    UPDATE reminders SET scheduled_epoch = ?, scheduled_time_str = ?, message_text = ?
                    WHERE id = ? AND academy_id = ? AND status = 'SCHEDULED'
                `).bind(scheduledEpoch, timeStr, messageText, id, this.academyId).run();
            } catch (e) {
                console.warn('[DB] D1 rescheduleReminder failed:', e);
            }
        }
        const r = memoryReminders.get(id);
        if (r && r.status === 'SCHEDULED') {
            r.scheduled_epoch = scheduledEpoch;
            r.scheduled_time_str = timeStr;
            r.message_text = messageText;
        }
    }

    /**
     * Revives a deterministic nob that was cancelled/failed but NEVER
     * delivered (e.g. the installment went paid → unpaid after a reversal, or
     * the due date moved). SENT rows are deliberately untouchable — a nob that
     * already reached the student must never fire twice.
     */
    async reviveReminder(id: string, scheduledEpoch: number, timeStr: string, messageText: string): Promise<boolean> {
        let changed = 0;
        if (this.db) {
            try {
                const res = await this.db.prepare(`
                    UPDATE reminders SET scheduled_epoch = ?, scheduled_time_str = ?, message_text = ?,
                        status = 'SCHEDULED', error_message = '', retry_count = 0, sent_at_epoch = 0
                    WHERE id = ? AND academy_id = ? AND status IN ('CANCELLED', 'FAILED')
                `).bind(scheduledEpoch, timeStr, messageText, id, this.academyId).run();
                changed = res.meta?.changes || 0;
            } catch (e) {
                console.warn('[DB] D1 reviveReminder failed:', e);
            }
        }
        const r = memoryReminders.get(id);
        if (r && (r.status === 'CANCELLED' || r.status === 'FAILED')) {
            r.status = 'SCHEDULED';
            r.scheduled_epoch = scheduledEpoch;
            r.scheduled_time_str = timeStr;
            r.message_text = messageText;
            r.error_message = '';
            r.retry_count = 0;
            r.sent_at_epoch = 0;
            changed++;
        }
        return changed > 0;
    }

    // ---- payment claims (student-submitted bank-transfer receipts) ----

    async insertPaymentClaim(c: PaymentClaimRecord): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO payment_claims (
                        id, academy_id, student_id, student_name, installment_id, installment_number,
                        course_title, claimed_amount, note, photo_bytes, photo_file_id, status,
                        decided_by, decision_note, decided_at_epoch, payment_id, telegram_chat_id,
                        manager_message_id, manager_chat_id, created_at_epoch, updated_at_epoch
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                `).bind(
                    c.id, this.academyId, c.student_id, c.student_name, c.installment_id, c.installment_number,
                    c.course_title, c.claimed_amount, c.note, c.photo_bytes, c.photo_file_id, c.status,
                    c.decided_by, c.decision_note, c.decided_at_epoch, c.payment_id, c.telegram_chat_id,
                    c.manager_message_id, c.manager_chat_id, c.created_at_epoch, c.updated_at_epoch
                ).run();
            } catch (e) {
                console.warn('[DB] D1 insertPaymentClaim failed:', e);
            }
        }
    }

    async getPaymentClaim(id: string, academyId: string = this.academyId): Promise<PaymentClaimRecord | null> {
        if (!id) return null;
        if (this.db) {
            try {
                const row = await this.db
                    .prepare('SELECT * FROM payment_claims WHERE id = ? AND academy_id = ?')
                    .bind(id, academyId)
                    .first<PaymentClaimRecord>();
                return row ?? null;
            } catch (e) {
                console.warn('[DB] D1 getPaymentClaim failed:', e);
            }
        }
        return null;
    }

    async updatePaymentClaim(id: string, academyId: string, patch: Partial<PaymentClaimRecord>): Promise<void> {
        const allowed = [
            'status', 'decided_by', 'decision_note', 'decided_at_epoch', 'payment_id',
            'manager_message_id', 'manager_chat_id', 'photo_file_id', 'updated_at_epoch'
        ] as const;
        const sets: string[] = [];
        const vals: unknown[] = [];
        for (const k of allowed) {
            if (patch[k] !== undefined) { sets.push(`${k} = ?`); vals.push(patch[k] as unknown); }
        }
        if (!sets.length) return;
        if (!sets.includes('updated_at_epoch = ?')) { sets.push('updated_at_epoch = ?'); vals.push(Date.now()); }
        vals.push(id, academyId);
        if (this.db) {
            try {
                await this.db.prepare(
                    `UPDATE payment_claims SET ${sets.join(', ')} WHERE id = ? AND academy_id = ?`
                ).bind(...vals).run();
            } catch (e) {
                console.warn('[DB] D1 updatePaymentClaim failed:', e);
            }
        }
    }

    /**
     * ATOMIC decision claim (CAS): flips a PENDING claim to APPROVED/REJECTED
     * exactly once. Two managers clicking simultaneously (or a double click,
     * or a webhook replay) can never both win — the loser reads changes = 0
     * and gets the "already decided" answer.
     */
    async decidePaymentClaim(
        id: string,
        academyId: string,
        decision: 'APPROVED' | 'REJECTED',
        managerChatId: string,
        decisionNote: string,
        paymentId: string
    ): Promise<boolean> {
        if (!this.db) return false;
        try {
            const res = await this.db.prepare(`
                UPDATE payment_claims SET
                    status = ?, decided_by = ?, decision_note = ?, decided_at_epoch = ?,
                    payment_id = ?, updated_at_epoch = ?
                WHERE id = ? AND academy_id = ? AND status = 'PENDING'
            `).bind(
                decision, managerChatId, decisionNote || '', Date.now(), paymentId || '',
                Date.now(), id, academyId
            ).run();
            return (res.meta?.changes || 0) > 0;
        } catch (e) {
            console.warn('[DB] D1 decidePaymentClaim failed:', e);
            return false;
        }
    }

    /** Open claims of one installment (newest first) — drives the bot statuses. */
    async listClaimsForInstallment(
        installmentId: string,
        statuses: string[] = ['PENDING'],
        academyId: string = this.academyId
    ): Promise<PaymentClaimRecord[]> {
        if (!installmentId) return [];
        if (this.db) {
            try {
                const marks = statuses.map(() => '?').join(',');
                const { results } = await this.db.prepare(`
                    SELECT * FROM payment_claims
                    WHERE installment_id = ? AND academy_id = ? AND status IN (${marks})
                    ORDER BY created_at_epoch DESC
                `).bind(installmentId, academyId, ...statuses).all<PaymentClaimRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listClaimsForInstallment failed:', e);
            }
        }
        return [];
    }

    async listPendingClaims(academyId: string = this.academyId, limit = 20): Promise<PaymentClaimRecord[]> {
        if (this.db) {
            try {
                const { results } = await this.db.prepare(`
                    SELECT * FROM payment_claims
                    WHERE academy_id = ? AND status = 'PENDING'
                    ORDER BY created_at_epoch ASC LIMIT ?
                `).bind(academyId, limit).all<PaymentClaimRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listPendingClaims failed:', e);
            }
        }
        return [];
    }

    /** APPROVED claims since a cursor epoch — feeds the app's confirmed-payment pull. */
    async listApprovedClaimsSince(academyId: string, since: number, limit = 200): Promise<PaymentClaimRecord[]> {
        if (this.db) {
            try {
                const { results } = await this.db.prepare(`
                    SELECT pc.*, COALESCE(r.amount, pc.claimed_amount) AS applied_amount
                    FROM payment_claims pc
                    LEFT JOIN payment_receipts r ON r.id = pc.payment_id
                    WHERE pc.academy_id = ? AND pc.status = 'APPROVED' AND pc.decided_at_epoch > ?
                    ORDER BY pc.decided_at_epoch ASC LIMIT ?
                `).bind(academyId, since, limit).all<PaymentClaimRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listApprovedClaimsSince failed:', e);
            }
        }
        return [];
    }

    /** Finds a PENDING claim by the manager-notification message id (reply-based reason). */
    async findPendingClaimByManagerMessage(
        managerChatId: string,
        messageId: number,
        academyId: string = this.academyId
    ): Promise<PaymentClaimRecord | null> {
        if (!this.db) return null;
        try {
            const row = await this.db.prepare(`
                SELECT * FROM payment_claims
                WHERE academy_id = ? AND manager_chat_id = ? AND manager_message_id = ? AND status = 'PENDING'
                LIMIT 1
            `).bind(academyId, managerChatId, String(messageId)).first<PaymentClaimRecord>();
            return row ?? null;
        } catch (e) {
            console.warn('[DB] D1 findPendingClaimByManagerMessage failed:', e);
            return null;
        }
    }

    // ---- payment lifecycle events (report backbone) ----

    async insertPaymentEvent(e: PaymentEventRecord): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT INTO payment_events (
                        id, academy_id, student_id, installment_id, payment_id, claim_id,
                        event_type, channel, actor, amount, detail, created_at_epoch
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                `).bind(
                    e.id, this.academyId, e.student_id, e.installment_id, e.payment_id, e.claim_id,
                    e.event_type, e.channel, e.actor, e.amount, e.detail, e.created_at_epoch
                ).run();
            } catch (err) {
                console.warn('[DB] D1 insertPaymentEvent failed:', err);
            }
        }
    }

    async listPaymentEvents(
        opts: { studentId?: string; installmentId?: string; limit?: number } = {},
        academyId: string = this.academyId
    ): Promise<PaymentEventRecord[]> {
        const limit = Math.min(500, Math.max(1, opts.limit || 100));
        if (this.db) {
            try {
                const where = ['academy_id = ?'];
                const vals: unknown[] = [academyId];
                if (opts.studentId) { where.push('student_id = ?'); vals.push(opts.studentId); }
                if (opts.installmentId) { where.push('installment_id = ?'); vals.push(opts.installmentId); }
                vals.push(limit);
                const { results } = await this.db.prepare(`
                    SELECT * FROM payment_events WHERE ${where.join(' AND ')}
                    ORDER BY created_at_epoch DESC LIMIT ?
                `).bind(...vals).all<PaymentEventRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listPaymentEvents failed:', e);
            }
        }
        return [];
    }

    /** Open (PENDING) claims of one STUDENT — one live claim per installment. */
    async listPendingClaimsForStudent(studentId: string, academyId: string = this.academyId): Promise<PaymentClaimRecord[]> {
        if (!studentId) return [];
        if (this.db) {
            try {
                const { results } = await this.db.prepare(`
                    SELECT * FROM payment_claims
                    WHERE student_id = ? AND academy_id = ? AND status = 'PENDING'
                    ORDER BY created_at_epoch DESC
                `).bind(studentId, academyId).all<PaymentClaimRecord>();
                return results || [];
            } catch (e) {
                console.warn('[DB] D1 listPendingClaimsForStudent failed:', e);
            }
        }
        return [];
    }

    /** Idempotent SMS outbox row (real sending stays DISABLED until a panel is connected). */
    async insertSmsOutbox(
        id: string, academyId: string, reminderId: string, phone: string,
        body: string, status: string, error: string, now: number
    ): Promise<void> {
        if (!phone) return;
        if (this.db) {
            try {
                await this.db.prepare(`
                    INSERT OR IGNORE INTO sms_outbox (id, academy_id, reminder_id, phone, body, status, error, created_at_epoch)
                    VALUES (?,?,?,?,?,?,?,?)
                `).bind(id, academyId, reminderId, phone, body, status, error, now).run();
            } catch (e) {
                console.warn('[DB] D1 insertSmsOutbox failed:', e);
            }
        }
    }

    /** Tracks the SMS channel status on the reminder row itself (independent of Telegram). */
    async updateSmsStatus(academyId: string, reminderId: string, status: string, error = ''): Promise<void> {
        if (this.db) {
            try {
                await this.db.prepare(`
                    UPDATE reminders SET sms_status = ?, sms_error = ?, sms_attempts = sms_attempts + 1,
                        sms_sent_at_epoch = CASE WHEN ? = 'SENT' THEN ? ELSE sms_sent_at_epoch END
                    WHERE academy_id = ? AND id = ?
                `).bind(status, error, status, Date.now(), academyId, reminderId).run();
            } catch (e) {
                console.warn('[DB] D1 updateSmsStatus failed:', e);
            }
        }
    }

    /** Stage 9: final status of ONE sms_outbox row (welcome / event copies). */
    async updateSmsOutboxStatus(id: string, academyId: string, status: string, error = ''): Promise<void> {
        if (!this.db) return;
        try {
            await this.db.prepare(
                'UPDATE sms_outbox SET status = ?, error = ? WHERE id = ? AND academy_id = ?'
            ).bind(status, error, id, academyId).run();
        } catch (e) {
            console.warn('[DB] D1 updateSmsOutboxStatus failed:', e);
        }
    }
}
