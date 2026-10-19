// ============================================================
// Signed-PDF composition on the Worker (no external libraries)
//
// A PDF is a tree of objects, so the original contract file is kept byte-for-byte
// as the base of the new document: we only append (1) an image XObject holding the
// student's finger signature and (2) a content stream that paints it together with
// the academy seal and the signing receipt (RTL text rendered as vector paths, so
// no Persian font has to be embedded).
// ============================================================


// ---------------------------------------------------------------
// Persian (shaped) glyphs. Each glyph is drawn on a 4x5 grid with its origin
// at the baseline-left; dotted letters carry their dot as a separate path.
// Text is shaped (initial/medial/final/isolated) and laid out right-to-left.
// ---------------------------------------------------------------
interface ArabicGlyph {
    paths: number[][];
    /** Width in grid units (0 = zero-width mark). */
    w: number;
}

const AR_DOT = [[[1, 1.55, 2, 1.55]]];
const AR_DOTS2 = [[[0.6, 1.55, 1.6, 1.55], [2.2, 1.55, 3.2, 1.55]]];
const AR_DOTS3 = [[[0.2, 1.7, 1.2, 1.7], [1.1, 1.1, 2.1, 1.1], [2.0, 0.5, 3.0, 0.5]]];

// Each letter carries its start form (as written) plus the hooks needed to
// join it to a following letter; the final/medial shapes reuse the same body.
const FINAL_FORMS: Record<string, ArabicGlyph> = {
    'ب': { w: 2.2, paths: [[0.4, 2.0, 2.2, 2.0, 2.2, 0.5, 1.6, 0, 0.2, 0, 0, 0.6], [0.9, 0.4, 1.2, 0.4], [1.4, 0.8, 1.7, 0.8]] },
    'پ': { w: 2.4, paths: [[0.4, 2.0, 2.2, 2.0, 2.2, 0.5, 1.6, 0, 0.2, 0, 0, 0.6], [0.6, 2.6, 1.0, 2.6], [1.2, 3.0, 1.6, 3.0], [1.8, 3.4, 2.2, 3.4]] },
    'ت': { w: 2.4, paths: [[0.4, 2.0, 2.2, 2.0, 2.2, 0.5, 1.6, 0, 0.2, 0, 0, 0.6], [0.5, 2.5, 0.9, 2.5], [1.5, 2.5, 1.9, 2.5]] },
    'ث': { w: 2.4, paths: [[0.4, 2.0, 2.2, 2.0, 2.2, 0.5, 1.6, 0, 0.2, 0, 0, 0.6], [0.4, 2.5, 0.8, 2.5], [0.9, 2.9, 1.3, 2.9], [1.4, 3.3, 1.8, 3.3]] },
    'ج': { w: 2.4, paths: [[0.4, 2.2, 2.2, 2.2, 2.2, 0.4, 1.4, 0, 0.2, 0.2, 0, 1.0], [1.0, 1.0, 1.3, 1.0]] },
    'چ': { w: 2.6, paths: [[0.4, 2.2, 2.2, 2.2, 2.2, 0.4, 1.4, 0, 0.2, 0.2, 0, 1.0], [0.9, 1.4, 1.2, 1.4], [1.3, 1.8, 1.6, 1.8], [1.7, 2.2, 2.0, 2.2]] },
    'ح': { w: 2.2, paths: [[0.4, 2.2, 2.0, 2.2, 2.0, 0.4, 1.2, 0, 0.2, 0.2, 0, 1.0]] },
    'خ': { w: 2.2, paths: [[0.4, 2.2, 2.0, 2.2, 2.0, 0.4, 1.2, 0, 0.2, 0.2, 0, 1.0], [1.1, 1.2, 1.4, 1.5]] },
    'س': { w: 2.6, paths: [[0.2, 1.6, 0.4, 2.2, 2.2, 2.2, 2.4, 1.6, 2.2, 0.4, 1.6, 0, 0.8, 0, 0.6, 0.5, 0.2, 0.5, 0.2, 1.6], [1.3, 0.6, 1.5, 0.6]] },
    'ش': { w: 2.8, paths: [[0.2, 1.6, 0.4, 2.2, 2.2, 2.2, 2.4, 1.6, 2.2, 0.4, 1.6, 0, 0.8, 0, 0.6, 0.5, 0.2, 0.5, 0.2, 1.6], [1.3, 0.6, 1.5, 0.6], [1.0, 0.2, 1.2, 0.2]] },
    'ص': { w: 2.4, paths: [[0.2, 1.6, 0.4, 2.2, 2.2, 2.2, 2.4, 1.6, 2.2, 0.6, 1.8, 0, 0.4, 0, 0.2, 1.6]] },
    'ض': { w: 2.4, paths: [[0.2, 1.6, 0.4, 2.2, 2.2, 2.2, 2.4, 1.6, 2.2, 0.6, 1.8, 0, 0.4, 0, 0.2, 1.6], [0.9, 0.9, 1.2, 0.9]] },
    'ع': { w: 2.0, paths: [[0.2, 1.9, 0.6, 2.3, 1.4, 2.3, 2.0, 1.5, 2.0, 0.4, 1.2, 0, 0.2, 0.8]] },
    'غ': { w: 2.0, paths: [[0.2, 1.9, 0.6, 2.3, 1.4, 2.3, 2.0, 1.5, 2.0, 0.4, 1.2, 0, 0.2, 0.8], [1.2, 1.9, 1.5, 1.9]] },
    'ف': { w: 1.8, paths: [[0.8, 2.8, 1.6, 3.5, 1.8, 2.6, 1.6, 0.2, 0.4, 0, 0.4, 1.4, 0.8, 2.6]] },
    'ق': { w: 1.8, paths: [[0.8, 2.8, 1.6, 3.5, 1.8, 2.6, 1.6, 0.2, 0.4, 0, 0.4, 1.4, 0.8, 2.6], [0.6, 1.1, 0.9, 1.1], [1.2, 1.1, 1.5, 1.1]] },
    'ک': { w: 2.2, paths: [[0.4, 2.2, 1.8, 2.2, 1.8, 0.4, 1.2, 0, 0.2, 0.3], [1.0, 1.2, 1.4, 1.6]] },
    'ك': { w: 2.2, paths: [[0.4, 2.2, 1.8, 2.2, 1.8, 0.4, 1.2, 0, 0.2, 0.3], [1.0, 1.2, 1.4, 1.6]] },
    'گ': { w: 2.4, paths: [[0.4, 2.2, 1.8, 2.2, 1.8, 0.4, 1.2, 0, 0.2, 0.3], [1.0, 1.2, 1.4, 1.6], [1.6, 2.7, 1.9, 2.7], [1.9, 2.7, 2.2, 3.0]] },
    'م': { w: 1.6, paths: [[0.8, 0, 0.2, 0.6, 0.2, 1.6, 0.8, 2.0, 1.4, 1.6, 1.4, 0.6, 0.8, 0]] },
    'ن': { w: 2.0, paths: [[0.4, 2.0, 0.4, 0.9, 0.9, 0.4, 1.6, 0.4, 2.0, 1.0, 2.0, 2.0], [1.0, 1.1, 1.3, 1.1]] },
    'ه': { w: 1.8, paths: [[0.9, 0, 0.2, 0.5, 0.2, 1.4, 0.9, 2.0, 1.6, 1.4, 1.6, 0.5, 0.9, 0], [0.7, 1.0, 1.0, 1.0]] },
    'ی': { w: 1.8, paths: [[0.4, 2.0, 1.4, 2.0, 1.4, 0.4, 1.0, 0, 0.2, 0.2], [0.7, 0.9, 1.0, 0.9], [1.0, 0.4, 1.3, 0.4]] },
    'ي': { w: 1.8, paths: [[0.4, 2.0, 1.4, 2.0, 1.4, 0.4, 1.0, 0, 0.2, 0.2], [0.7, 0.9, 1.0, 0.9], [1.0, 0.4, 1.3, 0.4]] }
};

