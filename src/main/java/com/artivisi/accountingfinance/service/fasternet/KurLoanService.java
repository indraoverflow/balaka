package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.dto.fasternet.KurReserveResponse;
import com.artivisi.accountingfinance.dto.fasternet.KurScheduleResponse;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.KurPayment;
import com.artivisi.accountingfinance.entity.KurReserveEntry;
import com.artivisi.accountingfinance.enums.AuditEventType;
import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.exception.KurResourceNotFoundException;
import com.artivisi.accountingfinance.repository.ChartOfAccountRepository;
import com.artivisi.accountingfinance.repository.DocumentRepository;
import com.artivisi.accountingfinance.repository.KurInstallmentRepository;
import com.artivisi.accountingfinance.repository.KurLoanRepository;
import com.artivisi.accountingfinance.repository.KurPaymentRepository;
import com.artivisi.accountingfinance.repository.KurReserveEntryRepository;
import com.artivisi.accountingfinance.service.DashboardService;
import com.artivisi.accountingfinance.service.SecurityAuditService;
import com.artivisi.accountingfinance.util.KurMoneyMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * KUR loan master, authoritative repayment schedule, installment payments and the
 * installment reserve tracker (FAS-23).
 *
 * <p>Guardrails enforced here:
 * <ul>
 *   <li>Provenance starts at {@code FOUNDER_ESTIMATE}; a loan is only
 *       {@code VERIFIED} when an agreement and the lender schedule are attached.</li>
 *   <li>Schedule and payment imports are idempotent by source reference.</li>
 *   <li>Reserving funds only changes the reserve buckets; it never marks an
 *       installment paid. Only a payment with an evidence reference does that.</li>
 *   <li>Corrections reverse a payment; rows are never deleted.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class KurLoanService {

    public static final String CONTRACT_VERSION = "fasternet.dashboard/v1";
    public static final String CURRENCY = "IDR";
    public static final String INVARIANT = "reserving funds never marks an installment paid";

    private final KurLoanRepository loanRepository;
    private final KurInstallmentRepository installmentRepository;
    private final KurPaymentRepository paymentRepository;
    private final KurReserveEntryRepository reserveEntryRepository;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final DashboardService dashboardService;
    private final DocumentRepository documentRepository;
    private final SecurityAuditService securityAuditService;

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    public record CreateLoanCommand(
            String lender,
            String product,
            String loanReference,
            String borrowerName,
            String borrowerLegalOwner,
            BigDecimal principalOriginal,
            LocalDate disbursementDate,
            Integer termMonths,
            BigDecimal installmentContractual,
            String installmentFrequency,
            Integer installmentCount,
            LocalDate firstDueDate,
            Integer monthlyDueDay,
            LocalDate maturityDate,
            BigDecimal fees,
            UUID agreementDocumentId,
            UUID scheduleDocumentId,
            KurLoanProvenance provenance,
            String notes
    ) {
    }

    public record ScheduleRow(
            Integer installmentNumber,
            LocalDate dueDate,
            BigDecimal amount,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal fee,
            KurScheduleSourceKind sourceKind,
            String sourceRef
    ) {
    }

    public record PaymentCommand(
            LocalDate paymentDate,
            BigDecimal amount,
            String bankRef,
            String evidenceRef,
            String sourceRef
    ) {
    }

    public record ReserveCommand(
            LocalDate entryDate,
            KurReserveEntryKind kind,
            BigDecimal amount,
            String description,
            String sourceRef
    ) {
    }

    /** Whether the write created a row or returned the row an earlier retry created. */
    public record ScheduleImportOutcome(KurInstallment installment, boolean created) {
    }

    public record PaymentOutcome(KurPayment payment, boolean created) {
    }

    public record ReserveOutcome(KurReserveEntry entry, boolean created) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public KurLoan createLoan(CreateLoanCommand command) {
        Objects.requireNonNull(command, "command");
        requireText(command.lender(), "lender");
        requireText(command.borrowerName(), "borrowerName");
        requirePositive(command.principalOriginal(), "principalOriginal");
        requirePositiveInteger(command.termMonths(), "termMonths");
        requirePositiveInteger(command.installmentCount(), "installmentCount");

        KurLoanProvenance provenance = command.provenance() == null
                ? KurLoanProvenance.FOUNDER_ESTIMATE
                : command.provenance();
        if (provenance == KurLoanProvenance.VERIFIED
                && (command.agreementDocumentId() == null || command.scheduleDocumentId() == null)) {
            throw new IllegalArgumentException(
                    "A verified loan requires both the signed agreement and the lender schedule to be attached");
        }
        requireDocumentIfPresent(command.agreementDocumentId(), "agreementDocumentId");
        requireDocumentIfPresent(command.scheduleDocumentId(), "scheduleDocumentId");

        KurLoan loan = new KurLoan();
        loan.setLender(command.lender().trim());
        loan.setProduct(hasText(command.product()) ? command.product().trim() : "KUR");
        loan.setLoanReference(trimToNull(command.loanReference()));
        loan.setBorrowerName(command.borrowerName().trim());
        loan.setBorrowerLegalOwner(trimToNull(command.borrowerLegalOwner()));
        loan.setPrincipalOriginal(KurMoneyMath.money(command.principalOriginal()));
        loan.setDisbursementDate(command.disbursementDate());
        loan.setTermMonths(command.termMonths());
        loan.setInstallmentContractual(KurMoneyMath.money(command.installmentContractual()));
        loan.setInstallmentFrequency(hasText(command.installmentFrequency())
                ? command.installmentFrequency().trim() : "MONTHLY");
        loan.setInstallmentCount(command.installmentCount());
        loan.setFirstDueDate(command.firstDueDate());
        loan.setMonthlyDueDay(command.monthlyDueDay());
        loan.setMaturityDate(command.maturityDate());
        loan.setFees(KurMoneyMath.money(command.fees()));
        loan.setAgreementDocumentId(command.agreementDocumentId());
        loan.setScheduleDocumentId(command.scheduleDocumentId());
        loan.setProvenance(provenance);
        loan.setNotes(trimToNull(command.notes()));
        KurLoan saved = loanRepository.save(loan);
        audit(AuditEventType.KUR_LOAN_CREATE,
                "KUR loan created: id=" + saved.getId()
                        + ", lender=" + saved.getLender()
                        + ", principal=" + moneyString(saved.getPrincipalOriginal())
                        + ", provenance=" + saved.getProvenance()
                        + ", agreementDocumentId=" + saved.getAgreementDocumentId()
                        + ", scheduleDocumentId=" + saved.getScheduleDocumentId());
        return saved;
    }

    /**
     * Imports one schedule row. Re-sending the same loan/source-kind/source-ref
     * returns the existing row instead of creating a duplicate (idempotency).
     */
    @Transactional
    public KurInstallment importScheduleRow(UUID loanId, ScheduleRow row) {
        return importScheduleRowOutcome(loanId, row).installment();
    }

    /**
     * Same as {@link #importScheduleRow(UUID, ScheduleRow)} but reports whether a
     * row was created, so the HTTP layer can answer 201 on the first import and
     * 200 on an idempotent replay.
     */
    @Transactional
    public ScheduleImportOutcome importScheduleRowOutcome(UUID loanId, ScheduleRow row) {
        Objects.requireNonNull(row, "row");
        KurLoan loan = requireLoan(loanId);
        requirePositiveInteger(row.installmentNumber(), "installmentNumber");
        Objects.requireNonNull(row.dueDate(), "dueDate");
        requireNonNegative(row.amount(), "amount");
        KurScheduleSourceKind sourceKind = row.sourceKind() == null
                ? KurScheduleSourceKind.ESTIMATE : row.sourceKind();
        requireText(row.sourceRef(), "sourceRef");

        String sourceRef = row.sourceRef().trim();
        KurInstallment sameSource = installmentRepository
                .findByLoanIdAndSourceKindAndSourceRef(loanId, sourceKind, sourceRef)
                .orElse(null);
        if (sameSource != null) {
            requireSameScheduleRow(sameSource, row, sourceRef);
            return new ScheduleImportOutcome(sameSource, false);
        }

        KurInstallment sameNumber = installmentRepository
                .findByLoanIdAndInstallmentNumber(loanId, row.installmentNumber())
                .orElse(null);
        if (sameNumber != null) {
            String before = scheduleRowValues(sameNumber);
            KurInstallment rebased = reconcileWithLenderRow(sameNumber, row, sourceKind, sourceRef);
            audit(AuditEventType.KUR_SCHEDULE_IMPORT,
                    "KUR schedule row re-baselined: loanId=" + loanId
                            + ", installmentId=" + rebased.getId()
                            + ", before[" + before + "]"
                            + ", after[" + scheduleRowValues(rebased) + "]");
            return new ScheduleImportOutcome(rebased, false);
        }

        KurInstallment installment = new KurInstallment();
        installment.setLoan(loan);
        installment.setInstallmentNumber(row.installmentNumber());
        installment.setDueDate(row.dueDate());
        installment.setAmount(KurMoneyMath.money(row.amount()));
        installment.setPrincipal(KurMoneyMath.money(row.principal()));
        installment.setInterest(KurMoneyMath.money(row.interest()));
        installment.setFee(KurMoneyMath.money(row.fee()));
        installment.setSourceKind(sourceKind);
        installment.setSourceRef(sourceRef);
        installment.setPaidAmount(KurMoneyMath.ZERO);
        installment.setStatus(KurInstallmentStatus.UNPAID);
        KurInstallment saved = installmentRepository.save(installment);
        audit(AuditEventType.KUR_SCHEDULE_IMPORT,
                "KUR schedule row imported: loanId=" + loanId
                        + ", installmentId=" + saved.getId()
                        + ", values[" + scheduleRowValues(saved) + "]");
        return new ScheduleImportOutcome(saved, true);
    }

    /**
     * Records a payment against an installment. Evidence is mandatory: missing
     * evidence means the installment is not paid. The same source reference is
     * applied once.
     */
    @Transactional
    public KurPayment recordPayment(UUID loanId, UUID installmentId, PaymentCommand command) {
        return recordPaymentOutcome(loanId, installmentId, command).payment();
    }

    @Transactional
    public PaymentOutcome recordPaymentOutcome(UUID loanId, UUID installmentId, PaymentCommand command) {
        Objects.requireNonNull(command, "command");
        KurLoan loan = requireLoan(loanId);
        KurInstallment installment = installmentRepository.findById(installmentId)
                .orElseThrow(() -> new KurResourceNotFoundException("Unknown installment: " + installmentId));
        if (!installment.getLoan().getId().equals(loan.getId())) {
            throw new IllegalArgumentException("Installment does not belong to loan " + loanId);
        }
        requireText(command.evidenceRef(), "evidenceRef");
        requireText(command.sourceRef(), "sourceRef");
        requirePositive(command.amount(), "amount");
        Objects.requireNonNull(command.paymentDate(), "paymentDate");

        KurPayment existing = paymentRepository
                .findByInstallmentIdAndSourceRef(installmentId, command.sourceRef().trim())
                .orElse(null);
        if (existing != null) {
            return new PaymentOutcome(existing, false);
        }

        KurPayment payment = new KurPayment();
        payment.setInstallment(installment);
        payment.setPaymentDate(command.paymentDate());
        payment.setAmount(KurMoneyMath.money(command.amount()));
        payment.setBankRef(trimToNull(command.bankRef()));
        payment.setEvidenceRef(command.evidenceRef().trim());
        payment.setSourceRef(command.sourceRef().trim());
        installment.getPayments().add(payment);
        installment.recalculateFromPayments();
        KurPayment saved = paymentRepository.save(payment);
        installmentRepository.save(installment);
        audit(AuditEventType.KUR_PAYMENT_RECORD,
                "KUR payment recorded: loanId=" + loanId
                        + ", installmentId=" + installmentId
                        + ", paymentId=" + saved.getId()
                        + ", amount=" + moneyString(saved.getAmount())
                        + ", paymentDate=" + saved.getPaymentDate()
                        + ", sourceRef=" + saved.getSourceRef()
                        + ", evidenceRef=" + saved.getEvidenceRef()
                        + ", installmentStatus=" + installment.getStatus()
                        + ", installmentPaidAmount=" + moneyString(installment.getPaidAmount()));
        return new PaymentOutcome(saved, true);
    }

    /**
     * Reverses a payment instead of deleting it. The installment's paid total and
     * status are recomputed from the remaining active payments.
     */
    /**
     * Reverses a payment that must belong to the supplied loan. The membership check
     * runs inside the transaction before any mutation, so a mismatched loan id cannot
     * leave a half-applied correction.
     */
    @Transactional
    public KurPayment reversePayment(UUID loanId, UUID paymentId, String reason) {
        KurPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new KurResourceNotFoundException("Unknown payment: " + paymentId));
        if (!payment.getInstallment().getLoan().getId().equals(loanId)) {
            throw new IllegalArgumentException("Payment does not belong to loan " + loanId);
        }
        return reversePayment(paymentId, reason);
    }

    @Transactional
    public KurPayment reversePayment(UUID paymentId, String reason) {
        requireText(reason, "reason");
        KurPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new KurResourceNotFoundException("Unknown payment: " + paymentId));
        if (payment.isReversed()) {
            return payment;
        }
        KurInstallment installment = payment.getInstallment();
        String statusBefore = installment.getStatus().name();
        String paidBefore = moneyString(installment.getPaidAmount());
        payment.setReversedAt(java.time.LocalDateTime.now());
        payment.setReversalReason(reason.trim());
        installment.recalculateFromPayments();
        installmentRepository.save(installment);
        KurPayment saved = paymentRepository.save(payment);
        audit(AuditEventType.KUR_PAYMENT_REVERSE,
                "KUR payment reversed: loanId=" + installment.getLoan().getId()
                        + ", installmentId=" + installment.getId()
                        + ", paymentId=" + saved.getId()
                        + ", amount=" + moneyString(saved.getAmount())
                        + ", sourceRef=" + saved.getSourceRef()
                        + ", reason=" + saved.getReversalReason()
                        + ", installmentStatus=" + statusBefore + "->" + installment.getStatus()
                        + ", installmentPaidAmount=" + paidBefore + "->" + moneyString(installment.getPaidAmount()));
        return saved;
    }

    /**
     * Records a reserve movement. Earmarking is internal and never a payment; a
     * release cannot take a bucket below zero.
     */
    @Transactional
    public KurReserveEntry recordReserve(UUID loanId, ReserveCommand command) {
        return recordReserveOutcome(loanId, command).entry();
    }

    @Transactional
    public ReserveOutcome recordReserveOutcome(UUID loanId, ReserveCommand command) {
        Objects.requireNonNull(command, "command");
        KurLoan loan = requireLoan(loanId);
        Objects.requireNonNull(command.entryDate(), "entryDate");
        Objects.requireNonNull(command.kind(), "kind");
        requirePositive(command.amount(), "amount");
        requireText(command.sourceRef(), "sourceRef");

        KurReserveEntry existing = reserveEntryRepository
                .findByLoanIdAndSourceRef(loanId, command.sourceRef().trim())
                .orElse(null);
        if (existing != null) {
            return new ReserveOutcome(existing, false);
        }

        if (command.kind().isRelease()) {
            BigDecimal available = bucketBalance(loanId, command.entryDate(), command.kind().isEarmarked());
            if (available.compareTo(command.amount()) < 0) {
                throw new IllegalArgumentException(
                        "Reserve release of " + KurMoneyMath.money(command.amount())
                                + " exceeds the available "
                                + (command.kind().isEarmarked() ? "earmarked" : "separate-account")
                                + " balance " + available);
            }
        }

        KurReserveEntry entry = new KurReserveEntry();
        entry.setLoan(loan);
        entry.setEntryDate(command.entryDate());
        entry.setKind(command.kind());
        entry.setAmount(KurMoneyMath.money(command.amount()));
        entry.setDescription(trimToNull(command.description()));
        entry.setSourceRef(command.sourceRef().trim());
        KurReserveEntry saved = reserveEntryRepository.save(entry);
        audit(AuditEventType.KUR_RESERVE_RECORD,
                "KUR reserve movement recorded: loanId=" + loanId
                        + ", reserveEntryId=" + saved.getId()
                        + ", kind=" + saved.getKind()
                        + ", amount=" + moneyString(saved.getAmount())
                        + ", entryDate=" + saved.getEntryDate()
                        + ", sourceRef=" + saved.getSourceRef());
        return new ReserveOutcome(saved, true);
    }

    /**
     * Marks a loan VERIFIED. Only a Finance Controller holding {@code KUR_VERIFY}
     * reaches this method through the HTTP layer, and both documents must be on the
     * loan (or supplied here). A founder estimate is never upgraded automatically.
     */
    @Transactional
    public KurLoan verifyLoan(UUID loanId, UUID agreementDocumentId, UUID scheduleDocumentId, String reason) {
        requireText(reason, "reason");
        KurLoan loan = requireLoan(loanId);
        UUID agreement = agreementDocumentId != null ? agreementDocumentId : loan.getAgreementDocumentId();
        UUID schedule = scheduleDocumentId != null ? scheduleDocumentId : loan.getScheduleDocumentId();
        if (agreement == null || schedule == null) {
            throw new IllegalArgumentException(
                    "A verified loan requires both the signed agreement and the lender schedule to be attached");
        }
        requireDocumentIfPresent(agreement, "agreementDocumentId");
        requireDocumentIfPresent(schedule, "scheduleDocumentId");

        KurLoanProvenance before = loan.getProvenance();
        loan.setAgreementDocumentId(agreement);
        loan.setScheduleDocumentId(schedule);
        loan.setProvenance(KurLoanProvenance.VERIFIED);
        KurLoan saved = loanRepository.save(loan);
        audit(AuditEventType.KUR_LOAN_VERIFY,
                "KUR loan verified: loanId=" + loanId
                        + ", provenance=" + before + "->" + saved.getProvenance()
                        + ", agreementDocumentId=" + agreement
                        + ", scheduleDocumentId=" + schedule
                        + ", reason=" + reason.trim());
        return saved;
    }

    /**
     * A re-imported source reference must describe the same row. Silently
     * accepting changed values would make an import non-deterministic.
     */
    private void requireSameScheduleRow(KurInstallment existing, ScheduleRow row, String sourceRef) {
        boolean same = Objects.equals(existing.getInstallmentNumber(), row.installmentNumber())
                && Objects.equals(existing.getDueDate(), row.dueDate())
                && sameMoney(existing.getAmount(), row.amount())
                && sameMoney(existing.getPrincipal(), row.principal())
                && sameMoney(existing.getInterest(), row.interest())
                && sameMoney(existing.getFee(), row.fee());
        if (!same) {
            throw new IllegalStateException(
                    "Schedule source " + sourceRef
                            + " was already imported with different values; use a new source reference"
                            + " and a correction entry instead");
        }
    }

    /**
     * Replaces a calculated estimate row with the lender's authoritative row for
     * the same installment number. The estimate is only re-baselined while no
     * payment exists; once money is recorded the row can only be corrected by a
     * reversal, never by overwriting history.
     */
    private KurInstallment reconcileWithLenderRow(
            KurInstallment existing, ScheduleRow row, KurScheduleSourceKind sourceKind, String sourceRef) {
        if (sourceKind == KurScheduleSourceKind.ESTIMATE) {
            throw new IllegalStateException(
                    "Installment " + row.installmentNumber()
                            + " already has an authoritative schedule row; an estimate cannot overwrite it");
        }
        if (existing.getSourceKind() == KurScheduleSourceKind.LENDER_SCHEDULE) {
            throw new IllegalStateException(
                    "Installment " + row.installmentNumber()
                            + " already has a lender schedule row (" + existing.getSourceRef() + ")");
        }
        boolean hasPayments = existing.getPayments().stream()
                .anyMatch(payment -> payment.getReversedAt() == null);
        if (hasPayments) {
            throw new IllegalStateException(
                    "Installment " + row.installmentNumber()
                            + " already has recorded payments; reverse them before re-baselining the schedule");
        }

        existing.setDueDate(row.dueDate());
        existing.setAmount(KurMoneyMath.money(row.amount()));
        existing.setPrincipal(KurMoneyMath.money(row.principal()));
        existing.setInterest(KurMoneyMath.money(row.interest()));
        existing.setFee(KurMoneyMath.money(row.fee()));
        existing.setSourceKind(sourceKind);
        existing.setSourceRef(sourceRef);
        return installmentRepository.save(existing);
    }

    private static boolean sameMoney(BigDecimal stored, BigDecimal incoming) {
        if (stored == null || incoming == null) {
            return stored == null && incoming == null;
        }
        return KurMoneyMath.money(stored).compareTo(KurMoneyMath.money(incoming)) == 0;
    }

    // ------------------------------------------------------------------
    // Read projections
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public KurScheduleResponse schedule(UUID loanId, LocalDate asOf) {
        KurLoan loan = requireLoan(loanId);
        List<KurInstallment> installments = installmentRepository
                .findByLoanIdOrderByInstallmentNumberAsc(loanId);
        List<KurScheduleResponse.Missing> missing = new ArrayList<>();
        if (!loan.getProvenance().isConfirmed()) {
            missing.add(new KurScheduleResponse.Missing(
                    "kur.schedule",
                    "loan terms and repayment schedule are " + loan.getProvenance().getJsonValue()
                            + "; not reconciled against the lender's official schedule",
                    "Finance Controller"));
        }
        if (loan.getAgreementDocumentId() == null) {
            missing.add(new KurScheduleResponse.Missing(
                    "kur.agreement", "signed KUR agreement is not attached", "Finance Controller"));
        }
        if (loan.getScheduleDocumentId() == null) {
            missing.add(new KurScheduleResponse.Missing(
                    "kur.scheduleDocument", "lender repayment schedule is not attached", "Finance Controller"));
        }

        KurScheduleResponse.Loan loanDto = new KurScheduleResponse.Loan(
                loan.getLender(),
                loan.getProduct(),
                null,
                moneyString(loan.getPrincipalOriginal()),
                dateString(loan.getDisbursementDate()),
                loan.getTermMonths(),
                moneyString(loan.getInstallmentContractual()),
                dateString(loan.getFirstDueDate()),
                dateString(loan.getMaturityDate()),
                loan.getProvenance().getJsonValue(),
                loan.getAgreementDocumentId() != null,
                loan.getScheduleDocumentId() != null);

        List<KurScheduleResponse.Installment> rows = installments.stream()
                .map(installment -> new KurScheduleResponse.Installment(
                        installment.getInstallmentNumber(),
                        dateString(installment.getDueDate()),
                        moneyString(installment.getAmount()),
                        moneyString(installment.getPrincipal()),
                        moneyString(installment.getInterest()),
                        moneyString(installment.getFee()),
                        moneyString(installment.getPaidAmount()),
                        dateString(installment.getPaidDate()),
                        moneyString(installment.getOutstanding()),
                        installment.getStatus().getJsonValue(),
                        installment.getBankRef(),
                        installment.getEvidenceRef(),
                        new KurScheduleResponse.Source(
                                installment.getSourceKind().getJsonValue(),
                                installment.getSourceRef())))
                .toList();

        return new KurScheduleResponse(
                CONTRACT_VERSION,
                Instant.now().toString(),
                dateString(asOf == null ? LocalDate.now() : asOf),
                CURRENCY,
                loanDto,
                rows,
                missing);
    }

    @Transactional(readOnly = true)
    public KurReserveResponse reserve(UUID loanId, LocalDate asOf) {
        KurLoan loan = requireLoan(loanId);
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now() : asOf;
        List<KurReserveEntry> entries = reserveEntryRepository
                .findByLoanIdAndEntryDateLessThanEqualOrderByEntryDateAsc(loanId, effectiveAsOf);
        Map<String, KurReserveResponse.Figure> figures = new LinkedHashMap<>();
        List<KurReserveResponse.Missing> missing = new ArrayList<>();

        BigDecimal earmarked = bucket(entries, true);
        BigDecimal separate = bucket(entries, false);
        boolean reserveConfigured = !entries.isEmpty();
        figures.put("reserve.earmarked", reserveConfigured
                ? new KurReserveResponse.Figure(moneyString(earmarked), "posted")
                : new KurReserveResponse.Figure(null, "unavailable"));
        figures.put("reserve.separateAccount", reserveConfigured
                ? new KurReserveResponse.Figure(moneyString(separate), "posted")
                : new KurReserveResponse.Figure(null, "unavailable"));
        if (!reserveConfigured) {
            missing.add(new KurReserveResponse.Missing(
                    "reserve.earmarked", "no reserve movements recorded for this loan", "Finance Controller"));
        }

        // Cash actually held in the ledger's cash/bank accounts.
        if (chartOfAccountRepository.findCashBankAccounts().isEmpty()) {
            figures.put("cash.actual", new KurReserveResponse.Figure(null, "unavailable"));
            missing.add(new KurReserveResponse.Missing(
                    "cash.actual", "no cash/bank account is configured in the chart of accounts", "Finance Controller"));
        } else {
            figures.put("cash.actual", new KurReserveResponse.Figure(
                    moneyString(dashboardService.calculateCashBalance(effectiveAsOf)), "posted"));
        }

        // Next unpaid installment as of the effective date.
        KurInstallment nextDue = firstUnpaidAsOf(loanId, effectiveAsOf);
        if (nextDue == null) {
            figures.put("obligation.nextDue", new KurReserveResponse.Figure(null, "unavailable"));
            missing.add(new KurReserveResponse.Missing(
                    "obligation.nextDue", "no unpaid installment is recorded for this loan", "Finance Controller"));
        } else {
            figures.put("obligation.nextDue", new KurReserveResponse.Figure(
                    moneyString(nextDue.getAmount()),
                    loan.getProvenance().isConfirmed() ? "verified" : "estimate"));
        }

        BigDecimal completed = paymentRepository
                .findByInstallmentLoanIdAndReversedAtIsNullAndPaymentDateLessThanEqualOrderByPaymentDateAsc(
                        loanId, effectiveAsOf)
                .stream()
                .map(KurPayment::getAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        figures.put("obligation.completed", new KurReserveResponse.Figure(moneyString(completed), "posted"));

        if (nextDue != null && reserveConfigured) {
            figures.put("reserve.gap", new KurReserveResponse.Figure(
                    moneyString(KurMoneyMath.reserveGap(nextDue.getAmount(), earmarked, separate)), "posted"));
        } else {
            figures.put("reserve.gap", new KurReserveResponse.Figure(null, "unavailable"));
            missing.add(new KurReserveResponse.Missing(
                    "reserve.gap",
                    nextDue == null ? "no next obligation" : "reserve is not configured",
                    "Finance Controller"));
        }

        return new KurReserveResponse(
                CONTRACT_VERSION,
                Instant.now().toString(),
                dateString(effectiveAsOf),
                CURRENCY,
                figures,
                INVARIANT,
                missing);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private KurLoan requireLoan(UUID loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new KurResourceNotFoundException("Unknown KUR loan: " + loanId));
    }

    private void requireDocumentIfPresent(UUID documentId, String field) {
        if (documentId != null && !documentRepository.existsById(documentId)) {
            throw new IllegalArgumentException("Unknown document for " + field + ": " + documentId);
        }
    }

    private static String scheduleRowValues(KurInstallment installment) {
        return "number=" + installment.getInstallmentNumber()
                + ", dueDate=" + installment.getDueDate()
                + ", amount=" + moneyString(installment.getAmount())
                + ", principal=" + moneyString(installment.getPrincipal())
                + ", interest=" + moneyString(installment.getInterest())
                + ", fee=" + moneyString(installment.getFee())
                + ", sourceKind=" + installment.getSourceKind()
                + ", sourceRef=" + installment.getSourceRef();
    }

    private void audit(AuditEventType eventType, String details) {
        securityAuditService.log(eventType, details);
    }

    private BigDecimal bucket(List<KurReserveEntry> entries, boolean earmarked) {
        return entries.stream()
                .filter(entry -> entry.getKind().isEarmarked() == earmarked)
                .map(entry -> entry.getKind().isRelease()
                        ? entry.getAmount().negate()
                        : entry.getAmount())
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private BigDecimal bucketBalance(UUID loanId, LocalDate asOf, boolean earmarked) {
        return bucket(reserveEntryRepository
                .findByLoanIdAndEntryDateLessThanEqualOrderByEntryDateAsc(loanId, asOf), earmarked);
    }

    private KurInstallment firstUnpaidAsOf(UUID loanId, LocalDate asOf) {
        List<KurInstallment> installments = installmentRepository
                .findByLoanIdOrderByInstallmentNumberAsc(loanId);
        Map<UUID, BigDecimal> paidByInstallment = paymentRepository
                .findByInstallmentLoanIdAndReversedAtIsNullAndPaymentDateLessThanEqualOrderByPaymentDateAsc(
                        loanId, asOf)
                .stream()
                .collect(Collectors.toMap(
                        payment -> payment.getInstallment().getId(),
                        KurPayment::getAmount,
                        BigDecimal::add));

        return installments.stream()
                .filter(installment -> paidByInstallment
                        .getOrDefault(installment.getId(), BigDecimal.ZERO.setScale(2))
                        .compareTo(installment.getAmount()) < 0)
                .min(java.util.Comparator.comparing(KurInstallment::getDueDate))
                .orElse(null);
    }

    private static String moneyString(BigDecimal value) {
        return value == null ? null : KurMoneyMath.money(value).toPlainString();
    }

    private static String dateString(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static void requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void requirePositiveInteger(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
