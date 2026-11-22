# Flujo del pipeline

Este documento explica **qué** hace cada parte del pipeline, **por qué** está diseñada así y **qué pasaría si no**. Para el detalle de cada herramienta, ver [tooling.md](tooling.md).

## Vista general

```mermaid
flowchart LR
    PR[Pull Request a main] --> P{pipeline.yml}
    PUSH[Push / merge a main] --> P
    P --> SEC[security.yml<br/>Gitleaks + dependency review]
    P --> CI[ci.yml<br/>verify + JAR]
    CI -- artefacto app-jar --> D[docker.yml<br/>imagen + Trivy + GHCR]
    D -- digest --> CD[cd.yml<br/>aprobación + Render + verificación]
    SEC --> CD
```

| Archivo | Tipo | Responsabilidad |
|---|---|---|
| `.github/workflows/pipeline.yml` | Orquestador | Decide qué etapas corren según el evento y las encadena |
| `.github/workflows/ci.yml` | Reutilizable (`workflow_call`) | Pruebas, calidad y empaquetado del JAR |
| `.github/workflows/docker.yml` | Reutilizable (`workflow_call`) | Construir la imagen **una vez** con el JAR de CI, escanearla y publicarla |
| `.github/workflows/security.yml` | Reutilizable (`workflow_call`) | Secretos en el historial y dependencias vulnerables |
| `.github/workflows/cd.yml` | Reutilizable (`workflow_call`) | Desplegar un digest en Render y verificar el commit |
| `.github/dependabot.yml` | Configuración | Actualizaciones semanales de dependencias, Actions e imagen base |
| `scripts/verify-deploy.sh` | Script | Verificación post-despliegue (con prueba en `scripts/test-verify-deploy.sh`) |

---

## 1. El orquestador y los workflows reutilizables

GitHub Actions ejecuta **workflows**: archivos YAML en `.github/workflows/`. Cada workflow tiene **disparadores** (`on:`) y **jobs**. Un job corre en una máquina virtual nueva (un *runner*) y ejecuta **steps** en orden.

Un **workflow reutilizable** declara `on: workflow_call` y no se dispara solo: otro workflow lo invoca como si fuera una función, con `uses: ./.github/workflows/ci.yml`.

**¿Por qué un orquestador más workflows reutilizables?**

- **Una responsabilidad por archivo.** `ci.yml` solo sabe probar y empaquetar. No sabe nada de Docker ni de Render. Si mañana cambias de proveedor de despliegue, `ci.yml` no se toca.
- **Una sola ejecución visible.** En la pestaña *Actions*, un PR o un merge aparece como **un** run con todas sus etapas encadenadas en un grafo. La alternativa (workflows independientes que se disparan entre sí con `workflow_run`) produce varias ejecuciones separadas, difíciles de seguir. Además, `workflow_run` siempre corre con el código de `main` y no con el del PR, lo que trae sorpresas.
- **Datos que viajan limpios.** Las etapas se pasan información con `inputs` y `outputs`, como parámetros de una función. Así viajará el *digest* de la imagen hasta el despliegue.

## 2. Disparadores: qué corre y cuándo

| Evento | Qué significa | Qué corre hoy |
|---|---|---|
| `pull_request` → `main` | Alguien propone integrar cambios | security (las dos revisiones) + CI + construir y escanear la imagen |
| `push` → `main` | Los cambios ya se integraron (merge) | secret-scan + CI + imagen + **publicar** + **deploy con aprobación** + verificación |
| `workflow_dispatch` | Ejecución manual (`gh workflow run`) | **Solo** `redeploy` de un digest ya publicado (rollback) |

**Un PR nunca publica ni despliega:** es código que todavía no fue aprobado.

## 3. Etapa CI (`ci.yml`)

```
checkout → setup-java (+caché) → ./mvnw verify → subir JAR → subir reportes
```

### `./mvnw verify`: el mismo comando en local y en CI

El pipeline **no** reimplementa las verificaciones en YAML: llama al mismo comando que corres en tu máquina. `verify` ejecuta, en este orden:

1. Compilación.
2. **Pruebas unitarias** (`*Test`, Surefire): lógica aislada con Mockito, en milisegundos.
3. Empaquetado del JAR.
4. **Pruebas de integración** (`*IT`, Failsafe): PostgreSQL real con Testcontainers. Los runners `ubuntu-latest` ya traen Docker, así que funcionan sin configuración extra.
5. **Spotless** (formato), **SpotBugs** (bugs comunes) y **JaCoCo** (cobertura ≥ 80 %).

