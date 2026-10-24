package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.KurPayment;
import com.artivisi.accountingfinance.entity.KurReserveEntry;
import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.service.DocumentService;
import com.artivisi.accountingfinance.service.fasternet.KurLoanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FAS-105: a concurrent duplicate insert must not surface as a 503. When the
 * unique index rejects the losing insert, the write API replays the row the
 * winner stored with 200; if no row can be found the conflict is a deterministic
 * 409, never a ledger-unavailable 503.
 */
@DisplayName("FASTERNET KUR write duplicate-key replay")
class KurWriteApiControllerReplayTest {

    private final KurLoanService kurLoanService = mock(KurLoanService.class);
    private final KurWriteApiController controller =
            new KurWriteApiController(kurLoanService, mock(DocumentService.class));

    @Test
    @DisplayName("a duplicate payment insert replays the stored row with 200")
    void duplicatePaymentInsertReplaysStoredRow() {
        UUID loanId = UUID.randomUUID();
        UUID installmentId = UUID.randomUUID();
        KurPayment payment = payment(loanId, installmentId, UUID.randomUUID());

        when(kurLoanService.recordPaymentOutcome(eq(loanId), eq(installmentId), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(kurLoanService.replayPayment(eq(installmentId), any()))
                .thenReturn(Optional.of(new KurLoanService.PaymentOutcome(payment, false)));

        ResponseEntity<KurWriteApiController.PaymentResponse> response =
                controller.recordPayment(loanId, installmentId, paymentRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(payment.getId());
        assertThat(response.getBody().amount()).isEqualTo("1000000.00");
    }

    @Test
    @DisplayName("a duplicate reserve insert replays the stored row with 200")
    void duplicateReserveInsertReplaysStoredRow() {
        UUID loanId = UUID.randomUUID();
        KurReserveEntry entry = reserveEntry(loanId, UUID.randomUUID());

        when(kurLoanService.recordReserveOutcome(eq(loanId), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(kurLoanService.replayReserve(eq(loanId), any()))
                .thenReturn(Optional.of(new KurLoanService.ReserveOutcome(entry, false)));

        ResponseEntity<KurWriteApiController.ReserveEntryResponse> response =
                controller.recordReserve(loanId, new KurWriteApiController.ReserveRequest(
                        LocalDate.of(2026, 10, 25), KurReserveEntryKind.EARMARK,
                        new BigDecimal("1000000.00"), "sisihkan", "reserve-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(entry.getId());
        assertThat(response.getBody().amount()).isEqualTo("1000000.00");
    }

    @Test
    @DisplayName("a duplicate schedule insert replays the stored row with 200")
    void duplicateScheduleInsertReplaysStoredRow() {
        UUID loanId = UUID.randomUUID();
        KurInstallment installment = installment(loanId, UUID.randomUUID());

        when(kurLoanService.importScheduleRowOutcome(eq(loanId), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(kurLoanService.replayScheduleRow(eq(loanId), any()))
                .thenReturn(Optional.of(new KurLoanService.ScheduleImportOutcome(installment, false)));

        ResponseEntity<KurWriteApiController.InstallmentResponse> response =
                controller.importScheduleRow(loanId, new KurWriteApiController.ScheduleRowRequest(
                        1, LocalDate.of(2026, 11, 21), new BigDecimal("2757500.00"),
                        null, null, null, KurScheduleSourceKind.LENDER_SCHEDULE, "bri-row-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(installment.getId());
        assertThat(response.getBody().amount()).isEqualTo("2757500.00");
    }

    @Test
    @DisplayName("a duplicate insert without a resolvable stored row is a deterministic 409")
    void unresolvedDuplicateIsConflictNot503() {
        UUID loanId = UUID.randomUUID();
        UUID installmentId = UUID.randomUUID();
        when(kurLoanService.recordPaymentOutcome(eq(loanId), eq(installmentId), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(kurLoanService.replayPayment(eq(installmentId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.recordPayment(loanId, installmentId, paymentRequest()))
                .isInstanceOf(DataIntegrityViolationException.class);

        ResponseEntity<java.util.Map<String, String>> fallback =
                controller.handleDataIntegrityConflict(new DataIntegrityViolationException("duplicate key"));
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fallback.getBody()).containsEntry("error", "conflict");
    }

    private static KurWriteApiController.PaymentRequest paymentRequest() {
        return new KurWriteApiController.PaymentRequest(
                LocalDate.of(2026, 11, 10), new BigDecimal("1000000.00"),
                "BRI-TRF", "bukti-trf-1", "trf-1");
    }

    private static KurPayment payment(UUID loanId, UUID installmentId, UUID paymentId) {
        KurPayment payment = new KurPayment();
        payment.setId(paymentId);
        payment.setInstallment(installment(loanId, installmentId));
        payment.setPaymentDate(LocalDate.of(2026, 11, 10));
        payment.setAmount(new BigDecimal("1000000.00"));
        payment.setBankRef("BRI-TRF");
        payment.setEvidenceRef("bukti-trf-1");
        payment.setSourceRef("trf-1");
        return payment;
    }

    private static KurReserveEntry reserveEntry(UUID loanId, UUID entryId) {
        KurLoan loan = new KurLoan();
        loan.setId(loanId);
        KurReserveEntry entry = new KurReserveEntry();
        entry.setId(entryId);
        entry.setLoan(loan);
        entry.setEntryDate(LocalDate.of(2026, 10, 25));
        entry.setKind(KurReserveEntryKind.EARMARK);
        entry.setAmount(new BigDecimal("1000000.00"));
        entry.setDescription("sisihkan");
        entry.setSourceRef("reserve-1");
        return entry;
    }

    private static KurInstallment installment(UUID loanId, UUID installmentId) {
        KurLoan loan = new KurLoan();
        loan.setId(loanId);
        KurInstallment installment = new KurInstallment();
        installment.setId(installmentId);
        installment.setLoan(loan);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.of(2026, 11, 21));
        installment.setAmount(new BigDecimal("2757500.00"));
        installment.setPaidAmount(new BigDecimal("0.00"));
        installment.setSourceKind(KurScheduleSourceKind.LENDER_SCHEDULE);
        installment.setSourceRef("bri-row-1");
        return installment;
    }
}
