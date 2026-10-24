package com.artivisi.accountingfinance.dto.fasternet;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Read-only FASTERNET dashboard envelope, contract {@code fasternet.dashboard/v1}.
 *
 * <p>Money values are decimal strings, never JSON numbers. A figure that cannot be
 * derived from the ledger is emitted with {@code value: null} and
 * {@code status: "unavailable"}, and is listed in {@link #missing()}. No value is ever
 * defaulted to zero.
 */
public record FasternetOverviewResponse(
        String contractVersion,
        String generatedAt,
        String asOf,
        Period period,
        String currency,
        Company company,
        Map<String, Figure> figures,
        Series series,
        List<Missing> missing
) {

    public record Period(String from, String to, String label, String status) {
    }

    public record Company(String id, String name) {
    }

    public record Figure(
            String key,
            String label,
            String value,
            String unit,
            String status,
            Map<String, String> period,
            Source source,
            String notes
    ) {
    }

    public record Source(String system, String kind, String ref, String computedBy) {
    }

    public record Series(
            String status,
            List<String> periods,
            List<String> cash,
            List<String> reserve,
            List<String> revenue,
            List<String> expense,
            @JsonProperty("cash.accounts") List<CashAccount> cashAccounts
    ) {
    }

    public record CashAccount(String accountCode, String accountName, String balance, String status) {
    }

    public record Missing(String key, String reason, String owner) {
    }
}
