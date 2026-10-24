package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.dto.fasternet.FasternetOverviewResponse;
import com.artivisi.accountingfinance.enums.AccountType;
import com.artivisi.accountingfinance.repository.ChartOfAccountRepository;
import com.artivisi.accountingfinance.repository.CompanyConfigRepository;
import com.artivisi.accountingfinance.repository.FiscalPeriodRepository;
import com.artivisi.accountingfinance.repository.JournalEntryRepository;
import com.artivisi.accountingfinance.service.DashboardService;
import com.artivisi.accountingfinance.service.ReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Series provenance on a chart of accounts with no transaction accounts.
 *
 * <p>When nothing can be derived, {@code series.status} must be {@code unavailable} and
 * every point must be {@code null}. A series must never claim to be {@code posted} just
 * because an empty ledger produced zeros.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FASTERNET dashboard series status")
class FasternetDashboardSeriesStatusTest {

    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;

    @Mock
    private CompanyConfigRepository companyConfigRepository;

    @Mock
    private FiscalPeriodRepository fiscalPeriodRepository;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private DashboardService dashboardService;

    @Mock
    private ReportService reportService;

    @InjectMocks
    private FasternetDashboardReadService readService;

    @Test
    @DisplayName("is unavailable, with null points, when no accounts are configured")
    void unavailableWhenNothingIsDerivable() {
        when(companyConfigRepository.findFirst()).thenReturn(Optional.empty());
        when(chartOfAccountRepository.findCashBankAccounts()).thenReturn(List.of());
        when(chartOfAccountRepository.findByAccountTypeAndActiveOrderByAccountCodeAsc(
                AccountType.REVENUE, true)).thenReturn(List.of());
        when(chartOfAccountRepository.findByAccountTypeAndActiveOrderByAccountCodeAsc(
                AccountType.EXPENSE, true)).thenReturn(List.of());
        when(fiscalPeriodRepository.findByYearAndMonth(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(reportService.generateIncomeStatement(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(incomeStatement());

        FasternetOverviewResponse overview = readService.buildOverview(LocalDate.of(2024, 6, 30));
        FasternetOverviewResponse.Series series = overview.series();

        assertThat(series.status()).isEqualTo("unavailable");
        assertThat(series.periods()).hasSize(4);
        assertThat(series.cash()).containsOnlyNulls();
        assertThat(series.reserve()).containsOnlyNulls();
        assertThat(series.revenue()).containsOnlyNulls();
        assertThat(series.expense()).containsOnlyNulls();
        assertThat(series.cashAccounts()).isEmpty();
        assertThat(overview.figures().get("cash.total").status()).isEqualTo("unavailable");
        assertThat(overview.figures().get("cash.total").value()).isNull();
    }

    private static ReportService.IncomeStatementReport incomeStatement() {
        return new ReportService.IncomeStatementReport(
                LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30),
                List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
