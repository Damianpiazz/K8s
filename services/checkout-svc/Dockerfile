# Build stage — Maven with Temurin JDK 17
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# Cache dependencies first (leverages layer caching in CI)
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# Runtime stage — Temurin JRE 17 (smaller, no compiler needed)
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1000 appuser
COPY --from=build /workspace/target/checkout-svc-*.jar app.jar
USER appuser
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]