# Despliegue: Neon + Render + GitHub Environments

Esta guía configura producción desde cero. Al terminar, cada merge a `main` despliega automáticamente la imagen recién publicada, **después de una aprobación manual**, y verifica que producción corre el commit correcto.

```mermaid
flowchart LR
    M[merge a main] --> D[docker / build<br/>publica en GHCR]
    D --> A{{"Aprobación<br/>(environment production)"}}
    A --> H[deploy hook de Render<br/>imgURL = imagen@digest]
    H --> R[Render ejecuta la imagen]
    R --> N[(Neon PostgreSQL)]
    R --> V[verify-deploy.sh<br/>/actuator/info = commit?]
```

| Pieza | Qué guarda | Quién la ve |
|---|---|---|
| Neon | Los datos (tabla `tasks`) | Solo Render, por SSL |
| Render | Credenciales de Neon (variables secretas) | Solo el contenedor en ejecución |
| GitHub, environment `production` | Deploy hook (secreto) y `APP_URL` (variable) | Solo los jobs aprobados para producción |
| Repositorio | **Ningún secreto** | Todo el mundo, porque es público |

---

## 1. Neon: la base de datos

1. Entra a [neon.tech](https://neon.tech) y crea un proyecto llamado `taskflow`, con **PostgreSQL 17**. Elige la región **AWS US East (N. Virginia)**, o la que esté más cerca de la región que uses en Render.
2. En **Connect**, desactiva **Connection pooling**. Quieres la cadena de conexión **directa**: su host **no** contiene `-pooler`. Tiene esta forma:
   ```
   postgresql://USUARIO:CLAVE@ep-xxxx.us-east-1.aws.neon.tech/neondb?sslmode=require
   ```
3. Sepárala en tres valores, para usarlos en Render:

   | Variable | Valor |
   |---|---|
   | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://ep-xxxx.us-east-1.aws.neon.tech/neondb?sslmode=require` |
   | `SPRING_DATASOURCE_USERNAME` | `USUARIO` |
   | `SPRING_DATASOURCE_PASSWORD` | `CLAVE` |

**Por qué la conexión directa y no la *pooled*:** la *pooled* pasa por PgBouncer en modo transacción, que reparte una misma conexión de PostgreSQL entre muchos clientes. Flyway, que crea las tablas al arrancar, necesita una sesión estable: usa *locks* y configuraciones de sesión que se pierden en ese modo. Además, la app ya trae su propio pool, Hikari, con un máximo de 5 conexiones en `application-prod.yml`.

**Por qué `sslmode=require`:** la conexión viaja por internet, de Render a Neon. Con SSL, usuario, clave y datos van cifrados.

**Por qué `jdbc:` y sin usuario en la URL:** el driver de Java usa el formato `jdbc:postgresql://host/db`. Separar las credenciales permite marcarlas como secretas una por una.

## 2. Render: donde corre la app

1. Entra a [render.com](https://render.com) y elige **New → Web Service → Existing image**.
2. Image URL: `ghcr.io/rabedon1/taskflow-api:main`. No necesita credenciales, porque el paquete es público.
3. Name: `taskflow-api`. Region: la misma que en Neon (**Virginia**). Instance type: **Free**.
4. En **Environment Variables**, agrega:

   | Clave | Valor |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `SPRING_DATASOURCE_URL` | (de Neon) |
   | `SPRING_DATASOURCE_USERNAME` | (de Neon) |
   | `SPRING_DATASOURCE_PASSWORD` | (de Neon) |

   No definas `PORT`: Render la inyecta, y la app la lee con `server.port: ${PORT:8080}`.
5. En **Advanced**, configura:
   - **Health Check Path:** `/actuator/health/readiness`
   - **Auto-Deploy:** **No / Off**
6. Crea el servicio. Cuando termine, copia su URL (por ejemplo `https://taskflow-api-xxxx.onrender.com`).
7. Ve a **Settings → Deploy Hook** y copia la URL. **Trátala como una contraseña: quien la tenga puede desplegar.**

**Por qué el health check apunta a `readiness`:** Render no manda tráfico a la nueva versión hasta que este endpoint responde `UP`, es decir, hasta que la app arrancó y puede conectarse a la base. Mientras tanto, la versión anterior sigue atendiendo, así que no hay caída del servicio.

**Por qué Auto-Deploy está apagado:** con Auto-Deploy, Render desplegaría por su cuenta cada vez que cambia el tag `main`, sin aprobación y sin verificación. Así solo hay **un camino a producción**: el pipeline, con aprobación, por digest y auditado en GitHub.

## 3. GitHub: el environment `production`

En el repositorio, entra a **Settings → Environments → New environment** y créalo con el nombre `production`:

1. **Required reviewers:** agrega a `Rabedon1`.
2. **Deployment branches and tags:** elige **Selected branches and tags** y agrega `main`.
3. En **Environment secrets → Add secret**, crea `RENDER_DEPLOY_HOOK_URL` con la URL del deploy hook.
4. En **Environment variables → Add variable**, crea `APP_URL` con la URL del servicio, sin `/` al final.

**Por qué el secreto va en el environment y no en el repositorio:** un secreto del repositorio puede leerlo cualquier job de cualquier workflow. Un secreto del environment solo puede leerlo un job que declare `environment: production`. Ese job, además, **no arranca hasta que un revisor lo aprueba** y solo corre desde `main`. Así, un PR malicioso no puede robar el hook.

## 4. Primer despliegue

1. Haz merge de cualquier PR a `main`. El run publica la imagen y el job `deploy / deploy` queda en **Waiting**.
2. Entra a **Actions**, abre el run y pulsa **Review deployments → production → Approve and deploy**.
3. El job llama al deploy hook con la imagen **por digest** y después ejecuta `scripts/verify-deploy.sh`, que espera hasta 10 minutos a que:
   - `/actuator/health` responda `UP`;
   - `/actuator/info` muestre `git.commit.id.full` igual al commit del merge.
4. Para comprobarlo tú mismo, abre `APP_URL/actuator/info` y `APP_URL/swagger-ui.html`.

**Arranques en frío (plan Free):** Render apaga el servicio tras unos 15 minutos sin tráfico. La primera petición lo despierta y tarda entre 30 y 60 segundos. Neon también suspende la base cuando no se usa, y despertarla tarda menos de un segundo. Por eso:
- la verificación reintenta cada 10 segundos hasta 10 minutos, y tolera un arranque en frío del servicio más el arranque de la versión nueva;
- `application-prod.yml` espera hasta 60 segundos a la base al iniciar (`initialization-fail-timeout`).

## 5. Rollback (volver a una versión anterior)

Busca el digest y el commit de la versión anterior. Ambos aparecen en el **Summary** de su run, en "Despliegue a producción". Después ejecuta:

```powershell
gh workflow run pipeline.yml -f digest=sha256:<digest-anterior> -f git_sha=<sha-completo-anterior>
```

Ese comando solo ejecuta el job `redeploy`: no compila, no prueba ni reconstruye la imagen, porque esa imagen ya pasó por todo eso cuando se publicó. Igual pide aprobación y verifica que producción corre el commit indicado.

## 6. Agregar staging más adelante

1. Crea otro servicio en Render y otra base en Neon (o una *branch* de Neon).
2. Crea el environment `staging`, sin revisores, con su propio `RENDER_DEPLOY_HOOK_URL` y su propio `APP_URL`.
3. En `cd.yml`, agrega un input `environment` y úsalo en `environment.name`.
4. En `pipeline.yml`, agrega un job `deploy-staging` antes de `deploy`, y haz que `deploy` dependa de él (`needs: deploy-staging`).

## Ajustes manuales del repositorio (resumen)

| Dónde | Ajuste | Para qué |
|---|---|---|
| Settings → Rules | Ruleset `protect-main` con los checks obligatorios | Que nada entre a `main` sin pasar el pipeline |
| Settings → Advanced Security | Dependency graph, Dependabot alerts y security updates | Lo necesita `dependency-review` y habilita las alertas |
| Packages → taskflow-api | Visibilidad **Public** | Que Render descargue la imagen sin credenciales |
| Settings → Environments | `production` con revisor, rama `main`, secreto y variable | Aprobación y aislamiento de secretos |
