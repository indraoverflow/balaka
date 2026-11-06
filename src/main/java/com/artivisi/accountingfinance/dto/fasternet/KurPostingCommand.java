package com.artivisi.accountingfinance.dto.fasternet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Commands and parameters for KUR accounting postings (FAS-139 / FAS-97 / FAS-124).
 */
public final class KurPostingCommand {

    private KurPostingCommand() {}

    public record DisbursementCommand(
            UUID loanId,
            LocalDate valueDate,
            BigDecimal grossPrincipal,
            BigDecimal feeWithheld,
            String evidenceRef,
            String sourceRef,
            boolean forcePriorPeriodIfClosed
    ) {}

    public record InstallmentCommand(
            UUID loanId,
            Integer installmentNumber,
            LocalDate valueDate,
            BigDecimal cashPaid,
            BigDecimal feePaid,
            String evidenceRef,
            String sourceRef,
            boolean forcePriorPeriodIfClosed
    ) {}

    public record AccrualCommand(
            UUID loanId,
            Integer installmentNumber,
            LocalDate valueDate,
            String sourceRef,
            boolean forcePriorPeriodIfClosed
    ) {}

    public record ReclassCommand(
            UUID loanId,
            LocalDate asOfDate,
            String sourceRef,
            boolean forcePriorPeriodIfClosed
    ) {}

    public record PairedReversalCommand(
            UUID loanId,
            UUID paymentId,
            String reason,
            String operator
    ) {}
}
