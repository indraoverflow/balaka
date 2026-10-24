package com.artivisi.accountingfinance.security;

import com.artivisi.accountingfinance.entity.User;
import com.artivisi.accountingfinance.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class DefaultAdminProductionGuard implements ApplicationRunner {

    static final String PRODUCTION_PROFILE = "production";
    static final String DEFAULT_ADMIN_USERNAME = "admin";
    static final String DEFAULT_ADMIN_PASSWORD = "admin";
    static final UUID SEEDED_ADMIN_ID = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");

    private final Environment environment;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DefaultAdminProductionGuard(Environment environment,
                                       UserRepository userRepository,
                                       PasswordEncoder passwordEncoder) {
        this.environment = environment;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    void validate() {
        if (!environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            return;
        }
        Optional<User> seededOrDefault = userRepository.findByUsername(DEFAULT_ADMIN_USERNAME)
                .filter(this::isDefaultCredential);
        if (seededOrDefault.isPresent()) {
            throw new IllegalStateException(
                    "Default admin/admin credentials are present in the production profile; "
                            + "remove the seeded admin and create a unique operator account before starting");
        }
    }

    private boolean isDefaultCredential(User user) {
        if (SEEDED_ADMIN_ID.equals(user.getId())) {
            return true;
        }
        String passwordHash = user.getPassword();
        return passwordHash != null && passwordEncoder.matches(DEFAULT_ADMIN_PASSWORD, passwordHash);
    }
}
