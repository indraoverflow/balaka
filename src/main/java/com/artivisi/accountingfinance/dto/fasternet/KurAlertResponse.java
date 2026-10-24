package com.artivisi.accountingfinance.dto.fasternet;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/fasternet/v1/kur/alert} response ({@code fasternet.dashboard/v1}
 * §3.5).
 *
 * <p>The alert aggregates the next unpaid KUR installment, the reserve gap from
 * {@link com.artivisi.accountingfinance.service.fasternet.KurLoanService}, actual
 * ledger cash and the approved threshold policy. It never presents a reserved
 * amount as a completed payment, and it never renders green while the policy is
 * unconfigured or a required input is unavailable.
 *
 * <p>{@code alert.state} is one of {@code green}, {@code yellow}, {@code orange},
 * {@code red}, {@code unconfigured}, {@code unavailable}. The last two are not
 * colours: they mean the state cannot be evaluated safely.
 */
public record KurAlertResponse(
        String contractVersion,
        String generatedAt,
        String asOf,
        String currency,
        Alert alert,
        List<Missing> missing
) {

    public record Alert(
            String state,
            String severity,
            String reason,
            Shortfall shortfall,
            String dueDate,
            Long daysRemaining,
            Integer installmentNumber,
            Obligation obligation,
            Reserve reserve,
            Cash cash,
            Payment payment,
            Policy policy,
            List<String> assumptions,
            String invariant
    ) {
    }

    public record Shortfall(String amount, String currency, String basis, String status) {
    }

    public record Obligation(
            String amount,
            String paidAmount,
            String outstanding,
            String paymentStatus,
            String scheduleStatus,
            String provenance,
            String sourceKind,
            String sourceRef
    ) {
    }

    public record Reserve(
            String qualifying,
            String earmarked,
            String separateAccount,
            String status
    ) {
    }

    public record Cash(
            String actual,
            String minimumRequired,
            String source,
            String asOf,
            String status
    ) {
    }

    public record Payment(
            String status,
            String paidAmount,
            boolean countsAsPaid,
            String note
    ) {
    }

    public record Policy(
            String status,
            String approvalReference,
            String approvedBy,
            String approvedAt,
            Map<String, String> thresholds
    ) {
    }

    public record Missing(String key, String reason, String owner) {
    }
}
