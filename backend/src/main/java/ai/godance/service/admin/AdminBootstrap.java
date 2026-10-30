package ai.godance.service.admin;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import ai.godance.config.AdminProperties;

/**
 * Gives the system its first admin at startup: while no active, verified
 * account holds ADMIN, the {@code GODANCE_ADMIN_EMAILS} accounts are
 * promoted. Once an admin exists the list is ignored, so a role revoked in
 * the console stays revoked across restarts. It also recovers a deployment
 * that has lost its last admin.
 *
 * <p>Only an account that already exists and has verified its address is
 * promoted. On a fresh install that means signing up, verifying, and
 * restarting once.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminProperties admin;
    private final AdminConsoleService console;

    public AdminBootstrap(AdminProperties admin, AdminConsoleService console) {
        this.admin = admin;
        this.console = console;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> promoted = console.bootstrapAdmins(admin.getEmails());
        if (!promoted.isEmpty()) {
            log.info("No admin found; promoted {} account(s) from GODANCE_ADMIN_EMAILS", promoted.size());
        }
    }
}
