# CompileLoop

[![CI](https://github.com/LambdaJedi/compileloop/actions/workflows/ci.yml/badge.svg)](https://github.com/LambdaJedi/compileloop/actions/workflows/ci.yml)

A self-testing, AI-assisted code repair engine for Maven projects, built as a free, open-source CLI.

CompileLoop runs a project's build and tests, turns failures into structured diagnostics, (optionally) asks a local AI model to propose a bounded patch, verifies that patch in an isolated workspace, and reports exactly what happened.

> **Core principle:** the model proposes changes; the trusted engine validates, tests, records and presents them. A passing build is evidence, not proof that software is correct.

## Status

Early development. The deterministic build-and-diagnose layer and a basic CLI work; the AI repair loop does not exist yet.

| Piece | State |
|---|---|
| Run `mvn -B test` safely (timeout, process-tree kill, bounded output, log file) | Done, tested |
| Distinguish outcomes: `PASS`, `FAIL`, `TIMEOUT`, `START_ERROR`, `CANCELLED`, `INFRA_ERROR` | Done |
| Parse Maven output into compile errors and test failures, with category and raw excerpt | Done, tested against real Windows output |
| Per-run artifact folder (log + `report.json`), JSON and text reports | Done, tested |
| Continuous integration (Linux and Windows) | Workflow added |
| Command-line interface (`compileloop test <dir>`) | Done, tested |
| AI patch proposal (optional, local model) | Not started |
| Safe patch application in a disposable copy + re-verification | Not started |
| Audit report per attempt | Not started |

## What it is not

- Not an unrestricted autonomous coding agent.
- Not a guarantee that every bug can be fixed.
- Not a tool that executes arbitrary AI-suggested shell commands.
- Not a replacement for code review or domain-specific testing.

## Requirements

- JDK 17 or newer
- Maven 3.9+ on your `PATH` (or a Maven Wrapper in the project being built)
- Windows, macOS or Linux

The first release must work with no AI model and no paid API.

## Build and test

```bash
mvn -B test
```

The suite takes about 30 seconds. The `BuildRunnerTest` cases are integration tests that generate small throwaway Maven projects and run the real Maven against them.

## Using the CLI

Build the runnable jar once (skipping tests makes it quick):

```bash
mvn -B -DskipTests package
```

Then point it at any Maven project:

```bash
java -jar target/compileloop-0.1.0-SNAPSHOT.jar test path/to/maven/project
java -jar target/compileloop-0.1.0-SNAPSHOT.jar test path/to/project --timeout 120
java -jar target/compileloop-0.1.0-SNAPSHOT.jar test path/to/project --json
```

`--json` prints the same report that is saved as `report.json` and keeps the same exit codes.

Example output for a project with a compile error:

```
CompileLoop: mvn -B test
Result: FAIL, exit code 1 (3.7s)

Compile errors (2):
  C:/Users/me/proj/src/main/java/fx/Broken.java:3:13
    incompatible types: java.lang.String cannot be converted to int
  C:/Users/me/proj/src/main/java/fx/Broken.java:4:16
    cannot find symbol
    symbol:   method undefinedMethod()
    location: class fx.Broken

Run:    20261012T115500Z-3f9a1c
Log:    C:\Users\me\proj\target\compileloop\runs\20261012T115500Z-3f9a1c\build.log
Report: C:\Users\me\proj\target\compileloop\runs\20261012T115500Z-3f9a1c\report.json
```

Exit codes: `0` build passed, `1` build failed (compile errors or failing tests), `2` timed out, `3` the build could not be run (for example Maven not found), `64` bad usage. A missing Maven is reported as exit `3`, never as a failing project.

## Using the library

The engine can also be called from Java:

```java
BuildRunner runner = new BuildRunner();
BuildResult result = runner.runMavenTests(Path.of("path/to/maven/project"));

System.out.println(result.outcome());   // PASS, FAIL, TIMEOUT, START_ERROR, ...

for (var e : result.parsed().compileErrors()) {
    System.out.printf("%s:%d:%d %s%n", e.file(), e.line(), e.column(), e.message());
}
for (var f : result.parsed().testFailures()) {
    System.out.printf("%s.%s (line %d): %s%n",
            f.testClass(), f.testMethod(), f.line(), f.message());
}
```

Every run writes its artifacts to its own folder inside the project being built:

```
target/compileloop/runs/<runId>/
  build.log      full, unmodified Maven output
  report.json    structured report (outcome, diagnostics, categories, raw excerpts)
```

`<runId>` is a UTC timestamp plus a random suffix, for example `20261012T115500Z-3f9a1c`. Runs are never overwritten. A build that never started (bad directory, no `pom.xml`, no Maven) creates no run folder.

## Design notes

- **No shell strings.** Processes are started from an argument list, never through a shell.
- **Output is drained concurrently**, so a chatty build cannot block on a full pipe, and in-memory output is capped (the full log goes to disk).
- **The whole process tree is killed on timeout**, not just the parent.
- **Infrastructure failures are never confused with broken source code.** A missing `mvn` is `START_ERROR`, not `FAIL`.
- **The parser degrades quietly.** Unrecognised output yields "nothing found" rather than an exception.
- **Reports never claim more than the evidence.** A failed build with nothing recognised says exactly that, and keeps the raw log; an infrastructure failure is labelled as one.
- **Validated before launch.** The runner checks the directory and `pom.xml` exist before starting Maven.

## Roadmap

1. Verified Java repair: build, diagnose, propose, patch in a copy, re-verify, report.
2. Self-repair: use the same loop on CompileLoop's own failing tests.
3. Multi-language migration through adapters, staged and behind human approval.

AI-generated patches are proposals. They are never applied silently to your working tree.

## Project layout

```
src/main/java/dev/compileloop/cli/
  Main.java               entry point for the jar
  Cli.java                argument parsing and report formatting
src/main/java/dev/compileloop/build/
  BuildRunner.java        runs Maven with timeout, draining and kill-tree
  BuildResult.java        outcome, exit code, log path, output tail, parsed diagnostics
  BuildOutcome.java       PASS / FAIL / TIMEOUT / START_ERROR / CANCELLED / INFRA_ERROR
  BuildOutputParser.java  Maven text -> compile errors, test failures, summary
  ParsedBuild.java        the structured result of parsing
  DiagnosticCategory.java coarse, stable classification of each diagnostic
  ReportWriter.java       builds report.json (no JSON library needed)
src/test/resources/maven/ real Maven output captured on Windows, used as parser fixtures
```

## License

MIT. See [LICENSE](LICENSE).
