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
import java.time.LocalDateTime;

/**
 * Threshold policy for the next-installment KUR repayment alert (FAS-96).
 *
 * <p>Values may only be written together with the approval reference recorded
 * when the CEO and Finance Controller approved them. A row is
 * {@link #isConfigured() configured} only when every threshold and the approval
 * evidence are present; otherwise the read API reports the policy as
 * {@code unconfigured} and never renders green.
 */
@Entity
@Table(name = "kur_alert_policies")
@Getter
@Setter
@NoArgsConstructor
public class KurAlertPolicy extends TimestampedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false, unique = true)
    private KurLoan loan;

    @Column(name = "minimum_cash", precision = 19, scale = 2)
    private BigDecimal minimumCash;

    @Column(name = "target_reserve", precision = 19, scale = 2)
    private BigDecimal targetReserve;

    @Column(name = "funding_pace_tolerance_percent", precision = 7, scale = 4)
    private BigDecimal fundingPaceTolerancePercent;

    @Column(name = "orange_shortfall", precision = 19, scale = 2)
    private BigDecimal orangeShortfall;

    @Column(name = "red_shortfall", precision = 19, scale = 2)
    private BigDecimal redShortfall;

    @Size(max = 200)
    @Column(name = "approval_reference", length = 200)
    private String approvalReference;

    @Size(max = 200)
    @Column(name = "approved_by", length = 200)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    public boolean isConfigured() {
        return minimumCash != null
                && targetReserve != null
                && fundingPaceTolerancePercent != null
                && orangeShortfall != null
                && redShortfall != null
                && approvalReference != null && !approvalReference.isBlank()
                && approvedBy != null && !approvedBy.isBlank()
                && approvedAt != null;
    }
}
