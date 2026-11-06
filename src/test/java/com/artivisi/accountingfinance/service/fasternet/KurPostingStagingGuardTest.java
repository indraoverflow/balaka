package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.dto.fasternet.KurPostingCommand;
import com.artivisi.accountingfinance.enums.KurLegalBorrowerStructure;
import com.artivisi.accountingfinance.enums.KurLoanProvenance;
import com.artivisi.accountingfinance.exception.KurPostingRejectedException;
import com.artivisi.accountingfinance.repository.JournalTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FAS-139 / FAS-97 §14.1: the posting engine ships staged and inactive. This test runs
 * against the shipped configuration (no override of
 * {@code fasternet.kur.accounting-posting.enabled}), so it guards the decision itself:
 * a build that starts posting without the governance gate fails here.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("KUR posting staging guard (FAS-97 §14.1)")
class KurPostingStagingGuardTest {

    @Autowired
    private KurPostingService postingService;

    @Autowired
    private KurLoanService loanService;

    @Autowired
    private JournalTemplateRepository templateRepository;

    @Test
    @DisplayName("the five KUR journal templates ship staged (active = false)")
    void templatesShipStaged() {
        List<String> names = List.of(
                KurPostingService.TEMPLATE_DISBURSEMENT,
                KurPostingService.TEMPLATE_INSTALLMENT,
                KurPostingService.TEMPLATE_ACCRUAL,
                KurPostingService.TEMPLATE_RECLASS,
                "Biaya dan Denda KUR (K-4)");
        for (String name : names) {
            assertThat(templateRepository.findByTemplateNameAndIsCurrentVersionTrue(name))
                    .as("template %s must exist", name)
                    .isPresent()
                    .get()
                    .satisfies(template -> assertThat(template.getActive()).isFalse());
        }
    }

    @Test
    @DisplayName("the posting engine refuses to post while the staging property is false")
    void postingIsRefusedWhileStaged() {
        var loan = loanService.createLoan(new KurLoanService.CreateLoanCommand(
                "BRI", "KUR", null, "FASTERNET", "Indra",
                new BigDecimal("99270000.00"), LocalDate.of(2026, 10, 21), 36,
                new BigDecimal("3050000.00"), "MONTHLY", 36, LocalDate.of(2026, 11, 21), 21,
                LocalDate.of(2029, 10, 21), BigDecimal.ZERO, null, null,
                KurLoanProvenance.FOUNDER_ESTIMATE, null));
        loanService.setLegalBorrowerStructure(loan.getId(), KurLegalBorrowerStructure.STRUCTURE_A);

        assertThatThrownBy(() -> postingService.postDisbursement(new KurPostingCommand.DisbursementCommand(
                loan.getId(), LocalDate.of(2026, 10, 21), new BigDecimal("99270000.00"),
                BigDecimal.ZERO, "BRI-TRF-DISB-1", "disb-staged", false)))
                .isInstanceOf(KurPostingRejectedException.class)
                .hasMessageContaining(KurPostingService.PROPERTY_ENABLED);
    }
}
