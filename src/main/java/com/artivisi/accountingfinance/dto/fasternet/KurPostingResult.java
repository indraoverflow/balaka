package com.artivisi.accountingfinance.dto.fasternet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Result of a KUR accounting posting (FAS-139 / FAS-97 §14).
 *
 * <p>{@code created} is false when the same source reference was already posted and
 * the stored journal is replayed; the replay never adds a second journal.
 * {@code priorPeriod} marks a value date that fell in a closed period and was
 * therefore posted into the current open period with the flag recorded.
 */
public record KurPostingResult(
        String kind,
        UUID loanId,
        UUID transactionId,
        String transactionNumber,
        LocalDate valueDate,
        LocalDate postingDate,
        boolean priorPeriod,
        boolean created,
        BigDecimal amount,
        Map<String, BigDecimal> components,
        String note
) {

    public static KurPostingResult replay(String kind, UUID loanId, UUID transactionId,
                                          String transactionNumber, LocalDate valueDate,
                                          LocalDate postingDate, boolean priorPeriod,
                                          BigDecimal amount, Map<String, BigDecimal> components) {
        return new KurPostingResult(kind, loanId, transactionId, transactionNumber, valueDate,
                postingDate, priorPeriod, false, amount, components,
                "replayed: journal already posted for this source reference");
    }
}