const AR: Record<string, ArabicGlyph> = {
    ' ': { w: 2, paths: [] },
    '\u200c': { w: 0.3, paths: [] },
    '\u200f': { w: 0, paths: [] },
    '\u200e': { w: 0, paths: [] },
    '0': { w: 2.2, paths: [[0, 0, 0, 3.4, 1.1, 4.2, 2.2, 3.4, 2.2, 0, 0, 0]] },
    '1': { w: 1.6, paths: [[0.4, 3.2, 1.1, 4.2, 1.1, 0]] },
    '2': { w: 2.4, paths: [[0, 3.2, 1.1, 4.2, 2.2, 3.2, 0, 0, 2.2, 0]] },
    '3': { w: 2.4, paths: [[0, 3.4, 1.1, 4.2, 2.2, 3.4, 1.1, 2.2, 2.2, 1, 1.1, 0, 0, 0.8]] },
    '4': { w: 2.4, paths: [[1.5, 0, 1.5, 4.0, 0, 1.4, 2.4, 1.4]] },
    '5': { w: 2.4, paths: [[2.4, 4.2, 0, 4.2, 0, 2.4, 1.2, 2.4, 2.4, 1.4, 2.4, 0.6, 1.4, 0, 0, 0.4]] },
    '6': { w: 2.2, paths: [[2.0, 3.8, 1, 4.2, 0, 2.8, 0, 0.6, 1.1, 0, 2.1, 0.8, 2.1, 1.8, 1, 2.2]] },
    '7': { w: 2.4, paths: [[0, 4.2, 2.4, 4.2, 0.9, 0]] },
    '8': { w: 2.2, paths: [[1.8, 3, 1.1, 4.2, 0.4, 3, 1.1, 2.2, 0, 1.2, 1.1, 0, 2.2, 1.2, 1.8, 3, 1.1, 3]] },
    '9': { w: 2.2, paths: [[0, 0.4, 1.1, 0, 2.2, 1.4, 2.2, 3.6, 1.1, 4.2, 0, 3.4, 0, 2.4]] },
    '/': { w: 1.6, paths: [[0, 0, 1.6, 4]] },
    '-': { w: 1.6, paths: [[0, 1.6, 1.6, 1.6]] },
    ':': { w: 0.7, paths: [[0.3, 2.2, 0.5, 2.2], [0.3, 0.3, 0.5, 0.3]] },
    '.': { w: 0.7, paths: [[0.3, 0.2, 0.5, 0.2]] },
    '(': { w: 1.2, paths: [[1, 4, 0.1, 2, 1, 0]] },
    ')': { w: 1.2, paths: [[0.2, 4, 1.1, 2, 0.2, 0]] },
    '،': { w: 1.4, paths: [[1.1, 0.8, 0.9, 0.3, 0.4, 0]] },
    'ا': { w: 0.8, paths: [[0.4, 0, 0.4, 4]] },
    'آ': { w: 1, paths: [[0.4, 0, 0.4, 4], [0.1, 4.6, 0.7, 4.6]] },
    'أ': { w: 1, paths: [[0.4, 0, 0.4, 4], [0.4, 4.2, 0.8, 4.6]] },
    'إ': { w: 1, paths: [[0.4, 0, 0.4, 4], [0.4, -0.2, 0.8, 0.2]] },
    'ب': { w: 3, paths: [[0, 0, 0.6, 0.5, 2.0, 0.6, 2.9, 1.4, 2.9, 2.4], [2.4, 2.4, 0.2, 2.4, 0.2, 1.4, 0.8, 0.9]] },
    'پ': { w: 3, paths: [[0, 0, 0.6, 0.5, 2.0, 0.6, 2.9, 1.4, 2.9, 2.4], [2.4, 2.4, 0.2, 2.4, 0.2, 1.4, 0.8, 0.9], [0.9, 2.9, 1.3, 2.9], [1.4, 3.3, 1.8, 3.3], [1.9, 3.7, 2.3, 3.7]] },
    'ت': { w: 3, paths: [[0, 0, 0.6, 0.5, 2.0, 0.6, 2.9, 1.4, 2.9, 2.4], [0.2, 2.4, 2.8, 2.4], [0.9, 2.9, 1.3, 2.9], [1.5, 2.9, 1.9, 2.9]] },
    'ث': { w: 3, paths: [[0, 0, 0.6, 0.5, 2.0, 0.6, 2.9, 1.4, 2.9, 2.4], [0.2, 2.4, 2.8, 2.4], [0.6, 2.9, 1.0, 2.9], [1.2, 3.3, 1.6, 3.3], [1.8, 3.7, 2.2, 3.7]] },
    'ج': { w: 3.0, paths: [[0, 0, 0.6, 0.5, 2.0, 0.5, 2.8, 1.3, 2.8, 2.2], [2.8, 2.0, 0.2, 2.0, 0.2, 1.2, 0.7, 0.6], [1.3, 1.0, 1.6, 1.0]] },
    'چ': { w: 3.2, paths: [[0, 0, 0.6, 0.5, 2.0, 0.5, 2.8, 1.3, 2.8, 2.2], [2.6, 2.2, 0.2, 2.2, 0.2, 1.2, 0.7, 0.6], [1.0, 1.3, 1.3, 1.3], [1.5, 1.7, 1.8, 1.7], [2.0, 2.1, 2.3, 2.1]] },
    'ح': { w: 2.8, paths: [[0, 0, 0.6, 0.5, 2.0, 0.5, 2.7, 1.3, 2.7, 2.2], [2.6, 2.2, 0.2, 2.2, 0.2, 1.2, 0.7, 0.6]] },
    'خ': { w: 2.8, paths: [[0, 0, 0.6, 0.5, 2.0, 0.5, 2.7, 1.3, 2.7, 2.2], [2.6, 2.2, 0.2, 2.2, 0.2, 1.2, 0.7, 0.6], [1.6, 1.3, 1.9, 1.6]] },
    'د': { w: 2.2, paths: [[0, 2.2, 1.0, 2.2, 1.9, 1.4, 1.7, 0.2, 0.1, 0]] },
    'ذ': { w: 2.2, paths: [[0, 2.2, 1.0, 2.2, 1.9, 1.4, 1.7, 0.2, 0.1, 0], [0.6, 2.8, 0.9, 2.8]] },
    'ر': { w: 1.8, paths: [[0.2, 0, 0.3, 1.2, 1.1, 2, 1.8, 2]] },
    'ز': { w: 1.8, paths: [[0.2, 0, 0.3, 1.2, 1.1, 2, 1.8, 2], [0.7, 2.6, 1.0, 2.6]] },
    'ژ': { w: 2, paths: [[0.2, 0, 0.3, 1.2, 1.1, 2, 1.8, 2], [0.7, 2.6, 1.0, 2.6], [0.9, 3.1, 1.2, 3.5], [0.8, 3.8, 1.1, 4.2]] },
    'س': { w: 3.6, paths: [[0, 1.8, 0.4, 2.3, 3.2, 2.3, 3.6, 1.8, 3.4, 0.4, 2.8, 0, 2.2, 0.5, 1.4, 0.5, 1.0, 0, 0.4, 0, 0.0, 1.8], [1.9, 1.0, 2.2, 1.3], [0.6, 1.0, 0.9, 1.3]] },
    'ش': { w: 3.6, paths: [[0, 1.8, 0.4, 2.3, 3.2, 2.3, 3.6, 1.8, 3.4, 0.4, 2.8, 0, 2.2, 0.5, 1.4, 0.5, 1.0, 0, 0.4, 0, 0.0, 1.8], [0.7, 0.8, 0.9, 1.1], [1.9, 0.8, 2.1, 1.1], [1.3, 0.3, 1.5, 0.6]] },
    'ص': { w: 3.4, paths: [[0.2, 1.8, 0.6, 2.2, 3.0, 2.2, 3.4, 1.7, 3.2, 0.3, 2.6, 0, 0.6, 0, 0.2, 1.2]] },
    'ض': { w: 3.4, paths: [[0.2, 1.8, 0.6, 2.2, 3.0, 2.2, 3.4, 1.7, 3.2, 0.3, 2.6, 0, 0.6, 0, 0.2, 1.2], [1.3, 0.9, 1.6, 1.2]] },
    'ط': { w: 2.6, paths: [[0.2, 1.8, 0.4, 2.2, 2.4, 2.2, 2.6, 1.6, 2.6, 0.2, 1.6, 0, 0.4, 0, 0.2, 0.8]] },
    'ظ': { w: 2.6, paths: [[0.2, 1.8, 0.4, 2.2, 2.4, 2.2, 2.6, 1.6, 2.6, 0.2, 1.6, 0, 0.4, 0, 0.2, 0.8], [1.1, 0.9, 1.4, 0.9], [1.5, 1.3, 1.8, 1.3]] },
    'ع': { w: 3.0, paths: [[0.2, 1.9, 0.6, 2.3, 2.2, 2.3, 3.0, 1.5, 3.0, 0.4, 2.4, 0, 1.4, 0.6, 0.8, 1.2, 1.4, 1.7, 2.4, 1.7]] },
    'غ': { w: 3.0, paths: [[0.2, 1.9, 0.6, 2.3, 2.2, 2.3, 3.0, 1.5, 3.0, 0.4, 2.4, 0, 1.4, 0.6, 0.8, 1.2, 1.4, 1.7, 2.4, 1.7], [1.9, 1.9, 2.2, 1.9]] },
    'ف': { w: 3.0, paths: [[0.9, 2.6, 1.9, 3.5, 3.0, 1.8, 2.2, 0.3, 0.6, 0.2, 0.2, 0.9, 0.5, 1.7, 1.9, 2.0]] },
    'ق': { w: 3.0, paths: [[0.9, 2.6, 1.9, 3.5, 3.0, 1.8, 2.2, 0.3, 0.6, 0.2, 0.2, 0.9, 0.5, 1.7, 1.9, 2.0], [0.6, 1.1, 0.9, 1.1], [1.9, 1.1, 2.2, 1.1]] },
    'ک': { w: 2.8, paths: [[0.2, 1.9, 0.6, 2.3, 2.4, 2.3, 2.8, 1.8, 2.8, 0.2, 1.4, 0, 0.2, 0.3], [1.3, 1.4, 1.7, 1.8]] },
    'ك': { w: 2.8, paths: [[0.2, 1.9, 0.6, 2.3, 2.4, 2.3, 2.8, 1.8, 2.8, 0.2, 1.4, 0, 0.2, 0.3], [1.3, 1.4, 1.7, 1.8]] },
    'گ': { w: 3.0, paths: [[0.2, 1.9, 0.6, 2.3, 2.4, 2.3, 2.8, 1.8, 2.8, 0.2, 1.4, 0, 0.2, 0.3], [1.3, 1.4, 1.7, 1.8], [2.0, 2.9, 2.3, 2.9], [2.3, 2.9, 2.6, 3.2]] },
    'ل': { w: 1.4, paths: [[1.4, 4, 1.4, 1, 0.8, 0, 0.2, 0.4, 0.2, 1.2]] },
    'م': { w: 2.2, paths: [[1.1, 0, 0.4, 0.5, 0.4, 1.4, 1.1, 2.0, 1.8, 1.4, 1.8, 0.5, 1.1, 0]] },
    'ن': { w: 2.4, paths: [[0.2, 2.0, 0.2, 0.9, 0.8, 0.4, 1.6, 0.4, 2.3, 1.0, 2.3, 2.0], [1.0, 1.1, 1.3, 1.1]] },
    'و': { w: 2.0, paths: [[0.8, 2, 0.2, 1.2, 0.6, 0.2, 1.4, 0, 2, 0.6, 1.8, 1.2]] },
    'ؤ': { w: 2.0, paths: [[0.8, 2, 0.2, 1.2, 0.6, 0.2, 1.4, 0, 2, 0.6, 1.8, 1.2], [1.0, 2.3, 1.3, 2.3], [1.1, 2.8, 1.4, 2.8]] },
    'ه': { w: 2.5, paths: [[1.25, 0, 0.45, 0.4, 0.3, 1.2, 0.75, 2.0, 1.6, 2.4, 2.2, 2.0, 2.3, 1.2, 1.25, 0], [1.05, 1.05, 1.3, 1.05]] },
    'ة': { w: 2.5, paths: [[1.25, 0, 0.45, 0.4, 0.3, 1.2, 0.75, 2.0, 1.6, 2.4, 2.2, 2.0, 2.3, 1.2, 1.25, 0], [1.0, 1.2, 1.25, 1.2], [1.2, 1.7, 1.45, 1.7]] },
    'ی': { w: 3.0, paths: [[0.2, 0.2, 0.6, 0, 2.2, 0, 3.0, 0.8, 3.0, 2.2], [0.7, 0.8, 1.0, 0.8], [1.7, 0.8, 2.0, 0.8], [2.5, 0.3, 2.8, 0.3]] },
    'ي': { w: 3.0, paths: [[0.2, 0.2, 0.6, 0, 2.2, 0, 3.0, 0.8, 3.0, 2.2], [0.7, 0.8, 1.0, 0.8], [1.7, 0.8, 2.0, 0.8], [2.5, 0.3, 2.8, 0.3]] },
    'ئ': { w: 3.0, paths: [[0.2, 0.2, 0.6, 0, 2.2, 0, 3.0, 0.8, 3.0, 2.2], [1.4, 1.0, 1.7, 1.5]] },
    'ء': { w: 1.1, paths: [[0.3, 0.8, 0.6, 1.5, 1.0, 1.0, 0.8, 0.5, 0.3, 0.8]] }
};

