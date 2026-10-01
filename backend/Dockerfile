# ---- build stage -----------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first: this layer is cached unless pom.xml changes, which
# turns a typical rebuild from ~3 minutes into ~20 seconds.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- runtime stage ---------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
RUN apk add --no-cache wget

COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# Container-aware heap sizing; the JVM reads the cgroup limit rather than the
# host's total RAM, which otherwise causes OOM kills under a memory cap.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
