package com.myshop.springshop.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class ProductionConfigGuard {

    private final Environment environment;
    private final String adminPassword;

    public ProductionConfigGuard(
            Environment environment,
            @Value("${app.admin.password:admin}") String adminPassword
    ) {
        this.environment = environment;
        this.adminPassword = adminPassword == null ? "" : adminPassword.trim();
    }

    @PostConstruct
    public void validateProductionSecrets() {
        boolean prodProfile = Arrays.stream(environment.getActiveProfiles())
                .anyMatch("prod"::equalsIgnoreCase);
        if (!prodProfile) {
            return;
        }

        if (isWeakAdminPassword(adminPassword)) {
            throw new IllegalStateException(
                    "Unsafe production config: ADMIN_PASSWORD is default/weak. Set a strong value in .env."
            );
        }
    }

    private boolean isWeakAdminPassword(String password) {
        if (password.isBlank()) {
            return true;
        }
        if ("admin".equalsIgnoreCase(password) || "change-me".equalsIgnoreCase(password)) {
            return true;
        }
        if (password.startsWith("{bcrypt}")) {
            return password.length() < 30;
        }
        return password.length() < 10;
    }

}
