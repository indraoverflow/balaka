package com.artivisi.accountingfinance.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RememberMeKeyProductionGuardTest {

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void rejectsBlankKeyInProduction() {
        var guard = new RememberMeKeyProductionGuard(environment("production"), "");

        var error = assertThrows(IllegalStateException.class, guard::validate);

        assertTrue(error.getMessage().contains("APP_REMEMBER_ME_KEY is required"));
    }

    @Test
    void rejectsMissingKeyInProduction() {
        var guard = new RememberMeKeyProductionGuard(environment("production"), null);

        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    void rejectsDevelopmentDefaultKeyInProduction() {
        var guard = new RememberMeKeyProductionGuard(
                environment("production"), RememberMeKeyProductionGuard.DEV_DEFAULT_KEY);

        var error = assertThrows(IllegalStateException.class, guard::validate);

        assertTrue(error.getMessage().contains("development default"));
    }

    @Test
    void acceptsUniqueKeyInProduction() {
        var guard = new RememberMeKeyProductionGuard(environment("production"), "3vJ7y4m0Kq9uW2pL8sN1xR6tZ5aB4cD7");

        assertDoesNotThrow(guard::validate);
    }

    @Test
    void allowsMissingKeyOutsideProduction() {
        var guard = new RememberMeKeyProductionGuard(environment("test"), "");

        assertDoesNotThrow(guard::validate);
    }
}
