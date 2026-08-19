# Builder: Maven is already here, and Gradle is fetched only when the project
# uses it. Neither wrapper (mvnw/gradlew) is shipped, because both need a
# committed .jar and template repositories are binary-free — the build tool
# comes from a pinned image or a pinned distribution URL instead.
FROM maven:3.9-eclipse-temurin-21 AS build

ARG GRADLE_VERSION=8.11.1

WORKDIR /src
COPY . .

RUN set -eux; \
    if [ -f pom.xml ]; then \
      mvn -B -DskipTests package; \
      cp target/*.jar /app.jar; \
    elif [ -f build.gradle.kts ] || [ -f build.gradle ]; then \
      apt-get update; \
      apt-get install -y --no-install-recommends curl unzip; \
      rm -rf /var/lib/apt/lists/*; \
      curl -fsSL -o /tmp/gradle.zip \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"; \
      unzip -q /tmp/gradle.zip -d /opt; \
      "/opt/gradle-${GRADLE_VERSION}/bin/gradle" --no-daemon -x test bootJar; \
      cp build/libs/*.jar /app.jar; \
    else \
      echo "No pom.xml and no build.gradle.kts — nothing to build." >&2; exit 1; \
    fi

# The Amazon RDS certificate authority bundle. RDS presents a certificate from a
# private CA that no system or JVM trust store carries, so without this the only
# ways to connect are "no TLS" or "TLS without verification" — both of which send
# database credentials over a channel nobody has authenticated.
ADD https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
    /rds-global-bundle.pem
RUN head -1 /rds-global-bundle.pem | grep -q "BEGIN CERTIFICATE"

FROM eclipse-temurin:21-jre-alpine AS runtime

ENV APP_ENV=production \
    SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

WORKDIR /app

# `apk upgrade` patches the OS packages the base image ships behind. Base images
# trail Alpine's security archive by days, which is long enough for openssl alone
# to fail the image gate.
RUN apk --no-cache upgrade \
    && addgroup -g 10001 -S app \
    && adduser -u 10001 -S app -G app

COPY --from=build --chown=app:app /app.jar /app/app.jar
COPY --from=build --chown=app:app /rds-global-bundle.pem /app/certs/rds-global-bundle.pem
# The migration seam. The Job runs `sh bin/migrate` with /app as its working
# directory, so this has to be in the image even though the rest of the source
# tree does not.
COPY --from=build --chown=app:app /src/bin /app/bin

# Import the RDS authorities into the JVM's own trust store.
#
# Doing it here, once, is what lets every driver verify the server with nothing
# but its ordinary sslmode setting. The alternative is per-driver trust
# plumbing — PostgreSQL reads a PEM, MySQL and Oracle want a keystore, MariaDB
# wants yet another flag — which is four ways to get it subtly wrong.
RUN set -eux; \
    mkdir -p /tmp/rdscerts; \
    awk '/BEGIN CERTIFICATE/{n++} {print > ("/tmp/rdscerts/ca-" n ".pem")}' \
        /app/certs/rds-global-bundle.pem; \
    for cert in /tmp/rdscerts/ca-*.pem; do \
      keytool -importcert -noprompt -cacerts -storepass changeit \
        -alias "rds-$(basename "$cert" .pem)" -file "$cert" >/dev/null 2>&1 || true; \
    done; \
    keytool -list -cacerts -storepass changeit 2>/dev/null | grep -c "rds-ca-" ; \
    rm -rf /tmp/rdscerts

USER app

EXPOSE 8080

# wget is in busybox, so the check needs nothing extra installed.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1

# Exec form: the JVM runs as PID 1 and receives SIGTERM directly, which is what
# makes Spring's graceful shutdown drain in-flight requests.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
