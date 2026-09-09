FROM eclipse-temurin:21-jre-jammy

ARG KOTLIN_VERSION=2.0.20
ENV KOTLIN_HOME=/opt/kotlinc
ENV PATH="${KOTLIN_HOME}/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl ca-certificates \
    && curl -sL -o /tmp/kotlin.zip "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" \
    && unzip -q /tmp/kotlin.zip -d /opt \
    && rm /tmp/kotlin.zip \
    && apt-get purge -y unzip curl \
    && rm -rf /var/lib/apt/lists/*

COPY scan-repo.main.kts /action/scan-repo.main.kts

WORKDIR /github/workspace
ENTRYPOINT ["kotlin", "/action/scan-repo.main.kts"]