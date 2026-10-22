// ============================================================
// Minimal D1-compatible shim over better-sqlite3 (D1 IS SQLite).
// Supports exactly the API surface the Worker uses:
//   prepare(sql).bind(...).first() / .all() / .run()  (+ meta.changes)
// Binary (BLOB) columns come back as Uint8Array, matching the
// toBinary() handling in contracts-api.ts.
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

export class D1Shim {
    private db: Database.Database;

    constructor() {
        this.db = new Database(':memory:');
        this.db.pragma('journal_mode = MEMORY');
    }

    /** Applies every migration file, in order (same as `wrangler d1 migrations apply`). */
    applyMigrations(migrations: string[]): void {
        for (const sql of migrations) this.db.exec(sql);
    }

    prepare(sql: string) {
        const stmt = this.db.prepare(sql);
        const makeBound = (params: unknown[]) => ({
            first: async <T = Row>(): Promise<T | null> => (stmt.get(...(params as never[])) as T) ?? null,
            all: async <T = Row>(): Promise<{ results: T[] }> => ({
                results: stmt.all(...(params as never[])) as T[]
            }),
            run: async (): Promise<{ meta: { changes: number } }> => {
                const info = stmt.run(...(params as never[]));
                return { meta: { changes: Number(info.changes) } };
            },
            // Captured execution used by batch() below.
            _raw: { sql, params, execute: () => stmt.run(...(params as never[])) } as RawStatement
        });
        return {
            ...makeBound([]),
            // D1 binds variadic: .bind(a, b, c)
            bind: (...params: unknown[]) => makeBound(params)
        };
    }

    /** Runs every statement in one transaction, like D1.batch. */
    async batch(statements: Array<{ _raw?: RawStatement }>): Promise<{ meta: { changes: number } }[]> {
        const raws = statements.map((s) => {
            const raw = (s as any)._raw as RawStatement | undefined;
            if (!raw) throw new Error('batch() expects prepare().bind() statements');
            return raw;
        });
        const out: { meta: { changes: number } }[] = [];
        const tx = this.db.transaction(() => {
            for (const raw of raws) {
                const info = raw.execute();
                out.push({ meta: { changes: Number(info.changes) } });
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
