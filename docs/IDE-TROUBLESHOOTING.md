# Missing Java extension Gradle initialization script

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

Use **Java: Restart Java Language Server**, then refresh the project in the editor.
If the cached import still fails, use **Java: Clean Java Language Server Workspace**.
These commands are documented by [the Java extension](https://github.com/redhat-developer/vscode-java#commands).
An assistant must not operate the editor or launch applications without permission.

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

```sh
ruby tools/repair-java-gradle-init-test.rb
./gradlew help --offline --init-script /absolute/path/from/the/error/<sha256>.gradle
./gradlew clean build :mc26.1:compileGlTestJava :mc26.2:compileGlTestJava :mc26.3:compileGlTestJava --offline --no-build-cache --console=plain
```

Do not downgrade Gradle, suppress Java errors, or disable Gradle imports for this symptom.
GPU test compilation is not GPU execution or proof of correct in-world shader rendering.
