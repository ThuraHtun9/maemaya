FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd -u 10001 -m appuser && mkdir -p /app/uploads && chown -R appuser:appuser /app

COPY --from=build /build/target/spring-shop-0.0.1-SNAPSHOT.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/springshop \
    SPRING_DATASOURCE_USERNAME=springshop \
    SPRING_DATASOURCE_PASSWORD=springshop \
    APP_UPLOAD_DIR=/app/uploads \
    ADMIN_USERNAME=admin \
    ADMIN_PASSWORD=change-me

VOLUME ["/app/uploads"]
EXPOSE 8080

USER appuser
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
