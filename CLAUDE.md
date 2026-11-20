# TaskFlow API — guía para Claude

Proyecto educativo: una API REST de tareas (Spring Boot) cuyo verdadero protagonista es el **pipeline CI/CD** (GitHub Actions → GHCR → Render, con base de datos en Neon).

- Diseño (fuente de verdad): `docs/superpowers/specs/2026-11-18-taskflow-api-cicd-design.md`
- Plan de implementación: `docs/superpowers/plans/2026-11-18-taskflow-api-cicd.md`

## Reglas de trabajo con el autor (Rabedon1)

- **El autor ejecuta todos los commits y todas las operaciones de GitHub** (`git commit`, `git push`, `gh`, PRs, secretos, environments, rulesets). Claude escribe el código y entrega los comandos exactos con su explicación; no los ejecuta.
- **Explicar en detalle** todo lo relacionado con CI/CD, contenedores, registro, seguridad del pipeline, despliegue e infraestructura: qué hace cada pieza, por qué se hace así y qué pasaría si no. En español y en lenguaje claro.
- Desde que `main` está protegida, todo cambio entra por rama + Pull Request.

## Comandos

| Qué | Comando |
|---|---|
| Build completo (unitarias, integración, formato, SpotBugs, cobertura ≥ 80 %) | `./mvnw verify` |
| Solo pruebas unitarias | `./mvnw test` |
| Una prueba concreta | `./mvnw test -Dtest=NombreTest` / `./mvnw verify -Dit.test=NombreIT` |
| Arreglar el formato | `./mvnw spotless:apply` |
| Levantar PostgreSQL local | `docker compose up -d postgres` |
| Ejecutar la app en local | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |

Las pruebas `*IT` usan **Testcontainers**: necesitan Docker Desktop corriendo.

## Convenciones

- Java 17, Spring Boot 4.2. Paquete raíz `io.github.rabedon1.taskflow`, organizado por funcionalidad (`task/`, `common/`).
- Pruebas unitarias: `*Test` (Surefire). Integración: `*IT` (Failsafe, PostgreSQL real con Testcontainers).
- Errores HTTP en formato RFC 9457 (`application/problem+json`).
- Migraciones con Flyway en `src/main/resources/db/migration`; Hibernate solo valida (`ddl-auto: validate`).
- Acciones de GitHub fijadas por SHA completo con comentario de versión; permisos mínimos por job.
- Nunca poner secretos en el repositorio ni imprimirlos en logs.
- Archivos de texto con fin de línea LF salvo `*.cmd` (ver `.gitattributes`); `mvnw` debe seguir siendo ejecutable en Git (`100755`).
