package com.artivisi.accountingfinance.util;

import com.artivisi.accountingfinance.enums.KurInstallmentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decimal-safe KUR money and reserve math.
 *
 * <p>Every value is a {@link BigDecimal} with two decimal places. The reserve gap
 * compares qualifying reserve funds against the <em>outstanding</em> balance of the
 * next obligation, so a partial payment is netted once and never double-counted. It
 * never mutates an installment's paid state.
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
     * Gap = max(0, outstanding - qualifying reserve).
     *
     * <p>{@code outstanding} is the contractual installment amount minus posted,
     * non-reversed payments recorded through the as-of date. A partially paid
     * installment remains the next obligation, but its paid amount already reduces
     * the funding need; using the contractual amount here would double-count the
     * payment. A fully funded outstanding balance has a zero gap. A missing
     * obligation has a zero gap only because there is nothing due, never because
     * the reserve is being treated as a payment.
     *
     * <p>A full-installment buffer is a separate target-reserve decision, not part
     * of this funding gap.
     */
    public static BigDecimal reserveGap(BigDecimal outstanding, BigDecimal earmarked, BigDecimal separateAccount) {
        if (outstanding == null) {
            return ZERO;
        }
        BigDecimal gap = money(outstanding).subtract(qualifyingReserve(earmarked, separateAccount));
        return gap.signum() > 0 ? money(gap) : ZERO;
    }

    /**
     * True when paying the outstanding balance would leave total cash below the
     * approved <strong>total-cash floor</strong>.
     *
     * <p>{@code cash} already includes the earmarked reserve, so the reserve is not
     * subtracted a second time. The single test is
     * {@code cash - outstanding < minimumCash}: the floor is a total-cash floor, not
     * an unencumbered operating-cash floor.
     */
    public static boolean cashFloorBreachedAfterOutstanding(
            BigDecimal cash, BigDecimal outstanding, BigDecimal minimumCash) {
        if (cash == null || minimumCash == null) {
            return false;
        }
        BigDecimal outstandingOrZero = outstanding == null ? ZERO : money(outstanding);
        return money(cash).subtract(outstandingOrZero).compareTo(money(minimumCash)) < 0;
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
