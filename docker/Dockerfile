# Imagen de ejecución de TaskFlow API.
#
# No es multi-stage a propósito: el JAR llega ya compilado y probado desde la
# etapa CI (artefacto app-jar). Recompilarlo aquí produciría un binario
# distinto del que pasó las pruebas. Principio: construir una vez, promover.

# Imagen base fijada por digest: el tag "17-jre" se mueve con cada parche,
# el digest identifica un contenido exacto e inmutable. Dependabot lo actualiza.
FROM eclipse-temurin:24-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2

ARG JAR_FILE=target/*.jar
ARG GIT_SHA=unknown
ARG BUILD_DATE=unknown
ARG VERSION=unknown

# Labels OCI: permiten saber, desde la imagen, de qué repositorio y de qué
# commit exacto salió. Es la trazabilidad imagen -> código.
LABEL org.opencontainers.image.title="taskflow-api" \
      org.opencontainers.image.description="TaskFlow API - CRUD de tareas con pipeline CI/CD" \
      org.opencontainers.image.source="https://github.com/Rabedon1/taskflow-api" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.version="${VERSION}"

# Usuario sin privilegios: si alguien explota la app, no es root en el contenedor.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --chown=10001:10001 ${JAR_FILE} /app/app.jar

USER 10001

# Por defecto la imagen arranca con el perfil de producción; compose y Render
# lo pueden sobrescribir. El puerto lo decide la variable PORT (Render la inyecta).
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

# MaxRAMPercentage=75: la JVM dimensiona su heap según la memoria del
# contenedor (512 MB en Render free) y deja margen para memoria fuera del heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
