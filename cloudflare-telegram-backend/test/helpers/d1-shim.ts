// ============================================================
// Minimal D1-compatible shim over better-sqlite3 (D1 IS SQLite).
// Supports exactly the API surface the Worker uses:
//   prepare(sql).bind(...).first() / .all() / .run()  (+ meta.changes)
//   batch(statements) -> per-statement { results?, meta }
//     (real D1 returns one result object per statement; SELECT
//      statements carry their rows, matching production semantics —
//      the backup module relies on batched SELECT reads)
// Binary (BLOB) columns come back as Uint8Array, matching the
// toBinary() handling in contracts-api.ts.
//
// Test instrumentation (additive, inert unless a test sets it):
//   d1.callCount        — number of D1 round-trips (batch = 1 call),
//                         the unit Cloudflare counts against the
//                         "queries per Worker invocation" limit
//   d1.statementCount   — conservative per-statement hypothetical count
//   d1.failAtCall       — one-shot: throw on the Nth call (simulates
//                         the platform killing the invocation mid-run)
//   d1.failOnSql        — one-shot: throw on the Nth call whose SQL
//                         contains this substring (d1.failOnOccurrence,
//                         default 2) — lets a test abort INSIDE a
//                         specific phase (e.g. the chunk store) without
//                         depending on exact call numbering
// ============================================================

import Database from 'better-sqlite3';

type Row = Record<string, unknown>;

interface BoundStatement {
    first<T = Row>(): Promise<T | null>;
    all<T = Row>(): Promise<{ results: T[] }>;
    run(): Promise<{ meta: { changes: number } }>;
}

interface RawStatement {
    sql: string;
    params: unknown[];
    execute: () => { changes: number | bigint };
}

const SELECT_RE = /^\s*(SELECT|WITH)\b/i;

export class D1Shim {
    private db: Database.Database;
    callCount = 0;
    statementCount = 0;
    failAtCall: number | null = null;
    failOnSql: string | null = null;
    failOnOccurrence = 1;
    private sqlMatchCount = 0;

    constructor() {
        this.db = new Database(':memory:');
        this.db.pragma('journal_mode = MEMORY');
    }

    /** Applies every migration file, in order (same as `wrangler d1 migrations apply`). */
    applyMigrations(migrations: string[]): void {
        for (const sql of migrations) this.db.exec(sql);
    }

    private noteCall(matchingSql?: string): void {
        this.callCount++;
        if (this.failAtCall !== null && this.callCount >= this.failAtCall) {
            this.failAtCall = null; // one-shot
            throw new Error(`d1-shim: simulated platform failure at D1 call #${this.callCount}`);
        }
        if (matchingSql !== undefined) {
            this.sqlMatchCount++;
            if (this.sqlMatchCount >= this.failOnOccurrence) {
                this.failOnSql = null; // one-shot
                throw new Error(`d1-shim: simulated platform failure on SQL match #${this.sqlMatchCount}`);
            }
        }
    }

    /** Returns the SQL of the first statement matching failOnSql, if any. */
    private matchSql(sqls: string[]): string | undefined {
        if (this.failOnSql === null) return undefined;
        return sqls.find((s) => s.includes(this.failOnSql!));
    }

    prepare(sql: string) {
        const stmt = this.db.prepare(sql);
        const isSelect = SELECT_RE.test(sql);
        const makeBound = (params: unknown[]) => ({
            first: async <T = Row>(): Promise<T | null> => {
                this.noteCall(this.matchSql([sql]));
                this.statementCount++;
                return (stmt.get(...(params as never[])) as T) ?? null;
            },
            all: async <T = Row>(): Promise<{ results: T[] }> => {
                this.noteCall(this.matchSql([sql]));
                this.statementCount++;
                return { results: stmt.all(...(params as never[])) as T[] };
            },
            run: async (): Promise<{ meta: { changes: number } }> => {
                this.noteCall(this.matchSql([sql]));
                this.statementCount++;
                const info = stmt.run(...(params as never[]));
                return { meta: { changes: Number(info.changes) } };
            },
            // Captured execution used by batch() below.
            _raw: { sql, params, execute: () => stmt.run(...(params as never[])) } as RawStatement,
            _isSelect: isSelect
        });
        return {
            ...makeBound([]),
            // D1 binds variadic: .bind(a, b, c)
            bind: (...params: unknown[]) => makeBound(params)
        };
    }

    /** Runs every statement in one transaction, like D1.batch.
     *  Returns one result per statement; SELECTs carry `results`. */
    async batch(statements: Array<{ _raw?: RawStatement; _isSelect?: boolean }>): Promise<Array<{ meta: { changes: number }; results?: Row[] }>> {
        const raws = statements.map((s) => {
            const raw = (s as any)._raw as RawStatement | undefined;
            if (!raw) throw new Error('batch() expects prepare().bind() statements');
            return { raw, isSelect: SELECT_RE.test(raw.sql) };
        });
        this.noteCall(this.matchSql(raws.map((r) => r.raw.sql)));
        this.statementCount += raws.length;
        const out: Array<{ meta: { changes: number }; results?: Row[] }> = [];
        const tx = this.db.transaction(() => {
            for (const { raw, isSelect } of raws) {
                if (isSelect) {
                    const rows = this.db.prepare(raw.sql).all(...(raw.params as never[])) as Row[];
                    out.push({ results: rows, meta: { changes: 0 } });
                } else {
                    const info = raw.execute();
                    out.push({ meta: { changes: Number(info.changes) } });
                }
            }
        });
        tx();
        return out;
    }

    /** Direct read for test assertions (row hash checks, tampering, ...). */
    get(sql: string, ...params: unknown[]): Row | undefined {
        return this.db.prepare(sql).get(...(params as never[])) as Row | undefined;
    }

    run(sql: string, ...params: unknown[]): void {
        this.db.prepare(sql).run(...(params as never[]));
    }
}
