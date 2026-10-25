FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY common-dto ./common-dto
COPY indexing-service ./indexing-service
RUN mvn -pl indexing-service -am clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/indexing-service/target/indexing-service-1.0.0.jar app.jar
EXPOSE 7002
ENTRYPOINT ["java", "-jar", "app.jar"]