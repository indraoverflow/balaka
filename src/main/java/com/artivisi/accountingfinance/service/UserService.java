package com.artivisi.accountingfinance.service;

import com.artivisi.accountingfinance.entity.User;
import com.artivisi.accountingfinance.enums.Role;
import com.artivisi.accountingfinance.exception.FinanceRoleAssignmentException;
import com.artivisi.accountingfinance.repository.UserRepository;
import com.artivisi.accountingfinance.security.CurrentUser;
import com.artivisi.accountingfinance.security.FinanceRolePolicy;
import com.artivisi.accountingfinance.security.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private static final String USER_NOT_FOUND = "User not found: ";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Page<User> findAll(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<User> search(String search, Pageable pageable) {
        if (search == null || search.isBlank()) {
            return userRepository.findAll(pageable);
        }
        return userRepository.findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                search, search, search, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional
    public User create(User user, Set<Role> roles) {
        if (FinanceRolePolicy.containsFinanceRole(roles)
                && !FinanceRolePolicy.actorHoldsFinanceAuthority()) {
            throw new FinanceRoleAssignmentException(
                    "Only an OWNER or FINANCE_CONTROLLER can grant finance roles (OWNER, FINANCE_CONTROLLER).");
        }

        // Validate unique constraints
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("Username already exists: " + user.getUsername());
        }
        if (user.getEmail() != null && !user.getEmail().isBlank() && userRepository.existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + user.getEmail());
        }

        // Encode password
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setPasswordLastChanged(java.time.LocalDateTime.now());

        // Set roles
        String currentUser = getCurrentUsername();
        user.setRoles(roles, currentUser);

        User savedUser = userRepository.save(user);
        log.info("Created user: {} with roles: {}", LogSanitizer.username(savedUser.getUsername()), roles);
        return savedUser;
    }

    @Transactional
    public User update(UUID id, User updated, Set<Role> roles) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND + id));

        Set<Role> currentRoles = user.getRoles();
        String currentUser = getCurrentUsername();

        // Separation of duties: nobody changes their own roles, and only an actor who
        // already holds finance authority may manage a finance-role account or move a
        // finance role on/off any account. See FinanceRolePolicy.
        if (user.getUsername().equals(currentUser) && !currentRoles.equals(roles)) {
            throw new FinanceRoleAssignmentException(
                    "You cannot change the roles of your own account; another authorized user must do it.");
        }
        if (FinanceRolePolicy.containsFinanceRole(currentRoles)
                && !FinanceRolePolicy.actorHoldsFinanceAuthority()) {
            throw new FinanceRoleAssignmentException(
                    "Only an OWNER or FINANCE_CONTROLLER can manage an account that holds a finance role.");
        }
        Set<Role> changedFinanceRoles = FinanceRolePolicy.changedFinanceRoles(currentRoles, roles);
        if (!changedFinanceRoles.isEmpty() && !FinanceRolePolicy.actorHoldsFinanceAuthority()) {
            throw new FinanceRoleAssignmentException(
                    "Only an OWNER or FINANCE_CONTROLLER can grant or remove finance roles: " + changedFinanceRoles);
        }

        // Check username uniqueness if changed
        if (!user.getUsername().equals(updated.getUsername()) &&
            userRepository.existsByUsername(updated.getUsername())) {
            throw new IllegalArgumentException("Username already exists: " + updated.getUsername());
        }

        // Check email uniqueness if changed
        if (updated.getEmail() != null && !updated.getEmail().isBlank() &&
            !updated.getEmail().equals(user.getEmail()) &&
            userRepository.existsByEmail(updated.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + updated.getEmail());
        }

        // Update fields
        user.setUsername(updated.getUsername());
        user.setFullName(updated.getFullName());
        user.setEmail(updated.getEmail());
        user.setActive(updated.getActive());

        // Update roles
        user.setRoles(roles, currentUser);

        User savedUser = userRepository.save(user);
        log.info("Updated user: {} with roles: {}", LogSanitizer.username(savedUser.getUsername()), roles);
        return savedUser;
    }

    @Transactional
    public void changePassword(UUID id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND + id));

        requireFinanceAuthorityForFinanceAccount(user, "change the password of");

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordLastChanged(java.time.LocalDateTime.now());
        userRepository.save(user);
        log.info("Changed password for user: {}", LogSanitizer.username(user.getUsername()));
    }

    @Transactional
    public void toggleActive(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND + id));

        requireFinanceAuthorityForFinanceAccount(user, "change the status of");

        user.setActive(!user.getActive());
        userRepository.save(user);
        log.info("Toggled active status for user: {} to {}", LogSanitizer.username(user.getUsername()), user.getActive());
    }

    @Transactional
    public void delete(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND + id));

        requireFinanceAuthorityForFinanceAccount(user, "delete");

        // Prevent self-deletion
        String currentUser = getCurrentUsername();
        if (user.getUsername().equals(currentUser)) {
            throw new IllegalArgumentException("Cannot delete your own account");
        }

        userRepository.delete(user);
        log.info("Deleted user: {}", LogSanitizer.username(user.getUsername()));
    }

    @Transactional(readOnly = true)
    public List<User> findAllActive() {
        return userRepository.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public long count() {
        return userRepository.count();
    }

    private void requireFinanceAuthorityForFinanceAccount(User user, String action) {
        if (FinanceRolePolicy.containsFinanceRole(user.getRoles())
                && !FinanceRolePolicy.actorHoldsFinanceAuthority()) {
            throw new FinanceRoleAssignmentException(
                    "Only an OWNER or FINANCE_CONTROLLER can " + action
                            + " an account that holds a finance role.");
        }
    }

    private String getCurrentUsername() {
        return CurrentUser.name();
    }
}
