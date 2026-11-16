package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.UserRepository;
import ai.godance.security.JwtService;
import ai.godance.security.KeyVault;
import ai.godance.security.WebhookSigner;
import ai.godance.service.AccountClosureService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Webhook signing secrets at rest (backend CLAUDE.md, hard constraint 2):
 * kept only through the key vault, bound to the account and the slot, the
 * same secret for their owner as before, and never logged.
 *
 * <p>The database starts as the release before V42 left it, with secrets
 * saved in clear, so V42.1 encrypts them when the application starts, as
 * it will in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class WebhookSecretVaultIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final SecureRandom RNG = new SecureRandom();
    private static final String BODY = "{\"event\":\"video.generation.completed\"}";

    // Accounts saved in clear by the release before V42.
    static final UUID LEGACY = UUID.randomUUID();
    static final String LEGACY_SECRET = newSecret();
    static final UUID LEGACY_ROTATED = UUID.randomUUID();
    static final String LEGACY_CURRENT = newSecret();
    static final String LEGACY_PREVIOUS = newSecret();
    static final Instant LEGACY_ROTATED_AT = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
    static final UUID LEGACY_BLANK = UUID.randomUUID();

    @BeforeAll
    static void databaseAsTheReleaseBeforeV42LeftIt() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("42"))
                .load()
                .migrate();
        try (Connection db = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement insert = db.prepareStatement("""
                     INSERT INTO users (id, email, full_name, role, webhook_signing_secret,
                                        webhook_signing_secret_previous, webhook_signing_secret_rotated_at)
                     VALUES (?, ?, 'Saved Before V42', 'USER', ?, ?, ?)""")) {
            savedInClear(insert, LEGACY, LEGACY_SECRET, null, null);
            savedInClear(insert, LEGACY_ROTATED, LEGACY_CURRENT, LEGACY_PREVIOUS, LEGACY_ROTATED_AT);
            savedInClear(insert, LEGACY_BLANK, "", null, null);
            insert.executeBatch();
        }
    }

    @Autowired WebTestClient http;
    @Autowired JdbcTemplate jdbc;
    @Autowired KeyVault vault;
    @Autowired WebhookSigner signer;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired AccountClosureService closure;

    // ===== Secrets saved in clear before V42 ===========================

    @Test
    void migration_givenSecretsSavedInClear_expectsEachEncryptedForItsAccountAndSlot(CapturedOutput output) {
        Stored legacy = stored(LEGACY);
        assertThat(legacy.current()).startsWith("v1:").doesNotContain(LEGACY_SECRET);
        assertThat(open(legacy.current(), LEGACY, "current")).isEqualTo(LEGACY_SECRET);
        assertThat(legacy.previous()).isNull();

        Stored rotated = stored(LEGACY_ROTATED);
        assertThat(rotated.current()).startsWith("v1:").doesNotContain(LEGACY_CURRENT);
        assertThat(rotated.previous()).startsWith("v1:").doesNotContain(LEGACY_PREVIOUS);
        assertThat(open(rotated.current(), LEGACY_ROTATED, "current")).isEqualTo(LEGACY_CURRENT);
        assertThat(open(rotated.previous(), LEGACY_ROTATED, "previous")).isEqualTo(LEGACY_PREVIOUS);
        assertThat(rotated.rotatedAt()).isEqualTo(LEGACY_ROTATED_AT);
        // Bound to its account: the same value on another row does not decrypt.
        assertThatThrownBy(() -> open(legacy.current(), LEGACY_ROTATED, "current"))
                .isInstanceOf(IllegalStateException.class);

        // Recorded, so it runs once.
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '42.1'",
                Boolean.class)).isTrue();
        assertNotLogged(output, LEGACY_SECRET, LEGACY_CURRENT, LEGACY_PREVIOUS);
    }

    @Test
    void getSecret_givenAccountSavedInClearBeforeV42_expectsTheSameSecretAsBefore(CapturedOutput output) {
        String storedBefore = stored(LEGACY).current();

        assertThat(secret(bearerOf(LEGACY))).isEqualTo(LEGACY_SECRET);
        assertThat(secret(bearerOf(LEGACY))).isEqualTo(LEGACY_SECRET);
        // Read, not rewritten.
        assertThat(stored(LEGACY).current()).isEqualTo(storedBefore);

        // A rotation from before V42, still in its grace window, signs with both secrets.
        WebhookSigner.SigningMaterial material = signer.materialFor(LEGACY_ROTATED);
        assertThat(material.current()).isEqualTo(LEGACY_CURRENT);
        assertThat(material.previous()).isEqualTo(LEGACY_PREVIOUS);
        assertSignedWith(signer.headerValueFromMaterial(material, BODY), LEGACY_CURRENT, LEGACY_PREVIOUS);
        assertNotLogged(output, LEGACY_SECRET, LEGACY_CURRENT, LEGACY_PREVIOUS);
    }

    @Test
    void getSecret_givenBlankSecretSavedBeforeV42_expectsANewOneAsBefore(CapturedOutput output) {
        // Blank always read as no secret; V42.1 made it NULL, which the new check accepts.
        assertThat(stored(LEGACY_BLANK).current()).isNull();

        String made = secret(bearerOf(LEGACY_BLANK));

        assertThat(made).startsWith("whsec_").hasSize(49);
        assertThat(open(stored(LEGACY_BLANK).current(), LEGACY_BLANK, "current")).isEqualTo(made);
        assertNotLogged(output, made);
    }

    // ===== Accounts using it now ======================================

    @Test
    void getSecret_givenNewAccount_expectsWhsecAndOnlyItsCiphertextStored(CapturedOutput output) {
        Account a = signUp();

        String secret = secret(a.bearer());

        assertThat(secret).startsWith("whsec_").hasSize(49);
        assertThat(secret(a.bearer())).isEqualTo(secret);
        Stored s = stored(a.id());
        assertThat(s.current()).startsWith("v1:").doesNotContain(secret).doesNotContain(secret.substring(6));
        assertThat(open(s.current(), a.id(), "current")).isEqualTo(secret);
        assertThat(s.previous()).isNull();
        assertNotLogged(output, secret);
    }

    @Test
    void rotate_givenSecret_expectsNewSecretAndOldOneSigningForTheGraceWindowOnly(CapturedOutput output) {
        Account a = signUp();
        String before = secret(a.bearer());

        String after = rotate(a.bearer());

        assertThat(after).startsWith("whsec_").isNotEqualTo(before);
        assertThat(secret(a.bearer())).isEqualTo(after);
        Stored s = stored(a.id());
        assertThat(s.current()).startsWith("v1:").doesNotContain(after);
        assertThat(s.previous()).startsWith("v1:").doesNotContain(before);
        assertThat(open(s.current(), a.id(), "current")).isEqualTo(after);
        assertThat(open(s.previous(), a.id(), "previous")).isEqualTo(before);

        // For the grace window, deliveries carry a signature with each secret ...
        assertSignedWith(signer.headerValueFromMaterial(signer.materialFor(a.id()), BODY), after, before);

        // ... and after it, with the new one only.
        jdbc.update("UPDATE users SET webhook_signing_secret_rotated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(WebhookSigner.GRACE_WINDOW).minusSeconds(60)), a.id());
        assertSignedWith(signer.headerValueFromMaterial(signer.materialFor(a.id()), BODY), after);
        assertNotLogged(output, before, after);
    }

    @Test
    void closeAccount_givenCurrentAndPreviousSecrets_expectsBothCleared(CapturedOutput output) {
        Account a = signUp();
        String before = secret(a.bearer());
        String after = rotate(a.bearer());
        assertThat(stored(a.id()).previous()).isNotNull();

        closure.close(a.id(), null, "203.0.113.7");

        Stored s = stored(a.id());
        assertThat(s.current()).isNull();
        assertThat(s.previous()).isNull();
        assertThat(s.rotatedAt()).isNull();
        assertNotLogged(output, before, after);
    }

    @Test
    void database_givenSecretInClear_expectsRefusal() {
        Account a = signUp();

        assertThatThrownBy(() -> jdbc.update("UPDATE users SET webhook_signing_secret = ? WHERE id = ?",
                "whsec_written_in_clear", a.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_webhook_signing_secret_vault");
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET webhook_signing_secret_previous = ? WHERE id = ?",
                "whsec_written_in_clear", a.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_webhook_signing_secret_vault");
    }

    // ===== Helpers =====================================================

    private record Account(UUID id, String bearer) { }

    private record Stored(String current, String previous, Instant rotatedAt) { }

    private Account signUp() {
        String email = "webhook-vault-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Webhook Vault Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return new Account(users.findByEmail(email).orElseThrow().getId(), "Bearer " + resp.accessToken());
    }

    /** A session for an account the test wrote straight to the database. */
    private String bearerOf(UUID userId) {
        return "Bearer " + jwt.issueFor(users.findById(userId).orElseThrow()).token();
    }

    private String secret(String bearer) {
        return secretIn(http.get().uri("/v1/webhooks/secret").header("Authorization", bearer).exchange());
    }

    private String rotate(String bearer) {
        return secretIn(http.post().uri("/v1/webhooks/secret/rotate").header("Authorization", bearer).exchange());
    }

    /** The response is the same as before V42: the secret, and the header it signs. */
    private static String secretIn(WebTestClient.ResponseSpec response) {
        Map<String, String> body = response.expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, String>>() { })
                .returnResult().getResponseBody();
        assertThat(body).containsOnlyKeys("secret", "header").containsEntry("header", WebhookSigner.HEADER);
        return body.get("secret");
    }

    private Stored stored(UUID userId) {
        return jdbc.queryForObject("""
                SELECT webhook_signing_secret, webhook_signing_secret_previous, webhook_signing_secret_rotated_at
                  FROM users
                 WHERE id = ?""", (rs, n) -> new Stored(rs.getString(1), rs.getString(2), instant(rs)), userId);
    }

    private static Instant instant(ResultSet rs) throws SQLException {
        OffsetDateTime at = rs.getObject(3, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    private String open(String stored, UUID userId, String slot) {
        return new String(vault.decrypt(stored, "webhook:" + userId + ":" + slot), StandardCharsets.UTF_8);
    }

    /** The header carries one {@code v1} per secret, in order, each over {@code <t>.<body>}. */
    private static void assertSignedWith(String header, String... secrets) {
        String[] parts = header.split(",");
        assertThat(parts).hasSize(secrets.length + 1);
        long t = Long.parseLong(parts[0].substring(2));
        for (int i = 0; i < secrets.length; i++) {
            assertThat(parts[i + 1]).isEqualTo("v1=" + hmacHex(secrets[i], t + "." + BODY));
        }
    }

    /** Neither a secret nor its random part reached the output, the application's start included. */
    private static void assertNotLogged(CapturedOutput output, String... secrets) {
        String logged = output.getAll();
        for (String secret : secrets) {
            assertThat(logged).doesNotContain(secret).doesNotContain(secret.substring("whsec_".length()));
        }
    }

    private static void savedInClear(PreparedStatement insert, UUID id, String current, String previous,
                                     Instant rotatedAt) throws SQLException {
        insert.setObject(1, id);
        insert.setString(2, "legacy-" + id + "@godance.test");
        insert.setString(3, current);
        insert.setString(4, previous);
        if (rotatedAt == null) {
            insert.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            insert.setObject(5, rotatedAt.atOffset(ZoneOffset.UTC));
        }
        insert.addBatch();
    }

    private static String newSecret() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
