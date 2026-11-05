package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.entity.JournalTemplate;
import com.artivisi.accountingfinance.entity.JournalTemplateLine;
import com.artivisi.accountingfinance.enums.CashFlowCategory;
import com.artivisi.accountingfinance.enums.JournalPosition;
import com.artivisi.accountingfinance.enums.TemplateCategory;
import com.artivisi.accountingfinance.enums.TemplateType;
import com.artivisi.accountingfinance.repository.JournalTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification tests for FAS-139 / FAS-97 / FAS-124 staged KUR journal templates.
 * Verifies that all 5 required templates (K-1, K-2, K-2 accrual, K-3, K-4) are provisioned,
 * staged (active = false), correctly typed, and have complete DEBIT and CREDIT lines.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("KUR staged journal templates (FAS-139 / FAS-97)")
class KurJournalTemplateIntegrationTest {

    @Autowired
    private JournalTemplateRepository templateRepository;

    @Test
    @DisplayName("K-1: Pencairan KUR BRI template exists, is staged/inactive, and has 4 lines")
    void testDisbursementTemplateK1() {
        Optional<JournalTemplate> opt = templateRepository.findAll().stream()
                .filter(t -> "Pencairan KUR BRI (K-1)".equals(t.getTemplateName()))
                .findFirst();

        assertThat(opt).isPresent();
        JournalTemplate template = opt.get();

        assertThat(template.getActive()).isFalse();
        assertThat(template.getCategory()).isEqualTo(TemplateCategory.RECEIPT);
        assertThat(template.getCashFlowCategory()).isEqualTo(CashFlowCategory.FINANCING);
        assertThat(template.getTemplateType()).isEqualTo(TemplateType.DETAILED);

        List<JournalTemplateLine> lines = template.getLines();
        assertThat(lines).hasSize(4);

        // Line 1: Debit 1.1.10
        JournalTemplateLine line1 = lines.get(0);
        assertThat(line1.getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(line1.getAccount().getAccountCode()).isEqualTo("1.1.10");
        assertThat(line1.getFormula()).isEqualTo("gross_principal - fee_withheld");

        // Line 2: Debit 5.2.04
        JournalTemplateLine line2 = lines.get(1);
        assertThat(line2.getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(line2.getAccount().getAccountCode()).isEqualTo("5.2.04");
        assertThat(line2.getFormula()).isEqualTo("fee_withheld");

        // Line 3: Credit 2.1.30
        JournalTemplateLine line3 = lines.get(2);
        assertThat(line3.getPosition()).isEqualTo(JournalPosition.CREDIT);
        assertThat(line3.getAccount().getAccountCode()).isEqualTo("2.1.30");
        assertThat(line3.getFormula()).isEqualTo("current_portion");

        // Line 4: Credit 2.2.01
        JournalTemplateLine line4 = lines.get(3);
        assertThat(line4.getPosition()).isEqualTo(JournalPosition.CREDIT);
        assertThat(line4.getAccount().getAccountCode()).isEqualTo("2.2.01");
        assertThat(line4.getFormula()).isEqualTo("non_current_portion");
    }

    @Test
    @DisplayName("K-2: Angsuran KUR BRI template exists, is staged/inactive, and has 4 lines")
    void testInstallmentTemplateK2() {
        Optional<JournalTemplate> opt = templateRepository.findAll().stream()
                .filter(t -> "Angsuran KUR BRI (K-2)".equals(t.getTemplateName()))
                .findFirst();

        assertThat(opt).isPresent();
        JournalTemplate template = opt.get();

        assertThat(template.getActive()).isFalse();
        assertThat(template.getCategory()).isEqualTo(TemplateCategory.EXPENSE);
        assertThat(template.getCashFlowCategory()).isEqualTo(CashFlowCategory.FINANCING);
        assertThat(template.getTemplateType()).isEqualTo(TemplateType.DETAILED);

        List<JournalTemplateLine> lines = template.getLines();
        assertThat(lines).hasSize(4);

        // Line 1: Debit 2.1.30 (principal)
        JournalTemplateLine line1 = lines.get(0);
        assertThat(line1.getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(line1.getAccount().getAccountCode()).isEqualTo("2.1.30");
        assertThat(line1.getFormula()).isEqualTo("principal");

        // Line 2: Debit 5.2.03 (interest)
        JournalTemplateLine line2 = lines.get(1);
        assertThat(line2.getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(line2.getAccount().getAccountCode()).isEqualTo("5.2.03");
        assertThat(line2.getFormula()).isEqualTo("interest");

        // Line 3: Debit 5.2.04 (fee)
        JournalTemplateLine line3 = lines.get(2);
        assertThat(line3.getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(line3.getAccount().getAccountCode()).isEqualTo("5.2.04");
        assertThat(line3.getFormula()).isEqualTo("fee");

        // Line 4: Credit 1.1.10 (cash)
        JournalTemplateLine line4 = lines.get(3);
        assertThat(line4.getPosition()).isEqualTo(JournalPosition.CREDIT);
        assertThat(line4.getAccount().getAccountCode()).isEqualTo("1.1.10");
        assertThat(line4.getFormula()).isEqualTo("cash_paid");
    }

    @Test
    @DisplayName("K-2 §6.7: Akrual Bunga KUR Akhir Bulan template exists and has 3 lines")
    void testAccrualTemplateK2() {
        Optional<JournalTemplate> opt = templateRepository.findAll().stream()
                .filter(t -> "Akrual Bunga KUR Akhir Bulan (K-2)".equals(t.getTemplateName()))
                .findFirst();

        assertThat(opt).isPresent();
        JournalTemplate template = opt.get();

        assertThat(template.getActive()).isFalse();
        assertThat(template.getCategory()).isEqualTo(TemplateCategory.EXPENSE);
        assertThat(template.getCashFlowCategory()).isEqualTo(CashFlowCategory.OPERATING);
        assertThat(template.getTemplateType()).isEqualTo(TemplateType.DETAILED);

        List<JournalTemplateLine> lines = template.getLines();
        assertThat(lines).hasSize(3);

        // Line 1: Debit 5.2.03 (interest)
        assertThat(lines.get(0).getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(lines.get(0).getAccount().getAccountCode()).isEqualTo("5.2.03");

        // Line 2: Debit 5.2.04 (fee)
        assertThat(lines.get(1).getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(lines.get(1).getAccount().getAccountCode()).isEqualTo("5.2.04");

        // Line 3: Credit 2.1.31 (accrued liability)
        assertThat(lines.get(2).getPosition()).isEqualTo(JournalPosition.CREDIT);
        assertThat(lines.get(2).getAccount().getAccountCode()).isEqualTo("2.1.31");
    }

    @Test
    @DisplayName("K-3: Reklasifikasi Pokok KUR Lancar template exists and has 2 lines")
    void testReclassTemplateK3() {
        Optional<JournalTemplate> opt = templateRepository.findAll().stream()
                .filter(t -> "Reklasifikasi Pokok KUR Lancar (K-3)".equals(t.getTemplateName()))
                .findFirst();

        assertThat(opt).isPresent();
        JournalTemplate template = opt.get();

        assertThat(template.getActive()).isFalse();
        assertThat(template.getCategory()).isEqualTo(TemplateCategory.TRANSFER);
        assertThat(template.getCashFlowCategory()).isEqualTo(CashFlowCategory.OPERATING);
        assertThat(template.getTemplateType()).isEqualTo(TemplateType.SIMPLE);

        List<JournalTemplateLine> lines = template.getLines();
        assertThat(lines).hasSize(2);

        // Line 1: Debit 2.2.01 (reduce non-current)
        assertThat(lines.get(0).getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(lines.get(0).getAccount().getAccountCode()).isEqualTo("2.2.01");

        // Line 2: Credit 2.1.30 (increase current)
        assertThat(lines.get(1).getPosition()).isEqualTo(JournalPosition.CREDIT);
        assertThat(lines.get(1).getAccount().getAccountCode()).isEqualTo("2.1.30");
    }

    @Test
    @DisplayName("K-4: Biaya dan Denda KUR template exists and has 2 lines")
    void testFeeTemplateK4() {
        Optional<JournalTemplate> opt = templateRepository.findAll().stream()
                .filter(t -> "Biaya dan Denda KUR (K-4)".equals(t.getTemplateName()))
                .findFirst();

        assertThat(opt).isPresent();
        JournalTemplate template = opt.get();

        assertThat(template.getActive()).isFalse();
        assertThat(template.getCategory()).isEqualTo(TemplateCategory.EXPENSE);
        assertThat(template.getCashFlowCategory()).isEqualTo(CashFlowCategory.OPERATING);
        assertThat(template.getTemplateType()).isEqualTo(TemplateType.SIMPLE);

        List<JournalTemplateLine> lines = template.getLines();
        assertThat(lines).hasSize(2);

        // Line 1: Debit 5.2.04
        assertThat(lines.get(0).getPosition()).isEqualTo(JournalPosition.DEBIT);
        assertThat(lines.get(0).getAccount().getAccountCode()).isEqualTo("5.2.04");

        // Line 2: Credit 1.1.10
        assertThat(lines.get(1).getPosition()).isEqualTo(JournalPosition.CREDIT);
        assertThat(lines.get(1).getAccount().getAccountCode()).isEqualTo("1.1.10");
    }
}
