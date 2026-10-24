# ADR-002 — ISP Operations/Billing is a separate bounded system that posts into Balaka through its documented API

- Status: Accepted for the architecture boundary. Implementation is gated by Finance Controller sign-off on account mappings and by the approvals listed in §9.
- Date: 2026-10-24
- Owner: CTO
- Deciders: CTO for technical boundary; Finance Controller for accounting semantics, account mappings and revenue/AR recognition; CEO for funding, priorities and any change to the approved target architecture.
- Related: CTO charter §3 (target architecture), ADR-001 (dashboard read layer), `docs/integration/isp-billing-contract-v1.md`, FAS-21 conditions, FAS-49/FAS-54 dashboard contract.
- Verified against: `fasternet/main` @ `d73cbb4a` (Balaka upstream baseline `182c32c8` plus FASTERNET merges).

## 1. Context

FASTERNET needs two different kinds of software:

1. **Accounting** — chart of accounts, journals, ledger, reports, tax, period close. Balaka is the accounting system of record.
2. **ISP operations and recurring billing** — subscriber identity, service address, package, activation/suspension/cancellation, monthly invoice generation, collection workflow, arrears, churn. None of this is an accounting-engine concern and Balaka has no subscriber master.

The CTO charter mandates that ISP operations live in a separate application/module with a separate database, integrated with Balaka through a documented API or an explicitly approved import/export boundary. It also forbids rebuilding Balaka's accounting engine, sharing tables, or writing into Balaka's database from the billing system.

Today there is no ISP application. Balaka's generic `clients`, `invoices` and `transactions` modules exist, and ADR-001 §6 / dashboard contract §3.4 still contain permissive wording that would allow a subscriber master to grow inside Balaka. That wording conflicts with the CTO condition recorded on FAS-21 and is corrected in this change.

## 2. Verified Balaka capabilities (not documentation claims)

Checked in the checkout at `d73cbb4a`:

| Capability | Verified fact |
|---|---|
| Authentication | OAuth 2.0 client_credentials for unattended callers: `POST /api/oauth/token`, RFC 6749 §4.4; client secret stored BCrypt; token scopes intersected with the service user's role permissions (`ApiClientService`, `OAuthTokenApiController`, `docs/developer-guide/api/01-authentication.md`). |
| Direct posting | `POST /api/transactions` creates **and posts** one transaction from an existing journal template (`TransactionApiController#createTransaction`). Requires `transactions:post` scope + `TRANSACTION_CREATE` + `TRANSACTION_POST`. |
| Retry safety | `POST /api/transactions` accepts `Idempotency-Key` (≤100 chars). The key is persisted on the transaction in the same commit as the posting; a repeat returns `200` with the original transaction; concurrent duplicates race on a unique index (`TransactionApiService#createTransactionDirect`, `Transaction#idempotencyKey`). |
| Free-form journals | `POST /api/transactions/journal-entry` creates a **DRAFT** only, with account UUIDs; posting is a second call. It has **no** Idempotency-Key support. |
| Templates | `GET /api/templates` (scope `templates:read`) exposes template ids and metadata. Templates support fixed accounts and `accountSlots` overrides. |
| Accounts | `GET /api/accounts` (scope `accounts:read`) exposes chart-of-accounts UUIDs and `isCashBank`, but not write access by default. |
| Void / reversal | `POST /api/transactions/{id}/void` posts a reversal; posted transactions are immutable. |
| Posting controls | Posting validates the fiscal period and requires balanced lines; closed periods reject ordinary postings. |
| Read model | `GET /api/fasternet/v1/overview` is a read-only, scope-gated composed view of POSTED ledger data (ADR-001). |
| No subscriber model | There is no subscriber/package entity in Balaka; `clients`/`invoices` are generic accounting AR documents, not an ISP operational model. |

## 3. Decision

