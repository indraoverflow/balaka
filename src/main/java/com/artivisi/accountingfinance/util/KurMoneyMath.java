package com.artivisi.accountingfinance.util;

import com.artivisi.accountingfinance.enums.KurInstallmentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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

    public static final BigDecimal ESTIMATE_PRINCIPAL = new BigDecimal("2757500.00");
    public static final BigDecimal ESTIMATE_INTEREST = new BigDecimal("292500.00");
    public static final BigDecimal ESTIMATE_INSTALLMENT = new BigDecimal("3050000.00");

    public record InstallmentSplit(
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal fee,
            BigDecimal overpayment,
            BigDecimal netInstallmentPaid
    ) {}

    /**
     * Splits an installment payment according to the approved waterfall:
     * Fee -> Interest -> Principal -> Overpayment (excess goes to 1.1.11 buffer).
     */
    public static InstallmentSplit splitInstallment(
            BigDecimal paymentAmount,
            BigDecimal scheduledPrincipal,
            BigDecimal scheduledInterest,
            BigDecimal scheduledFee) {
        BigDecimal paid = nonNullMoney(paymentAmount);
        BigDecimal targetFee = nonNullMoney(scheduledFee);
        BigDecimal targetInterest = nonNullMoney(scheduledInterest);
        BigDecimal targetPrincipal = nonNullMoney(scheduledPrincipal);

        BigDecimal feePaid = paid.min(targetFee);
        BigDecimal rem1 = paid.subtract(feePaid);

        BigDecimal interestPaid = rem1.min(targetInterest);
        BigDecimal rem2 = rem1.subtract(interestPaid);

        BigDecimal principalPaid = rem2.min(targetPrincipal);
        BigDecimal overpayment = rem2.subtract(principalPaid);

        BigDecimal netPaid = feePaid.add(interestPaid).add(principalPaid);

        return new InstallmentSplit(
                money(principalPaid),
                money(interestPaid),
                money(feePaid),
                money(overpayment),
                money(netPaid)
        );
    }

    public static InstallmentSplit splitInstallmentEstimateFallback(BigDecimal paymentAmount) {
        return splitInstallment(paymentAmount, ESTIMATE_PRINCIPAL, ESTIMATE_INTEREST, ZERO);
    }

    /**
     * Scheduled principal/interest/fee for one installment, resolved the same way for
     * posting and for reconciliation. A lender-provided split always wins; a missing
     * split falls back to the approved founder estimate when the installment amount
     * matches it, otherwise to the whole amount as principal. {@code estimated} marks
     * the fallback so callers can carry the provenance into the posting/report.
     */
    public record ScheduledComponents(
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal fee,
            boolean estimated
    ) {}

    public static ScheduledComponents scheduleComponents(
            BigDecimal installmentAmount, BigDecimal principal, BigDecimal interest, BigDecimal fee) {
        BigDecimal feeValue = nonNullMoney(fee);
        if (principal != null && principal.signum() > 0 && interest != null) {
            return new ScheduledComponents(money(principal), money(interest), feeValue, false);
        }
        BigDecimal amount = nonNullMoney(installmentAmount);
        if (amount.compareTo(ESTIMATE_INSTALLMENT) == 0) {
            return new ScheduledComponents(ESTIMATE_PRINCIPAL, ESTIMATE_INTEREST, feeValue, true);
        }
        return new ScheduledComponents(amount, ZERO, feeValue, true);
    }

    public record ReclassResult(
            BigDecimal currentRequired,
            BigDecimal nonCurrentRequired,
            BigDecimal delta
    ) {}

    /**
     * Calculates the month-end current/non-current principal reclassification.
     * Evaluates a rolling 12-month horizon: current portion = min(remaining, sum of next 12 months).
     * Non-current portion = max(0, remaining - current portion).
     * Near maturity (months 25-36, <= 12 months remaining), non-current is strictly zero.
     */
    public static ReclassResult calculateReclass(
            BigDecimal remainingTotalPrincipal,
            List<BigDecimal> upcoming12MonthsPrincipals,
            BigDecimal currentGlBalance) {
        BigDecimal rem = nonNullMoney(remainingTotalPrincipal);
        BigDecimal sumUpcoming = ZERO;
        if (upcoming12MonthsPrincipals != null) {
            for (BigDecimal p : upcoming12MonthsPrincipals) {
                if (p != null) {
                    sumUpcoming = sumUpcoming.add(money(p));
                }
            }
        }
        BigDecimal currentReq = rem.min(sumUpcoming);
        BigDecimal nonCurrentReq = rem.subtract(currentReq).max(ZERO);
        BigDecimal delta = currentReq.subtract(nonNullMoney(currentGlBalance));

        return new ReclassResult(
                money(currentReq),
                money(nonCurrentReq),
                money(delta)
        );
    }

    public record ThreeWayReconciliationResult(
            BigDecimal legAPrincipalDifference,
            BigDecimal legBCashDifference,
            BigDecimal legCComponentDifference,
            BigDecimal unexplainedDifference,
            boolean isClean,
            boolean isRedException
    ) {}

    /**
     * Performs three-way reconciliation (triangulation):
     * Leg A: Outstanding Principal (GL 2.1.30 + 2.2.01 vs Bank reported). Tolerance <= 1.00.
     * Leg B: Cash Outflow (GL 1.1.10 credit vs Bank statement debit). Tolerance = 0.
     * Leg C: Component Split (GL components sum vs Schedule row amount). Tolerance = 0.
     */
    public static ThreeWayReconciliationResult reconcileThreeWay(
            BigDecimal glCurrentPrincipal,
            BigDecimal glNonCurrentPrincipal,
            BigDecimal bankReportedPrincipal,
            BigDecimal glCashCredit,
            BigDecimal bankCashDebit,
            BigDecimal glComponentsSum,
            BigDecimal scheduleInstallment) {
        BigDecimal glTotalPrincipal = nonNullMoney(glCurrentPrincipal).add(nonNullMoney(glNonCurrentPrincipal));
        BigDecimal legA = glTotalPrincipal.subtract(nonNullMoney(bankReportedPrincipal)).abs();
        BigDecimal legB = nonNullMoney(glCashCredit).subtract(nonNullMoney(bankCashDebit)).abs();
        BigDecimal legC = nonNullMoney(glComponentsSum).subtract(nonNullMoney(scheduleInstallment)).abs();

        BigDecimal unexplained = legA.add(legB).add(legC);
        boolean clean = unexplained.compareTo(ZERO) == 0;
        boolean red = legA.compareTo(new BigDecimal("1.00")) > 0 || legB.compareTo(ZERO) > 0 || legC.compareTo(ZERO) > 0;

        return new ThreeWayReconciliationResult(
                money(legA),
                money(legB),
                money(legC),
                money(unexplained),
                clean,
                red
        );
    }
}
