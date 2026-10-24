package com.artivisi.accountingfinance.enums;

/**
 * Reserve movements. Earmarking cash is an internal designation inside an
 * existing account; a separate reserve account is real cash held elsewhere.
 * Neither kind touches an installment's paid state.
 */
public enum KurReserveEntryKind {
    EARMARK(1, true),
    RELEASE(-1, true),
    SEPARATE_ACCOUNT(1, false),
    SEPARATE_ACCOUNT_RELEASE(-1, false);

    private final int sign;
    private final boolean earmarked;

    KurReserveEntryKind(int sign, boolean earmarked) {
        this.sign = sign;
        this.earmarked = earmarked;
    }

    public int getSign() {
        return sign;
    }

    public boolean isEarmarked() {
        return earmarked;
    }

    public boolean isRelease() {
        return sign < 0;
    }
}
