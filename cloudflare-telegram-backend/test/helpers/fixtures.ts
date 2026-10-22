// ============================================================
// Test fixtures: env builder, synthetic PDF/PNG with high bytes,
// multipart upload helper. All values are fake test-only data.
// ============================================================

import { readFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { D1Shim } from './d1-shim';
import type { Env } from '../../src/types';

export const ACADEMY_A = 'academy-a';
export const ACADEMY_B = 'academy-b';
export const API_KEY = 'test-key-123';

/** Loads schema.sql + migrations 0001..0008 exactly like a real deployment. */
export function loadMigrations(): string[] {
    const base = fileURLToPath(new URL('../../schema.sql', import.meta.url));
    const dir = fileURLToPath(new URL('../../migrations/', import.meta.url));
    const names = [
        '0001_tenancy.sql',
        '0002_contracts.sql',
        '0003_seal.sql',
        '0004_academy_bots.sql',
        '0005_file_chunks.sql',
        '0006_token_links.sql',
        '0007_file_content_nullable.sql',
        '0008_link_hash_index.sql',
        '0009_payments_receipts.sql'
    ];
    return [readFileSync(base, 'utf8'), ...names.map((n) => readFileSync(dir + n, 'utf8'))];
}

export function makeEnv(
    db?: D1Shim,
    overrides: Partial<Env> = {}
): { env: Env; db: D1Shim } {
    const shim = db ?? new D1Shim();
    shim.applyMigrations(loadMigrations());
    const env: Env = {
        DB: shim as unknown as Env['DB'],
        ACADEMY_IDS: `${ACADEMY_A},${ACADEMY_B}`,
        API_SECRET_KEY: API_KEY,
        TELEGRAM_BOT_TOKEN: '12345:fake-test-token',
        PUBLIC_BASE_URL: 'https://worker.test',
        ENVIRONMENT: 'test',
        ...overrides
    };
    return { env, db: shim };
}

/**
 * Synthetic PDF whose content stream carries HIGH-BYTE binary junk
 * (0x80..0xFF): exactly the input that exposed the latin1 hash bug.
 * A classic uncompressed xref layout the worker's own parser accepts.
 */
export function makePdf(seed = 1): Uint8Array {
    const junk = new Uint8Array(512);
    for (let i = 0; i < junk.length; i++) junk[i] = (i * 31 + seed * 17) % 256; // includes 0x80..0xFF
    const header = '%PDF-1.4\n';
    const obj1 = '1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n';
    const obj2 = '2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n';
    const obj3 = '3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n';
    const obj4 = '4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n';
    const streamHead = '5 0 obj\n<< /Length 512 >>\nstream\n';
    const streamTail = '\nendstream\nendobj\n';
    const trailer = 'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n0\n%%EOF\n';
    const parts = [header, obj1, obj2, obj3, obj4, streamHead]
        .map((s) => new TextEncoder().encode(s));
    return concat([...parts, junk, new TextEncoder().encode(streamTail + trailer)]);
}

/** RGBA PNG (8-bit, color type 6) with real zlib IDAT — decodeable by the worker. */
export function makeSignaturePng(width = 4, height = 4): Uint8Array {
    const raw = new Uint8Array(height * (1 + width * 4));
    let p = 0;
    for (let y = 0; y < height; y++) {
        raw[p++] = 0; // filter none
        for (let x = 0; x < width; x++) {
            const inked = x === y; // a diagonal "stroke"
            raw[p++] = inked ? 20 : 255;
            raw[p++] = inked ? 20 : 255;
            raw[p++] = inked ? 20 : 255;
            raw[p++] = inked ? 255 : 0;
        }
    }
    const chunk = (type: string, data: Uint8Array): Uint8Array => {
        const out = new Uint8Array(12 + data.length);
        const view = new DataView(out.buffer);
        view.setUint32(0, data.length);
        for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
        out.set(data, 8);
        // CRC is not verified by the worker decoder; zeros keep the fixture tiny.
        return out;
    };
    const ihdr = new Uint8Array(13);
    const hv = new DataView(ihdr.buffer);
    hv.setUint32(0, width);
    hv.setUint32(4, height);
    ihdr[8] = 8; // bit depth
    ihdr[9] = 6; // RGBA
    const sig = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    return concat([sig, chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', new Uint8Array(0))]);
}

/**
 * Minimal baseline JPEG (SOI + APP0 + DQT + SOF0 + SOS + EOI). The worker only
 * reads the SOF0 dimensions and stores the bytes verbatim (DCTDecode), so this
 * fixture is enough to prove the real image data reaches the final PDF.
 */
export function makeJpegFixture(width = 6, height = 5): Uint8Array {
    const b: number[] = [];
    b.push(0xff, 0xd8); // SOI
    // APP0 JFIF (len 16)
    b.push(0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00);
    // DQT (len 67 = 2 + 1 + 64)
    b.push(0xff, 0xdb, 0x00, 0x43, 0x00);
    for (let i = 1; i <= 64; i++) b.push(i & 0xff);
    // SOF0 (len 17): precision 8, height, width, 3 components
    b.push(0xff, 0xc0, 0x00, 0x11, 0x08,
        (height >> 8) & 0xff, height & 0xff,
        (width >> 8) & 0xff, width & 0xff, 0x03,
        0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01);
    // SOS (len 12) + a few entropy bytes without 0xFF
    b.push(0xff, 0xda, 0x00, 0x0c, 0x03, 0x01, 0x00, 0x02, 0x11, 0x03, 0x11, 0x00, 0x3f, 0x00);
    b.push(0x12, 0x34, 0x56, 0x78, 0x9a, 0xab, 0xcd);
    b.push(0xff, 0xd9); // EOI
    return new Uint8Array(b);
}

export interface UploadMeta {
    enrollmentId: string;
    studentId: string;
    tokenHash: string;
    contractId?: string;
    publicToken?: string;
    phone?: string;
    nationalCode?: string;
    expiresAtEpoch?: number;
}

/** Builds a multipart POST /api/contracts request like the Android app does. */
export function uploadRequest(
    baseUrl: string,
    meta: UploadMeta,
    pdf: Uint8Array,
    auth: Record<string, string>
): Request {
    const form = new FormData();
    form.append('meta', JSON.stringify({
        contractId: meta.contractId || '',
        enrollmentId: meta.enrollmentId,
        studentId: meta.studentId,
        tokenHash: meta.tokenHash,
        publicToken: meta.publicToken || '',
        expiresAtEpoch: meta.expiresAtEpoch ?? (Date.now() + 30 * 86400000),
        signerName: '',
        signerTitle: '',
        enrollment: {
            info: { contractNumber: 'CN-1', courseTitle: 'دوره آزمایشی' },
            student: { fullName: 'هنرجو آزمایشی', phone: meta.phone ?? '', nationalCode: meta.nationalCode ?? '' }
        }
    }));
    form.append('file', new Blob([pdf as BlobPart], { type: 'application/pdf' }), 'contract.pdf');
    return new Request(`${baseUrl}/api/contracts`, { method: 'POST', body: form, headers: auth });
}

/** Standard SHA-256 over raw bytes — the exact value the Android app computes. */
export async function sha256Bytes(bytes: Uint8Array): Promise<string> {
    const digest = await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function bearer(academyId: string): Record<string, string> {
    return { Authorization: `Bearer ${API_KEY}`, 'X-Academy-Id': academyId };
}

export function concat(parts: Uint8Array[]): Uint8Array {
    const total = parts.reduce((n, p) => n + p.length, 0);
    const out = new Uint8Array(total);
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
}
