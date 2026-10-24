package com.artivisi.accountingfinance.dto.fasternet;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/fasternet/v1/kur/reserve} response, contract
 * {@code fasternet.dashboard/v1} §3.3.
 *
 * <p>Qualifying reserve = earmarked + separately held reserve account, without
 * double counting the same cash. {@code reserve.gap = max(0, outstanding -
 * qualifying reserve)}, where outstanding is the contractual installment minus
 * posted, non-reversed payments recorded through {@code asOf}. Reserving funds
 * never marks an installment paid.
 *
 * <p>{@code composition} and {@code cashAccounts} are provenance: the reserve
 * tracker rows that make up each bucket, and the cash/bank COA codes included in
 * {@code cash.actual}. An empty list means the source has no such rows; it never
 * means a fabricated zero.
 */
public record KurReserveResponse(
        String contractVersion,
        String generatedAt,
        String asOf,
        String currency,
        Map<String, Figure> figures,
        List<ReserveEntry> composition,
        List<CashAccount> cashAccounts,
        String invariant,
        List<Missing> missing
) {

    public record Figure(String value, String status) {
    }

    /**
     * One reserve-tracker row composing a bucket. {@code pool} is the bucket the
     * row belongs to ({@code EARMARKED} or {@code SEPARATE_ACCOUNT}); the v1
     * tracker does not model a separate account/tag id, so the entry id and source
     * reference are the available provenance.
     */
    public record ReserveEntry(
            String entryId,
            String pool,
            String kind,
            String amount,
            String entryDate,
            String sourceRef
    ) {
    }

    public record CashAccount(String accountCode, String accountName) {
    }

    public record Missing(String key, String reason, String owner) {
    }
}
