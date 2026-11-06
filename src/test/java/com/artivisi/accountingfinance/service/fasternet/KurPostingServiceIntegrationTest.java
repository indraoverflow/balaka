package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.dto.fasternet.KurPostingCommand;
import com.artivisi.accountingfinance.dto.fasternet.KurPostingResult;
import com.artivisi.accountingfinance.dto.fasternet.KurReconciliationReport;
import com.artivisi.accountingfinance.entity.FiscalPeriod;
import com.artivisi.accountingfinance.entity.JournalEntry;
import com.artivisi.accountingfinance.entity.JournalTemplate;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.KurPayment;
import com.artivisi.accountingfinance.enums.FiscalPeriodStatus;
import com.artivisi.accountingfinance.enums.KurLegalBorrowerStructure;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.enums.TransactionStatus;
import com.artivisi.accountingfinance.exception.KurPostingRejectedException;
import com.artivisi.accountingfinance.repository.FiscalPeriodRepository;
import com.artivisi.accountingfinance.repository.JournalEntryRepository;
import com.artivisi.accountingfinance.repository.JournalTemplateRepository;
import com.artivisi.accountingfinance.repository.KurInstallmentRepository;
import com.artivisi.accountingfinance.repository.KurPaymentRepository;
import com.artivisi.accountingfinance.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FAS-139 / FAS-97 §14: the staged KUR accounting posting engine.
 *
 * <p>The engine posts from the V014 templates, so the fixtures activate those templates
 * inside the test transaction only. Nothing here flips a production activation flag:
 * the shipped state stays {@code active = false} plus
 * {@code fasternet.kur.accounting-posting.enabled = false}.
 */
