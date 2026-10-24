package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.dto.fasternet.KurReserveResponse;
import com.artivisi.accountingfinance.dto.fasternet.KurScheduleResponse;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.KurPayment;
import com.artivisi.accountingfinance.entity.KurReserveEntry;
import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.repository.KurInstallmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the KUR loan backend. They start the full Spring context and
 * Flyway schema; they require a PostgreSQL test container (or an external DB with
 * {@code -Ddemo.external-db=true}).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("KUR loan backend")
class KurLoanServiceIntegrationTest {

    private static final LocalDate DISBURSEMENT = LocalDate.of(2026, 10, 21);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 21);
    private static final BigDecimal INSTALLMENT = new BigDecimal("2757500.00");

    @Autowired
    private KurLoanService service;

    @Autowired
    private KurInstallmentRepository installmentRepository;

    @Test
    @DisplayName("founder figures start as founder-estimate, not confirmed")
    void newLoanIsFounderEstimate() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);

        KurScheduleResponse schedule = service.schedule(loan.getId(), FIRST_DUE.plusDays(1));

        assertThat(schedule.loan().provenance()).isEqualTo("founder-estimate");
        assertThat(schedule.loan().agreementAttached()).isFalse();
        assertThat(schedule.loan().scheduleAttached()).isFalse();
        assertThat(schedule.missing()).extracting(KurScheduleResponse.Missing::key)
                .contains("kur.schedule", "kur.agreement", "kur.scheduleDocument");
    }

    @Test
    @DisplayName("a verified loan without attached documents is rejected")
    void verifiedNeedsDocuments() {
        assertThatThrownBy(() -> createLoan(KurLoanProvenance.VERIFIED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed agreement");
    }

    @Test
    @DisplayName("importing the same schedule source reference twice is idempotent")
    void scheduleImportIsIdempotent() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);

        KurInstallment first = service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "lender-row-1"));
        KurInstallment second = service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "lender-row-1"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(service.schedule(loan.getId(), FIRST_DUE).installments()).hasSize(1);
    }

    @Test
    @DisplayName("reserving funds never marks an installment paid")
    void reserveNeverMarksPaid() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        KurInstallment installment = service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "lender-row-1"));

        service.recordReserve(loan.getId(), new KurLoanService.ReserveCommand(
                LocalDate.of(2026, 10, 25), KurReserveEntryKind.EARMARK,
                new BigDecimal("1000000.00"), "sisihkan dari kas", "reserve-1"));

        KurScheduleResponse schedule = service.schedule(loan.getId(), LocalDate.of(2026, 10, 31));
        KurScheduleResponse.Installment paid = schedule.installments().getFirst();
        assertThat(paid.status()).isEqualTo("unpaid");
        assertThat(paid.paidAmount()).isEqualTo("0.00");
        assertThat(installment.getPaidAmount()).isEqualByComparingTo("0.00");

        KurReserveResponse reserve = service.reserve(loan.getId(), LocalDate.of(2026, 10, 31));
        assertThat(reserve.figures().get("reserve.earmarked").value()).isEqualTo("1000000.00");
        assertThat(reserve.figures().get("reserve.gap").value()).isEqualTo("1757500.00");
        assertThat(reserve.figures().get("obligation.nextDue").value()).isEqualTo("2757500.00");
        assertThat(reserve.invariant()).isEqualTo("reserving funds never marks an installment paid");
    }

    @Test
    @DisplayName("partial then over payment is tracked and duplicate source refs are ignored")
    void partialAndOverpayment() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        KurInstallment installment = service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "lender-row-1"));

        service.recordPayment(loan.getId(), installment.getId(), payment(
                "1000000.00", "2026-11-10", "trf-1"));
        service.recordPayment(loan.getId(), installment.getId(), payment(
                "1000000.00", "2026-11-10", "trf-1"));
        KurInstallment afterPartial = installments(loan.getId()).getFirst();
        assertThat(afterPartial.getPaidAmount()).isEqualByComparingTo("1000000.00");
        assertThat(afterPartial.getStatus()).isEqualTo(KurInstallmentStatus.PARTIAL);
        assertThat(afterPartial.getOutstanding()).isEqualByComparingTo("1757500.00");

        service.recordPayment(loan.getId(), installment.getId(), payment(
                "2000000.00", "2026-11-20", "trf-2"));
        KurInstallment afterOver = installments(loan.getId()).getFirst();
        assertThat(afterOver.getPaidAmount()).isEqualByComparingTo("3000000.00");
        assertThat(afterOver.getStatus()).isEqualTo(KurInstallmentStatus.OVERPAID);
        assertThat(afterOver.getOutstanding()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a reversed payment is excluded without deleting history")
    void reversalRecomputesPaid() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        KurInstallment installment = service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "lender-row-1"));
        KurPayment payment = service.recordPayment(loan.getId(), installment.getId(), payment(
                "2757500.00", "2026-11-20", "trf-1"));
        assertThat(installments(loan.getId()).getFirst().getStatus()).isEqualTo(KurInstallmentStatus.PAID);

        service.reversePayment(payment.getId(), "salah transfer");

        KurInstallment after = installments(loan.getId()).getFirst();
        assertThat(after.getStatus()).isEqualTo(KurInstallmentStatus.UNPAID);
        assertThat(after.getPaidAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a payment without evidence is rejected and leaves the installment unpaid")
    void paymentWithoutEvidenceIsRejected() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        KurInstallment installment = service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "lender-row-1"));

        assertThatThrownBy(() -> service.recordPayment(loan.getId(), installment.getId(),
                new KurLoanService.PaymentCommand(
                        LocalDate.of(2026, 11, 20), INSTALLMENT, "BRI-TRF",
                        "  ", "trf-no-evidence")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRef");

        assertThat(installments(loan.getId()).getFirst().getStatus()).isEqualTo(KurInstallmentStatus.UNPAID);
        assertThat(installments(loan.getId()).getFirst().getPaidAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a reserve release cannot take the earmarked bucket below zero")
    void reserveReleaseCannotGoNegative() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        service.recordReserve(loan.getId(), new KurLoanService.ReserveCommand(
                LocalDate.of(2026, 10, 25), KurReserveEntryKind.EARMARK,
                new BigDecimal("1000000.00"), "sisihkan", "reserve-1"));

        assertThatThrownBy(() -> service.recordReserve(loan.getId(), new KurLoanService.ReserveCommand(
                LocalDate.of(2026, 10, 26), KurReserveEntryKind.RELEASE,
                new BigDecimal("1500000.00"), "ambil kembali", "reserve-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the available earmarked balance");
    }

    @Test
    @DisplayName("cash and separate reserve account are summed exactly once")
    void separateAccountAddsToQualifyingReserve() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "lender-row-1"));

        service.recordReserve(loan.getId(), new KurLoanService.ReserveCommand(
                LocalDate.of(2026, 10, 25), KurReserveEntryKind.EARMARK,
                new BigDecimal("1000000.00"), "earmark", "reserve-1"));
        KurReserveEntry separate = service.recordReserve(loan.getId(), new KurLoanService.ReserveCommand(
                LocalDate.of(2026, 10, 25), KurReserveEntryKind.SEPARATE_ACCOUNT,
                new BigDecimal("500000.00"), "rekening cadangan", "reserve-2"));

        assertThat(separate.getKind()).isEqualTo(KurReserveEntryKind.SEPARATE_ACCOUNT);
        KurReserveResponse reserve = service.reserve(loan.getId(), LocalDate.of(2026, 10, 31));
        assertThat(reserve.figures().get("reserve.separateAccount").value()).isEqualTo("500000.00");
        assertThat(reserve.figures().get("reserve.gap").value()).isEqualTo("1257500.00");
    }

    @Test
    @DisplayName("the lender schedule re-baselines an estimate row before any payment")
    void lenderScheduleReplacesEstimateBeforePayments() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        KurInstallment estimate = service.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, new BigDecimal("3000000.00"), null, null, null,
                KurScheduleSourceKind.ESTIMATE, "calculated-1"));

        KurInstallment authoritative = service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "bri-row-1"));

        assertThat(authoritative.getId()).isEqualTo(estimate.getId());
        assertThat(authoritative.getSourceKind()).isEqualTo(KurScheduleSourceKind.LENDER_SCHEDULE);
        assertThat(authoritative.getSourceRef()).isEqualTo("bri-row-1");
        assertThat(authoritative.getAmount()).isEqualByComparingTo("2757500.00");
        assertThat(installments(loan.getId())).hasSize(1);
    }

    @Test
    @DisplayName("the same source reference with different values is rejected, not silently applied")
    void changedValuesUnderSameSourceReferenceRejected() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "bri-row-1"));

        assertThatThrownBy(() -> service.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, new BigDecimal("2000000.00"), null, null, null,
                KurScheduleSourceKind.LENDER_SCHEDULE, "bri-row-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different values");
    }

    @Test
    @DisplayName("a lender row cannot re-baseline an installment that already has payments")
    void lenderScheduleCannotReplaceEstimateWithPayments() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        KurInstallment estimate = service.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, new BigDecimal("3000000.00"), null, null, null,
                KurScheduleSourceKind.ESTIMATE, "calculated-1"));
        service.recordPayment(loan.getId(), estimate.getId(), payment("1000000.00", "2026-11-10", "trf-1"));

        assertThatThrownBy(() -> service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "bri-row-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reverse them");
    }

    @Test
    @DisplayName("an estimate row cannot overwrite the lender schedule")
    void estimateCannotOverwriteLenderRow() {
        KurLoan loan = createLoan(KurLoanProvenance.FOUNDER_ESTIMATE);
        service.importScheduleRow(loan.getId(), row(1, FIRST_DUE, "bri-row-1"));

        assertThatThrownBy(() -> service.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, INSTALLMENT, null, null, null,
                KurScheduleSourceKind.ESTIMATE, "calculated-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritative schedule row");
    }

    private KurLoan createLoan(KurLoanProvenance provenance) {
        return service.createLoan(new KurLoanService.CreateLoanCommand(
                "BRI", "KUR", null, "FASTERNET", "Indra",
                new BigDecimal("99270000.00"), DISBURSEMENT, 36, INSTALLMENT,
                "MONTHLY", 36, FIRST_DUE, 21, LocalDate.of(2029, 10, 21),
                null, null, null, provenance, null));
    }

    private KurLoanService.ScheduleRow row(int number, LocalDate dueDate, String sourceRef) {
        return new KurLoanService.ScheduleRow(
                number, dueDate, INSTALLMENT, null, null, null,
                KurScheduleSourceKind.LENDER_SCHEDULE, sourceRef);
    }

    private KurLoanService.PaymentCommand payment(String amount, String date, String sourceRef) {
        return new KurLoanService.PaymentCommand(
                LocalDate.parse(date), new BigDecimal(amount), "BRI-" + sourceRef,
                "bukti-" + sourceRef, sourceRef);
    }

    private java.util.List<KurInstallment> installments(UUID loanId) {
        return installmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loanId);
    }
}
