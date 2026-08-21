# syntax=docker/dockerfile:1

# ---- build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY src ./src/
COPY pom.xml .
RUN mvn clean package -DskipTests

# ---- runtime stage ----
FROM archlinux:latest
ARG TZ=America/Bahia
RUN pacman -Syu --noconfirm --needed jre21-openjdk-headless tzdata \
    && pacman -Scc --noconfirm
RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
RUN mkdir -p /app/config
COPY --from=build /app/target/java-telegram-notifier.jar /app/java-telegram-notifier.jar
ENTRYPOINT [ "java", "-Dspring.config.additional-location=optional:/app/config/config.yaml", "-jar", "/app/java-telegram-notifier.jar" ]
