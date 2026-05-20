package ai.godance.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.ApiKey;
import ai.godance.domain.AuditOutcome;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.security.ApiKeyMinter;
import ai.godance.security.ApiKeyMinter.Minted;
import ai.godance.web.dto.ApiKeyDtos.ApiKeySummary;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyRequest;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;

@Service
public class ApiKeyService {

    private final ApiKeyRepository repo;
    private final ApiKeyMinter minter;
    private final AuditLogService audit;

    public ApiKeyService(ApiKeyRepository repo, ApiKeyMinter minter, AuditLogService audit) {
        this.repo = repo;
        this.minter = minter;
        this.audit = audit;
    }

    @Transactional
    public CreateApiKeyResponse create(UUID userId, CreateApiKeyRequest req, String ip) {
        Minted minted = minter.mint();
        ApiKey k = new ApiKey();
        k.setUserId(userId);
        k.setName(req.name().trim());
        k.setKeyHash(minted.hash());
        k.setKeyPrefix(minted.prefix());
        k.setScopes(req.scopes());
        k.setActive(true);
        repo.save(k);

        audit.record(AuditLogService.EVT_API_KEY_CREATED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("apiKeyId", k.getId().toString(), "name", k.getName()));

        return new CreateApiKeyResponse(
                k.getId(), k.getName(), k.getKeyPrefix(), minted.plaintext(), k.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ApiKeySummary> listForUser(UUID userId) {
        return repo.findByUserId(userId).stream().map(ApiKeySummary::from).toList();
    }

    @Transactional
    public void delete(UUID userId, UUID keyId, String ip) {
        ApiKey k = repo.findById(keyId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "api key not found"));
        if (!k.getUserId().equals(userId)) {
            // Don't leak existence — same 404 as not-found.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "api key not found");
        }
        repo.delete(k);
        audit.record(AuditLogService.EVT_API_KEY_DELETED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("apiKeyId", keyId.toString(), "name", k.getName()));
    }
}
