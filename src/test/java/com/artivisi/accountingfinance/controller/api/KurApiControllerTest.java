package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.security.CspNonceFilter;
import com.artivisi.accountingfinance.service.fasternet.KurLoanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the read-only KUR endpoints in
 * {@code docs/integration/dashboard-contract.md} §3.2/§3.3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "dashboard", authorities = {"SCOPE_analysis:read", "REPORT_VIEW"})
@DisplayName("FASTERNET KUR API")
class KurApiControllerTest {

    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 21);
    private static final BigDecimal INSTALLMENT = new BigDecimal("2757500.00");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private KurLoanService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean(CspNonceFilter.class))
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("schedule returns the contract envelope with lowercase status and string money")
    void scheduleReturnsContractEnvelope() throws Exception {
        KurLoan loan = createLoan(null);
        service.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, INSTALLMENT, null, null, null,
                KurScheduleSourceKind.LENDER_SCHEDULE, "bri-row-1"));

        mockMvc.perform(get("/api/fasternet/v1/kur/schedule")
                        .param("asOf", "2026-10-24")
                        .param("loanId", loan.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.contractVersion").value("fasternet.dashboard/v1"))
                .andExpect(jsonPath("$.currency").value("IDR"))
                .andExpect(jsonPath("$.loan.provenance").value("founder-estimate"))
                .andExpect(jsonPath("$.loan.reference").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.loan.agreementAttached").value(false))
                .andExpect(jsonPath("$.installments[0].number").value(1))
                .andExpect(jsonPath("$.installments[0].amount").value("2757500.00"))
                .andExpect(jsonPath("$.installments[0].status").value("unpaid"))
                .andExpect(jsonPath("$.installments[0].source.kind").value("lender-schedule"))
                .andExpect(jsonPath("$.missing").isArray());
    }

    @Test
    @DisplayName("schedule uses the single active loan when loanId is omitted")
    void scheduleDefaultsToSingleActiveLoan() throws Exception {
        createLoan(null);

        mockMvc.perform(get("/api/fasternet/v1/kur/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.lender").value("BRI"));
    }

    @Test
    @DisplayName("ambiguous active loans require an explicit loanId")
    void ambiguousActiveLoansAreRejected() throws Exception {
        createLoan(null);
        createLoan("KUR-2");

        mockMvc.perform(get("/api/fasternet/v1/kur/schedule"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("reserve returns the gap figures without treating the reserve as a payment")
    void reserveReturnsGapFigures() throws Exception {
        KurLoan loan = createLoan(null);
        service.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, INSTALLMENT, null, null, null,
                KurScheduleSourceKind.LENDER_SCHEDULE, "bri-row-1"));
        service.recordReserve(loan.getId(), new KurLoanService.ReserveCommand(
                LocalDate.of(2026, 10, 25), KurReserveEntryKind.EARMARK,
                new BigDecimal("1000000.00"), "sisihkan", "reserve-1"));

        mockMvc.perform(get("/api/fasternet/v1/kur/reserve")
                        .param("asOf", "2026-10-31")
                        .param("loanId", loan.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figures['reserve.earmarked'].value").value("1000000.00"))
                .andExpect(jsonPath("$.figures['reserve.gap'].value").value("1757500.00"))
                .andExpect(jsonPath("$.figures['obligation.nextDue'].value").value("2757500.00"))
                .andExpect(jsonPath("$.invariant").value("reserving funds never marks an installment paid"));
    }

    @Test
    @DisplayName("an unknown loan is 404")
    void unknownLoanIs404() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/schedule")
                        .param("loanId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("kur_loan_not_found"));
    }

    @Test
    @DisplayName("a malformed loanId is 400, not 404")
    void malformedLoanIdIs400() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/schedule").param("loanId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("a malformed asOf is 400")
    void malformedAsOfIs400() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/schedule").param("asOf", "24-10-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("is GET-only; POST is not allowed")
    void isGetOnly() throws Exception {
        mockMvc.perform(post("/api/fasternet/v1/kur/schedule").with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("unauthenticated request is 401")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/schedule"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "no-report", authorities = {"SCOPE_accounts:read"})
    @DisplayName("token without REPORT_VIEW is 403")
    void missingReportViewIs403() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/schedule"))
                .andExpect(status().isForbidden());
    }

    private KurLoan createLoan(String reference) {
        return service.createLoan(new KurLoanService.CreateLoanCommand(
                "BRI", "KUR", reference, "FASTERNET", "Indra",
                new BigDecimal("99270000.00"), LocalDate.of(2026, 10, 21), 36, INSTALLMENT,
                "MONTHLY", 36, FIRST_DUE, 21, LocalDate.of(2029, 10, 21),
                null, null, null, KurLoanProvenance.FOUNDER_ESTIMATE, null));
    }
}
