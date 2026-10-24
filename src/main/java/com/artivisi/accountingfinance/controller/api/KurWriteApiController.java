package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.dto.DocumentResponse;
import com.artivisi.accountingfinance.entity.Document;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.KurPayment;
import com.artivisi.accountingfinance.entity.KurReserveEntry;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.exception.KurResourceNotFoundException;
import com.artivisi.accountingfinance.security.CurrentUser;
import com.artivisi.accountingfinance.security.Permission;
import com.artivisi.accountingfinance.service.DocumentService;
import com.artivisi.accountingfinance.service.fasternet.KurLoanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Authorized KUR write/admin path (FAS-95).
 *
 * <p>Authority is deliberately separate from {@code analysis:read}. Ordinary writes
 * need {@code SCOPE_kur:write} plus {@code KUR_WRITE}; provenance verification needs
 * {@code SCOPE_kur:verify} plus {@code KUR_VERIFY}. Neither permission is granted to
 * {@code ADMIN} by default. The controller never initiates a bank transfer, stores no
 * bank credentials, and calls the existing {@link KurLoanService} rather than
 * mutating the ledger tables directly.
 *
 * <p>A loan created here is always {@code FOUNDER_ESTIMATE}. It only becomes
 * {@code VERIFIED} through the explicit verify action, with both the signed agreement
 * and the lender schedule attached.
 */
@RestController
@RequestMapping("/api/fasternet/v1/kur")
@Tag(name = "FASTERNET KUR write", description = "Authorized entry of KUR loan master, schedule, payments and reserve movements")
@RequiredArgsConstructor
@Slf4j
public class KurWriteApiController {

    private final KurLoanService kurLoanService;
    private final DocumentService documentService;

    // ------------------------------------------------------------------
    // Supporting documents
    // ------------------------------------------------------------------

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_kur:write') and hasAuthority('" + Permission.KUR_WRITE + "')")
    @Operation(summary = "Upload a KUR supporting document",
            description = "Stores the signed agreement, lender repayment schedule or transfer proof. "
                    + "Returns the document id to attach to the loan or payment.")
    @ApiResponse(responseCode = "201", description = "Document stored")
    @ApiResponse(responseCode = "400", description = "Invalid or rejected file")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks KUR_WRITE")
    public ResponseEntity<DocumentResponse> uploadDocument(@RequestParam("file") MultipartFile file) throws IOException {
        Document saved = documentService.upload(file, CurrentUser.name());
        log.info("FASTERNET KUR: supporting document stored id={}", saved.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.from(saved));
    }

    // ------------------------------------------------------------------
    // Loan master
    // ------------------------------------------------------------------

