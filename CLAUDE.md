# CLAUDE.md — wrunconfig-verify

Context and hard-won facts for anyone (human or AI) working in this repo. Read
before editing `WrunconfigVerifier` or the placeholder handling.

## What this project is

A Gradle plugin (`de.m13t.wrunconfig-verify`) that verifies the Java classpath
declared inside **MSIX-Power-Wrapper** `.wrunconfig` files: every static classpath
entry must exist in the staged app tree, and the declared main class must be
present on the classpath. It fails the build on a broken config so it can gate CI.

It is a Gradle port of an existing Python validator that runs in GitLab CI. The
Python version and this plugin must stay behaviorally aligned; differences are
called out under "Deviations" below.

## Repo layout

```
build.gradle.kts / settings.gradle.kts   Gradle 8+, Kotlin JVM plugin, JDK 17 toolchain
src/main/kotlin/de/m13t/wrunconfig/
  WrunconfigVerifier.kt      PURE logic (no Gradle deps) — parse, resolve, extract, check
  WrunconfigVerifyExtension.kt  DSL: wrunconfigDir, applicationRoot, failOnDropped, failOnDynamic
  WrunconfigVerifyTask.kt    @CacheableTask wrapper: inputs/outputs, report, fails build
  WrunconfigVerifyPlugin.kt  registers extension + task, wires task into `check`
src/test/kotlin/...          JUnit5 unit tests of the pure verifier
tools/WrunconfigVerify.java  same logic as a single-file CLI (JDK 11+, `java <file>.java`)
samples/App.wrunconfig       realistic example
```

Keep verification logic in `WrunconfigVerifier` (Gradle-free) so it stays unit-
testable and reusable. The task/plugin are thin wiring only.

## Commands

```
./gradlew check              # runs verifyWrunconfig (wired into check) + tests
./gradlew verifyWrunconfig   # just the verification; writes build/reports/wrunconfig/verify.txt
./gradlew test               # unit tests
java tools/WrunconfigVerify.java <wrunconfig-dir> <application-root>   # standalone, no Gradle
```

## `.wrunconfig` schema — SOURCE OF TRUTH

Taken from the wrapper's own source, not guessed:
`Weatherlights/MSIX-Power-Wrapper`, project `wcommsixwrap` —
`Program.cs` (element dispatch + `ResolveVariables`), `Runtime.cs` (the `Process`
element), `EnvironmentVariable.cs`.

- The wrapper reads the doc with a streaming `XmlReader` and dispatches **every
  start element by name**; the document root element name is NOT significant.
  Known top-level elements: `Process`, `Service`, `RougeConfig`, `UnwantedFolder`,
  `UnwantedFile`, `VirtualFile`, `VirtualFolder`, `RegistryEntry`, `UpdateHandler`,
  `AppInstallerUpdateHandler`, `LiteWarning`, `PrivacyPolicy`, `SymbolicLink`,
  `EnvironmentVariable`, `RoamingProfile`, `Certificate`.
- The JVM classpath + main class live in **`Process/Arguments`**. `Process` also
  has `Filename`, `WorkingDirectory`, `ArgsSelector`, `WaitForExit`,
  `UseShellExecute`, `WindowStyle`.
- `EnvironmentVariable` has `Name` and `Value` children (and an optional target,
  default `"Process"`).

Every value string is passed through `Program.ResolveVariables` before use.

## Placeholder grammar

Tokens are `[COMMAND|arg|arg|...]`. The wrapper's matcher is **nested-bracket
aware** (a balanced-bracket regex), and args can contain nested tokens; resolution
is recursive (e.g. `RETRIVEFROMREGISTRY` re-resolves its result). Commands seen in
source: `EXENAME`, `APPDIR`, `ARGS` (optional selector), `RESOLVED_ARGS`,
`ARGSSELECTOR`, `CHANGEEXTENSION`, `QUOTE`, `ENV`, `SPECIALFOLDER`,
`RETRIVEFROMREGISTRY`, `WRAPPER_APPDATA`; anything else is treated as an env var.

Key facts that drive our resolution:
- `[APPDIR]` in the wrapper = entry-assembly dir + `\..`. Here it maps to
  `applicationRoot`. The longer literal `[APPDIR]\..` is replaced **before** the
  bare `[APPDIR]` (order matters — same as the Python validator).
- `[RETRIVEFROMREGISTRY|hive|key|value|default]`: the wrapper uses the registry
  value, falling back to **`default`** (the last, 5th field) when absent. At build
  time we always use `default`. In the inner string that is field index 4.
- `[ARGS]`/`[RESOLVED_ARGS]`/`[ARGSSELECTOR]` are runtime args → not available at
  build time → collapse to a separator (space).

### Build-time resolution table (what `resolveToken` does)

