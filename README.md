# wrunconfig-verify

A Gradle plugin that verifies the Java classpath declared in **MSIX-Power-Wrapper**
`.wrunconfig` files: it checks that every static classpath entry exists in the
staged application tree and that the declared main class is actually present on
the classpath. It fails the build when a config is broken, so it can gate CI the
same way a schema validator would.

The schema and placeholder grammar below were taken from the wrapper's own source
(`Weatherlights/MSIX-Power-Wrapper`, `wcommsixwrap`: `Program.cs`, `Runtime.cs`),
not guessed.

## What a `.wrunconfig` looks like

The wrapper walks all start elements under the document root and dispatches each
by name (`Process`, `EnvironmentVariable`, `RegistryEntry`, `RoamingProfile`,
`UnwantedFile`, `RegistryEntry`, `Certificate`, ...). The classpath lives in the
`Process` block:

```xml
<Configuration>
  <Process>
    <Filename>[APPDIR]\client\jre\bin\javaw.exe</Filename>
    <WorkingDirectory>[APPDIR]\client</WorkingDirectory>
    <Arguments>-cp "lib\app.jar;lib\deps.jar" com.acme.client.Main [ARGS]</Arguments>
  </Process>
  <EnvironmentVariable><Name>APP_HOME</Name><Value>[APPDIR]\client</Value></EnvironmentVariable>
</Configuration>
```

`Process` children the wrapper understands: `Filename`, `WorkingDirectory`,
`Arguments`, `ArgsSelector`, `WaitForExit`, `UseShellExecute`, `WindowStyle`.
The launched process is `Filename` with `Arguments`; for a JVM app the classpath
and main class are inside `Arguments`.

### Placeholders

Every value passes through the wrapper's `ResolveVariables`. Tokens are
`[COMMAND|arg|arg|...]`, nested-bracket aware. This plugin resolves the ones that
are knowable at build time and treats the rest as *dynamic* (skipped, not failed):

| Token | Build-time handling |
|---|---|
| `[APPDIR]`, `[APPDIR]\..` | resolved to `applicationRoot` |
| `[ARGS]`, `[RESOLVED_ARGS]`, `[ARGSSELECTOR]` | collapsed to a separator (no runtime args) |
| `[RETRIVEFROMREGISTRY\|hive\|key\|value\|default]` | the **default** (last field), matching the wrapper's fallback |
| `[QUOTE\|x]` | `x` |
| `[CHANGEEXTENSION\|path\|ext]` | `path` with extension changed |
| `[ENV\|VAR]`, bare `[VAR]` | environment variable if set at build time, else dynamic |
| `[EXENAME]`, `[SPECIALFOLDER\|...]`, `[WRAPPER_APPDATA]`, ... | runtime-only → **dynamic**, skipped |

A classpath entry that still contains `[`/`]` after resolution can't be verified
statically, so it's reported and skipped unless `failOnDynamic` is set.

## Using the plugin

```kotlin
plugins {
    id("de.m13t.wrunconfig-verify") version "0.1.0"
}

wrunconfigVerify {
    wrunconfigDir.set(layout.projectDirectory.dir("src/wrunconfig")) // default
    applicationRoot.set(layout.buildDirectory.dir("msix/application"))
    failOnDropped.set(false)   // fail on cp entries that aren't a dir / .jar / .zip
    failOnDynamic.set(false)   // fail on cp entries unresolved at build time
    verifyCatalog.set(false)   // also check the sibling <name>.cat covers the staged tree
}

// The classpath is verified against the *staged* app tree, so run after staging:
tasks.named("verifyWrunconfig") { dependsOn("stageMsixApplication") }
```

`verifyWrunconfig` is wired into `check`, so `./gradlew check` runs it. It writes
`build/reports/wrunconfig/verify.txt` and throws on any failure. Output per file:

```
src/wrunconfig/App.wrunconfig
  main    : com.acme.client.Main
  link    : OK (main class present)
  drop    : 1 non-class cp entries (e.g. notes.pom)
  dynamic : 1 unresolved at build time, skipped (e.g. [EXENAME]_plugins)
  env     : 1 vars

=== 0 failure(s) in 1 config(s) ===
```

## Verification model

- **workdir**: `applicationRoot` + the basename of `Process/WorkingDirectory`
  (matches the reference Python validator).
- **classpath split**: on `;`; entries kept if `.`, an existing directory, or a
  `.jar`/`.zip`; everything else (poms, txt, ...) is *dropped*.
- **existence**: every kept entry must exist (absolute entries used as-is, others
  resolved under the workdir).
- **linkage**: the main class's `.class` resource must be present in a kept
  directory or jar/zip. This is a deterministic presence scan (no JVM subprocess),
  which also flags corrupt jars. It confirms the main class is *reachable*; it is
  not a full JVM link of transitive dependencies. If you need that, run the
  standalone tool below with `jshell` available, or add a `JavaExec` link probe.

## Catalog (`.cat`) sync check — opt-in

With `verifyCatalog.set(true)`, each config is also checked against its **sibling
Authenticode catalog** — `App.exe.wrunconfig` pairs with `App.exe.cat` in the same
directory. Every file staged under `applicationRoot` must have its hash listed as
a member of the catalog; a file that isn't means the payload changed since the
catalog was signed and **the package must be re-signed**. A missing, unparseable,
or drifted catalog fails the build:

```
src/wrunconfig/App.exe.wrunconfig
  main    : com.acme.client.Main
  link    : OK (main class present)
  catalog : DRIFT - 2/312 staged file(s) not in App.exe.cat - re-sign required
  resign  : client/lib/deps.jar, client/config/app.properties

=== 1 failure(s) in 1 config(s) ===
```

Catalog parsing is dependency-free (a minimal DER walk collecting member digests);
it covers `signtool` / `makeappx` catalogs. It checks **hash coverage only** — not
the signature or the signer's trust chain. This check is plugin-only; the
standalone CLI does not implement it.

## Standalone CLI (no Gradle)

`tools/WrunconfigVerify.java` is the same logic as a single-file program, runnable
on JDK 11+ without compiling:

```bash
java tools/WrunconfigVerify.java <wrunconfig-dir> <application-root>
```

Useful for the existing pipeline / a quick check outside a Gradle build. It exits
non-zero on failure.

## Notes

- Group/plugin id (`de.m13t.gradle` / `de.m13t.wrunconfig-verify`) are placeholders;
  rename in `build.gradle.kts` before publishing.
- Targets Gradle 8+ / JDK 17 toolchain. The verifier core has no Gradle
  dependencies and is unit-tested (`src/test/kotlin`); the plugin wiring targets
  the standard `check` lifecycle.
