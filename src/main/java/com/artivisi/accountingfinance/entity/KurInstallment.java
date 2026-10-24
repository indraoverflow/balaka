package com.artivisi.accountingfinance.entity;

import com.artivisi.accountingfinance.enums.KurInstallmentStatus;
import com.artivisi.accountingfinance.enums.KurScheduleSourceKind;
import com.artivisi.accountingfinance.util.KurMoneyMath;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

@Entity
@Table(name = "kur_installments")
@Getter
@Setter
@NoArgsConstructor
public class KurInstallment extends TimestampedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false)
    private KurLoan loan;

    @NotNull
    @Column(name = "installment_number", nullable = false)
    private Integer installmentNumber;

    @NotNull
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "principal", precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "interest", precision = 19, scale = 2)
    private BigDecimal interest;

    @Column(name = "fee", precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO.setScale(2);

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private KurInstallmentStatus status = KurInstallmentStatus.UNPAID;

    @Size(max = 100)
    @Column(name = "bank_ref", length = 100)
    private String bankRef;

    @Size(max = 200)
    @Column(name = "evidence_ref", length = 200)
    private String evidenceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 30)
    private KurScheduleSourceKind sourceKind = KurScheduleSourceKind.ESTIMATE;

    @NotNull
    @Size(max = 200)
    @Column(name = "source_ref", nullable = false, length = 200)
    private String sourceRef;

    @OneToMany(mappedBy = "installment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("paymentDate ASC")
    private List<KurPayment> payments = new ArrayList<>();

    public BigDecimal getOutstanding() {
        return KurMoneyMath.outstanding(amount, paidAmount);
    }

    /**
     * Recomputes the denormalised paid total and status from the payment rows.
     * Reversed payments are excluded, so a correction never destroys history.
     */
    public void recalculateFromPayments() {
        BigDecimal activePaid = payments.stream()
                .filter(payment -> payment.getReversedAt() == null)
                .map(KurPayment::getAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        this.paidAmount = KurMoneyMath.money(activePaid);
        this.status = KurMoneyMath.installmentStatus(amount, activePaid);
        this.paidDate = payments.stream()
                .filter(payment -> payment.getReversedAt() == null)
                .map(KurPayment::getPaymentDate)
                .max(LocalDate::compareTo)
                .orElse(null);
    }
}