const FONT_REVERSED: Record<string, string> = {
    '0': '0', '1': '1', '2': '2', '3': '3', '4': '4', '5': '5', '6': '6', '7': '7', '8': '8', '9': '9',
    '۰': '0', '۱': '1', '۲': '2', '۳': '3', '۴': '4', '۵': '5', '۶': '6', '۷': '7', '۸': '8', '۹': '9'
};

// 5x7 stroke font used for Latin text and digits (only the glyphs we print).
const GLYPHS: Record<string, number[][]> = {
    '0': [[0, 0, 3, 0, 3, 5, 0, 5, 0, 0]],
    '1': [[1, 1, 2, 0, 2, 5]],
    '2': [[0, 4, 1, 5, 3, 5, 3, 3, 0, 1, 0, 0, 3, 0]],
    '3': [[0, 5, 3, 5, 1, 3, 3, 2, 3, 0, 0, 0]],
    '4': [[0, 5, 0, 2, 3, 2], [3, 4, 3, 0]],
    '5': [[3, 5, 0, 5, 0, 3, 2, 3, 3, 2, 3, 0, 0, 0]],
    '6': [[3, 5, 1, 4, 0, 2, 0, 0, 3, 0, 3, 2, 0, 2]],
    '7': [[0, 5, 3, 5, 1, 0]],
    '8': [[1, 3, 0, 4, 0, 5, 3, 5, 3, 3, 0, 2, 0, 0, 3, 0, 3, 2, 2, 3, 1, 3]],
    '9': [[0, 5, 2, 5, 3, 4, 3, 2, 0, 2, 0, 0, 2, 0]],
    'A': [[0, 0, 0, 3, 1, 5, 3, 5, 4, 3, 4, 0], [0, 2, 4, 2]],
    'B': [[0, 0, 0, 5, 3, 5, 4, 4, 4, 3, 3, 2, 0, 2], [3, 2, 4, 1, 4, 0, 3, 0, 0, 0]],
    'C': [[4, 5, 1, 5, 0, 4, 0, 0, 1, 0, 4, 0]],
    'D': [[0, 0, 0, 5, 3, 5, 4, 4, 4, 0, 3, 0, 0, 0]],
    'E': [[4, 5, 0, 5, 0, 0, 4, 0], [0, 2, 3, 2]],
    'F': [[0, 0, 0, 5, 4, 5], [0, 2, 3, 2]],
    'G': [[4, 5, 1, 5, 0, 4, 0, 0, 1, 0, 3, 0, 4, 1, 4, 2, 2, 2]],
    'H': [[0, 0, 0, 5], [4, 0, 4, 5], [0, 2, 4, 2]],
    'I': [[2, 0, 2, 5], [1, 5, 3, 5], [1, 0, 3, 0]],
    'J': [[4, 5, 4, 1, 3, 0, 1, 0, 0, 1]],
    'K': [[0, 0, 0, 5], [4, 5, 0, 2], [1, 3, 4, 0]],
    'L': [[0, 5, 0, 0, 4, 0]],
    'M': [[0, 0, 0, 5, 2, 3, 4, 5, 4, 0]],
    'N': [[0, 0, 0, 5, 4, 0, 4, 5]],
    'O': [[1, 5, 0, 4, 0, 1, 1, 0, 3, 0, 4, 1, 4, 4, 3, 5, 1, 5]],
    'P': [[0, 0, 0, 5, 3, 5, 4, 4, 4, 3, 3, 2, 0, 2]],
    'Q': [[1, 5, 0, 4, 0, 1, 1, 0, 3, 0, 4, 1, 4, 4, 3, 5, 1, 5], [2, 1, 4, 0]],
    'R': [[0, 0, 0, 5, 3, 5, 4, 4, 4, 3, 3, 2, 0, 2], [1, 2, 4, 0]],
    'S': [[4, 5, 1, 5, 0, 4, 0, 3, 1, 2, 3, 2, 4, 1, 4, 0, 3, 0, 0, 0]],
    'T': [[0, 5, 4, 5], [2, 5, 2, 0]],
    'U': [[0, 5, 0, 1, 1, 0, 3, 0, 4, 1, 4, 5]],
    'V': [[0, 5, 2, 0, 4, 5]],
    'W': [[0, 5, 1, 0, 2, 3, 3, 0, 4, 5]],
    'X': [[0, 5, 4, 0], [0, 0, 4, 5]],
    'Y': [[0, 5, 2, 3, 4, 5], [2, 3, 2, 0]],
    'Z': [[0, 5, 4, 5, 0, 0, 4, 0]],
    'a': [[1, 2, 3, 2, 3, 0, 1, 0, 0, 1, 0, 2, 1, 3, 3, 3]],
    'b': [[0, 5, 0, 2, 1, 3, 3, 3, 3, 0, 1, 0, 0, 2]],
    'c': [[3, 3, 1, 3, 0, 2, 0, 0, 1, 0, 3, 0]],
    'd': [[3, 5, 3, 2, 2, 3, 0, 3, 0, 0, 2, 0, 3, 2]],
    'e': [[0, 1, 3, 1, 3, 3, 1, 3, 0, 2, 0, 0, 1, 0, 3, 0]],
    'f': [[3, 5, 2, 4, 2, 0], [1, 3, 3, 3]],
    'g': [[3, 3, 3, -2, 2, -3, 0, -3], [3, 2, 2, 3, 0, 3, 0, 1, 2, 1, 3, 2]],
    'h': [[0, 5, 0, 0], [0, 2, 1, 3, 3, 3, 3, 0]],
    'i': [[1, 3, 1, 0], [1, 3.6, 1, 4]],
    'j': [[2, 3, 2, -2, 1, -3], [2, 3.6, 2, 4]],
    'k': [[0, 0, 0, 5], [3, 3, 0, 1], [1, 2, 3, 0]],
    'l': [[1, 5, 1, 0]],
    'm': [[0, 3, 0, 0], [0, 2, 1, 3, 1, 0], [1, 2, 2, 3, 2, 0]],
    'n': [[0, 3, 0, 0], [0, 2, 1, 3, 3, 3, 3, 0]],
    'o': [[0, 3, 1, 3, 3, 2, 3, 1, 1, 0, 0, 1, 0, 3]],
    'p': [[0, 3, 0, -3], [0, 3, 1, 3, 3, 2, 3, 1, 1, 0, 0, 1]],
    'q': [[3, 3, 3, -3], [3, 3, 2, 3, 0, 2, 0, 1, 2, 0, 3, 1]],
    'r': [[0, 3, 0, 0], [0, 2, 1, 3, 3, 3]],
    's': [[3, 3, 1, 3, 0, 2, 1, 1, 3, 1, 3, 0, 2, 0, 0, 0]],
    't': [[1, 5, 1, 0, 2, 0], [0, 3, 3, 3]],
    'u': [[0, 3, 0, 1, 1, 0, 3, 0], [3, 3, 3, 0]],
    'v': [[0, 3, 1, 0, 3, 3]],
    'w': [[0, 3, 1, 0, 2, 2, 3, 0, 4, 3]],
    'x': [[0, 3, 3, 0], [0, 0, 3, 3]],
    'y': [[0, 3, 1, 0, 3, 3], [3, 3, 2, -2, 1, -3]],
    'z': [[0, 3, 3, 3, 0, 0, 3, 0]],
    '-': [[1, 1.5, 3, 1.5]],
    ':': [[1, 2.2, 1, 2.0], [1, 0.4, 1, 0.2]],
    '/': [[0, 0, 3, 5]],
    '*': [[0, 4, 3, 1], [0, 1, 3, 4]],
    '.': [[1, 0.2, 1, 0]],
    ' ': [],
    '(': [[3, 5, 1, 3, 1, 2, 3, 0]],
    ')': [[1, 5, 3, 3, 3, 2, 1, 0]],
    '#': [[0, 4, 4, 4], [1, 5, 1, 1], [3, 5, 3, 1], [0, 3, 4, 3]],
    '%': [[0, 0, 4, 5]],
    ',': [[2, 1, 2, -1]],
    '+': [[1, 1, 3, 1], [2, 2, 2, 0]],
    '=': [[0, 3, 4, 3], [0, 1, 4, 1]],
    '@': [[1, 0, 0, 1, 0, 3, 1, 4, 3, 4, 4, 3, 4, 2, 2, 2, 2, 1, 3, 1, 3, 3]]
};

