package ai.godance.security;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import ai.godance.config.OAuthProperties;

/**
 * "Sign in with Apple" does not use a static client secret — the OAuth
 * {@code client_secret} is a short-lived ES256 JWT the relying party
 * signs with its private key (the {@code .p8} downloaded from the Apple
 * developer console).
 *
 * <p>This generator builds that JWT once at startup (Apple permits a
 * lifetime of up to ~6 months; we use 150 days). If the signing key is
 * not configured — the dev/test default — it returns a harmless
 * placeholder so the application context still boots; Apple login is
 * simply non-functional until an operator supplies the real key.
 */
@Component
public class AppleClientSecretGenerator {

    private static final Logger log = LoggerFactory.getLogger(AppleClientSecretGenerator.class);
    private static final String APPLE_AUDIENCE = "https://appleid.apple.com";
    private static final long SECRET_TTL_DAYS = 150;
    static final String PLACEHOLDER_SECRET = "apple-client-secret-not-configured";

    private final OAuthProperties props;

    public AppleClientSecretGenerator(OAuthProperties props) {
        this.props = props;
    }

    /**
     * The current Apple {@code client_secret}. Returns {@link #PLACEHOLDER_SECRET}
     * when no signing key is configured or generation fails — never throws,
     * so a missing key cannot break context startup.
     */
    public String currentSecret() {
        OAuthProperties.AppleProvider apple = props.apple();
        if (apple == null || apple.privateKey() == null || apple.privateKey().isBlank()) {
            log.info("Apple OAuth signing key not configured — Apple sign-in disabled until set");
            return PLACEHOLDER_SECRET;
        }
        try {
            return generate(apple);
        } catch (Exception e) {
            log.warn("Apple client-secret generation failed ({}); Apple sign-in disabled", e.toString());
            return PLACEHOLDER_SECRET;
        }
    }

    private static String generate(OAuthProperties.AppleProvider apple) throws Exception {
        ECPrivateKey key = parsePkcs8(apple.privateKey());
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(apple.teamId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(SECRET_TTL_DAYS, ChronoUnit.DAYS)))
                .audience(APPLE_AUDIENCE)
                .subject(apple.clientId())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(apple.keyId()).build(),
                claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static ECPrivateKey parsePkcs8(String pem) throws Exception {
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
