FROM maven:3.9.9-eclipse-temurin-21 AS build_image
ARG ARTIFACT_VERSION=unknown
COPY src /app/src
COPY pom.xml /app
WORKDIR /app
RUN mvn --quiet -B versions:set -DnewVersion=$ARTIFACT_VERSION
RUN mvn --quiet -B clean package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-jammy@sha256:daebe9ae03913ec4b2dadd8df60f3ea3df1aa6108fecd5d324d000bdd5c4c816
USER 1001
WORKDIR /app
COPY --from=build_image /app/target/negotiator-link-*.jar /app/negotiator-link.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s CMD curl -f http://localhost:9090/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "negotiator-link.jar"]