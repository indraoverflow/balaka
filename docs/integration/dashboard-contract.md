# FASTERNET Dashboard Data Contract v1

- Contract id: `fasternet.dashboard/v1`
- Status: Draft for CTO + Finance Controller review; Frontend Engineer to consume
- Owner: Backend Engineer
- Related: [ADR-001](./adr-001-dashboard-read-layer.md), [FAS-21](/FAS/issues/FAS-21), [FAS-23](/FAS/issues/FAS-23), [FAS-24](/FAS/issues/FAS-24)
- Source of truth: Balaka POSTED journal entries and their supporting records. The SQLite
  prototype is **not** a data source.

## 0. Rules

1. Money is a JSON **string** containing a decimal (`"1250000.00"`), never a float.
   Currency is always `"IDR"`; minor units are not used (whole rupiah).
2. A figure that cannot be derived is `value: null` with `status: "unavailable"`. It is
   never rendered as `0`.
3. Every figure object carries `status`, `source`, and `period`. No bare numbers.
4. `status` is one of:

   | status | Meaning |
   |---|---|
   | `posted` | Derived only from POSTED, non-voided journal entries |
   | `draft` | Includes DRAFT/unposted records; must be shown as provisional |
   | `estimate` | Computed or assumed (e.g. calculated amortization, unconfigured threshold) |
   | `bank-confirmed` | Matched to an imported bank statement/reference |
   | `reconciled` | Bank-confirmed and closed through Balaka reconciliation |
   | `unavailable` | No ledger/source data; `value` must be null |

5. `draft` and `posted` are never mixed in one figure. If a view needs both, return two
   figures.

## 1. Envelope

All endpoints return:

```json
{
  "contractVersion": "fasternet.dashboard/v1",
  "generatedAt": "2026-10-24T03:30:00Z",
  "asOf": "2026-10-23",
  "period": { "from": "2026-10-01", "to": "2026-10-31", "label": "2026-10", "status": "open" },
  "currency": "IDR",
  "company": { "id": "uuid", "name": "FASTERNET" },
  "figures": {
    "cash.total": { "...": "Figure" }
  },
  "series": {},
  "missing": [
    { "key": "kur.schedule", "reason": "no verified lender schedule attached", "owner": "Finance Controller" }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `contractVersion` | string | Always `fasternet.dashboard/v1` |
| `generatedAt` | ISO-8601 UTC string | Server clock |
| `asOf` | `YYYY-MM-DD` | Effective date for balances |
| `period.from` / `period.to` | `YYYY-MM-DD` | Inclusive period for flows |
| `period.label` | string | `YYYY-MM` |
| `period.status` | `open` \| `closed` | From `fiscal_periods` |
| `currency` | string | `IDR` |
| `company` | object | `id`, `name` from `company_config` |
| `figures` | object | Map of `key` → Figure |
| `series` | object | Named arrays for charts (see §4) |
| `missing` | array | Each `{ key, reason, owner }`; empty array if none |

## 2. Figure object

```json
{
  "key": "cash.total",
  "label": "Cash & bank balance",
  "value": "12500000.00",
  "unit": "IDR",
  "status": "posted",
  "period": { "asOf": "2026-10-23" },
  "source": {
    "system": "balaka",
    "kind": "ledger",
    "ref": "chart_of_accounts:ASSET/cash-bank",
    "computedBy": "DashboardService#calculateCashBalance"
  },
  "notes": null
}
```

| Field | Type | Notes |
|---|---|---|
| `key` | string | Stable machine key, e.g. `cash.total` |
| `label` | string | Human label (Indonesian/English as UI needs) |
| `value` | string \| null | Decimal string; null iff `unavailable` |
| `unit` | `IDR` \| `count` \| `percent` \| `text` | |
| `status` | enum | §0.4 |
| `period` | object | `{from,to}` for flows, `{asOf}` for balances |
| `source.kind` | `ledger` \| `bank-statement` \| `loan-agreement` \| `manual` \| `none` | |
| `source.ref` | string | Account codes, table/record references, or file reference |
| `source.computedBy` | string | Service/method, for auditability |
| `notes` | string \| null | Assumptions or caveats |

## 3. Endpoints (all `GET`, read-only, Bearer auth, scope `REPORT_VIEW`)

### 3.1 `GET /api/fasternet/v1/overview?asOf=YYYY-MM-DD`

Returns the envelope with these figure keys:

| key | unit | Source | v1 status |
|---|---|---|---|
| `cash.total` | IDR | cash/bank COA balances | `posted` |
| `revenue.period` | IDR | income statement revenue | `posted` |
| `expense.period` | IDR | income statement expense | `posted` |
| `netProfit.period` | IDR | revenue − expense | `posted` |
| `profitMargin.period` | percent | netProfit / revenue | `posted` or `unavailable` when revenue = 0 |
| `receivables.total` | IDR | AR control accounts | `posted` |
| `payables.total` | IDR | AP control accounts | `posted` |
| `equity.founderCapital` | IDR | founder-capital equity accounts | `unavailable` until COA mapping confirmed by Finance Controller |
| `kur.installmentDue.next` | IDR | KUR schedule (FAS-23) | `unavailable` until verified schedule exists |
| `kur.reserve.earmarked` | IDR | reserve tracker (FAS-23) | `unavailable` until configured |
| `kur.reserve.gap` | IDR | max(0, due − qualifying reserve) | `unavailable` until configured |
| `subscribers.active` | count | subscriber master | `unavailable` (no subscriber master in v1) |
| `subscribers.new.period` | count | subscriber master | `unavailable` |
| `subscribers.cancelled.period` | count | subscriber master | `unavailable` |
| `billing.mrr` | IDR | active plan recurring fees | `unavailable` |
| `billing.invoiced.period` | IDR | invoices in period | `unavailable` until billing mapping confirmed |
| `billing.collected.period` | IDR | posted collections | `unavailable` until billing mapping confirmed |
| `billing.outstanding` | IDR | AR from issued invoices | `unavailable` until billing mapping confirmed |
| `billing.overdue` | IDR | past-due invoice balance | `unavailable` |

Every unavailable key is still emitted (with `value: null`, `status: "unavailable"` and a
`missing` entry) so the frontend can render an explicit "data not available" state instead
of a blank panel.

`cash.accounts` is **not** a figure. Per-account cash balances live only in
`series.cash.accounts` (see §4); `cash.total` remains the scalar figure. Consumers must not
expect a scalar `figures["cash.accounts"]`.

### 3.2 `GET /api/fasternet/v1/kur/schedule`

Returns the loan master + installment rows from FAS-23.

```json
{
  "contractVersion": "fasternet.dashboard/v1",
  "generatedAt": "...",
  "asOf": "...",
  "currency": "IDR",
  "loan": {
    "lender": "BRI",
    "product": "KUR",
    "reference": null,
    "principalOriginal": "99270000.00",
    "disbursementDate": "2026-10-21",
    "termMonths": 36,
    "installmentContractual": "2757500.00",
    "firstDueDate": "2026-11-21",
    "maturityDate": "2029-10-21",
    "provenance": "founder-estimate",
    "agreementAttached": false,
    "scheduleAttached": false
  },
  "installments": [
    {
      "number": 1,
      "dueDate": "2026-11-21",
      "amount": "2757500.00",
      "principal": null,
      "interest": null,
      "fee": null,
      "paidAmount": "0.00",
      "paidDate": null,
      "outstanding": "2757500.00",
      "status": "unpaid",
      "bankRef": null,
      "evidenceRef": null,
      "source": { "kind": "estimate", "ref": "calculated-amortization" }
    }
  ]
}
```

`loan.provenance` ∈ `verified` (signed agreement + lender schedule attached),
`bank-confirmed` (lender record), `founder-estimate`. Founder figures are never shown as
confirmed. `amount` is the contractual installment; `principal`/`interest`/`fee` are null
until the official schedule supplies them.

Control figures derived from the schedule:

| key | unit | Derivation | status |
|---|---|---|---|
| `kur.nextDue.date` | text (`YYYY-MM-DD`) | first unpaid installment | `estimate` until schedule verified, then `verified` |
| `kur.nextDue.amount` | IDR | that installment's contractual amount | same as above |
| `kur.nextDue.daysRemaining` | count | due date − asOf (may be negative = overdue) | same as above |
| `kur.paid.count` / `kur.paid.total` | count / IDR | installments with evidence + reconciliation | `posted`/`reconciled` |
| `kur.lastPayment.status` | text | last installment's payment state | `unavailable` if no payments recorded |

### 3.3 `GET /api/fasternet/v1/kur/reserve?asOf=YYYY-MM-DD`

```json
{
  "contractVersion": "fasternet.dashboard/v1",
  "asOf": "2026-10-24",
  "currency": "IDR",
  "figures": {
    "cash.actual":     { "value": "0.00", "status": "posted" },
    "reserve.earmarked": { "value": null, "status": "unavailable" },
    "reserve.separateAccount": { "value": null, "status": "unavailable" },
    "obligation.nextDue": { "value": null, "status": "unavailable" },
    "obligation.completed": { "value": "0.00", "status": "posted" },
    "reserve.gap": { "value": null, "status": "unavailable" }
  },
  "invariant": "reserving funds never marks an installment paid"
}
```

Qualifying reserve = earmarked balance + separately held reserve account, with no
double counting of the same cash. `reserve.gap = max(0, obligation.nextDue − qualifying
reserve)`.

### 3.4 `GET /api/fasternet/v1/billing/summary?period=YYYY-MM`

Billing aggregates from Balaka invoices and collection journals. Subscriber-level rows
are out of scope until a subscriber model is approved. Never returns a fabricated
subscriber count.

## 4. Series (charts)

`series` contains a block-level `status` plus named arrays with parallel `periods`:

```json
{
  "status": "posted",
  "periods": ["2026-07", "2026-08", "2026-09", "2026-10"],
  "cash":    ["0.00", "0.00", "0.00", "12500000.00"],
  "reserve": [null, null, null, null],
  "revenue": ["0.00", "0.00", "0.00", "8000000.00"],
  "expense": ["0.00", "0.00", "0.00", "3000000.00"],
  "cash.accounts": [
    { "accountCode": "1-1001", "accountName": "Kas", "balance": "1000000.00", "status": "posted" }
  ]
}
```

**Status and provenance (F6).** v1 series are **posted-only**. Every non-null point is a
balance/flow computed only from `POSTED`, non-voided journal entries; drafts and estimates
are never mixed into a series. `series.status` is therefore:

| value | Meaning |
|---|---|
| `posted` | At least one point is derivable and every non-null point is posted |
| `unavailable` | No point is derivable; all points are `null` |

A `null` point means that specific period is unavailable; it is never rendered as `0`.
There is no series-level `draft`/`estimate` state in v1. If a future series needs one, it
is an additive change that also adds a separate series rather than mixing states.

Provenance per series in v1:

| array | Derivation | Non-null point status |
|---|---|---|
| `cash` | `DashboardService#calculateCashBalance` at each month end (current month at `asOf`) | `posted` |
| `revenue` | `ReportService#generateIncomeStatement` monthly totalRevenue | `posted` |
| `expense` | `ReportService#generateIncomeStatement` monthly totalExpense | `posted` |
| `reserve` | KUR reserve tracker (FAS-23); all `null` until configured | `posted` or `null` |

