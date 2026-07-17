package ai.godance.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import ai.godance.domain.Region;

/**
 * Region-gate check on the cron wrapper. Mockito on JDK 25 can't
 * subclass {@link PricingRefreshService} reliably, so this test uses a
 * hand-written counting subclass instead — also lighter and faster
 * than firing a mocking framework for a one-method assertion.
 */
class PricingRefreshCronJobTest {

    @Test
    void execute_whenRegionNotDigitalocean_doesNotInvokeRefreshService() throws Exception {
        CountingRefreshService refresh = new CountingRefreshService();
        PricingRefreshCronJob job = jobWithDeps(refresh, Region.LOCAL.name().toLowerCase());

        job.execute(null);

        assertThat(refresh.runs).isZero();
    }

    @Test
    void execute_whenRegionAlicloud_doesNotInvokeRefreshService() throws Exception {
        CountingRefreshService refresh = new CountingRefreshService();
        PricingRefreshCronJob job = jobWithDeps(refresh, Region.ALICLOUD.name());

        job.execute(null);

        assertThat(refresh.runs).isZero();
    }

    @Test
    void execute_whenRegionDigitalocean_invokesRefreshServiceExactlyOnce() throws Exception {
        CountingRefreshService refresh = new CountingRefreshService();
        PricingRefreshCronJob job = jobWithDeps(refresh, Region.DIGITALOCEAN.name());

        job.execute(null);

        assertThat(refresh.runs).isEqualTo(1);
    }

    private static PricingRefreshCronJob jobWithDeps(PricingRefreshService refresh, String region) throws Exception {
        PricingRefreshCronJob job = new PricingRefreshCronJob();
        var refField = PricingRefreshCronJob.class.getDeclaredField("refresh");
        refField.setAccessible(true);
        refField.set(job, refresh);
        var regionField = PricingRefreshCronJob.class.getDeclaredField("regionName");
        regionField.setAccessible(true);
        regionField.set(job, region);
        return job;
    }

    /**
     * Pure counting stub — super constructor takes nulls (the cron-wrapper
     * test never invokes inherited behaviour, so the field references are
     * unread).
     */
    static class CountingRefreshService extends PricingRefreshService {
        int runs = 0;
        CountingRefreshService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }
        @Override
        public Digest runOnce() {
            runs++;
            return new Digest();
        }
    }
}
