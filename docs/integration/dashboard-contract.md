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
| `cash.accounts` | series | per-account balances | `posted` |
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

`series` contains named arrays with parallel `periods`:

```json
{
  "periods": ["2026-07", "2026-08", "2026-09", "2026-10"],
  "cash":    ["0.00", "0.00", "0.00", "12500000.00"],
  "reserve": [null, null, null, null],
  "revenue": ["0.00", "0.00", "0.00", "8000000.00"],
  "expense": ["0.00", "0.00", "0.00", "3000000.00"]
}
```

Rules: every series has the same length as `periods`; unavailable points are `null`, not
`0`; series values are decimal strings.

## 5. Error responses

| HTTP | Body | When |
|---|---|---|
| 400 | `{"error":"invalid_request","message":"..."}` | bad/missing `asOf`/`period` |
| 401 | `{"error":"unauthorized"}` | no/invalid Bearer token or session |
| 403 | `{"error":"forbidden","message":"REPORT_VIEW required"}` | insufficient permission |
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
