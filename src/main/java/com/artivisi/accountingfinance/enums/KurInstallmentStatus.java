package com.artivisi.accountingfinance.enums;

/**
 * Installment payment state. The JSON value is lowercase to match the
 * {@code fasternet.dashboard/v1} contract; the database stores the enum name.
 */
public enum KurInstallmentStatus {
    UNPAID("unpaid"),
    PARTIAL("partial"),
    PAID("paid"),
    OVERPAID("overpaid");

    private final String jsonValue;

    KurInstallmentStatus(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String getJsonValue() {
        return jsonValue;
    }
}
