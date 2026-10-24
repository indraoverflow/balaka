package com.artivisi.accountingfinance.util;

import com.artivisi.accountingfinance.enums.KurInstallmentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decimal-safe KUR money and reserve math.
 *
 * <p>Every value is a {@link BigDecimal} with two decimal places. The reserve gap
 * only ever compares qualifying reserve funds against the next obligation; it never
 * mutates an installment's paid state.
 */
public final class KurMoneyMath {

    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);

    private KurMoneyMath() {
    }

    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal nonNullMoney(BigDecimal value) {
        return value == null ? ZERO : money(value);
    }

    /**
     * Qualifying reserve = internally earmarked money + money held in the separate
     * reserve account. This is a sum only; the caller is responsible for ensuring
     * the two buckets never describe the same cash.
     */
    public static BigDecimal qualifyingReserve(BigDecimal earmarked, BigDecimal separateAccount) {
        return nonNullMoney(earmarked).add(nonNullMoney(separateAccount)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Gap = max(0, obligation - qualifying reserve). A fully funded obligation has
     * a zero gap; a missing obligation has a zero gap only because there is nothing
     * due, never because the reserve is being treated as a payment.
     */
    public static BigDecimal reserveGap(BigDecimal obligation, BigDecimal earmarked, BigDecimal separateAccount) {
        if (obligation == null) {
            return ZERO;
        }
        BigDecimal gap = money(obligation).subtract(qualifyingReserve(earmarked, separateAccount));
        return gap.signum() > 0 ? money(gap) : ZERO;
    }

    public static BigDecimal outstanding(BigDecimal contractualAmount, BigDecimal paidAmount) {
        BigDecimal outstanding = nonNullMoney(contractualAmount).subtract(nonNullMoney(paidAmount));
        return outstanding.signum() > 0 ? money(outstanding) : ZERO;
    }

    public static KurInstallmentStatus installmentStatus(BigDecimal contractualAmount, BigDecimal paidAmount) {
        BigDecimal paid = nonNullMoney(paidAmount);
        if (paid.signum() == 0) {
            return KurInstallmentStatus.UNPAID;
        }
        int comparison = paid.compareTo(nonNullMoney(contractualAmount));
        if (comparison < 0) {
            return KurInstallmentStatus.PARTIAL;
        }
        if (comparison == 0) {
            return KurInstallmentStatus.PAID;
        }
        return KurInstallmentStatus.OVERPAID;
    }

    public static boolean isPaid(KurInstallmentStatus status) {
        return status == KurInstallmentStatus.PAID || status == KurInstallmentStatus.OVERPAID;
    }
}