1. **Separate bounded application.** Build FASTERNET ISP Operations & Billing as its own application with its **own database**. It owns subscriber, package, subscription lifecycle, invoices, collections and ISP operational metrics.
2. **Balaka remains the only accounting system of record.** The ISP application never owns GL balances, journals, chart-of-accounts mappings, tax postings or period state. It must not create a second ledger.
3. **Integration is API-only.** The approved write path is Balaka's documented REST API, authenticated with a dedicated `client_credentials` API client. No direct SQL, no shared tables, no shared database/schema, no reading Balaka's internal tables, and no database links.
4. **Templates, not free-form account selection, for automated postings.** The Finance Controller approves a small, versioned set of Balaka journal templates for ISP events (subscription revenue, installation revenue, collection, credit note/refund, etc.). The ISP application submits `templateId` + amount + date + description + source. It does not send arbitrary account UUIDs on the automated path. `accountSlots` is used only where the approved template explicitly allows a bounded slot (for example, operating bank vs. KUR reserve bank), and the allowed values come from Finance-Controller-approved configuration.
5. **Retry-safe by construction.** Every automated ledger posting carries a deterministic `Idempotency-Key` derived from the ISP event: `fasternet:isp:{eventType}:{sourceRecordId}`. The ISP application also keeps a durable outbox row with the same key and a unique constraint, so a lost response, replay or double-click cannot double-post revenue or collections.
6. **Staged automation.** Until the integration passes reconciliation tests for at least one complete billing period and the Finance Controller approves automation in writing, events that would touch the ledger are staged for human review and posted by an accountant. The adapter supports both modes behind a configuration flag; the default is staged.
7. **Status separation is mandatory.** Invoice issuance, payment notification, cash receipt, bank settlement and accounting posting are separate fields with separate transitions. No status implies another. A posted invoice is not cash; a recorded payment is not bank-reconciled; a bank-confirmed receipt is not necessarily posted to the GL yet.
8. **The dashboard reads both systems.** The management dashboard composes accounting figures from Balaka's read API and operational metrics from the ISP application's read API. It does not write to either, and it must label each figure with its system, period, status and freshness.
9. **Runtime/toolchain.** Prefer the team's existing Java/Spring Boot + PostgreSQL toolchain for the new application unless a documented ADR selects something else. Do not introduce a new language, framework, database or message broker merely for novelty. A message broker is not required for v1; a database-backed transactional outbox is the simplest reliable equivalent.
10. **No money movement.** Neither application initiates bank transfers or stores banking credentials. Payment execution stays outside both systems and is recorded only after human execution with evidence.

## 4. System boundary

| Domain | System of record | Writable by ISP app? | Notes |
|---|---|---|---|
| Subscriber identity, contact (minimised), service address | ISP Operations | Yes | Personal data minimised and access-controlled. |
| Package catalogue, recurring fee, installation fee | ISP Operations | Yes | Fees are commercial inputs, not GL balances. |
| Activation / suspension / cancellation history | ISP Operations | Yes | Operational state only. |
| Invoice issuance, due date, arrears, dunning state | ISP Operations | Yes | Invoice is an operational claim; not revenue by itself. |
| Payment notification / collection workflow | ISP Operations | Yes | A recorded payment is not yet a reconciled receipt. |
| Bank statement and bank reconciliation | Balaka | No | Balaka owns statement import and matching. |
| Journal entries, GL, trial balance, reports | Balaka | Only via API posting | ISP app never writes tables. |
| Chart of accounts and templates | Balaka | No (Finance Controller configures) | ISP app reads template/account ids. |
| Revenue recognition and AR balances | Balaka | Only via approved postings | Derived from POSTED journals. |
| Cash and bank balances | Balaka | No | Derived from POSTED cash/bank-flagged accounts. |
| KUR loan master, schedule, reserve | Balaka (FAS-23) | No | Not an ISP concern. |
| Management KPI composition | Dashboard read layer | No | Read-only over both systems. |

## 5. Event flow (v1)

The ISP application emits a small, versioned set of business events. Each event is persisted in its own outbox before any network call.

```
ISP transaction (invoice.issued / payment.recorded / credit_note.issued)
  -> ISP outbox row (unique idempotency key, status=PENDING)
  -> [staged mode] review queue -> accountant approves
  -> POST /api/transactions (Balaka template, Idempotency-Key)
  -> record Balaka transaction id/number + HTTP outcome in outbox
  -> [period] reconciliation: ISP totals vs Balaka POSTED totals
```

- Events are ordered per source aggregate (one subscriber/invoice at a time); global ordering is not required.
- Out-of-order events are allowed but must not create a negative or duplicated receivable; the adapter re-validates the source state before posting.
- A rejected posting is never discarded: it moves to `FAILED` with the HTTP status, Balaka error code and attempt count, and is visible on an operator screen.
- Replay of an already-posted event returns the original Balaka transaction through the idempotency key and is recorded as `REPLAYED`, not as a new posting.

## 6. Alternatives considered and rejected

