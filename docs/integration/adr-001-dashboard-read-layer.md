# ADR-001 — Read-only dashboard integration layer over the Balaka ledger

- Status: Proposed (CTO review required)
- Date: 2026-10-24
- Owner: Backend Engineer
- Deciders: CTO; Finance Controller for financial data definitions
- Supersedes: the SQLite-fed `/home/apps/fasternet-charts` prototype as a reporting source

## Context

FASTERNET's operator dashboard currently reads a hand-maintained SQLite database
(`/home/apps/fasternet-charts/data/dashboard.db`: pools, txns, kur, subscribers,
invoices, budgets). That is a parallel book of record. Balaka will become the accounting
system of record; the dashboard must show figures derived from Balaka's posted ledger,
each with provenance and an honest status, and must show missing data as missing rather
than inventing totals.

Balaka already exposes ledger reads through `DashboardService`, `ReportService`,
`StatementService`, `AgingReportService`, and reconciliation/tax services, and protects
`/api/**` with session or Bearer authentication plus API scopes. There is no FASTERNET
dashboard endpoint, no KUR loan master, and no subscriber billing model yet.

## Decision

1. Add a **read-only, versioned JSON API** `GET /api/fasternet/v1/**` in a new
   `controller/api/FasternetDashboardApiController` backed by
   `service/fasternet/FasternetDashboardReadService`.
2. Reuse existing Balaka services/repositories for every ledger figure. The new layer
   only composes, filters, and annotates them with provenance; it never computes a
   balance from a second source.
3. Define the wire format in `docs/integration/dashboard-contract.md` (v1). Every figure
   is an object carrying `value` (decimal string), `unit`, `status`, `source`, and
   `period`. Unsupported figures are emitted with `value: null` and
   `status: "unavailable"`.
4. Authenticate with the existing Bearer-token/API-client mechanism and require
   `REPORT_VIEW`. The layer is GET-only; no writes, no CSRF surface, no mutations.
5. Treat the SQLite prototype as a **view contract reference only** (which panels and
   labels exist). It stops being a data source once the endpoint is live; no code in
   Balaka reads it.
6. Implement KUR figures in FAS-23 (loan master, verified schedule, reserve) and
   subscriber/billing aggregates from existing `clients`/`invoices`/`transactions` or a
   Finance-Controller-approved subscriber model. Until then they are `unavailable`.

## Reuse map

| Dashboard section | Source of truth | Existing component |
|---|---|---|
| Cash and bank balances | POSTED journal entries on cash/bank accounts | `DashboardService.calculateCashBalance`, `journalEntryRepository.sumDebit/CreditByAccountAndDateRange` |
| Revenue / expense / net profit | POSTED journal entries, period-scoped | `DashboardService`, `ReportService` income statement |
| Receivables / payables | POSTED journals on AR/AP control accounts | `DashboardService`, `AgingReportService` |
| Journal/trial balance drill-down | POSTED journals | `ReportService`, general ledger |
| Bank reconciliation state | imported statements + matched entries | `BankReconciliationService`, `BankReconciliationReportService` |
| KUR schedule / reserve | KUR loan master + lender schedule (FAS-23) | new entities + existing journal posting for repayments |
| Billing summary | Balaka `invoices` + collection transactions | `InvoiceService`, `BillService` as references only |
| Period/closed state | `fiscal_periods` | `FiscalPeriodService` |

## Consequences

Positive:
- One book of record; dashboard totals are auditable to journal entries.
- Missing/unverified data cannot silently appear as zero.
- No schema change required for the read layer itself.

Negative / constraints:
- Dashboard is only as complete as Balaka's configured COA and posted journals; before
  FASTERNET COA/KUR data exists, panels will legitimately show `unavailable`.
- Read endpoints add load to the primary DB; mitigate with read-only transactions and
  bounded date ranges. No caching in v1.
- The prototype UI must be repointed; that is FAS-24 (Frontend).

## Explicitly out of scope (v1)

- Any write/mutation endpoint, payment initiation, or bank credential storage.
- Subscriber self-service, invoicing workflow redesign, tax calculation changes.
- Real-time streaming/websockets; polling on page load is enough.
- Multi-tenant support; FASTERNET is a single company/ledger.
- Replacing Balaka's existing report screens.

## Alternatives considered

1. **Dashboard reads Balaka's HTML report pages** — brittle scraping, no typed status.
2. **Dashboard reads PostgreSQL directly** — bypasses auth/domain rules and duplicates
   accounting logic; rejected.
3. **Keep SQLite as source and reconcile later** — violates single-source-of-truth and
   auditability; rejected.

## Validation

- Contract and endpoints reviewed by CTO; financial definitions confirmed with Finance
  Controller before KUR/billing values are labeled `posted`/`bank-confirmed`.
- Tests in FAS-24/FAS-23 must assert: envelope fields present, decimal strings, status
  semantics, `unavailable` for missing data, and that figures equal the underlying
  ledger service output for the same as-of date.