Si cualquier paso falla, el comando devuelve un código distinto de 0 y el job queda en rojo.

> **Si no fuera así:** con controles definidos solo en el YAML, descubrirías los errores recién al hacer push, y "en mi máquina funciona" volvería a ser un problema.

### Caché de Maven

`setup-java` con `cache: maven` guarda `~/.m2` entre ejecuciones, con una clave basada en el `pom.xml`. La primera ejecución descarga todas las dependencias; las siguientes las reutilizan. Esto reduce minutos por run.

### El JAR como artefacto: la semilla de "construir una vez"

Al terminar, el job sube `target/taskflow-api-*.jar` como **artefacto** `app-jar`. En la etapa Docker, la imagen se construirá copiando **este** JAR: el mismo binario que pasó todas las pruebas. No se recompila.

> **Si no fuera así:** si cada etapa recompilara, el binario desplegado no sería exactamente el que se probó (otra versión de una dependencia resuelta en otro momento, otra JVM, etc.). "Construir una vez y promover" elimina esa duda.

Los reportes de pruebas y cobertura se suben con `if: always()`: también, y sobre todo, cuando algo falla, para poder descargar el reporte y ver *por qué* falló.

## 4. Etapa Docker (`docker.yml`)

```
checkout → descargar app-jar → Buildx → metadata (tags) → build (load) → comprobar usuario y labels
```

### Construir una vez, a partir del JAR de CI

El job `docker / build` **no compila**: descarga el artefacto `app-jar` que subió `ci / verify` y lo copia dentro de la imagen. `needs: ci` garantiza el orden: si las pruebas fallan, la imagen ni siquiera se construye.

> **Si no fuera así:** con un Dockerfile multi-stage que compila por su cuenta, la imagen llevaría un binario que nunca pasó las pruebas. Casi siempre sería idéntico, pero "casi" no es una garantía.

### Tags y digest

| Referencia | Ejemplo | ¿Cambia? | Uso |
|---|---|---|---|
| Tag `sha-<7>` | `ghcr.io/rabedon1/taskflow-api:sha-e017916` | No en la práctica, porque un commit nunca se reconstruye | Saber qué commit es una imagen |
| Tag `main` | `ghcr.io/rabedon1/taskflow-api:main` | **Sí**: apunta a lo último integrado | Comodidad para humanos |
| Digest | `ghcr.io/rabedon1/taskflow-api@sha256:9f2c…` | **Nunca**: es la huella del contenido | **El despliegue** |

Un tag es una etiqueta que se puede despegar y pegar en otra imagen. El digest es el hash del contenido: si cambia un byte, cambia el digest. Por eso Render desplegará **por digest**: corre exactamente la imagen que se probó, se escaneó y se aprobó, aunque alguien vuelva a usar el tag `main` en otra imagen.

En un PR el tag `main` no se genera (`enable=${{ inputs.push }}`). Un PR nunca debe hacerse pasar por "lo que está en main".

### Labels con valores reales

El Dockerfile declara los labels OCI y el workflow les pasa los valores reales como `build-args`: `GIT_SHA` es el commit del run y `BUILD_DATE`/`VERSION` vienen de `metadata-action`. El último step verifica dos cosas sobre la imagen: que **no corre como root** y que el label `revision` **coincide con el commit**. Si algo de esto se rompiera en un cambio futuro, el pipeline lo detendría.

### Caché de capas

`cache-from/cache-to: type=gha` guarda las capas en la caché de GitHub Actions. La capa de Java (unos 300 MB) se reutiliza entre runs, y solo se reconstruye la capa del JAR.

### Cómo viajan los datos entre workflows

Un workflow reutilizable funciona como una función: recibe `inputs` y devuelve `outputs`. El valor sube por tres niveles:

```
step (echo "tag=..." >> $GITHUB_OUTPUT)
  → job      outputs: tag: ${{ steps.ref.outputs.tag }}
  → workflow outputs: tag: value: ${{ jobs.build.outputs.tag }}
  → pipeline.yml lo lee como ${{ needs.docker.outputs.tag }}
```

Con este mismo camino, en la etapa de publicación viajará el **digest** desde `docker.yml` hasta el job de despliegue.

