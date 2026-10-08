# Clinstra backend for Render (or any Docker host).
# Stage 1 builds the jar; stage 2 is a small runtime image that holds only the jar.

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependencies first, so they are cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/backend-*.jar /build/app.jar

# ---- run ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S clinstra && adduser -S clinstra -G clinstra
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER clinstra

# Production mode: refuses to start without real secrets (see the variables below).
ENV SPRING_PROFILES_ACTIVE=prod
# Kept small so it fits a 512 MB instance; raise it on a bigger plan.
ENV JAVA_OPTS="-Xms64m -Xmx320m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Dfile.encoding=UTF-8"

# Render tells the app which port to use through $PORT (default 10000); locally it falls back to 8080.
EXPOSE 8080
# `exec` makes java the main process so it receives Render's shutdown signal.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
