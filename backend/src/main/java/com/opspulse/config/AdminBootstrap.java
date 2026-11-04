package com.opspulse.config;

import com.opspulse.domain.entity.UserAccount;
import com.opspulse.domain.enums.Role;
import com.opspulse.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Solves the chicken-and-egg problem: every write endpoint needs an ADMIN, so
 * who creates the first admin? Answer: the app itself, on first boot, from
 * environment variables. A public "register" endpoint would let anyone who
 * reaches the server first take it over.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final OpsPulseProperties props;

    @Override
    public void run(ApplicationArguments args) {
        var cfg = props.getBootstrap();
        if (users.count() > 0) return;
        if (!StringUtils.hasText(cfg.getAdminEmail()) || !StringUtils.hasText(cfg.getAdminPassword())) {
            log.warn("No users exist and BOOTSTRAP_ADMIN_EMAIL/PASSWORD are not set -- nobody can log in.");
            return;
        }
        UserAccount admin = new UserAccount();
        admin.setEmail(cfg.getAdminEmail().trim().toLowerCase());
        admin.setFullName("Administrator");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(cfg.getAdminPassword()));
        try {
            users.saveAndFlush(admin);
            log.info("Bootstrapped first admin user {}", admin.getEmail());
        } catch (DataIntegrityViolationException e) {
            // Two replicas booting at once: the other one won. Fine.
            log.info("Admin bootstrap skipped: another instance created it first");
        }
    }
}
