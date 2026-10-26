# راهنمای توسعه‌دهنده: افزودن پنل پیامکی یا درگاه پرداخت جدید

> این سند دقیقاً توضیح می‌دهد که برای «پنل پیامکی X» یا «درگاه Y» چه چیزی را
> پیاده‌سازی کنید، کجا، با چه تنظیمات/Secret و چه تست‌هایی — بدون بازنویسی
> منطق مالی، یادآوری اقساط، ربات تلگرام یا سایر ارائه‌دهندگان.
>
> قانون پایهٔ پروژه: **فقط مستندات رسمی ارائه‌دهنده مبنای پیاده‌سازی است؛
> هیچ endpoint یا پارامتری حدس زده نمی‌شود.** اگر مستندات عمومی نیست،
> اسلات ارائه‌دهنده صادقانه «در انتظار مستندات» می‌ماند (نمونهٔ موجود:
> رایس‌اس‌ام‌اس).

---

## بخش ۱ — افزودن یک پنل پیامکی جدید

### ۱.۱ قراردادی که باید پیاده‌سازی شود

فایل: `cloudflare-telegram-backend/src/sms.ts`

```ts
export interface SmsProviderAdapter {
    readonly id: string;                       // همان id ثبت‌شده در رجیستری
    // آمادگی محلی تنظیمات (بدون شبکه) — کامل بودن فیلدهای الزامی
    validateSettings(settings): { ok: boolean; missing: string[] };
    // «فقط» endpoint رایگان رسمی (اعتبار/موجودی) — هرگز پیامک قابل‌هزینه نمی‌فرستد
    checkConnection(settings): Promise<SmsConnectionCheckResult>;
    // ارسال «یک» پیامک متنی به «یک» شمارهٔ نرمال‌شده
    sendText(settings, to, text): Promise<SmsAdapterCallResult>;
    // اختیاری — فقط وقتی مستندات رسمی ارسال قالبی (خط خدماتی) دارند
    sendTemplate?(settings, to, templateId, parameters): Promise<SmsAdapterCallResult>;
    // اختیاری — فقط وقتی مستندات رسمی گزارش دلیوری دارند
    queryDelivery?(settings, messageId): Promise<SmsDeliveryInfo>;
}
```

قواعد اجباری هر آداپتور:
- هر پاسخ خطا به **کدهای استاندارد سیستم** نگاشت شود (`SmsErrorCode`:
  `UNAUTHENTICATED | NO_CREDIT | INVALID_LINE | INVALID_MOBILE | RATE_LIMITED |
  REJECTED | PROVIDER_ERROR | UNEXPECTED_RESPONSE | NETWORK | TIMEOUT | …`).
  متن فارسیِ قابل‌فهم برای مدیر **هرگز نباید کلید یا مقدار محرمانه** داشته باشد.
- فراخوانی‌ها با `fetchWithTimeout` (timeout سخت ۱۲ ثانیه) انجام شود.
- شناسهٔ پیگیری پنل (`messageId`/`packId`) در نتیجه برگردد؛ لایهٔ مشترک آن را
  در دفتر `deliveries` ثبت می‌کند.
- پاسخ موفقِ مشکوک را جعل نکنید: مثلاً در SMS.ir طبق مستندات رسمی،
  `messageIds[i] = 0` یعنی گیرنده در لیست سیاه و `null` یعنی شماره نامعتبر یا
  متن بیش‌ازحد — هر دو باید FAILED گزارش شوند، نه SENT.

### ۱.۲ مسیر فایل و ثبت در رجیستری

همه‌چیز در `src/sms.ts` و فقط در **دو نقطه**:

1. تعریف ارائه‌دهنده در `SMS_PROVIDERS` (اطلاعات غیرحساس برای UI و اعتبارسنجی):
   ```ts
   mypanel: {
       id: 'mypanel', label: 'پنل من',
       docsUrl: 'https://…',            // لینک مستندات رسمیِ بررسی‌شده
       docsPending: undefined,          // فقط وقتی مستندات عمومی نیست true
       fields: [ { key: 'api_key', kind: 'secret', required: true, … },
                 { key: 'line',   kind: 'line',   required: true, … } ],
       capabilities: { creditCheck: true, deliveryReport: true, templateSend: false }
   }
   ```
2. ثبت آداپتور در `SMS_ADAPTERS`:
   ```ts
   const SMS_ADAPTERS: Record<string, SmsAdapter> = { smsir: …, ryesms: …, mypanel: myPanelAdapter };
   ```

