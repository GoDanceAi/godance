package ai.godance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ai.godance.config.ObjectStorageProperties;
import ai.godance.web.MinioTestImage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * {@link S3ObjectStorageClient#deletePrefix} against a real MinIO: it deletes
 * exactly the keys under the prefix, across more than one ListObjectsV2 page,
 * and refuses a prefix that would match the whole bucket.
 */
@Testcontainers
class S3ObjectStorageClientIntegrationTest {

    private static final String BUCKET = "godance-prefix-test";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static S3ObjectStorageClient client;
    static S3Client inspector;

    @BeforeAll
    static void start() {
        client = new S3ObjectStorageClient(new ObjectStorageProperties(
                MINIO.getS3URL(), "us-east-1", MINIO.getUserName(), MINIO.getPassword(),
                BUCKET, Duration.ofHours(1), true));
        client.start();
        inspector = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @AfterAll
    static void stop() {
        if (inspector != null) inspector.close();
        if (client != null) client.stop();
    }

    private static List<String> keysUnder(String prefix) {
        return inspector.listObjectsV2Paginator(b -> b.bucket(BUCKET).prefix(prefix)).contents().stream()
                .map(S3Object::key)
                .toList();
    }

    private static void put(String key) {
        client.upload(key, new byte[] {1, 2, 3}, "application/octet-stream");
    }

    @Test
    void deletePrefix_givenObjectsUnderPrefix_deletesOnlyThoseAndCountsThem() {
        String job = UUID.randomUUID().toString();
        put("images/" + job + "/0.png");
        put("images/" + job + "/1.png");
        put("images/" + job + "x/0.png");      // shares the characters, not the prefix
        put("videos/" + job + "/0.mp4");

        int deleted = client.deletePrefix("images/" + job + "/");

        assertThat(deleted).isEqualTo(2);
        assertThat(keysUnder("images/" + job + "/")).isEmpty();
        assertThat(keysUnder("images/" + job + "x/")).containsExactly("images/" + job + "x/0.png");
        assertThat(keysUnder("videos/" + job + "/")).containsExactly("videos/" + job + "/0.mp4");
    }

    @Test
    void deletePrefix_givenMoreKeysThanOneListingPage_deletesThemAll() {
        String prefix = "bulk/" + UUID.randomUUID() + "/";
        IntStream.range(0, 1_005).parallel().forEach(i -> put(prefix + i + ".bin"));
        assertThat(keysUnder(prefix)).hasSize(1_005);

        int deleted = client.deletePrefix(prefix);

        assertThat(deleted).isEqualTo(1_005);
        assertThat(keysUnder(prefix)).isEmpty();
    }

    @Test
    void deletePrefix_givenNothingUnderPrefix_returnsZero() {
        assertThat(client.deletePrefix("images/" + UUID.randomUUID() + "/")).isZero();
    }

    @Test
    void deletePrefix_givenBlankPrefix_refusesWithoutDeleting() {
        String keep = "keep/" + UUID.randomUUID() + "/0.bin";
        put(keep);

        assertThatThrownBy(() -> client.deletePrefix("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deletePrefix("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deletePrefix(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(keysUnder(keep)).containsExactly(keep);
    }
}
