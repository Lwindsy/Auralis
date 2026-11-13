FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY apps/backend apps/backend
COPY apps/intelligence/src/auralisy_intelligence/contracts apps/intelligence/src/auralisy_intelligence/contracts
RUN mvn -B -q -f apps/backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /build/apps/backend/target/auralisy-core-database-0.1.0-SNAPSHOT-online.jar /app/auralisy.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/auralisy.jar"]
