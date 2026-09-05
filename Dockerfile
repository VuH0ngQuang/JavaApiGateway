# --- build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the pom first so dependency resolution is cached across builds unless
# pom.xml itself changes -- source edits alone won't invalidate this layer.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# --- runtime stage ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# maven-shade-plugin already produces a self-contained fat jar with the
# Main-Class manifest set (see pom.xml) -- nothing else needs to be on the
# classpath at runtime.
COPY --from=build /build/target/java-api-gateway.jar app.jar

EXPOSE 1221

# --enable-native-access=ALL-UNNAMED: Netty's native Epoll transport (used
# automatically on Linux -- see Main.java) calls a restricted method that
# otherwise prints a JDK warning on every startup.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
