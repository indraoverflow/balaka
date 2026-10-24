package com.artivisi.accountingfinance.repository;

import com.artivisi.accountingfinance.entity.KurInstallment;
import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KurInstallmentRepository extends JpaRepository<KurInstallment, UUID> {

    List<KurInstallment> findByLoanIdOrderByInstallmentNumberAsc(UUID loanId);

    Optional<KurInstallment> findByLoanIdAndInstallmentNumber(UUID loanId, Integer installmentNumber);

    Optional<KurInstallment> findByLoanIdAndSourceKindAndSourceRef(
            UUID loanId, KurScheduleSourceKind sourceKind, String sourceRef);

    Optional<KurInstallment> findFirstByLoanIdAndStatusInOrderByDueDateAsc(
            UUID loanId, Collection<KurInstallmentStatus> statuses);
}
