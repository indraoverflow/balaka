package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.dto.fasternet.KurReconciliationReport;
import com.artivisi.accountingfinance.entity.ChartOfAccount;
import com.artivisi.accountingfinance.entity.JournalEntry;
import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.entity.KurPayment;
import com.artivisi.accountingfinance.entity.Transaction;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.repository.ChartOfAccountRepository;
import com.artivisi.accountingfinance.repository.JournalEntryRepository;
import com.artivisi.accountingfinance.repository.KurInstallmentRepository;
import com.artivisi.accountingfinance.repository.KurLoanRepository;
import com.artivisi.accountingfinance.repository.KurPaymentRepository;
import com.artivisi.accountingfinance.repository.TransactionRepository;
import com.artivisi.accountingfinance.util.KurMoneyMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Three-way KUR lender reconciliation (FAS-139 / FAS-97 §10).
 *
 * <p>Triangulates three independently recorded views of the same loan:
 * <ol>
 *   <li>Leg A — outstanding principal: the ledger (2.1.30 + 2.2.01) versus the
 *       lender schedule imported into the loan master.</li>
 *   <li>Leg B — cash: posted cash out of 1.1.10 for KUR installments versus the
 *       payments recorded from bank evidence in the tracker.</li>
 *   <li>Leg C — component split: the posted principal/interest/fee of each posted
 *       installment versus the schedule row it settles.</li>
 * </ol>
 *
 * <p>Zero tolerance for unexplained differences: any leg that does not tie gives a
 * RED status. A payment that is recorded but not yet posted is a timing difference
 * (AMBER), never a silent zero. When the loan is still a founder estimate the bank
 * leg is unverified, so the report is at best AMBER and carries the estimate variance.
 */
@Service
@RequiredArgsConstructor
public class KurReconciliationService {

    static final String ACCOUNT_SHORT_TERM = "2.1.30";
    static final String ACCOUNT_LONG_TERM = "2.2.01";
    static final String ACCOUNT_CASH_BANK = "1.1.10";
    static final String ACCOUNT_INTEREST_EXPENSE = "5.2.03";
    static final String ACCOUNT_FEE_EXPENSE = "5.2.04";

    private static final BigDecimal PRINCIPAL_TOLERANCE = new BigDecimal("1.00");

    private final KurLoanRepository loanRepository;
    private final KurInstallmentRepository installmentRepository;
    private final KurPaymentRepository paymentRepository;
    private final TransactionRepository transactionRepository;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final JournalEntryRepository journalEntryRepository;

