package com.artivisi.accountingfinance.exception;

/**
 * A role change would cross the finance separation-of-duties boundary: the actor
 * tried to change their own roles, or to grant/remove a finance role (or manage a
 * finance-role account) without currently holding {@code FINANCE_CONTROLLER} or
 * {@code OWNER}.
 *
 * <p>Extends {@link IllegalArgumentException} so the existing user-management
 * controller renders it as a form error; the subtype lets the controller audit the
 * rejected attempt separately from ordinary validation failures.
 */
public class FinanceRoleAssignmentException extends IllegalArgumentException {

    public FinanceRoleAssignmentException(String message) {
        super(message);
    }
}
