# برنامهٔ پشتیبان‌گیری، تطبیق Schema و اجرای امن Migration

> **وضعیت این سند:** فقط برنامه است. هیچ‌کدام از دستورات این سند اجرا نشده و تا تأیید جداگانهٔ مالک اجرا نخواهد شد.
> مبنای فنی: ممیزی فقط-خواندنی Cloudflare (کارگزارشده در گفتگو) + کد شاخهٔ `sec/stage3-bot-connection`.
> دیتابیس هدف: D1 با نام `academy-contracts-dev` (شناسهٔ `2d7fa1bd-f015-43be-b180-1d7b84554ffd`)، حدود ۲۱٫۴ مگابایت.

---

## ۱) داده‌شناسی — چرا ۶۷ قرارداد و ۱۵۵ فایل وجود دارد ولی students و installments خالی‌اند؟

این اختلاف **از دست رفتن داده نیست**؛ اختلاف «مسیر نوشتن» است:

| داده | مسیر نوشتن | نیازی به توکن ربات؟ | وضعیت زنده |
|---|---|---|---|
| قراردادها (۶۷) و فایل‌ها (۱۵۵) | `/api/contracts` (آپلود اپ) و `/sign/:token/…` (امضای هنرجو) | **خیر** — فقط کلید bearer | ✅ ذخیره شده (۶۳ امضاشده، ۴ فعال) |
| هنرجویان، اقساط، خلاصهٔ مالی | `/api/sync/students-batch`، `/api/sync/installments`، `/api/sync/financials` | کد قدیمی: **بله (اشتباه)** | ❌ هرگز ذخیره نشد |
| یادآوری‌ها | `/api/schedule-reminders` | کد قدیمی: **بله (اشتباه)** | ❌ |
| ربات/وبهوک | `academies.bot_token` | — | خالی (رباتی متصل نشده) |

**ریشهٔ مشکل (با شواهد):** در نسخهٔ مستقرشده (main @ `08acaec`)، همهٔ مسیرهای `/api/sync/*` و `/api/schedule-*` پشت یک گیت سراسری بودند:
اگر Secret کلادفلر `TELEGRAM_BOT_TOKEN` تنظیم نشده باشد → پاسخ `500` برای همهٔ این مسیرها. ممیزی نشان داد این Secret روی Worker تنظیم **نیست** و `academies.bot_token` هم خالی است؛ پس هر Push اپ با ۵۰۰ شکست خورده است. اپ هم شکست Push را فقط لاگ می‌کند، بنابراین داده‌های هنرجو/مالی فقط روی خود گوشی (Room) مانده‌اند.

**نتیجهٔ اصلاح این شاخه:** مسیرهای Sync دیگر هیچ توکنی نمی‌خواهند (تأییدشده با تست خودکار `bot-connection.test.ts`). پس از استقرار، دکمهٔ «فعال‌سازی ربات ابری» در اپ داده‌ها را بدون توکن ربات Push می‌کند.

> ⚠️ **نکتهٔ حیاتی قبل از Push دسته‌ای:** تا زمانی که Migration `0008` اجرا نشود، ایندکس یکتای `idx_students_link_hash` روی مقدار خالی، ذخیرهٔ **هنرجوی دوم به بعد** را با خطای UNIQUE رد می‌کند (اولین هنرجو ذخیره می‌شود، بقیه نه). نسخهٔ جدید این حالت را با پیام فارسیِ شفاف گزارش می‌کند و دیگر «۲۰۰ خاموش» نیست، اما حل نهایی آن فقط با اجرای 0008 است (بخش ۵).

---

## ۲) مقایسهٔ Schema زنده با فایل‌های Migration

بر اساس ممیزی فقط-خواندنیِ D1 (بدون تغییر):

