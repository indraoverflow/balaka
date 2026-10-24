package com.artivisi.accountingfinance.enums;

/**
 * Provenance of the KUR loan master and its repayment schedule.
 *
 * <p>Only {@link #VERIFIED} may be presented as confirmed; it requires the signed
 * agreement and the lender's official schedule to be attached and reviewed by the
 * Finance Controller. The system never upgrades provenance automatically.
 */
public enum KurLoanProvenance {
    FOUNDER_ESTIMATE("founder-estimate"),
    BANK_CONFIRMED("bank-confirmed"),
    VERIFIED("verified");

    private final String jsonValue;

    KurLoanProvenance(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String getJsonValue() {
        return jsonValue;
    }

    public boolean isConfirmed() {
        return this == VERIFIED || this == BANK_CONFIRMED;
    }
}
