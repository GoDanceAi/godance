package ai.godance.security;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import ai.godance.repository.UserRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Decodes a Godance JWT with the signature/expiry-checking
 * {@code delegate}, then rejects it unless its
 * {@link JwtService#SESSION_VERSION_CLAIM} still matches the user's
 * {@code users.session_version}.
 *
 * <p>JWTs are stateless HS256 tokens, so on their own nothing can end one
 * before it expires. A password reset bumps the user's session version;
 * from then on every JWT issued before the reset fails here, and the
 * resource server answers 401 {@code invalid_token}. Without this, a
 * JWT held by whoever had the account before the reset could mint a new
 * API key or read the new webhook signing secret straight away.
 *
 * <p>Costs one primary-key lookup per JWT-authenticated request. API-key
 * requests never reach a JWT decoder ({@link ApiKeyAuthFilter}).
 */
public class SessionVersionJwtDecoder implements ReactiveJwtDecoder {

    private final ReactiveJwtDecoder delegate;
    private final UserRepository users;

    public SessionVersionJwtDecoder(ReactiveJwtDecoder delegate, UserRepository users) {
        this.delegate = delegate;
        this.users = users;
    }

    @Override
    public Mono<Jwt> decode(String token) {
        return delegate.decode(token)
                .flatMap(jwt -> Mono.fromCallable(() -> requireCurrentSession(jwt))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private Jwt requireCurrentSession(Jwt jwt) {
        UUID userId;
        try {
            userId = JwtService.userIdFromSubject(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new BadJwtException("token subject is not a user id");
        }
        int current = users.findSessionVersionById(userId)
                .orElseThrow(() -> new BadJwtException("token user no longer exists"));
        if (JwtService.sessionVersionOf(jwt) != current) {
            throw new BadJwtException("session has been revoked; sign in again");
        }
        return jwt;
    }
}
