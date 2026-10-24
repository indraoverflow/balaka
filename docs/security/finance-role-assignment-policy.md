# Finance Role Assignment Policy (FASTERNET)

Status: **interim technical control in place; CEO and Finance Controller approval pending**
(FASTERNET issue FAS-106). The code guard is fail-closed; approval can widen the
operational procedure, not weaken the guard without a new decision.

Scope: user and role management in the Balaka instance used by FASTERNET
(`UserController`, `UserService`) and the bulk data-import path
(`DataImportService`). This policy does not change KUR accounting semantics or
the ISP/accounting boundary.

## Why this exists

`ADMIN` deliberately does not inherit `KUR_WRITE`/`KUR_VERIFY`; only
`FINANCE_CONTROLLER` holds them by default. `ADMIN` does hold `USER_EDIT`, so
without a guard an administrator could add `FINANCE_CONTROLLER` or `OWNER` to its
own account (or to an account whose password it controls) and obtain finance-write
authority without a second approver. That defeats the separation of duties the KUR
write path depends on.

## Definitions

- **Finance roles:** `FINANCE_CONTROLLER` and `OWNER`.
- **Finance authority:** the acting account currently holds at least one finance
  role. `OWNER` is treated as the CEO/founder approver role for this policy.
- **Finance-role account:** an account whose current roles include any finance
  role.

## Rules (implemented)

1. **No self role changes.** No account may change the role set of its own
   account, regardless of its roles. Another authorized account must make the
   change.
2. **Finance roles move only with finance authority.** Granting or removing
   `FINANCE_CONTROLLER` or `OWNER` requires the acting account to already hold a
   finance role. This applies when editing an existing account.
3. **No finance-role creation by non-finance actors.** Creating an account with a
   finance role requires the acting account to already hold a finance role.
4. **Finance-role accounts are protected.** Editing a finance-role account
   (including roles, profile fields, or active flag through the edit form),
   resetting its password, toggling its active status, or deleting it requires
   finance authority.
5. **Non-finance user management is unchanged.** `ADMIN` may still create and
   manage accounts that hold only non-finance roles. `ADMIN` cannot use those
   accounts to acquire finance authority because of rules 2 and 3.
6. **Bulk import applies the same rules.** `29_user_roles.csv` in an uploaded ZIP
   is rejected as a whole when a row would add a finance role without finance
   authority, or when the target username is the authenticated actor. The importer
   never silently skips a disallowed row. `ADMIN` holds `DATA_IMPORT`, so without
   this rule it could bypass rules 1-2 by uploading `admin,FINANCE_CONTROLLER`.
7. **First-run bootstrap is the only exception.** The setup wizard may import a
   bundled classpath seed pack while the `users` table is empty and the request is
   unauthenticated. Only that pre-user, unauthenticated path may carry finance-role
   rows. Uploaded archives, including those from an authenticated `ADMIN`, can
   never use it. Bundled seed packs currently ship a header-only
   `29_user_roles.csv`.

An attempt that violates rules 1-7 is rejected, the change is not applied, and a
failed `USER_CREATED` / `USER_UPDATED` / `PASSWORD_CHANGED` /
`USER_STATUS_CHANGED` / `USER_DELETED` audit event records the actor and the
attempted target. Successful role changes keep the existing `USER_UPDATED` event
and now record the actor and the role delta, for example:

```
Updated user: finance-grantee (id: <uuid>) roles: [STAFF] -> [FINANCE_CONTROLLER] (actor: fc-actor)
```

The security audit log records the actor username, timestamp, IP address and user
agent for every event; the details above add the before/after role delta.
Bulk-import finance-role changes and rejected import attempts are recorded the same
way under `USER_UPDATED`; rejected attempts are written in their own transaction so
the audit survives the rollback of the import.

## Bootstrap and operations

Because no one may grant themselves a finance role, the **first** `OWNER` /
`FINANCE_CONTROLLER` accounts cannot be created from the `ADMIN` web UI. They are
provisioned out-of-band by the deployment operator (Ansible/`psql`), the same way
the deployment already provisions the first `ADMIN`:

1. The CEO/founders approve the named people who will hold `OWNER` and
   `FINANCE_CONTROLLER`, in writing (issue or deployment record).
2. The deployment operator creates the accounts and their role rows directly in
   the database during provisioning, using unique usernames and a password
   delivered out-of-band (never stored in the repository or deployment logs).
3. The provisioning change is recorded in the deployment log with the approval
   reference. Database access is restricted to the operator role.
4. Afterwards, additional finance-role accounts are granted or removed by an
   existing `OWNER`/`FINANCE_CONTROLLER` through the approved procedure (today:
   the same out-of-band operator step; no in-app UI is enabled for those roles).

`FINANCE_CONTROLLER` and `OWNER` do not currently hold `USER_VIEW`/`USER_EDIT`, so
they cannot use the `/users` screen. Enabling in-app role assignment for them is a
role-permission-matrix change and requires the separate CEO + Finance Controller
approval described below.

## Approval record

| Approver | Decision | Reference | Date |
| --- | --- | --- | --- |
| CEO | pending | FAS-106 approval interaction | — |
| Finance Controller | pending | FAS-106 approval interaction | — |

The final role-permission matrix (including whether `OWNER`/`FINANCE_CONTROLLER`
should receive user-management permissions, and whether `ADMIN` should keep
`USER_ASSIGN_ROLES`) remains subject to CEO and Finance Controller approval. The
guard above does not depend on that matrix: it denies the escalation even if the
matrix changes, unless this policy is explicitly superseded.

## Verification

Automated coverage lives in:

- `UserServiceTest.FinanceRoleSeparationTests` — self-escalation, grant/removal,
  creation, password reset, activation and deletion, plus the non-finance control
  case.
- `UserControllerRoleEscalationTest` — HTTP rejection and the actor/delta audit
  events.
- `DataImportRoleGuardTest` — an authenticated `ADMIN` cannot import a finance role
  for itself or another account, an `OWNER` can import a finance role, the
  unauthenticated empty-user bootstrap still works, and the seed path cannot be
  used by an authenticated caller.
- `DataExportImportTest` — export/import round-trip remains green.

No production financial data is used by these tests.