| Token | Result at build time |
|---|---|
| `[APPDIR]`, `[APPDIR]\..` | `applicationRoot` |
| `[ARGS]`, `[RESOLVED_ARGS]`, `[ARGSSELECTOR]` | `" "` |
| `[QUOTE\|x]` | `x` |
| `[CHANGEEXTENSION\|path\|ext]` | `path` with extension changed |
| `[RETRIVEFROMREGISTRY\|…\|default]` | `default` |
| `[ENV\|VAR]`, bare `[VAR]` | `System.getenv(VAR)` if set, else leave dynamic |
| `[EXENAME]`, `[SPECIALFOLDER\|…]`, `[WRAPPER_APPDATA]`, … | leave **dynamic** |

A cp entry that still contains `[`/`]` after resolution is **dynamic**: reported
and skipped (can't be verified statically), unless `failOnDynamic` is set. This is
the main capability the Python validator lacks.

## Verification model (must stay in sync with the Python validator)

1. Parse the XML; parse error → `FAIL`.
2. Resolve `Process/Arguments`, tokenize respecting quotes
   (`(?:"[^"]*"|\S)+`), then extract classpath + main via `cpAndMain`:
   - `-cp` / `-classpath` / `--class-path` consume the next token as the classpath.
   - any other `-flag` is skipped.
   - the first non-flag token is the main class.
   - no classpath or no main → `SKIP`.
3. Working dir = `applicationRoot` + basename of the **raw** (unresolved)
   `Process/WorkingDirectory` (Windows-style basename). Missing dir → `FAIL`.
4. Split classpath on `;`. Keep an entry if it is `.`, an existing directory, or a
   `.jar`/`.zip`; otherwise **drop** it (poms, txt, …). Dynamic entries were split
   off in step 2's resolution.
5. Every kept entry must exist (absolute entries used as-is; relative entries
   resolved under the working dir). Missing → `FAIL`.
6. Linkage: the main class's `<pkg>/<Name>.class` resource must be present in a
   kept directory or inside a kept jar/zip. Corrupt jar → `FAIL`. Not found →
   `FAIL`. Present → `OK`.

Exit/return non-zero if any config fails.

## Deviations from the Python validator (intentional)

- **Dynamic entries**: unresolved-placeholder cp entries are skipped, not failed.
- **Absolute cp entries** are used as-is instead of being blindly joined under the
  workdir (Python did `wd / e` for everything). Keeps it correct on Linux CI.
- **Linkage**: deterministic zip/dir presence scan only. Python prefers a real
  `jshell` link when available and falls back to the same scan. Our scan confirms
  the main class is *reachable* and flags corrupt jars, but does **not** link
  transitive deps. Add a `jshell`/`JavaExec` probe if a true link is needed.

## Invariants — don't break these when editing

- `applicationRoot` is `@Internal`; the app tree is tracked for up-to-date checks
  via a separate `@InputFiles applicationFiles`. Don't make `applicationRoot` an
  `@InputDirectory` — it may not exist until a staging task runs, and that would
  fail configuration/validation before the friendly runtime error.
- `verifyWrunconfig` must run **after** whatever stages the app tree
  (`dependsOn`/`mustRunAfter`), because the classpath is checked against staged
  artifacts.
- The `[APPDIR]\..`-before-`[APPDIR]` replacement order is load-bearing.
- Registry default is the **last** pipe field; keep the `>= 5` length guard.
- Group/plugin id (`de.m13t.gradle` / `de.m13t.wrunconfig-verify`) are placeholders
  — rename before publishing.

## Environment / build caveats

- Targets Gradle 8+ and a JDK 17 toolchain; Kotlin JVM plugin 1.9.24, JUnit 5.
  The wrapper is pinned to Gradle 8.10.2 (bundles Kotlin 1.9.24 — must stay
  compatible with the `kotlin("jvm")` plugin version). Do not run the build with a
  Gradle 9.x install directly: its bundled kotlin-stdlib 2.4.0 can't be read by
  the 1.9.x compiler and `:compileKotlin` fails. Use `./gradlew`.
- The verifier core has **no Gradle dependency** by design.
- CI: `.github/workflows/ci.yml` runs `./gradlew build` (compile + `test` +
  `validatePlugins`) on push/PR to `main`/`develop` with Temurin JDK 17.
- NOTE: this project was scaffolded in a JRE-only sandbox with no `gradle`/`kotlinc`
  and no network to `services.gradle.org`, so the Kotlin/Gradle side was not
  compiled there. The verification **algorithm** was validated end-to-end via the
  single-file `tools/WrunconfigVerify.java` against a fixture (valid jar with the
  main class, dir-based cp entry, registry-default jar, dropped `.pom`, corrupt
  jar, dynamic tokens) — all branches confirmed, exit code 1 on failure. The
  Kotlin `WrunconfigVerifier` is a faithful port of that tested logic. `./gradlew
  build` now compiles and all 5 unit tests pass.

## How this was validated (provenance)

- Schema/placeholder facts: read directly from `wcommsixwrap` C# source
  (`Program.cs`, `Runtime.cs`, `EnvironmentVariable.cs`).
- Algorithm: executed `tools/WrunconfigVerify.java` on a synthetic app tree +
  `.wrunconfig` set covering OK / SKIP / missing-entry / corrupt-jar / dynamic.
