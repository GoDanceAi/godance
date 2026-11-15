package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yaml.snakeyaml.Yaml;

import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;

/**
 * {@code GET /v1/openapi.yaml} against the running application: it is
 * served to anyone, and what it says about who may call each route is what
 * the security rules do. {@link OpenApiDriftTest} checks the routes and the
 * schemas; this checks the two auth methods.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class OpenApiDocumentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-openapi-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    /** What an API key is told on a route that needs a signed-in session. */
    private static final String KEY_REFUSED = "API keys can call the model API only; sign in to manage the account.";

    @Autowired WebTestClient http;

    @Test
    void document_givenNoCredentials_expectsTheFileAsYaml() throws IOException {
        byte[] onClasspath;
        try (InputStream in = getClass().getResourceAsStream("/static/openapi.yaml")) {
            onClasspath = in.readAllBytes();
        }

        EntityExchangeResult<byte[]> served = http.get().uri("/v1/openapi.yaml")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("application/yaml")
                .expectBody().returnResult();

        assertThat(served.getResponseBody()).isEqualTo(onClasspath);
        assertThat(new String(served.getResponseBody(), StandardCharsets.UTF_8)).startsWith("openapi: 3.1.0");
        assertThat(served.getResponseHeaders().getCacheControl()).contains("public");
    }

    @Test
    void security_givenEachOperation_expectsWhatTheDocumentSaysAboutAnApiKeyAndNoCredentials() {
        String key = apiKey();
        List<String> wrong = new ArrayList<>();
        int checked = 0;

        for (Operation operation : operations()) {
            checked++;
            // No credentials: a public route answers something other than 401; every other route answers 401.
            int anonymous = send(operation, null).getStatus().value();
            if (operation.isPublic() && (anonymous == 401 || anonymous == 403)) {
                wrong.add(operation + " is documented as public but answered " + anonymous + " without credentials");
            }
            if (!operation.isPublic() && anonymous != 401) {
                wrong.add(operation + " needs credentials but answered " + anonymous + " without any");
            }
            if (operation.isPublic()) continue;

            // An API key: admitted where the document lists apiKey, and told to sign in where it does not.
            EntityExchangeResult<byte[]> withKey = send(operation, key);
            String body = withKey.getResponseBody() == null ? "" : new String(withKey.getResponseBody(), StandardCharsets.UTF_8);
            boolean refusedAsKey = withKey.getStatus().value() == 403 && body.contains(KEY_REFUSED);
            if (operation.takesApiKey() && (refusedAsKey || withKey.getStatus().value() == 401)) {
                wrong.add(operation + " is documented for API keys but refused one: " + withKey.getStatus());
            }
            if (!operation.takesApiKey() && !refusedAsKey) {
                wrong.add(operation + " is documented as session only but an API key got " + withKey.getStatus());
            }
        }

        assertThat(checked).as("operations read from the document").isGreaterThan(80);
        assertThat(wrong).as("where the document and the security rules disagree").isEmpty();
    }

    // ===== Helpers ====================================================

    private record Operation(HttpMethod method, String path, List<String> schemes) {
        boolean isPublic() {
            return schemes.isEmpty();
        }

        boolean takesApiKey() {
            return schemes.contains("apiKey");
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Operation> operations() {
        Map<String, Object> document;
        try (InputStream in = getClass().getResourceAsStream("/static/openapi.yaml")) {
            document = new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<Operation> operations = new ArrayList<>();
        ((Map<String, Object>) document.get("paths")).forEach((path, item) ->
                ((Map<String, Object>) item).forEach((method, operation) -> {
                    List<String> schemes = new ArrayList<>();
                    for (Object requirement : (List<Object>) ((Map<String, Object>) operation).get("security")) {
                        schemes.addAll(((Map<String, Object>) requirement).keySet());
                    }
                    operations.add(new Operation(HttpMethod.valueOf(method.toUpperCase()), path, schemes));
                }));
        return operations;
    }

    /** The request with an id in place of each path variable, and an empty JSON object where a body goes. */
    private EntityExchangeResult<byte[]> send(Operation operation, String bearer) {
        String uri = operation.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        WebTestClient.RequestBodySpec request = http.method(operation.method()).uri(uri);
        if (bearer != null) request.header(HttpHeaders.AUTHORIZATION, bearer);
        boolean hasBody = operation.method() == HttpMethod.POST || operation.method() == HttpMethod.PUT
                || operation.method() == HttpMethod.PATCH;
        WebTestClient.RequestHeadersSpec<?> ready = hasBody
                ? request.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                : request;
        return ready.exchange().expectBody().returnResult();
    }

    private String apiKey() {
        String bearer = InputFixtures.signup(http, "openapi-" + UUID.randomUUID() + "@godance.test");
        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "openapi-check"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
        return "Bearer " + key.plaintext();
    }
}
