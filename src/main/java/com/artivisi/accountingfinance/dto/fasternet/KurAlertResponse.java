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
 * colours: they mean the state cannot be evaluated safely. {@code alert.state} is
 * a v1 operational state and is <em>not</em> the framework §8 level.
 *
 * <p>{@code alert.frameworkLevel} is one of {@code GREEN}, {@code AMBER},
 * {@code RED}, {@code CRITICAL}, {@code unavailable}. v1 has no forward cash-flow
 * forecast, so it returns {@code unavailable} unless the Finance Controller has
 * recorded a forecast-backed level; a v1 {@code green} is never framework GREEN.
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
            String frameworkLevel,
            String frameworkLevelReason,
            String frameworkLevelSource,
            Shortfall shortfall,
            String dueDate,
            Long daysRemaining,
            Integer installmentNumber,
            Obligation obligation,
            Reserve reserve,
            Cash cash,
            Payment payment,
            Policy policy,
            Provenance provenance,
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
            String status,
            List<ReserveComposition> composition
    ) {
    }

    /** One reserve-tracker row; the v1 tracker has no separate account/tag id. */
    public record ReserveComposition(
            String entryId,
            String pool,
            String kind,
            String amount,
            String entryDate,
            String sourceRef
    ) {
    }

    public record Cash(
            String actual,
            String minimumRequired,
            String source,
            String asOf,
            String status,
            List<String> accountCodes
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

    /**
     * Provenance required by contract §0/§7 for a financial alert: the loan master,
     * the next schedule row, the reserve-tracker rows and the cash/bank accounts
     * used to derive {@code cash.actual}.
     */
    public record Provenance(
            String loanId,
            String nextScheduleRowId,
            String scheduleProvenance,
            String scheduleSourceKind,
            String scheduleSourceRef,
            List<ReserveComposition> reserveComposition,
            List<String> cashAccountCodes,
            String cashAsOf
    ) {
    }

    public record Missing(String key, String reason, String owner) {
    }
}
