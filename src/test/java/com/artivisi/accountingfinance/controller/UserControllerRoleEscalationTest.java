package com.artivisi.accountingfinance.controller;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.entity.User;
import com.artivisi.accountingfinance.enums.AuditEventType;
import com.artivisi.accountingfinance.enums.Role;
import com.artivisi.accountingfinance.repository.SecurityAuditLogRepository;
import com.artivisi.accountingfinance.repository.UserRepository;
import com.artivisi.accountingfinance.security.CspNonceFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * HTTP-level coverage for the finance-role separation-of-duties guard (FAS-106).
 *
 * <p>Runs without a surrounding test transaction so the controller's rejected-change
 * audit row is written in its own transaction, exactly as it is in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DisplayName("UserController finance role escalation")
class UserControllerRoleEscalationTest {

    private static final String PASSWORD = "Rahasia#2045x";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private UserRepository userRepository;
    @Autowired private SecurityAuditLogRepository securityAuditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean(CspNonceFilter.class))
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(username = "self-admin", authorities = {"USER_VIEW", "USER_EDIT", "ROLE_ADMIN"})
    @DisplayName("ADMIN cannot POST a finance role onto its own account, and the attempt is audited")
    void adminCannotSelfEscalateThroughUpdate() throws Exception {
        User self = createUser("self-admin", Role.ADMIN);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        mockMvc.perform(post("/users/" + self.getId()).with(csrf())
                        .param("username", "self-admin")
                        .param("fullName", self.getFullName())
                        .param("email", self.getEmail())
                        .param("active", "true")
                        .param("selectedRoles", "ADMIN")
                        .param("selectedRoles", "FINANCE_CONTROLLER"))
                .andExpect(status().isOk())
                .andExpect(view().name("users/form"))
                .andExpect(model().attribute("errorMessage", containsString("own account")));

        assertThat(userRepository.findById(self.getId()).orElseThrow().getRoles())
                .containsExactly(Role.ADMIN);
        assertThat(securityAuditLogRepository.findByUsernameAndEventTypeAndTimestampAfter(
                "self-admin", AuditEventType.USER_UPDATED, before))
                .anyMatch(log -> Boolean.FALSE.equals(log.getSuccess())
                        && log.getDetails().startsWith("Rejected role change")
                        && log.getDetails().contains("roles [ADMIN] -> [ADMIN, FINANCE_CONTROLLER]"));
    }

    @Test
    @WithMockUser(username = "plain-admin", authorities = {"USER_VIEW", "USER_EDIT", "ROLE_ADMIN"})
    @DisplayName("ADMIN cannot POST a finance role onto another account")
    void adminCannotGrantFinanceRoleToAnotherAccount() throws Exception {
        User target = createUser("role-target", Role.STAFF);

        mockMvc.perform(post("/users/" + target.getId()).with(csrf())
                        .param("username", target.getUsername())
                        .param("fullName", target.getFullName())
                        .param("email", target.getEmail())
                        .param("active", "true")
                        .param("selectedRoles", "FINANCE_CONTROLLER"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorMessage", containsString("finance role")));

        assertThat(userRepository.findById(target.getId()).orElseThrow().getRoles())
                .containsExactly(Role.STAFF);
    }

    @Test
    @WithMockUser(username = "create-admin", authorities = {"USER_VIEW", "USER_CREATE", "ROLE_ADMIN"})
    @DisplayName("ADMIN cannot create an account with a finance role, and the attempt is audited")
    void adminCannotCreateFinanceRoleAccount() throws Exception {
        String username = "created-finance-" + UUID.randomUUID();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        mockMvc.perform(post("/users").with(csrf())
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("fullName", "Created Finance")
                        .param("email", username + "@example.com")
                        .param("selectedRoles", "FINANCE_CONTROLLER"))
                .andExpect(status().isOk())
                .andExpect(view().name("users/form"))
                .andExpect(model().attribute("errorMessage", containsString("finance roles")));

        assertThat(userRepository.findByUsername(username)).isEmpty();
        assertThat(securityAuditLogRepository.findByUsernameAndEventTypeAndTimestampAfter(
                "create-admin", AuditEventType.USER_CREATED, before))
                .anyMatch(log -> Boolean.FALSE.equals(log.getSuccess())
                        && log.getDetails().startsWith("Rejected user creation"));
    }

    @Test
    @WithMockUser(username = "fc-actor", authorities = {"USER_VIEW", "USER_EDIT", "ROLE_FINANCE_CONTROLLER"})
    @DisplayName("FINANCE_CONTROLLER can grant a finance role; the audit records actor and role delta")
    void financeControllerCanGrantFinanceRoleAndAuditDelta() throws Exception {
        User target = createUser("finance-grantee", Role.STAFF);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        mockMvc.perform(post("/users/" + target.getId()).with(csrf())
                        .param("username", target.getUsername())
                        .param("fullName", target.getFullName())
                        .param("email", target.getEmail())
                        .param("active", "true")
                        .param("selectedRoles", "FINANCE_CONTROLLER"))
                .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(target.getId()).orElseThrow().getRoles())
                .containsExactly(Role.FINANCE_CONTROLLER);
        assertThat(securityAuditLogRepository.findByUsernameAndEventTypeAndTimestampAfter(
                "fc-actor", AuditEventType.USER_UPDATED, before))
                .anyMatch(log -> Boolean.TRUE.equals(log.getSuccess())
                        && log.getDetails().contains("[STAFF] -> [FINANCE_CONTROLLER]")
                        && log.getDetails().contains("(actor: fc-actor)"));
    }

    private User createUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFullName("Test " + username);
        user.setEmail(username + "@example.com");
        user.setActive(true);
        user.setRoles(Set.of(role), "system");
        return userRepository.save(user);
    }
}
