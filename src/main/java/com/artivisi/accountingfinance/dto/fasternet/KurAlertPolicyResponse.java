package com.artivisi.accountingfinance.dto.fasternet;

/**
 * Response of {@code PUT /api/fasternet/v1/kur/alert-policy} (FAS-96).
 */
public record KurAlertPolicyResponse(
        String contractVersion,
        String generatedAt,
        String loanId,
        KurAlertResponse.Policy policy
) {
}
