package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.entity.ChartOfAccount;
import com.artivisi.accountingfinance.entity.Document;
import com.artivisi.accountingfinance.entity.JournalEntry;
import com.artivisi.accountingfinance.entity.JournalTemplate;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.Transaction;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.enums.TransactionStatus;
import com.artivisi.accountingfinance.repository.ChartOfAccountRepository;
import com.artivisi.accountingfinance.repository.DocumentRepository;
import com.artivisi.accountingfinance.repository.JournalEntryRepository;
import com.artivisi.accountingfinance.repository.JournalTemplateRepository;
import com.artivisi.accountingfinance.repository.TransactionRepository;
import com.artivisi.accountingfinance.security.CspNonceFilter;
import com.artivisi.accountingfinance.service.fasternet.KurAlertPolicyService;
import com.artivisi.accountingfinance.service.fasternet.KurLoanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the FAS-96 KUR repayment alert endpoint and the
 * approval-gated threshold policy write.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "dashboard", authorities = {"SCOPE_analysis:read", "REPORT_VIEW"})
@DisplayName("FASTERNET KUR alert API")
class KurAlertApiControllerTest {

    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 21);
    private static final BigDecimal INSTALLMENT = new BigDecimal("2757500.00");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private KurLoanService loanService;

    @Autowired
    private KurAlertPolicyService policyService;

    @Autowired
    private ChartOfAccountRepository chartOfAccountRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JournalTemplateRepository journalTemplateRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean(CspNonceFilter.class))
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("alert returns state, reason, shortfall, due date, assumptions and policy")
    void alertReturnsContractEnvelope() throws Exception {
        KurLoan loan = createLoan();
        schedule(loan);
        reserve(loan, INSTALLMENT);
        configure(loan, "0.00", "2757500.00", "0.00", "2000000.00", "2500000.00");
        postCash(new BigDecimal("1000000000.00"));

        mockMvc.perform(get("/api/fasternet/v1/kur/alert")
                        .param("asOf", "2026-10-24")
                        .param("loanId", loan.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.contractVersion").value("fasternet.dashboard/v1"))
                .andExpect(jsonPath("$.currency").value("IDR"))
                .andExpect(jsonPath("$.alert.state").value("green"))
                .andExpect(jsonPath("$.alert.severity").value("ok"))
                .andExpect(jsonPath("$.alert.reason").isNotEmpty())
                .andExpect(jsonPath("$.alert.shortfall.amount").value("0.00"))
                .andExpect(jsonPath("$.alert.dueDate").value("2026-11-21"))
                .andExpect(jsonPath("$.alert.daysRemaining").value(28))
                .andExpect(jsonPath("$.alert.installmentNumber").value(1))
                .andExpect(jsonPath("$.alert.payment.status").value("unpaid"))
                .andExpect(jsonPath("$.alert.payment.countsAsPaid").value(false))
                .andExpect(jsonPath("$.alert.policy.status").value("configured"))
                .andExpect(jsonPath("$.alert.policy.approvalReference").value("FAS-96-TEST"))
                .andExpect(jsonPath("$.alert.assumptions", not(empty())))
                .andExpect(jsonPath("$.alert.invariant")
                        .value("a reserved amount is never a completed payment"));
    }

    @Test
    @DisplayName("alert reports unconfigured when no approved threshold policy exists")
    void alertIsUnconfiguredWithoutPolicy() throws Exception {
        KurLoan loan = createLoan();
        schedule(loan);
        reserve(loan, INSTALLMENT);

        mockMvc.perform(get("/api/fasternet/v1/kur/alert")
                        .param("asOf", "2026-10-24")
                        .param("loanId", loan.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.state").value("unconfigured"))
                .andExpect(jsonPath("$.alert.policy.status").value("unconfigured"));
    }

    @Test
    @WithMockUser(username = "finance",
            authorities = {"SCOPE_analysis:read", "REPORT_VIEW", "SCOPE_kur-alert:write", "ALERT_CONFIG"})
    @DisplayName("recording an approved policy makes the alert evaluable")
    void putPolicyRecordsApprovalAndAlertUsesIt() throws Exception {
        KurLoan loan = createLoan();
        schedule(loan);
        reserve(loan, INSTALLMENT);
        postCash(new BigDecimal("1000000000.00"));

        mockMvc.perform(put("/api/fasternet/v1/kur/alert-policy")
                        .param("loanId", loan.getId().toString())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "minimumCash": "0.00",
                                  "targetReserve": "2757500.00",
                                  "fundingPaceTolerancePercent": "0.00",
                                  "orangeShortfall": "2000000.00",
                                  "redShortfall": "2500000.00",
                                  "approvalReference": "FAS-96-TEST",
                                  "approvedBy": "CEO + Finance Controller"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.loanId").value(loan.getId().toString()))
                .andExpect(jsonPath("$.policy.status").value("configured"))
                .andExpect(jsonPath("$.policy.approvalReference").value("FAS-96-TEST"));

        mockMvc.perform(get("/api/fasternet/v1/kur/alert")
                        .param("asOf", "2026-10-24")
                        .param("loanId", loan.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.state").value("green"))
                .andExpect(jsonPath("$.alert.policy.status").value("configured"));
    }

    @Test
    @WithMockUser(username = "finance",
            authorities = {"SCOPE_kur-alert:write", "ALERT_CONFIG"})
    @DisplayName("a policy without an approval reference is rejected")
    void putPolicyWithoutApprovalReferenceIs400() throws Exception {
        KurLoan loan = createLoan();

        mockMvc.perform(put("/api/fasternet/v1/kur/alert-policy")
                        .param("loanId", loan.getId().toString())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "minimumCash": "0.00",
                                  "targetReserve": "2757500.00",
                                  "fundingPaceTolerancePercent": "0.00",
                                  "orangeShortfall": "2000000.00",
                                  "redShortfall": "2500000.00",
                                  "approvedBy": "CEO + Finance Controller"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("approvalReference")));
    }

    @Test
    @DisplayName("the read scope cannot record a threshold policy")
    void putPolicyRequiresWriteScope() throws Exception {
        KurLoan loan = createLoan();

        mockMvc.perform(put("/api/fasternet/v1/kur/alert-policy")
                        .param("loanId", loan.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("no active loan is 404, not a fabricated alert")
    void alertWithoutLoanIs404() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/alert"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("kur_loan_not_found"));
    }

    @Test
    @DisplayName("a malformed asOf is 400")
    void malformedAsOfIs400() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/alert").param("asOf", "24-10-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("a malformed loanId is 400")
    void malformedLoanIdIs400() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/alert").param("loanId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("the alert endpoint is GET-only")
    void alertIsGetOnly() throws Exception {
        mockMvc.perform(post("/api/fasternet/v1/kur/alert")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("unauthenticated request is 401")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/alert"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "no-report", authorities = {"SCOPE_accounts:read"})
    @DisplayName("token without analysis:read is 403")
    void missingReportViewIs403() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/kur/alert"))
                .andExpect(status().isForbidden());
    }

    private KurLoan createLoan() {
        KurLoan loan = loanService.createLoan(new KurLoanService.CreateLoanCommand(
                "BRI", "KUR", null, "FASTERNET", "Indra",
                new BigDecimal("99270000.00"), LocalDate.of(2026, 10, 21), 36, INSTALLMENT,
                "MONTHLY", 36, FIRST_DUE, 21, LocalDate.of(2029, 10, 21),
                null, null, null,
                KurLoanProvenance.FOUNDER_ESTIMATE, null));

        Document agreement = new Document();
        agreement.setFilename("agreement.pdf");
        agreement.setOriginalFilename("agreement.pdf");
        agreement.setContentType("application/pdf");
        agreement.setFileSize(1024L);
        agreement.setStoragePath("2026/10/agreement.pdf");
        agreement.setChecksumSha256("a".repeat(64));
        agreement.setUploadedBy("dashboard");
        agreement = documentRepository.save(agreement);

        Document schedule = new Document();
        schedule.setFilename("schedule.pdf");
        schedule.setOriginalFilename("schedule.pdf");
        schedule.setContentType("application/pdf");
        schedule.setFileSize(1024L);
        schedule.setStoragePath("2026/10/schedule.pdf");
        schedule.setChecksumSha256("b".repeat(64));
        schedule.setUploadedBy("dashboard");
        schedule = documentRepository.save(schedule);

        return loanService.verifyLoan(loan.getId(), agreement.getId(), schedule.getId(),
                "FAS-96 test verification");
    }

    private void schedule(KurLoan loan) {
        loanService.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, INSTALLMENT, null, null, null,
                KurScheduleSourceKind.LENDER_SCHEDULE, "lender-row-1"));
    }

    private void reserve(KurLoan loan, BigDecimal amount) {
        loanService.recordReserve(loan.getId(), new KurLoanService.ReserveCommand(
                LocalDate.of(2026, 10, 22), KurReserveEntryKind.EARMARK, amount,
                "sisihkan angsuran KUR", "reserve-1"));
    }

    private void configure(KurLoan loan, String minimumCash, String targetReserve,
                           String tolerance, String orange, String red) {
        policyService.savePolicy(loan.getId(), new KurAlertPolicyService.PolicyCommand(
                new BigDecimal(minimumCash), new BigDecimal(targetReserve), new BigDecimal(tolerance),
                new BigDecimal(orange), new BigDecimal(red),
                "FAS-96-TEST", "CEO + Finance Controller"));
    }

    private void postCash(BigDecimal amount) {
        ChartOfAccount cash = chartOfAccountRepository.findCashBankAccounts().stream()
                .findFirst().orElseThrow();
        ChartOfAccount revenue = chartOfAccountRepository.findByAccountCode("4.1.01").orElseThrow();
        JournalTemplate template = journalTemplateRepository.findAll().stream().findFirst().orElseThrow();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Transaction transaction = new Transaction();
        transaction.setTransactionNumber("TRX-FAS96-API-" + suffix);
        transaction.setTransactionDate(LocalDate.of(2026, 10, 1));
        transaction.setJournalTemplate(template);
        transaction.setAmount(amount);
        transaction.setDescription("FAS-96 alert API test cash");
        transaction.setStatus(TransactionStatus.POSTED);
        transaction = transactionRepository.save(transaction);

        JournalEntry debit = new JournalEntry();
        debit.setJournalNumber("JRN-FAS96-API-D-" + suffix);
        debit.setTransaction(transaction);
        debit.setAccount(cash);
        debit.setDebitAmount(amount);
        debit.setCreditAmount(BigDecimal.ZERO);

        JournalEntry credit = new JournalEntry();
        credit.setJournalNumber("JRN-FAS96-API-C-" + suffix);
        credit.setTransaction(transaction);
        credit.setAccount(revenue);
        credit.setDebitAmount(BigDecimal.ZERO);
        credit.setCreditAmount(amount);

        journalEntryRepository.saveAll(List.of(debit, credit));
    }
}