    @Transactional(readOnly = true)
    public KurReconciliationReport reconcile(UUID loanId, LocalDate asOf) {
        KurLoan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown KUR loan: " + loanId));
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now() : asOf;

        List<KurInstallment> installments = installmentRepository
                .findByLoanIdOrderByInstallmentNumberAsc(loanId);
        List<KurPayment> payments = paymentRepository
                .findByInstallmentLoanIdAndReversedAtIsNullAndPaymentDateLessThanEqualOrderByPaymentDateAsc(
                        loanId, effectiveAsOf);

        BigDecimal glCurrent = glOutstanding(ACCOUNT_SHORT_TERM, effectiveAsOf);
        BigDecimal glNonCurrent = glOutstanding(ACCOUNT_LONG_TERM, effectiveAsOf);
        BigDecimal ledgerPrincipal = glCurrent.add(glNonCurrent);

        BigDecimal scheduleOutstanding = installments.stream()
                .filter(row -> row.getDueDate() != null && row.getDueDate().isAfter(effectiveAsOf))
                .map(this::principalOf)
                .reduce(KurMoneyMath.ZERO, BigDecimal::add);
        boolean scheduleMissing = installments.isEmpty();
        BigDecimal bankPrincipal = scheduleMissing ? ledgerPrincipal : scheduleOutstanding;
        BigDecimal principalVariance = ledgerPrincipal.subtract(bankPrincipal);

        BigDecimal ledgerCash = postedCashCredits(effectiveAsOf);
        BigDecimal bankCash = payments.stream()
                .map(KurPayment::getAmount)
                .reduce(KurMoneyMath.ZERO, BigDecimal::add);

        List<KurReconciliationReport.TimingDifferenceItem> timing = new ArrayList<>();
        BigDecimal timingTotal = KurMoneyMath.ZERO;
        for (KurPayment payment : payments) {
            if (payment.getJournalTransactionId() != null) {
                continue;
            }
            int age = (int) ChronoUnit.DAYS.between(payment.getPaymentDate(), effectiveAsOf);
            timingTotal = timingTotal.add(KurMoneyMath.nonNullMoney(payment.getAmount()));
            timing.add(new KurReconciliationReport.TimingDifferenceItem(
                    payment.getSourceRef(),
                    payment.getPaymentDate(),
                    payment.getAmount(),
                    age,
                    "payment recorded from bank evidence but not yet posted to the ledger"));
        }
        // A recorded-but-unposted payment is a timing difference, not an unexplained
        // gap: exclude it from the cash variance so it quarantines instead of plugging.
        BigDecimal cashVariance = ledgerCash.subtract(bankCash);

        Map<UUID, KurPayment> postedPaymentByInstallment = new LinkedHashMap<>();
        for (KurPayment payment : payments) {
            if (payment.getJournalTransactionId() != null && !payment.isReversed()) {
                postedPaymentByInstallment.put(payment.getInstallment().getId(), payment);
            }
        }

        List<KurReconciliationReport.ScheduleItemDiscrepancy> discrepancies = new ArrayList<>();
        BigDecimal postedComponents = KurMoneyMath.ZERO;
        BigDecimal scheduleComponents = KurMoneyMath.ZERO;
        for (KurInstallment installment : installments) {
            KurPayment posted = postedPaymentByInstallment.get(installment.getId());
            if (posted == null) {
                continue;
            }
            ComponentSplit split = postedSplit(posted.getJournalTransactionId());
            KurMoneyMath.ScheduledComponents schedule = KurMoneyMath.scheduleComponents(
                    installment.getAmount(), installment.getPrincipal(),
                    installment.getInterest(), installment.getFee());
            postedComponents = postedComponents.add(split.total());
            scheduleComponents = scheduleComponents.add(schedule.principal())
                    .add(schedule.interest()).add(schedule.fee());
            boolean differs = split.principal().compareTo(schedule.principal()) != 0
                    || split.interest().compareTo(schedule.interest()) != 0
                    || split.fee().compareTo(schedule.fee()) != 0;
            if (differs) {
                discrepancies.add(new KurReconciliationReport.ScheduleItemDiscrepancy(
                        installment.getInstallmentNumber(),
                        split.principal(), schedule.principal(),
                        split.interest(), schedule.interest(),
                        split.fee(), schedule.fee(),
                        schedule.estimated()
                                ? "schedule components are an estimate; the lender split is not attached"
                                : "posted split differs from the lender schedule row"));
            }
        }

        BigDecimal componentVariance = postedComponents.subtract(scheduleComponents).abs();
        BigDecimal unexplainedCashVariance = cashVariance.add(timingTotal).abs();
        BigDecimal unexplained = principalVariance.abs()
                .subtract(PRINCIPAL_TOLERANCE).max(KurMoneyMath.ZERO)
                .add(unexplainedCashVariance)
                .add(discrepancies.isEmpty() ? KurMoneyMath.ZERO : componentVariance);
        boolean estimate = loan.getProvenance() != KurLoanProvenance.VERIFIED;
        BigDecimal estimateVariance = estimate ? principalVariance.abs() : KurMoneyMath.ZERO;

        KurReconciliationReport.ReconciliationStatus status;
        if (unexplained.signum() > 0) {
            status = KurReconciliationReport.ReconciliationStatus.RED;
        } else if (estimate || !timing.isEmpty() || scheduleMissing) {
            status = KurReconciliationReport.ReconciliationStatus.AMBER;
        } else {
            status = KurReconciliationReport.ReconciliationStatus.GREEN;
        }

        String summary = buildSummary(status, ledgerPrincipal, bankPrincipal, principalVariance,
                ledgerCash, bankCash, cashVariance, discrepancies.size(), timing.size(), estimate,
                scheduleMissing);

        return new KurReconciliationReport(
                loanId,
                effectiveAsOf,
                ledgerPrincipal,
                bankPrincipal,
                principalVariance,
                ledgerCash,
                bankCash,
                cashVariance,
                postedPaymentByInstallment.size(),
                (int) payments.stream().filter(p -> p.getAmount().signum() > 0).count(),
                discrepancies,
                timing,
                estimateVariance,
                unexplained,
                status,
                summary);
    }

