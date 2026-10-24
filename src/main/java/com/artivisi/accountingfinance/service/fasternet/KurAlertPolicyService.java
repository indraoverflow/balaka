package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.entity.KurAlertPolicy;
import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.repository.KurAlertPolicyRepository;
import com.artivisi.accountingfinance.repository.KurLoanRepository;
import com.artivisi.accountingfinance.util.KurMoneyMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores the CEO + Finance Controller-approved threshold policy for the KUR
 * repayment alert (FAS-96).
 *
 * <p>There are no defaults and no partial policies: every threshold and the
 * approval evidence must be saved together. Until then the alert read path
 * reports {@code unconfigured} and never renders green.
 */
@Service
@RequiredArgsConstructor
public class KurAlertPolicyService {

    private static final int PERCENT_SCALE = 4;

    private final KurAlertPolicyRepository policyRepository;
    private final KurLoanRepository loanRepository;

    public record PolicyCommand(
            BigDecimal minimumCash,
            BigDecimal targetReserve,
            BigDecimal fundingPaceTolerancePercent,
            BigDecimal orangeShortfall,
            BigDecimal redShortfall,
            String approvalReference,
            String approvedBy
    ) {
    }

    @Transactional(readOnly = true)
    public Optional<KurAlertPolicy> findPolicy(UUID loanId) {
        return policyRepository.findByLoanId(loanId);
    }

    @Transactional
    public KurAlertPolicy savePolicy(UUID loanId, PolicyCommand command) {
        Objects.requireNonNull(command, "command");
        KurLoan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown KUR loan: " + loanId));

        requireCompletePolicy(command);
        String approvalReference = command.approvalReference().trim();
        String approvedBy = command.approvedBy().trim();
        BigDecimal minimumCash = nonNegativeMoney(command.minimumCash(), "minimumCash");
        BigDecimal targetReserve = nonNegativeMoney(command.targetReserve(), "targetReserve");
        BigDecimal tolerance = tolerancePercent(command.fundingPaceTolerancePercent());
        BigDecimal orange = positiveMoney(command.orangeShortfall(), "orangeShortfall");
        BigDecimal red = positiveMoney(command.redShortfall(), "redShortfall");
        if (red.compareTo(orange) <= 0) {
            throw new IllegalArgumentException("redShortfall must be greater than orangeShortfall");
        }

        KurAlertPolicy policy = policyRepository.findByLoanId(loanId).orElseGet(() -> {
            KurAlertPolicy created = new KurAlertPolicy();
            created.setLoan(loan);
            return created;
        });

        boolean unchanged = policy.isConfigured()
                && sameMoney(policy.getMinimumCash(), minimumCash)
                && sameMoney(policy.getTargetReserve(), targetReserve)
                && samePercent(policy.getFundingPaceTolerancePercent(), tolerance)
                && sameMoney(policy.getOrangeShortfall(), orange)
                && sameMoney(policy.getRedShortfall(), red)
                && approvalReference.equals(policy.getApprovalReference())
                && approvedBy.equals(policy.getApprovedBy());
        if (unchanged) {
            return policy;
        }

        policy.setMinimumCash(minimumCash);
        policy.setTargetReserve(targetReserve);
        policy.setFundingPaceTolerancePercent(tolerance);
        policy.setOrangeShortfall(orange);
        policy.setRedShortfall(red);
        policy.setApprovalReference(approvalReference);
        policy.setApprovedBy(approvedBy);
        policy.setApprovedAt(LocalDateTime.now());
        return policyRepository.save(policy);
    }

    private static void requireCompletePolicy(PolicyCommand command) {
        if (command.minimumCash() == null
                || command.targetReserve() == null
                || command.fundingPaceTolerancePercent() == null
                || command.orangeShortfall() == null
                || command.redShortfall() == null) {
            throw new IllegalArgumentException(
                    "minimumCash, targetReserve, fundingPaceTolerancePercent, orangeShortfall and "
                            + "redShortfall are all required; a partial policy cannot be stored");
        }
        if (command.approvalReference() == null || command.approvalReference().isBlank()) {
            throw new IllegalArgumentException(
                    "approvalReference is required: values must be approved by the CEO and Finance Controller");
        }
        if (command.approvedBy() == null || command.approvedBy().isBlank()) {
            throw new IllegalArgumentException("approvedBy is required");
        }
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String field) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return KurMoneyMath.money(value);
    }

    private static BigDecimal positiveMoney(BigDecimal value, String field) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
        return KurMoneyMath.money(value);
    }

    private static BigDecimal tolerancePercent(BigDecimal value) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("fundingPaceTolerancePercent must be between 0 and 100");
        }
        return value.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean samePercent(BigDecimal left, BigDecimal right) {
        return sameMoney(left, right);
    }
}
