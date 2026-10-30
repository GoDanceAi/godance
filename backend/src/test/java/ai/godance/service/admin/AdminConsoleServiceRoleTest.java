package ai.godance.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.CreditLedgerService;

/** The lost race, which {@code AdminRolesIntegrationTest} can't produce on demand. */
class AdminConsoleServiceRoleTest {

    @Test
    void changeRole_givenAnotherChangeGotThereFirst_returns409AndWritesNoAuditRow() {
        UserRepository users = mock(UserRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        AdminConsoleService console = new AdminConsoleService(users, mock(ApiKeyRepository.class),
                mock(CreditLedgerRepository.class), mock(UsageRecordRepository.class),
                mock(CreditLedgerService.class), audit);
        User target = new User();
        target.setId(UUID.randomUUID());
        target.setRole(UserRole.USER);
        target.setActive(true);
        target.setEmailVerified(true);
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(users.changeRole(eq(target.getId()), eq(UserRole.USER), eq(UserRole.ADMIN), any())).thenReturn(0);

        assertThatThrownBy(() -> console.changeRole(target.getId(), UserRole.ADMIN,
                        UUID.randomUUID(), "admin@godance.test", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(audit);
    }
}
