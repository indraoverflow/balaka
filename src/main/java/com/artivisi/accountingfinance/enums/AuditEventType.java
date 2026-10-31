package com.artivisi.accountingfinance.enums;

/**
 * Types of security audit events.
 */
public enum AuditEventType {
    // Authentication events
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    ACCOUNT_LOCKED,

    // User management
    USER_CREATE,
    USER_CREATED,
    USER_UPDATE,
    USER_UPDATED,
    USER_DELETE,
    USER_DELETED,
    USER_ROLE_CHANGE,
    USER_STATUS_CHANGED,
    PASSWORD_CHANGE,
    PASSWORD_CHANGED,

    // Data access
    DATA_EXPORT,
    DATA_IMPORT,
    REPORT_GENERATE,
    BACKUP_CREATE,

    // Document operations
    DOCUMENT_UPLOAD,
    DOCUMENT_DOWNLOAD,
    DOCUMENT_DELETE,

    // Settings
    SETTINGS_CHANGE,

    // Payroll (sensitive data)
    PAYROLL_VIEW,
    PAYROLL_EXPORT,

    // API operations
    API_CALL,

    // FASTERNET KUR loan write path (FAS-95)
    KUR_LOAN_CREATE,
    KUR_SCHEDULE_IMPORT,
    KUR_PAYMENT_RECORD,
    KUR_PAYMENT_REVERSE,
    KUR_RESERVE_RECORD,
    KUR_LOAN_VERIFY,
    // Supporting-document attachment (FAS-105)
    KUR_DOCUMENT_UPLOAD,
    // FAS-96: KUR repayment alert threshold policy write
    KUR_ALERT_POLICY_SAVE
}
