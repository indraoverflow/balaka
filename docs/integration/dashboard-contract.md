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
| `kur.reserve.gap` | IDR | max(0, outstanding − qualifying reserve), same basis as §3.3/§3.5 | `unavailable` until the Finance Controller signs off the formula and maps the reserve accounts |
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

`installments[].status` is lowercase (`unpaid`, `partial`, `paid`, `overpaid`). If no
`loanId` is supplied the endpoint uses the single active KUR loan. Zero active loans
return `404 kur_loan_not_found`; several active loans return `400 invalid_request`
instead of guessing. Both KUR endpoints also return a `missing[]` array of
`{key, reason, owner}` for figures that cannot be derived; entries never replace a
missing value with zero.

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
double counting of the same cash.

`outstanding = obligation.amount − posted, non-reversed payments recorded through asOf`.
`reserve.gap = max(0, outstanding − qualifying reserve)`. The gap is the **unfunded
outstanding balance**, not the unfunded contractual amount: a partial payment reduces the
funding need exactly once and is never subtracted again. `obligation.nextDue` keeps the
contractual amount. A full-installment buffer, if policy wants one, is expressed through
`targetReserve`, never by inflating the gap.

`composition[]` lists the reserve-tracker rows composing the buckets (`entryId`, `pool`
= `EARMARKED` \| `SEPARATE_ACCOUNT`, `kind`, `amount`, `entryDate`, `sourceRef`), and
`cashAccounts[]` lists the cash/bank COA codes in `cash.actual`. Empty arrays mean the
source has no such rows; they are never a fabricated zero (§7).

### 3.4 `GET /api/fasternet/v1/billing/summary?period=YYYY-MM`

**Not implemented in this contract version.** Per [ADR-002](./adr-002-isp-billing-boundary.md),
subscriber, package, invoice and collection data belong to the separate ISP
Operations/Billing application, not to Balaka's generic `clients`/`invoices`. Balaka must
not grow a subscriber master. Until the ISP application exists, is integrated through the
documented API, and its account mappings are approved by the Finance Controller, this
endpoint is not served and the corresponding figures stay `unavailable`; see
[`isp-billing-contract-v1.md`](./isp-billing-contract-v1.md). Never returns a fabricated
subscriber count or billing total.

### 3.5 `GET /api/fasternet/v1/kur/alert?asOf=YYYY-MM-DD` (additive, FAS-96)

Additive section added by FAS-96, revised by FAS-108 for the Finance Controller's
[FAS-104](/FAS/issues/FAS-104) conditions. It does **not** change the shapes of §3.2 or
§3.3. The dashboard/alert feed may consume it, but the state is not an accounting
posting.

> **Every value in the example below is illustrative and unapproved.** The installment
> 2,757,500, the 5,000,000 cash balance, the 1,000,000 reserve and the 1,757,500
> shortfall are placeholder numbers for shape only. They are not founder figures, not a
> verified lender schedule, and not an approved policy. The alternative 3,050,000
> figure used in the worked examples is equally unapproved. No value here may be seeded
> as verified or displayed as confirmed.

