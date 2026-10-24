package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.entity.Document;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.KurPayment;
import com.artivisi.accountingfinance.entity.SecurityAuditLog;
import com.artivisi.accountingfinance.enums.AuditEventType;
import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.enums.Role;
import com.artivisi.accountingfinance.repository.DocumentRepository;
import com.artivisi.accountingfinance.repository.KurInstallmentRepository;
import com.artivisi.accountingfinance.repository.KurLoanRepository;
import com.artivisi.accountingfinance.repository.KurPaymentRepository;
import com.artivisi.accountingfinance.repository.SecurityAuditLogRepository;
import com.artivisi.accountingfinance.security.CspNonceFilter;
import com.artivisi.accountingfinance.security.Permission;
import com.artivisi.accountingfinance.service.fasternet.KurLoanService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FAS-95: authorization, validation, idempotency, audit and money invariants for the
 * KUR write/admin HTTP path. The write authority is separate from {@code analysis:read};
 * {@code ADMIN} does not hold it by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "finance", authorities = {
        "SCOPE_kur:write", "KUR_WRITE", "SCOPE_analysis:read", "REPORT_VIEW"})
@DisplayName("FASTERNET KUR write API")
class KurWriteApiControllerTest {

    private static final LocalDate DISBURSEMENT = LocalDate.of(2026, 10, 21);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 21);
    private static final BigDecimal INSTALLMENT = new BigDecimal("2757500.00");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private KurLoanService service;
    @Autowired private KurLoanRepository loanRepository;
    @Autowired private KurInstallmentRepository installmentRepository;
    @Autowired private KurPaymentRepository paymentRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private SecurityAuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean(CspNonceFilter.class))
                .apply(springSecurity())
                .build();
    }

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a technical ADMIN role does not receive KUR_WRITE")
    void adminDoesNotHoldKurWriteByDefault() {
        assertThat(Permission.getPermissionsForRole(Role.ADMIN))
                .doesNotContain(Permission.KUR_WRITE, Permission.KUR_VERIFY);
        assertThat(Permission.getPermissionsForRole(Role.FINANCE_CONTROLLER))
                .contains(Permission.KUR_WRITE, Permission.KUR_VERIFY);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("unauthenticated write is 401")
    void unauthenticatedWriteIs401() throws Exception {
        mockMvc.perform(post("/api/fasternet/v1/kur/loans").with(csrf())
                        .contentType("application/json")
                        .content(createLoanJson(null, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "analyst", authorities = {"SCOPE_analysis:read", "REPORT_VIEW"})
    @DisplayName("analysis:read alone cannot write KUR data")
    void analysisReadCannotWrite() throws Exception {
        mockMvc.perform(post("/api/fasternet/v1/kur/loans").with(csrf())
                        .contentType("application/json")
                        .content(createLoanJson(null, null)))
                .andExpect(status().isForbidden());
        assertThat(loanRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Loan master
    // ------------------------------------------------------------------

    @Test
    @DisplayName("creating a loan always stores founder-estimate and audits the actor/record")
    void createLoanDefaultsToFounderEstimateAndIsAudited() throws Exception {
        mockMvc.perform(post("/api/fasternet/v1/kur/loans").with(csrf())
                        .contentType("application/json")
                        .content(createLoanJson(null, null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.provenance").value("founder-estimate"))
                .andExpect(jsonPath("$.lender").value("BRI"))
                .andExpect(jsonPath("$.loanReference").doesNotExist());

        flushAndClear();
        KurLoan loan = loanRepository.findAll().getFirst();
        assertThat(loan.getProvenance()).isEqualTo(KurLoanProvenance.FOUNDER_ESTIMATE);

        List<SecurityAuditLog> events = audits(AuditEventType.KUR_LOAN_CREATE);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getUsername()).isEqualTo("finance");
        assertThat(events.getFirst().getDetails())
                .contains(loan.getId().toString())
                .contains("provenance=FOUNDER_ESTIMATE")
                .contains("principal=99270000.00");
    }

    @Test
    @DisplayName("a supplied provenance is not trusted: the write path still creates founder-estimate")
    void suppliedProvenanceIsNotTrusted() throws Exception {
        String body = createLoanJson(null, null).replace("{", "{\"provenance\":\"VERIFIED\",");
        mockMvc.perform(post("/api/fasternet/v1/kur/loans").with(csrf())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provenance").value("founder-estimate"));
    }

    @Test
    @DisplayName("a verification requires KUR_VERIFY, both documents and a reason")
    void verificationRequiresAuthorityAndDocuments() throws Exception {
        KurLoan loan = createLoan(null, null);

        mockMvc.perform(post("/api/fasternet/v1/kur/loans/" + loan.getId() + "/verify").with(csrf())
                        .contentType("application/json")
                        .content("{\"reason\":\"agreement reviewed\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/fasternet/v1/kur/loans/" + loan.getId() + "/verify").with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("finance").authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_kur:verify"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("KUR_VERIFY")))
                        .contentType("application/json")
                        .content("{\"reason\":\"agreement reviewed\"}"))
                .andExpect(status().isBadRequest());

        Document agreement = document("agreement.pdf");
        Document schedule = document("schedule.pdf");
        mockMvc.perform(post("/api/fasternet/v1/kur/loans/" + loan.getId() + "/verify").with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("finance").authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_kur:verify"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("KUR_VERIFY")))
                        .contentType("application/json")
                        .content("{\"agreementDocumentId\":\"" + agreement.getId()
                                + "\",\"scheduleDocumentId\":\"" + schedule.getId()
                                + "\",\"reason\":\"signed KUR agreement and BRI schedule reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance").value("verified"))
                .andExpect(jsonPath("$.agreementDocumentId").value(agreement.getId().toString()))
                .andExpect(jsonPath("$.scheduleDocumentId").value(schedule.getId().toString()));

        flushAndClear();
        KurLoan verified = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(verified.getProvenance()).isEqualTo(KurLoanProvenance.VERIFIED);
        List<SecurityAuditLog> events = audits(AuditEventType.KUR_LOAN_VERIFY);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getDetails())
                .contains("provenance=FOUNDER_ESTIMATE->VERIFIED")
                .contains("signed KUR agreement and BRI schedule reviewed");
    }

    @Test
    @DisplayName("a document upload returns a usable document id for the loan")
    void documentUploadReturnsId() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "kredit-ur.pdf", "application/pdf",
                "%PDF-1.4\nKUR agreement\n".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/fasternet/v1/kur/documents").file(file).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();

        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(documentRepository.findById(UUID.fromString(id))).isPresent();
    }

    // ------------------------------------------------------------------
    // Schedule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("schedule import is idempotent, audits the source reference and rejects changed values")
    void scheduleImportIsIdempotentAndRejectsChangedValues() throws Exception {
        KurLoan loan = createLoan(null, null);
        String body = scheduleJson(1, "2026-11-21", "2757500.00", "bri-row-1");

        MvcResult first = mockMvc.perform(post("/api/fasternet/v1/kur/loans/" + loan.getId() + "/schedule")
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.amount").value("2757500.00"))
                .andExpect(jsonPath("$.sourceKind").value("lender-schedule"))
                .andReturn();
        String firstId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/fasternet/v1/kur/loans/" + loan.getId() + "/schedule")
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));

        assertThat(installmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loan.getId())).hasSize(1);

        mockMvc.perform(post("/api/fasternet/v1/kur/loans/" + loan.getId() + "/schedule")
                        .with(csrf()).contentType("application/json")
                        .content(scheduleJson(1, "2026-11-21", "2000000.00", "bri-row-1")))
                .andExpect(status().isConflict());

        flushAndClear();
        List<SecurityAuditLog> events = audits(AuditEventType.KUR_SCHEDULE_IMPORT);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getDetails())
                .contains("installmentId=" + firstId)
                .contains("sourceRef=bri-row-1");
    }

    // ------------------------------------------------------------------
    // Payments
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a payment requires evidence, is idempotent, supports partial/overpay and reverses with a reason")
    void paymentLifecycle() throws Exception {
        KurLoan loan = createLoan(null, null);
        KurInstallment installment = service.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, INSTALLMENT, null, null, null,
                KurScheduleSourceKind.LENDER_SCHEDULE, "bri-row-1"));

        mockMvc.perform(post(paymentPath(loan, installment)).with(csrf())
                        .contentType("application/json")
                        .content("{\"paymentDate\":\"2026-11-10\",\"amount\":1000000.00,"
                                + "\"bankRef\":\"BRI-REF\",\"evidenceRef\":\"  \",\"sourceRef\":\"trf-1\"}"))
                .andExpect(status().isBadRequest());

        MvcResult partial = mockMvc.perform(post(paymentPath(loan, installment)).with(csrf())
                        .contentType("application/json")
                        .content(paymentJson("2026-11-10", "1000000.00", "trf-1")))
                .andExpect(status().isCreated())
                .andReturn();
        String partialId = com.jayway.jsonpath.JsonPath.read(partial.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post(paymentPath(loan, installment)).with(csrf())
                        .contentType("application/json")
                        .content(paymentJson("2026-11-10", "1000000.00", "trf-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(partialId));

        flushAndClear();
        KurInstallment afterPartial = installmentRepository.findById(installment.getId()).orElseThrow();
        assertThat(afterPartial.getPaidAmount()).isEqualByComparingTo("1000000.00");
        assertThat(afterPartial.getStatus()).isEqualTo(KurInstallmentStatus.PARTIAL);

        mockMvc.perform(post(paymentPath(loan, installment)).with(csrf())
                        .contentType("application/json")
                        .content(paymentJson("2026-11-20", "2000000.00", "trf-2")))
                .andExpect(status().isCreated());

        flushAndClear();
        KurInstallment afterOver = installmentRepository.findById(installment.getId()).orElseThrow();
        assertThat(afterOver.getPaidAmount()).isEqualByComparingTo("3000000.00");
        assertThat(afterOver.getStatus()).isEqualTo(KurInstallmentStatus.OVERPAID);

        mockMvc.perform(post("/api/fasternet/v1/kur/loans/" + loan.getId() + "/payments/" + partialId + "/reverse")
                        .with(csrf()).contentType("application/json")
                        .content("{\"reason\":\"salah transfer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversalReason").value("salah transfer"));

        flushAndClear();
        KurPayment reversed = paymentRepository.findById(UUID.fromString(partialId)).orElseThrow();
        assertThat(reversed.isReversed()).isTrue();
        assertThat(paymentRepository.findById(UUID.fromString(partialId))).isPresent();
        KurInstallment afterReverse = installmentRepository.findById(installment.getId()).orElseThrow();
        assertThat(afterReverse.getPaidAmount()).isEqualByComparingTo("2000000.00");
        assertThat(afterReverse.getStatus()).isEqualTo(KurInstallmentStatus.PARTIAL);

        assertThat(audits(AuditEventType.KUR_PAYMENT_RECORD)).hasSize(2);
        List<SecurityAuditLog> reversals = audits(AuditEventType.KUR_PAYMENT_REVERSE);
        assertThat(reversals).hasSize(1);
        assertThat(reversals.getFirst().getDetails())
                .contains("reason=salah transfer")
                .contains("installmentStatus=OVERPAID->PARTIAL");
    }

    @Test
    @DisplayName("a payment on an installment belonging to another loan is rejected")
    void paymentOnWrongLoanIsRejected() throws Exception {
        KurLoan loan = createLoan(null, null);
        KurLoan other = createLoan("KUR-2", null);
        KurInstallment installment = service.importScheduleRow(other.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, INSTALLMENT, null, null, null,
                KurScheduleSourceKind.LENDER_SCHEDULE, "bri-row-1"));

        mockMvc.perform(post(paymentPath(loan, installment)).with(csrf())
                        .contentType("application/json")
                        .content(paymentJson("2026-11-10", "1000000.00", "trf-1")))
                .andExpect(status().isBadRequest());
        assertThat(paymentRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Reserve
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reserve release cannot go negative, replay is idempotent, and reserving never marks paid")
    void reserveMovements() throws Exception {
        KurLoan loan = createLoan(null, null);
        KurInstallment installment = service.importScheduleRow(loan.getId(), new KurLoanService.ScheduleRow(
                1, FIRST_DUE, INSTALLMENT, null, null, null,
                KurScheduleSourceKind.LENDER_SCHEDULE, "bri-row-1"));

        mockMvc.perform(post(reservePath(loan)).with(csrf())
                        .contentType("application/json")
                        .content(reserveJson("EARMARK", "1000000.00", "reserve-1")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(reservePath(loan)).with(csrf())
                        .contentType("application/json")
                        .content(reserveJson("RELEASE", "1500000.00", "reserve-2")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(reservePath(loan)).with(csrf())
                        .contentType("application/json")
                        .content(reserveJson("SEPARATE_ACCOUNT", "500000.00", "reserve-3")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(reservePath(loan)).with(csrf())
                        .contentType("application/json")
                        .content(reserveJson("SEPARATE_ACCOUNT", "500000.00", "reserve-3")))
                .andExpect(status().isOk());

        flushAndClear();
        KurInstallment unchanged = installmentRepository.findById(installment.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(KurInstallmentStatus.UNPAID);
        assertThat(unchanged.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(audits(AuditEventType.KUR_RESERVE_RECORD)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private KurLoan createLoan(String reference, UUID agreementDocumentId) {
        return service.createLoan(new KurLoanService.CreateLoanCommand(
                "BRI", "KUR", reference, "FASTERNET", "Indra",
                new BigDecimal("99270000.00"), DISBURSEMENT, 36, INSTALLMENT,
                "MONTHLY", 36, FIRST_DUE, 21, LocalDate.of(2029, 10, 21),
                null, agreementDocumentId, null, KurLoanProvenance.FOUNDER_ESTIMATE, null));
    }

    private Document document(String filename) {
        Document document = new Document();
        document.setFilename(filename);
        document.setOriginalFilename(filename);
        document.setContentType("application/pdf");
        document.setFileSize(1024L);
        document.setStoragePath("2026/10/" + filename);
        document.setChecksumSha256("a".repeat(64));
        document.setUploadedBy("finance");
        return documentRepository.save(document);
    }

    private static String createLoanJson(UUID agreementDocumentId, UUID scheduleDocumentId) {
        return "{\"lender\":\"BRI\",\"product\":\"KUR\",\"borrowerName\":\"FASTERNET\","
                + "\"borrowerLegalOwner\":\"Indra\",\"principalOriginal\":99270000.00,"
                + "\"disbursementDate\":\"2026-10-21\",\"termMonths\":36,"
                + "\"installmentContractual\":2757500.00,\"installmentFrequency\":\"MONTHLY\","
                + "\"installmentCount\":36,\"firstDueDate\":\"2026-11-21\",\"monthlyDueDay\":21,"
                + "\"maturityDate\":\"2029-10-21\""
                + (agreementDocumentId == null ? "" : ",\"agreementDocumentId\":\"" + agreementDocumentId + "\"")
                + (scheduleDocumentId == null ? "" : ",\"scheduleDocumentId\":\"" + scheduleDocumentId + "\"")
                + "}";
    }

    private static String scheduleJson(int number, String dueDate, String amount, String sourceRef) {
        return "{\"installmentNumber\":" + number
                + ",\"dueDate\":\"" + dueDate + "\""
                + ",\"amount\":" + amount
                + ",\"sourceKind\":\"LENDER_SCHEDULE\""
                + ",\"sourceRef\":\"" + sourceRef + "\"}";
    }

    private static String paymentJson(String date, String amount, String sourceRef) {
        return "{\"paymentDate\":\"" + date + "\""
                + ",\"amount\":" + amount
                + ",\"bankRef\":\"BRI-TRF\""
                + ",\"evidenceRef\":\"bukti-" + sourceRef + "\""
                + ",\"sourceRef\":\"" + sourceRef + "\"}";
    }

    private static String reserveJson(String kind, String amount, String sourceRef) {
        return "{\"entryDate\":\"2026-10-25\""
                + ",\"kind\":\"" + kind + "\""
                + ",\"amount\":" + amount
                + ",\"description\":\"reserve test\""
                + ",\"sourceRef\":\"" + sourceRef + "\"}";
    }

    private static String paymentPath(KurLoan loan, KurInstallment installment) {
        return "/api/fasternet/v1/kur/loans/" + loan.getId()
                + "/installments/" + installment.getId() + "/payments";
    }

    private static String reservePath(KurLoan loan) {
        return "/api/fasternet/v1/kur/loans/" + loan.getId() + "/reserve";
    }

    private List<SecurityAuditLog> audits(AuditEventType type) {
        return auditLogRepository.findByEventType(type, Pageable.ofSize(100)).getContent();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
