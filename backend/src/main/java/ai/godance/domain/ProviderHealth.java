package ai.godance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "provider_health",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_provider_health_window",
                columnNames = {"provider", "model", "window_start", "window_end"}))
public class ProviderHealth {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private Provider provider;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    @Column(name = "error_count", nullable = false)
    private long errorCount;

    @Column(name = "rate_limit_count", nullable = false)
    private long rateLimitCount;

    @Column(name = "p50_ms")
    private Integer p50Ms;

    @Column(name = "p95_ms")
    private Integer p95Ms;

    @Column(name = "p99_ms")
    private Integer p99Ms;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }

    public long getRequestCount() { return requestCount; }
    public void setRequestCount(long requestCount) { this.requestCount = requestCount; }

    public long getErrorCount() { return errorCount; }
    public void setErrorCount(long errorCount) { this.errorCount = errorCount; }

    public long getRateLimitCount() { return rateLimitCount; }
    public void setRateLimitCount(long rateLimitCount) { this.rateLimitCount = rateLimitCount; }

    public Integer getP50Ms() { return p50Ms; }
    public void setP50Ms(Integer p50Ms) { this.p50Ms = p50Ms; }

    public Integer getP95Ms() { return p95Ms; }
    public void setP95Ms(Integer p95Ms) { this.p95Ms = p95Ms; }

    public Integer getP99Ms() { return p99Ms; }
    public void setP99Ms(Integer p99Ms) { this.p99Ms = p99Ms; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderHealth other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
