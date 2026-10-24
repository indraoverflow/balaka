package com.artivisi.accountingfinance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class RememberMeKeyProductionGuard implements ApplicationRunner {

    static final String PRODUCTION_PROFILE = "production";
    static final String DEV_DEFAULT_KEY = "dev-remember-me-key";

    private final Environment environment;
    private final String rememberMeKey;

    public RememberMeKeyProductionGuard(Environment environment,
                                        @Value("${app.remember-me.key:}") String rememberMeKey) {
        this.environment = environment;
        this.rememberMeKey = rememberMeKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    void validate() {
        if (!environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            return;
        }
        if (rememberMeKey == null || rememberMeKey.isBlank()) {
            throw new IllegalStateException(
                    "APP_REMEMBER_ME_KEY is required in the production profile; refusing to start with remember-me disabled or unsigned");
        }
        if (DEV_DEFAULT_KEY.equals(rememberMeKey)) {
            throw new IllegalStateException(
                    "APP_REMEMBER_ME_KEY must not use the development default in the production profile");
        }
    }
}
