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
    private final String gitShaEnv;

    public HealthController(
            ObjectProvider<BuildProperties> buildProperties,
            ObjectProvider<GitProperties> gitProperties,
            @Value("${godance.region:local}") String region,
            // Baked into the container image from the CI build arg (see
            // deploy/Dockerfile). Authoritative in prod because the Docker
            // build has no .git for the maven git-commit-id plugin to read.
            @Value("${GODANCE_GIT_SHA:}") String gitShaEnv) {
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
        this.region = region;
        this.gitShaEnv = gitShaEnv;
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
        // 1) Prefer the env baked in at image build (prod path — deterministic
        //    in a .git-less Docker build).
        if (gitShaEnv != null && !gitShaEnv.isBlank() && !"unknown".equals(gitShaEnv)) {
            return shorten(gitShaEnv.strip());
        }
        // 2) Fall back to GitProperties (local dev / `mvn` runs with a real .git).
        GitProperties gp = gitProperties.getIfAvailable();
        if (gp != null) {
            String sha = gp.getShortCommitId();
            if (sha != null && !sha.isBlank()) return sha;
        }
        return "unknown";
    }

    /** Display a full 40-char commit id as its 7-char short form. */
    private static String shorten(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