function sanitize(text: string): string {
    return [...text].map((ch) => FONT_REVERSED[ch] ?? ch).join('');
}

function escapePdfText(text: string): string {
    return text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
}


/** Letters that join to the next letter (Persian joins only inside words). */
const JOINS_NEXT = new Set([...'بپتثجچحخسشصضطظعغفقکگلمنهيیئ']);
/** Letters that connect to the previous letter. */
const JOINS_PREV = new Set([...'بپتثجچحخسشصضطظعغفقکگلمنهيیئة']);

/** Converts the displayed digits to the Persian forms expected on the receipt. */
export function toPersianDigits(text: string): string {
    const fa = '۰۱۲۳۴۵۶۷۸۹';
    return text.replace(/[0-9]/g, (d) => fa[Number(d)]);
}

/**
 * Lays out Persian text right-to-left: words are laid out left-to-right inside
 * the line so Latin runs (phone numbers, hashes) stay readable.
 */
export function persianText(x: number, y: number, text: string, size = 5, color = '0.10 0.15 0.20', bold = 0.9, right = true): string {
    // Glyphs are drawn on a 4-unit box, so the unit is a quarter of the size.
    const unit = size / 4;
    const spaceWidth = 1.6 * unit;
    const isLatin = (word: string) => /^[A-Za-z0-9@._#*+=\-]+$/.test(word);
    const words = text.split(/\s+/).filter((w) => w.length > 0);
    const measureWord = (word: string) => isLatin(word)
        ? [...word].reduce((w, ch) => w + ((GLYPHS[ch]?.length ?? 0) ? 5.6 : 3.6) * unit, 0)
        : shapePersian(word).reduce((w, g) => w + g.w * unit, 0);
    const widths = words.map(measureWord);
    const total = widths.reduce((w, x) => w + x, 0) + spaceWidth * Math.max(0, words.length - 1);
    // The visual order is left-to-right; only the anchor changes for RTL lines.
    let pen = x + (right ? -total : 0);
    let ops = '';
    for (let index = 0; index < words.length; index++) {
        const word = words[index];
        if (isLatin(word)) {
            ops += strokeText(pen, y, word, size, color, false, bold);
        } else {
            let gx = pen;
            for (const g of shapePersian(word)) {
                for (const path of g.paths) {
                    let p = '';
                    for (let i = 0; i < path.length; i += 2) {
                        p += `${(gx + path[i] * unit).toFixed(2)} ${(y + path[i + 1] * unit).toFixed(2)} ${i === 0 ? 'm' : 'l'} `;
                    }
                    if (p) ops += `${p}S\n`;
                }
                gx += g.w * unit;
            }
        }
        pen += widths[index] + spaceWidth;
    }
    return `${color} rg ${color} RG ${bold} w\n` + ops;
}

/** Splits RTL text into glyphs with contextual forms and kerning widths. */
function shapePersian(text: string): ArabicGlyph[] {
    const out: ArabicGlyph[] = [];
    const chars = [...text];
    for (let i = 0; i < chars.length; i++) {
        const ch = chars[i];
        if (ch === 'ل' && chars[i + 1] === 'ا') {
            out.push({ w: 2.8, paths: [[0.4, 4.6, 0.4, 2.6, 1.2, 1.2, 2.6, 0.4, 2.8, 0], [2.4, 4.6, 0.6, 0.6]] });
            i++;
            continue;
        }
        const g = AR[ch];
        if (!g) {
            out.push({ w: 1.6, paths: [] });
            continue;
        }
        const prev = chars[i - 1] || '';
        const next = chars[i + 1] || '';
        const joinsNext = JOINS_NEXT.has(ch) && JOINS_PREV.has(next) && next !== ' ';
        const joinsPrev = JOINS_PREV.has(ch) && JOINS_NEXT.has(prev);
        // Only the final form of a letter gets a tail; everything else keeps the
        // start form, so glyphs line up into one connected baseline.
        let glyph: ArabicGlyph = g;
        if (!joinsNext && joinsPrev && FINAL_FORMS[ch]) glyph = FINAL_FORMS[ch];
        out.push(glyph);
    }
    return out;
}

/** Latin/digits only, drawn with the stroke font. `size` in points. */
export function asciiText(x: number, y: number, text: string, size = 4, reverse = true): string {
    return strokeText(x, y, sanitize(text), size, '0.35 0.39 0.45', false, 0.5);
}

/** Upright Latin text; unmapped characters are dropped by the stroke font. */
export function latinText(x: number, y: number, text: string, size = 5, color = '0.10 0.15 0.20', bold = 0.9): string {
    return strokeText(x, y, text, size, color, false, bold);
}

/** Stroke-font drawing in PDF units, mirrored for RTL languages. */
export function strokeText(
    x: number, y: number, text: string, size = 5, color = '0 0 0', rtl = true, bold = 1
): string {
    const unit = size / 5;
    const glyphRecords: number[][][] = [];
    for (const ch of text) {
        const glyph = GLYPHS[ch] ?? GLYPHS[sanitize(ch)];
        // Unmapped characters (e.g. Persian glyphs) are skipped, not faked.
        glyphRecords.push(glyph ?? []);
    }
    const advance = (g: number[][]) => (g.length ? 4 * unit : 2 * unit);
    const gap = 1.6 * unit;
    const totalWidth = glyphRecords.reduce((w, g) => w + advance(g) + gap, 0);
    let ops = `${color} rg ${color} RG ${bold} w\n`;
    let cursor = 0;
    // RTL text starts at the right edge; every glyph still reads left-to-right.
    for (const g of glyphRecords) {
        const offset = rtl ? totalWidth - cursor - 4 * unit : cursor;
        for (const poly of g) {
            let path = '';
            for (let i = 0; i < poly.length; i += 2) {
                const px = x + offset + poly[i] * unit;
                const py = y + poly[i + 1] * unit;
                path += `${px.toFixed(2)} ${py.toFixed(2)} ${i === 0 ? 'm' : 'l'} `;
            }
            if (path) ops += `${path}S\n`;
        }
        cursor += advance(g) + gap;
    }
    return ops;
}

function parseObjects(text: string): Map<number, { start: number; end: number; body: string }> {
    const objects = new Map<number, { start: number; end: number; body: string }>();
    const re = /(\d+)\s+(\d+)\s+obj([\s\S]*?)endobj/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
        objects.set(Number(m[1]), { start: m.index, end: m.index + m[0].length, body: m[3] });
    }
    return objects;
}

