package ai.godance.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/** What the community controllers (round 14) read off a request. */
final class Requests {

    private Requests() { }

    /** The caller's address, as the other controllers record it. */
    static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy (forward-headers-strategy=framework) the
        // remote address can be UNRESOLVED — getAddress() returns null.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }

    /**
     * Scheme, host and port the request came to: where this API answers,
     * as the caller sees it. Behind the proxy, the forwarded scheme and host.
     */
    static String apiBase(ServerWebExchange exchange) {
        URI uri = exchange.getRequest().getURI();
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    /** {@code limit} from the query, or null when absent. 400 for anything that is not 1 to {@code max}. */
    static Integer limit(String raw, int max) {
        if (raw == null) return null;
        try {
            int limit = Integer.parseInt(raw.trim());
            if (limit >= 1 && limit <= max) return limit;
        } catch (NumberFormatException ignored) {
            // falls through to the 400
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be an integer between 1 and " + max);
    }
}
