FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY common-dto/ ./common-dto/
COPY ingestion-service/ ./ingestion-service/
COPY indexing-service/ ./indexing-service/
COPY search-service/ ./search-service/
RUN mvn -pl search-service -am clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup -d /app -s /sbin/nologin appuser

# Shared volume directories with permissions
RUN mkdir -p /data/datalake /data/datamarts && chown -R appuser:appgroup /data /app

COPY --from=build --chown=appuser:appgroup /app/search-service/target/search-service-1.0.0.jar app.jar

USER appuser

EXPOSE 7000

ENV JAVA_OPTS="-Dfile.encoding=UTF-8"
ENV JVM_MAX_HEAP="512m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Xmx$JVM_MAX_HEAP -jar app.jar"]