function findRootRef(text: string): string {
    const m = /\/Root\s+(\d+\s+\d+\s+R)/.exec(text);
    return m ? m[1] : '1 0 R';
}

/** Last page's MediaBox and /Contents reference. */
function lastPage(text: string, objects: Map<number, { body: string }>): { pageRef: string; contentsRef: string | null; width: number; height: number } {
    let best: { ref: string; body: string } | null = null;
    for (const [num, obj] of objects) {
        if (/\/Type\s*\/Page\b/.test(obj.body) && !/\/Type\s*\/Pages/.test(obj.body)) {
            best = { ref: `${num} 0 R`, body: obj.body };
        }
    }
    const media = best ? /\/MediaBox\s*\[\s*([\d.\-]+)\s+([\d.\-]+)\s+([\d.\-]+)\s+([\d.\-]+)\s*\]/.exec(best.body) : null;
    const w = media ? Number(media[3]) - Number(media[1]) : 595;
    const h = media ? Number(media[4]) - Number(media[2]) : 842;
    const contents = best ? /\/Contents\s+(\d+\s+\d+\s+R|\d+)/.exec(best.body) : null;
    return {
        pageRef: best?.ref ?? '',
        contentsRef: contents ? (contents[1].includes('R') ? contents[1] : `${contents[1]} 0 R`) : null,
        width: w,
        height: h
    };
}

export interface SignedLayer {
    /** Finger signature exactly as the page produced it (PNG with alpha or JPEG). */
    signatureBytes: Uint8Array;
    imageIsPng: boolean;
    imageWidth: number;
    imageHeight: number;
    sealJpeg?: Uint8Array;
    sealPng?: Uint8Array;
    sealWidth?: number;
    sealHeight?: number;
    managerSignatureJpeg?: Uint8Array;
    managerSignaturePng?: Uint8Array;
    managerSignatureWidth?: number;
    managerSignatureHeight?: number;
    lines: string[];
    verifyLines: string[];
}

/**
 * Returns the original PDF with an appended signature layer on its last page.
 * Every original object keeps its number and bytes; the xref is rewritten.
 */
