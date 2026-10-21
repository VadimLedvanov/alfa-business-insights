FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build --chown=app:app /build/target/core.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
