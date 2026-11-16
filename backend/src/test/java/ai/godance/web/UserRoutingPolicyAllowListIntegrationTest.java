package ai.godance.web;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * {@code godance.routing.user-policies.allow-list}: with user policies on and
 * a list set, only the accounts on it may write and call policies; any other
 * gets 403 {@code routing_policies_not_allowed}. Addresses match whatever
 * their case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class UserRoutingPolicyAllowListIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.routing.user-policies.enabled", () -> "true");
        r.add("godance.routing.user-policies.allow-list", () -> "listed@godance.test, Second@Godance.test");
    }

    @Autowired WebTestClient http;

    @Test
    void routes_givenAnAccountOnTheList_expectsThemOpen() {
        for (String email : new String[] {"listed@godance.test", "second@godance.test"}) {
            String bearer = signUp(email);
            http.put().uri("/v1/routing/policies/mine").header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("default_model", "gpt-4o"))
                    .exchange()
                    .expectStatus().isCreated();
        }
    }

    @Test
    void routes_givenAnAccountNotOnTheList_expect403NotAllowed() {
        String bearer = signUp("someone-else@godance.test");

        http.get().uri("/v1/routing/policies").header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectStatus().isForbidden().expectBody()
                .jsonPath("$.code").isEqualTo("routing_policies_not_allowed");
        http.put().uri("/v1/routing/policies/mine").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("default_model", "gpt-4o"))
                .exchange()
                .expectStatus().isForbidden();
    }

    private String signUp(String email) {
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Allow List"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }
}
