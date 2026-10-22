// ============================================================
// Stage 5: academy-timezone scheduling helpers.
//
// Reminders must fire at the ACADEMY's local time (default 10:00), never
// at server/UTC or device time. Iranian academies run on Asia/Tehran,
// which has had a FIXED +03:30 offset since 2022 (no DST). To stay
// honest about "configurable timezone" without shipping a tz database,
// the academy can set `reminder_timezone` to either:
//   - a known zone id (Asia/Tehran, Asia/Dubai, Asia/Kabul …) resolved
//     through the small offset table below, or
//   - an explicit fixed offset written as "UTC+03:30" / "+04:30".
// Anything unknown falls back to Asia/Tehran (+03:30) — the correct
// default for this product.
// ============================================================

/** Fixed offsets (minutes east of UTC) for zones an Iranian academy may plausibly use. */
const KNOWN_OFFSETS: Record<string, number> = {
    'asia/tehran': 210,   // +03:30 (DST abolished 2022)
    'asia/dubai': 240,    // +04:00
    'asia/kabul': 270,    // +04:30
    'asia/baghdad': 180,  // +03:00
    'asia/istanbul': 180, // +03:00
    'europe/berlin': 120, // +02:00 (CET, standard time)
    'europe/london': 0,
    'utc': 0
};

/** Normalizes Persian/Arabic digits to ASCII so Shamsi strings like ۱۴۰۴/۰۵/۱۲ parse. */
export function normalizeDigits(s: string): string {
    let out = '';
    for (const ch of s || '') {
        const c = ch.codePointAt(0) || 0;
        if (c >= 0x06f0 && c <= 0x06f9) out += String.fromCharCode(c - 0x06f0 + 0x30);      // Persian ۰-۹
        else if (c >= 0x0660 && c <= 0x0669) out += String.fromCharCode(c - 0x0660 + 0x30); // Arabic ٠-٩
        else out += ch;
    }
    return out;
}

export function academyTzOffsetMinutes(tz: string | undefined): number {
    const v = (tz || '').trim();
    if (!v) return 210;
    const fixed = /^utc([+-])(\d{1,2})(?::?(\d{2}))?$/i.exec(v.replace(/\s+/g, ''))
        || /^([+-])(\d{1,2})(?::?(\d{2}))?$/.exec(v);
    if (fixed) {
        const sign = fixed[1] === '-' ? -1 : 1;
        const h = Number(fixed[2] || 0);
        const m = Number(fixed[3] || 0);
        if (h <= 14 && m < 60) return sign * (h * 60 + m);
    }
    return KNOWN_OFFSETS[v.toLowerCase()] ?? 210;
}

/**
 * Jalali (Shamsi) → Gregorian. The jalaali-js algorithm — the same
 * conversion the Android app performs (PersianDateUtil.jalaliToGregorian).
 * Returns [gy, gm, gd].
 */
export function jalaliToGregorian(jy: number, jm: number, jd: number): [number, number, number] {
    let gy: number;
    if (jy > 979) { gy = 1600; jy -= 979; } else { gy = 621; }
    let days = (365 * jy) + (~~(jy / 33) * 8) + (~~(((jy % 33) + 3) / 4)) + 78 + jd
        + ((jm < 7) ? (jm - 1) * 31 : ((jm - 7) * 30) + 186);
    gy += 400 * ~~(days / 146097);
    days %= 146097;
    if (days > 36524) {
        gy += 100 * (--days === 36524 ? 0 : ~~(days / 36524));
        days %= 36524;
        if (days >= 365) days++;
    }
    gy += 4 * ~~(days / 1461);
    days %= 1461;
    if (days > 365) {
        gy += ~~((days - 1) / 365);
        days = (days - 1) % 365;
    }
    let gd = days + 1;
    const leap = (gy % 4 === 0 && gy % 100 !== 0) || (gy % 400 === 0);
    const monthLen = [0, 31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    let gm = 1;
    for (; gm <= 12 && gd > monthLen[gm]; gm++) gd -= monthLen[gm];
    return [gy, gm, gd];
}

/** Parses '۱۴۰۴/۰۵/۱۲' (or '1404-5-12', with spaces) → [jy, jm, jd]; null when invalid. */
export function parseShamsiDate(s: string): [number, number, number] | null {
    const m = /^(\d{3,4})[/\-.](\d{1,2})[/\-.](\d{1,2})$/.exec(normalizeDigits(s || '').trim());
    if (!m) return null;
    const jy = Number(m[1]), jm = Number(m[2]), jd = Number(m[3]);
    if (jy < 1200 || jy > 1500 || jm < 1 || jm > 12 || jd < 1 || jd > 31) return null;
    return [jy, jm, jd];
}

/**
 * Epoch ms of "Shamsi due date + dayOffset days, at hour:minute academy
 * local time". Deterministic and independent of the server's own zone.
 * Returns 0 when the Shamsi string is unparsable (callers must not
 * schedule anything off a 0).
 */
export function shamsiAtAcademyTime(
    dueDateShamsi: string,
    dayOffset: number,
    hour: number,
    minute: number,
    tzOffsetMinutes: number
): number {
    const parts = parseShamsiDate(dueDateShamsi);
    if (!parts) return 0;
    const [jy, jm, jd] = parts;
    const [gy, gm, gd] = jalaliToGregorian(jy, jm, jd);
    const utcMidnight = Date.UTC(gy, gm - 1, gd, 0, 0, 0, 0);
    return utcMidnight
        + dayOffset * 86_400_000
        + hour * 3_600_000
        + minute * 60_000
        - tzOffsetMinutes * 60_000;
}
