# Missing Java extension Gradle initialization script

Current layout: the root, `common` and `fabric` are the three projects in a version branch.
The five-project results below describe the earlier multi-version layout.
After switching branches, run Java: Reload Projects and wait for import completion.

An error on line 1 of `build.gradle.kts` can originate outside the build script:

```text
Could not run phased build action ...
The specified initialization script '/.../T/<sha256>.gradle' does not exist.
```

On September 21, 2026, the missing `db3b08fc...` script matched the SHA-256 of
`gradle/init/init.gradle` inside the installed Java extension's JDT LS core JAR.
The extension was referencing a deleted temporary resource. The log does not establish
what deleted it. Changing Gradle dependencies or creating an empty script is not a fix.

A later import revealed that the Protobuf script (`52cde0cf...`) was also missing. Restoring
only the first reported file was insufficient. The batch recovery below checks all nine bundled
Gradle resources; seven were missing, while the main and annotation-processing scripts already existed.

## Recovery

After restoring the scripts, use **Java: Reload Projects** in the editor's command palette
(`Cmd+Shift+P` on macOS). If the error remains, use **Java: Clean Java Language Server Workspace**,
confirm the restart and wait for the project import to finish. Restarting the language server
alone can reload persisted error markers without performing a fresh Gradle synchronization.
These commands are documented by [the Java extension](https://github.com/redhat-developer/vscode-java#commands).
Do not operate the editor or launch applications without the maintainer's permission.

Prefer checking the complete bundle rather than repairing files one error at a time:

```sh
ruby tools/repair-java-gradle-init.rb /absolute/path/to/org.eclipse.jdt.ls.core_<version>.jar --all
```

Existing exact copies are verified and left untouched. All destinations are checked before any
missing file is created; a conflicting file or symlink aborts the repair. No extension is patched.

For terminal-only recovery of the missing file, use the installed extension's original resource:

```sh
ruby tools/repair-java-gradle-init.rb \
  /absolute/path/to/redhat.java-<version>/server/plugins/org.eclipse.jdt.ls.core_<version>.jar \
  /absolute/path/from/the/error/<sha256>.gradle
```

The helper only restores a bundled Gradle resource whose SHA-256 matches the requested filename.
The destination must be directly in the current user's temporary directory. It refuses existing
files, symlinks, other destinations and unmatched hashes. It does not edit extension files,
workspace caches, Gradle settings or diagnostics, and does not start the editor. Run it only
with the JDT LS JAR from the trusted, installed Java extension, never an arbitrary downloaded JAR.
Refresh the Java project afterward; successful command-line compilation alone does not clear
an already displayed IDE diagnostic. Temporary-file restoration is recovery, not an upstream
fix for whatever removed the file.

## Gradle 9 import locking

After restoring the files, a separate error appeared at line 88 of `gradle/apt/init.gradle`:

```text
Resolution of the configuration ':common:annotationProcessor' was attempted without an exclusive lock.
```

This is the [JDT LS parallel-import bug](https://github.com/eclipse-jdtls/eclipse.jdt.ls/issues/3807).
`gradle.properties` now defaults to `org.gradle.parallel=false`. This keeps dependency discovery,
annotation processing, imports and diagnostics enabled; no exception is swallowed. The trade-off is
serial project builds. CLI-only builds may opt into `--parallel`; IDE imports should not do so until
the upstream model builder is fixed. Do not patch the extension's hash-named script to hide errors.

`tools/CheckIdeImport.java` replays the actual annotation-processor tooling model, not a normal build:

```sh
"$JAVA_HOME/bin/java" --class-path "$GRADLE_HOME/lib/*" tools/CheckIdeImport.java \
  . /absolute/path/from/the/error/<apt-script-sha256>.gradle
```

Use JDK 21 and the Gradle 9.7.1 wrapper distribution directory for these variables. Appending
`--parallel` deliberately reproduces the original locking failure and is a negative regression test.

## Headless verification

`CheckIdeImport.java` checks the annotation-processor model only. It does not exercise the
phased Eclipse model import named in the missing-script error. Use `CheckIdePhasedImport.java`
for that additional check, supplying every `--init-script` path from the affected workspace's
`org.eclipse.buildship.core.prefs` in the same order. Read that file without editing the IDE cache.

```sh
"$JAVA_HOME/bin/javac" -cp "$GRADLE_HOME/lib/*" -d .gradle/ide-import-check tools/CheckIdePhasedImport.java
"$JAVA_HOME/bin/java" -cp ".gradle/ide-import-check:$GRADLE_HOME/lib/*" \
  CheckIdePhasedImport . /absolute/main-init.gradle /absolute/protobuf-init.gradle /absolute/other-init.gradle
```

The script paths above are placeholders; use the actual hash-named files. Compile this helper
before running it because Gradle must deserialize its build-action classes in a separate process.
It queries both phases and resolves Eclipse models/classpaths, without starting the editor or
Minecraft. It does not replace Buildship's workspace synchronization or clear its diagnostics.

### September 21 follow-up

- The reported Protobuf script (`52cde0cf...`) was present and byte-identical to the installed
  extension resource. Batch verification restored a separate missing Scala support resource.
- The five init scripts in the cached Buildship preferences imported all five projects through
  the phased Tooling API: root, common, mc26.1, mc26.2 and mc26.3. All external classpath files existed.
- Annotation-processor model discovery also passed for all five projects.
- A negative test with a nonexistent, isolated script path reproduced the same
  `Could not run phased build action` / `does not exist` failure. No real IDE script was removed.
- Before the reload completed, the persisted root-project `.markers` file contained the old
  missing-Protobuf error and the cached project list had no mc26.3 entry. The user still saw the
  error during this interval. At 19:01:17, the IDE logged `Projects updated in 101091 ms` and
  mc26.3 appeared in its workspace, without a new missing-script error. Wait for import completion
  before judging Reload Projects. A clean workspace is the fallback only if the diagnostic remains.
- A `.markers` snapshot alone does not establish a live diagnostic: Eclipse can record newer
  changes in `.markers.snap`. Verify the editor's Problems view; do not manually remove either file.

Do not delete `.markers`, rewrite Buildship preferences, suppress diagnostics or edit the build
just to hide this error. Until the editor performs a fresh successful import, report the IDE result
as unverified even when terminal model checks pass.

```sh
ruby tools/repair-java-gradle-init-test.rb
./gradlew help --offline --init-script /absolute/path/from/the/error/<sha256>.gradle
./gradlew clean build :fabric:compileGlTestJava --offline --no-build-cache --console=plain
```

Do not downgrade Gradle, suppress Java errors, or disable Gradle imports for this symptom.
GPU test compilation is not GPU execution or proof of correct in-world shader rendering.