**`series.cash.accounts` (F3).** Per-account cash/bank balances are an array of objects,
never a scalar figure:

```json
{ "accountCode": "1-1001", "accountName": "Kas", "balance": "1000000.00", "status": "posted" }
```

| field | type | notes |
|---|---|---|
| `accountCode` | string | COA code |
| `accountName` | string | COA name |
| `balance` | decimal string | debit-normal balance as of `asOf` |
| `status` | enum | `posted` in v1 |

Membership is explicit: only accounts with `chart_of_accounts.is_cash_bank = true` are
included. There is no account-code prefix rule, so receivables (`1.1.04`, `1.1.08`),
prepaid expenses (`1.1.05`–`1.1.07`) and input VAT (`1.1.09`) are never cash, even when
they carry a balance.

The sum of `cash.accounts[].balance` equals `figures["cash.total"].value`. When no cash/bank
account is configured the array is empty and both `cash.total` and `missing["cash.accounts"]`
carry the reason.

**Runtime maintenance of the cash universe (FAS-87).** `is_cash_bank` is not only a
migration backfill:

- The chart-of-accounts create/edit form lets an operator mark an ASSET leaf as cash/bank.
  This sets `cash_bank_source = EXPLICIT` and writes a settings audit event. Headers and
  non-ASSET accounts are rejected.
- Creating or re-pointing a `company_bank_accounts` row at an ASSET leaf sets
  `is_cash_bank = true` with `cash_bank_source = BANK_LINK` (CSV import follows the same
  rule). The UI warns that the linked account joins the cash universe.
- Deleting or re-pointing the bank account clears the flag **only** when its source is
  `BANK_LINK` and no other bank account still links it. `EXPLICIT` (operator decision) and
  `MIGRATED` (V007 backfill) flags stay cash so an unlink cannot silently understate cash.
  An explicit un-flag is rejected while a bank account still links the account.
- The ZIP chart-of-accounts export/import carries `is_cash_bank`, so a round-trip keeps the
  flag; imported flags are treated as `EXPLICIT`.