```json
{
  "contractVersion": "fasternet.dashboard/v1",
  "generatedAt": "2026-10-24T09:15:00Z",
  "asOf": "2026-10-24",
  "currency": "IDR",
  "alert": {
    "state": "unconfigured",
    "severity": "unknown",
    "reason": "Threshold policy is unconfigured: the CEO and Finance Controller have not approved the KUR repayment alert thresholds, so no green state is produced",
    "frameworkLevel": "unavailable",
    "frameworkLevelReason": "v1 has no forward cash-flow forecast: the framework's 30-day projected-shortfall test and 90-day runway condition are not evaluated, and no Finance Controller-recorded forecast-backed level exists; frameworkLevel is unavailable and a v1 green is not framework GREEN",
    "frameworkLevelSource": "framework §8 (rev 3) + framework-s8-errata: AMBER runway 30-90 days, all-in burn base, 1 month = 30.44 days",
    "shortfall": { "amount": "1757500.00", "currency": "IDR", "basis": "reserve-gap", "status": "posted" },
    "dueDate": "2026-11-21",
    "daysRemaining": 28,
    "installmentNumber": 1,
    "obligation": {
      "amount": "2757500.00", "paidAmount": "0.00", "outstanding": "2757500.00",
      "paymentStatus": "unpaid", "scheduleStatus": "estimate",
      "provenance": "founder-estimate", "sourceKind": "estimate",
      "sourceRef": "calculated-amortization"
    },
    "reserve": {
      "qualifying": "1000000.00", "earmarked": "1000000.00",
      "separateAccount": "0.00", "status": "posted",
      "composition": [
        { "entryId": "illustrative-uuid", "pool": "EARMARKED", "kind": "EARMARK",
          "amount": "1000000.00", "entryDate": "2026-10-22", "sourceRef": "illustrative" }
      ]
    },
    "cash": {
      "actual": "5000000.00", "minimumRequired": null,
      "source": "Balaka POSTED cash/bank account balances",
      "asOf": "2026-10-24", "status": "posted",
      "accountCodes": ["1.1.01"]
    },
    "payment": { "status": "unpaid", "paidAmount": "0.00", "countsAsPaid": false,
                 "note": "a reserved amount is never a completed payment" },
    "policy": {
      "status": "unconfigured", "approvalReference": null, "approvedBy": null,
      "approvedAt": null,
      "thresholds": {
        "minimumCash": null, "targetReserve": null,
        "fundingPaceTolerancePercent": null,
        "orangeShortfall": null, "redShortfall": null
      }
    },
    "provenance": {
      "loanId": "illustrative-uuid",
      "nextScheduleRowId": "illustrative-uuid",
      "scheduleProvenance": "founder-estimate",
      "scheduleSourceKind": "estimate",
      "scheduleSourceRef": "calculated-amortization",
      "reserveComposition": [
        { "entryId": "illustrative-uuid", "pool": "EARMARKED", "kind": "EARMARK",
          "amount": "1000000.00", "entryDate": "2026-10-22", "sourceRef": "illustrative" }
      ],
      "cashAccountCodes": ["1.1.01"],
      "cashAsOf": "2026-10-24"
    },
    "assumptions": [
      "cash source: Balaka POSTED cash/bank account balances as of 2026-10-24; no forward cash-flow forecast is applied",
      "reserve source: reserve movements recorded on or before 2026-10-24 (earmarked plus separate reserve account, no double counting)",
      "obligation: first KUR installment with an unpaid balance as of 2026-10-24; payment totals are counted only through 2026-10-24",
      "reserved funds are earmarked only; installment payment status is reported separately and a reserve is never a payment",
      "framework level: v1 evaluates no forward forecast, so framework §8's 30-day projected-shortfall test and 90-day runway condition are not evaluated; frameworkLevel is unavailable and a v1 green is not framework GREEN. The Finance Controller continues the 90-day forecast and manual escalation outside v1",
      "threshold policy is unconfigured; no threshold value was assumed"
    ],
    "invariant": "a reserved amount is never a completed payment"
  },
  "missing": [
    { "key": "kur.alert.policy",
      "reason": "no CEO + Finance Controller-approved threshold policy is recorded",
      "owner": "Finance Controller" },
    { "key": "kur.alert.frameworkLevel",
      "reason": "v1 has no forward cash-flow forecast; the framework §8 runway and 30-day projected-shortfall tests are not evaluated",
      "owner": "Finance Controller" }
  ]
}
```

**Reserve gap and shortfall.** `obligation.amount` is always the contractual installment.
`obligation.outstanding = amount − posted, non-reversed payments through asOf`.
`shortfall.amount` and §3.3 `reserve.gap` are both
`max(0, outstanding − qualifying reserve)`. The partially paid installment remains the
next obligation until fully paid, but only the **funding gap** nets the partial payment.
A full-installment buffer belongs in `targetReserve`, not in `shortfall`.

Worked examples (illustrative, unapproved):

| Scenario | Installment | Paid | Outstanding | Reserve | Gap |
|---|---|---|---|---|---|
| Paid 1,000,000 from operating cash; reserve unchanged 2,050,000 | 3,050,000 | 1,000,000 | 2,050,000 | 2,050,000 | **0** (contractual basis would wrongly report 1,000,000) |
| Paid 1,000,000 from the reserve, leaving 1,050,000 | 3,050,000 | 1,000,000 | 2,050,000 | 1,050,000 | **1,000,000** (contractual basis would wrongly report 2,000,000) |

**Cash floor.** `minimumCash` is a **total-cash floor**, not an unencumbered
operating-cash floor. `cash.actual` already includes the earmarked reserve (it is POSTED
cash/bank balance), so the reserve must not be subtracted a second time. The single test
is `cash.actual − outstanding >= minimumCash`: after paying the outstanding, total cash
must stay at or above the floor. Example: cash 3,050,000, reserve 1,000,000,
minimumCash 1,000,000, outstanding 3,050,000 → paying leaves 0, below the floor, so the
state is **orange**. The old `(cash − minimumCash) >= gap` test passes on the same
inputs and understates the risk, so it is not used. `cash.actual` is still `unavailable`
(never zero) when no cash/bank balance can be derived.

