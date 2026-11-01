FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -s .mvn/settings.xml -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S classroom && adduser -S -G classroom classroom
WORKDIR /app
COPY --from=build /workspace/api/target/classroom-api-0.1.0-SNAPSHOT.jar app.jar
USER classroom
EXPOSE 8089
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