| Migration | محتوا | وضعیت در D1 زنده | اقدام در اجرای امن |
|---|---|---|---|
| schema.sql (پایه) | ۶ جدول پایه | ✅ موجود | — |
| 0001_tenancy | ستون‌های `academy_id`، جدول `academies`، `deliveries`، `sms_outbox`، ستون‌های لینک + **ایندکس UNIQUE `idx_students_link_hash`** | ✅ اعمال شده (ایندکس یکتا هنوز زنده است) | ثبت در `d1_migrations` |
| 0002_contracts | `contracts`, `contract_events`, `contract_files` | ✅ | ثبت |
| 0003_seal | ستون‌های مهر/امضا | ✅ | ثبت |
| 0004_academy_bots | ستون‌های `bot_token/bot_username/bot_id/webhook_secret`، `pending_students`, `contract_deliveries` | ✅ (جدول `contract_deliveries` هم هست) | ثبت |
| 0005_file_chunks | `contract_file_chunks` | ✅ | ثبت |
| **0006_token_links** | `contract_pending_links` | ❌ **غایب** — قابلیت «ارسال مجدد لینک امضا» روی تولید خطای داخلی (گرفته‌شده) می‌دهد | **باید اجرا شود** |
| **0007_file_content_nullable** | بازسازی `contract_files` (content قابل NULL) | ✅ **عملاً اعمال شده** (سطرهای chunked با content نال خوانده شدند) | فقط **ثبت** شود؛ اجرای مجدد ممنوع (DROP TABLE دارد) |
| **0008_link_hash_index** | حذف ایندکس یکتا + ایندکس معمولی | ❌ **اعمال نشده** — ریسک Push چند-هنرجو | **باید اجرا شود** (بی‌خطر و سریع؛ فقط ایندکس، نه داده) |
| جدول `d1_migrations` | دفتر اعمال مهاجرت‌ها | ❌ **وجود ندارد** | باید ساخته و مقداردهی شود (بخش ۵) |

**دربارهٔ عدد «۱۷ جدول» گزارش قبلی:** ۱۵ جدولِ دامنه + جداول سیستمی SQLite (مانند `sqlite_sequence` به‌خاطر AUTOINCREMENT در `contract_events`). در مرحلهٔ پشتیبان‌گیری، لیست دقیق با `SELECT name FROM sqlite_master WHERE type='table'` گرفته و با این فهرست مطابقت داده می‌شود؛ هر جدول ناشناخته = توقف و بازبینی.

---

## ۳) برنامهٔ پشتیبان‌گیری (قبل از هر تغییر — فقط خواندن)

### گام ۰ — ثبت نقطهٔ بازیابی ابری (Time Travel)
D1 به‌صورت پیش‌فرض ۳۰ روز Time Travel دارد. دستور زیر فقط گزارش می‌دهد:
```bash
npx wrangler d1 time-travel info academy-contracts-dev --remote
```

### گام ۱ — Export کامل (Schema + داده) به دو نسخه
```bash
npx wrangler d1 export academy-contracts-dev --remote --output=backup-$(date +%Y%m%d-%H%M).sql
# نسخهٔ دوم تفکیکی از جدول‌های حیاتی (برای مقایسه):
npx wrangler d1 export academy-contracts-dev --remote \
  --table contracts --table contract_events --table contract_files --table contract_file_chunks \
  --output=backup-contracts-$(date +%Y%m%d-%H%M).sql
```
هر دو فایل در **دو محل جدا** (خارج از این رایانه و خارج از حساب Cloudflare) نگهداری شود؛ فایل‌ها حاوی دادهٔ شخصی هنرجویان و فایل قرارداد هستند — انتقال/آرشیو فقط رمزنگاری‌شده.

### گام ۲ — کنترل صحت پشتیبان (روی فایل Export، نه روی تولید)
فایل SQL را در یک SQLite محلی باز کنید (کتابخانهٔ `better-sqlite3` همین مخزن کافی است) و این چک‌ها را اجرا کنید:

1. **شمارش‌ها باید دقیقاً با ممیزی بخواند:**
   ```sql
   SELECT COUNT(*) FROM contracts;                 -- انتظار: 67
   SELECT COUNT(*) FROM contract_files;            -- انتظار: 155
   SELECT COUNT(DISTINCT file_key) FROM contract_file_chunks;  -- انتظار: 59 کلید chunked
   SELECT COUNT(*) FROM contract_events;           -- انتظار: 148 (67 CREATED + 63 SIGNED + 16 PDF_VIEW + 1 OPENED + 1 LINK_ROTATED)
   SELECT status, COUNT(*) FROM contracts GROUP BY status;  -- انتظار: SIGNED=63, غیرامضاشده=4
   SELECT COUNT(*) FROM students;                  -- انتظار: 0 (مطابق ممیزی)
   ```
