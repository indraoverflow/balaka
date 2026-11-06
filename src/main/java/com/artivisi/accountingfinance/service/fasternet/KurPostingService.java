package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.dto.FormulaContext;
import com.artivisi.accountingfinance.dto.fasternet.KurPostingCommand;
import com.artivisi.accountingfinance.dto.fasternet.KurPostingResult;
import com.artivisi.accountingfinance.entity.ChartOfAccount;
import com.artivisi.accountingfinance.entity.JournalEntry;
import com.artivisi.accountingfinance.entity.JournalTemplate;
import com.artivisi.accountingfinance.entity.JournalTemplateLine;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.KurPayment;
import com.artivisi.accountingfinance.entity.Transaction;
import com.artivisi.accountingfinance.enums.AuditEventType;
import com.artivisi.accountingfinance.enums.FiscalPeriodStatus;
import com.artivisi.accountingfinance.enums.JournalPosition;
import com.artivisi.accountingfinance.enums.TransactionStatus;
import com.artivisi.accountingfinance.enums.VoidReason;
import com.artivisi.accountingfinance.exception.KurPostingRejectedException;
import com.artivisi.accountingfinance.repository.ChartOfAccountRepository;
import com.artivisi.accountingfinance.repository.JournalEntryRepository;
import com.artivisi.accountingfinance.repository.JournalTemplateRepository;
import com.artivisi.accountingfinance.repository.KurInstallmentRepository;
import com.artivisi.accountingfinance.repository.KurLoanRepository;
import com.artivisi.accountingfinance.repository.KurPaymentRepository;
import com.artivisi.accountingfinance.repository.TransactionRepository;
import com.artivisi.accountingfinance.security.CurrentUser;
import com.artivisi.accountingfinance.service.FormulaEvaluator;
import com.artivisi.accountingfinance.service.FiscalPeriodService;
import com.artivisi.accountingfinance.service.JournalEntryService;
import com.artivisi.accountingfinance.service.SecurityAuditService;
import com.artivisi.accountingfinance.service.TransactionService;
import com.artivisi.accountingfinance.util.KurMoneyMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * KUR accounting posting engine (FAS-139 / FAS-97 §14).
 *
 * <p>Every posting is derived from the staged journal templates provisioned by V014
 * (K-1 disbursement, K-2 installment, K-2 month-end accrual, K-3 reclass, K-4 fee).
 * The engine is <strong>staged</strong>: it refuses to run unless
 * {@code fasternet.kur.accounting-posting.enabled=true} <em>and</em> the template is
 * active, so turning it on is an explicit two-key decision. Production keeps the
 * property false and the templates inactive until the founder gates FAS-9 / FAS-86
 * clear and the Finance Controller activates them.
 *
 * <p>Guardrails:
 * <ul>
 *   <li>Value-date journaling: the value date drives which fiscal period is checked;
 *       a closed period is rejected unless the caller explicitly accepts a
 *       prior-period posting, which then hits the current open period and carries the
 *       prior-period flag in the journal notes.</li>
 *   <li>Idempotency: the source reference is the ledger idempotency key, so a replayed
 *       request returns the existing journal instead of double-posting.</li>
 *   <li>Legal-borrower structure: only STRUCTURE_A permits GL posting; the default is
 *       STRUCTURE_D (off).</li>
 *   <li>Paired reversal: reversing a payment reverses the tracker row and voids the
 *       linked journal in one transaction.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KurPostingService {

    public static final String PROPERTY_ENABLED = "fasternet.kur.accounting-posting.enabled";

    public static final String TEMPLATE_DISBURSEMENT = "Pencairan KUR BRI (K-1)";
    public static final String TEMPLATE_INSTALLMENT = "Angsuran KUR BRI (K-2)";
    public static final String TEMPLATE_ACCRUAL = "Akrual Bunga KUR Akhir Bulan (K-2)";
    public static final String TEMPLATE_RECLASS = "Reklasifikasi Pokok KUR Lancar (K-3)";

    static final String ACCOUNT_CASH_BANK = "1.1.10";
    static final String ACCOUNT_PREPAID_INSTALLMENT = "1.1.11";
    static final String ACCOUNT_SHORT_TERM = "2.1.30";
    static final String ACCOUNT_LONG_TERM = "2.2.01";

    private static final int IDEMPOTENCY_KEY_MAX = 100;
    private static final int SOURCE_REF_MAX = 40;

    private final KurLoanRepository loanRepository;
    private final KurInstallmentRepository installmentRepository;
    private final KurPaymentRepository paymentRepository;
    private final JournalTemplateRepository templateRepository;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final JournalEntryService journalEntryService;
    private final FiscalPeriodService fiscalPeriodService;
    private final FormulaEvaluator formulaEvaluator;
    private final SecurityAuditService securityAuditService;
    private final KurLoanService kurLoanService;

    @Value("${" + PROPERTY_ENABLED + ":false}")
    private boolean postingEnabled;

    // ------------------------------------------------------------------
    // Disbursement (K-1)
    // ------------------------------------------------------------------

    @Transactional
    public KurPostingResult postDisbursement(KurPostingCommand.DisbursementCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        KurLoan loan = requireLoan(command.loanId());
        requirePostingStructure(loan);
        requireText(command.evidenceRef(), "evidenceRef");
        String sourceRef = requireText(command.sourceRef(), "sourceRef");
        JournalTemplate template = requireActiveTemplate(TEMPLATE_DISBURSEMENT);

        LocalDate valueDate = command.valueDate() == null
                ? loan.getDisbursementDate() : command.valueDate();
        Objects.requireNonNull(valueDate, "valueDate");

        BigDecimal gross = KurMoneyMath.money(command.grossPrincipal() == null
                ? loan.getPrincipalOriginal() : command.grossPrincipal());
        requirePositive(gross, "grossPrincipal");
        BigDecimal fee = KurMoneyMath.nonNullMoney(
                command.feeWithheld() == null ? loan.getFees() : command.feeWithheld());
        if (fee.compareTo(gross) > 0) {
            throw new KurPostingRejectedException("invalid_amount",
                    "feeWithheld " + fee + " exceeds grossPrincipal " + gross);
        }

        String key = idempotencyKey("DISB", loan.getId(), sourceRef);
        KurPostingResult replay = replayIfPosted("DISBURSEMENT", loan.getId(), key, valueDate, gross, Map.of());
        if (replay != null) {
            return replay;
        }

        BigDecimal currentPortion = currentPortionWithinTwelveMonths(loan, valueDate, gross);
        BigDecimal nonCurrentPortion = gross.subtract(currentPortion);

        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        variables.put("gross_principal", gross);
        variables.put("fee_withheld", fee);
        variables.put("current_portion", currentPortion);
        variables.put("non_current_portion", nonCurrentPortion);

        PostingWindow window = resolveWindow(valueDate, command.forcePriorPeriodIfClosed());
        Transaction saved = postJournal(template, window, gross, variables,
                "KUR disbursement " + loan.getLender() + " " + String.valueOf(loan.getLoanReference()),
                sourceRef, "KUR_LOAN", loan.getId(), key, List.of());

        KurPostingResult result = new KurPostingResult("DISBURSEMENT", loan.getId(), saved.getId(),
                saved.getTransactionNumber(), valueDate, window.postingDate(), window.priorPeriod(),
                true, gross, Map.copyOf(variables), window.note());
        audit(AuditEventType.KUR_POSTING_DISBURSEMENT,
                "KUR disbursement posted: loanId=" + loan.getId()
                        + ", transactionId=" + saved.getId()
                        + ", valueDate=" + valueDate + ", postingDate=" + window.postingDate()
                        + ", priorPeriod=" + window.priorPeriod()
                        + ", gross=" + moneyString(gross) + ", fee=" + moneyString(fee)
                        + ", current=" + moneyString(currentPortion)
                        + ", nonCurrent=" + moneyString(nonCurrentPortion)
                        + ", sourceRef=" + sourceRef);
        return result;
    }

    // ------------------------------------------------------------------
    // Installment (K-2)
    // ------------------------------------------------------------------

    @Transactional
    public KurPostingResult postInstallment(KurPostingCommand.InstallmentCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        KurLoan loan = requireLoan(command.loanId());
        requirePostingStructure(loan);
        requireText(command.evidenceRef(), "evidenceRef");
        String sourceRef = requireText(command.sourceRef(), "sourceRef");
        JournalTemplate template = requireActiveTemplate(TEMPLATE_INSTALLMENT);

        KurInstallment installment = requireInstallment(loan.getId(), command.installmentNumber());
        LocalDate valueDate = command.valueDate() == null
                ? installment.getDueDate() : command.valueDate();
        Objects.requireNonNull(valueDate, "valueDate");

        BigDecimal cashPaid = KurMoneyMath.money(command.cashPaid());
        requirePositive(cashPaid, "cashPaid");
        BigDecimal feePaid = KurMoneyMath.nonNullMoney(command.feePaid());

        ScheduledSplit scheduled = scheduledSplit(installment);
        KurMoneyMath.InstallmentSplit split = KurMoneyMath.splitInstallment(
                cashPaid,
                scheduled.principal(),
                scheduled.interest(),
                scheduled.fee().add(feePaid));

        String key = idempotencyKey("INST", loan.getId(), sourceRef);
        Map<String, BigDecimal> components = Map.of(
                "principal", split.principal(),
                "interest", split.interest(),
                "fee", split.fee(),
                "overpayment", split.overpayment(),
                "cash_paid", cashPaid);
        KurPostingResult replay = replayIfPosted("INSTALLMENT", loan.getId(), key, valueDate,
                cashPaid, components);
        if (replay != null) {
            return replay;
        }

        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        variables.put("principal", split.principal());
        variables.put("interest", split.interest());
        variables.put("fee", split.fee());
        variables.put("cash_paid", cashPaid);

        List<ExtraLine> extraLines = new ArrayList<>();
        if (split.overpayment().signum() > 0) {
            ChartOfAccount prepaid = requireAccount(ACCOUNT_PREPAID_INSTALLMENT);
            extraLines.add(new ExtraLine(prepaid, JournalPosition.DEBIT, split.overpayment(),
                    "Kelebihan bayar angsuran KUR ke buffer 1.1.11"));
        }

        PostingWindow window = resolveWindow(valueDate, command.forcePriorPeriodIfClosed());
        Transaction saved = postJournal(template, window, cashPaid, variables,
                "KUR installment #" + installment.getInstallmentNumber(),
                sourceRef, "KUR_INSTALLMENT", installment.getId(), key, extraLines);

        linkPaymentJournal(installment, sourceRef, saved.getId());

        KurPostingResult result = new KurPostingResult("INSTALLMENT", loan.getId(), saved.getId(),
                saved.getTransactionNumber(), valueDate, window.postingDate(), window.priorPeriod(),
                true, cashPaid, Map.copyOf(components), window.note());
        audit(AuditEventType.KUR_POSTING_INSTALLMENT,
                "KUR installment posted: loanId=" + loan.getId()
                        + ", installmentId=" + installment.getId()
                        + ", installmentNumber=" + installment.getInstallmentNumber()
                        + ", transactionId=" + saved.getId()
                        + ", valueDate=" + valueDate + ", postingDate=" + window.postingDate()
                        + ", priorPeriod=" + window.priorPeriod()
                        + ", principal=" + moneyString(split.principal())
                        + ", interest=" + moneyString(split.interest())
                        + ", fee=" + moneyString(split.fee())
                        + ", overpayment=" + moneyString(split.overpayment())
                        + ", cashPaid=" + moneyString(cashPaid)
                        + ", scheduleComponents=" + (scheduled.estimated() ? "ESTIMATE" : "LENDER")
                        + ", sourceRef=" + sourceRef);
        return result;
    }

    // ------------------------------------------------------------------
    // Month-end accrual (K-2 §6.7)
    // ------------------------------------------------------------------

    @Transactional
    public KurPostingResult postAccrual(KurPostingCommand.AccrualCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        KurLoan loan = requireLoan(command.loanId());
        requirePostingStructure(loan);
        String sourceRef = requireText(command.sourceRef(), "sourceRef");
        JournalTemplate template = requireActiveTemplate(TEMPLATE_ACCRUAL);

        KurInstallment installment = requireInstallment(loan.getId(), command.installmentNumber());
        LocalDate valueDate = command.valueDate() == null
                ? monthEnd(installment.getDueDate()) : command.valueDate();
        Objects.requireNonNull(valueDate, "valueDate");

        ScheduledSplit scheduled = scheduledSplit(installment);
        BigDecimal interest = scheduled.interest();
        BigDecimal fee = scheduled.fee();
        BigDecimal accrued = interest.add(fee);
        if (accrued.signum() <= 0) {
            throw new KurPostingRejectedException("nothing_to_accrue",
                    "installment #" + installment.getInstallmentNumber()
                            + " has no interest or fee to accrue");
        }

        String key = idempotencyKey("ACCR", loan.getId(), sourceRef);
        Map<String, BigDecimal> components = Map.of(
                "interest", interest, "fee", fee, "accrued_amount", accrued);
        KurPostingResult replay = replayIfPosted("ACCRUAL", loan.getId(), key, valueDate, accrued, components);
        if (replay != null) {
            return replay;
        }

        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        variables.put("interest", interest);
        variables.put("fee", fee);
        variables.put("accrued_amount", accrued);

        PostingWindow window = resolveWindow(valueDate, command.forcePriorPeriodIfClosed());
        Transaction saved = postJournal(template, window, accrued, variables,
                "KUR month-end accrual #" + installment.getInstallmentNumber(),
                sourceRef, "KUR_ACCRUAL", installment.getId(), key, List.of());

        KurPostingResult result = new KurPostingResult("ACCRUAL", loan.getId(), saved.getId(),
                saved.getTransactionNumber(), valueDate, window.postingDate(), window.priorPeriod(),
                true, accrued, Map.copyOf(components), window.note());
        audit(AuditEventType.KUR_POSTING_ACCRUAL,
                "KUR accrual posted: loanId=" + loan.getId()
                        + ", installmentId=" + installment.getId()
                        + ", transactionId=" + saved.getId()
                        + ", valueDate=" + valueDate + ", postingDate=" + window.postingDate()
                        + ", interest=" + moneyString(interest) + ", fee=" + moneyString(fee)
                        + ", sourceRef=" + sourceRef);
        return result;
    }

    // ------------------------------------------------------------------
    // Month-end current/non-current reclass (K-3)
    // ------------------------------------------------------------------

    @Transactional
    public KurPostingResult postMonthEndReclass(KurPostingCommand.ReclassCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        KurLoan loan = requireLoan(command.loanId());
        requirePostingStructure(loan);
        String sourceRef = requireText(command.sourceRef(), "sourceRef");
        JournalTemplate template = requireActiveTemplate(TEMPLATE_RECLASS);
        LocalDate asOf = command.asOfDate() == null ? LocalDate.now() : command.asOfDate();

        List<KurInstallment> installments = installmentRepository
                .findByLoanIdOrderByInstallmentNumberAsc(loan.getId());
        BigDecimal remaining = installments.stream()
                .filter(row -> row.getDueDate() != null && row.getDueDate().isAfter(asOf))
                .map(this::principalOf)
                .reduce(KurMoneyMath.ZERO, BigDecimal::add);
        if (remaining.signum() == 0) {
            remaining = KurMoneyMath.nonNullMoney(loan.getPrincipalOriginal());
        }
        List<BigDecimal> upcoming = installments.stream()
                .filter(row -> row.getDueDate() != null
                        && row.getDueDate().isAfter(asOf)
                        && !row.getDueDate().isAfter(asOf.plusMonths(12)))
                .sorted(Comparator.comparing(KurInstallment::getDueDate))
                .map(this::principalOf)
                .toList();

        BigDecimal currentGlBalance = glOutstanding(ACCOUNT_SHORT_TERM, asOf);
        KurMoneyMath.ReclassResult reclass = KurMoneyMath.calculateReclass(remaining, upcoming, currentGlBalance);
        BigDecimal delta = reclass.delta();
        if (delta.signum() == 0) {
            return new KurPostingResult("RECLASS", loan.getId(), null, null, asOf, asOf, false,
                    false, KurMoneyMath.ZERO, Map.of("current", currentGlBalance),
                    "no reclass needed: current GL balance already equals the 12-month horizon");
        }

        String key = idempotencyKey("RECL", loan.getId(), sourceRef);
        Map<String, BigDecimal> components = Map.of(
                "delta", delta.abs(),
                "current_required", reclass.currentRequired(),
                "non_current_required", reclass.nonCurrentRequired());
        KurPostingResult replay = replayIfPosted("RECLASS", loan.getId(), key, asOf, delta.abs(), components);
        if (replay != null) {
            return replay;
        }

        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        variables.put("amount", delta.abs());

        PostingWindow window = resolveWindow(asOf, command.forcePriorPeriodIfClosed());
        Transaction saved = postJournal(template, window, delta.abs(), variables,
                "KUR current/non-current reclass as of " + asOf,
                sourceRef, "KUR_RECLASS", loan.getId(), key, List.of(),
                delta.signum() < 0 ? Direction.REVERSE : Direction.FORWARD);

        KurPostingResult result = new KurPostingResult("RECLASS", loan.getId(), saved.getId(),
                saved.getTransactionNumber(), asOf, window.postingDate(), window.priorPeriod(),
                true, delta.abs(), Map.copyOf(components), window.note());
        audit(AuditEventType.KUR_POSTING_RECLASS,
                "KUR reclass posted: loanId=" + loan.getId()
                        + ", transactionId=" + saved.getId()
                        + ", asOf=" + asOf + ", postingDate=" + window.postingDate()
                        + ", delta=" + moneyString(delta)
                        + ", currentRequired=" + moneyString(reclass.currentRequired())
                        + ", nonCurrentRequired=" + moneyString(reclass.nonCurrentRequired())
                        + ", sourceRef=" + sourceRef);
        return result;
    }

    // ------------------------------------------------------------------
    // Paired reversal: tracker row + ledger journal
    // ------------------------------------------------------------------

    public record PairedReversalOutcome(
            boolean paymentReversed,
            boolean journalVoided,
            UUID transactionId,
            String note
    ) {}

    @Transactional
    public PairedReversalOutcome reversePaymentPaired(KurPostingCommand.PairedReversalCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        requireText(command.reason(), "reason");
        requireText(command.operator(), "operator");
        KurPayment payment = paymentRepository.findById(command.paymentId())
                .orElseThrow(() -> new KurPostingRejectedException("payment_not_found",
                        "Unknown payment: " + command.paymentId()));
        UUID loanId = payment.getInstallment().getLoan().getId();
        if (command.loanId() != null && !command.loanId().equals(loanId)) {
            throw new KurPostingRejectedException("payment_not_found",
                    "Payment " + command.paymentId() + " does not belong to loan " + command.loanId());
        }

        boolean alreadyReversed = payment.isReversed();
        KurPayment reversed = alreadyReversed
                ? payment
                : kurLoanService.reversePayment(loanId, payment.getId(), command.reason().trim());

        UUID transactionId = reversed.getJournalTransactionId();
        boolean voided = false;
        if (transactionId != null) {
            Transaction journal = transactionRepository.findById(transactionId).orElse(null);
            if (journal != null && journal.getStatus() == TransactionStatus.POSTED) {
                transactionService.voidTransaction(transactionId, VoidReason.INPUT_ERROR,
                        command.reason().trim(), command.operator().trim());
                voided = true;
            }
        }

        audit(AuditEventType.KUR_PAIRED_REVERSAL,
                "KUR paired reversal: loanId=" + loanId
                        + ", paymentId=" + reversed.getId()
                        + ", transactionId=" + transactionId
                        + ", trackerReversed=" + true
                        + ", journalVoided=" + voided
                        + ", newlyApplied=" + !alreadyReversed
                        + ", reason=" + reversed.getReversalReason());
        String note = transactionId == null
                ? "tracker reversal only: the payment was never posted to the ledger"
                : voided ? "tracker reversal paired with ledger reversal" : "tracker reversal only: journal was already void";
        return new PairedReversalOutcome(true, voided, transactionId, note);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    record PostingWindow(LocalDate valueDate, LocalDate postingDate, boolean priorPeriod, String note) {}

    private enum Direction { FORWARD, REVERSE }

    private record ExtraLine(ChartOfAccount account, JournalPosition position,
                             BigDecimal amount, String description) {}

    private record ScheduledSplit(BigDecimal principal, BigDecimal interest, BigDecimal fee,
                                  boolean estimated) {}

    private void requireEnabled() {
        if (!postingEnabled) {
            throw new KurPostingRejectedException("posting_disabled",
                    "KUR accounting posting is staged and disabled; set "
                            + PROPERTY_ENABLED + "=true only after the founder gates clear");
        }
    }

    private KurLoan requireLoan(UUID loanId) {
        Objects.requireNonNull(loanId, "loanId");
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new KurPostingRejectedException("loan_not_found",
                        "Unknown KUR loan: " + loanId));
    }

    private void requirePostingStructure(KurLoan loan) {
        if (loan.getLegalBorrowerStructure() == null
                || !loan.getLegalBorrowerStructure().isPostingAllowed()) {
            throw new KurPostingRejectedException("legal_structure_blocks_posting",
                    "Loan " + loan.getId() + " is " + loan.getLegalBorrowerStructure()
                            + "; GL posting is permitted only for STRUCTURE_A");
        }
    }

    private KurInstallment requireInstallment(UUID loanId, Integer number) {
        Objects.requireNonNull(number, "installmentNumber");
        return installmentRepository.findByLoanIdAndInstallmentNumber(loanId, number)
                .orElseThrow(() -> new KurPostingRejectedException("installment_not_found",
                        "Unknown installment #" + number + " on loan " + loanId));
    }

    private JournalTemplate requireActiveTemplate(String name) {
        JournalTemplate template = templateRepository
                .findByTemplateNameAndIsCurrentVersionTrue(name)
                .orElseThrow(() -> new KurPostingRejectedException("template_missing",
                        "KUR journal template missing: " + name));
        if (!Boolean.TRUE.equals(template.getActive())) {
            throw new KurPostingRejectedException("template_staged",
                    "KUR journal template is staged (active=false): " + name);
        }
        return template;
    }

    private ChartOfAccount requireAccount(String code) {
        return chartOfAccountRepository.findByAccountCode(code)
                .orElseThrow(() -> new KurPostingRejectedException("account_missing",
                        "Chart-of-account entry missing: " + code));
    }

    private PostingWindow resolveWindow(LocalDate valueDate, boolean allowPriorPeriod) {
        var period = fiscalPeriodService.findByDate(valueDate);
        if (period.isEmpty() || period.get().getStatus() == FiscalPeriodStatus.OPEN) {
            return new PostingWindow(valueDate, valueDate, false, null);
        }
        FiscalPeriodStatus status = period.get().getStatus();
        if (!allowPriorPeriod) {
            throw new KurPostingRejectedException("period_closed",
                    "Value date " + valueDate + " is in the " + status
                            + " period " + period.get().getPeriodDisplayName()
                            + "; close-of-period posting needs forcePriorPeriodIfClosed");
        }
        LocalDate today = LocalDate.now();
        LocalDate openDate = fiscalPeriodService.findOpenPeriods().stream()
                .filter(open -> open.getYear() != null && open.getMonth() != null)
                .filter(open -> !periodStart(open).isAfter(today))
                .map(open -> {
                    LocalDate start = periodStart(open);
                    LocalDate end = periodEnd(open);
                    return today.isAfter(end) ? end : today;
                })
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new KurPostingRejectedException("period_closed",
                        "Value date " + valueDate + " is closed and no open period is available"));
        return new PostingWindow(valueDate, openDate, true,
                "prior-period posting: value date " + valueDate
                        + " closed (" + status + "), posted into open period ending " + openDate);
    }

    private static LocalDate periodStart(com.artivisi.accountingfinance.entity.FiscalPeriod period) {
        return LocalDate.of(period.getYear(), period.getMonth(), 1);
    }

    private static LocalDate periodEnd(com.artivisi.accountingfinance.entity.FiscalPeriod period) {
        return periodStart(period).plusMonths(1).minusDays(1);
    }

    private Transaction postJournal(JournalTemplate template, PostingWindow window, BigDecimal amount,
                                    Map<String, BigDecimal> variables, String description,
                                    String sourceRef, String sourceDocumentType, UUID sourceDocumentId,
                                    String idempotencyKey, List<ExtraLine> extraLines) {
        return postJournal(template, window, amount, variables, description, sourceRef,
                sourceDocumentType, sourceDocumentId, idempotencyKey, extraLines, Direction.FORWARD);
    }

    private Transaction postJournal(JournalTemplate template, PostingWindow window, BigDecimal amount,
                                    Map<String, BigDecimal> variables, String description,
                                    String sourceRef, String sourceDocumentType, UUID sourceDocumentId,
                                    String idempotencyKey, List<ExtraLine> extraLines,
                                    Direction direction) {
        Transaction transaction = new Transaction();
        transaction.setTransactionDate(window.postingDate());
        transaction.setDescription(description);
        transaction.setReferenceNumber(sourceRef);
        transaction.setJournalTemplate(template);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setSourceDocumentType(sourceDocumentType);
        transaction.setSourceDocumentId(sourceDocumentId);
        Map<String, BigDecimal> storedVariables = new LinkedHashMap<>(variables);
        if (window.priorPeriod()) {
            transaction.setNotes(window.note());
        }

        FormulaContext formulaContext = FormulaContext.of(amount, storedVariables);
        List<JournalEntry> entries = buildEntries(template, formulaContext, extraLines, direction);
        Transaction draft = journalEntryService.create(transaction, entries);
        Transaction posted = transactionService.post(draft.getId(), CurrentUser.nameOrSystem(),
                formulaContext);
        log.debug("KUR posting {} posted as {}", description, posted.getTransactionNumber());
        return posted;
    }

    private List<JournalEntry> buildEntries(JournalTemplate template, FormulaContext context,
                                            List<ExtraLine> extraLines, Direction direction) {
        List<JournalEntry> entries = new ArrayList<>();
        for (JournalTemplateLine line : template.getLines()) {
            ChartOfAccount account = line.getAccount();
            if (account == null) {
                throw new KurPostingRejectedException("account_missing",
                        "Template line has no account: " + line.getAccountHint());
            }
            BigDecimal value = formulaEvaluator.evaluate(line.getFormula(), context);
            BigDecimal entryAmount = KurMoneyMath.money(value == null ? BigDecimal.ZERO : value.abs());
            if (entryAmount.signum() == 0) {
                continue;
            }
            JournalPosition position = line.getPosition();
            if (direction == Direction.REVERSE) {
                position = position == JournalPosition.DEBIT ? JournalPosition.CREDIT : JournalPosition.DEBIT;
            }
            entries.add(entry(account, position, entryAmount, line.getDescription()));
        }
        for (ExtraLine extra : extraLines) {
            entries.add(entry(extra.account(), extra.position(), extra.amount(), extra.description()));
        }
        if (entries.size() < 2) {
            throw new KurPostingRejectedException("empty_journal",
                    "Template " + template.getTemplateName() + " produced no balanced journal");
        }
        BigDecimal debit = entries.stream().map(JournalEntry::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entries.stream().map(JournalEntry::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debit.compareTo(credit) != 0) {
            throw new KurPostingRejectedException("unbalanced_journal",
                    "Template " + template.getTemplateName() + " produced debit " + debit
                            + " != credit " + credit + "; the posting is refused");
        }
        return entries;
    }

    private JournalEntry entry(ChartOfAccount account, JournalPosition position,
                               BigDecimal amount, String description) {
        JournalEntry entry = new JournalEntry();
        entry.setAccount(account);
        if (position == JournalPosition.DEBIT) {
            entry.setDebitAmount(amount);
            entry.setCreditAmount(KurMoneyMath.ZERO);
        } else {
            entry.setDebitAmount(KurMoneyMath.ZERO);
            entry.setCreditAmount(amount);
        }
        return entry;
    }

    private KurPostingResult replayIfPosted(String kind, UUID loanId, String key, LocalDate valueDate,
                                            BigDecimal amount, Map<String, BigDecimal> components) {
        return transactionRepository.findByIdempotencyKeyWithJournalEntries(key)
                .map(existing -> {
                    if (existing.getAmount() != null && existing.getAmount().compareTo(amount) != 0) {
                        throw new KurPostingRejectedException("conflicting_replay",
                                "source reference already posted as " + existing.getTransactionNumber()
                                        + " with amount " + moneyString(existing.getAmount())
                                        + ", the request carries " + moneyString(amount)
                                        + "; a changed value under the same reference is refused");
                    }
                    if (existing.getTransactionDate() != null && valueDate != null
                            && existing.getTransactionDate().isBefore(valueDate)) {
                        throw new KurPostingRejectedException("conflicting_replay",
                                "source reference already posted as " + existing.getTransactionNumber()
                                        + " dated " + existing.getTransactionDate()
                                        + ", the request carries a later value date " + valueDate);
                    }
                    return KurPostingResult.replay(kind, loanId, existing.getId(),
                            existing.getTransactionNumber(), valueDate, existing.getTransactionDate(),
                            existing.getNotes() != null && existing.getNotes().startsWith("prior-period"),
                            amount, components);
                })
                .orElse(null);
    }

    private void linkPaymentJournal(KurInstallment installment, String sourceRef, UUID transactionId) {
        paymentRepository.findByInstallmentIdAndSourceRef(installment.getId(), sourceRef.trim())
                .ifPresent(payment -> {
                    payment.setJournalTransactionId(transactionId);
                    paymentRepository.save(payment);
                });
    }

    private ScheduledSplit scheduledSplit(KurInstallment installment) {
        KurMoneyMath.ScheduledComponents components = KurMoneyMath.scheduleComponents(
                installment.getAmount(), installment.getPrincipal(), installment.getInterest(),
                installment.getFee());
        return new ScheduledSplit(components.principal(), components.interest(),
                components.fee(), components.estimated());
    }

    private BigDecimal principalOf(KurInstallment installment) {
        if (installment.getPrincipal() != null) {
            return KurMoneyMath.money(installment.getPrincipal());
        }
        return scheduledSplit(installment).principal();
    }

    private BigDecimal currentPortionWithinTwelveMonths(KurLoan loan, LocalDate valueDate, BigDecimal gross) {
        LocalDate horizon = valueDate.plusMonths(12);
        List<KurInstallment> rows = installmentRepository
                .findByLoanIdOrderByInstallmentNumberAsc(loan.getId());
        if (rows.isEmpty()) {
            return KurMoneyMath.money(gross);
        }
        BigDecimal current = rows.stream()
                .filter(row -> row.getDueDate() != null && !row.getDueDate().isAfter(horizon))
                .map(this::principalOf)
                .reduce(KurMoneyMath.ZERO, BigDecimal::add);
        if (current.compareTo(gross) > 0) {
            return KurMoneyMath.money(gross);
        }
        return KurMoneyMath.money(current);
    }

    private BigDecimal glOutstanding(String accountCode, LocalDate asOf) {
        ChartOfAccount account = requireAccount(accountCode);
        BigDecimal debit = journalEntryRepository.sumDebitBeforeDate(account.getId(), asOf.plusDays(1));
        BigDecimal credit = journalEntryRepository.sumCreditBeforeDate(account.getId(), asOf.plusDays(1));
        return KurMoneyMath.money(KurMoneyMath.nonNullMoney(credit)
                .subtract(KurMoneyMath.nonNullMoney(debit)));
    }

    private String idempotencyKey(String kind, UUID loanId, String sourceRef) {
        String ref = sourceRef.trim();
        if (ref.length() > SOURCE_REF_MAX) {
            ref = ref.substring(0, SOURCE_REF_MAX);
        }
        String key = "KURPOST-" + kind + ":" + loanId + ":" + ref;
        if (key.length() > IDEMPOTENCY_KEY_MAX) {
            key = key.substring(0, IDEMPOTENCY_KEY_MAX);
        }
        return key;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new KurPostingRejectedException("missing_evidence",
                    field + " is required for a KUR accounting posting");
        }
        return value.trim();
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new KurPostingRejectedException("invalid_amount",
                    field + " must be a positive amount");
        }
    }

    private static LocalDate monthEnd(LocalDate date) {
        return date.withDayOfMonth(date.lengthOfMonth());
    }

    private static String moneyString(BigDecimal value) {
        return value == null ? "null" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private void audit(AuditEventType eventType, String details) {
        securityAuditService.log(eventType, details);
    }
}
