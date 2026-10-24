package com.artivisi.accountingfinance.repository;

import com.artivisi.accountingfinance.entity.KurReserveEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KurReserveEntryRepository extends JpaRepository<KurReserveEntry, UUID> {

    Optional<KurReserveEntry> findByLoanIdAndSourceRef(UUID loanId, String sourceRef);

    List<KurReserveEntry> findByLoanIdAndEntryDateLessThanEqualOrderByEntryDateAsc(UUID loanId, LocalDate asOf);

    List<KurReserveEntry> findByLoanIdOrderByEntryDateAsc(UUID loanId);
}
