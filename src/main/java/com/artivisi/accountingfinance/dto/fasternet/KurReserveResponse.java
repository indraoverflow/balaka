package com.artivisi.accountingfinance.dto.fasternet;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/fasternet/v1/kur/reserve} response, contract
 * {@code fasternet.dashboard/v1} §3.3.
 *
 * <p>Qualifying reserve = earmarked + separately held reserve account, without
 * double counting the same cash. {@code reserve.gap = max(0, obligation.nextDue -
 * qualifying reserve)}. Reserving funds never marks an installment paid.
 */
public record KurReserveResponse(
        String contractVersion,
        String generatedAt,
        String asOf,
        String currency,
        Map<String, Figure> figures,
        String invariant,
        List<Missing> missing
) {

    public record Figure(String value, String status) {
    }

    public record Missing(String key, String reason, String owner) {
    }
}