2. **صحت بایت‌به‌بایت فایل‌ها:** برای هر سطر `contract_files`:
   - اگر `content` نال است → بایت‌ها را از `contract_file_chunks` به‌ترتیب `part` بچسبانید؛
   - `sha256` ذخیره‌شده را با **هر دو** الگوریتم بسنجید:
     `sha256(بایت‌های خام)` (نسخهٔ جدید) یا `sha256(decode-latin1(بایت‌های خام) → UTF-8)` (نسخهٔ قدیمی latin1).
     **هر فایل باید با حداقل یکی از دو الگوریتم بخواند**؛ آن‌که با هیچ‌کدام نخواند = فایل خراب → توقف.
   - فایل‌های باینری (PDF امضاشده، مهر PNG) عمداً با الگوریتم دوم می‌خوانند — این طبیعی است (باگ هش نسخهٔ قدیمی).
3. **یک نمونهٔ زندهٔ رفتاری:** قراردادِ تازه و امضاشده را انتخاب کنید و لینک `/sign/:token/pdf` آن را باز کنید؛ هدر `X-Content-SHA256` را با هش بایت‌های دانلودی مقایسه کنید (باید برابر باشد — مسیر خواندن هر دو الگوریتم را پشتیبانی می‌کند).

### گام ۳ — ثبت نتیجه
یک فایل متن کنار پشتیبان: تاریخ، شمارش‌ها، تعداد فایل‌هایی که با الگوریتم استاندارد/legacy خواندند، تعداد ناخوانا (باید ۰ باشد).

---

## ۴) روش بازیابی پشتیبان (وقتی لازم شد)

| اولویت | روش | دستور/مرحله |
|---|---|---|
| ۱ | **Time Travel** (سریع‌ترین، بدون فایل) | `npx wrangler d1 time-travel restore academy-contracts-dev --remote --timestamp="YYYY-MM-DDTHH:MM:SSZ"` — فقط با تأیید مالک؛ خود Restore پیش از اجرا یک نسخهٔ پشتیبان خودکار می‌گیرد |
| ۲ | **Import به D1 موازی** (امن‌ترین) | ۱) یک D1 *جدید* بسازید؛ ۲) `npx wrangler d1 execute <db-jadid> --remote --file=backup.sql -y`؛ ۳) چک‌های شمارش/هش گام ۲ را روی آن اجرا کنید؛ ۴) یک Worker آزمایشی با binding به D1 جدید بالا بیاورید و دانلود نمونهٔ قرارداد را تست کنید؛ ۵) فقط بعد از تأیید، binding Worker اصلی را به D1 بازیابی‌شده تغییر دهید |
| ۳ | Import درجا روی همان D1 | **ممنوع مگر با تأیید کتبی** — روی ۱۵۵ فایل واقعی اثر مستقیم دارد |

بعد از هر بازیابی: شمارش‌های گام ۲ دوباره اجرا شود + دانلود دستی حداقل ۳ قرارداد (یکی inline، یکی chunked، یکی تازه) و مقایسهٔ هش.

---

## ۵) Runbook اجرای امن Migration (فقط با تأیید جداگانه — اینجا اجرا نمی‌شود)

**پیش‌نیازها:**
- پشتیبان گام ۲ بخش ۳ با نتیجهٔ «۰ فایل ناخوانا» ثبت شده باشد.
- پنجرهٔ زمانی کوتاه که اپ/ربات ترافیک ندارد (دکمه‌های اپ استفاده نمی‌شود).
- ترمینال با توکن حساب با حداقل دسترسی (فقط D1 Edit) — توکن در کد/لاگ/گزارش نوشته نمی‌شود.

**ترتیب دقیق:**

