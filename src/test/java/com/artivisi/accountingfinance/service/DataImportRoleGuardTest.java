package com.artivisi.accountingfinance.service;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.entity.User;
import com.artivisi.accountingfinance.enums.AuditEventType;
import com.artivisi.accountingfinance.enums.Role;
import com.artivisi.accountingfinance.exception.FinanceRoleAssignmentException;
import com.artivisi.accountingfinance.repository.SecurityAuditLogRepository;
import com.artivisi.accountingfinance.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bulk-import boundary coverage for the finance-role separation-of-duties policy
 * (FAS-111 follow-up to FAS-106). The uploaded ZIP must not be able to bypass the
 * guard that {@code UserService} applies to the user-management screens.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("Data import finance role guard")
class DataImportRoleGuardTest {

    @Autowired private DataImportService dataImportService;
    @Autowired private UserRepository userRepository;
    @Autowired private SecurityAuditLogRepository securityAuditLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN", "DATA_IMPORT"})
    @DisplayName("ADMIN cannot import FINANCE_CONTROLLER onto its own account; role is not persisted and attempt is audited")
    void adminCannotImportFinanceRoleOntoItself() throws IOException {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        assertThat(admin.getRoles()).containsExactly(Role.ADMIN);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        byte[] zip = zipOf("29_user_roles.csv",
                "username,role,created_by,created_at\n"
                        + "admin,FINANCE_CONTROLLER,system,\n");

        assertThatThrownBy(() -> dataImportService.importAllData(zip))
                .isInstanceOf(FinanceRoleAssignmentException.class)
                .hasMessageContaining("own account");

        assertThat(userRepository.findByUsername("admin").orElseThrow().getRoles())
                .containsExactly(Role.ADMIN);
        assertThat(securityAuditLogRepository.findByUsernameAndEventTypeAndTimestampAfter(
                "admin", AuditEventType.USER_UPDATED, before))
                .anyMatch(log -> Boolean.FALSE.equals(log.getSuccess())
                        && log.getDetails().startsWith("Rejected role import for user: admin")
                        && log.getDetails().contains("roles [ADMIN] -> [ADMIN, FINANCE_CONTROLLER]")
                        && log.getDetails().contains("by admin"));
    }

    @Test
    @WithMockUser(username = "plain-admin", authorities = {"ROLE_ADMIN", "DATA_IMPORT"})
    @DisplayName("ADMIN cannot import a finance role onto another account")
    void adminCannotImportFinanceRoleOntoAnotherAccount() throws IOException {
        User target = userRepository.findByUsername("staff").orElseThrow();

        byte[] zip = zipOf("29_user_roles.csv",
                "username,role,created_by,created_at\n"
                        + "staff,FINANCE_CONTROLLER,system,\n");

        assertThatThrownBy(() -> dataImportService.importAllData(zip))
                .isInstanceOf(FinanceRoleAssignmentException.class)
                .hasMessageContaining("finance roles");

        assertThat(userRepository.findByUsername(target.getUsername()).orElseThrow().getRoles())
                .containsExactly(Role.STAFF);
    }

    @Test
    @WithMockUser(username = "owner-actor", authorities = {"ROLE_OWNER", "DATA_IMPORT"})
    @DisplayName("OWNER can import a finance role; the audit records actor and role delta")
    void ownerCanImportFinanceRole() throws IOException {
        User target = userRepository.findByUsername("staff").orElseThrow();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        byte[] zip = zipOf("29_user_roles.csv",
                "username,role,created_by,created_at\n"
                        + "staff,FINANCE_CONTROLLER,system,\n");

        DataImportService.ImportResult result = dataImportService.importAllData(zip);

        assertThat(result.totalRecords()).isEqualTo(1);
        assertThat(userRepository.findByUsername(target.getUsername()).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Role.STAFF, Role.FINANCE_CONTROLLER);
        assertThat(securityAuditLogRepository.findByUsernameAndEventTypeAndTimestampAfter(
                "owner-actor", AuditEventType.USER_UPDATED, before))
                .anyMatch(log -> Boolean.TRUE.equals(log.getSuccess())
                        && log.getDetails().startsWith("Imported role change for user: staff")
                        && log.getDetails().contains("[STAFF] -> [FINANCE_CONTROLLER, STAFF]")
                        && log.getDetails().contains("(actor: owner-actor)"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("First-run setup seed import still succeeds with a header-only user-role CSV")
    void setupSeedImportWithEmptyUserRoleCsvSucceeds() throws IOException {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        assertThat(userRepository.count()).isZero();

        byte[] zip = zipOf("29_user_roles.csv", "username,role,created_by,created_at\n");

        DataImportService.ImportResult result = dataImportService.importSeedPack(zip);

        assertThat(result.totalRecords()).isZero();
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Pre-user setup bootstrap may seed a finance role from a bundled pack")
    void bootstrapSeedImportMayAssignFinanceRole() throws IOException {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        assertThat(userRepository.count()).isZero();

        byte[] zip = zipOf(
                "28_users.csv",
                "username,full_name,email,active,created_at\n"
                        + "bootstrap-owner,Bootstrap Owner,owner@example.test,true,\n",
                "29_user_roles.csv",
                "username,role,created_by,created_at\n"
                        + "bootstrap-owner,OWNER,setup-wizard,\n");

        dataImportService.importSeedPack(zip);

        User owner = userRepository.findByUsername("bootstrap-owner").orElseThrow();
        assertThat(owner.getRoles()).containsExactly(Role.OWNER);
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN", "DATA_IMPORT"})
    @DisplayName("An authenticated caller cannot use the seed-pack bootstrap path")
    void authenticatedCallerCannotUseSeedPackPath() throws IOException {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        assertThat(userRepository.count()).isZero();

        byte[] zip = zipOf("29_user_roles.csv",
                "username,role,created_by,created_at\n"
                        + "admin,FINANCE_CONTROLLER,system,\n");

        assertThatThrownBy(() -> dataImportService.importSeedPack(zip))
                .isInstanceOf(FinanceRoleAssignmentException.class)
                .hasMessageContaining("unauthenticated setup wizard");
    }

    private static byte[] zipOf(String... namesAndContents) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zos.putNextEntry(new ZipEntry(namesAndContents[i]));
                zos.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
