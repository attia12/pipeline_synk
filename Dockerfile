# -----------------------------
# Stage 1: Build with Maven
# -----------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

# 1) Provide a default dev profile so we don't fail if not passed
ARG PROFILE=dev
ENV PROFILE=${PROFILE}

WORKDIR /app

# 2) Copy only the pom.xml first to leverage Docker’s build cache for dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# 3) Copy the rest of the project
COPY src ./src

# 4) Build the JAR (skip tests for faster CI/CD or dev builds)
RUN mvn clean package -DskipTests -P ${PROFILE}


# -----------------------------
# Stage 2: Runtime Image
# -----------------------------
FROM eclipse-temurin:21-jdk-jammy

# 5) Create a non-root user (security best practice)
RUN addgroup --system spring && adduser --system spring --ingroup spring
USER spring:spring

# 6) Set working directory in runtime container
WORKDIR /app

# 7) Copy only the final artifact from the builder stage
COPY --from=builder /app/target/*.jar app.jar

# 8) Expose the port your app runs on internally (Spring Boot defaults to 8090)
EXPOSE 8090

# 9) Run the JAR, with server.port defined by an env var or default
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