The Finance Controller still owns the FASTERNET cash-account mapping and must confirm the
universe after any account change.

Rules: every series array has the same length as `periods`; unavailable points are `null`,
not `0`; series values are decimal strings.

## 5. Error responses

| HTTP | Body | When |
|---|---|---|
| 400 | `{"error":"invalid_request","message":"..."}` | bad/missing `asOf`/`period` |
| 401 | `{"error":"unauthorized","message":"Authentication required"}` | no/invalid Bearer token or session (SecurityConfig API entry point) |
| 403 | `{"error":"FORBIDDEN","message":"The token user's role lacks the required permission"}` | authenticated but token scope does not resolve to `REPORT_VIEW` (`ApiExceptionHandler`) |
| 422 | `{"error":"period_closed","message":"..."}` | requested period is closed and the figure cannot be produced under policy |
| 503 | `{"error":"ledger_unavailable","message":"..."}` | DB unavailable; never substitute stale/fabricated values |

## 6. Compatibility

- Additive changes only within v1 (new keys/fields). Removing/renaming a field or
  changing a type requires `fasternet.dashboard/v2`.
- Frontend must ignore unknown keys and render `unavailable` explicitly.
- `generatedAt` changes on every request; it is not a cache key.

## 7. Provenance requirements per section

| Section | Required source evidence |
|---|---|
| Cash / revenue / expense / receivable / payable | COA codes + posted journal date range |
| KUR installment | loan master id + schedule row id + provenance |
| KUR reserve | account/tag ids composing the reserve and the obligation row |
| Billing | invoice ids or journal range + collection references |

## 8. Open questions (owner)

1. Finance Controller: confirm COA codes for founder capital, KUR liability (current /
   non-current), reserve/earmarked cash, subscriber revenue, and AR. Until confirmed
   those figures stay `unavailable`.
2. Finance Controller: approve reserve-gap formula and alert thresholds (green/yellow/
   orange/red); until approved thresholds are `estimate` + `notes: "unconfigured"`.
3. CTO: confirm whether the dashboard service account uses a dedicated API client with
   only `REPORT_VIEW`.

## 9. Auth and local integration

The API is read-only, but it is still authenticated. There is no anonymous dashboard mode.

| Item | v1 rule |
|---|---|
| Scheme | `Authorization: Bearer <token>`; session cookie is accepted for same-host interactive use only |
| Scope | `analysis:read` (the existing `ApiScope` that resolves to `REPORT_VIEW`) |
| Account | A **dedicated** API client, not a founder's personal login. Created under Pengaturan › API Klien (`api_clients`), with an acting service user that holds `REPORT_VIEW` |
| Token | Obtained with the OAuth2 `client_credentials` grant: `POST /api/oauth/token` (`grant_type=client_credentials`, `client_id`, `client_secret`). Tokens are short-lived and opaque |
| Secret storage | Client secret and issued token live server-side only: the dashboard BFF reads `DASHBOARD_TOKEN_FILE` (mode 600, outside the repo, e.g. `/etc/fasternet/dashboard-token`) or `DASHBOARD_TOKEN`. Neither value is committed, logged, or sent to the browser |
| Dev base URL | Balaka dev instance `http://127.0.0.1:10001`; dashboard BFF: `BALAKA_API_URL=http://127.0.0.1:10001` (see `/paperclip/fasternet/dashboard/README.md`) |
| Production base URL | Set by deploy config, not in source. The BFF calls Balaka over the internal network |
| Rotation | Deactivate the API client under Pengaturan › API Klien, create a new secret (shown once), replace the token file, restart the BFF |

Local verification sequence (no secret in git):

```bash
# 1. Balaka dev instance (test profile + local PostgreSQL)
curl -s http://127.0.0.1:10001/actuator/health

# 2. Exchange a dedicated client's credentials for a short-lived token (run on the host)
TOKEN=$(curl -s -X POST http://127.0.0.1:10001/api/oauth/token \
  -d grant_type=client_credentials \
  -d client_id="$DASHBOARD_CLIENT_ID" \
  -d client_secret="$DASHBOARD_CLIENT_SECRET" | jq -r .access_token)

# 3. Call the contract endpoint
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:10001/api/fasternet/v1/overview?asOf=2026-10-24"
```

`DASHBOARD_CLIENT_ID`/`DASHBOARD_CLIENT_SECRET` are host environment variables, not repo
files. A 401 means no/invalid token; a 403 means the client scope is not `analysis:read`
(→ `REPORT_VIEW`). Out of scope: bank credentials, transfer initiation, writes of any kind.
