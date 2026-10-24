package com.artivisi.accountingfinance.util;

import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KUR money and reserve math")
class KurMoneyMathTest {

    @Test
    @DisplayName("reserve gap is the unfunded part of the next obligation")
    void reserveGapCoveredPartially() {
        BigDecimal gap = KurMoneyMath.reserveGap(
                new BigDecimal("2757500.00"),
                new BigDecimal("1000000.00"),
                new BigDecimal("500000.00"));

        assertThat(gap).isEqualByComparingTo("1257500.00");
    }

    @Test
    @DisplayName("a fully funded obligation has a zero gap")
    void reserveGapFullyFunded() {
        BigDecimal gap = KurMoneyMath.reserveGap(
                new BigDecimal("2757500.00"),
                new BigDecimal("2000000.00"),
                new BigDecimal("757500.00"));

        assertThat(gap).isEqualByComparingTo("0.00");
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
}
