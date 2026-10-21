# MinIO RELEASE.2024-12-13T22-19-12Z, built from source.
#
# Used by docker-compose.yml (local dev), the CI workflows, and the
# Testcontainers tests (ImageGenerationIntegrationTest,
# VideoGenerationIntegrationTest), all under one local-only tag:
#
#   localhost/godance/minio:RELEASE.2024-12-13T22-19-12Z
#
# Why from source: MinIO withdrew its public images from Docker Hub and Quay
# in 2025 (docker.io/minio/minio now answers "pull access denied"), and our
# GHCR mirror cannot be public. The Go module proxy keeps every published
# module version permanently, and sum.golang.org pins its hash, so this
# build only needs the public golang + alpine images and proxy.golang.org.
#
# The binary matches upstream's release build: same Go line (1.23), same
# CGO_ENABLED=0 / -tags kqueue / -trimpath, and the same version ldflags,
# so `minio --version` reports the release instead of DEVELOPMENT.GOGET.
#
# Upgrading: resolve the new tag with
#   go list -m github.com/minio/minio@RELEASE.<new-tag>
# and update MINIO_RELEASE, MINIO_COMMIT and MINIO_MODULE_VERSION below, and
# the image tag in docker-compose.yml, .github/actions/backing-services and
# src/test/java/ai/godance/web/MinioTestImage.java.
#
# License: MinIO is AGPL-3.0. This image is a local dev/test tool and is
# not published or shipped.

# Builder base image. Pinned by digest for reproducibility; override with
# --build-arg GO_IMAGE=... to go through a registry mirror.
ARG GO_IMAGE=golang:1.23.12-alpine3.22@sha256:383395b794dffa5b53012a212365d40c8e37109a626ca30d6151c8348d380b5f

FROM ${GO_IMAGE} AS build

ARG MINIO_RELEASE=RELEASE.2024-12-13T22-19-12Z
# The commit the release tag points to, and the Go pseudo-version that
# names it. Installing by pseudo-version (not by tag) ties the build to the
# commit, so it cannot drift even if the tag were moved.
ARG MINIO_COMMIT=68b004a48f418ed9c4363b9faaf61a90d2f6f502
ARG MINIO_MODULE_VERSION=v0.0.0-20241213221912-68b004a48f41

ENV CGO_ENABLED=0

# Version string as upstream's gen-ldflags.go derives it from the tag
# (2024-12-13T22:19:12Z). The Go caches are dropped in the same layer so the
# layer, and the CI layer cache, hold just the binary.
RUN version="$(echo "${MINIO_RELEASE#RELEASE.}" | sed -E 's/T([0-9]{2})-([0-9]{2})-([0-9]{2})Z$/T\1:\2:\3Z/')" \
    && go install -tags kqueue -trimpath -ldflags "-s -w \
        -X github.com/minio/minio/cmd.Version=${version} \
        -X github.com/minio/minio/cmd.CopyrightYear=$(echo "${version}" | cut -c1-4) \
        -X github.com/minio/minio/cmd.ReleaseTag=${MINIO_RELEASE} \
        -X github.com/minio/minio/cmd.CommitID=${MINIO_COMMIT} \
        -X github.com/minio/minio/cmd.ShortCommitID=$(echo "${MINIO_COMMIT}" | cut -c1-12)" \
        "github.com/minio/minio@${MINIO_MODULE_VERSION}" \
    && /go/bin/minio --version \
    && go clean -cache -modcache

FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8

ARG MINIO_RELEASE=RELEASE.2024-12-13T22-19-12Z
LABEL org.opencontainers.image.title="minio" \
      org.opencontainers.image.version="${MINIO_RELEASE}" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.description="MinIO built from source for Godance local dev and tests"

COPY --from=build /go/bin/minio /usr/bin/minio

EXPOSE 9000 9001

# Same arguments as the official image: `docker run <image> server /data`.
# Health checks can use busybox wget, e.g.
#   wget -q --spider http://localhost:9000/minio/health/live
ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data"]