1. **Grow a subscriber module inside Balaka.** Rejected: mixes network operations with the accounting engine, increases the blast radius of every billing change, and violates the approved target architecture. Balaka's generic `clients`/`invoices` are not a substitute for subscriber lifecycle and package history.
2. **Share Balaka's database/schema with the ISP app.** Rejected: cross-application writes bypass domain validation, permissions, audit logging, idempotency and period controls; a billing bug could corrupt the ledger.
3. **Direct SQL or database views into Balaka.** Rejected: brittle, unauditable, bypasses authorization and posting rules.
4. **CSV/ZIP import only.** Acceptable as a manual fallback or migration path, but not as the primary recurring path: no reliable per-event idempotency, weak failure reporting, and high operator error risk. If used, it must be a controlled import with a file hash, row-level validation and a reconciliation report.
5. **Event bus / message broker in v1.** Rejected for now: unnecessary operational complexity at FASTERNET's scale. A database outbox with retries gives the required durability.
6. **Billing app calculates revenue and sends a monthly summary journal.** Rejected as the primary design: it hides per-invoice traceability and makes AR reconciliation coarse. Per-event posting is required; a summary journal can be an approved fallback only if it remains traceable to the invoice register.

## 7. Security and controls

- Dedicated API client per environment; only the scopes needed (`transactions:post`, `templates:read`, `accounts:read`). No admin client, no device-flow token, no personal session.
- Client secret in a secret manager or mounted secret file, never in source control, logs, prompts, screenshots or the ISP database.
- TLS only; certificate validation enabled; no plaintext HTTP to Balaka outside localhost development.
- `POST` retries are bounded with exponential backoff; 4xx validation errors are not retried blindly.
- Logs include event id, correlation id, idempotency key, attempt, outcome and Balaka reference; they exclude secrets and unnecessary subscriber PII.
- The ISP application has no permission to update or delete arbitrary Balaka rows. It cannot post to closed periods; a closed-period rejection becomes a visible exception requiring accountant action.
- Access to subscriber data is role-based and minimised; no national ID or payment-card data is stored.

## 8. Consequences

Positive:
- Billing changes cannot destabilise the ledger, and ledger upgrades cannot break subscriber operations.
- Every posted revenue/collection row is traceable to one ISP source record through the idempotency key and description reference.
- Duplicate retries and replayed events are safe by construction.
- Each system can be backed up, restored, tested and deployed independently.

Negative / constraints:
- Two applications, two databases, two backup/restore runbooks and a reconciliation process to operate.
- Account mappings and template ids become a versioned configuration contract; changing them requires Finance Controller approval.
- A failed or stale integration must be visible; silent disconnection is unacceptable.
- Some duplication of subscriber identifiers in journal descriptions is unavoidable for traceability; this is not a second subscriber master.

## 9. Open decisions and approval gates

These are **not** decided by this ADR and must not be invented by implementation:

| Decision | Owner | Status |
|---|---|---|
| Selected accounting/reporting framework and revenue recognition policy | Finance Controller | Unconfigured |
| Chart-of-accounts and journal-template mappings for subscription, installation, collection, credit note/refund | Finance Controller | Unconfigured |
| PPN/PPh treatment of subscription and installation fees | Finance Controller with qualified adviser | Unconfigured |
| Proration, partial-month, advance/arrears billing rules | Finance Controller + CEO | Unconfigured |
| Package catalogue and commercial fees | CEO / founders | Unconfigured |
| Grace period, suspension, cancellation and bad-debt policy | CEO + Finance Controller | Unconfigured |
| Operating and KUR-reserve bank account mapping | Finance Controller | Unconfigured |
| Cutover date and opening AR/subscriber balances | Finance Controller + CEO | Unconfigured |
| Automation approval (staged -> automatic posting) | Finance Controller | Not granted |
| Runtime/toolchain and deployment target for the new app | CTO | Proposed: existing Java/Spring/Postgres stack |

## 10. Validation

Before the ISP application may post to Balaka automatically, all of the following must be demonstrated and recorded:

1. No code path opens a connection to Balaka's database; the only outbound dependency is the documented HTTPS API.
2. A duplicate/replayed `invoice.issued`, `payment.recorded` and `credit_note.issued` event produces exactly one Balaka transaction each.
3. A lost response after a successful Balaka post is recovered as `200` replay and does not create a second journal.
4. A 400/422 validation failure, 401/403 auth failure, 5xx outage and timeout each produce a distinct, observable outbox state with a retry/repair procedure.
5. Period reconciliation proves, for a closed test period: ISP issued invoices, collections and credit notes reconcile to the corresponding POSTED Balaka AR/revenue/bank movements; unexplained differences block period close.
6. Closed-period posting attempts are rejected and surfaced; nothing is silently backdated.
7. Access review confirms least privilege for the API client and role-based access for subscriber data.
8. Backup/restore has been tested for both databases independently.
