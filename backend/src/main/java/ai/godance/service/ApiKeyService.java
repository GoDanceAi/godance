package ai.godance.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.ImagesProperties;
import ai.godance.domain.ApiKey;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.ImageResponseMode;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.security.ApiKeyMinter;
import ai.godance.security.ApiKeyMinter.Minted;
import ai.godance.web.dto.ApiKeyDtos.ApiKeySummary;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyRequest;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.ApiKeyDtos.UpdateApiKeyRequest;

@Service
@EnableConfigurationProperties(ImagesProperties.class)
public class ApiKeyService {

    private final ApiKeyRepository repo;
    private final ApiKeyMinter minter;
    private final AuditLogService audit;
    private final ImagesProperties images;

    public ApiKeyService(ApiKeyRepository repo, ApiKeyMinter minter, AuditLogService audit,
                         ImagesProperties images) {
        this.repo = repo;
        this.minter = minter;
        this.audit = audit;
        this.images = images;
    }

    @Transactional
    public CreateApiKeyResponse create(UUID userId, CreateApiKeyRequest req, String ip) {
        // What the request asks for, or the deployment's default for new keys.
        ImageResponseMode imageResponse = req.imageResponse() == null
                ? images.newKeyResponse()
                : requireImageResponse(req.imageResponse());
        Minted minted = minter.mint();
        ApiKey k = new ApiKey();
        k.setUserId(userId);
        k.setName(req.name().trim());
        k.setKeyHash(minted.hash());
        k.setKeyPrefix(minted.prefix());
        k.setScopes(req.scopes());
        k.setImageResponse(imageResponse);
        k.setActive(true);
        repo.save(k);

        audit.record(AuditLogService.EVT_API_KEY_CREATED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("apiKeyId", k.getId().toString(), "name", k.getName()));

        return new CreateApiKeyResponse(
                k.getId(), k.getName(), k.getKeyPrefix(), minted.plaintext(), k.getCreatedAt(),
                imageResponse.wire());
    }

    /**
     * Changes how image requests made with the caller's key answer, and
     * returns the key. 404 for an unknown key or another user's; 400 when
     * {@code image_response} is missing or names neither mode.
     */
    @Transactional
    public ApiKeySummary update(UUID userId, UUID keyId, UpdateApiKeyRequest req, String ip) {
        ImageResponseMode imageResponse = requireImageResponse(req == null ? null : req.imageResponse());
        ApiKey k = repo.findById(keyId)
                // Don't leak existence — same 404 as not-found.
                .filter(key -> key.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "api key not found"));
        if (k.getImageResponse() != imageResponse) {
            k.setImageResponse(imageResponse);
            repo.save(k);
            audit.record(AuditLogService.EVT_API_KEY_UPDATED, AuditOutcome.SUCCESS, userId, ip,
                    Map.of("apiKeyId", keyId.toString(), "name", k.getName(),
                            "field", "image_response", "value", imageResponse.wire()));
        }
        return ApiKeySummary.from(k);
    }

    /**
     * How image requests made with this key answer. A key that no longer
     * exists answers as every key did before the setting: with a job.
     */
    @Transactional(readOnly = true)
    public ImageResponseMode imageResponseOf(UUID keyId) {
        return repo.findById(keyId).map(ApiKey::getImageResponse).orElse(ImageResponseMode.ASYNC);
    }

    private static ImageResponseMode requireImageResponse(String value) {
        ImageResponseMode mode = ImageResponseMode.fromWire(value);
        if (mode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "image_response must be \"async\" or \"sync\"");
        }
        return mode;
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
