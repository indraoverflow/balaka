package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.dto.fasternet.FasternetOverviewResponse;
import com.artivisi.accountingfinance.entity.ChartOfAccount;
import com.artivisi.accountingfinance.entity.CompanyConfig;
import com.artivisi.accountingfinance.entity.FiscalPeriod;
import com.artivisi.accountingfinance.entity.JournalEntry;
import com.artivisi.accountingfinance.entity.JournalTemplate;
import com.artivisi.accountingfinance.entity.Transaction;
import com.artivisi.accountingfinance.enums.FiscalPeriodStatus;
import com.artivisi.accountingfinance.enums.TransactionStatus;
import com.artivisi.accountingfinance.repository.ChartOfAccountRepository;
import com.artivisi.accountingfinance.repository.CompanyConfigRepository;
import com.artivisi.accountingfinance.repository.FiscalPeriodRepository;
import com.artivisi.accountingfinance.repository.JournalEntryRepository;
import com.artivisi.accountingfinance.repository.JournalTemplateRepository;
import com.artivisi.accountingfinance.repository.TransactionRepository;
import com.artivisi.accountingfinance.service.DashboardService;
import com.artivisi.accountingfinance.service.ReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the FASTERNET overview read layer. Figures must equal the
 * underlying Balaka service output for the same as-of date; nothing may be fabricated.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("FASTERNET dashboard read service")
class FasternetDashboardReadServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2024, 6, 30);
    private static final Set<String> REQUIRED_FIGURE_KEYS = Set.of(
            "cash.total", "revenue.period", "expense.period", "netProfit.period",
            "profitMargin.period", "receivables.total", "payables.total",
            "equity.founderCapital", "kur.installmentDue.next", "kur.reserve.earmarked",
            "kur.reserve.gap", "subscribers.active", "subscribers.new.period",
            "subscribers.cancelled.period", "billing.mrr", "billing.invoiced.period",
            "billing.collected.period", "billing.outstanding", "billing.overdue",
            "kur.nextDue.date", "kur.nextDue.amount", "kur.nextDue.daysRemaining",
            "kur.paid.count", "kur.paid.total", "kur.lastPayment.status");

    @Autowired
    private FasternetDashboardReadService readService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private CompanyConfigRepository companyConfigRepository;

    @Autowired
    private FiscalPeriodRepository fiscalPeriodRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private JournalTemplateRepository journalTemplateRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ChartOfAccountRepository chartOfAccountRepository;

    @Test
    @DisplayName("emits the full envelope and every contract figure key")
    void emitsFullEnvelope() {
        FasternetOverviewResponse overview = readService.buildOverview(AS_OF);

        assertThat(overview.contractVersion()).isEqualTo("fasternet.dashboard/v1");
        assertThat(overview.currency()).isEqualTo("IDR");
        assertThat(overview.asOf()).isEqualTo("2024-06-30");
        assertThat(overview.generatedAt()).endsWith("Z");
        assertThat(overview.period().from()).isEqualTo("2024-06-01");
        assertThat(overview.period().to()).isEqualTo("2024-06-30");
        assertThat(overview.period().label()).isEqualTo("2024-06");
        assertThat(overview.period().status()).isEqualTo("open");
        assertThat(overview.company()).isNotNull();
        assertThat(overview.company().id()).isNotBlank();

        assertThat(overview.figures().keySet()).containsExactlyInAnyOrderElementsOf(REQUIRED_FIGURE_KEYS);
        for (FasternetOverviewResponse.Figure figure : overview.figures().values()) {
            assertThat(figure.key()).isNotBlank();
            assertThat(figure.label()).isNotBlank();
            assertThat(figure.unit()).isIn("IDR", "count", "percent", "text");
            assertThat(figure.source().system()).isEqualTo("balaka");
            assertThat(figure.period()).isNotEmpty();
            if ("unavailable".equals(figure.status())) {
                assertThat(figure.value()).isNull();
                assertThat(overview.missing()).anyMatch(missing -> missing.key().equals(figure.key()));
            } else {
                assertThat(figure.value()).isNotNull();
                assertThat(figure.status()).isEqualTo("posted");
            }
        }
    }

    @Test
    @DisplayName("posted cash, revenue, expense and profit equal the ledger services")
    void postedFiguresEqualLedgerServices() {
        FasternetOverviewResponse overview = readService.buildOverview(AS_OF);
        Map<String, FasternetOverviewResponse.Figure> figures = overview.figures();

        String expectedCash = money(dashboardService.calculateCashBalance(AS_OF));
        assertThat(figures.get("cash.total").value()).isEqualTo(expectedCash);
        assertThat(figures.get("cash.total").status()).isEqualTo("posted");

        ReportService.IncomeStatementReport income = reportService.generateIncomeStatement(
                AS_OF.withDayOfMonth(1), AS_OF);
        assertThat(figures.get("revenue.period").value()).isEqualTo(money(income.totalRevenue()));
        assertThat(figures.get("expense.period").value()).isEqualTo(money(income.totalExpense()));

        BigDecimal netProfit = income.totalRevenue().subtract(income.totalExpense());
        assertThat(figures.get("netProfit.period").value()).isEqualTo(money(netProfit));

        BigDecimal expectedMargin = income.totalRevenue().compareTo(BigDecimal.ZERO) == 0
                ? null
                : netProfit.multiply(BigDecimal.valueOf(100))
                        .divide(income.totalRevenue(), 2, RoundingMode.HALF_UP);
        if (expectedMargin == null) {
            assertThat(figures.get("profitMargin.period").value()).isNull();
        } else {
            assertThat(figures.get("profitMargin.period").value())
                    .isEqualTo(expectedMargin.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }

        CompanyConfig config = companyConfigRepository.findFirst().orElseThrow();
        assertThat(figures.get("receivables.total").value())
                .isEqualTo(money(calculatedBalance(config.getReceivableAccount())));
        assertThat(figures.get("payables.total").value())
                .isEqualTo(money(calculatedBalance(config.getPayableAccount())));
    }

    @Test
    @DisplayName("unavailable figures are explicit and never zero")
    void unavailableFiguresAreExplicit() {
        FasternetOverviewResponse overview = readService.buildOverview(AS_OF);

        for (String key : List.of(
                "equity.founderCapital",
                "kur.installmentDue.next", "kur.nextDue.date", "kur.nextDue.amount",
                "kur.nextDue.daysRemaining", "kur.paid.count", "kur.paid.total",
                "kur.lastPayment.status", "kur.reserve.earmarked", "kur.reserve.gap",
                "subscribers.active", "subscribers.new.period", "subscribers.cancelled.period",
                "billing.mrr", "billing.invoiced.period", "billing.collected.period",
                "billing.outstanding", "billing.overdue")) {
            FasternetOverviewResponse.Figure figure = overview.figures().get(key);
            assertThat(figure.status()).as(key).isEqualTo("unavailable");
            assertThat(figure.value()).as(key).isNull();
            assertThat(figure.source().kind()).as(key).isEqualTo("none");
            assertThat(figure.notes()).as(key).isNotBlank();
        }

        List<String> missingKeys = overview.missing().stream()
                .map(FasternetOverviewResponse.Missing::key)
                .toList();
        assertThat(missingKeys).doesNotHaveDuplicates();
        assertThat(missingKeys).contains("equity.founderCapital", "kur.installmentDue.next",
                "billing.outstanding", "subscribers.active");
        assertThat(overview.missing()).allSatisfy(missing -> {
            assertThat(missing.reason()).isNotBlank();
            assertThat(missing.owner()).isNotBlank();
        });
    }

    @Test
    @DisplayName("series are parallel, decimal strings, and unavailable points are null")
    void seriesAreParallelAndDecimalSafe() {
        FasternetOverviewResponse overview = readService.buildOverview(AS_OF);
        FasternetOverviewResponse.Series series = overview.series();

        assertThat(series.status()).isEqualTo("posted");
        assertThat(series.periods()).hasSize(4);
        assertThat(series.periods().get(3)).isEqualTo("2024-06");
        assertThat(series.cash()).hasSize(4);
        assertThat(series.reserve()).hasSize(4);
        assertThat(series.revenue()).hasSize(4);
        assertThat(series.expense()).hasSize(4);
        assertThat(series.reserve()).containsOnlyNulls();

        assertThat(series.cash()).allSatisfy(value -> assertDecimalString(value));
        assertThat(series.revenue()).allSatisfy(value -> assertDecimalString(value));
        assertThat(series.expense()).allSatisfy(value -> assertDecimalString(value));

        assertThat(series.cash().get(3)).isEqualTo(overview.figures().get("cash.total").value());
        assertThat(series.revenue().get(3)).isEqualTo(overview.figures().get("revenue.period").value());
        assertThat(series.expense().get(3)).isEqualTo(overview.figures().get("expense.period").value());
    }

    @Test
    @DisplayName("per-account cash series sums to cash.total")
    void cashAccountsSumToTotal() {
        FasternetOverviewResponse overview = readService.buildOverview(AS_OF);

        BigDecimal sum = overview.series().cashAccounts().stream()
                .map(account -> new BigDecimal(account.balance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(money(sum)).isEqualTo(overview.figures().get("cash.total").value());
        assertThat(overview.series().cashAccounts()).allSatisfy(account -> {
            assertThat(account.accountCode()).isNotBlank();
            assertThat(account.status()).isEqualTo("posted");
            assertDecimalString(account.balance());
        });
    }

    @Test
    @DisplayName("cash universe is explicit: only Kas/Bank, never AR, prepaid or input VAT")
    void cashUniverseExcludesNonCashCurrentAssets() {
        List<String> cashCodes = chartOfAccountRepository.findCashBankAccounts().stream()
                .map(ChartOfAccount::getAccountCode)
                .toList();
        assertThat(cashCodes).containsExactly("1.1.01", "1.1.02", "1.1.03");

        for (String nonCashCode : List.of("1.1.04", "1.1.05", "1.1.06", "1.1.07", "1.1.08", "1.1.09")) {
            ChartOfAccount account = chartOfAccountRepository.findByAccountCode(nonCashCode).orElseThrow();
            assertThat(account.getIsCashBank()).as(nonCashCode).isFalse();
        }

        FasternetOverviewResponse overview = readService.buildOverview(AS_OF);
        assertThat(overview.series().cashAccounts())
                .extracting(FasternetOverviewResponse.CashAccount::accountCode)
                .doesNotContain("1.1.04", "1.1.05", "1.1.06", "1.1.07", "1.1.08", "1.1.09");
    }

    @Test
    @DisplayName("a nonzero receivable changes receivables, not cash.total or cash.accounts")
    void nonzeroReceivableDoesNotMoveCashTotal() {
        FasternetOverviewResponse before = readService.buildOverview(AS_OF);

        ChartOfAccount receivable = chartOfAccountRepository.findByAccountCode("1.1.04").orElseThrow();
        ChartOfAccount revenue = chartOfAccountRepository.findByAccountCode("4.1.01").orElseThrow();
        JournalTemplate template = journalTemplateRepository
                .findById(UUID.fromString("e0000000-0000-0000-0000-000000000099")).orElseThrow();

        BigDecimal unpaidInvoice = new BigDecimal("7500000.00");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Transaction transaction = new Transaction();
        transaction.setTransactionNumber("TRX-FAS77-AR-" + suffix);
        transaction.setTransactionDate(LocalDate.of(2024, 6, 15));
        transaction.setJournalTemplate(template);
        transaction.setAmount(unpaidInvoice);
        transaction.setDescription("FAS-77 unpaid customer invoice");
        transaction.setStatus(TransactionStatus.POSTED);
        transaction = transactionRepository.save(transaction);

        JournalEntry debit = new JournalEntry();
        debit.setJournalNumber("JRN-FAS77-AR-D-" + suffix);
        debit.setTransaction(transaction);
        debit.setAccount(receivable);
        debit.setDebitAmount(unpaidInvoice);
        debit.setCreditAmount(BigDecimal.ZERO);

        JournalEntry credit = new JournalEntry();
        credit.setJournalNumber("JRN-FAS77-AR-C-" + suffix);
        credit.setTransaction(transaction);
        credit.setAccount(revenue);
        credit.setDebitAmount(BigDecimal.ZERO);
        credit.setCreditAmount(unpaidInvoice);

        journalEntryRepository.saveAll(List.of(debit, credit));

        FasternetOverviewResponse after = readService.buildOverview(AS_OF);

        assertThat(after.figures().get("receivables.total").value()).isEqualTo(money(unpaidInvoice));
        assertThat(after.figures().get("cash.total").value())
                .isEqualTo(before.figures().get("cash.total").value());
        assertThat(after.series().cash().get(3)).isEqualTo(before.series().cash().get(3));
        assertThat(after.series().cashAccounts())
                .extracting(FasternetOverviewResponse.CashAccount::accountCode)
                .doesNotContain("1.1.04");

        BigDecimal cashAccountSum = after.series().cashAccounts().stream()
                .map(account -> new BigDecimal(account.balance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(money(cashAccountSum)).isEqualTo(after.figures().get("cash.total").value());
    }

    @Test
    @DisplayName("profit margin is unavailable, not zero, when revenue is zero")
    void profitMarginUnavailableWhenRevenueZero() {
        FasternetOverviewResponse overview = readService.buildOverview(LocalDate.of(2099, 12, 31));

        assertThat(overview.figures().get("revenue.period").value()).isEqualTo("0.00");
        assertThat(overview.figures().get("profitMargin.period").status()).isEqualTo("unavailable");
        assertThat(overview.figures().get("profitMargin.period").value()).isNull();
        assertThat(overview.missing()).anyMatch(missing -> missing.key().equals("profitMargin.period"));
    }

    @Test
    @DisplayName("closed fiscal period is reported as closed")
    void closedPeriodIsReported() {
        YearMonth month = YearMonth.from(AS_OF);
        FiscalPeriod period = new FiscalPeriod();
        period.setYear(month.getYear());
        period.setMonth(month.getMonthValue());
        period.setStatus(FiscalPeriodStatus.MONTH_CLOSED);
        fiscalPeriodRepository.save(period);

        FasternetOverviewResponse overview = readService.buildOverview(AS_OF);

        assertThat(overview.period().status()).isEqualTo("closed");
    }

    private BigDecimal calculatedBalance(ChartOfAccount account) {
        if (account == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal debit = journalEntryRepository.sumDebitByAccountAndDateRange(
                account.getId(), LocalDate.of(1900, 1, 1), AS_OF);
        BigDecimal credit = journalEntryRepository.sumCreditByAccountAndDateRange(
                account.getId(), LocalDate.of(1900, 1, 1), AS_OF);
        return account.getNormalBalance() == com.artivisi.accountingfinance.enums.NormalBalance.DEBIT
                ? debit.subtract(credit)
                : credit.subtract(debit);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void assertDecimalString(String value) {
        assertThat(value).isNotNull();
        assertThat(value).matches("-?\\d+\\.\\d{2}");
    }
}
