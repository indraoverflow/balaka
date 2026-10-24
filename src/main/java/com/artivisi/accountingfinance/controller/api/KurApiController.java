package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.dto.fasternet.KurReserveResponse;
import com.artivisi.accountingfinance.dto.fasternet.KurScheduleResponse;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.enums.KurLoanStatus;
import com.artivisi.accountingfinance.repository.KurLoanRepository;
import com.artivisi.accountingfinance.service.fasternet.KurLoanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only KUR schedule and reserve endpoints ({@code fasternet.dashboard/v1} §3.2/§3.3).
 *
 * <p>GET-only: the dashboard cannot import, pay, earmark or transfer money. Values are
 * derived from the KUR loan master and reserve ledger rows; missing data is reported as
 * {@code unavailable}, never as a fabricated zero.
 */
@RestController
@RequestMapping("/api/fasternet/v1/kur")
@Tag(name = "FASTERNET KUR", description = "Read-only KUR schedule and installment reserve projections")
@PreAuthorize("hasAuthority('SCOPE_analysis:read')")
@RequiredArgsConstructor
@Slf4j
public class KurApiController {

    private final KurLoanService kurLoanService;
    private final KurLoanRepository kurLoanRepository;

    @GetMapping("/schedule")
    @Operation(summary = "KUR loan master and repayment schedule as of a date",
            description = "Returns the loan master plus installment rows. Ambiguous loan selection is rejected.")
    @ApiResponse(responseCode = "200", description = "KUR schedule envelope")
    @ApiResponse(responseCode = "400", description = "Invalid asOf or loanId")
    @ApiResponse(responseCode = "404", description = "No KUR loan")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks REPORT_VIEW (analysis:read)")
    @ApiResponse(responseCode = "503", description = "Ledger database unavailable")
    public ResponseEntity<?> schedule(
            @RequestParam(name = "asOf", required = false) String asOf,
            @RequestParam(name = "loanId", required = false) String loanId) {
        LocalDate asOfDate;
        try {
            asOfDate = (asOf == null || asOf.isBlank()) ? LocalDate.now() : LocalDate.parse(asOf.trim());
        } catch (DateTimeParseException e) {
            return invalidAsOf();
        }

        try {
            UUID resolved = resolveLoanId(loanId);
            if (resolved == null) return loanNotFound();
            KurScheduleResponse response = kurLoanService.schedule(resolved, asOfDate);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
        } catch (AmbiguousLoanException e) {
            return invalidLoanId(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "kur_loan_not_found",
                    "message", e.getMessage()));
        } catch (DataAccessException e) {
            return ledgerUnavailable(e);
        }
    }

    @GetMapping("/reserve")
    @Operation(summary = "KUR installment reserve tracker as of a date",
            description = "Earmarked and separate-account reserve versus the next obligation. "
                    + "Reserving funds never marks an installment paid.")
    @ApiResponse(responseCode = "200", description = "KUR reserve envelope")
    @ApiResponse(responseCode = "400", description = "Invalid asOf or loanId")
    @ApiResponse(responseCode = "404", description = "No KUR loan")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks REPORT_VIEW (analysis:read)")
    @ApiResponse(responseCode = "503", description = "Ledger database unavailable")
    public ResponseEntity<?> reserve(
            @RequestParam(name = "asOf", required = false) String asOf,
            @RequestParam(name = "loanId", required = false) String loanId) {
        LocalDate asOfDate;
        try {
            asOfDate = (asOf == null || asOf.isBlank()) ? LocalDate.now() : LocalDate.parse(asOf.trim());
        } catch (DateTimeParseException e) {
            return invalidAsOf();
        }

        try {
            UUID resolved = resolveLoanId(loanId);
            if (resolved == null) return loanNotFound();
            KurReserveResponse response = kurLoanService.reserve(resolved, asOfDate);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
        } catch (AmbiguousLoanException e) {
            return invalidLoanId(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "kur_loan_not_found",
                    "message", e.getMessage()));
        } catch (DataAccessException e) {
            return ledgerUnavailable(e);
        }
    }

    /**
     * Resolves an optional loan id. With no explicit id the single active KUR loan is
     * used; if there are zero or several active loans the endpoint refuses instead of
     * silently picking one.
     */
    private UUID resolveLoanId(String loanId) {
        if (loanId == null || loanId.isBlank()) {
            var active = kurLoanRepository.findByStatusOrderByCreatedAtAsc(KurLoanStatus.ACTIVE);
            if (active.size() == 1) {
                return active.getFirst().getId();
            }
            if (active.isEmpty()) {
                return null;
            }
            throw new AmbiguousLoanException(
                    "Multiple active KUR loans exist; pass loanId explicitly");
        }
        try {
            return UUID.fromString(loanId.trim());
        } catch (IllegalArgumentException e) {
            throw new AmbiguousLoanException("loanId must be a UUID");
        }
    }

    /** A request-level selection problem, not a missing loan. */
    private static final class AmbiguousLoanException extends RuntimeException {
        private AmbiguousLoanException(String message) {
            super(message);
        }
    }

    private ResponseEntity<?> invalidLoanId(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_request",
                "message", message));
    }

    private ResponseEntity<?> invalidAsOf() {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_request",
                "message", "asOf must be YYYY-MM-DD"));
    }

    private ResponseEntity<?> loanNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "kur_loan_not_found",
                "message", "No active KUR loan is recorded"));
    }

    private ResponseEntity<?> ledgerUnavailable(DataAccessException e) {
        log.error("FASTERNET KUR: ledger is unavailable: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "ledger_unavailable",
                "message", "The accounting ledger is currently unavailable"));
    }
}
