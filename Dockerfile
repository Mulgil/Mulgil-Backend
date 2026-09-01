FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./

RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies

COPY src ./src

RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 mulgil \
    && useradd --system --uid 10001 --gid mulgil --home-dir /app --shell /usr/sbin/nologin mulgil

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

USER mulgil:mulgil

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
