package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;

/** Spec risk R-02: a caller-supplied webhook URL may only reach a public https endpoint. */
class WebhookDestinationsTest {

    private final WebhookDestinations strict = new WebhookDestinations(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://93.184.215.14/hook",
            "ftp://93.184.215.14/hook",
            "https://127.0.0.1/hook",
            "https://localhost/hook",
            "https://0.0.0.0/hook",
            "https://10.1.2.3/hook",
            "https://172.16.0.1/hook",
            "https://192.168.1.10/hook",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/hook",
            "https://224.0.0.1/hook",
            "https://[::1]/hook",
            "https://[fd12:3456::1]/hook",
            "https://[fe80::1]/hook",
            "https://[::ffff:127.0.0.1]/hook",
            "/relative/hook",
            "not a url"})
    void requireAllowed_givenNonPublicOrNonHttpsUrl_throws400(String url) {
        assertThatThrownBy(() -> strict.requireAllowed(url))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void requireAllowed_givenHttpsToPublicAddress_passes() {
        assertThatCode(() -> strict.requireAllowed("https://93.184.215.14/hooks/godance"))
                .doesNotThrowAnyException();
        assertThatCode(() -> strict.requireAllowed("https://[2606:2800:21f:cb07:6820:80da:af6b:8b2c]/hook"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAllowed_givenNoUrl_passes() {
        assertThatCode(() -> strict.requireAllowed(null)).doesNotThrowAnyException();
        assertThatCode(() -> strict.requireAllowed("  ")).doesNotThrowAnyException();
    }

    @Test
    void requireAllowed_whenPrivateDestinationsAllowed_acceptsLocalHttpReceiver() {
        assertThatCode(() -> new WebhookDestinations(true).requireAllowed("http://localhost:8089/cb"))
                .doesNotThrowAnyException();
    }

    @Test
    void publicOnlyResolver_givenNameOfLoopback_failsAtConnectTime() {
        AddressResolver<InetSocketAddress> resolver =
                WebhookDestinations.publicOnlyResolver().getResolver(GlobalEventExecutor.INSTANCE);

        Future<InetSocketAddress> resolved =
                resolver.resolve(InetSocketAddress.createUnresolved("localhost", 443));

        assertThat(resolved.awaitUninterruptibly().isSuccess()).isFalse();
        assertThat(resolved.cause()).isInstanceOf(UnknownHostException.class);
    }
}
