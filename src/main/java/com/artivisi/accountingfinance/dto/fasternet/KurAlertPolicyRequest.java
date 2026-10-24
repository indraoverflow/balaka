package com.artivisi.accountingfinance.dto.fasternet;

import java.math.BigDecimal;

/**
 * Body of {@code PUT /api/fasternet/v1/kur/alert-policy} (FAS-96).
 *
 * <p>Every threshold and the approval evidence must be supplied together. There
 * are no server-side defaults: a missing approval reference is rejected instead
 * of silently keeping an unapproved policy.
 */
public record KurAlertPolicyRequest(
        BigDecimal minimumCash,
        BigDecimal targetReserve,
        BigDecimal fundingPaceTolerancePercent,
        BigDecimal orangeShortfall,
        BigDecimal redShortfall,
        String approvalReference,
        String approvedBy
) {
}
