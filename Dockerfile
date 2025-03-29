FROM eclipse-temurin:17-jdk-alpine as build
WORKDIR /workspace/app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

RUN chmod +x ./mvnw
RUN ./mvnw install -DskipTests
RUN mkdir -p target/dependency && (cd target/dependency; jar -xf ../*.jar)

FROM eclipse-temurin:17-jre-alpine
VOLUME /tmp
ARG DEPENDENCY=/workspace/app/target/dependency
COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF
COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app

ENTRYPOINT ["java","-cp","app:app/lib/*","com.company.transactionrecovery.TransactionRecoveryApplication"]

# Health check para verificar que la aplicación está funcionando
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q --spider http://localhost:8080/api/actuator/health || exit 1

# Metadatos
LABEL maintainer="Tu Nombre <tu.email@example.com>"
LABEL version="1.0"
LABEL description="Servicio de recuperación para operaciones inconclusas"