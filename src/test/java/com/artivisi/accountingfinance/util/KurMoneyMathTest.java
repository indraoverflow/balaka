package com.artivisi.accountingfinance.util;

import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KUR money and reserve math")
class KurMoneyMathTest {

    @Test
    @DisplayName("reserve gap is the unfunded part of the next outstanding balance")
    void reserveGapCoveredPartially() {
        BigDecimal gap = KurMoneyMath.reserveGap(
                new BigDecimal("2757500.00"),
                new BigDecimal("1000000.00"),
                new BigDecimal("500000.00"));

        assertThat(gap).isEqualByComparingTo("1257500.00");
    }

    @Test
    @DisplayName("a fully funded outstanding balance has a zero gap")
    void reserveGapFullyFunded() {
        BigDecimal gap = KurMoneyMath.reserveGap(
                new BigDecimal("2757500.00"),
                new BigDecimal("2000000.00"),
                new BigDecimal("757500.00"));

        assertThat(gap).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("partial payment from operating cash nets once: outstanding 2,050,000, gap 0")
    void reserveGapPartialPaymentFromOperatingCash() {
        BigDecimal installment = new BigDecimal("3050000.00");
        BigDecimal paidFromOperatingCash = new BigDecimal("1000000.00");
        BigDecimal outstanding = KurMoneyMath.outstanding(installment, paidFromOperatingCash);
        BigDecimal reserve = new BigDecimal("2050000.00");

        assertThat(outstanding).isEqualByComparingTo("2050000.00");
        assertThat(KurMoneyMath.reserveGap(outstanding, reserve, BigDecimal.ZERO))
                .isEqualByComparingTo("0.00");
        assertThat(KurMoneyMath.reserveGap(installment, reserve, BigDecimal.ZERO))
                .as("the old contractual basis double-counts the payment")
                .isEqualByComparingTo("1000000.00");
    }

    @Test
    @DisplayName("partial payment from the reserve nets once: outstanding 2,050,000, gap 1,000,000")
    void reserveGapPartialPaymentFromReserve() {
        BigDecimal installment = new BigDecimal("3050000.00");
        BigDecimal paidFromReserve = new BigDecimal("1000000.00");
        BigDecimal outstanding = KurMoneyMath.outstanding(installment, paidFromReserve);
        BigDecimal reserveAfterPayment = new BigDecimal("1050000.00");

        assertThat(outstanding).isEqualByComparingTo("2050000.00");
        assertThat(KurMoneyMath.reserveGap(outstanding, reserveAfterPayment, BigDecimal.ZERO))
                .isEqualByComparingTo("1000000.00");
        assertThat(KurMoneyMath.reserveGap(installment, reserveAfterPayment, BigDecimal.ZERO))
                .as("the old contractual basis overstates the need by the amount already paid")
                .isEqualByComparingTo("2000000.00");
    }

    @Test
    @DisplayName("total-cash floor catches the case the old cash-beyond-minimum test misses")
    void cashFloorBreachedAfterOutstanding() {
        BigDecimal cash = new BigDecimal("3050000.00");
        BigDecimal outstanding = new BigDecimal("3050000.00");
        BigDecimal minimumCash = new BigDecimal("1000000.00");
        BigDecimal reserve = new BigDecimal("1000000.00");
        BigDecimal gap = KurMoneyMath.reserveGap(outstanding, reserve, BigDecimal.ZERO);

        assertThat(gap).isEqualByComparingTo("2050000.00");
        assertThat(KurMoneyMath.cashFloorBreachedAfterOutstanding(cash, outstanding, minimumCash))
                .as("paying the outstanding leaves 0 cash, below the 1,000,000 floor")
                .isTrue();
        assertThat(cash.subtract(minimumCash))
                .as("the old test compares 2,050,000 with the 2,050,000 gap and passes")
                .isEqualByComparingTo(gap);
        assertThat(KurMoneyMath.cashFloorBreachedAfterOutstanding(
                new BigDecimal("5000000.00"), outstanding, minimumCash))
                .as("cash 5,000,000 minus 3,050,000 leaves 1,950,000, above the floor")
                .isFalse();
    }

    @Test
    @DisplayName("an overfunded reserve does not produce a negative gap")
    void reserveGapNeverNegative() {
        BigDecimal gap = KurMoneyMath.reserveGap(
                new BigDecimal("1000000.00"),
                new BigDecimal("5000000.00"),
                BigDecimal.ZERO);

        assertThat(gap).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a missing obligation yields a zero gap instead of a fabricated amount")
    void reserveGapNoObligation() {
        assertThat(KurMoneyMath.reserveGap(null, new BigDecimal("100.00"), null))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("earmarked and separate-account money add up exactly once")
    void qualifyingReserveAddsBuckets() {
        assertThat(KurMoneyMath.qualifyingReserve(new BigDecimal("100.10"), new BigDecimal("200.20")))
                .isEqualByComparingTo("300.30");
        assertThat(KurMoneyMath.qualifyingReserve(null, new BigDecimal("200.20")))
                .isEqualByComparingTo("200.20");
    }

    @Test
    @DisplayName("partial payment leaves the unpaid remainder outstanding")
    void partialPaymentOutstanding() {
        BigDecimal outstanding = KurMoneyMath.outstanding(
                new BigDecimal("2757500.00"), new BigDecimal("1000000.00"));

        assertThat(outstanding).isEqualByComparingTo("1757500.00");
        assertThat(KurMoneyMath.installmentStatus(
                new BigDecimal("2757500.00"), new BigDecimal("1000000.00")))
                .isEqualTo(KurInstallmentStatus.PARTIAL);
    }

    @Test
    @DisplayName("overpayment is recorded as overpaid with no negative outstanding")
    void overpayment() {
        BigDecimal outstanding = KurMoneyMath.outstanding(
                new BigDecimal("100.00"), new BigDecimal("150.00"));

        assertThat(outstanding).isEqualByComparingTo("0.00");
        assertThat(KurMoneyMath.installmentStatus(
                new BigDecimal("100.00"), new BigDecimal("150.00")))
                .isEqualTo(KurInstallmentStatus.OVERPAID);
    }

    @Test
    @DisplayName("a zero payment is unpaid, an exact payment is paid")
    void statusBoundaries() {
        assertThat(KurMoneyMath.installmentStatus(new BigDecimal("100.00"), BigDecimal.ZERO))
                .isEqualTo(KurInstallmentStatus.UNPAID);
        assertThat(KurMoneyMath.installmentStatus(new BigDecimal("100.00"), new BigDecimal("100.00")))
                .isEqualTo(KurInstallmentStatus.PAID);
        assertThat(KurMoneyMath.isPaid(KurInstallmentStatus.PAID)).isTrue();
        assertThat(KurMoneyMath.isPaid(KurInstallmentStatus.PARTIAL)).isFalse();
    }

    @Test
    @DisplayName("money is rounded half-up to two decimal places, never via double")
    void moneyRounding() {
        assertThat(KurMoneyMath.money(new BigDecimal("2757499.995")))
                .isEqualByComparingTo("2757500.00");
        assertThat(KurMoneyMath.money(new BigDecimal("0.004")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("split installment exact: principal, interest, fee match schedule with 0 overpayment")
    void splitInstallmentExact() {
        KurMoneyMath.InstallmentSplit split = KurMoneyMath.splitInstallment(
                new BigDecimal("3050000.00"),
                new BigDecimal("2757500.00"),
                new BigDecimal("292500.00"),
                BigDecimal.ZERO);

        assertThat(split.principal()).isEqualByComparingTo("2757500.00");
        assertThat(split.interest()).isEqualByComparingTo("292500.00");
        assertThat(split.fee()).isEqualByComparingTo("0.00");
        assertThat(split.overpayment()).isEqualByComparingTo("0.00");
        assertThat(split.netInstallmentPaid()).isEqualByComparingTo("3050000.00");
    }

    @Test
    @DisplayName("split installment partial waterfall: fee -> interest -> principal")
    void splitInstallmentPartialWaterfall() {
        // Payment of 1,000,000 against scheduled fee 10,000, interest 292,500, principal 2,757,500
        KurMoneyMath.InstallmentSplit split = KurMoneyMath.splitInstallment(
                new BigDecimal("1000000.00"),
                new BigDecimal("2757500.00"),
                new BigDecimal("292500.00"),
                new BigDecimal("10000.00"));

        assertThat(split.fee()).isEqualByComparingTo("10000.00");
        assertThat(split.interest()).isEqualByComparingTo("292500.00");
        assertThat(split.principal()).isEqualByComparingTo("697500.00");
        assertThat(split.overpayment()).isEqualByComparingTo("0.00");
        assertThat(split.netInstallmentPaid()).isEqualByComparingTo("1000000.00");
    }

    @Test
    @DisplayName("split installment overpayment: excess posts to overpayment buffer, not principal")
    void splitInstallmentOverpayment() {
        KurMoneyMath.InstallmentSplit split = KurMoneyMath.splitInstallment(
                new BigDecimal("4000000.00"),
                new BigDecimal("2757500.00"),
                new BigDecimal("292500.00"),
                BigDecimal.ZERO);

        assertThat(split.principal()).isEqualByComparingTo("2757500.00");
        assertThat(split.interest()).isEqualByComparingTo("292500.00");
        assertThat(split.fee()).isEqualByComparingTo("0.00");
        assertThat(split.overpayment()).isEqualByComparingTo("950000.00");
        assertThat(split.netInstallmentPaid()).isEqualByComparingTo("3050000.00");
    }

    @Test
    @DisplayName("split installment estimate fallback: uses straight-line 2,757,500 / 292,500")
    void splitInstallmentEstimateFallback() {
        KurMoneyMath.InstallmentSplit split = KurMoneyMath.splitInstallmentEstimateFallback(
                new BigDecimal("3050000.00"));

        assertThat(split.principal()).isEqualByComparingTo("2757500.00");
        assertThat(split.interest()).isEqualByComparingTo("292500.00");
        assertThat(split.overpayment()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("current/non-current reclass rolling 12-month horizon and zero non-current near maturity")
    void reclassRolling12MonthsAndTerminalBoundary() {
        // Month 1: remaining principal 99,270,000, next 12 months sum 33,090,000, GL current balance 33,090,000
        List<BigDecimal> next12 = List.of(
                new BigDecimal("2757500.00"), new BigDecimal("2757500.00"),
                new BigDecimal("2757500.00"), new BigDecimal("2757500.00"),
                new BigDecimal("2757500.00"), new BigDecimal("2757500.00"),
                new BigDecimal("2757500.00"), new BigDecimal("2757500.00"),
                new BigDecimal("2757500.00"), new BigDecimal("2757500.00"),
                new BigDecimal("2757500.00"), new BigDecimal("2757500.00")
        );
        KurMoneyMath.ReclassResult res1 = KurMoneyMath.calculateReclass(
                new BigDecimal("99270000.00"), next12, new BigDecimal("33090000.00"));

        assertThat(res1.currentRequired()).isEqualByComparingTo("33090000.00");
        assertThat(res1.nonCurrentRequired()).isEqualByComparingTo("66180000.00");
        assertThat(res1.delta()).isEqualByComparingTo("0.00");

        // Near maturity (month 25-36): remaining principal 30,000,000, next 12 months sum 33,090,000
        KurMoneyMath.ReclassResult resNearMaturity = KurMoneyMath.calculateReclass(
                new BigDecimal("30000000.00"), next12, new BigDecimal("27575000.00"));

        assertThat(resNearMaturity.currentRequired()).isEqualByComparingTo("30000000.00");
        assertThat(resNearMaturity.nonCurrentRequired()).isEqualByComparingTo("0.00");
        assertThat(resNearMaturity.delta()).isEqualByComparingTo("2425000.00");
    }

    @Test
    @DisplayName("three-way reconciliation: clean fixture vs red control exception on cash difference")
    void threeWayReconciliation() {
        // Clean
        KurMoneyMath.ThreeWayReconciliationResult clean = KurMoneyMath.reconcileThreeWay(
                new BigDecimal("33090000.00"),
                new BigDecimal("66180000.00"),
                new BigDecimal("99270000.00"),
                new BigDecimal("3050000.00"),
                new BigDecimal("3050000.00"),
                new BigDecimal("3050000.00"),
                new BigDecimal("3050000.00"));

        assertThat(clean.isClean()).isTrue();
        assertThat(clean.isRedException()).isFalse();
        assertThat(clean.unexplainedDifference()).isEqualByComparingTo("0.00");

        // Cash mismatch (Leg B != 0) -> RED exception
        KurMoneyMath.ThreeWayReconciliationResult cashMismatch = KurMoneyMath.reconcileThreeWay(
                new BigDecimal("33090000.00"),
                new BigDecimal("66180000.00"),
                new BigDecimal("99270000.00"),
                new BigDecimal("3050000.00"),
                new BigDecimal("3040000.00"), // 10,000 bank diff
                new BigDecimal("3050000.00"),
                new BigDecimal("3050000.00"));

        assertThat(cashMismatch.isClean()).isFalse();
        assertThat(cashMismatch.isRedException()).isTrue();
        assertThat(cashMismatch.legBCashDifference()).isEqualByComparingTo("10000.00");
    }
}