export function appendSignatureLayer(original: Uint8Array, layer: SignedLayer): Uint8Array {
    const text = new TextDecoder('latin1').decode(original);
    const objects = parseObjects(text);
    const page = lastPage(text, objects);
    // Object numbers are counted from the file itself: a regex map may miss
    // nested objects, but the highest number is what the xref must cover.
    const occurrences = text.match(/(?:^|[\r\n\s])(\d+)\s+0\s+obj\b/g) || [];
    let maxObject = 0;
    for (const occ of occurrences) {
        const n = Number(/(\d+)/.exec(occ)?.[1] || 0);
        if (n > maxObject) maxObject = n;
    }
    for (const n of objects.keys()) if (n > maxObject) maxObject = n;

    const now = Date.now();
    const base = maxObject + 1;
    const sigObj = base;              // signature image XObject
    const contentObj = base + 1;      // signature content stream
    const sigMaskObj = base + 4;      // alpha mask for the signature image
    const sealSrc = layer.sealPng || layer.sealJpeg;
    const msigSrc = layer.managerSignaturePng || layer.managerSignatureJpeg;
    const sealObj = sealSrc ? base + 2 : 0;
    const msigObj = msigSrc ? base + 3 : 0;
    const pageObjNum = page.pageRef ? Number(page.pageRef.split(' ')[0]) : 0;
    const pageBody = pageObjNum ? objects.get(pageObjNum)?.body ?? '' : '';

    const w = page.width;
    const h = page.height;
    const margin = 24;
    const boxH = Math.min(190, h * 0.24);
    const boxY = margin + 20;
    const boxX = margin;

    // Canvas input: the student drew with the mouse, so the PNG's aspect ratio
    // is what keeps the strokes from looking stretched on paper.
    const sigW = layer.imageWidth || 1;
    const sigH = layer.imageHeight || 1;
    const sigBoxW = 150;
    const sigBoxH = Math.min(100, (sigBoxW * sigH) / sigW);

    // ---- content stream (signature image + seal + receipt text) ----
    let ops = 'q\n';
    ops += `1 1 1 rg ${boxX} ${boxY} ${w - margin * 2} ${boxH} re f\n`;
    ops += `0.58 0.63 0.70 RG 0.8 w ${boxX} ${boxY} ${w - margin * 2} ${boxH} re S\n`;
    ops += `q ${boxX + 10} ${boxY + 30} ${sigBoxW} ${sigBoxH} re W n `;
    if (layer.imageWidth > 0 && layer.imageHeight > 0) {
        ops += `q ${sigBoxW} 0 0 ${sigBoxH.toFixed(2)} ${boxX + 10} ${boxY + 30} cm /Sig Do Q Q\n`;
    } else {
        ops += 'Q\n';
    }
    // Seal keeps its own aspect ratio; the manager signature sits left of it.
    if (sealSrc) {
        const sw = layer.sealWidth || 1, sh = layer.sealHeight || 1;
        const target = 78;
        const dw = sw >= sh ? target : (target * sw) / sh;
        const dh = sh >= sw ? target : (target * sh) / sw;
        ops += `q ${dw.toFixed(2)} 0 0 ${dh.toFixed(2)} ${(w - margin - dw - 12).toFixed(2)} ${(boxY + boxH - dh - 10).toFixed(2)} cm /Seal Do Q\n`;
    }
    if (msigSrc) {
        const sw = layer.managerSignatureWidth || 1, sh = layer.managerSignatureHeight || 1;
        const dw = Math.min(96, (52 * sw) / sh);
        const dh = (dw * sh) / sw;
        ops += `q ${dw.toFixed(2)} 0 0 ${dh.toFixed(2)} ${(w - margin - 105).toFixed(2)} ${(boxY + 26).toFixed(2)} cm /MSig Do Q\n`;
    }
    ops += `Q\n`;

    let textY = boxY + boxH - 14;
    for (const line of layer.lines) {
        ops += persianText(boxX + 12, textY, line, 8, '0.10 0.15 0.20', 0.9, false);
        textY -= 13;
    }
    ops += persianText(boxX + 12, boxY + 30 + sigBoxH + 6, 'امضای هنرجو', 4.4, '0.35 0.39 0.45', 0.5, false);
    // verification facts are Latin-only, so the compact font keeps them legible
    let vY = boxY + 24;
    for (const v of layer.verifyLines) {
        ops += asciiText(w - margin - 8 - v.length * 1.9, vY, v, 4.2, false);
        vY -= 8;
    }
    ops += asciiText(boxX + 8, boxY - 10, `signed ${new Date(now).toISOString().slice(0, 19)}Z`, 4, false);



    // Minimal dictionary walk: find the top-level key `name` inside `dict` and
    // return its range, so a nested ">>" never ends the parent dict early.
    function findKey(dict: string, name: string): { start: number; valueStart: number; end: number } | null {
        const idx = dict.indexOf('/' + name);
        if (idx < 0) return null;
        let i = idx + name.length + 1;
        while (i < dict.length && /\s/.test(dict[i])) i++;
        if (dict.startsWith('<<', i)) {
            let depth = 0;
            for (let j = i; j < dict.length; j++) {
                if (dict.startsWith('<<', j)) { depth++; j++; continue; }
                if (dict.startsWith('>>', j)) { depth--; j++; if (depth === 0) return { start: idx, valueStart: i, end: j + 1 }; }
            }
            return null;
        }
        if (dict[i] === '[') {
            let depth = 0;
            for (let j = i; j < dict.length; j++) {
                if (dict[j] === '[') depth++;
                else if (dict[j] === ']') { depth--; if (depth === 0) return { start: idx, valueStart: i, end: j + 1 }; }
            }
            return null;
        }
        const m = /^\d+\s+\d+\s+R/.exec(dict.slice(i));
        if (m) return { start: idx, valueStart: i, end: i + m[0].length };
        return null;
    }

    function patchPage(body: string): string {
        let out = body;
        const open = out.indexOf('<<');
        if (open < 0) return out;
        const head = out.slice(0, open);
        let dict = out.slice(open);

        if (extra) {
            const res = findKey(dict, 'Resources');
            if (res) {
                const resDict = dict.slice(res.valueStart, res.end);
                const xo = findKey(resDict, 'XObject');
                const patchedRes = xo
                    ? resDict.slice(0, xo.end - 2) + ' ' + extra + ' >>' + resDict.slice(xo.end)
                    : resDict.slice(0, resDict.length - 2) + `/XObject << ${extra} >> ` + resDict.slice(resDict.length - 2);
                dict = dict.slice(0, res.valueStart) + patchedRes + dict.slice(res.end);
            } else {
                dict = dict.slice(0, 2) + `/Resources << /XObject << ${extra} >> >> ` + dict.slice(2);
            }
        }

        const contents = findKey(dict, 'Contents');
        if (contents) {
            const value = dict.slice(contents.valueStart, contents.end);
            const merged = value.startsWith('[')
                ? value.slice(0, -1).trimEnd() + ` ${contentObj} 0 R ]`
                : `[${value} ${contentObj} 0 R]`;
            dict = dict.slice(0, contents.valueStart) + merged + dict.slice(contents.end);
        } else {
            dict = dict.slice(0, 2) + `/Contents ${contentObj} 0 R ` + dict.slice(2);
        }
        return head + dict;
    }

    let objectsOut: string[] = [];

    const imgHeader = (jpeg: Uint8Array, iw: number, ih: number, isPng = false, smaskObj = 0) =>
        isPng
            ? `<< /Type /XObject /Subtype /Image /Width ${iw} /Height ${ih} /ColorSpace /DeviceGray /BitsPerComponent 8 ${smaskObj ? `/SMask ${smaskObj} 0 R ` : ''}/Filter /FlateDecode /Length ${jpeg.length} >>`
            : `<< /Type /XObject /Subtype /Image /Width ${iw} /Height ${ih} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>`;

    // A canvas PNG arrives as RGBA: opaque strokes are turned into alpha ink.
    const decoded = layer.imageIsPng ? pngSignatureImage(layer.signatureBytes) : null;
    // Images are assembled inline (header + raw bytes) inside assemblePdf, so the
    // binary body must not be joined with the text objects.
    const imageBodies = new Map<number, { header: string; payload: Uint8Array }>();
    // The new content stream is registered in the page dict at the end.
    const opsBytes = new TextEncoder().encode(ops);
    objectsOut.push(`${contentObj} 0 obj\n<< /Length ${opsBytes.length} >>\nstream\n${ops}\nendstream\nendobj`);
    // The PNG fallback is stored raw, so its header must not claim a Flate stream.

    if (decoded && layer.imageWidth > 0 && layer.imageHeight > 0) {
        // The image paints black ink on white paper; the soft mask carries the
        // canvas alpha so the paper vanishes but the stroke keeps its soft edge.
        // A PNG whose decode only produced rows is stored as an inverted image:
        // the mask object is optional, the page must never reference a gap.
        imageBodies.set(sigObj, { header: imgHeader(decoded.alpha, layer.imageWidth, layer.imageHeight, true, sigMaskObj), payload: decoded.alpha });
        const maskHeader = `<< /Type /XObject /Subtype /Image /Width ${layer.imageWidth} /Height ${layer.imageHeight} /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length ${decoded.mask.length} >>`;
        imageBodies.set(sigMaskObj, { header: maskHeader, payload: decoded.mask });
        if (!imageBodies.has(sigMaskObj) || !decoded.mask.length) imageBodies.delete(sigMaskObj);
    }
    // A PNG seal/signature is flattened to RGB first: RGB has no alpha channel,
    // so the soft mask keeps the transparent background from painting white.
    const flattenPng = (bytes: Uint8Array, w: number, h: number) => {
        const px = pngToRgb(bytes);
        if (!px || !w || !h) return null;
        return { rgb: px.rgb, mask: px.mask, header: `<< /Type /XObject /Subtype /Image /Width ${w} /Height ${h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /SMask ${base + 5} 0 R /Filter /FlateDecode /Length ${px.rgb.length} >>`, maskHeader: `<< /Type /XObject /Subtype /Image /Width ${w} /Height ${h} /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length ${px.mask.length} >>` };
    };
    const sealIsPng = Boolean(layer.sealPng);
    const msigIsPng = Boolean(layer.managerSignaturePng);
    const flatSeal = sealIsPng && sealSrc ? flattenPng(sealSrc, layer.sealWidth || 0, layer.sealHeight || 0) : null;
    const flatMsig = msigIsPng && msigSrc ? flattenPng(msigSrc, layer.managerSignatureWidth || 0, layer.managerSignatureHeight || 0) : null;
    if (flatSeal && sealSrc) {
        imageBodies.set(sealObj, { header: flatSeal.header, payload: flatSeal.rgb });
        imageBodies.set(sealObj + 3, { header: flatSeal.maskHeader, payload: flatSeal.mask });
    } else if (layer.sealJpeg) {
        imageBodies.set(sealObj, { header: imgHeader(layer.sealJpeg, layer.sealWidth || 1, layer.sealHeight || 1), payload: layer.sealJpeg });
    }
    if (flatMsig && msigSrc) {
        imageBodies.set(msigObj, { header: flatMsig.header, payload: flatMsig.rgb });
        imageBodies.set(msigObj + 3, { header: flatMsig.maskHeader, payload: flatMsig.mask });
    } else if (layer.managerSignatureJpeg) {
        imageBodies.set(msigObj, {
            header: imgHeader(layer.managerSignatureJpeg, layer.managerSignatureWidth || 1, layer.managerSignatureHeight || 1),
            payload: layer.managerSignatureJpeg
        });
    }

    const maskReady = imageBodies.has(sigMaskObj);
    // Page dictionary: register the new XObjects and the new content stream in
    // one balanced edit (a regex on the page body alone can unbalance the dict).
    const extra = [
        layer.imageWidth > 0 && layer.imageHeight > 0 ? `/Sig ${sigObj} 0 R${maskReady ? ` /SigMask ${sigMaskObj} 0 R` : ''}` : '',
        imageBodies.has(sealObj) ? `/Seal ${sealObj} 0 R` : '',
        imageBodies.has(msigObj) ? `/MSig ${msigObj} 0 R` : ''
    ].filter(Boolean).join(' ');

    // The page is patched last: /Sig is only referenced once the mask object
    // actually exists in imageBodies. The content stream object added above stays.
    for (const [num, obj] of objects) {
        const body = num === pageObjNum ? patchPage(obj.body) : obj.body;
        objectsOut.push(`${num} 0 obj${body}endobj`);
    }

    return assemblePdf(
        original, objects, objectsOut, maxObject,
        imageBodies,
        findRootRef(text)
    );
}

