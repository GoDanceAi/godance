package ai.godance.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * /healthz — Cloudflare DNS failover polls this endpoint.
 * Returns 200 OK with build info (version, git SHA, region).
 */
@RestController
public class HealthController {

    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<GitProperties> gitProperties;
    private final String region;

    public HealthController(
            ObjectProvider<BuildProperties> buildProperties,
            ObjectProvider<GitProperties> gitProperties,
            @Value("${godance.region:local}") String region) {
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
        this.region = region;
    }

    @GetMapping("/healthz")
    public Mono<Map<String, Object>> healthz() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("region", region);
        body.put("version", versionOrUnknown());
        body.put("git_sha", gitShaOrUnknown());
        body.put("timestamp", Instant.now().toString());
        return Mono.just(body);
    }

    private String versionOrUnknown() {
        BuildProperties bp = buildProperties.getIfAvailable();
        return bp != null ? bp.getVersion() : "unknown";
    }

    private String gitShaOrUnknown() {
        GitProperties gp = gitProperties.getIfAvailable();
        if (gp == null) return "unknown";
        String sha = gp.getShortCommitId();
        return (sha == null || sha.isBlank()) ? "unknown" : sha;
    }
}
