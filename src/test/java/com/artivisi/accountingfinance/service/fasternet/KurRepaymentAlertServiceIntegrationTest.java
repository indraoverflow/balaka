package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.dto.fasternet.KurAlertResponse;
import com.artivisi.accountingfinance.entity.ChartOfAccount;
import com.artivisi.accountingfinance.entity.JournalEntry;
import com.artivisi.accountingfinance.entity.JournalTemplate;
import com.artivisi.accountingfinance.entity.KurAlertPolicy;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.Transaction;
import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.enums.TransactionStatus;
import com.artivisi.accountingfinance.repository.ChartOfAccountRepository;
import com.artivisi.accountingfinance.repository.JournalEntryRepository;
import com.artivisi.accountingfinance.repository.JournalTemplateRepository;
import com.artivisi.accountingfinance.repository.KurInstallmentRepository;
import com.artivisi.accountingfinance.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FAS-96: the next-installment KUR repayment alert must return a state, reason,
 * shortfall, due date and assumptions; must never show green under an
 * unconfigured policy; must never turn an earmarked reserve into a paid
 * installment; and must not render missing data as zero.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("KUR repayment alert (FAS-96)")
class KurRepaymentAlertServiceIntegrationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 24);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 21);
    private static final LocalDate OVERDUE_DUE = LocalDate.of(2026, 9, 21);
    private static final BigDecimal INSTALLMENT = new BigDecimal("2757500.00");

    @Autowired
    private KurLoanService loanService;

    @Autowired
    private KurAlertPolicyService policyService;

    @Autowired
    private KurRepaymentAlertService alertService;

    @Autowired
    private KurInstallmentRepository installmentRepository;

    @Autowired
    private ChartOfAccountRepository chartOfAccountRepository;

    @Autowired
    private JournalTemplateRepository journalTemplateRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Test
    @DisplayName("an unconfigured policy returns unconfigured, never green")
    void unconfiguredPolicyNeverGreen() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE, FIRST_DUE);
        schedule(loan, FIRST_DUE, KurScheduleSourceKind.LENDER_SCHEDULE, "row-1");
        reserve(loan, new BigDecimal("2757500.00"), "reserve-1");

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("unconfigured");
        assertThat(response.alert().severity()).isEqualTo("unknown");
        assertThat(response.alert().reason()).contains("unconfigured");
        assertThat(response.alert().dueDate()).isEqualTo("2026-11-21");
        assertThat(response.alert().daysRemaining()).isEqualTo(28);
        assertThat(response.alert().shortfall().amount()).isEqualTo("0.00");
        assertThat(response.alert().policy().status()).isEqualTo("unconfigured");
        assertThat(response.alert().policy().thresholds())
                .containsEntry("minimumCash", null)
                .containsEntry("targetReserve", null);
        assertThat(response.alert().assumptions())
                .anyMatch(line -> line.contains("threshold policy is unconfigured"));
        assertThat(response.missing())
                .extracting(KurAlertResponse.Missing::key)
                .contains("kur.alert.policy");
    }

    @Test
    @DisplayName("green when the approved plan is fully funded and cash covers the minimum")
    void greenWhenFullyFundedByApprovedPlan() {
        KurLoan loan = createLoan(KurLoanProvenance.VERIFIED, FIRST_DUE);
        schedule(loan, FIRST_DUE, KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1");
        reserve(loan, INSTALLMENT, "reserve-1");
        configure(loan, "0.00", "2757500.00", "0.00", "2000000.00", "2500000.00");
        postCash(new BigDecimal("1000000000.00"));

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("green");
        assertThat(response.alert().severity()).isEqualTo("ok");
        assertThat(response.alert().shortfall().amount()).isEqualTo("0.00");
        assertThat(response.alert().reserve().qualifying()).isEqualTo("2757500.00");
        assertThat(response.alert().cash().actual()).isNotNull();
        assertThat(response.alert().cash().minimumRequired()).isEqualTo("0.00");
        assertThat(response.alert().policy().status()).isEqualTo("configured");
        assertThat(response.alert().policy().approvalReference()).isEqualTo("FAS-96-TEST");
        assertThat(response.alert().obligation().scheduleStatus()).isEqualTo("verified");
        assertThat(response.alert().payment().status()).isEqualTo("unpaid");
        assertThat(response.alert().payment().countsAsPaid()).isFalse();
        assertThat(response.alert().invariant()).isEqualTo("a reserved amount is never a completed payment");
        assertThat(response.missing())
                .extracting(KurAlertResponse.Missing::key)
                .doesNotContain("kur.alert.schedule");
    }

    @Test
    @DisplayName("yellow when the reserve is behind the approved funding plan")
    void yellowWhenReserveBehindPlan() {
        KurLoan loan = createLoan(KurLoanProvenance.VERIFIED, FIRST_DUE);
        schedule(loan, FIRST_DUE, KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1");
        reserve(loan, new BigDecimal("1000000.00"), "reserve-1");
        configure(loan, "0.00", "2757500.00", "0.00", "10000000.00", "20000000.00");
        postCash(new BigDecimal("1000000000.00"));

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("yellow");
        assertThat(response.alert().severity()).isEqualTo("warning");
        assertThat(response.alert().shortfall().amount()).isEqualTo("1757500.00");
        assertThat(response.alert().reason()).contains("behind the approved funding plan");
    }

    @Test
    @DisplayName("orange when the gap reaches the approved orange cutoff")
    void orangeWhenGapReachesCutoff() {
        KurLoan loan = createLoan(KurLoanProvenance.VERIFIED, FIRST_DUE);
        schedule(loan, FIRST_DUE, KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1");
        reserve(loan, new BigDecimal("1000000.00"), "reserve-1");
        configure(loan, "0.00", "2757500.00", "0.00", "1500000.00", "2500000.00");
        postCash(new BigDecimal("1000000000.00"));

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("orange");
        assertThat(response.alert().severity()).isEqualTo("high");
        assertThat(response.alert().reason()).contains("orange cutoff");
    }

    @Test
    @DisplayName("orange when actual cash beyond the minimum cannot cover the gap")
    void orangeWhenCashCannotCoverGap() {
        BigDecimal largeInstallment = new BigDecimal("9000000000.00");
        KurLoan loan = createLoan(KurLoanProvenance.VERIFIED, FIRST_DUE, largeInstallment);
        schedule(loan, FIRST_DUE, largeInstallment, KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1");
        reserve(loan, new BigDecimal("1000000.00"), "reserve-1");
        configure(loan, "0.00", "9000000000.00", "0.00", "9500000000.00", "9700000000.00");

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("orange");
        assertThat(response.alert().reason()).contains("cannot cover the reserve gap");
    }

    @Test
    @DisplayName("red when the next installment is overdue")
    void redWhenOverdue() {
        KurLoan loan = createLoan(KurLoanProvenance.VERIFIED, OVERDUE_DUE);
        schedule(loan, OVERDUE_DUE, KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1");
        reserve(loan, INSTALLMENT, "reserve-1");
        configure(loan, "0.00", "2757500.00", "0.00", "2000000.00", "2500000.00");

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("red");
        assertThat(response.alert().severity()).isEqualTo("critical");
        assertThat(response.alert().reason()).contains("overdue");
        assertThat(response.alert().daysRemaining()).isNegative();
    }

    @Test
    @DisplayName("red when a verified schedule shows a critical funding shortfall")
    void redWhenVerifiedScheduleShowsCriticalShortfall() {
        KurLoan loan = createLoan(KurLoanProvenance.VERIFIED, FIRST_DUE);
        schedule(loan, FIRST_DUE, KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1");
        reserve(loan, new BigDecimal("100000.00"), "reserve-1");
        configure(loan, "1000000.00", "2757500.00", "0.00", "2000000.00", "2500000.00");
        postCash(new BigDecimal("5000000.00"));

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("red");
        assertThat(response.alert().reason()).contains("Critical funding shortfall");
    }

    @Test
    @DisplayName("an unverified schedule withholds red for a critical shortfall")
    void unverifiedScheduleWithholdsRed() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE, FIRST_DUE);
        schedule(loan, FIRST_DUE, KurScheduleSourceKind.ESTIMATE, "estimated-row-1");
        reserve(loan, new BigDecimal("100000.00"), "reserve-1");
        configure(loan, "1000000.00", "2757500.00", "0.00", "2000000.00", "2500000.00");
        postCash(new BigDecimal("5000000.00"));

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("orange");
        assertThat(response.alert().reason()).contains("red is withheld");
        assertThat(response.missing())
                .extracting(KurAlertResponse.Missing::key)
                .contains("kur.alert.schedule");
    }

    @Test
    @DisplayName("missing reserve data is unavailable, not a zero gap")
    void missingReserveIsUnavailableNotZero() {
        KurLoan loan = createLoan(KurLoanProvenance.VERIFIED, FIRST_DUE);
        schedule(loan, FIRST_DUE, KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1");
        configure(loan, "0.00", "2757500.00", "0.00", "2000000.00", "2500000.00");

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("unavailable");
        assertThat(response.alert().shortfall().amount()).isNull();
        assertThat(response.alert().shortfall().status()).isEqualTo("unavailable");
        assertThat(response.alert().reserve().qualifying()).isNull();
        assertThat(response.alert().reserve().status()).isEqualTo("unavailable");
        assertThat(response.missing())
                .extracting(KurAlertResponse.Missing::key)
                .contains("reserve.earmarked");
    }

    @Test
    @DisplayName("a reserve equal to the installment is never a paid state")
    void reserveEqualToInstallmentIsNeverPaid() {
        KurLoan loan = createLoan(KurLoanProvenance.VERIFIED, FIRST_DUE);
        KurInstallment installment = schedule(loan, FIRST_DUE,
                KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1");
        reserve(loan, INSTALLMENT, "reserve-1");
        configure(loan, "0.00", "2757500.00", "0.00", "2000000.00", "2500000.00");

        KurAlertResponse response = alertService.evaluate(loan.getId(), AS_OF);

        assertThat(response.alert().state()).isEqualTo("green");
        assertThat(response.alert().payment().status()).isEqualTo("unpaid");
        assertThat(response.alert().payment().paidAmount()).isEqualTo("0.00");
        assertThat(response.alert().payment().countsAsPaid()).isFalse();
        assertThat(installmentRepository.findById(installment.getId()).orElseThrow().getStatus())
                .isEqualTo(KurInstallmentStatus.UNPAID);
    }

    @Test
    @DisplayName("a policy without an approval reference is rejected")
    void policyWithoutApprovalReferenceIsRejected() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE, FIRST_DUE);

        assertThatThrownBy(() -> policyService.savePolicy(loan.getId(), new KurAlertPolicyService.PolicyCommand(
                BigDecimal.ZERO, INSTALLMENT, BigDecimal.ZERO,
                new BigDecimal("2000000.00"), new BigDecimal("2500000.00"), null, "CEO + Finance Controller")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvalReference");
    }

    @Test
    @DisplayName("a partial policy is rejected instead of inventing missing thresholds")
    void partialPolicyIsRejected() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE, FIRST_DUE);

        assertThatThrownBy(() -> policyService.savePolicy(loan.getId(), new KurAlertPolicyService.PolicyCommand(
                BigDecimal.ZERO, INSTALLMENT, BigDecimal.ZERO, null,
                new BigDecimal("2500000.00"), "FAS-96-TEST", "CEO + Finance Controller")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all required");
    }

    @Test
    @DisplayName("the red cutoff must be above the orange cutoff")
    void redCutoffMustExceedOrange() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE, FIRST_DUE);

        assertThatThrownBy(() -> policyService.savePolicy(loan.getId(), new KurAlertPolicyService.PolicyCommand(
                BigDecimal.ZERO, INSTALLMENT, BigDecimal.ZERO,
                new BigDecimal("2500000.00"), new BigDecimal("2500000.00"),
                "FAS-96-TEST", "CEO + Finance Controller")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redShortfall must be greater");
    }

    @Test
    @DisplayName("saving the same approved policy twice is idempotent")
    void policySaveIsIdempotent() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE, FIRST_DUE);
        KurAlertPolicy first = policyService.savePolicy(loan.getId(), defaultCommand());
        KurAlertPolicy second = policyService.savePolicy(loan.getId(), defaultCommand());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getApprovedAt()).isEqualTo(first.getApprovedAt());
    }

    private KurAlertPolicyService.PolicyCommand defaultCommand() {
        return new KurAlertPolicyService.PolicyCommand(
                BigDecimal.ZERO, INSTALLMENT, BigDecimal.ZERO,
                new BigDecimal("2000000.00"), new BigDecimal("2500000.00"),
                "FAS-96-TEST", "CEO + Finance Controller");
    }

    private void configure(KurLoan loan, String minimumCash, String targetReserve,
                           String tolerance, String orange, String red) {
        policyService.savePolicy(loan.getId(), new KurAlertPolicyService.PolicyCommand(
                new BigDecimal(minimumCash), new BigDecimal(targetReserve), new BigDecimal(tolerance),
                new BigDecimal(orange), new BigDecimal(red),
                "FAS-96-TEST", "CEO + Finance Controller"));
    }

    private KurLoan createLoan(KurLoanProvenance provenance, LocalDate firstDue) {
        return createLoan(provenance, firstDue, INSTALLMENT);
    }

    private KurLoan createLoan(KurLoanProvenance provenance, LocalDate firstDue, BigDecimal installment) {
        boolean verified = provenance == KurLoanProvenance.VERIFIED;
        return loanService.createLoan(new KurLoanService.CreateLoanCommand(
                "BRI", "KUR", null, "FASTERNET", "Indra",
                new BigDecimal("99270000.00"), LocalDate.of(2026, 10, 21), 36, installment,
                "MONTHLY", 36, firstDue, 21, LocalDate.of(2029, 10, 21),
                null,
                verified ? UUID.randomUUID() : null,
                verified ? UUID.randomUUID() : null,
                provenance, null));
    }

    private KurInstallment schedule(KurLoan loan, LocalDate due,
                                    KurScheduleSourceKind kind, String sourceRef) {
        return schedule(loan, due, INSTALLMENT, kind, sourceRef);
    }

    private KurInstallment schedule(KurLoan loan, LocalDate due, BigDecimal amount,
                                    KurScheduleSourceKind kind, String sourceRef) {
        return loanService.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, due, amount, null, null, null, kind, sourceRef));
    }

    private void reserve(KurLoan loan, BigDecimal amount, String sourceRef) {
        loanService.recordReserve(loan.getId(), new KurLoanService.ReserveCommand(
                LocalDate.of(2026, 10, 22), KurReserveEntryKind.EARMARK, amount,
                "sisihkan angsuran KUR", sourceRef));
    }

    private void postCash(BigDecimal amount) {
        ChartOfAccount cash = chartOfAccountRepository.findCashBankAccounts().stream()
                .findFirst().orElseThrow();
        ChartOfAccount revenue = chartOfAccountRepository.findByAccountCode("4.1.01").orElseThrow();
        JournalTemplate template = journalTemplateRepository.findAll().stream().findFirst().orElseThrow();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Transaction transaction = new Transaction();
        transaction.setTransactionNumber("TRX-FAS96-" + suffix);
        transaction.setTransactionDate(LocalDate.of(2026, 10, 1));
        transaction.setJournalTemplate(template);
        transaction.setAmount(amount);
        transaction.setDescription("FAS-96 alert test cash");
        transaction.setStatus(TransactionStatus.POSTED);
        transaction = transactionRepository.save(transaction);

        JournalEntry debit = new JournalEntry();
        debit.setJournalNumber("JRN-FAS96-D-" + suffix);
        debit.setTransaction(transaction);
        debit.setAccount(cash);
        debit.setDebitAmount(amount);
        debit.setCreditAmount(BigDecimal.ZERO);

        JournalEntry credit = new JournalEntry();
        credit.setJournalNumber("JRN-FAS96-C-" + suffix);
        credit.setTransaction(transaction);
        credit.setAccount(revenue);
        credit.setDebitAmount(BigDecimal.ZERO);
        credit.setCreditAmount(amount);

        journalEntryRepository.saveAll(List.of(debit, credit));
    }
}
