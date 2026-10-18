// ============================================================
// Crypto helpers: token hashing, constant-time compare, HMAC keys
// ============================================================

export async function sha256Hex(value: string): Promise<string> {
    const data = new TextEncoder().encode(value);
    const digest = await crypto.subtle.digest('SHA-256', data);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function randomToken(bytes = 32): string {
    const buf = new Uint8Array(bytes);
    crypto.getRandomValues(buf);
    let bin = '';
    for (const b of buf) bin += String.fromCharCode(b);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Compares two hex strings without leaking length/prefix timing. */
export function timingSafeEqual(a: string, b: string): boolean {
    if (a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

/** Last-4 mask shown to the academy and the student ("0912***45"). */
export function maskPhone(phone: string): string {
    const p = (phone || '').replace(/\D/g, '');
    if (p.length < 6) return '****';
    return `${p.slice(0, 4)}***${p.slice(-2)}`;
}

export function maskNational(code: string): string {
    const c = (code || '').replace(/\D/g, '');
    if (c.length < 4) return '****';
    return `***${c.slice(-4)}`;
}
