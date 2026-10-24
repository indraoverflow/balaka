package com.artivisi.accountingfinance.enums;

/**
 * A calculated amortization is an estimate until it is reconciled line by line
 * against the lender's official schedule; importing lender rows never marks the
 * loan verified by itself.
 */
public enum KurScheduleSourceKind {
    ESTIMATE("estimate"),
    LENDER_SCHEDULE("lender-schedule");

    private final String jsonValue;

    KurScheduleSourceKind(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String getJsonValue() {
        return jsonValue;
    }
}
