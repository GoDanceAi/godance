package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import ai.godance.config.KeyVaultProperties;

/** What V42.1 keeps for each stored value. The run itself is in WebhookSecretVaultIntegrationTest. */
class WebhookSigningSecretVaultMigrationTest {

    private static final KeyVault VAULT = new KeyVault(new KeyVaultProperties(Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8))));

    private final WebhookSigningSecretVaultMigration migration = new WebhookSigningSecretVaultMigration(VAULT);

    @Test
    void sealed_givenSecretInClear_expectsKeyVaultValueOfItForThatContext() {
        String kept = migration.sealed("whsec_saved_in_clear", "webhook:u-1:current");

        assertThat(kept).startsWith("v1:").doesNotContain("whsec_saved_in_clear");
        assertThat(new String(VAULT.decrypt(kept, "webhook:u-1:current"), StandardCharsets.UTF_8))
                .isEqualTo("whsec_saved_in_clear");
    }

    @Test
    void sealed_givenKeyVaultValue_expectsItUnchanged() {
        String stored = VAULT.encrypt("whsec_already_sealed".getBytes(StandardCharsets.UTF_8), "webhook:u-1:current");

        assertThat(migration.sealed(stored, "webhook:u-1:current")).isEqualTo(stored);
    }

    @Test
    void sealed_givenNullOrBlank_expectsNull() {
        assertThat(migration.sealed(null, "webhook:u-1:current")).isNull();
        assertThat(migration.sealed("", "webhook:u-1:current")).isNull();
        assertThat(migration.sealed("   ", "webhook:u-1:previous")).isNull();
    }

    @Test
    void getVersion_givenV42_expectsRightAfterIt() {
        // V42 widens the columns this fills; V43 is the next whole number free.
        assertThat(migration.getVersion()).isEqualTo(MigrationVersion.fromVersion("42.1"));
        assertThat(migration.canExecuteInTransaction()).isTrue();
    }
}