## 5. Buenas prácticas de seguridad aplicadas

### Acciones fijadas por SHA completo

```yaml
uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
```

Un tag como `@v7` es **mutable**: quien controle ese repositorio, o un atacante que lo comprometa, puede moverlo a otro código. Tu pipeline ejecutaría ese código nuevo con acceso a tus secretos, sin que cambies una línea.

Esto ocurrió en marzo de 2025 con `tj-actions/changed-files`: se reescribieron sus tags para apuntar a código malicioso que imprimía los secretos de miles de repositorios en los logs.

Un **SHA de commit** es inmutable: identifica un contenido exacto. El comentario `# v7.0.1` es para humanos. Dependabot (etapa 4) actualizará SHA y comentario juntos cuando salgan versiones nuevas.

### Permisos mínimos del `GITHUB_TOKEN`

Cada job recibe automáticamente un token, `GITHUB_TOKEN`, para hablar con la API de GitHub. Por defecto puede tener permisos amplios, como escribir en el repositorio.

```yaml
permissions:
  contents: read
```

Así, el token solo puede **leer** el código. Si un paso fuera comprometido (por ejemplo, una dependencia maliciosa ejecutándose durante las pruebas), no podría hacer push, crear releases ni publicar paquetes. Los jobs que necesiten más, como publicar en GHCR, lo pedirán **explícitamente y solo para ellos**.

También usamos `persist-credentials: false` en el checkout, para que el token no quede guardado en `.git/config`, donde cualquier paso posterior podría leerlo.

### `concurrency`

```yaml
concurrency:
  group: pipeline-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
```

- **En un PR**, si haces otro push mientras el pipeline anterior sigue corriendo, el anterior se cancela. Su resultado ya no importa y ahorras minutos.
- **En `main` no se cancela nada.** Cada merge tiene que terminar su recorrido completo. Más adelante, eso incluye el despliegue, y cancelar un despliegue a medias es peligroso.

### Validación local de los workflows

