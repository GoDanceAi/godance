package ai.godance.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;

/**
 * {@code GODANCE_ADMIN_EMAILS} only ever fills an empty seat: it promotes
 * while nobody can act as an admin, and is ignored afterwards, so a role
 * revoked in the console stays revoked across restarts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class AdminBootstrapIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String CONFIGURED = "configured@godance.test";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.admin.emails", () -> CONFIGURED);
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired AdminBootstrap bootstrap;
    @Autowired AdminConsoleService console;
    @Autowired UserRepository users;
    @Autowired AuditLogEntryRepository audit;

    @BeforeEach
    void noAdminsYet() {
        users.findAll().forEach(u -> {
            u.setRole(UserRole.USER);
            users.save(u);
        });
    }

    @Test
    void bootstrapAdmins_givenNoAdmin_promotesVerifiedAccountAndAudits() {
        User u = account(UserRole.USER, true, true);

        // Spacing and case as an operator might type them in the env var.
        List<String> promoted = console.bootstrapAdmins(List.of("  " + u.getEmail().toUpperCase() + " ", ""));

        assertThat(promoted).containsExactly(u.getEmail());
        User after = users.findById(u.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(after.getSessionVersion()).isEqualTo(u.getSessionVersion() + 1);
        AuditLogEntry row = audit.findByEventType(AuditLogService.EVT_ADMIN_ROLE_CHANGED).stream()
                .filter(r -> u.getId().equals(r.getUserId()))
                .findFirst().orElseThrow();
        assertThat(row.getEventDetail())
                .containsEntry("adminEmail", "bootstrap:GODANCE_ADMIN_EMAILS")
                .containsEntry("fromRole", "USER")
                .containsEntry("toRole", "ADMIN");
    }

    @Test
    void bootstrapAdmins_givenUnverifiedOrDisabledAccount_promotesNobody() {
        User unverified = account(UserRole.USER, false, true);
        User disabled = account(UserRole.USER, true, false);

        assertThat(console.bootstrapAdmins(List.of(unverified.getEmail(), disabled.getEmail()))).isEmpty();

        assertThat(users.findById(unverified.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
        assertThat(users.findById(disabled.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void bootstrapAdmins_givenAnAdminExists_leavesARevokedAccountRevoked() {
        account(UserRole.ADMIN, true, true);
        User revoked = account(UserRole.USER, true, true);

        assertThat(console.bootstrapAdmins(List.of(revoked.getEmail()))).isEmpty();

        assertThat(users.findById(revoked.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void bootstrapAdmins_givenTheOnlyAdminIsDisabled_recovers() {
        account(UserRole.ADMIN, true, false);
        User replacement = account(UserRole.USER, true, true);

        assertThat(console.bootstrapAdmins(List.of(replacement.getEmail()))).containsExactly(replacement.getEmail());
    }

    @Test
    void run_readsTheConfiguredAddresses() {
        User u = account(CONFIGURED, UserRole.USER, true, true);

        bootstrap.run(new DefaultApplicationArguments());

        assertThat(users.findById(u.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
    }

    private User account(UserRole role, boolean emailVerified, boolean active) {
        return account("bootstrap-" + UUID.randomUUID() + "@godance.test", role, emailVerified, active);
    }

    private User account(String email, UserRole role, boolean emailVerified, boolean active) {
        User u = users.findByEmail(email).orElseGet(User::new);
        u.setEmail(email);
        u.setFullName("Bootstrap Test");
        u.setRole(role);
        u.setEmailVerified(emailVerified);
        u.setActive(active);
        return users.save(u);
    }
}
