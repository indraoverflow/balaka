package com.artivisi.accountingfinance.repository;

import com.artivisi.accountingfinance.entity.KurLoan;
import com.artivisi.accountingfinance.enums.KurLoanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KurLoanRepository extends JpaRepository<KurLoan, UUID> {

    List<KurLoan> findByStatusOrderByCreatedAtAsc(KurLoanStatus status);

    List<KurLoan> findAllByOrderByCreatedAtAsc();
}
