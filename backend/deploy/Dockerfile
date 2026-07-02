# =====================================================================
# Godance backend — multi-stage production image.
#
# Build context MUST be the godance/ root (one level up from this file)
# so the build stage can see pom.xml + src/. The docker-compose.prod.yml
# in this same folder sets context: .. for you.
#
#   docker build -f deploy/Dockerfile -t godance:local .
#
# Final image is ~190 MB, runs as the non-root `app` user, exposes 8080,
# and ships a /healthz container healthcheck.
# =====================================================================

# ---------- Build stage ----------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache Maven deps separately from source: copy pom first, resolve,
# then copy source. Editing only Java files re-uses the dep layer.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src/ ./src/

# Stamp the git SHA into the jar. The build context is backend/ (no .git),
# so Spring's GitProperties can't discover it — CI passes the SHA in as a
# build arg and we write the git.properties Spring Boot looks for.
# Manual builds without the arg keep the "unknown" default (same as today).
ARG GIT_SHA=unknown
RUN printf 'git.commit.id.abbrev=%s\n' "$GIT_SHA" > src/main/resources/git.properties

RUN mvn -B -q -DskipTests package \
    && cp target/*.jar /workspace/app.jar


# ---------- Runtime stage --------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime

# Install curl for HEALTHCHECK + create non-root user in one layer.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 app \
 && useradd  --system --uid 1001 --gid app --home /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

# Container-level healthcheck. Docker / orchestrators surface this in
# `docker ps` and use it for restart decisions. The endpoint is public
# (no auth) and returns build info — see HealthController.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
  CMD curl -fsS http://localhost:8080/healthz >/dev/null || exit 1

USER app
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC"

# `sh -c` so JAVA_OPTS env expansion happens at runtime, not build time.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