/**
 * Canvas PNGs are RGBA. This decodes the IDAT stream (8-bit, non-interlaced,
 * RGBA) and writes a gray+alpha PNG where every visible pixel can be inked on
 * top of the contract: alpha keeps the background transparent, gray keeps the
 * stroke colour. Real PNG output is required for a proportional-glyph font, so
 * the text next to it stays readable in every viewer.
 */
const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        t[n] = c >>> 0;
    }
    return t;
})();

function crc32(bytes: Uint8Array): number {
    let c = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
}

/**
 * Minimal PNG writer for 8-bit grey images (colorType 0 = alpha mask).
 * Scanlines carry filter 0 and the stream is stored uncompressed, which keeps
 * the worker free of native zlib dependencies.
 */
function encodePng(width: number, height: number, colorType: number, scanlines: Uint8Array): Uint8Array {
    const chunk = (type: string, data: Uint8Array): Uint8Array => {
        const out = new Uint8Array(12 + data.length);
        const view = new DataView(out.buffer);
        view.setUint32(0, data.length);
        for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
        out.set(data, 8);
        view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
        return out;
    };
    const ihdr = new Uint8Array(13);
    const hv = new DataView(ihdr.buffer);
    hv.setUint32(0, width);
    hv.setUint32(4, height);
    ihdr[8] = 8;
    ihdr[9] = colorType;
    const body = deflateStore(scanlines);
    const parts = [
        new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', ihdr),
        chunk('IDAT', body),
        chunk('IEND', new Uint8Array(0))
    ];
    const total = parts.reduce((n, p) => n + p.length, 0);
    const out = new Uint8Array(total);
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
}

export interface SignatureImage {
    /** Deflated grey alpha rows used as the image SMask. */
    mask: Uint8Array;
    /** Deflated ink (black where the student drew). */
    alpha: Uint8Array;
}

export function pngSignatureImage(png: Uint8Array): SignatureImage | null {
    const dec = decodePngRgba(png);
    if (!dec) return null;
    const { width, height, rgba: out } = dec;

    const INK = 40, PAPER = 255;
    const ink = new Uint8Array(height * width);
    const mask = new Uint8Array(height * width);
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const i = (y * width + x) * 4;
            // Ink is where the canvas is not white, whatever the alpha channel says.
            const luma = (out[i] * 299 + out[i + 1] * 587 + out[i + 2] * 114) / 1000;
            const inked = luma < 245 ? Math.min(out[i + 3], 255 - luma) : 0;
            ink[y * width + x] = inked > 8 ? INK : PAPER;
            mask[y * width + x] = inked;
        }
    }
    return { alpha: deflateStore(ink), mask: deflateStore(mask) };
}

/** Flat RGB + alpha mask from an RGBA canvas PNG (seal / manager signature). */
function pngToRgb(png: Uint8Array): { rgb: Uint8Array; mask: Uint8Array } | null {
    const dec = decodePngRgba(png);
    if (!dec) return null;
    const { width, height, rgba } = dec;
    const rgb = new Uint8Array(height * width * 3);
    const mask = new Uint8Array(height * width);
    for (let i = 0, j = 0; i < width * height; i++, j += 4) {
        rgb[i * 3] = rgba[j];
        rgb[i * 3 + 1] = rgba[j + 1];
        rgb[i * 3 + 2] = rgba[j + 2];
        mask[i] = rgba[j + 3];
    }
    return { rgb: deflateStore(rgb), mask: deflateStore(mask) };
}

/** zlib wrapper around stored (uncompressed) deflate blocks. */
function deflateStore(data: Uint8Array): Uint8Array {
    const blocks = Math.max(1, Math.ceil(data.length / 65535));
    const out = new Uint8Array(2 + blocks * 5 + data.length + 4);
    let p = 0;
    out[p++] = 0x78; out[p++] = 0x01;
    for (let b = 0; b < blocks; b++) {
        const start = b * 65535;
        const len = Math.min(65535, data.length - start);
        out[p++] = b === blocks - 1 ? 1 : 0;
        out[p++] = len & 0xff; out[p++] = (len >> 8) & 0xff;
        out[p++] = (~len) & 0xff; out[p++] = ((~len) >> 8) & 0xff;
        out.set(data.subarray(start, start + len), p); p += len;
    }
    // adler32
    let a = 1, bsum = 0;
    for (const byte of data) { a = (a + byte) % 65521; bsum = (bsum + a) % 65521; }
    const adler = ((bsum << 16) | a) >>> 0;
    out[p++] = (adler >>> 24) & 0xff; out[p++] = (adler >>> 16) & 0xff;
    out[p++] = (adler >>> 8) & 0xff; out[p++] = adler & 0xff;
    return out.subarray(0, p);
}

function decodePngRgba(png: Uint8Array): { width: number; height: number; rgba: Uint8Array } | null {
    const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
    let pos = 8;
    let width = 0, height = 0, bitDepth = 0, colorType = 0;
    const idat: Uint8Array[] = [];
    while (pos + 8 <= png.length) {
        const len = view.getUint32(pos);
        let type = '';
        for (let i = 4; i < 8; i++) type += String.fromCharCode(png[pos + i]);
        const data = png.subarray(pos + 8, pos + 8 + len);
        if (type === 'IHDR') {
            width = view.getUint32(pos + 8);
            height = view.getUint32(pos + 12);
            bitDepth = data[8];
            colorType = data[9];
        } else if (type === 'IDAT') {
            idat.push(data.slice());
        } else if (type === 'IEND') break;
        pos += 12 + len;
    }
    if (!width || !height || bitDepth !== 8 || colorType !== 6) return null;

    const total = idat.reduce((n, c) => n + c.length, 0);
    const z = new Uint8Array(total);
    let o = 0;
    for (const c of idat) { z.set(c, o); o += c.length; }
    const inflated = inflateZlib(z);
    if (!inflated || inflated.length < height * (width * 4 + 1)) return null;

    const bpp = 4;
    const stride = width * bpp;
    const out = new Uint8Array(height * stride);
    let prev = new Uint8Array(stride);
    for (let y = 0; y < height; y++) {
        const filter = inflated[y * (stride + 1)];
        const row = inflated.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
        const cur = new Uint8Array(stride);
        for (let x = 0; x < stride; x++) {
            const a = x >= bpp ? cur[x - bpp] : 0;
            const b = prev[x];
            const c = x >= bpp ? prev[x - bpp] : 0;
            let v = row[x];
            switch (filter) {
                case 1: v += a; break;
                case 2: v += b; break;
                case 3: v += (a + b) >> 1; break;
                case 4: {
                    const p = a + b - c;
                    const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                    v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                    break;
                }
            }
            cur[x] = v & 0xff;
        }
        out.set(cur, y * stride);
        prev = cur;
    }
    return { width, height, rgba: out };
}

