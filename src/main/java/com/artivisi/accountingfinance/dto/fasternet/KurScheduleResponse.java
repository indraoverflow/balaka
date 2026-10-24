package com.artivisi.accountingfinance.dto.fasternet;

import java.util.List;

/**
 * {@code GET /api/fasternet/v1/kur/schedule} response, contract
 * {@code fasternet.dashboard/v1} §3.2.
 *
 * <p>Money is emitted as decimal strings. Principal/interest/fee stay null until the
 * official lender schedule supplies them; the source block always names whether a
 * row is an estimate or a lender schedule row.
 */
public record KurScheduleResponse(
        String contractVersion,
        String generatedAt,
        String asOf,
        String currency,
        Loan loan,
        List<Installment> installments,
        List<Missing> missing
) {

    public record Loan(
            String lender,
            String product,
            String reference,
            String principalOriginal,
            String disbursementDate,
            Integer termMonths,
            String installmentContractual,
            String firstDueDate,
            String maturityDate,
            String provenance,
            boolean agreementAttached,
            boolean scheduleAttached
    ) {
    }

    public record Installment(
            Integer number,
            String dueDate,
            String amount,
            String principal,
            String interest,
            String fee,
            String paidAmount,
            String paidDate,
            String outstanding,
            String status,
            String bankRef,
            String evidenceRef,
            Source source
    ) {
    }

    public record Source(String kind, String ref) {
    }

    public record Missing(String key, String reason, String owner) {
    }
}