### مرحلهٔ A — ساخت و مقداردهی `d1_migrations` (bootstrap)
چون `d1_migrations` وجود ندارد، اجرای مستقیم `wrangler d1 migrations apply` از 0001 دوباره شروع می‌کند و روی ستون تکراری متوقف می‌شود. پس ابتدا دفتر را بسازید و مهاجرت‌های **از قبل اعمال‌شده** را ثبت کنید:
```bash
npx wrangler d1 execute academy-contracts-dev --remote --command "
CREATE TABLE IF NOT EXISTS d1_migrations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);" -y

npx wrangler d1 execute academy-contracts-dev --remote --command "
INSERT INTO d1_migrations (name) VALUES
 ('0001_tenancy.sql'),('0002_contracts.sql'),('0003_seal.sql'),
 ('0004_academy_bots.sql'),('0005_file_chunks.sql'),
 ('0007_file_content_nullable.sql');" -y
```
> 0007 ثبت می‌شود چون ممیزی نشان داد عملاً اعمال شده؛ 0006 و 0008 ثبت **نمی‌شوند** چون باید واقعاً اجرا شوند.

### مرحلهٔ B — اجرای دو مهاجرت باقی‌مانده
```bash
npx wrangler d1 migrations apply academy-contracts-dev --remote
```
wrangler فقط 0006 و 0008 را به همین ترتیب اجرا می‌کند (هر مهاجرت در تراکنش خودش). اگر ترجیح دادید دستی اجرا کنید:
```bash
npx wrangler d1 execute academy-contracts-dev --remote --file=migrations/0006_token_links.sql -y
npx wrangler d1 execute academy-contracts-dev --remote --file=migrations/0008_link_hash_index.sql -y
```

### مرحلهٔ C — صحت‌سنجی بعد از Migration
```sql
SELECT name FROM sqlite_master WHERE type='table' AND name IN ('contract_pending_links','d1_migrations');
SELECT name FROM sqlite_master WHERE type='index' AND name='idx_students_link_hash';          -- انتظار: نتیجه‌ای ندهد (DROP شده)
SELECT name FROM sqlite_master WHERE type='index' AND name='idx_students_link_hash_lookup';   -- انتظار: موجود
SELECT COUNT(*) FROM contracts; SELECT COUNT(*) FROM contract_files;                          -- انتظار: 67 / 155 (بدون تغییر)
```
+ یک دانلود آزمایشی قرارداد امضاشده و مقایسهٔ هش.

### دربارهٔ 0007 — تنها مهاجرتِ بالاخطر
0007 جدول `contract_files` را **DROP و بازسازی** می‌کند. چون ممیزی نشان داد نسخهٔ زنده از قبل nullable است، **هرگز نباید دوباره اجرا شود**. اگر روزی دیتابیس از نو ساخته شد، `wrangler d1 migrations apply` آن را در ترتیب درست و در تراکنش اجرا می‌کند و همین مسیرِ مجاز است. اجرای دستی/تکراری آن روی دیتابیس فعلی ممنوع است.

### بعد از Migration — بازیابی دادهٔ هنرجویان
1. Worker نسخهٔ جدید مستقر شود (تأیید جداگانهٔ deploy).
2. مالک در اپ: توکن ربات → «اتصال ربات به سرور» → «فعال‌سازی ربات ابری» (Push داده‌ها).
3. نتیجهٔ مورد انتظار: پیام «همگام‌سازی سرور انجام شد · هنرجو: N · قسط: M» و دیده‌شدن همان اعداد در D1.

---

## ۶) مواردی که تا تأیید جداگانهٔ مالک متوقف می‌مانند

| # | اقدام | نوع اثر |
|---|---|---|
| ۱ | اجرای `d1 export` (بخش ۳) | فقط خواندن |
| ۲ | ساخت و ثبت `d1_migrations` (بخش ۵-آ) | نوشتن در D1 واقعی |
| ۳ | اجرای Migrationهای 0006 و 0008 (بخش ۵-ب) | نوشتن در D1 واقعی |
| ۴ | Deploy شاخهٔ `sec/stage3-bot-connection` روی Worker | انتشار واقعی |
| ۵ | حذف/افزودن Secretها (در صورت نیاز به `WEBHOOK_SECRET` سراسری) | تغییر Secret |
| ۶ | (اختیاری، بعداً) یک‌بارِ backfill هش‌های legacy به هش استاندارد | نوشتن در D1 واقعی |
| ۷ | انتخاب پنل پیامک | مرحلهٔ ۶ برنامهٔ کلی — فعلاً SMS همچنان DISABLED است |
