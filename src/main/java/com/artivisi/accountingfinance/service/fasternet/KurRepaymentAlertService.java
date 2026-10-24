package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.dto.fasternet.KurAlertResponse;
import com.artivisi.accountingfinance.dto.fasternet.KurReserveResponse;
import com.artivisi.accountingfinance.entity.KurAlertPolicy;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.enums.KurAlertState;
import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import com.artivisi.accountingfinance.repository.KurAlertPolicyRepository;
import com.artivisi.accountingfinance.repository.KurLoanRepository;
import com.artivisi.accountingfinance.util.KurMoneyMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the next-installment KUR repayment alert (FAS-96).
 *
 * <p>This service composes data it does not own: the reserve gap and cash figure
 * come from {@link KurLoanService#reserve}, the obligation snapshot from
 * {@link KurLoanService#nextObligation}, and the thresholds from
 * {@link KurAlertPolicyService}. It never re-implements the reserve-gap formula,
 * never treats missing data as zero, and never lets an earmarked reserve look
 * like a paid installment.
 *
 * <p>State rules:
 * <ul>
 *   <li>red: the next unpaid installment is overdue, or a <em>verified</em>
 *       schedule shows a gap at/above the approved red cutoff;</li>
 *   <li>orange: the gap reaches the approved orange cutoff, actual cash is below
 *       the approved total-cash minimum, actual cash after paying the outstanding
 *       falls below that minimum, or an unverified schedule reaches the red cutoff
 *       (red is withheld);</li>
 *   <li>yellow: the reserve is behind the approved funding plan;</li>
 *   <li>green: the reserve is funded to target and cash covers the minimum;</li>
 *   <li>unconfigured: no approved threshold policy has been recorded;</li>
 *   <li>unavailable: a required input is missing.</li>
 * </ul>
 *
 * <p>{@code minimumCash} is a <strong>total-cash floor</strong>: the test is
 * {@code cash.actual - outstanding >= minimumCash}. {@code cash.actual} already
 * includes the earmarked reserve, so the gap is not subtracted again.
 *
 * <p>{@code frameworkLevel} is not derived in v1. The framework §8 GREEN and AMBER
 * legs need a 90-day runway forecast, and RED needs the 30-day projected-shortfall
 * test; none exists here. The field is therefore {@code unavailable} with the
 * limitation stated in the reason and assumptions.
 */
@Service
@RequiredArgsConstructor
public class KurRepaymentAlertService {

    public static final String CONTRACT_VERSION = "fasternet.dashboard/v1";
    public static final String CURRENCY = "IDR";
    public static final String INVARIANT = "a reserved amount is never a completed payment";
    public static final String CASH_SOURCE = "Balaka POSTED cash/bank account balances";
    public static final String FRAMEWORK_LEVEL_UNAVAILABLE = "unavailable";
    public static final String FRAMEWORK_LEVEL_SOURCE =
            "framework §8 (rev 3) + framework-s8-errata: AMBER runway 30-90 days, "
                    + "all-in burn base, 1 month = 30.44 days";
    public static final String FRAMEWORK_LEVEL_REASON =
            "v1 has no forward cash-flow forecast: the framework's 30-day projected-shortfall "
                    + "test and 90-day runway condition are not evaluated, and no Finance "
                    + "Controller-recorded forecast-backed level exists; frameworkLevel is "
                    + "unavailable and a v1 green is not framework GREEN";

    private final KurLoanRepository loanRepository;
    private final KurAlertPolicyRepository policyRepository;
    private final KurLoanService kurLoanService;

    @Transactional(readOnly = true)
    public KurAlertResponse evaluate(java.util.UUID loanId, LocalDate asOf) {
        KurLoan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown KUR loan: " + loanId));
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now() : asOf;

        KurReserveResponse reserve = kurLoanService.reserve(loanId, effectiveAsOf);
        KurLoanService.ObligationSnapshot obligation = kurLoanService.nextObligation(loanId, effectiveAsOf);
        KurAlertPolicy policy = policyRepository.findByLoanId(loanId).orElse(null);

        BigDecimal earmarked = amount(reserve, "reserve.earmarked");
        BigDecimal separate = amount(reserve, "reserve.separateAccount");
        BigDecimal qualifyingReserve = earmarked == null || separate == null
                ? null
                : KurMoneyMath.qualifyingReserve(earmarked, separate);
        BigDecimal cash = amount(reserve, "cash.actual");
        BigDecimal gap = amount(reserve, "reserve.gap");
        String gapStatus = status(reserve, "reserve.gap");
        String cashStatus = status(reserve, "cash.actual");

        Map<String, KurAlertResponse.Missing> missing = new LinkedHashMap<>();
        for (KurReserveResponse.Missing item : reserve.missing()) {
            missing.put(item.key(), new KurAlertResponse.Missing(item.key(), item.reason(), item.owner()));
        }

        boolean policyConfigured = policy != null && policy.isConfigured();
        if (!policyConfigured) {
            missing.put("kur.alert.policy", new KurAlertResponse.Missing(
                    "kur.alert.policy",
                    "no CEO + Finance Controller-approved threshold policy is recorded",
                    "Finance Controller"));
        }
        if (obligation != null && !obligation.scheduleVerified()) {
            missing.put("kur.alert.schedule", new KurAlertResponse.Missing(
                    "kur.alert.schedule",
                    "installment due date and amount are not from a verified lender schedule",
                    "Finance Controller"));
        }
        missing.put("kur.alert.frameworkLevel", new KurAlertResponse.Missing(
                "kur.alert.frameworkLevel",
                FRAMEWORK_LEVEL_REASON,
                "Finance Controller"));

        Evaluation evaluation = evaluateState(effectiveAsOf, obligation, policy, gap, cash,
                qualifyingReserve, policyConfigured);
        if (evaluation.state() == KurAlertState.UNAVAILABLE) {
            missing.computeIfAbsent(evaluation.missingKey(), key -> new KurAlertResponse.Missing(
                    key, evaluation.reason(), "Finance Controller"));
        }

        List<String> assumptions = assumptions(effectiveAsOf, policy, policyConfigured);
        KurAlertResponse.Shortfall shortfall = gap == null
                ? new KurAlertResponse.Shortfall(null, CURRENCY, "reserve-gap", "unavailable")
                : new KurAlertResponse.Shortfall(moneyString(gap), CURRENCY, "reserve-gap", gapStatus);

        KurAlertResponse.Obligation obligationDto = obligation == null ? null
                : new KurAlertResponse.Obligation(
                        moneyString(obligation.amount()),
                        moneyString(obligation.paidAmount()),
                        moneyString(obligation.outstanding()),
                        obligation.status().getJsonValue(),
                        obligation.scheduleVerified() ? "verified" : "estimate",
                        obligation.provenance().getJsonValue(),
                        obligation.sourceKind().getJsonValue(),
                        obligation.sourceRef());

        KurAlertResponse.Payment payment = new KurAlertResponse.Payment(
                obligation == null ? "unavailable" : obligation.status().getJsonValue(),
                obligation == null ? null : moneyString(obligation.paidAmount()),
                obligation != null && isPaid(obligation.status()),
                INVARIANT);

        List<KurAlertResponse.ReserveComposition> reserveComposition = reserve.composition().stream()
                .map(entry -> new KurAlertResponse.ReserveComposition(
                        entry.entryId(),
                        entry.pool(),
                        entry.kind(),
                        entry.amount(),
                        entry.entryDate(),
                        entry.sourceRef()))
                .toList();
        List<String> cashAccountCodes = reserve.cashAccounts().stream()
                .map(KurReserveResponse.CashAccount::accountCode)
                .toList();

        KurAlertResponse.Reserve reserveDto = new KurAlertResponse.Reserve(
                moneyString(qualifyingReserve),
                moneyString(earmarked),
                moneyString(separate),
                qualifyingReserve == null ? "unavailable" : "posted",
                reserveComposition);

        KurAlertResponse.Cash cashDto = new KurAlertResponse.Cash(
                moneyString(cash),
                policyConfigured ? moneyString(policy.getMinimumCash()) : null,
                CASH_SOURCE,
                effectiveAsOf.toString(),
                cashStatus,
                cashAccountCodes);

        KurAlertResponse.Provenance provenance = new KurAlertResponse.Provenance(
                loan.getId().toString(),
                obligation == null ? null : obligation.installmentId().toString(),
                obligation == null ? null : obligation.provenance().getJsonValue(),
                obligation == null ? null : obligation.sourceKind().getJsonValue(),
                obligation == null ? null : obligation.sourceRef(),
                reserveComposition,
                cashAccountCodes,
                effectiveAsOf.toString());

        KurAlertResponse.Alert alert = new KurAlertResponse.Alert(
                evaluation.state().getJsonValue(),
                evaluation.state().getSeverity(),
                evaluation.reason(),
                FRAMEWORK_LEVEL_UNAVAILABLE,
                FRAMEWORK_LEVEL_REASON,
                FRAMEWORK_LEVEL_SOURCE,
                shortfall,
                obligation == null ? null : obligation.dueDate().toString(),
                obligation == null ? null : obligation.daysRemaining(),
                obligation == null ? null : obligation.installmentNumber(),
                obligationDto,
                reserveDto,
                cashDto,
                payment,
                policyDto(policy),
                provenance,
                assumptions,
                INVARIANT);

        return new KurAlertResponse(
                CONTRACT_VERSION,
                Instant.now().toString(),
                effectiveAsOf.toString(),
                CURRENCY,
                alert,
                new ArrayList<>(missing.values()));
    }

    private Evaluation evaluateState(
            LocalDate asOf,
            KurLoanService.ObligationSnapshot obligation,
            KurAlertPolicy policy,
            BigDecimal gap,
            BigDecimal cash,
            BigDecimal qualifyingReserve,
            boolean policyConfigured) {
        if (obligation == null) {
            return Evaluation.unavailable("obligation.nextDue",
                    "No unpaid KUR installment is recorded for this loan as of " + asOf);
        }
        if (obligation.overdue()) {
            long overdueDays = Math.abs(obligation.daysRemaining());
            return Evaluation.of(KurAlertState.RED,
                    "Installment #" + obligation.installmentNumber() + " is overdue by "
                            + overdueDays + " day(s): due " + obligation.dueDate()
                            + ", outstanding " + moneyString(obligation.outstanding())
                            + " as of " + asOf);
        }
        if (!policyConfigured) {
            return Evaluation.of(KurAlertState.UNCONFIGURED,
                    "Threshold policy is unconfigured: the CEO and Finance Controller have not approved "
                            + "the KUR repayment alert thresholds, so no green state is produced");
        }
        if (qualifyingReserve == null || gap == null) {
            return Evaluation.unavailable("reserve.gap",
                    "Reserve movements are not recorded, so the reserve gap cannot be derived; "
                            + "missing data is not treated as zero");
        }

        BigDecimal minimumCash = policy.getMinimumCash();
        BigDecimal targetReserve = policy.getTargetReserve();
        BigDecimal tolerance = policy.getFundingPaceTolerancePercent();
        BigDecimal orangeCutoff = policy.getOrangeShortfall();
        BigDecimal redCutoff = policy.getRedShortfall();

        if (gap.compareTo(redCutoff) >= 0) {
            if (obligation.scheduleVerified()) {
                return Evaluation.of(KurAlertState.RED,
                        "Critical funding shortfall: reserve gap " + moneyString(gap)
                                + " is at or above the approved red cutoff " + moneyString(redCutoff)
                                + " against a verified lender schedule");
            }
            return Evaluation.of(KurAlertState.ORANGE,
                    "Critical funding shortfall under an unverified schedule: reserve gap " + moneyString(gap)
                            + " is at or above the approved red cutoff " + moneyString(redCutoff)
                            + ", but the schedule is " + obligation.provenance().getJsonValue()
                            + ", so red is withheld");
        }

        if (cash != null && cash.compareTo(minimumCash) < 0) {
            return Evaluation.of(KurAlertState.ORANGE,
                    "Actual cash " + moneyString(cash) + " is below the approved total-cash minimum "
                            + moneyString(minimumCash) + " as of " + asOf);
        }
        if (gap.compareTo(orangeCutoff) >= 0) {
            return Evaluation.of(KurAlertState.ORANGE,
                    "Reserve gap " + moneyString(gap) + " is at or above the approved orange cutoff "
                            + moneyString(orangeCutoff));
        }
        if (cash != null) {
            BigDecimal cashAfterOutstanding = KurMoneyMath.money(cash)
                    .subtract(KurMoneyMath.money(obligation.outstanding()));
            if (KurMoneyMath.cashFloorBreachedAfterOutstanding(
                    cash, obligation.outstanding(), minimumCash)) {
                return Evaluation.of(KurAlertState.ORANGE,
                        "Actual cash " + moneyString(cash) + " minus the outstanding "
                                + moneyString(obligation.outstanding()) + " leaves "
                                + moneyString(cashAfterOutstanding)
                                + ", below the approved total-cash minimum " + moneyString(minimumCash)
                                + "; cash cannot cover the reserve gap " + moneyString(gap)
                                + " as of " + asOf);
            }
        }
        if (gap.signum() > 0) {
            return Evaluation.of(KurAlertState.YELLOW,
                    "Reserve accumulation is behind the approved funding plan: qualifying reserve "
                            + moneyString(qualifyingReserve) + " leaves a gap of " + moneyString(gap)
                            + " against installment #" + obligation.installmentNumber()
                            + " due " + obligation.dueDate());
        }

        BigDecimal targetDeficit = targetReserve.subtract(qualifyingReserve);
        if (targetDeficit.signum() > 0) {
            BigDecimal allowedDeficit = targetReserve.multiply(tolerance)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (targetDeficit.compareTo(allowedDeficit) > 0) {
                return Evaluation.of(KurAlertState.YELLOW,
                        "Reserve accumulation is behind the approved funding plan: reserve "
                                + moneyString(qualifyingReserve) + " is below the approved target reserve "
                                + moneyString(targetReserve) + " by " + moneyString(targetDeficit)
                                + ", beyond the funding-pace tolerance " + plainString(tolerance) + "%");
            }
        }

        if (cash == null) {
            return Evaluation.unavailable("cash.actual",
                    "Actual cash is unavailable, so payment readiness cannot be confirmed; "
                            + "missing data is not treated as zero");
        }
        return Evaluation.of(KurAlertState.GREEN,
                "Reserve is fully funded and actual cash covers the approved minimum: "
                        + moneyString(qualifyingReserve) + " reserved against installment #"
                        + obligation.installmentNumber() + " due " + obligation.dueDate()
                        + " (" + obligation.daysRemaining() + " day(s) remaining)");
    }

    private List<String> assumptions(LocalDate asOf, KurAlertPolicy policy, boolean policyConfigured) {
        List<String> assumptions = new ArrayList<>();
        assumptions.add("cash source: " + CASH_SOURCE + " as of " + asOf
                + "; no forward cash-flow forecast is applied");
        assumptions.add("reserve source: reserve movements recorded on or before " + asOf
                + " (earmarked plus separate reserve account, no double counting)");
        assumptions.add("obligation: first KUR installment with an unpaid balance as of " + asOf
                + "; payment totals are counted only through " + asOf);
        assumptions.add("reserved funds are earmarked only; installment payment status is reported "
                + "separately and a reserve is never a payment");
        assumptions.add("framework level: v1 evaluates no forward forecast, so framework §8's 30-day "
                + "projected-shortfall test and 90-day runway condition are not evaluated; "
                + "frameworkLevel is unavailable and a v1 green is not framework GREEN. The Finance "
                + "Controller continues the 90-day forecast and manual escalation outside v1");
        if (policyConfigured) {
            assumptions.add("thresholds approved by " + policy.getApprovedBy()
                    + " under reference " + policy.getApprovalReference()
                    + " (recorded " + policy.getApprovedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ")");
        } else {
            assumptions.add("threshold policy is unconfigured; no threshold value was assumed");
        }
        return assumptions;
    }

    @Transactional(readOnly = true)
    public KurAlertResponse.Policy policyView(java.util.UUID loanId) {
        return policyDto(policyRepository.findByLoanId(loanId).orElse(null));
    }

    private KurAlertResponse.Policy policyDto(KurAlertPolicy policy) {
        Map<String, String> thresholds = new LinkedHashMap<>();
        thresholds.put("minimumCash", policy == null ? null : moneyString(policy.getMinimumCash()));
        thresholds.put("targetReserve", policy == null ? null : moneyString(policy.getTargetReserve()));
        thresholds.put("fundingPaceTolerancePercent",
                policy == null ? null : plainString(policy.getFundingPaceTolerancePercent()));
        thresholds.put("orangeShortfall", policy == null ? null : moneyString(policy.getOrangeShortfall()));
        thresholds.put("redShortfall", policy == null ? null : moneyString(policy.getRedShortfall()));
        if (policy == null || !policy.isConfigured()) {
            return new KurAlertResponse.Policy("unconfigured", null, null, null, thresholds);
        }
        return new KurAlertResponse.Policy(
                "configured",
                policy.getApprovalReference(),
                policy.getApprovedBy(),
                policy.getApprovedAt() == null ? null : policy.getApprovedAt().toString(),
                thresholds);
    }

    private static BigDecimal amount(KurReserveResponse response, String key) {
        KurReserveResponse.Figure figure = response.figures().get(key);
        if (figure == null || figure.value() == null) {
            return null;
        }
        return new BigDecimal(figure.value());
    }

    private static String status(KurReserveResponse response, String key) {
        KurReserveResponse.Figure figure = response.figures().get(key);
        return figure == null ? "unavailable" : figure.status();
    }

    private static boolean isPaid(KurInstallmentStatus status) {
        return status == KurInstallmentStatus.PAID || status == KurInstallmentStatus.OVERPAID;
    }

    private static String moneyString(BigDecimal value) {
        return value == null ? null : KurMoneyMath.money(value).toPlainString();
    }

    private static String plainString(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private record Evaluation(KurAlertState state, String reason, String missingKey) {

        static Evaluation of(KurAlertState state, String reason) {
            return new Evaluation(state, reason, null);
        }

        static Evaluation unavailable(String missingKey, String reason) {
            return new Evaluation(KurAlertState.UNAVAILABLE, reason, missingKey);
        }
    }
}
