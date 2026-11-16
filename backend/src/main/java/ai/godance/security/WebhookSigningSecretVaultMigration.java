package ai.godance.security;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flyway V42.1: encrypts the webhook signing secrets saved in clear before
 * V42, then has the database refuse a secret in clear (backend CLAUDE.md,
 * hard constraint 2). V42 widened the columns; this does what SQL can't,
 * because the key is in the environment.
 *
 * <p>A Java migration, and a Spring bean so it gets {@link KeyVault}: Spring
 * Boot hands every {@link JavaMigration} bean to Flyway. Flyway runs it once,
 * in one transaction, before anything serves a request, so this release
 * never meets a secret in clear and none is left in clear. It is also safe
 * to run again: a value that is already a KeyVault value is left as it is.
 * A blank value becomes NULL, which {@link WebhookSigner} always read as no
 * secret. It logs how many accounts it changed, never a value.
 *
 * <p>Its class lives outside {@code db/migration}, so Flyway's own scan does
 * not find it a second time.
 */
@Component
public class WebhookSigningSecretVaultMigration implements JavaMigration {

    private static final Logger log = LoggerFactory.getLogger(WebhookSigningSecretVaultMigration.class);

    private final KeyVault vault;

    public WebhookSigningSecretVaultMigration(KeyVault vault) {
        this.vault = vault;
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("42.1");
    }

    @Override
    public String getDescription() {
        return "webhook signing secrets through the key vault";
    }

    @Override
    public Integer getChecksum() {
        return null;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        int changed = 0;
        try (Statement select = db.createStatement();
             ResultSet rows = select.executeQuery("""
                     SELECT id, webhook_signing_secret, webhook_signing_secret_previous
                       FROM users
                      WHERE webhook_signing_secret IS NOT NULL
                         OR webhook_signing_secret_previous IS NOT NULL
                        FOR UPDATE""");
             PreparedStatement update = db.prepareStatement("""
                     UPDATE users
                        SET webhook_signing_secret = ?, webhook_signing_secret_previous = ?
                      WHERE id = ?""")) {
            while (rows.next()) {
                UUID id = rows.getObject("id", UUID.class);
                String current = rows.getString("webhook_signing_secret");
                String previous = rows.getString("webhook_signing_secret_previous");
                String sealedCurrent = sealed(current, WebhookSigner.currentContext(id));
                String sealedPrevious = sealed(previous, WebhookSigner.previousContext(id));
                if (Objects.equals(sealedCurrent, current) && Objects.equals(sealedPrevious, previous)) {
                    continue;
                }
                update.setString(1, sealedCurrent);
                update.setString(2, sealedPrevious);
                update.setObject(3, id);
                update.addBatch();
                changed++;
            }
            if (changed > 0) {
                update.executeBatch();
            }
        }
        try (Statement ddl = db.createStatement()) {
            ddl.execute("""
                    ALTER TABLE users ADD CONSTRAINT ck_users_webhook_signing_secret_vault CHECK (
                            (webhook_signing_secret IS NULL OR webhook_signing_secret LIKE 'v1:%')
                        AND (webhook_signing_secret_previous IS NULL
                             OR webhook_signing_secret_previous LIKE 'v1:%'))""");
        }
        log.info("Webhook signing secrets: rewrote {} account(s); none is kept in clear now", changed);
    }

    /** What to keep for {@code stored}: a KeyVault value as it is, a secret in clear encrypted, blank as NULL. */
    String sealed(String stored, String context) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (KeyVault.isVaultValue(stored)) {
            return stored;
        }
        return vault.encrypt(stored.getBytes(StandardCharsets.UTF_8), context);
    }
}