**هیچ فایل دیگری تغییر نمی‌کند** — یادآوری اقساط، رسیدها، ربات، ارسال دستی و
تنظیمات همه از `resolveSmsProvider()` + `sendSmsText()`/`sendSmsTemplate()`
عبور می‌کنند.

### ۱.۳ تنظیمات و Secretها

- تنظیمات هر آموزشگاه در جدول namespaced `academy_settings` (D1) با کلیدهای
  `<providerId>_<field>` ذخیره می‌شود (`mypanel_api_key`, `mypanel_line`) —
  **بدون migration جدید**. UI و API ذخیره/خواندن خودکار از `SMS_PROVIDERS`
  ساخته می‌شود.
- Secret ابری اختیاری: نامش باید `<ID_UPPER>_API_KEY` باشد (مثلاً
  `MYPANEL_API_KEY`) و در `resolveSmsProvider` به‌صورت خودکار بر مقدار
  ذخیره‌شده اولویت می‌گیرد. تنظیم:
  `npx wrangler secret put MYPANEL_API_KEY`.
- امنیت (تست‌شده، باید برای پنل جدید هم برقرار بماند):
  - مقدار کلید هرگز در پاسخ API برنمی‌گردد (فقط `hasValue`)، لاگ/متن خطا نمی‌شود؛
  - ذخیرهٔ پیکربندی ناقص رد می‌شود؛ فیلد secret خالی = «قبلی بماند»؛
  - نوشتن این کلیدها از مسیر همگام‌سازی اپ (`/api/sync/settings`) مسدود است
    (الگوی رزرو در `src/handlers/api.ts` — کلید جدید را به همان الگو اضافه کنید:
    `sms_provider`، `smsir_`، `rysesms_`، و برای پنل جدید `mypanel_`).

### ۱.۴ فعال‌سازی / غیرفعال‌سازی بدون حذف سوابق

- فعال‌سازی: مدیر در اپ پنل را انتخاب و فیلدها را ذخیره می‌کند + دکمهٔ
  «آزمایش اتصال» (فقط endpoint رایگان). ارسال واقعی فقط با اجازهٔ صریح مالک
  و پس از ورود کلید واقعی شروع می‌شود.
- غیرفعال‌سازی/تعویض: `PUT /api/messaging/sms/config` با `provider: ''` یا
  پنل دیگر — همهٔ فیلدهای ذخیره‌شدهٔ پنل قبلی و **تمام سوابق ارسال**
  (`deliveries` + `sms_outbox`) دست‌نخورده می‌مانند (تست‌شده: تست ۱۰
  `provider-architecture.test.ts`). بازگشت به پنل قبلی نیازی به واردکردن
  دوبارهٔ کلید ندارد.
- ارسال واقعی این پنل هرگز بدون تکمیل فیلدهای الزامی ممکن نیست
  (`resolveSmsProvider` → `not-selected | incomplete | docs-pending`).

### ۱.۵ تست‌های لازم (الگو: `test/provider-architecture.test.ts`)

- قرارداد HTTP با fetch-mock: URL/متد/هدر احراز هویت/body دقیقاً طبق مستندات؛
- نگاشت همهٔ کدهای خطای مستندشدهٔ پنل به `SmsErrorCode` + timeout/http-500؛
- صفر فراخوانی شبکه وقتی: پیکربندی ناقص، `docsPending`، شمارهٔ نامعتبر؛
- ضدتکرار: دومین فراخوانی با همان `ledgerId` = یک فراخوانی پنل در مجموع؛
- جداسازی آموزشگاه‌ها (پنل آموزشگاه A برای B استفاده نمی‌شود) و ماسک‌بودن کلید؛
- آزمایش اتصال فقط endpoint رایگان؛ عدم افشای کلید در پاسخ‌ها.
- اجرای: `npx vitest run` + `npx tsc -p tsconfig.json --noEmit` +
  `npx wrangler deploy --dry-run` (همه باید سبز شوند؛ هیچ تستی حذف/تضعیف
  نمی‌شود).

---

## بخش ۲ — افزودن یک درگاه پرداخت جدید (فعال‌سازی صریح لازم دارد)

### ۲.۱ قراردادی که باید پیاده‌سازی شود

فایل: `cloudflare-telegram-backend/src/gateway.ts`

