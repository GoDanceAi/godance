package ai.godance.web;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.exception.NotFoundException;

/**
 * The MinIO image for the Testcontainers tests. MinIO no longer publishes
 * public images, so it is built from source by {@code dev/minio/Dockerfile}
 * under the same local-only tag that docker-compose.yml and CI use.
 *
 * <p>{@code docker compose up} (or the CI workflow) normally builds it first
 * and this just reuses it. If it is missing, it is built here once (a few
 * minutes) and kept for later runs. The {@code localhost/} registry prefix
 * means Docker never tries to pull it from a remote registry.
 */
public final class MinioTestImage {

    private static final Logger log = LoggerFactory.getLogger(MinioTestImage.class);

    /** Keep in sync with dev/minio/Dockerfile and docker-compose.yml. */
    static final String TAG = "localhost/godance/minio:RELEASE.2024-12-13T22-19-12Z";

    private MinioTestImage() { }

    public static DockerImageName get() {
        if (!presentLocally()) {
            log.info("{} not found locally; building it from dev/minio/Dockerfile (first run only, takes a few minutes)", TAG);
            new ImageFromDockerfile(TAG, false)
                    .withDockerfile(Path.of("dev/minio/Dockerfile"))
                    .get();
        }
        return DockerImageName.parse(TAG).asCompatibleSubstituteFor("minio/minio");
    }

    private static boolean presentLocally() {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(TAG).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }
}
