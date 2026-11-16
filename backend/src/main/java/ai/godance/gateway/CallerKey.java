package ai.godance.gateway;

import java.util.Objects;

import ai.godance.domain.Provider;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * The credential seam of bring-your-own-key (round 14 brief 03): the key one
 * provider call goes out with. The service that makes the call decides,
 * after {@link ProviderGateway#dispatch} has passed the region check,
 * whether the caller's own key is used; if so it puts it in the call's
 * Reactor context with {@link #in}. A migrated adapter asks {@link #or} for
 * the key to send and gets the caller's, or else the platform's. An adapter
 * that never asks always sends the platform's.
 *
 * <p>The key travels only in memory, for the one call, inside a holder
 * whose {@link #toString} shows the last four characters and nothing else,
 * so a context that gets printed never prints a key.
 */
public final class CallerKey {

    private static final Object CONTEXT_KEY = CallerKey.class;

    private final Provider provider;
    private final String value;

    private CallerKey(Provider provider, String value) {
        this.provider = Objects.requireNonNull(provider);
        this.value = Objects.requireNonNull(value);
    }

    /** The caller's key for {@code provider}, for one call. */
    public static CallerKey of(Provider provider, String value) {
        return new CallerKey(provider, value);
    }

    /** A context carrying this key, for {@code contextWrite}. */
    public static Context in(Context context, CallerKey key) {
        return key == null ? context : context.put(CONTEXT_KEY, key);
    }

    /**
     * The key a call to {@code provider} goes out with: the caller's, when
     * the call's context carries one for this very provider, else
     * {@code platformKey}.
     */
    public static String or(ContextView context, Provider provider, String platformKey) {
        return context.<CallerKey>getOrEmpty(CONTEXT_KEY)
                .filter(key -> key.provider == provider)
                .map(key -> key.value)
                .orElse(platformKey);
    }

    public Provider provider() {
        return provider;
    }

    /** The key's last four characters. */
    public String last4() {
        return last4(value);
    }

    @Override
    public String toString() {
        return "CallerKey[" + provider + ", …" + last4(value) + "]";
    }

    /** The last four characters of a key: all of it that may be shown, logged or stored in clear. */
    public static String last4(String key) {
        if (key == null || key.isEmpty()) return "";
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }
}
