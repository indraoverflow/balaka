package com.artivisi.accountingfinance.dto.fasternet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Three-way lender reconciliation report per FAS-97 §10 / FAS-124.
 * Triangulates:
 * 1. Leg A: Outstanding principal (Ledger 2.1.30 + 2.2.01 vs Bank Statement/BRImo)
 * 2. Leg B: Cash debits (Ledger 1.1.10 vs Bank Statement debits)
 * 3. Leg C: Schedule split (Posted Principal + Interest + Fee vs Lender Schedule)
 */
public record KurReconciliationReport(
        UUID loanId,
        LocalDate asOfDate,
        BigDecimal ledgerPrincipal,
        BigDecimal bankPrincipal,
        BigDecimal principalVariance,
        BigDecimal ledgerCashDebits,
        BigDecimal bankCashDebits,
        BigDecimal cashVariance,
        int postedInstallmentCount,
        int bankInstallmentCount,
        List<ScheduleItemDiscrepancy> scheduleDiscrepancies,
        List<TimingDifferenceItem> timingDifferences,
        BigDecimal estimateVariance,
        BigDecimal unexplainedDifference,
        ReconciliationStatus status,
        String summary
) {

    public enum ReconciliationStatus {
        GREEN,  // Zero unexplained differences, fully reconciled
        AMBER,  // Timing differences or estimate variance (> IDR 1,000,000) present
        RED     // Unexplained difference > 0 (zero tolerance breach!)
    }

    public record ScheduleItemDiscrepancy(
            int installmentNumber,
            BigDecimal postedPrincipal,
            BigDecimal schedulePrincipal,
            BigDecimal postedInterest,
            BigDecimal scheduleInterest,
            BigDecimal postedFee,
            BigDecimal scheduleFee,
            String explanation
    ) {}

    public record TimingDifferenceItem(
            String reference,
            LocalDate valueDate,
            BigDecimal amount,
            int transitAgeDays,
            String description
    ) {}
}
