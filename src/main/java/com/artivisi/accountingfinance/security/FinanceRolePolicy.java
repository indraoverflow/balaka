package com.artivisi.accountingfinance.security;

import com.artivisi.accountingfinance.enums.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Separation-of-duties policy for roles that carry finance authority.
 *
 * <p>ADMIN deliberately does not hold {@code KUR_WRITE}/{@code KUR_VERIFY}, but it
 * does hold {@code USER_EDIT}. Without a guard, an administrator could grant itself
 * (or an account whose password it controls) {@code FINANCE_CONTROLLER} or
 * {@code OWNER} and obtain finance-write authority without a second approver.
 *
 * <p>This class defines the narrow technical rule that keeps the role boundary
 * intact:
 * <ul>
 *   <li>{@code FINANCE_CONTROLLER} and {@code OWNER} are finance roles.</li>
 *   <li>Only an actor who already holds one of those roles may grant or remove a
 *       finance role, or otherwise manage a finance-role account.</li>
 *   <li>No actor may change the role set of their own account.</li>
 * </ul>
 *
 * <p>The actor's roles are read from the authenticated request authorities
 * ({@code ROLE_*}); this guard is used by session-bound user-management code only.
 * The rule is intentionally fail-closed: an absent or anonymous authentication is
 * not finance authority.
 *
 * <p>The final role-permission matrix still requires CEO and Finance Controller
 * approval. See {@code docs/fasternet/FAS-106-role-assignment-policy.md}.
 */
public final class FinanceRolePolicy {

    public static final Set<Role> FINANCE_ROLES =
            EnumSet.of(Role.FINANCE_CONTROLLER, Role.OWNER);

    private FinanceRolePolicy() {
    }

    public static boolean isFinanceRole(Role role) {
        return FINANCE_ROLES.contains(role);
    }

    public static boolean containsFinanceRole(Collection<Role> roles) {
        return roles != null && roles.stream().anyMatch(FINANCE_ROLES::contains);
    }

    /**
     * Finance roles that are present on only one side of a role change.
     */
    public static Set<Role> changedFinanceRoles(Collection<Role> before, Collection<Role> after) {
        Set<Role> changed = new TreeSet<>();
        if (before != null) {
            changed.addAll(before);
        }
        if (after != null) {
            changed.addAll(after);
        }
        changed.removeIf(role -> before != null && after != null
                && before.contains(role) && after.contains(role));
        changed.retainAll(FINANCE_ROLES);
        return changed;
    }

    /**
     * Whether the authenticated actor currently holds a finance role.
     */
    public static boolean actorHoldsFinanceAuthority() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (("ROLE_" + Role.FINANCE_CONTROLLER.name()).equals(name)
                    || ("ROLE_" + Role.OWNER.name()).equals(name)) {
                return true;
            }
        }
        return false;
    }
}
