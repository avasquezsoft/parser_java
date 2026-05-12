# Etapa 1: Build con Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Etapa 2: Runtime liviano
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/javaparser-service-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
