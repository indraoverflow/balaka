package com.artivisi.accountingfinance.repository;

import com.artivisi.accountingfinance.entity.KurPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KurPaymentRepository extends JpaRepository<KurPayment, UUID> {

    Optional<KurPayment> findByInstallmentIdAndSourceRef(UUID installmentId, String sourceRef);

    List<KurPayment> findByInstallmentLoanIdAndReversedAtIsNullAndPaymentDateLessThanEqualOrderByPaymentDateAsc(
            UUID loanId, LocalDate asOf);
}
