# Configuración en GitHub

Guía para publicar el repositorio, ver el pipeline correr y proteger la rama `main`. Todos los comandos se ejecutan en PowerShell desde la carpeta del proyecto.

## Requisitos

- [GitHub CLI](https://cli.github.com/) (`gh`) instalado.
- Una cuenta de GitHub.

## 1. Iniciar sesión con `gh`

```powershell
gh auth login
```

Responde así:

| Pregunta | Respuesta |
|---|---|
| Where do you use GitHub? | `GitHub.com` |
| Preferred protocol for Git operations? | `HTTPS` |
| Authenticate Git with your GitHub credentials? | `Yes` |
| How would you like to authenticate? | `Login with a web browser` |

`gh` muestra un código de 8 caracteres y abre el navegador. Pégalo y autoriza. Para comprobarlo:

```powershell
gh auth status
```

Deberías ver `✓ Logged in to github.com account <tu-usuario>`.

**Por qué:** `gh` guarda un token en el almacén de credenciales del sistema y configura Git para usarlo. A partir de aquí, `git push` funciona sin pedir contraseña.

## 2. Crear el repositorio y subir el código

```powershell
gh repo create Rabedon1/taskflow-api --public --source . --remote origin --push
```

Este único comando:

1. crea el repositorio **público** `Rabedon1/taskflow-api`;
2. registra el remoto `origin` en tu repositorio local;
3. hace push de la rama `main` con todo el historial.

**Por qué público:** el proyecto es educativo. Además, en repositorios públicos son gratis los minutos de GitHub Actions, el escaneo de seguridad (*Code scanning*) y la revisión de dependencias que usaremos más adelante.

## 3. Ver el primer pipeline

El push a `main` dispara `pipeline.yml` automáticamente.

```powershell
gh run list --limit 3
gh run watch
```

`gh run watch` te deja elegir el run y lo muestra en vivo. Después de unos 3–5 minutos deberías ver `✓ ci / verify`. También puedes verlo en el navegador:

```powershell
gh repo view --web
```

Y luego ir a la pestaña **Actions**.

**Qué observar:**

- El grafo del run: `pipeline.yml` llama al job `ci / verify`.
- Los artefactos **app-jar** y **test-reports**, abajo en la página del run.
- Los logs del step *Build, test and quality gates*: ahí verás Testcontainers levantar PostgreSQL dentro del runner de GitHub.

## 4. Proteger `main` con un ruleset

En el navegador: **Settings → Rules → Rulesets → New ruleset → New branch ruleset**.

| Campo | Valor |
|---|---|
| Ruleset name | `protect-main` |
| Enforcement status | **Active** |
| Bypass list | *(vacía)* |
| Target branches → Add target | **Include default branch** |

En **Rules**, marca:

- ✅ **Restrict deletions** (viene marcado)
- ✅ **Block force pushes** (viene marcado)
- ✅ **Require a pull request before merging**, con *Required approvals* en **0**
- ✅ **Require status checks to pass**
  - ✅ *Require branches to be up to date before merging*
  - **Add checks** → escribe `verify` y elige **`ci / verify`** (fuente: GitHub Actions)

Pulsa **Create**.

**Por qué cada regla:**

- **Require a pull request:** nadie, ni siquiera tú, puede hacer push directo a `main`. Todo cambio pasa por un PR, donde el pipeline lo valida antes de integrarlo.
- **Required approvals = 0:** GitHub no permite aprobar tu propio PR. En un equipo real pondrías 1 o más revisores; aquí el pipeline hace de revisor automático.
- **Require status checks (`ci / verify`):** es la regla que hace cumplir "**no se integra si las pruebas fallan**". Sin ella, el pipeline solo avisa; con ella, el botón *Merge* queda bloqueado mientras el check esté en rojo.
- **Up to date before merging:** el PR debe probarse contra la versión **actual** de `main`. Dos PRs que pasan por separado pueden romperse al combinarse.
- **Block force pushes / Restrict deletions:** nadie puede reescribir ni borrar el historial de `main`.
- **Bypass list vacía:** ni el administrador se salta las reglas.

> El check `ci / verify` solo aparece en el buscador si ya corrió al menos una vez en el repositorio. Por eso se configura **después** del paso 3.

## 5. Demostración: un PR con una prueba rota queda bloqueado

Crea una rama y rompe una aserción a propósito:

```powershell
git switch -c demo/failing-test
$f = "src/test/java/io/github/rabedon1/taskflow/task/TaskServiceTest.java"
$c = [IO.File]::ReadAllText((Resolve-Path $f)).Replace('isEqualTo("Nueva tarea")', 'isEqualTo("Tarea rota")')
[IO.File]::WriteAllText((Resolve-Path $f), $c)
git diff
```

`git diff` debe mostrar una sola línea cambiada.

> Se usa `[IO.File]::WriteAllText` porque `Set-Content -Encoding utf8` de Windows PowerShell 5.1 agrega un BOM al archivo, y el compilador de Java lo rechaza.

Sube la rama y abre el PR:

```powershell
git commit -am "test: break an assertion on purpose (demo)"
git push -u origin demo/failing-test
gh pr create --title "Demo: failing test" --body "PR de demostración: una prueba rota debe bloquear el merge."
gh pr checks --watch
```

**Resultado esperado:** `ci / verify` termina en **rojo** (`TaskServiceTest.createDefaultsStatusToTodo`) y en la página del PR el botón de merge aparece bloqueado con *"Required statuses must pass before merging"*. 📸 Toma una captura de esta pantalla para el README y el video.

Cierra el PR sin hacer merge y vuelve a `main`:

```powershell
gh pr close --delete-branch
git switch main
```
