# Build stage: compile and extract the Spring Boot layers.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -q -DskipTests package \
 && java -Djarmode=tools -jar target/convo-insights-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination /layers

# Runtime stage: JRE only, non-root, one layer per dependency group so code changes rebuild fast.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
