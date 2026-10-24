package com.artivisi.accountingfinance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "kur_payments")
@Getter
@Setter
@NoArgsConstructor
public class KurPayment extends TimestampedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "installment_id", nullable = false)
    private KurInstallment installment;

    @NotNull
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Size(max = 100)
    @Column(name = "bank_ref", length = 100)
    private String bankRef;

    @NotNull
    @Size(max = 200)
    @Column(name = "evidence_ref", nullable = false, length = 200)
    private String evidenceRef;

    @NotNull
    @Size(max = 200)
    @Column(name = "source_ref", nullable = false, length = 200)
    private String sourceRef;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Size(max = 300)
    @Column(name = "reversal_reason", length = 300)
    private String reversalReason;

    public boolean isReversed() {
        return reversedAt != null;
    }
}
