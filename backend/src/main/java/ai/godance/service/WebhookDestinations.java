package ai.godance.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import reactor.netty.http.client.HttpClient;

/**
 * Where a job's completion webhook may be sent (spec risk R-02).
 *
 * <p>The caller supplies {@code webhook_url}, so without a check the backend
 * would POST to whatever it names — its own loopback, the private network
 * the database and Redis sit on, or the cloud metadata address. The URL is
 * checked when the job is submitted, before any charge: https only, and
 * every address the host resolves to must be public. Delivery checks again,
 * and its client resolves names through {@link #publicOnlyResolver()}, so a
 * name that was public at submit time cannot be re-pointed at an internal
 * address before the callback connects. Redirects are not followed.
 *
 * <p>{@code godance.webhooks.allow-private-destinations=true} lifts the
 * address and https rules for tests that deliver to a local receiver. It is
 * never set in production configuration.
 */
@Component
public class WebhookDestinations {

    private final boolean allowPrivate;
    private final WebClient client;

    public WebhookDestinations(
            @Value("${godance.webhooks.allow-private-destinations:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
        HttpClient http = HttpClient.create().followRedirect(false);
        if (!allowPrivate) {
            http = http.resolver(publicOnlyResolver());
        }
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    /** The client deliveries go through: no redirects, and only public addresses. */
    public WebClient client() {
        return client;
    }

    /**
     * Refuses a webhook URL the backend must not call, with a 400. A null or
     * blank URL means "no webhook" and passes.
     */
    public void requireAllowed(String url) {
        if (url == null || url.isBlank()) return;
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw invalid("webhook_url is not a valid URL");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            throw invalid("webhook_url must be an absolute https URL");
        }
        if (allowPrivate) {
            if (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")) {
                throw invalid("webhook_url must use http or https");
            }
            return;
        }
        if (!scheme.equalsIgnoreCase("https")) {
            throw invalid("webhook_url must use https");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw invalid("webhook_url host does not resolve");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw invalid("webhook_url must point to a public address");
            }
        }
    }

    /**
     * True when {@code address} is an ordinary public unicast address:
     * not loopback, unspecified, link-local (which includes the cloud metadata
     * address 169.254.169.254), private, carrier-grade NAT, multicast,
     * reserved, or an IPv6 range that embeds an IPv4 address.
     */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xFF, second = b[1] & 0xFF;
            if ((first & 0xFE) == 0xFC) return false;                          // fc00::/7 unique local
            if (first == 0x20 && second == 0x02) return false;                 // 2002::/16 6to4
            if (first == 0x00 && second == 0x64 && (b[2] & 0xFF) == 0xFF
                    && (b[3] & 0xFF) == 0x9B) return false;                    // 64:ff9b::/96 NAT64
            return true;
        }
        int first = b[0] & 0xFF, second = b[1] & 0xFF;
        if (first == 0) return false;                                          // 0.0.0.0/8
        if (first == 100 && (second & 0xC0) == 64) return false;               // 100.64.0.0/10 CGNAT
        if (first == 192 && second == 0 && (b[2] & 0xFF) == 0) return false;  // 192.0.0.0/24
        if (first == 198 && (second == 18 || second == 19)) return false;      // 198.18.0.0/15
        return first < 224;                                                    // multicast, reserved, broadcast
    }

    /**
     * Resolves names the way the JDK does, but fails any name with a
     * non-public address. Checked at connect time, so it covers a name whose
     * DNS answer changed after the submit-time check.
     */
    static AddressResolverGroup<InetSocketAddress> publicOnlyResolver() {
        return new AddressResolverGroup<>() {
            @Override
            protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new InetSocketAddressResolver(executor, new InetNameResolver(executor) {
                    @Override
                    protected void doResolve(String host, Promise<InetAddress> promise) {
                        try {
                            promise.setSuccess(publicAddresses(host).get(0));
                        } catch (UnknownHostException e) {
                            promise.setFailure(e);
                        }
                    }

                    @Override
                    protected void doResolveAll(String host, Promise<List<InetAddress>> promise) {
                        try {
                            promise.setSuccess(publicAddresses(host));
                        } catch (UnknownHostException e) {
                            promise.setFailure(e);
                        }
                    }
                });
            }
        };
    }

    private static List<InetAddress> publicAddresses(String host) throws UnknownHostException {
        List<InetAddress> all = List.of(InetAddress.getAllByName(host));
        if (all.stream().anyMatch(a -> !isPublic(a))) {
            throw new UnknownHostException(host + " resolves to a non-public address");
        }
        return all;
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
