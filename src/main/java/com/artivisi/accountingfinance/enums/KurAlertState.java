package com.artivisi.accountingfinance.enums;

/**
 * State of the next KUR installment repayment alert (FAS-96).
 *
 * <p>{@link #UNCONFIGURED} is not a fifth colour: it means the CEO and Finance
 * Controller have not approved the threshold values yet, so the system refuses to
 * render green. {@link #UNAVAILABLE} means a required input (obligation, reserve,
 * or cash) cannot be derived; missing data is never treated as zero.
 */
public enum KurAlertState {
    GREEN("green", "ok"),
    YELLOW("yellow", "warning"),
    ORANGE("orange", "high"),
    RED("red", "critical"),
    UNCONFIGURED("unconfigured", "unknown"),
    UNAVAILABLE("unavailable", "unknown");

    private final String jsonValue;
    private final String severity;

    KurAlertState(String jsonValue, String severity) {
        this.jsonValue = jsonValue;
        this.severity = severity;
    }

    public String getJsonValue() {
        return jsonValue;
    }

    public String getSeverity() {
        return severity;
    }
}