**Framework level.** `alert.frameworkLevel` ∈ `GREEN`, `AMBER`, `RED`, `CRITICAL`,
`unavailable`, mapped to framework [FAS-2](/FAS/issues/FAS-2) `framework` §8 and
`framework-s8-errata` (AMBER runway band **30-90 days**, all-in burn base, 1 month =
30.44 days). v1 has no forward forecast and cannot evaluate the §8 30-day
projected-shortfall test or the 90-day runway condition. Therefore:

- `frameworkLevel` is `unavailable` with a reason and assumption stating the limitation,
  unless the Finance Controller has recorded a forecast-backed level (none exists).
- A v1 alert `green` is **not** framework GREEN and must never be rendered as such.
  `yellow`/`orange` must not mask a framework RED/CRITICAL.
- The Finance Controller continues the 90-day forecast and manual escalation outside v1.

**State rules.** `alert.state` ∈ `green`, `yellow`, `orange`, `red`, `unconfigured`,
`unavailable`. `alert.state` is a v1 operational state and is not the framework level.

| state | Meaning |
|---|---|
| `green` | Reserve funded to the approved target and actual cash covers the approved minimum, due date not passed |
| `yellow` | Reserve accumulation is behind the approved funding plan (gap, or target-reserve deficit beyond the funding-pace tolerance) |
| `orange` | Projected cash flow may be insufficient (actual total cash below the minimum, or cash after paying the outstanding falls below the total-cash minimum, or the gap reaches the orange cutoff), **or** an unverified schedule reaches the red cutoff (red is withheld) |
| `red` | The next unpaid installment is overdue, or a **verified** lender schedule shows a gap at/above the red cutoff |
| `unconfigured` | No CEO + Finance Controller-approved threshold policy is recorded. Never rendered as green |
| `unavailable` | Required input (obligation, reserve movements, or cash) cannot be derived. Never treated as zero |

**Threshold policy.** Values are stored by
`PUT /api/fasternet/v1/kur/alert-policy?loanId=...` (scope `kur-alert:write` plus
`ALERT_CONFIG`). All five thresholds and the approval evidence must be supplied
together; there are no defaults and no partial policies. `approvalReference` records
the CEO + Finance Controller decision. Until a complete policy exists, the read API
returns `unconfigured`, `policy.status: "unconfigured"` and `null` thresholds.

**Separation of states.** `shortfall.status` and `cash.status` use the §0.4 figure
statuses. `obligation.scheduleStatus` is `verified` only for a `verified`/`bank-confirmed`
loan whose next row is a lender schedule row; otherwise it is `estimate`. `payment`
is the actual installment payment state and is never derived from the reserve.
`alert.state` is a derived projection, not a posted journal.

**Provenance (§0/§7).** A financial alert must be traceable. `alert.provenance` carries
the loan master id, the next schedule row id, the row's schedule provenance/source kind/
source ref, the reserve-tracker rows composing each bucket (pool, entry id, kind, amount,
date, source ref), and the cash/bank COA codes with the as-of date used for
`cash.actual`. `sourceRef: "calculated-amortization"` alone is not sufficient provenance.
The v1 reserve tracker has no separate account/tag id, so the tracker entry id and source
ref are the available identifiers; that gap is stated rather than invented.

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
- The REST API (`/api/accounts`) exposes `isCashBank`. Omitting it from a `PUT` leaves the
  stored flag unchanged, so an API update cannot silently remove an account from the cash
  universe; sending an explicit `false` follows the same un-flag rules as the form.

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
| KUR reserve / alert §3.3, §3.5 | loan master id + schedule row id + reserve-tracker entry ids per pool (with source refs) + cash/bank COA codes and as-of date |
| Billing | invoice ids or journal range + collection references |

## 8. Open questions (owner)

1. Finance Controller: confirm COA codes for founder capital, KUR liability (current /
   non-current), reserve/earmarked cash, subscriber revenue, and AR. Until confirmed
   those figures stay `unavailable`.
2. Finance Controller: the reserve-gap basis (outstanding) and total-cash floor are
   defined in §3.3/§3.5 and await final semantic sign-off; approve the alert thresholds
   (green/yellow/orange/red). Until approved, thresholds stay `estimate` +
   `notes: "unconfigured"`.
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
