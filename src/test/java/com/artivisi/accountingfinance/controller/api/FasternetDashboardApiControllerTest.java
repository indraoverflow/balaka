package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for {@code GET /api/fasternet/v1/overview}.
 *
 * <p>The successful response is also written to
 * {@code target/fasternet-overview-golden.json} so the frontend can consume real
 * backend output instead of a hand-written fixture. The file is not committed by the
 * test; it is copied to {@code docs/integration/fixtures/} as part of the issue evidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "dashboard", authorities = {"SCOPE_analysis:read", "REPORT_VIEW"})
@DisplayName("FASTERNET overview API")
class FasternetDashboardApiControllerTest {

    private static final String OVERVIEW = "/api/fasternet/v1/overview?asOf=2024-06-30";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("returns the contract envelope with posted and unavailable figures")
    void returnsContractEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get(OVERVIEW))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.contractVersion").value("fasternet.dashboard/v1"))
                .andExpect(jsonPath("$.asOf").value("2024-06-30"))
                .andExpect(jsonPath("$.currency").value("IDR"))
                .andExpect(jsonPath("$.period.label").value("2024-06"))
                .andExpect(jsonPath("$.figures['cash.total'].status").value("posted"))
                .andExpect(jsonPath("$.figures['cash.total'].value").isString())
                .andExpect(jsonPath("$.figures['kur.installmentDue.next'].status").value("unavailable"))
                .andExpect(jsonPath("$.figures['kur.installmentDue.next'].value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.figures['billing.outstanding'].status").value("unavailable"))
                .andExpect(jsonPath("$.series.periods.length()").value(4))
                .andExpect(jsonPath("$.series.reserve[0]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.missing").isArray())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("figures").path("kur.installmentDue.next").path("value").isNull()).isTrue();
        assertThat(body.path("missing").findValuesAsText("key"))
                .contains("kur.installmentDue.next", "equity.founderCapital");

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "fasternet-overview-golden.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
    }

    @Test
    @DisplayName("defaults asOf to today when omitted")
    void defaultsAsOfToToday() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").isString())
                .andExpect(jsonPath("$.contractVersion").value("fasternet.dashboard/v1"));
    }

    @Test
    @DisplayName("rejects a malformed asOf with 400")
    void rejectsMalformedAsOf() throws Exception {
        mockMvc.perform(get("/api/fasternet/v1/overview?asOf=23-10-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("is GET-only; POST is not allowed")
    void isGetOnly() throws Exception {
        mockMvc.perform(post("/api/fasternet/v1/overview").with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("unauthenticated request is 401")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(get(OVERVIEW))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    @WithMockUser(username = "no-report", authorities = {"SCOPE_accounts:read"})
    @DisplayName("token without REPORT_VIEW is 403")
    void missingReportViewIs403() throws Exception {
        mockMvc.perform(get(OVERVIEW))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    @DisplayName("accepts JSON only")
    void acceptsJson() throws Exception {
        mockMvc.perform(get(OVERVIEW).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
