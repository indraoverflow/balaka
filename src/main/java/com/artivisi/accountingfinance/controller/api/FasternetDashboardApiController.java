package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.dto.fasternet.FasternetOverviewResponse;
import com.artivisi.accountingfinance.service.fasternet.FasternetDashboardReadService;
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

/**
 * Read-only FASTERNET dashboard API ({@code fasternet.dashboard/v1}).
 *
 * <p>GET-only by design: no writes, no payment initiation, no bank credentials. The
 * caller must present a bearer token whose {@code analysis:read} scope resolves to
 * {@code REPORT_VIEW}. Every value is derived from POSTED ledger data, and missing
 * data is reported as {@code unavailable}, never as zero.
 */
@RestController
@RequestMapping("/api/fasternet/v1")
@Tag(name = "FASTERNET Dashboard", description = "Read-only dashboard figures derived from the Balaka ledger")
@PreAuthorize("hasAuthority('SCOPE_analysis:read')")
@RequiredArgsConstructor
@Slf4j
public class FasternetDashboardApiController {

    private final FasternetDashboardReadService readService;

    @GetMapping("/overview")
    @Operation(summary = "Dashboard overview for an effective date",
            description = "Returns the fasternet.dashboard/v1 envelope. asOf defaults to today when omitted. "
                    + "Figures that cannot be derived are returned with value=null and status=unavailable.")
    @ApiResponse(responseCode = "200", description = "Overview envelope")
    @ApiResponse(responseCode = "400", description = "Invalid asOf")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    @ApiResponse(responseCode = "403", description = "Token lacks REPORT_VIEW (analysis:read)")
    @ApiResponse(responseCode = "503", description = "Ledger database unavailable")
    public ResponseEntity<?> overview(@RequestParam(name = "asOf", required = false) String asOf) {
        LocalDate asOfDate;
        try {
            asOfDate = (asOf == null || asOf.isBlank()) ? LocalDate.now() : LocalDate.parse(asOf.trim());
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_request",
                    "message", "asOf must be YYYY-MM-DD"));
        }

        try {
            FasternetOverviewResponse response = readService.buildOverview(asOfDate);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(response);
        } catch (DataAccessException e) {
            log.error("FASTERNET overview: ledger is unavailable: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "ledger_unavailable",
                    "message", "The accounting ledger is currently unavailable"));
        }
    }
}
