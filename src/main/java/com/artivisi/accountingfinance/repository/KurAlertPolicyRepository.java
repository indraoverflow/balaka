package com.artivisi.accountingfinance.repository;

import com.artivisi.accountingfinance.entity.KurAlertPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KurAlertPolicyRepository extends JpaRepository<KurAlertPolicy, UUID> {

    Optional<KurAlertPolicy> findByLoanId(UUID loanId);
}
