package ai.godance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.godance.domain.CreditSource;

class ReferralPropertiesTest {

    private static ReferralProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("godance.promo.referral", ReferralProperties.class);
    }

    private static ReferralProperties with(long referrerCents, long refereeCents, Set<CreditSource> sources) {
        return new ReferralProperties(referrerCents, refereeCents, 1000, 20, 168, sources, 5, 20);
    }

    @Test
    void bind_givenNothingSet_expectsTheBriefsDefaultsAndOff() {
        ReferralProperties p = bind(Map.of());

        assertThat(p.referrerRewardCents()).isZero();
        assertThat(p.refereeRewardCents()).isZero();
        assertThat(p.minTopupCents()).isEqualTo(1000);
        assertThat(p.monthlyRewardLimit()).isEqualTo(20);
        assertThat(p.claimWindow()).isEqualTo(Duration.ofHours(168));
        assertThat(p.qualifyingSources()).containsExactly(CreditSource.STRIPE);
        assertThat(p.enabled()).isFalse();
    }

    @Test
    void bind_givenBothRailsInAnyCase_expectsBothSources() {
        ReferralProperties p = bind(Map.of("godance.promo.referral.qualifying-sources", "stripe, NOWPAYMENTS"));

        assertThat(p.qualifyingSources()).containsExactlyInAnyOrder(CreditSource.STRIPE, CreditSource.NOWPAYMENTS);
    }

    @Test
    void enabled_givenEitherRewardAboveZero_expectsOn() {
        assertThat(with(100, 0, Set.of(CreditSource.STRIPE)).enabled()).isTrue();
        assertThat(with(0, 100, Set.of(CreditSource.STRIPE)).enabled()).isTrue();
        assertThat(with(0, 0, Set.of(CreditSource.STRIPE)).enabled()).isFalse();
    }

    @Test
    void constructor_givenNegativeReward_expectsRejected() {
        assertThatThrownBy(() -> with(-1, 0, Set.of(CreditSource.STRIPE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("referrer-reward-cents");
        assertThatThrownBy(() -> with(0, -1, Set.of(CreditSource.STRIPE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("referee-reward-cents");
    }

    @Test
    void constructor_givenSourceThatIsNotAPaymentRail_expectsRejected() {
        // A reward paid on a promotional or referral credit could pay for itself.
        assertThatThrownBy(() -> with(100, 100, Set.of(CreditSource.PROMO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qualifying-sources");
        assertThatThrownBy(() -> with(100, 100, Set.of(CreditSource.STRIPE, CreditSource.REFERRAL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qualifying-sources");
    }

    @Test
    void constructor_givenNoSources_expectsRejected() {
        assertThatThrownBy(() -> with(100, 100, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qualifying-sources");
    }

    @Test
    void constructor_givenClaimLimitBelowOne_expectsRejected() {
        assertThatThrownBy(() -> new ReferralProperties(100, 100, 1000, 20, 168, Set.of(CreditSource.STRIPE), 0, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim-limit-per-account");
    }
}