    private String buildSummary(KurReconciliationReport.ReconciliationStatus status,
                                BigDecimal ledgerPrincipal, BigDecimal bankPrincipal,
                                BigDecimal principalVariance, BigDecimal ledgerCash,
                                BigDecimal bankCash, BigDecimal cashVariance, int discrepancyCount,
                                int timingCount, boolean estimate, boolean scheduleMissing) {
        StringBuilder sb = new StringBuilder();
        sb.append(status).append(": principal ledger ").append(money(ledgerPrincipal))
                .append(" vs lender schedule ").append(money(bankPrincipal))
                .append(" (variance ").append(money(principalVariance)).append("); ")
                .append("cash posted ").append(money(ledgerCash))
                .append(" vs recorded ").append(money(bankCash))
                .append(" (variance ").append(money(cashVariance)).append(")");
        if (scheduleMissing) {
            sb.append("; no lender schedule rows imported, so the bank leg is unverified");
        }
        if (estimate) {
            sb.append("; loan is a founder estimate, the bank leg is unverified");
        }
        if (discrepancyCount > 0) {
            sb.append("; ").append(discrepancyCount).append(" installment split discrepancy(ies)");
        }
        if (timingCount > 0) {
            sb.append("; ").append(timingCount).append(" recorded payment(s) not yet posted");
        }
        return sb.toString();
    }

    private record ComponentSplit(BigDecimal principal, BigDecimal interest, BigDecimal fee) {
        BigDecimal total() {
            return principal.add(interest).add(fee);
        }
    }

    private ComponentSplit postedSplit(UUID transactionId) {
        Map<String, BigDecimal> debitsByAccount = new LinkedHashMap<>();
        for (JournalEntry entry : journalEntryRepository.findByTransactionIdWithAccount(transactionId)) {
            if (entry.getDebitAmount() == null || entry.getDebitAmount().signum() == 0) {
                continue;
            }
            String code = entry.getAccount().getAccountCode();
            debitsByAccount.merge(code, entry.getDebitAmount(), BigDecimal::add);
        }
        return new ComponentSplit(
                KurMoneyMath.nonNullMoney(debitsByAccount.get(ACCOUNT_SHORT_TERM)),
                KurMoneyMath.nonNullMoney(debitsByAccount.get(ACCOUNT_INTEREST_EXPENSE)),
                KurMoneyMath.nonNullMoney(debitsByAccount.get(ACCOUNT_FEE_EXPENSE)));
    }

/**
 * Posted cash out of 1.1.10 that belongs to KUR postings, up to {@code asOf}.
 * Only POSTED transactions count; a voided journal is excluded on both sides.
 */
    private BigDecimal postedCashCredits(LocalDate asOf) {
        ChartOfAccount cash = requireAccount(ACCOUNT_CASH_BANK);
        LocalDate from = LocalDate.of(1970, 1, 1);
        BigDecimal total = KurMoneyMath.ZERO;
        for (JournalEntry entry : journalEntryRepository
                .findPostedEntriesByAccountAndDateRange(cash.getId(), from, asOf)) {
            Transaction transaction = entry.getTransaction();
            if (transaction == null || entry.getCreditAmount() == null
                    || entry.getCreditAmount().signum() == 0) {
                continue;
            }
            if (transaction.getSourceDocumentType() != null
                    && transaction.getSourceDocumentType().startsWith("KUR_")) {
                total = total.add(entry.getCreditAmount());
            }
        }
        return KurMoneyMath.money(total);
    }

    private BigDecimal glOutstanding(String accountCode, LocalDate asOf) {
        ChartOfAccount account = requireAccount(accountCode);
        BigDecimal credit = journalEntryRepository.sumCreditBeforeDate(account.getId(), asOf.plusDays(1));
        BigDecimal debit = journalEntryRepository.sumDebitBeforeDate(account.getId(), asOf.plusDays(1));
        return KurMoneyMath.money(KurMoneyMath.nonNullMoney(credit)
                .subtract(KurMoneyMath.nonNullMoney(debit)));
    }

    private BigDecimal principalOf(KurInstallment installment) {
        return KurMoneyMath.scheduleComponents(installment.getAmount(), installment.getPrincipal(),
                installment.getInterest(), installment.getFee()).principal();
    }

    private ChartOfAccount requireAccount(String code) {
        return chartOfAccountRepository.findByAccountCode(code).orElseThrow(() ->
                new IllegalStateException("Chart-of-account entry missing: " + code));
    }

    private static String money(BigDecimal value) {
        return KurMoneyMath.nonNullMoney(value).toPlainString();
    }
}
