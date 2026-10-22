// ============================================================
// Printable payment-receipt PDF, composed from scratch on the Worker
// (no external libraries). The visual DATA is exactly the app's
// DigitalReceiptDialog / PdfDocumentHelper receipt: transaction code,
// Shamsi date+time, student, installment, amount, method, destination
// account, tracking number, notes. No fabricated values are ever added.
//
// Persian text is rendered as vector paths (same glyph engine as the
// contract PDF), so no font is embedded and the file prints cleanly.
// ============================================================

import { persianText, latinText, toPersianDigits } from './pdf';

export interface ReceiptPdfData {
    academyName: string;
    academyPhone: string;
    currency: string;
    transactionId: string;
    studentName: string;
    courseTitle: string;
    installmentNumber: number;
    totalInstallments: number;
    amount: number;
    paidDateShamsi: string;
    paidTime: string;
    methodLabel: string;
    destinationAccount: string;
    trackingNumber: string;
    notes: string;
    isReversed: boolean;
    reversalReason: string;
    source: string;
}

const W = 595; // A4 width in points
const H = 842; // A4 height

function fa(n: number | string): string {
    return toPersianDigits(String(n));
}

function money(n: number): string {
    return fa(n.toLocaleString('en-US'));
}

/** One right-aligned "label: value" line; unmapped glyphs are skipped, never faked. */
function row(y: number, label: string, value: string, size = 11, bold = 0.7): string {
    if (!value) return '';
    return persianText(W - 56, y, `${label}: ${value}`, size, '0.10 0.12 0.16', bold, true);
}

/**
 * Builds a minimal one-page PDF whose content stream draws the receipt.
 * Structure mirrors the fixture PDFs the test-suite already parses:
 * classic xref, uncompressed content stream, no external dependencies.
 */
export function composeReceiptPdf(d: ReceiptPdfData): Uint8Array {
    const ops: string[] = [];

    // ---------- frame ----------
    ops.push('0.55 0.62 0.70 RG 1.2 w');
    ops.push(`40 40 m ${W - 40} 40 l ${W - 40} ${H - 40} l 40 ${H - 40} l h S\n`);
    ops.push('0.85 0.88 0.92 RG 0.8 w');
    ops.push(`52 ${H - 150} m ${W - 52} ${H - 150} l S\n`); // under header
    ops.push(`52 170 m ${W - 52} 170 l S\n`);               // above footer

    // ---------- header ----------
    ops.push(persianText(W - 56, H - 78, d.academyName || 'آموزشگاه', 22, '0.08 0.10 0.14', 1, true));
    if (d.isReversed) {
        ops.push(persianText(W - 56, H - 104, '⚠ این رسید برگشت (ابطال) شده است', 13, '0.75 0.15 0.15', 1, true));
    } else {
        ops.push(persianText(W - 56, H - 104, 'رسید پرداخت شهریه', 15, '0.25 0.30 0.38', 0.8, true));
    }
    ops.push(persianText(56, H - 78, `شماره رسید: ${d.transactionId || '-'}`, 10, '0.35 0.39 0.45', 0.5, false));

    // ---------- body rows ----------
    const inst = d.totalInstallments > 0
        ? `قسط ${fa(d.installmentNumber)} از ${fa(d.totalInstallments)}${d.courseTitle ? ` (${d.courseTitle})` : ''}`
        : (d.courseTitle || '');
    let y = H - 190;
    const line = (label: string, value: string, size = 12, bold = 0.7) => {
        const op = row(y, label, value, size, bold);
        if (op) { ops.push(op); y -= 34; }
    };

    line('هنرجو', d.studentName, 13, 0.9);
    line('دوره / قسط', inst);
    ops.push(persianText(W - 56, y, 'مبلغ پرداخت‌شده', 13, '0.10 0.12 0.16', 0.9, true));
    ops.push(persianText(56, y, `${money(d.amount)} ${d.currency}`, 15, '0.05 0.35 0.20', 1, false));
    y -= 40;
    line('تاریخ', d.paidDateShamsi);
    line('ساعت', d.paidTime);
    line('روش پرداخت', d.methodLabel);
    line('حساب مقصد', d.destinationAccount);
    line('کد رهگیری', d.trackingNumber);
    if (d.source === 'online') line('نوع', 'پرداخت آنلاین');
    if (d.notes) line('توضیحات', d.notes);
    if (d.isReversed && d.reversalReason) line('دلیل برگشت', d.reversalReason);

    // ---------- footer ----------
    ops.push(persianText(W / 2, 132, 'این رسید به‌صورت الکترونیکی صادر شده است', 10, '0.45 0.50 0.56', 0.5, true));
    if (d.academyPhone) {
        ops.push(persianText(W / 2, 106, `تلفن آموزشگاه: ${d.academyPhone}`, 11, '0.25 0.30 0.38', 0.7, true));
    }
    ops.push(persianText(W / 2, 76, 'با تشکر از پرداخت شما', 13, '0.10 0.12 0.16', 0.9, true));
    ops.push(latinText(56, 52, d.transactionId || '', 8));

    const content = ops.join('\n');

    // ---------- minimal PDF assembly (classic xref) ----------
    const objects = [
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${W} ${H}] /Contents 4 0 R >>`,
        `<< /Length ${content.length} >>\nstream\n${content}\nendstream`
    ];
    let body = '%PDF-1.4\n';
    const offsets: number[] = [];
    objects.forEach((obj, i) => {
        offsets.push(body.length);
        body += `${i + 1} 0 obj\n${obj}\nendobj\n`;
    });
    const xrefStart = body.length;
    body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
    for (const off of offsets) body += `${String(off).padStart(10, '0')} 00000 n \n`;
    body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`;

    return new TextEncoder().encode(body);
}