```ts
export interface GatewayProvider {
    id: string;
    capabilities?: { statusCheck: boolean; cancel: boolean; refund: boolean };
    // الزامی: ساخت لینک پرداخت میزبانی‌شدهٔ پنل برای همین تراکنش
    createPaymentLink(txn, callbackUrl): Promise<{ redirectUrl: string }>;
    // الزامی و تنها منبع اعتماد: تأیید سرور-به-سرور با کلید فروشندگی
    verifyPayment(txn): Promise<{ ok:true; paidAmount; ref; raw } | { ok:false; … }>;
    // اختیاری — فقط در صورت پشتیبانی رسمی:
    getPaymentStatus?(txn): Promise<{ ok:true; status:'PENDING'|'PAID'|'FAILED'|'CANCELED'|'EXPIRED'; … }>;
    cancelPayment?(txn): Promise<{ ok:true; raw } | { ok:false; code; error }>;
    refundPayment?(txn, amount): Promise<{ ok:true; ref; raw } | { ok:false; code; error }>;
}
```

کدهای خطای استاندارد پرداخت: `PaymentErrorCode` (مثل `UNAUTHENTICATED`,
`AMOUNT_MISMATCH`, `NOT_SUPPORTED`, …). تأیید باید همیشه سمت سرور باشد؛
مبلغ تأییدشده باید «دقیقاً» با مبلغ باقی‌ماندهٔ قسط برابر باشد وگرنه
`AMOUNT_MISMATCH` — هیچ اعمال خودکاری انجام نمی‌شود.

### ۲.۲ ثبت و شرط‌های سه‌گانهٔ فعال‌سازی

```ts
registerProviderFactory('mygateway', (merchantId, apiKey) => new MyGatewayAdapter(merchantId, apiKey));
```

حلقهٔ پرداخت (لینک یکتا، دفتر تراکنش‌ها، حالت‌های PENDING/PAID/FAILED/…،
CAS ضد اعتبارسنجی دوباره، به‌روزرسانی قسط و رسید) از قبل کامل است؛ برای
فعال‌شدن واقعی **هر سه** شرط لازم است و نبودِ هرکدام = مسدود کامل:

1. پرچم استقرار `ONLINE_PAYMENTS_ENABLED="true"` (در wrangler.toml/داشبورد —
   پیش‌فرض خاموش)؛
2. یک factory ثبت‌شده در کد برای همان `gateway_provider`؛
3. اعتبارهای کامل مدیر (`gateway_provider` + `gateway_merchant_id` +
   `gateway_api_key` در تنظیمات آموزشگاه).

تا برقرارشدن هر سه: `/api/payments/link` و `/api/payments/verify` پاسخ ۴۰۹
می‌دهند، صفحات `/pay/*` یک صفحهٔ خنثی برمی‌گردانند (بدون probe توکن/دیتابیس)
و هیچ UI/پیامی «پرداخت آنلاین» را نام نمی‌برد. کلید درگاه هرگز در پاسخ API،
لاگ یا خطا برنمی‌گردد. **مسیر کارت‌به‌کارت → رسید → تأیید مدیر مستقل و
دست‌نخورده است.**

### ۲.۳ تست‌های لازم

- الگو: `test/payments-flow.test.ts` (معماریِ فعال‌شده با provider جعلی) و
  `test/gateway-hidden.test.ts` (مخفی‌بودن کامل با پرچم خاموش).
- برای درگاه جدید: قرارداد create/verify با mock، خطاهای مستندشده →
  `PaymentErrorCode`، amount-mismatch، idempotency (تأیید دوباره دوبار اعمال
  نمی‌شود)، و مخفی‌ماندن در حالت پیش‌فرض.

---

## بخش ۳ — چک‌لیست نهایی قبل از اعلام «پنل/درگاه جدید آماده است»

1. مستندات رسمی بررسی و لینک + تاریخ بررسی در `docs/SMS_PANELS.md` ثبت شده؛
2. آداپتور فقط از مستندات رسمی ساخته شده (هیچ endpoint حدسی)؛
3. کدهای خطا به واژگان استاندارد نگاشت شده‌اند؛ خروجی‌ها عاری از کلید؛
4. رجیستری + فیلدها + Secret (در صورت تمایل) تعریف شده‌اند؛
5. الگوی رزرو کلید در `/api/sync/settings` تکمیل شده؛
6. تست‌های بخش ۱.۵/۲.۳ اضافه شده و کل مجموعه (همهٔ فایل‌ها) سبز است؛
7. `tsc --noEmit` و `wrangler deploy --dry-run` بدون خطا؛
8. هیچ migration تولیدی، deploy یا push بدون اجازهٔ مالک انجام نشده.
