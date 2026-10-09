# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER 10001
EXPOSE 8080
# Container-aware heap sizing; die on OOM so Kubernetes restarts the pod instead of leaving it half-alive
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOOMError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]