    @PostMapping("/loans")
    @PreAuthorize("hasAuthority('SCOPE_kur:write') and hasAuthority('" + Permission.KUR_WRITE + "')")
    @Operation(summary = "Create the KUR loan master",
            description = "Always creates a FOUNDER_ESTIMATE loan. Provenance is not accepted from the caller; "
                    + "use POST /loans/{loanId}/verify with a Finance Controller token to mark it VERIFIED.")
    @ApiResponse(responseCode = "201", description = "Loan created as founder-estimate")
    @ApiResponse(responseCode = "400", description = "Validation error or unknown document id")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks KUR_WRITE")
    public ResponseEntity<LoanResponse> createLoan(@Valid @RequestBody CreateLoanRequest request) {
        KurLoan loan = kurLoanService.createLoan(new KurLoanService.CreateLoanCommand(
                request.lender(),
                request.product(),
                request.loanReference(),
                request.borrowerName(),
                request.borrowerLegalOwner(),
                request.principalOriginal(),
                request.disbursementDate(),
                request.termMonths(),
                request.installmentContractual(),
                request.installmentFrequency(),
                request.installmentCount(),
                request.firstDueDate(),
                request.monthlyDueDay(),
                request.maturityDate(),
                request.fees(),
                request.agreementDocumentId(),
                request.scheduleDocumentId(),
                KurLoanProvenance.FOUNDER_ESTIMATE,
                request.notes()));
        log.info("FASTERNET KUR: loan created id={}", loan.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(LoanResponse.from(loan));
    }

    @PostMapping("/loans/{loanId}/verify")
    @PreAuthorize("hasAuthority('SCOPE_kur:verify') and hasAuthority('" + Permission.KUR_VERIFY + "')")
    @Operation(summary = "Verify the loan against the signed agreement and lender schedule",
            description = "Finance Controller only. Both documents must be attached (already on the loan or in "
                    + "this request). Sets provenance VERIFIED and records the reason in the security audit.")
    @ApiResponse(responseCode = "200", description = "Loan verified")
    @ApiResponse(responseCode = "400", description = "Missing document or reason")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks KUR_VERIFY")
    @ApiResponse(responseCode = "404", description = "Loan not found")
    public ResponseEntity<LoanResponse> verifyLoan(
            @PathVariable UUID loanId,
            @Valid @RequestBody VerifyLoanRequest request) {
        KurLoan loan = kurLoanService.verifyLoan(
                loanId, request.agreementDocumentId(), request.scheduleDocumentId(), request.reason());
        log.info("FASTERNET KUR: loan verified id={}", loan.getId());
        return ResponseEntity.ok(LoanResponse.from(loan));
    }

    // ------------------------------------------------------------------
    // Lender schedule
    // ------------------------------------------------------------------

    @PostMapping("/loans/{loanId}/schedule")
    @PreAuthorize("hasAuthority('SCOPE_kur:write') and hasAuthority('" + Permission.KUR_WRITE + "')")
    @Operation(summary = "Import one lender schedule row",
            description = "Idempotent on (source kind, source reference). A replay returns the existing row; "
                    + "changed values under the same source reference are rejected with 409.")
    @ApiResponse(responseCode = "201", description = "Schedule row created")
    @ApiResponse(responseCode = "200", description = "Idempotent replay or estimate re-baselined")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks KUR_WRITE")
    @ApiResponse(responseCode = "404", description = "Loan not found")
    @ApiResponse(responseCode = "409", description = "Source reference already used with different values")
    public ResponseEntity<InstallmentResponse> importScheduleRow(
            @PathVariable UUID loanId,
            @Valid @RequestBody ScheduleRowRequest request) {
        KurLoanService.ScheduleImportOutcome outcome = kurLoanService.importScheduleRowOutcome(
                loanId, new KurLoanService.ScheduleRow(
                        request.installmentNumber(),
                        request.dueDate(),
                        request.amount(),
                        request.principal(),
                        request.interest(),
                        request.fee(),
                        request.sourceKind() == null ? KurScheduleSourceKind.LENDER_SCHEDULE : request.sourceKind(),
                        request.sourceRef()));
        log.info("FASTERNET KUR: schedule row {} loan={} installment={}",
                outcome.created() ? "created" : "replayed", loanId, outcome.installment().getId());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(InstallmentResponse.from(outcome.installment()));
    }

    // ------------------------------------------------------------------
    // Payments
    // ------------------------------------------------------------------

    @PostMapping("/loans/{loanId}/installments/{installmentId}/payments")
    @PreAuthorize("hasAuthority('SCOPE_kur:write') and hasAuthority('" + Permission.KUR_WRITE + "')")
    @Operation(summary = "Record an installment payment",
            description = "Evidence is mandatory. Partial, full and overpayment are supported. "
                    + "Idempotent on (installment, source reference).")
    @ApiResponse(responseCode = "201", description = "Payment recorded")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of the same source reference")
    @ApiResponse(responseCode = "400", description = "Validation error, missing evidence, or installment/loan mismatch")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks KUR_WRITE")
    @ApiResponse(responseCode = "404", description = "Loan or installment not found")
    public ResponseEntity<PaymentResponse> recordPayment(
            @PathVariable UUID loanId,
            @PathVariable UUID installmentId,
            @Valid @RequestBody PaymentRequest request) {
        KurLoanService.PaymentOutcome outcome = kurLoanService.recordPaymentOutcome(
                loanId, installmentId, new KurLoanService.PaymentCommand(
                        request.paymentDate(),
                        request.amount(),
                        request.bankRef(),
                        request.evidenceRef(),
                        request.sourceRef()));
        KurInstallment installment = outcome.payment().getInstallment();
        log.info("FASTERNET KUR: payment {} payment={} installment={} status={}",
                outcome.created() ? "recorded" : "replayed",
                outcome.payment().getId(), installmentId, installment.getStatus());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(PaymentResponse.from(outcome.payment()));
    }

    @PostMapping("/loans/{loanId}/payments/{paymentId}/reverse")
    @PreAuthorize("hasAuthority('SCOPE_kur:write') and hasAuthority('" + Permission.KUR_WRITE + "')")
    @Operation(summary = "Reverse a payment without deleting it",
            description = "Requires a reason. The installment paid total and status are recomputed from the "
                    + "remaining active payments; the original row is retained.")
    @ApiResponse(responseCode = "200", description = "Payment reversed (or already reversed)")
    @ApiResponse(responseCode = "400", description = "Missing reason or payment not on this loan")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks KUR_WRITE")
    @ApiResponse(responseCode = "404", description = "Loan or payment not found")
    public ResponseEntity<PaymentResponse> reversePayment(
            @PathVariable UUID loanId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody ReversePaymentRequest request) {
        KurPayment payment = kurLoanService.reversePayment(loanId, paymentId, request.reason());
        log.info("FASTERNET KUR: payment reversed payment={} loan={}", paymentId, loanId);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // ------------------------------------------------------------------
    // Reserve tracker
    // ------------------------------------------------------------------

    @PostMapping("/loans/{loanId}/reserve")
    @PreAuthorize("hasAuthority('SCOPE_kur:write') and hasAuthority('" + Permission.KUR_WRITE + "')")
    @Operation(summary = "Record a reserve movement",
            description = "EARMARK/RELEASE and SEPARATE_ACCOUNT/SEPARATE_ACCOUNT_RELEASE. A release cannot "
                    + "drive its bucket negative. Reserving never marks an installment paid.")
    @ApiResponse(responseCode = "201", description = "Reserve movement recorded")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of the same source reference")
    @ApiResponse(responseCode = "400", description = "Validation error or release exceeds the bucket")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks KUR_WRITE")
    @ApiResponse(responseCode = "404", description = "Loan not found")
    public ResponseEntity<ReserveEntryResponse> recordReserve(
            @PathVariable UUID loanId,
            @Valid @RequestBody ReserveRequest request) {
        KurLoanService.ReserveOutcome outcome = kurLoanService.recordReserveOutcome(
                loanId, new KurLoanService.ReserveCommand(
                        request.entryDate(),
                        request.kind(),
                        request.amount(),
                        request.description(),
                        request.sourceRef()));
        log.info("FASTERNET KUR: reserve {} entry={} loan={} kind={}",
                outcome.created() ? "recorded" : "replayed",
                outcome.entry().getId(), loanId, outcome.entry().getKind());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ReserveEntryResponse.from(outcome.entry()));
    }

    // ------------------------------------------------------------------
    // Error mapping local to the write API
    // ------------------------------------------------------------------

    @ExceptionHandler(KurResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(KurResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "not_found",
                "message", ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleLedgerUnavailable(DataAccessException ex) {
        log.error("FASTERNET KUR write: ledger is unavailable: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "ledger_unavailable",
                "message", "The accounting ledger is currently unavailable"));
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    public record CreateLoanRequest(
            @NotBlank @Size(max = 100) String lender,
            @Size(max = 50) String product,
            @Size(max = 100) String loanReference,
            @NotBlank @Size(max = 200) String borrowerName,
            @Size(max = 200) String borrowerLegalOwner,
            @NotNull @DecimalMin("0.01") BigDecimal principalOriginal,
            LocalDate disbursementDate,
            @NotNull @Min(1) Integer termMonths,
            @DecimalMin("0.00") BigDecimal installmentContractual,
            @Size(max = 20) String installmentFrequency,
            @NotNull @Min(1) Integer installmentCount,
            LocalDate firstDueDate,
            @Min(1) @Max(31) Integer monthlyDueDay,
            LocalDate maturityDate,
            @DecimalMin("0.00") BigDecimal fees,
            UUID agreementDocumentId,
            UUID scheduleDocumentId,
            String notes
    ) {
    }

    public record VerifyLoanRequest(
            UUID agreementDocumentId,
            UUID scheduleDocumentId,
            @NotBlank @Size(max = 300) String reason
    ) {
    }

    public record ScheduleRowRequest(
            @NotNull @Min(1) Integer installmentNumber,
            @NotNull LocalDate dueDate,
            @NotNull @DecimalMin("0.00") BigDecimal amount,
            @DecimalMin("0.00") BigDecimal principal,
            @DecimalMin("0.00") BigDecimal interest,
            @DecimalMin("0.00") BigDecimal fee,
            KurScheduleSourceKind sourceKind,
            @NotBlank @Size(max = 200) String sourceRef
    ) {
    }

    public record PaymentRequest(
            @NotNull LocalDate paymentDate,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Size(max = 100) String bankRef,
            @NotBlank @Size(max = 200) String evidenceRef,
            @NotBlank @Size(max = 200) String sourceRef
    ) {
    }

    public record ReversePaymentRequest(
            @NotBlank @Size(max = 300) String reason
    ) {
    }

    public record ReserveRequest(
            @NotNull LocalDate entryDate,
            @NotNull KurReserveEntryKind kind,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Size(max = 300) String description,
            @NotBlank @Size(max = 200) String sourceRef
    ) {
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    public record LoanResponse(
            UUID id,
            String lender,
            String product,
            String borrowerName,
            String borrowerLegalOwner,
            String principalOriginal,
            String disbursementDate,
            Integer termMonths,
            String installmentContractual,
            Integer installmentCount,
            String firstDueDate,
            String maturityDate,
            UUID agreementDocumentId,
            UUID scheduleDocumentId,
            String provenance,
            String status
    ) {
        public static LoanResponse from(KurLoan loan) {
            return new LoanResponse(
                    loan.getId(),
                    loan.getLender(),
                    loan.getProduct(),
                    loan.getBorrowerName(),
                    loan.getBorrowerLegalOwner(),
                    money(loan.getPrincipalOriginal()),
                    date(loan.getDisbursementDate()),
                    loan.getTermMonths(),
                    money(loan.getInstallmentContractual()),
                    loan.getInstallmentCount(),
                    date(loan.getFirstDueDate()),
                    date(loan.getMaturityDate()),
                    loan.getAgreementDocumentId(),
                    loan.getScheduleDocumentId(),
                    loan.getProvenance().getJsonValue(),
                    loan.getStatus().name());
        }
    }

    public record InstallmentResponse(
            UUID id,
            UUID loanId,
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
            String sourceKind,
            String sourceRef
    ) {
        public static InstallmentResponse from(KurInstallment installment) {
            return new InstallmentResponse(
                    installment.getId(),
                    installment.getLoan().getId(),
                    installment.getInstallmentNumber(),
                    date(installment.getDueDate()),
                    money(installment.getAmount()),
                    money(installment.getPrincipal()),
                    money(installment.getInterest()),
                    money(installment.getFee()),
                    money(installment.getPaidAmount()),
                    date(installment.getPaidDate()),
                    money(installment.getOutstanding()),
                    installment.getStatus().getJsonValue(),
                    installment.getSourceKind().getJsonValue(),
                    installment.getSourceRef());
        }
    }

    public record PaymentResponse(
            UUID id,
            UUID installmentId,
            String paymentDate,
            String amount,
            String bankRef,
            String evidenceRef,
            String sourceRef,
            String reversedAt,
            String reversalReason
    ) {
        public static PaymentResponse from(KurPayment payment) {
            return new PaymentResponse(
                    payment.getId(),
                    payment.getInstallment().getId(),
                    date(payment.getPaymentDate()),
                    money(payment.getAmount()),
                    payment.getBankRef(),
                    payment.getEvidenceRef(),
                    payment.getSourceRef(),
                    instant(payment.getReversedAt()),
                    payment.getReversalReason());
        }
    }

    public record ReserveEntryResponse(
            UUID id,
            UUID loanId,
            String entryDate,
            String kind,
            String amount,
            String description,
            String sourceRef
    ) {
        public static ReserveEntryResponse from(KurReserveEntry entry) {
            return new ReserveEntryResponse(
                    entry.getId(),
                    entry.getLoan().getId(),
                    date(entry.getEntryDate()),
                    entry.getKind().name(),
                    money(entry.getAmount()),
                    entry.getDescription(),
                    entry.getSourceRef());
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String instant(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
