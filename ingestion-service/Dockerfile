FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/ingestion-service/target/ingestion-service-1.0.0.jar app.jar
EXPOSE 7001
ENTRYPOINT ["java", "-jar", "app.jar"]