Los YAML se validan antes de subirlos con [actionlint](https://github.com/rhysd/actionlint), que detecta errores de sintaxis, expresiones mal escritas (`github.evnt_name`) y problemas de shell:

```bash
docker run --rm -v "${PWD}:/repo" -w /repo rhysd/actionlint:latest -color
```

---

## 6. Etapa de seguridad

| Control | Dónde | Cuándo bloquea |
|---|---|---|
| **Gitleaks** | `security / secret-scan` | Un token, clave o contraseña en **cualquier commit** del historial |
| **Dependency review** | `security / dependency-review` (solo PR) | El PR agrega o sube una dependencia con un CVE de severidad alta o crítica |
| **Trivy** | step en `docker / build` | La imagen tiene un CVE **crítico o alto con parche disponible**, en Ubuntu o en el JAR |
| **Dependabot** | `.github/dependabot.yml` | No bloquea: abre PRs semanales que pasan por todo el pipeline |

`security` corre **en paralelo** con `ci`, porque no necesita esperar a las pruebas. El deploy depende de `security`: con un secreto filtrado no se despliega.

> **Caso real de este repositorio:** Dependabot propuso cambiar la imagen base a `eclipse-temurin:24-jre`. Trivy encontró 16 CVE altos en su Ubuntu y bloqueó el PR. La imagen vulnerable nunca se publicó.

## 7. Publicación en GHCR

Solo en `push` a `main` y **después** de que Trivy pasa:

1. `docker login ghcr.io` con el `GITHUB_TOKEN` del run (permiso `packages: write`, solo en este job). No hace falta un token personal.
2. `docker push` de la **misma** imagen que se escaneó. No hay un segundo build.
3. Se lee el **digest** que asignó el registro. Se valida su formato, se expone como output y se muestra en el *Summary* del run.
4. **Attestation de procedencia (SLSA):** se firma con una identidad OIDC temporal (Sigstore) y se guarda junto a la imagen. Cualquiera puede verificarla:
   ```bash
   gh attestation verify oci://ghcr.io/rabedon1/taskflow-api@sha256:<digest> --owner Rabedon1
   ```

## 8. Despliegue (`cd.yml`)

1. El job declara `environment: production` y **se pausa** hasta que un revisor lo aprueba.
2. Valida que `image_digest` sea `sha256:` + 64 caracteres y que `git_sha` tenga 40.
3. Llama al **deploy hook** de Render con `imgURL=ghcr.io/rabedon1/taskflow-api@<digest>`, codificada como URL. El hook es un secreto del environment, se enmascara en los logs y nunca se imprime.
4. `scripts/verify-deploy.sh` consulta producción cada 10 segundos, durante un máximo de 10 minutos:
   - `/actuator/health` debe responder `UP`;
   - `/actuator/info` debe mostrar `git.commit.id.full` igual al commit del merge.

   Si el commit no coincide, sigue esperando, porque la versión anterior responde hasta que la nueva está lista. Si se agota el tiempo, el pipeline queda en rojo.

`concurrency: production` garantiza un solo despliegue a la vez, y nunca cancela uno a medias.

## 9. Del PR a producción, paso a paso

```mermaid
sequenceDiagram
    actor Dev as Desarrollador
    participant GH as GitHub (PR + ruleset)
    participant CI as ci / verify
    participant SEC as security
    participant DK as docker / build
    participant REG as GHCR
    participant ENV as environment production
    participant R as Render
    participant DB as Neon

    Dev->>GH: push de la rama + PR
    par en paralelo
        GH->>SEC: Gitleaks + dependency review
    and
        GH->>CI: mvnw verify (Testcontainers)
    end
    CI-->>DK: artefacto app-jar
    DK->>DK: docker build + Trivy (no publica en un PR)
    GH-->>Dev: checks en verde, merge habilitado
    Dev->>GH: merge a main
    Note over GH,DK: se repiten secret-scan, CI y docker sobre main
    DK->>REG: push + digest + attestation
    DK-->>ENV: digest (output)
    ENV-->>Dev: Review deployments
    Dev->>ENV: Approve
    ENV->>R: deploy hook con imgURL = imagen@digest
    R->>REG: pull por digest
    R->>DB: Flyway + conexión SSL
    ENV->>R: verify-deploy.sh (/health, /info)
    R-->>ENV: UP + commit del merge
    ENV-->>Dev: deploy verde
```

## 10. Construir una vez, promover el digest

El mismo binario recorre todas las etapas, y cada una le agrega una garantía:

| Etapa | Qué pasa con el artefacto | Garantía |
|---|---|---|
| CI | Se compila **una vez** y pasa las pruebas → `app-jar` | El código funciona |
| Docker | El **mismo JAR** entra en la imagen (no se recompila) | La imagen contiene lo que se probó |
| Trivy | Se escanea **esa** imagen | No hay vulnerabilidades conocidas corregibles |
| GHCR | Se publica **esa** imagen → `sha256:…` | Tiene una identidad inmutable y procedencia firmada |
| Deploy | Render corre **ese digest**, no un tag | Producción corre exactamente lo aprobado |
| Verificación | `/actuator/info` = commit del merge | Se comprueba, no se supone |
| Rollback | Se redespliega **un digest anterior** | Volver atrás es seguro y no reconstruye nada |

> **Si no fuera así:** si cada etapa reconstruyera desde el código, cada una produciría un artefacto ligeramente distinto. Nadie podría afirmar con certeza que lo que corre en producción es lo que pasó las pruebas y el escaneo.

## 11. Cómo reproducir la demo del fallo

Esta demo muestra que el pipeline **obliga**, no solo avisa. Los comandos completos están en [github-setup.md](github-setup.md), sección 5:

1. Crea la rama `demo/failing-test` y cambia una aserción en `TaskServiceTest`: `"Nueva tarea"` pasa a `"Tarea rota"`.
2. Haz push y abre un PR.
3. `ci / verify` queda en **rojo** y el botón de merge aparece bloqueado (*"Required statuses must pass before merging"*).
4. Corrige la aserción y haz push otra vez: los checks quedan en verde y el merge se habilita. O cierra el PR sin hacer merge.

Otras demos que puedes reproducir:

| Demo | Cómo provocarla | Qué la frena |
|---|---|---|
| Secreto filtrado | Commitear un archivo con un token falso (por ejemplo `ghp_` + 36 caracteres) | `security / secret-scan` |
| Imagen vulnerable | Cambiar la base del Dockerfile a una imagen vieja | Trivy en `docker / build` |
| Rollback | `gh workflow run pipeline.yml -f digest=sha256:<anterior> -f git_sha=<sha>` | El job `redeploy` (con aprobación) |
