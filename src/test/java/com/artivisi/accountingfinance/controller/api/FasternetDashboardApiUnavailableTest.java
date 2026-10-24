package com.artivisi.accountingfinance.controller.api;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.service.fasternet.FasternetDashboardReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@WithMockUser(username = "dashboard", authorities = {"SCOPE_analysis:read", "REPORT_VIEW"})
@DisplayName("FASTERNET overview API when the ledger is down")
class FasternetDashboardApiUnavailableTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private FasternetDashboardReadService readService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("returns 503 ledger_unavailable instead of stale or fabricated values")
    void ledgerDownIs503() throws Exception {
        when(readService.buildOverview(any(LocalDate.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(get("/api/fasternet/v1/overview?asOf=2026-10-24"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ledger_unavailable"))
                .andExpect(jsonPath("$.message").isString());
    }
}
