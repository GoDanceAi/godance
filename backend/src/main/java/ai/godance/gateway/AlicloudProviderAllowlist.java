package ai.godance.gateway;

import java.util.EnumSet;
import java.util.Set;

import ai.godance.domain.Provider;

/**
 * Hardcoded allowlist of providers that may be dispatched from the
 * Alicloud region. Per CLAUDE.md hard constraint #1 and the outcome
 * brief §"Provider gateway region awareness — HARD POLICY".
 *
 * <p>Western frontier providers (Anthropic, OpenAI, Google Vertex,
 * BFL Flux, ElevenLabs, Cartesia, Runway) MUST NEVER be routed via
 * Alicloud — not in failover, not for testing, not ever. Their absence
 * from this set is the policy. Fail closed on any provider not present.
 *
 * <p>This constant exists in Session 2 even though the gateway hasn't
 * been built yet — by establishing the policy in code first, every
 * future provider integration is forced to consult this allowlist.
 *
 * <p>Sourced from outcome-brief.md lines 438–440.
 */
public final class AlicloudProviderAllowlist {

    /** Providers the Alicloud region is permitted to dispatch to. */
    public static final Set<Provider> ALLOWED = EnumSet.of(
            Provider.DEEPSEEK,
            Provider.MOONSHOT,
            Provider.ALIBABA_DASHSCOPE,
            Provider.ZHIPU,
            Provider.MINIMAX,
            Provider.VOLCENGINE_ARK
    );

    private AlicloudProviderAllowlist() { }

    /** Fail-closed check: only providers in {@link #ALLOWED} return true. */
    public static boolean isAllowed(Provider provider) {
        return provider != null && ALLOWED.contains(provider);
    }
}
