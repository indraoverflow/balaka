package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.dto.fasternet.FasternetOverviewResponse;
import com.artivisi.accountingfinance.entity.ChartOfAccount;
import com.artivisi.accountingfinance.entity.CompanyConfig;
import com.artivisi.accountingfinance.entity.FiscalPeriod;
import com.artivisi.accountingfinance.enums.AccountType;
import com.artivisi.accountingfinance.enums.FiscalPeriodStatus;
import com.artivisi.accountingfinance.enums.NormalBalance;
import com.artivisi.accountingfinance.repository.ChartOfAccountRepository;
import com.artivisi.accountingfinance.repository.CompanyConfigRepository;
import com.artivisi.accountingfinance.repository.FiscalPeriodRepository;
import com.artivisi.accountingfinance.repository.JournalEntryRepository;
import com.artivisi.accountingfinance.service.DashboardService;
import com.artivisi.accountingfinance.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Composes the FASTERNET dashboard overview from Balaka's posted ledger.
 *
 * <p>This service owns no balances. Cash, revenue, expense, receivable and payable
 * figures come from the existing ledger services/repositories; the read layer only
 * selects, formats and annotates them with provenance. Figures that cannot be derived
 * stay explicitly {@code unavailable}; they are never replaced with zero.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FasternetDashboardReadService {

    public static final String CONTRACT_VERSION = "fasternet.dashboard/v1";
    public static final String CURRENCY = "IDR";

    private static final LocalDate LEDGER_EPOCH = LocalDate.of(1900, 1, 1);
    private static final int SERIES_MONTHS = 4;
    private static final String SOURCE_SYSTEM = "balaka";
    private static final String KIND_LEDGER = "ledger";
    private static final String KIND_NONE = "none";
    private static final String STATUS_POSTED = "posted";
    private static final String STATUS_UNAVAILABLE = "unavailable";
    private static final String UNIT_IDR = "IDR";
    private static final String UNIT_COUNT = "count";
    private static final String UNIT_PERCENT = "percent";
    private static final String UNIT_TEXT = "text";

    private final ChartOfAccountRepository chartOfAccountRepository;
    private final CompanyConfigRepository companyConfigRepository;
    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DashboardService dashboardService;
    private final ReportService reportService;

    public FasternetOverviewResponse buildOverview(LocalDate asOf) {
        YearMonth month = YearMonth.from(asOf);
        LocalDate periodStart = month.atDay(1);
        LocalDate periodEnd = month.atEndOfMonth();

        List<FasternetOverviewResponse.Missing> missing = new ArrayList<>();
        Map<String, FasternetOverviewResponse.Figure> figures = new LinkedHashMap<>();

        CompanyConfig config = companyConfigRepository.findFirst().orElse(null);
        if (config == null) {
            missing.add(new FasternetOverviewResponse.Missing(
                    "company", "company_config is empty", "CTO"));
        }

        List<ChartOfAccount> cashAccounts = chartOfAccountRepository.findCashBankAccounts();
        List<ChartOfAccount> revenueAccounts =
                chartOfAccountRepository.findByAccountTypeAndActiveOrderByAccountCodeAsc(AccountType.REVENUE, true);
        List<ChartOfAccount> expenseAccounts =
                chartOfAccountRepository.findByAccountTypeAndActiveOrderByAccountCodeAsc(AccountType.EXPENSE, true);
        boolean hasRevenueAccounts = hasTransactableAccount(revenueAccounts);
        boolean hasExpenseAccounts = hasTransactableAccount(expenseAccounts);

        // ---- cash ----
        if (cashAccounts.isEmpty()) {
            figures.put("cash.total", unavailable("cash.total", "Kas & saldo bank", UNIT_IDR,
                    balancePeriod(asOf), "chart_of_accounts:cash-bank",
                    "DashboardService#calculateCashBalance",
                    "no cash/bank account is configured in the chart of accounts",
                    "Finance Controller", missing));
        } else {
            BigDecimal cashTotal = dashboardService.calculateCashBalance(asOf);
            figures.put("cash.total", posted("cash.total", "Kas & saldo bank", money(cashTotal),
                    UNIT_IDR, balancePeriod(asOf), "chart_of_accounts:cash-bank",
                    "DashboardService#calculateCashBalance", null));
        }

        // ---- revenue / expense / profit ----
        ReportService.IncomeStatementReport income =
                reportService.generateIncomeStatement(periodStart, periodEnd);
        if (hasRevenueAccounts) {
            figures.put("revenue.period", posted("revenue.period", "Pendapatan periode",
                    money(income.totalRevenue()), UNIT_IDR, flowPeriod(periodStart, periodEnd),
                    "chart_of_accounts:REVENUE", "ReportService#generateIncomeStatement", null));
        } else {
            figures.put("revenue.period", unavailable("revenue.period", "Pendapatan periode", UNIT_IDR,
                    flowPeriod(periodStart, periodEnd), "chart_of_accounts:REVENUE",
                    "ReportService#generateIncomeStatement",
                    "no revenue account is configured in the chart of accounts",
                    "Finance Controller", missing));
        }

        if (hasExpenseAccounts) {
            figures.put("expense.period", posted("expense.period", "Beban periode",
                    money(income.totalExpense()), UNIT_IDR, flowPeriod(periodStart, periodEnd),
                    "chart_of_accounts:EXPENSE", "ReportService#generateIncomeStatement", null));
        } else {
            figures.put("expense.period", unavailable("expense.period", "Beban periode", UNIT_IDR,
                    flowPeriod(periodStart, periodEnd), "chart_of_accounts:EXPENSE",
                    "ReportService#generateIncomeStatement",
                    "no expense account is configured in the chart of accounts",
                    "Finance Controller", missing));
        }

        boolean profitAvailable = hasRevenueAccounts && hasExpenseAccounts;
        if (profitAvailable) {
            BigDecimal netProfit = income.totalRevenue().subtract(income.totalExpense());
            figures.put("netProfit.period", posted("netProfit.period", "Laba bersih (akuntansi)",
                    money(netProfit), UNIT_IDR, flowPeriod(periodStart, periodEnd),
                    "income-statement", "ReportService#generateIncomeStatement",
                    "Laba akuntansi, bukan kas tersedia"));

            if (income.totalRevenue().compareTo(BigDecimal.ZERO) == 0) {
                figures.put("profitMargin.period", unavailable("profitMargin.period", "Margin laba",
                        UNIT_PERCENT, flowPeriod(periodStart, periodEnd), "income-statement",
                        "FasternetDashboardReadService#profitMargin",
                        "revenue is zero, so the margin is undefined",
                        "Finance Controller", missing));
            } else {
                BigDecimal margin = netProfit.multiply(BigDecimal.valueOf(100))
                        .divide(income.totalRevenue(), 2, RoundingMode.HALF_UP);
                figures.put("profitMargin.period", posted("profitMargin.period", "Margin laba",
                        decimal(margin), UNIT_PERCENT, flowPeriod(periodStart, periodEnd),
                        "income-statement", "FasternetDashboardReadService#profitMargin", null));
            }
        } else {
            figures.put("netProfit.period", unavailable("netProfit.period", "Laba bersih (akuntansi)",
                    UNIT_IDR, flowPeriod(periodStart, periodEnd), "income-statement",
                    "ReportService#generateIncomeStatement",
                    "revenue/expense account mapping is incomplete",
                    "Finance Controller", missing));
            figures.put("profitMargin.period", unavailable("profitMargin.period", "Margin laba",
                    UNIT_PERCENT, flowPeriod(periodStart, periodEnd), "income-statement",
                    "FasternetDashboardReadService#profitMargin",
                    "revenue/expense account mapping is incomplete",
                    "Finance Controller", missing));
        }

        putControlBalance(figures, missing, "receivables.total", "Piutang (AR)",
                config == null ? null : config.getReceivableAccount(), asOf,
                "chart_of_accounts:ASSET/receivables", "FasternetDashboardReadService#accountBalance",
                "receivable account is not configured in company_config");
        putControlBalance(figures, missing, "payables.total", "Utang (AP)",
                config == null ? null : config.getPayableAccount(), asOf,
                "chart_of_accounts:LIABILITY/payables", "FasternetDashboardReadService#accountBalance",
                "payable account is not configured in company_config");

        // ---- founder capital: mapping not confirmed ----
        figures.put("equity.founderCapital", unavailable("equity.founderCapital", "Modal pendiri",
                UNIT_IDR, balancePeriod(asOf), "chart_of_accounts:EQUITY",
                "FasternetDashboardReadService#founderCapital",
                "founder-capital COA mapping has not been confirmed by the Finance Controller",
                "Finance Controller", missing));

        // ---- KUR: loan master/schedule/reserve backend not implemented yet ----
        figures.put("kur.installmentDue.next", unavailable("kur.installmentDue.next",
                "Angsuran KUR berikutnya", UNIT_IDR, balancePeriod(asOf), "kur-loan-master",
                "FasternetDashboardReadService#kur",
                "KUR loan master and verified installment schedule are not implemented yet",
                "Backend Engineer", missing));
        figures.put("kur.nextDue.date", unavailable("kur.nextDue.date", "Jatuh tempo berikutnya",
                UNIT_TEXT, balancePeriod(asOf), "kur-loan-master",
                "FasternetDashboardReadService#kur",
                "KUR loan master and verified installment schedule are not implemented yet",
                "Backend Engineer", missing));
        figures.put("kur.nextDue.amount", unavailable("kur.nextDue.amount",
                "Nominal jatuh tempo berikutnya", UNIT_IDR, balancePeriod(asOf), "kur-loan-master",
                "FasternetDashboardReadService#kur",
                "KUR loan master and verified installment schedule are not implemented yet",
                "Backend Engineer", missing));
        figures.put("kur.nextDue.daysRemaining", unavailable("kur.nextDue.daysRemaining", "Sisa hari",
                UNIT_COUNT, balancePeriod(asOf), "kur-loan-master",
                "FasternetDashboardReadService#kur",
                "KUR loan master and verified installment schedule are not implemented yet",
                "Backend Engineer", missing));
        figures.put("kur.paid.count", unavailable("kur.paid.count", "Angsuran terbayar",
                UNIT_COUNT, balancePeriod(asOf), "kur-payment-journals",
                "FasternetDashboardReadService#kur",
                "KUR loan master and evidenced payments are not implemented yet",
                "Backend Engineer", missing));
        figures.put("kur.paid.total", unavailable("kur.paid.total", "Total terbayar",
                UNIT_IDR, balancePeriod(asOf), "kur-payment-journals",
                "FasternetDashboardReadService#kur",
                "KUR loan master and evidenced payments are not implemented yet",
                "Backend Engineer", missing));
        figures.put("kur.lastPayment.status", unavailable("kur.lastPayment.status",
                "Status pembayaran terakhir", UNIT_TEXT, balancePeriod(asOf), "kur-payment-journals",
                "FasternetDashboardReadService#kur",
                "no KUR payment has been recorded",
                "Backend Engineer", missing));
        figures.put("kur.reserve.earmarked", unavailable("kur.reserve.earmarked",
                "Dana KUR yang disisihkan", UNIT_IDR, balancePeriod(asOf), "kur-reserve-tracker",
                "FasternetDashboardReadService#kurReserve",
                "reserve account/tag has not been configured by the Finance Controller",
                "Finance Controller", missing));
        figures.put("kur.reserve.gap", unavailable("kur.reserve.gap", "Gap reserve KUR",
                UNIT_IDR, balancePeriod(asOf), "kur-reserve-tracker",
                "FasternetDashboardReadService#kurReserve",
                "the outstanding-based reserve-gap basis is defined in the dashboard contract and awaits Finance Controller sign-off; reserve accounts are not mapped yet",
                "Finance Controller", missing));

        // ---- subscriber / billing aggregates: no approved model/mapping in v1 ----
        putUnavailableWithOwner(figures, missing, "subscribers.active", "Pelanggan aktif", UNIT_COUNT,
                balancePeriod(asOf), "subscriber-master", "BillingService#activeSubscribers",
                "no subscriber master exists in v1");
        putUnavailableWithOwner(figures, missing, "subscribers.new.period", "Pelanggan baru", UNIT_COUNT,
                flowPeriod(periodStart, periodEnd), "subscriber-master", "BillingService#newSubscribers",
                "no subscriber master exists in v1");
        putUnavailableWithOwner(figures, missing, "subscribers.cancelled.period", "Pelanggan berhenti",
                UNIT_COUNT, flowPeriod(periodStart, periodEnd), "subscriber-master",
                "BillingService#cancelledSubscribers", "no subscriber master exists in v1");
        putUnavailableWithOwner(figures, missing, "billing.mrr", "MRR", UNIT_IDR,
                flowPeriod(periodStart, periodEnd), "invoice-mapping", "BillingService#mrr",
                "recurring-plan mapping has not been approved");
        putUnavailableWithOwner(figures, missing, "billing.invoiced.period", "Ditagihkan periode",
                UNIT_IDR, flowPeriod(periodStart, periodEnd), "invoice-mapping", "BillingService#invoiced",
                "billing mapping has not been confirmed");
        putUnavailableWithOwner(figures, missing, "billing.collected.period", "Terkumpul periode",
                UNIT_IDR, flowPeriod(periodStart, periodEnd), "invoice-mapping", "BillingService#collected",
                "billing mapping has not been confirmed");
        putUnavailableWithOwner(figures, missing, "billing.outstanding", "Piutang tagihan", UNIT_IDR,
                balancePeriod(asOf), "invoice-mapping", "BillingService#outstanding",
                "billing mapping has not been confirmed");
        putUnavailableWithOwner(figures, missing, "billing.overdue", "Tagihan jatuh tempo", UNIT_IDR,
                balancePeriod(asOf), "invoice-mapping", "BillingService#overdue",
                "billing mapping has not been confirmed");

        // ---- series ----
        List<String> periods = new ArrayList<>();
        List<String> cashSeries = new ArrayList<>();
        List<String> reserveSeries = new ArrayList<>();
        List<String> revenueSeries = new ArrayList<>();
        List<String> expenseSeries = new ArrayList<>();
        for (int i = SERIES_MONTHS - 1; i >= 0; i--) {
            YearMonth point = month.minusMonths(i);
            periods.add(point.toString());
            reserveSeries.add(null);

            LocalDate cashDate = point.equals(month) ? asOf : point.atEndOfMonth();
            cashSeries.add(cashAccounts.isEmpty() ? null : money(dashboardService.calculateCashBalance(cashDate)));

            if (hasRevenueAccounts) {
                revenueSeries.add(money(reportService.generateIncomeStatement(
                        point.atDay(1), point.atEndOfMonth()).totalRevenue()));
            } else {
                revenueSeries.add(null);
            }
            if (hasExpenseAccounts) {
                expenseSeries.add(money(reportService.generateIncomeStatement(
                        point.atDay(1), point.atEndOfMonth()).totalExpense()));
            } else {
                expenseSeries.add(null);
            }
        }

        List<FasternetOverviewResponse.CashAccount> cashAccountRows = new ArrayList<>();
        for (ChartOfAccount account : cashAccounts) {
            cashAccountRows.add(new FasternetOverviewResponse.CashAccount(
                    account.getAccountCode(), account.getAccountName(),
                    money(accountBalance(account, asOf)), STATUS_POSTED));
        }
        if (cashAccounts.isEmpty()) {
            missing.add(new FasternetOverviewResponse.Missing(
                    "cash.accounts", "no cash/bank account is configured in the chart of accounts",
                    "Finance Controller"));
        }

        // Series never mix states: every non-null point is derived from POSTED journal
        // entries only. A series with no derivable point is reported unavailable, not zero.
        String seriesStatus = cashSeries.stream().anyMatch(Objects::nonNull)
                || revenueSeries.stream().anyMatch(Objects::nonNull)
                || expenseSeries.stream().anyMatch(Objects::nonNull)
                || reserveSeries.stream().anyMatch(Objects::nonNull)
                ? STATUS_POSTED : STATUS_UNAVAILABLE;

        FasternetOverviewResponse.Series series = new FasternetOverviewResponse.Series(
                seriesStatus, periods, cashSeries, reserveSeries, revenueSeries, expenseSeries,
                cashAccountRows);

        FasternetOverviewResponse.Company company = new FasternetOverviewResponse.Company(
                config == null ? null : config.getId().toString(),
                config == null ? null : config.getCompanyName());

        return new FasternetOverviewResponse(
                CONTRACT_VERSION,
                OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                asOf.toString(),
                new FasternetOverviewResponse.Period(periodStart.toString(), periodEnd.toString(),
                        month.toString(), periodStatus(month)),
                CURRENCY,
                company,
                figures,
                series,
                missing);
    }

    private void putControlBalance(
            Map<String, FasternetOverviewResponse.Figure> figures,
            List<FasternetOverviewResponse.Missing> missing,
            String key,
            String label,
            ChartOfAccount account,
            LocalDate asOf,
            String ref,
            String computedBy,
            String missingReason) {
        if (account == null) {
            figures.put(key, unavailable(key, label, UNIT_IDR, balancePeriod(asOf), ref, computedBy,
                    missingReason, "Finance Controller", missing));
            return;
        }
        figures.put(key, posted(key, label, money(accountBalance(account, asOf)), UNIT_IDR,
                balancePeriod(asOf), ref, computedBy, null));
    }

    private void putUnavailableWithOwner(
            Map<String, FasternetOverviewResponse.Figure> figures,
            List<FasternetOverviewResponse.Missing> missing,
            String key,
            String label,
            String unit,
            Map<String, String> period,
            String ref,
            String computedBy,
            String reason) {
        figures.put(key, unavailable(key, label, unit, period, ref, computedBy, reason,
                "Finance Controller", missing));
    }

    private FasternetOverviewResponse.Figure posted(
            String key, String label, String value, String unit, Map<String, String> period,
            String ref, String computedBy, String notes) {
        return new FasternetOverviewResponse.Figure(key, label, value, unit, STATUS_POSTED, period,
                new FasternetOverviewResponse.Source(SOURCE_SYSTEM, KIND_LEDGER, ref, computedBy), notes);
    }

    private FasternetOverviewResponse.Figure unavailable(
            String key, String label, String unit, Map<String, String> period,
            String ref, String computedBy, String reason, String owner,
            List<FasternetOverviewResponse.Missing> missing) {
        missing.add(new FasternetOverviewResponse.Missing(key, reason, owner));
        return new FasternetOverviewResponse.Figure(key, label, null, unit, STATUS_UNAVAILABLE, period,
                new FasternetOverviewResponse.Source(SOURCE_SYSTEM, KIND_NONE, ref, computedBy), reason);
    }

    private BigDecimal accountBalance(ChartOfAccount account, LocalDate asOf) {
        BigDecimal debit = journalEntryRepository.sumDebitByAccountAndDateRange(
                account.getId(), LEDGER_EPOCH, asOf);
        BigDecimal credit = journalEntryRepository.sumCreditByAccountAndDateRange(
                account.getId(), LEDGER_EPOCH, asOf);
        return account.getNormalBalance() == NormalBalance.DEBIT
                ? debit.subtract(credit)
                : credit.subtract(debit);
    }

    private boolean hasTransactableAccount(List<ChartOfAccount> accounts) {
        return accounts.stream().anyMatch(account -> !Boolean.TRUE.equals(account.getIsHeader()));
    }

    private String periodStatus(YearMonth month) {
        Optional<FiscalPeriod> period = fiscalPeriodRepository.findByYearAndMonth(
                month.getYear(), month.getMonthValue());
        return period.map(value -> value.getStatus() == FiscalPeriodStatus.OPEN ? "open" : "closed")
                .orElse("open");
    }

    private Map<String, String> balancePeriod(LocalDate asOf) {
        Map<String, String> period = new LinkedHashMap<>();
        period.put("asOf", asOf.toString());
        return period;
    }

    private Map<String, String> flowPeriod(LocalDate from, LocalDate to) {
        Map<String, String> period = new LinkedHashMap<>();
        period.put("from", from.toString());
        period.put("to", to.toString());
        return period;
    }

    private String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String decimal(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
