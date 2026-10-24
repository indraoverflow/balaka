package com.artivisi.accountingfinance.service;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.entity.User;
import com.artivisi.accountingfinance.enums.Role;
import com.artivisi.accountingfinance.exception.FinanceRoleAssignmentException;
import com.artivisi.accountingfinance.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for UserService.
 * Tests actual database queries and business logic.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("UserService Integration Tests")
@WithMockUser(username = "testadmin", roles = {"ADMIN"})
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Nested
    @DisplayName("Find Operations")
    class FindOperationsTests {

        @Test
        @DisplayName("findAll should return paginated results")
        void findAllShouldReturnPaginatedResults() {
            createTestUser();
            createTestUser();

            Page<User> page = userService.findAll(PageRequest.of(0, 10));

            assertThat(page.getContent()).isNotEmpty();
            assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("findById should return user")
        void findByIdShouldReturnUser() {
            User user = createTestUser();

            Optional<User> found = userService.findById(user.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getUsername()).isEqualTo(user.getUsername());
        }

        @Test
        @DisplayName("findById should return empty for invalid ID")
        void findByIdShouldReturnEmptyForInvalidId() {
            UUID invalidId = UUID.randomUUID();

            Optional<User> found = userService.findById(invalidId);

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("findByUsername should return user")
        void findByUsernameShouldReturnUser() {
            User user = createTestUser();

            Optional<User> found = userService.findByUsername(user.getUsername());

            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(user.getId());
        }

        @Test
        @DisplayName("findByUsername should return empty for invalid username")
        void findByUsernameShouldReturnEmptyForInvalidUsername() {
            Optional<User> found = userService.findByUsername("nonexistent");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("search should find by username")
        void searchShouldFindByUsername() {
            User user = buildTestUser();
            user.setUsername("uniqueuser123");
            userService.create(user, Set.of(Role.STAFF));

            Page<User> page = userService.search("unique", PageRequest.of(0, 10));

            assertThat(page.getContent()).isNotEmpty();
            assertThat(page.getContent()).anyMatch(u -> u.getUsername().contains("unique"));
        }

        @Test
        @DisplayName("search should find by full name")
        void searchShouldFindByFullName() {
            User user = buildTestUser();
            user.setFullName("Bambang Unique Name");
            userService.create(user, Set.of(Role.STAFF));

            Page<User> page = userService.search("Bambang Unique", PageRequest.of(0, 10));

            assertThat(page.getContent()).isNotEmpty();
            assertThat(page.getContent()).anyMatch(u -> u.getFullName().contains("Bambang"));
        }

        @Test
        @DisplayName("search should find by email")
        void searchShouldFindByEmail() {
            User user = buildTestUser();
            user.setEmail("unique.search@test.com");
            userService.create(user, Set.of(Role.STAFF));

            Page<User> page = userService.search("unique.search", PageRequest.of(0, 10));

            assertThat(page.getContent()).isNotEmpty();
            assertThat(page.getContent()).anyMatch(u ->
                u.getEmail() != null && u.getEmail().contains("unique.search"));
        }

        @Test
        @DisplayName("search should return all when search is blank")
        void searchShouldReturnAllWhenSearchIsBlank() {
            createTestUser();
            createTestUser();

            Page<User> page = userService.search("", PageRequest.of(0, 10));

            assertThat(page.getContent()).isNotEmpty();
        }

        @Test
        @DisplayName("search should return all when search is null")
        void searchShouldReturnAllWhenSearchIsNull() {
            createTestUser();

            Page<User> page = userService.search(null, PageRequest.of(0, 10));

            assertThat(page.getContent()).isNotEmpty();
        }

        @Test
        @DisplayName("findAllActive should return only active users")
        void findAllActiveShouldReturnOnlyActiveUsers() {
            User active = createTestUser();
            User inactive = createTestUser();
            userService.toggleActive(inactive.getId());

            List<User> activeUsers = userService.findAllActive();

            assertThat(activeUsers)
                    .allMatch(User::getActive)
                    .anyMatch(u -> u.getId().equals(active.getId()))
                    .noneMatch(u -> u.getId().equals(inactive.getId()));
        }
    }

    @Nested
    @DisplayName("Create User")
    class CreateUserTests {

        @Test
        @DisplayName("create should save user with encoded password")
        void createShouldSaveWithEncodedPassword() {
            User user = buildTestUser();
            String rawPassword = user.getPassword();

            User saved = userService.create(user, Set.of(Role.STAFF));

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getPassword()).isNotEqualTo(rawPassword);
            assertThat(passwordEncoder.matches(rawPassword, saved.getPassword())).isTrue();
        }

        @Test
        @DisplayName("create should assign roles")
        void createShouldAssignRoles() {
            User user = buildTestUser();
            Set<Role> roles = Set.of(Role.ACCOUNTANT, Role.STAFF);

            User saved = userService.create(user, roles);

            assertThat(saved.getRoles()).containsExactlyInAnyOrderElementsOf(roles);
            assertThat(saved.hasRole(Role.ACCOUNTANT)).isTrue();
            assertThat(saved.hasRole(Role.STAFF)).isTrue();
        }

        @Test
        @DisplayName("create should throw for duplicate username")
        void createShouldThrowForDuplicateUsername() {
            User first = createTestUser();

            User second = buildTestUser();
            second.setUsername(first.getUsername());

            assertThatThrownBy(() -> userService.create(second, Set.of(Role.STAFF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username already exists");
        }

        @Test
        @DisplayName("create should throw for duplicate email")
        void createShouldThrowForDuplicateEmail() {
            User first = buildTestUser();
            first.setEmail("duplicate@test.com");
            userService.create(first, Set.of(Role.STAFF));

            User second = buildTestUser();
            second.setEmail("duplicate@test.com");

            assertThatThrownBy(() -> userService.create(second, Set.of(Role.STAFF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email already exists");
        }

        @Test
        @DisplayName("create should allow null email")
        void createShouldAllowNullEmail() {
            User user = buildTestUser();
            user.setEmail(null);

            User saved = userService.create(user, Set.of(Role.STAFF));

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getEmail()).isNull();
        }

        @Test
        @DisplayName("create should allow blank email")
        void createShouldAllowBlankEmail() {
            User user = buildTestUser();
            user.setEmail("");

            User saved = userService.create(user, Set.of(Role.STAFF));

            assertThat(saved.getId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Update User")
    class UpdateUserTests {

        @Test
        @DisplayName("update should modify user fields")
        void updateShouldModifyFields() {
            User user = createTestUser();

            User updateData = buildUpdateData(user);
            updateData.setFullName("Updated Full Name");
            updateData.setEmail("updated@test.com");

            User updated = userService.update(user.getId(), updateData, Set.of(Role.STAFF));

            assertThat(updated.getFullName()).isEqualTo("Updated Full Name");
            assertThat(updated.getEmail()).isEqualTo("updated@test.com");
        }

        @Test
        @DisplayName("update should allow changing username if not duplicate")
        void updateShouldAllowChangingUsername() {
            User user = createTestUser();
            String newUsername = "newuser" + System.nanoTime();

            User updateData = buildUpdateData(user);
            updateData.setUsername(newUsername);

            User updated = userService.update(user.getId(), updateData, Set.of(Role.STAFF));

            assertThat(updated.getUsername()).isEqualTo(newUsername);
        }

        @Test
        @DisplayName("update should throw for duplicate username")
        void updateShouldThrowForDuplicateUsername() {
            User first = createTestUser();
            User second = createTestUser();

            User updateData = buildUpdateData(second);
            updateData.setUsername(first.getUsername());

            assertThatThrownBy(() -> userService.update(second.getId(), updateData, Set.of(Role.STAFF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username already exists");
        }

        @Test
        @DisplayName("update should throw for duplicate email")
        void updateShouldThrowForDuplicateEmail() {
            User first = buildTestUser();
            first.setEmail("existing@test.com");
            userService.create(first, Set.of(Role.STAFF));

            User second = createTestUser();

            User updateData = buildUpdateData(second);
            updateData.setEmail("existing@test.com");

            assertThatThrownBy(() -> userService.update(second.getId(), updateData, Set.of(Role.STAFF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email already exists");
        }

        @Test
        @DisplayName("update should allow keeping same email")
        void updateShouldAllowKeepingSameEmail() {
            User user = buildTestUser();
            user.setEmail("same@test.com");
            user = userService.create(user, Set.of(Role.STAFF));

            User updateData = buildUpdateData(user);
            updateData.setEmail("same@test.com");
            updateData.setFullName("Updated Name");

            User updated = userService.update(user.getId(), updateData, Set.of(Role.STAFF));

            assertThat(updated.getFullName()).isEqualTo("Updated Name");
            assertThat(updated.getEmail()).isEqualTo("same@test.com");
        }

        @Test
        @DisplayName("update should update roles")
        void updateShouldUpdateRoles() {
            User user = createTestUser();

            User updateData = buildUpdateData(user);
            Set<Role> newRoles = Set.of(Role.ADMIN, Role.ACCOUNTANT);

            User updated = userService.update(user.getId(), updateData, newRoles);

            assertThat(updated.getRoles()).containsExactlyInAnyOrderElementsOf(newRoles);
        }

        @Test
        @DisplayName("update should throw for non-existent user")
        void updateShouldThrowForNonExistentUser() {
            UUID invalidId = UUID.randomUUID();
            User updateData = buildTestUser();

            assertThatThrownBy(() -> userService.update(invalidId, updateData, Set.of(Role.STAFF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
        }

        @Test
        @DisplayName("update should allow null email without duplicate check")
        void updateShouldAllowNullEmail() {
            User user = createTestUser();

            User updateData = buildUpdateData(user);
            updateData.setEmail(null);
            updateData.setFullName("Updated With Null Email");

            User updated = userService.update(user.getId(), updateData, Set.of(Role.STAFF));

            assertThat(updated.getFullName()).isEqualTo("Updated With Null Email");
            assertThat(updated.getEmail()).isNull();
        }

        @Test
        @DisplayName("update should allow blank email without duplicate check")
        void updateShouldAllowBlankEmail() {
            User user = createTestUser();

            User updateData = buildUpdateData(user);
            updateData.setEmail("");
            updateData.setFullName("Updated With Blank Email");

            User updated = userService.update(user.getId(), updateData, Set.of(Role.STAFF));

            assertThat(updated.getFullName()).isEqualTo("Updated With Blank Email");
        }
    }

    @Nested
    @DisplayName("Password Operations")
    class PasswordOperationsTests {

        @Test
        @DisplayName("changePassword should encode new password")
        void changePasswordShouldEncodeNewPassword() {
            User user = createTestUser();
            String newPassword = "newpassword123";

            userService.changePassword(user.getId(), newPassword);

            User updated = userRepository.findById(user.getId()).orElseThrow();
            assertThat(passwordEncoder.matches(newPassword, updated.getPassword())).isTrue();
        }

        @Test
        @DisplayName("changePassword should throw for non-existent user")
        void changePasswordShouldThrowForNonExistentUser() {
            UUID invalidId = UUID.randomUUID();

            assertThatThrownBy(() -> userService.changePassword(invalidId, "newpassword"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
        }
    }

    @Nested
    @DisplayName("Toggle Active")
    class ToggleActiveTests {

        @Test
        @DisplayName("toggleActive should deactivate active user")
        void toggleActiveShouldDeactivateActiveUser() {
            User user = createTestUser();
            assertThat(user.getActive()).isTrue();

            userService.toggleActive(user.getId());

            User updated = userRepository.findById(user.getId()).orElseThrow();
            assertThat(updated.getActive()).isFalse();
        }

        @Test
        @DisplayName("toggleActive should activate inactive user")
        void toggleActiveShouldActivateInactiveUser() {
            User user = createTestUser();
            userService.toggleActive(user.getId()); // Deactivate

            userService.toggleActive(user.getId()); // Activate again

            User updated = userRepository.findById(user.getId()).orElseThrow();
            assertThat(updated.getActive()).isTrue();
        }

        @Test
        @DisplayName("toggleActive should throw for non-existent user")
        void toggleActiveShouldThrowForNonExistentUser() {
            UUID invalidId = UUID.randomUUID();

            assertThatThrownBy(() -> userService.toggleActive(invalidId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
        }
    }

    @Nested
    @DisplayName("Delete User")
    class DeleteUserTests {

        @Test
        @DisplayName("delete should remove user")
        void deleteShouldRemoveUser() {
            User user = createTestUser();
            UUID userId = user.getId();

            userService.delete(userId);

            assertThat(userRepository.findById(userId)).isEmpty();
        }

        @Test
        @DisplayName("delete should throw for non-existent user")
        void deleteShouldThrowForNonExistentUser() {
            UUID invalidId = UUID.randomUUID();

            assertThatThrownBy(() -> userService.delete(invalidId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
        }

        @Test
        @WithMockUser(username = "selfuser", roles = {"ADMIN"})
        @DisplayName("delete should throw when deleting self")
        void deleteShouldThrowWhenDeletingSelf() {
            User user = buildTestUser();
            user.setUsername("selfuser");
            user = userService.create(user, Set.of(Role.ADMIN));
            UUID userId = user.getId();

            assertThatThrownBy(() -> userService.delete(userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot delete your own account");
        }
    }

    @Nested
    @DisplayName("Finance Role Separation of Duties")
    class FinanceRoleSeparationTests {

        @Test
        @WithMockUser(username = "selfadmin", roles = {"ADMIN"})
        @DisplayName("ADMIN cannot grant itself FINANCE_CONTROLLER")
        void adminCannotGrantFinanceControllerToSelf() {
            User self = createUserWithRoles("selfadmin", Role.ADMIN);
            User updateData = buildUpdateData(self);

            assertThatThrownBy(() -> userService.update(self.getId(), updateData,
                    Set.of(Role.ADMIN, Role.FINANCE_CONTROLLER)))
                    .isInstanceOf(FinanceRoleAssignmentException.class)
                    .hasMessageContaining("own account");

            assertThat(userRepository.findById(self.getId()).orElseThrow().getRoles())
                    .containsExactly(Role.ADMIN);
        }

        @Test
        @WithMockUser(username = "selfadmin", roles = {"ADMIN"})
        @DisplayName("ADMIN cannot grant itself OWNER")
        void adminCannotGrantOwnerToSelf() {
            User self = createUserWithRoles("selfadmin", Role.ADMIN);
            User updateData = buildUpdateData(self);

            assertThatThrownBy(() -> userService.update(self.getId(), updateData,
                    Set.of(Role.ADMIN, Role.OWNER)))
                    .isInstanceOf(FinanceRoleAssignmentException.class)
                    .hasMessageContaining("own account");

            assertThat(userRepository.findById(self.getId()).orElseThrow().getRoles())
                    .containsExactly(Role.ADMIN);
        }

        @Test
        @DisplayName("ADMIN cannot grant a finance role to another account")
        void adminCannotGrantFinanceRoleToAnotherAccount() {
            User target = createUserWithRoles("finance-target", Role.STAFF);
            User updateData = buildUpdateData(target);

            assertThatThrownBy(() -> userService.update(target.getId(), updateData,
                    Set.of(Role.FINANCE_CONTROLLER)))
                    .isInstanceOf(FinanceRoleAssignmentException.class)
                    .hasMessageContaining("finance role");

            assertThat(userRepository.findById(target.getId()).orElseThrow().getRoles())
                    .containsExactly(Role.STAFF);
        }

        @Test
        @DisplayName("ADMIN cannot create an account that already holds a finance role")
        void adminCannotCreateFinanceRoleAccount() {
            User user = buildTestUser();

            assertThatThrownBy(() -> userService.create(user, Set.of(Role.FINANCE_CONTROLLER)))
                    .isInstanceOf(FinanceRoleAssignmentException.class)
                    .hasMessageContaining("finance roles");

            assertThat(userRepository.findByUsername(user.getUsername())).isEmpty();
        }

        @Test
        @WithMockUser(username = "fc-actor", roles = {"FINANCE_CONTROLLER"})
        @DisplayName("FINANCE_CONTROLLER can grant a finance role")
        void financeControllerCanGrantFinanceRole() {
            User target = createUserWithRoles("finance-grantee", Role.STAFF);
            User updateData = buildUpdateData(target);

            User updated = userService.update(target.getId(), updateData,
                    Set.of(Role.FINANCE_CONTROLLER));

            assertThat(updated.getRoles()).containsExactly(Role.FINANCE_CONTROLLER);
        }

        @Test
        @WithMockUser(username = "owner-self", roles = {"OWNER"})
        @DisplayName("Nobody can change their own roles, even with finance authority")
        void ownerCannotChangeOwnRoles() {
            User self = createUserWithRoles("owner-self", Role.OWNER);
            User updateData = buildUpdateData(self);

            assertThatThrownBy(() -> userService.update(self.getId(), updateData,
                    Set.of(Role.OWNER, Role.FINANCE_CONTROLLER)))
                    .isInstanceOf(FinanceRoleAssignmentException.class)
                    .hasMessageContaining("own account");

            assertThat(userRepository.findById(self.getId()).orElseThrow().getRoles())
                    .containsExactly(Role.OWNER);
        }

        @Test
        @DisplayName("ADMIN cannot change the password of a finance-role account")
        void adminCannotResetFinanceAccountPassword() {
            User finance = createUserWithRoles("finance-password", Role.FINANCE_CONTROLLER);
            String originalPassword = finance.getPassword();

            assertThatThrownBy(() -> userService.changePassword(finance.getId(), "NewPassword#123"))
                    .isInstanceOf(FinanceRoleAssignmentException.class)
                    .hasMessageContaining("finance role");

            assertThat(userRepository.findById(finance.getId()).orElseThrow().getPassword())
                    .isEqualTo(originalPassword);
        }

        @Test
        @WithMockUser(username = "fc-actor", roles = {"FINANCE_CONTROLLER"})
        @DisplayName("FINANCE_CONTROLLER can reset a finance-role password")
        void financeControllerCanResetFinanceAccountPassword() {
            User finance = createUserWithRoles("finance-password-reset", Role.FINANCE_CONTROLLER);

            userService.changePassword(finance.getId(), "NewPassword#123");

            assertThat(passwordEncoder.matches("NewPassword#123",
                    userRepository.findById(finance.getId()).orElseThrow().getPassword())).isTrue();
        }

        @Test
        @DisplayName("ADMIN cannot deactivate a finance-role account")
        void adminCannotDeactivateFinanceAccount() {
            User finance = createUserWithRoles("finance-active", Role.OWNER);

            assertThatThrownBy(() -> userService.toggleActive(finance.getId()))
                    .isInstanceOf(FinanceRoleAssignmentException.class)
                    .hasMessageContaining("finance role");

            assertThat(userRepository.findById(finance.getId()).orElseThrow().getActive()).isTrue();
        }

        @Test
        @DisplayName("ADMIN cannot delete a finance-role account")
        void adminCannotDeleteFinanceAccount() {
            User finance = createUserWithRoles("finance-delete", Role.FINANCE_CONTROLLER);

            assertThatThrownBy(() -> userService.delete(finance.getId()))
                    .isInstanceOf(FinanceRoleAssignmentException.class)
                    .hasMessageContaining("finance role");

            assertThat(userRepository.findById(finance.getId())).isPresent();
        }

        @Test
        @DisplayName("ADMIN can still edit a non-finance account without changing its roles")
        void adminCanEditNonFinanceAccount() {
            User target = createUserWithRoles("plain-target", Role.STAFF);
            User updateData = buildUpdateData(target);
            updateData.setFullName("Plain Target Updated");

            User updated = userService.update(target.getId(), updateData, Set.of(Role.STAFF));

            assertThat(updated.getFullName()).isEqualTo("Plain Target Updated");
            assertThat(updated.getRoles()).containsExactly(Role.STAFF);
        }
    }

    @Nested
    @DisplayName("Count Operations")
    class CountOperationsTests {

        @Test
        @DisplayName("count should return total user count")
        void countShouldReturnTotalCount() {
            long initialCount = userService.count();

            createTestUser();
            createTestUser();

            long newCount = userService.count();

            assertThat(newCount).isEqualTo(initialCount + 2);
        }
    }

    // Helper methods

    private User createTestUser() {
        User user = buildTestUser();
        return userService.create(user, Set.of(Role.STAFF));
    }

    private User createUserWithRoles(String username, Role... roles) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setFullName("Test " + username);
        user.setEmail(username + "@test.com");
        user.setActive(true);
        user.setRoles(Set.of(roles), "system");
        return userRepository.save(user);
    }

    private User buildTestUser() {
        User user = new User();
        user.setUsername("user" + System.nanoTime());
        user.setPassword("password123");
        user.setFullName("Test User " + System.currentTimeMillis());
        user.setActive(true);
        return user;
    }

    private User buildUpdateData(User existing) {
        User updateData = new User();
        updateData.setUsername(existing.getUsername());
        updateData.setFullName(existing.getFullName());
        updateData.setEmail(existing.getEmail());
        updateData.setActive(existing.getActive());
        return updateData;
    }
}
