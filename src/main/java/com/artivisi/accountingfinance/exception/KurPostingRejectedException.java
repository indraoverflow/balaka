package com.artivisi.accountingfinance.exception;

/**
 * A KUR accounting posting request that the domain refuses to execute.
 *
 * <p>Typical causes: the posting capability is staged/disabled, the journal template
 * is inactive, the loan's legal-borrower structure forbids GL posting, or the value
 * date falls in a closed fiscal period without an explicit prior-period fallback.
 * The message is safe to return to a caller; it never contains ledger details.
 */
public class KurPostingRejectedException extends RuntimeException {

    private final String reason;

    public KurPostingRejectedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public KurPostingRejectedException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