const LEN_BASE = [3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258];
const LEN_EXTRA = [0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0];
const DIST_BASE = [1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577];
const DIST_EXTRA = [0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13];

/** Inflate a zlib stream (stored + fixed/dynamic Huffman blocks). */
function inflateZlib(input: Uint8Array): Uint8Array | null {
    try {
        const stream = typeof DecompressionStream !== 'undefined'
            ? new DecompressionStream('deflate')
            : null;
        if (stream) {
            // Synchronous fallback is not possible with the stream API; use sync inflate.
            return inflateSync(input.subarray(2, input.length - 4));
        }
        return inflateSync(input.subarray(2, input.length - 4));
    } catch (e) {
        return null;
    }
}

function inflateSync(data: Uint8Array): Uint8Array | null {
    let pos = 0;
    let bit = 0;
    const out: number[] = [];
    const bits = (n: number): number => {
        let v = 0;
        for (let i = 0; i < n; i++) {
            const byte = data[pos + ((bit + i) >> 3)];
            if (byte === undefined) throw new Error('eof');
            v |= ((byte >> ((bit + i) & 7)) & 1) << i;
        }
        bit += n;
        return v;
    };
    const huffman = (lengths: number[]): Record<string, number> => {
        const maxBits = Math.max(...lengths, 0);
        const blCount = new Array(maxBits + 1).fill(0);
        lengths.forEach((l) => { if (l) blCount[l]++; });
        const nextCode: number[] = new Array(maxBits + 1).fill(0);
        let code = 0;
        for (let b = 1; b <= maxBits; b++) {
            code = (code + blCount[b - 1]) << 1;
            nextCode[b] = code;
        }
        const table: Record<string, number> = {};
        lengths.forEach((len, sym) => {
            if (!len) return;
            const c = nextCode[len]++;
            let rev = 0;
            for (let i = 0; i < len; i++) rev |= ((c >> i) & 1) << (len - 1 - i);
            table[`${len}:${rev}`] = sym;
        });
        return table;
    };
    const decode = (table: Record<string, number>): number => {
        let code = 0;
        for (let len = 1; len <= 15; len++) {
            code |= bits(1) << (len - 1);
            const sym = table[`${len}:${code}`];
            if (sym !== undefined) return sym;
        }
        throw new Error('bad code');
    };
    const fixedLit = huffman([
        ...new Array(144).fill(8), ...new Array(112).fill(9),
        ...new Array(24).fill(7), ...new Array(8).fill(8)
    ]);
    const fixedDist = huffman(new Array(32).fill(5));

    let last = 0;
    while (!last) {
        last = bits(1);
        const type = bits(2);
        if (type === 0) {
            bit = (bit + 7) & ~7;
            const p = pos + (bit >> 3);
            const len = data[p] | (data[p + 1] << 8);
            const start = p + 4;
            for (let i = 0; i < len; i++) out.push(data[start + i]);
            bit += (len + 4) * 8;
            continue;
        }
        let lit = fixedLit, dist = fixedDist;
        if (type === 2) {
            const hlit = bits(5) + 257, hdist = bits(5) + 1, hclen = bits(4) + 4;
            const order = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];
            const clLens = new Array(19).fill(0);
            for (let i = 0; i < hclen; i++) clLens[order[i]] = bits(3);
            const clTable = huffman(clLens);
            const lens: number[] = [];
            while (lens.length < hlit + hdist) {
                const sym = decode(clTable);
                if (sym < 16) lens.push(sym);
                else if (sym === 16) { const rep = 3 + bits(2); const v = lens[lens.length - 1]; for (let i = 0; i < rep; i++) lens.push(v); }
                else if (sym === 17) { const rep = 3 + bits(3); for (let i = 0; i < rep; i++) lens.push(0); }
                else { const rep = 11 + bits(7); for (let i = 0; i < rep; i++) lens.push(0); }
            }
            lit = huffman(lens.slice(0, hlit));
            dist = huffman(lens.slice(hlit));
        }
        for (;;) {
            const sym = decode(lit);
            if (sym < 256) out.push(sym);
            else if (sym === 256) break;
            else {
                const li = sym - 257;
                const len = LEN_BASE[li] + bits(LEN_EXTRA[li]);
                const ds = decode(dist);
                const d = DIST_BASE[ds] + bits(DIST_EXTRA[ds]);
                const start = out.length - d;
                for (let i = 0; i < len; i++) out.push(out[start + i]);
            }
        }
    }
    return Uint8Array.from(out);
}

function nameFor(n: number): string {
    return `${n}`;
}

/**
 * Writes every object (original ones verbatim, new ones from strings plus binary
 * image payloads) and a fresh xref table pointing at the real byte offsets.
 */
function assemblePdf(
    original: Uint8Array,
    originalObjects: Map<number, { start: number; end: number; body: string }>,
    updatedStrings: string[],
    maxObject: number,
    imageBodies: Map<number, { header: string; payload: Uint8Array }>,
    rootRef: string
): Uint8Array {
    const chunks: Uint8Array[] = [];
    let offset = 0;
    const offsets = new Map<number, number>();
    const enc = new TextEncoder();

    const push = (bytes: Uint8Array) => {
        chunks.push(bytes);
        offset += bytes.length;
    };

    push(enc.encode('%PDF-1.7\n'));

    // Original objects in order, except the page object, which gets rewritten.
    const objectBodies = new Map<number, string>();
    for (const [num, obj] of originalObjects) objectBodies.set(num, `${num} 0 obj${obj.body}endobj`);
    for (const s of updatedStrings) {
        const num = Number(s.split(' ')[0]);
        objectBodies.set(num, s);
    }

    // The signature content stream is regenerated on every pass, so its body is
    // taken from updatedStrings only (a stale body would duplicate the layers).
    // Image objects live only in imageBodies, so their placeholder body (if any)
    // is dropped: the loop below writes the binary body instead.
    // The xref must also cover trailing object numbers that carry no body (an
    // optional mask that was skipped): the size is the highest number present
    // in the file, never derived from the emitted list alone.
    const highest = Math.max(maxObject, ...[...objectBodies.keys(), ...imageBodies.keys()]);
    const order = [...new Set([...objectBodies.keys(), ...imageBodies.keys()])].sort((a, b) => a - b);
    for (const num of order) {
        offsets.set(num, offset);
        const image = imageBodies.get(num);
        if (image) {
            push(enc.encode(`${num} 0 obj\n${image.header}\nstream\n`));
            push(image.payload);
            push(enc.encode('\nendstream\nendobj\n'));
        } else {
            push(enc.encode(`${objectBodies.get(num)!}\n`));
        }
    }

    const size = Math.max(highest, ...order) + 1;
    const xrefStart = offset;
    let xref = `xref\n0 ${size}\n0000000000 65535 f \n`;
    for (let i = 1; i < size; i++) {
        const off = offsets.get(i);
        xref += off === undefined
            ? '0000000000 65535 f \n'
            : `${off.toString().padStart(10, '0')} 00000 n \n`;
    }
    push(enc.encode(xref));
    push(enc.encode(`trailer\n<< /Size ${size} /Root ${rootRef} >>\nstartxref\n${xrefStart}\n%%EOF\n`));

    const total = chunks.reduce((n, c) => n + c.length, 0);
    const out = new Uint8Array(total);
    let pos = 0;
    for (const c of chunks) {
        out.set(c, pos);
        pos += c.length;
    }
    return out;
}
