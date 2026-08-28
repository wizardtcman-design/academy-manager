# ⚡ Academy Telegram Backend for Cloudflare Workers

A production-grade, highly scalable backend designed specifically for **Cloudflare Workers**, **Cloudflare D1 (SQL Database)**, **Cloudflare KV**, and **Cron Triggers**.

---

## 🏗 Architecture Overview

```
┌─────────────────────────┐
│   Android Mobile App    │
└────────────┬────────────┘
             │ HTTPS REST API
             │ (Bearer Auth: API_SECRET_KEY)
             ▼
┌────────────────────────────────────────────────────────┐
│               Cloudflare Worker Backend                │
│                                                        │
│  • HTTPS REST API Endpoints (/api/...)                 │
│  • Telegram Webhook Receiver (/webhook)                │
│  • Scalable SQL Relational Storage (Cloudflare D1)     │
│  • Fast Cache & Indexing (Cloudflare KV)               │
│  • Scheduled Background Jobs (Cloudflare Cron Trigger) │
│  • Server-Side Secure Bot Token (Never in Android)     │
└────────────┬─────────────────────────────▲─────────────┘
             │ Telegram Bot API            │ Webhook Updates
             ▼                             │ (/start <STUDENT_ID>)
┌────────────────────────────────────────────────────────┐
│                  Telegram Bot Servers                  │
└────────────────────────────────────────────────────────┘
```

---

## 🚀 Key Features

1. **Zero Bot Token Exposure**: The Telegram Bot Token is stored strictly as a Cloudflare Secret. The Android app only knows the Worker URL and API Secret Key.
2. **Instant Webhook & Deep Linking**: Handles Telegram `/start <STUDENT_ID>` deep links with sub-millisecond response times across Cloudflare's global edge network.
3. **Interactive Persian Menu**: Real-time balance calculations (`💰 وضعیت مالی`), due installment tables (`📅 اقساط من`), receipts (`🧾 رسیدهای من`), and academy contact info.
4. **Cloudflare D1 (SQL) Persistence**: Stores student records, linked chat IDs, installment schedules, financial balances, and message logs.
5. **Automated Reminders (Cloudflare Cron Triggers)**: Cloudflare runs hourly background cron jobs to check due reminders and dispatch Telegram notifications without requiring the Android device to be awake or connected.

---

## 🛠 Cloudflare Services Required

1. **Cloudflare Workers** (Standard Compute / Serverless Edge)
2. **Cloudflare D1** (Serverless SQLite Relational Database)
3. **Cloudflare KV** (Key-Value fast cache namespace `STUDENT_KV`)
4. **Cloudflare Secrets** (Encrypted server-side variables for `TELEGRAM_BOT_TOKEN`, `API_SECRET_KEY`, `WEBHOOK_SECRET`)
5. **Cloudflare Cron Triggers** (Scheduled triggers defined in `wrangler.toml`)

---

## 📋 Environment Variables & Secrets

| Variable | Type | Description |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | Secret | Bot token obtained from `@BotFather` (Server-side only) |
| `API_SECRET_KEY` | Secret | Random secure string (e.g. 32+ characters) used by Android App |
| `WEBHOOK_SECRET` | Secret | (Optional) Token for `X-Telegram-Bot-Api-Secret-Token` |
| `ENVIRONMENT` | Var | `production` or `development` |
| `BOT_USERNAME` | Var | (Optional) Fallback bot username without `@` |

---

## 🚀 Exact Deployment Steps

### Step 1: Install Wrangler and Dependencies
```bash
cd cloudflare-telegram-backend
npm install
```

### Step 2: Login to Cloudflare
```bash
npx wrangler login
```

### Step 3: Create Cloudflare D1 Database & KV Namespace
```bash
# 1. Create the D1 Database
npx wrangler d1 create academy-telegram-db

# 2. Create the KV Namespace
npx wrangler kv:namespace create STUDENT_KV
```
> *Copy the `database_id` and `kv_namespace id` output from the above commands and paste them into `wrangler.toml`.*

### Step 4: Apply Database Schema to Remote D1
```bash
npx wrangler d1 execute academy-telegram-db --file=schema.sql --remote
```

### Step 5: Configure Cloudflare Secrets
```bash
# Set your Telegram Bot Token
npx wrangler secret put TELEGRAM_BOT_TOKEN

# Set your API Secret Key (copy this to the Android app settings)
npx wrangler secret put API_SECRET_KEY

# (Optional) Set Webhook Secret
npx wrangler secret put WEBHOOK_SECRET
```

### Step 6: Deploy to Cloudflare Workers
```bash
npx wrangler deploy
```
*Your Worker will be live at: `https://academy-telegram-worker.<your-subdomain>.workers.dev`*

### Step 7: Set Telegram Webhook
Run the following POST request (or use the Android App's test connection flow) to register the webhook:
```bash
curl -X POST "https://academy-telegram-worker.<your-subdomain>.workers.dev/api/set-webhook" \
  -H "Authorization: Bearer <YOUR_API_SECRET_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"webhookUrl": "https://academy-telegram-worker.<your-subdomain>.workers.dev/webhook"}'
```

---

## 📡 API Endpoints Reference

All endpoints except `/api/status` require header: `Authorization: Bearer <API_SECRET_KEY>`

### 1. Health & Status
- **`GET /api/status`**: Checks worker health and validates server-side Telegram connection.

### 2. Bot & Webhook Management
- **`POST /api/test-connection`**: Tests `getMe` on Telegram and returns bot username & details.
- **`POST /api/set-webhook`**: Configures Telegram to send incoming messages to `/webhook`.

### 3. Messaging & Reminders
- **`POST /api/send-message`**: Sends a direct message to a student's Telegram Chat ID.
  ```json
  {
    "chatId": "123456789",
    "studentId": "student-uuid",
    "studentName": "Ali Rezaei",
    "message": "پیام تستی شما",
    "withMenu": true
  }
  ```
- **`POST /api/schedule-reminder`**: Schedules an installment reminder for automatic dispatch by Cloudflare Cron.
  ```json
  {
    "studentId": "student-uuid",
    "studentName": "Ali Rezaei",
    "chatId": "123456789",
    "installmentId": "inst-uuid",
    "reminderType": "REMINDER_3_DAYS",
    "message": "یادآوری ۳ روز مانده به سررسید قسط...",
    "scheduledEpoch": 1756460400000
  }
  ```

### 4. Data Synchronization
- **`POST /api/sync/student`**: Syncs single student details (id, name, phone, chat ID, status).
- **`POST /api/sync/students-batch`**: Batch syncs students array.
- **`POST /api/sync/installments`**: Batch syncs installment schedules.
- **`POST /api/sync/financials`**: Syncs student tuition, paid, and debt amounts for bot menu balance queries.
- **`POST /api/sync/settings`**: Syncs academy phone, address, name, and currency unit.
- **`POST /api/generate-link`**: Generates `https://t.me/BOT_USERNAME?start=STUDENT_ID`.
- **`GET /api/logs`**: Returns recent Telegram message audit logs.
