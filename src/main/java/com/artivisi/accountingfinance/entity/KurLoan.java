package com.artivisi.accountingfinance.entity;

import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.enums.KurLoanStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "kur_loans")
@Getter
@Setter
@NoArgsConstructor
public class KurLoan extends TimestampedEntity {

    @Size(max = 100)
    @Column(name = "lender", nullable = false, length = 100)
    private String lender;

    @Size(max = 50)
    @Column(name = "product", nullable = false, length = 50)
    private String product = "KUR";

    @Size(max = 100)
    @Column(name = "loan_reference", length = 100)
    private String loanReference;

    @NotNull
    @Size(max = 200)
    @Column(name = "borrower_name", nullable = false, length = 200)
    private String borrowerName;

    @Size(max = 200)
    @Column(name = "borrower_legal_owner", length = 200)
    private String borrowerLegalOwner;

    @NotNull
    @Column(name = "principal_original", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalOriginal;

    @Column(name = "disbursement_date")
    private LocalDate disbursementDate;

    @Column(name = "term_months", nullable = false)
    private Integer termMonths;

    @Column(name = "installment_contractual", precision = 19, scale = 2)
    private BigDecimal installmentContractual;

    @Size(max = 20)
    @Column(name = "installment_frequency", nullable = false, length = 20)
    private String installmentFrequency = "MONTHLY";

    @Column(name = "installment_count", nullable = false)
    private Integer installmentCount;

    @Column(name = "first_due_date")
    private LocalDate firstDueDate;

    @Column(name = "monthly_due_day")
    private Integer monthlyDueDay;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "fees", precision = 19, scale = 2)
    private BigDecimal fees;

    @Column(name = "agreement_document_id")
    private UUID agreementDocumentId;

    @Column(name = "schedule_document_id")
    private UUID scheduleDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private KurLoanStatus status = KurLoanStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 30)
    private KurLoanProvenance provenance = KurLoanProvenance.FOUNDER_ESTIMATE;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("installmentNumber ASC")
    private List<KurInstallment> installments = new ArrayList<>();

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("entryDate ASC")
    private List<KurReserveEntry> reserveEntries = new ArrayList<>();

    public boolean hasVerifiedDocuments() {
        return agreementDocumentId != null && scheduleDocumentId != null;
    }
}
