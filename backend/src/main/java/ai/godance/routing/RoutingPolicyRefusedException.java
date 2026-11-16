package ai.godance.routing;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused routing policy request: problem+json whose {@code code} member
 * says which kind, so a client can branch without reading {@code detail}.
 */
public final class RoutingPolicyRefusedException extends ResponseStatusException {

    /** User routing policies are off on this server. 404, as if the routes did not exist. */
    public static final String CODE_DISABLED = "routing_policies_disabled";
    /** They are on, for a list of accounts this one is not on. 403. */
    public static final String CODE_NOT_ALLOWED = "routing_policies_not_allowed";
    /** The policy breaks a rule; {@code errors} lists every problem. 400. */
    public static final String CODE_INVALID = "routing_policy_invalid";
    /** No such policy of the caller's (a preset cannot be changed or deleted either). 404. */
    public static final String CODE_NOT_FOUND = "routing_policy_not_found";
    /** The account has as many policies as it may. 409. */
    public static final String CODE_LIMIT_REACHED = "routing_policy_limit_reached";
    /** A batch asked for fallbacks it cannot have. 400. */
    public static final String CODE_FALLBACKS_UNAVAILABLE = "fallbacks_unavailable";

    private RoutingPolicyRefusedException(HttpStatus status, String detail, String code) {
        super(status, detail);
        getBody().setProperty("code", code);
    }

    public static RoutingPolicyRefusedException disabled() {
        return new RoutingPolicyRefusedException(HttpStatus.NOT_FOUND,
                "routing policies are not turned on on this server", CODE_DISABLED);
    }

    public static RoutingPolicyRefusedException notAllowed() {
        return new RoutingPolicyRefusedException(HttpStatus.FORBIDDEN,
                "routing policies are not available to this account", CODE_NOT_ALLOWED);
    }

    public static RoutingPolicyRefusedException invalid(List<String> problems) {
        RoutingPolicyRefusedException e = new RoutingPolicyRefusedException(HttpStatus.BAD_REQUEST,
                "the routing policy is not valid: " + String.join("; ", problems), CODE_INVALID);
        e.getBody().setProperty("errors", List.copyOf(problems));
        return e;
    }

    public static RoutingPolicyRefusedException notFound(String name) {
        return new RoutingPolicyRefusedException(HttpStatus.NOT_FOUND,
                "you have no routing policy '" + name + "'", CODE_NOT_FOUND);
    }

    public static RoutingPolicyRefusedException limitReached(int max) {
        return new RoutingPolicyRefusedException(HttpStatus.CONFLICT,
                "an account may have " + max + " routing policies; delete one to add another", CODE_LIMIT_REACHED);
    }

    public static RoutingPolicyRefusedException fallbacksUnavailable(String detail) {
        return new RoutingPolicyRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_FALLBACKS_UNAVAILABLE);
    }
}
