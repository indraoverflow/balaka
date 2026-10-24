package com.artivisi.accountingfinance.entity;

import com.artivisi.accountingfinance.enums.KurReserveEntryKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "kur_reserve_entries")
@Getter
@Setter
@NoArgsConstructor
public class KurReserveEntry extends TimestampedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false)
    private KurLoan loan;

    @NotNull
    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private KurReserveEntryKind kind;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Size(max = 300)
    @Column(name = "description", length = 300)
    private String description;

    @NotNull
    @Size(max = 200)
    @Column(name = "source_ref", nullable = false, length = 200)
    private String sourceRef;
}