@SpringBootTest(properties = "fasternet.kur.accounting-posting.enabled=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("KUR accounting posting engine (FAS-139 / FAS-97)")
class KurPostingServiceIntegrationTest {

    private static final LocalDate DISBURSEMENT = LocalDate.of(2026, 10, 21);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 21);
    private static final BigDecimal PRINCIPAL = new BigDecimal("99270000.00");
    private static final BigDecimal INSTALLMENT = new BigDecimal("3050000.00");
    private static final BigDecimal INSTALLMENT_PRINCIPAL = new BigDecimal("2757500.00");
    private static final BigDecimal INSTALLMENT_INTEREST = new BigDecimal("292500.00");

    @Autowired
    private KurPostingService postingService;

    @Autowired
    private KurReconciliationService reconciliationService;

    @Autowired
    private KurLoanService loanService;

    @Autowired
    private JournalTemplateRepository templateRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private KurInstallmentRepository installmentRepository;

    @Autowired
    private KurPaymentRepository paymentRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private FiscalPeriodRepository fiscalPeriodRepository;

    @BeforeEach
    void activateStagedTemplates() {
        for (String name : List.of(
                KurPostingService.TEMPLATE_DISBURSEMENT,
                KurPostingService.TEMPLATE_INSTALLMENT,
                KurPostingService.TEMPLATE_ACCRUAL,
                KurPostingService.TEMPLATE_RECLASS)) {
            JournalTemplate template = templateRepository
                    .findByTemplateNameAndIsCurrentVersionTrue(name).orElseThrow();
            template.setActive(true);
            templateRepository.save(template);
        }
    }

    // ------------------------------------------------------------------
    // K-1 disbursement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("K-1: disbursement credits the 12-month principal to 2.1.30 and the rest to 2.2.01")
    void disbursementSplitsCurrentAndNonCurrent() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);

        KurPostingResult result = postingService.postDisbursement(new KurPostingCommand.DisbursementCommand(
                loan.getId(), DISBURSEMENT, PRINCIPAL, BigDecimal.ZERO,
                "BRI-TRF-DISB-1", "disb-2026-10-21", false));

        assertThat(result.created()).isTrue();
        assertThat(result.kind()).isEqualTo("DISBURSEMENT");
        assertThat(result.valueDate()).isEqualTo(DISBURSEMENT);

        Map<String, BigDecimal> debits = debits(result.transactionId());
        Map<String, BigDecimal> credits = credits(result.transactionId());
        assertThat(debits.get("1.1.10")).isEqualByComparingTo("99270000.00");
        assertThat(credits.get("2.1.30")).isEqualByComparingTo("2757500.00");
        assertThat(credits.get("2.2.01")).isEqualByComparingTo("96512500.00");
        assertThat(transactionRepository.findById(result.transactionId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    @DisplayName("K-1: a withheld fee splits the cash receipt from the liability")
    void disbursementWithHeldFee() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);

        KurPostingResult result = postingService.postDisbursement(new KurPostingCommand.DisbursementCommand(
                loan.getId(), DISBURSEMENT, PRINCIPAL, new BigDecimal("500000.00"),
                "BRI-TRF-DISB-1", "disb-fee", false));

        Map<String, BigDecimal> debits = debits(result.transactionId());
        assertThat(debits.get("1.1.10")).isEqualByComparingTo("98770000.00");
        assertThat(debits.get("5.2.04")).isEqualByComparingTo("500000.00");
        assertThat(debits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(credits(result.transactionId()).values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    // ------------------------------------------------------------------
    // K-2 installment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("K-2: an installment posts fee/interest/principal and the cash paid, and balances")
    void installmentSplitsComponents() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);

        KurPostingResult result = postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, INSTALLMENT, BigDecimal.ZERO,
                "BRI-TRF-1", "trf-2026-11-21", false));

        assertThat(result.created()).isTrue();
        Map<String, BigDecimal> debits = debits(result.transactionId());
        Map<String, BigDecimal> credits = credits(result.transactionId());
        assertThat(debits.get("2.1.30")).isEqualByComparingTo("2757500.00");
        assertThat(debits.get("5.2.03")).isEqualByComparingTo("292500.00");
        assertThat(debits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("3050000.00");
        assertThat(credits.get("1.1.10")).isEqualByComparingTo("3050000.00");
        assertThat(result.components().get("overpayment")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("K-2: an overpayment posts to the 1.1.11 buffer, never to principal")
    void overpaymentGoesToBufferAccount() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);

        KurPostingResult result = postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, new BigDecimal("4000000.00"), BigDecimal.ZERO,
                "BRI-TRF-2", "trf-over", false));

        Map<String, BigDecimal> debits = debits(result.transactionId());
        assertThat(debits.get("2.1.30")).isEqualByComparingTo("2757500.00");
        assertThat(debits.get("1.1.11")).isEqualByComparingTo("950000.00");
        assertThat(result.components().get("overpayment")).isEqualByComparingTo("950000.00");
        assertThat(credits(result.transactionId()).get("1.1.10")).isEqualByComparingTo("4000000.00");
    }

    @Test
    @DisplayName("K-2: a partial payment satisfies interest before reducing principal")
    void partialPaymentSatisfiesInterestFirst() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);

        KurPostingResult result = postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, new BigDecimal("500000.00"), BigDecimal.ZERO,
                "BRI-TRF-3", "trf-partial", false));

        assertThat(result.components().get("interest")).isEqualByComparingTo("292500.00");
        assertThat(result.components().get("principal")).isEqualByComparingTo("207500.00");
        assertThat(result.components().get("overpayment")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("K-2: an installment without evidence is refused before it reaches the ledger")
    void postingRequiresEvidence() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);
        long journalsBefore = transactionRepository.count();

        assertThatThrownBy(() -> postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, INSTALLMENT, BigDecimal.ZERO,
                "   ", "trf-no-evidence", false)))
                .isInstanceOfSatisfying(KurPostingRejectedException.class,
                        rejected -> assertThat(rejected.getReason()).isEqualTo("missing_evidence"));

        assertThat(transactionRepository.count()).isEqualTo(journalsBefore);
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same source reference replays instead of posting a second journal")
    void replayDoesNotDuplicate() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);

        KurPostingCommand.InstallmentCommand command = new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, INSTALLMENT, BigDecimal.ZERO,
                "BRI-TRF-1", "trf-idem", false);
        KurPostingResult first = postingService.postInstallment(command);
        KurPostingResult replay = postingService.postInstallment(command);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(journalEntryRepository.findByTransactionIdWithAccount(first.transactionId())).hasSize(3);
    }

    @Test
    @DisplayName("a changed amount under the same source reference is rejected, not silently replayed")
    void changedAmountUnderSameSourceReferenceIsRejected() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);
        importRow(loan.getId(), 2, FIRST_DUE.plusMonths(1));

        postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, INSTALLMENT, BigDecimal.ZERO,
                "BRI-TRF-1", "trf-conflict", false));

        assertThatThrownBy(() -> postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 2, FIRST_DUE, new BigDecimal("4000000.00"), BigDecimal.ZERO,
                "BRI-TRF-2", "trf-conflict", false)))
                .isInstanceOfSatisfying(KurPostingRejectedException.class,
                        rejected -> assertThat(rejected.getReason()).isEqualTo("conflicting_replay"));
    }

    // ------------------------------------------------------------------
    // Period gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a value date in a closed period is rejected")
    void closedPeriodIsRejected() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);
        LocalDate closedValueDate = LocalDate.of(2026, 1, 21);
        closePeriod(closedValueDate);

        assertThatThrownBy(() -> postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, closedValueDate, INSTALLMENT, BigDecimal.ZERO,
                "BRI-TRF-1", "trf-closed", false)))
                .isInstanceOfSatisfying(KurPostingRejectedException.class,
                        rejected -> assertThat(rejected.getReason()).isEqualTo("period_closed"));
    }

    @Test
    @DisplayName("a late-posting accepted by the caller lands in the current open period with the prior-period flag")
    void latePostingCarriesPriorPeriodFlag() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);
        LocalDate closedValueDate = LocalDate.of(2026, 1, 21);
        closePeriod(closedValueDate);
        FiscalPeriod open = fiscalPeriodRepository
                .findByYearAndMonth(LocalDate.now().getYear(), LocalDate.now().getMonthValue())
                .orElseGet(() -> {
                    FiscalPeriod period = new FiscalPeriod();
                    period.setYear(LocalDate.now().getYear());
                    period.setMonth(LocalDate.now().getMonthValue());
                    period.setStatus(FiscalPeriodStatus.OPEN);
                    return fiscalPeriodRepository.save(period);
                });
        assertThat(open.getStatus()).isEqualTo(FiscalPeriodStatus.OPEN);

        KurPostingResult result = postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, closedValueDate, INSTALLMENT, BigDecimal.ZERO,
                "BRI-TRF-1", "trf-late", true));

        assertThat(result.priorPeriod()).isTrue();
        assertThat(result.valueDate()).isEqualTo(closedValueDate);
        assertThat(result.postingDate()).isEqualTo(LocalDate.now());
        var transaction = transactionRepository.findById(result.transactionId()).orElseThrow();
        assertThat(transaction.getTransactionDate()).isEqualTo(LocalDate.now());
        assertThat(transaction.getNotes()).startsWith("prior-period posting");
    }

    // ------------------------------------------------------------------
    // Paired reversal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reversing a payment reverses the tracker row and voids exactly its journal")
    void pairedReversalVoidsJournalAndRestoresTracker() {
        KurLoan loan = createLoan();
        KurInstallment installment = importRow(loan.getId(), 1, FIRST_DUE);
        KurPayment payment = loanService.recordPayment(loan.getId(), installment.getId(),
                new KurLoanService.PaymentCommand(FIRST_DUE, INSTALLMENT, "BRI-TRF-1",
                        "BRI-TRF-1", "trf-reverse"));

        KurPostingResult posted = postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, INSTALLMENT, BigDecimal.ZERO,
                "BRI-TRF-1", "trf-reverse", false));
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getJournalTransactionId())
                .isEqualTo(posted.transactionId());

        KurPostingService.PairedReversalOutcome outcome = postingService.reversePaymentPaired(
                new KurPostingCommand.PairedReversalCommand(loan.getId(), payment.getId(),
                        "salah transfer", "finance-controller"));

        assertThat(outcome.paymentReversed()).isTrue();
        assertThat(outcome.journalVoided()).isTrue();
        assertThat(outcome.transactionId()).isEqualTo(posted.transactionId());
        assertThat(transactionRepository.findById(posted.transactionId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.VOID);
        KurPayment reversed = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reversed.isReversed()).isTrue();
        assertThat(reversed.getReversalReason()).isEqualTo("salah transfer");
        assertThat(installmentRepository.findById(installment.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0.00");

        // A second reversal is a no-op, not a second write.
        KurPostingService.PairedReversalOutcome again = postingService.reversePaymentPaired(
                new KurPostingCommand.PairedReversalCommand(loan.getId(), payment.getId(),
                        "salah transfer", "finance-controller"));
        assertThat(again.journalVoided()).isFalse();
    }

    // ------------------------------------------------------------------
    // K-3 reclass
    // ------------------------------------------------------------------

    @Test
    @DisplayName("K-3: a paid installment is topped up back into the 12-month bucket at month end")
    void monthEndReclassTopsUpCurrentPortion() {
        KurLoan loan = createLoan();
        for (int number = 1; number <= 36; number++) {
            importRow(loan.getId(), number, FIRST_DUE.plusMonths(number - 1L));
        }
        postingService.postDisbursement(new KurPostingCommand.DisbursementCommand(
                loan.getId(), DISBURSEMENT, PRINCIPAL, BigDecimal.ZERO,
                "BRI-TRF-DISB-1", "disb-1", false));
        postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, INSTALLMENT, BigDecimal.ZERO,
                "BRI-TRF-1", "trf-1", false));

        KurPostingResult reclass = postingService.postMonthEndReclass(new KurPostingCommand.ReclassCommand(
                loan.getId(), FIRST_DUE.plusDays(5), "reclass-2026-11", false));

        assertThat(reclass.created()).isTrue();
        assertThat(reclass.amount()).isEqualByComparingTo("2757500.00");
        Map<String, BigDecimal> debits = debits(reclass.transactionId());
        Map<String, BigDecimal> credits = credits(reclass.transactionId());
        assertThat(debits.get("2.2.01")).isEqualByComparingTo("2757500.00");
        assertThat(credits.get("2.1.30")).isEqualByComparingTo("2757500.00");
        assertThat(reclass.components().get("current_required")).isEqualByComparingTo("33090000.00");
    }

    @Test
    @DisplayName("K-3: when the whole remaining principal falls inside 12 months, non-current is zero")
    void nearMaturityNonCurrentIsZero() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);
        importRow(loan.getId(), 2, FIRST_DUE.plusMonths(1));

        KurPostingResult reclass = postingService.postMonthEndReclass(new KurPostingCommand.ReclassCommand(
                loan.getId(), FIRST_DUE.minusDays(10), "reclass-near", false));

        assertThat(reclass.components().get("non_current_required")).isEqualByComparingTo("0.00");
        assertThat(reclass.components().get("current_required")).isEqualByComparingTo("5515000.00");
        assertThat(reclass.amount()).isEqualByComparingTo("5515000.00");
    }

    // ------------------------------------------------------------------
    // K-2 §6.7 accrual
    // ------------------------------------------------------------------

    @Test
    @DisplayName("K-2 §6.7: the month-end accrual credits the accrued interest and fee liability")
    void monthEndAccrual() {
        KurLoan loan = createLoan();
        importRow(loan.getId(), 1, FIRST_DUE);

        KurPostingResult result = postingService.postAccrual(new KurPostingCommand.AccrualCommand(
                loan.getId(), 1, null, "accrual-2026-11", false));

        assertThat(result.amount()).isEqualByComparingTo("292500.00");
        Map<String, BigDecimal> debits = debits(result.transactionId());
        assertThat(debits.get("5.2.03")).isEqualByComparingTo("292500.00");
        assertThat(credits(result.transactionId()).get("2.1.31")).isEqualByComparingTo("292500.00");
    }

    // ------------------------------------------------------------------
    // Legal-borrower structure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("structure D (default) leaves the loan on the tracker and blocks GL posting")
    void structureDBlocksPosting() {
        KurLoan loan = createLoanWithDefaultStructure();
        assertThat(loan.getLegalBorrowerStructure()).isEqualTo(KurLegalBorrowerStructure.STRUCTURE_D);

        assertThatThrownBy(() -> postingService.postDisbursement(new KurPostingCommand.DisbursementCommand(
                loan.getId(), DISBURSEMENT, PRINCIPAL, BigDecimal.ZERO,
                "BRI-TRF-DISB-1", "disb-no-mou", false)))
                .isInstanceOfSatisfying(KurPostingRejectedException.class,
                        rejected -> assertThat(rejected.getReason())
                                .isEqualTo("legal_structure_blocks_posting"));

        KurLoan structureA = loanService.setLegalBorrowerStructure(loan.getId(),
                KurLegalBorrowerStructure.STRUCTURE_A);
        assertThat(structureA.getLegalBorrowerStructure()).isEqualTo(KurLegalBorrowerStructure.STRUCTURE_A);
        assertThat(postingService.postDisbursement(new KurPostingCommand.DisbursementCommand(
                loan.getId(), DISBURSEMENT, PRINCIPAL, BigDecimal.ZERO,
                "BRI-TRF-DISB-1", "disb-no-mou", false)).created()).isTrue();
    }

    // ------------------------------------------------------------------
    // Lender reconciliation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reconciliation ties principal, cash and components on a clean fixture")
    void reconciliationIsCleanOnPostedFixture() {
        KurLoan loan = createLoan();
        for (int number = 1; number <= 36; number++) {
            importRow(loan.getId(), number, FIRST_DUE.plusMonths(number - 1L));
        }
        postingService.postDisbursement(new KurPostingCommand.DisbursementCommand(
                loan.getId(), DISBURSEMENT, PRINCIPAL, BigDecimal.ZERO, "TRF-D", "disb-1", false));
        loanService.recordPayment(loan.getId(), installmentRepository
                        .findByLoanIdAndInstallmentNumber(loan.getId(), 1).orElseThrow().getId(),
                new KurLoanService.PaymentCommand(FIRST_DUE, INSTALLMENT, "BRI-TRF-1",
                        "BRI-TRF-1", "trf-1"));
        postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, INSTALLMENT, BigDecimal.ZERO,
                "BRI-TRF-1", "trf-1", false));

        KurReconciliationReport report = reconciliationService.reconcile(loan.getId(), FIRST_DUE.plusDays(1));

        assertThat(report.unexplainedDifference()).isEqualByComparingTo("0.00");
        assertThat(report.cashVariance()).isEqualByComparingTo("0.00");
        assertThat(report.principalVariance()).isEqualByComparingTo("0.00");
        assertThat(report.scheduleDiscrepancies()).isEmpty();
        assertThat(report.status()).isEqualTo(KurReconciliationReport.ReconciliationStatus.AMBER);
        assertThat(report.summary()).contains("founder estimate");
    }

    @Test
    @DisplayName("reconciliation raises RED on an unexplained cash difference")
    void reconciliationRaisesRedOnUnexplainedCash() {
        KurLoan loan = createLoan();
        for (int number = 1; number <= 36; number++) {
            importRow(loan.getId(), number, FIRST_DUE.plusMonths(number - 1L));
        }
        postingService.postDisbursement(new KurPostingCommand.DisbursementCommand(
                loan.getId(), DISBURSEMENT, PRINCIPAL, BigDecimal.ZERO, "TRF-D", "disb-red", false));
        KurInstallment installment = installmentRepository
                .findByLoanIdAndInstallmentNumber(loan.getId(), 1).orElseThrow();
        loanService.recordPayment(loan.getId(), installment.getId(),
                new KurLoanService.PaymentCommand(FIRST_DUE, INSTALLMENT, "BRI-TRF-1",
                        "BRI-TRF-1", "trf-bank"));

        // Posted under a different reference and a smaller amount: 1,050,000 unexplained.
        postingService.postInstallment(new KurPostingCommand.InstallmentCommand(
                loan.getId(), 1, FIRST_DUE, new BigDecimal("2000000.00"), BigDecimal.ZERO,
                "BRI-TRF-1", "trf-ledger", false));

        KurReconciliationReport report = reconciliationService.reconcile(loan.getId(), FIRST_DUE);

        assertThat(report.cashVariance()).isEqualByComparingTo("-1050000.00");
        assertThat(report.unexplainedDifference()).isPositive();
        assertThat(report.status()).isEqualTo(KurReconciliationReport.ReconciliationStatus.RED);
    }

    @Test
    @DisplayName("reconciliation quarantines a recorded-but-unposted payment as a timing difference")
    void reconciliationQuarantinesTimingDifference() {
        KurLoan loan = createLoan();
        KurInstallment installment = importRow(loan.getId(), 1, FIRST_DUE);
        loanService.recordPayment(loan.getId(), installment.getId(),
                new KurLoanService.PaymentCommand(FIRST_DUE, INSTALLMENT, "BRI-TRF-1",
                        "BRI-TRF-1", "trf-timing"));

        KurReconciliationReport report = reconciliationService.reconcile(loan.getId(), FIRST_DUE.plusDays(1));

        assertThat(report.timingDifferences()).hasSize(1);
        assertThat(report.timingDifferences().getFirst().reference()).isEqualTo("trf-timing");
        assertThat(report.unexplainedDifference()).isEqualByComparingTo("0.00");
        assertThat(report.status()).isEqualTo(KurReconciliationReport.ReconciliationStatus.AMBER);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private KurLoan createLoanWithDefaultStructure() {
        return loanService.createLoan(new KurLoanService.CreateLoanCommand(
                "BRI", "KUR", null, "FASTERNET", "Indra",
                PRINCIPAL, DISBURSEMENT, 36, INSTALLMENT,
                "MONTHLY", 36, FIRST_DUE, 21, LocalDate.of(2029, 10, 21),
                BigDecimal.ZERO, null, null,
                KurLoanProvenance.FOUNDER_ESTIMATE, null));
    }

    private KurLoan createLoan() {
        KurLoan loan = loanService.createLoan(new KurLoanService.CreateLoanCommand(
                "BRI", "KUR", null, "FASTERNET", "Indra",
                PRINCIPAL, DISBURSEMENT, 36, INSTALLMENT,
                "MONTHLY", 36, FIRST_DUE, 21, LocalDate.of(2029, 10, 21),
                BigDecimal.ZERO, null, null, KurLoanProvenance.FOUNDER_ESTIMATE, null));
        return loanService.setLegalBorrowerStructure(loan.getId(), KurLegalBorrowerStructure.STRUCTURE_A);
    }

    private KurInstallment importRow(UUID loanId, int number, LocalDate dueDate) {
        return loanService.importScheduleRow(loanId, new KurLoanService.ScheduleRow(
                number, dueDate, INSTALLMENT, INSTALLMENT_PRINCIPAL, INSTALLMENT_INTEREST,
                BigDecimal.ZERO, KurScheduleSourceKind.LENDER_SCHEDULE, "bri-row-" + number));
    }

    private void closePeriod(LocalDate date) {
        FiscalPeriod period = fiscalPeriodRepository
                .findByYearAndMonth(date.getYear(), date.getMonthValue())
                .orElseGet(() -> {
                    FiscalPeriod created = new FiscalPeriod();
                    created.setYear(date.getYear());
                    created.setMonth(date.getMonthValue());
                    return fiscalPeriodRepository.save(created);
                });
        period.setStatus(FiscalPeriodStatus.MONTH_CLOSED);
        fiscalPeriodRepository.save(period);
    }

    private Map<String, BigDecimal> debits(UUID transactionId) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (JournalEntry entry : journalEntryRepository.findByTransactionIdWithAccount(transactionId)) {
            if (entry.getDebitAmount() != null && entry.getDebitAmount().signum() > 0) {
                totals.merge(entry.getAccount().getAccountCode(), entry.getDebitAmount(), BigDecimal::add);
            }
        }
        return totals;
    }

    private Map<String, BigDecimal> credits(UUID transactionId) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (JournalEntry entry : journalEntryRepository.findByTransactionIdWithAccount(transactionId)) {
            if (entry.getCreditAmount() != null && entry.getCreditAmount().signum() > 0) {
                totals.merge(entry.getAccount().getAccountCode(), entry.getCreditAmount(), BigDecimal::add);
            }
        }
        return totals;
    }
}
