// ============================================================
// Jalali (Shamsi) date conversion — Gregorian epoch ms → "YYYY/MM/DD".
// Standard integer algorithm (no dependencies). Used by the receipt
// pipeline so ONLINE payments (whose gateways return Gregorian times)
// still show a real Shamsi date. No fabricated dates: the value is
// always derived from the actual payment timestamp.
// ============================================================

/** Tehran display offset (+03:30) — mirrors tz.ts; kept local so this
 *  module stays dependency-free (single source of truth documented here). */
const TEHRAN_DISPLAY_OFFSET_MS = 12600_000;

/**
 * Gregorian → Jalali (Khayyam/Birashk-free integer algorithm, widely
 * validated; leap years follow the 33-year cycle used by Iranian
 * calendars). Returns e.g. "۱۴۰۴/۰۵/۱۲" with Persian digits.
 */
export function epochToShamsi(epochMs: number): string {
    // Tehran calendar day (+03:30, DST abolished 2022): an online payment at
    // 00:30 Tehran is 21:00 UTC the PREVIOUS day — the UTC parts would print
    // the wrong official date. Shifting first keeps the algorithm unchanged.
    const d = new Date(epochMs + TEHRAN_DISPLAY_OFFSET_MS);
    const gy = d.getUTCFullYear();
    const gm = d.getUTCMonth() + 1;
    const gd = d.getUTCDate();

    const gDaysInMonth = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    let gy2 = gm > 2 ? gy + 1 : gy;
    let days =
        355666 + 365 * gy + Math.floor((gy2 + 3) / 4) - Math.floor((gy2 + 99) / 100) +
        Math.floor((gy2 + 399) / 400) + gd + gDaysInMonth.slice(0, gm - 1).reduce((a, b) => a + b, 0);
    let jy = -1595 + 33 * Math.floor(days / 12053);
    days %= 12053;
    jy += 4 * Math.floor(days / 1461);
    days %= 1461;
    if (days > 365) {
        jy += Math.floor((days - 1) / 365);
        days = (days - 1) % 365;
    }
    const jm = days < 186 ? 1 + Math.floor(days / 31) : 7 + Math.floor((days - 186) / 30);
    const jd = 1 + (days < 186 ? days % 31 : (days - 186) % 30);

    const fa = '۰۱۲۳۴۵۶۷۸۹';
    const p = (n: number) => String(n).padStart(2, '0').replace(/[0-9]/g, (x) => fa[Number(x)]);
    return `${p(jy)}/${p(jm)}/${p(jd)}`;
}
