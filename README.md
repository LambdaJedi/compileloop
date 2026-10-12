# CompileLoop

A self-testing, AI-assisted code repair engine for Maven projects, built as a free, open-source CLI.

CompileLoop runs a project's build and tests, turns failures into structured diagnostics, (optionally) asks a local AI model to propose a bounded patch, verifies that patch in an isolated workspace, and reports exactly what happened.

> **Core principle:** the model proposes changes; the trusted engine validates, tests, records and presents them. A passing build is evidence, not proof that software is correct.

## Status

Early development. The deterministic build-and-diagnose layer works; the AI repair loop and the CLI do not exist yet.

| Piece | State |
|---|---|
| Run `mvn -B test` safely (timeout, process-tree kill, bounded output, log file) | Done, tested |
| Distinguish outcomes: `PASS`, `FAIL`, `TIMEOUT`, `START_ERROR`, `CANCELLED`, `INFRA_ERROR` | Done |
| Parse Maven output into compile errors and test failures | Done, tested against real Windows output |
| Command-line interface | Not started |
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

## Using the library

There is no CLI yet. The engine can be called from Java:

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

The full Maven output is written to `target/compileloop/build.log` inside the project being built.

## Design notes

- **No shell strings.** Processes are started from an argument list, never through a shell.
- **Output is drained concurrently**, so a chatty build cannot block on a full pipe, and in-memory output is capped (the full log goes to disk).
- **The whole process tree is killed on timeout**, not just the parent.
- **Infrastructure failures are never confused with broken source code.** A missing `mvn` is `START_ERROR`, not `FAIL`.
- **The parser degrades quietly.** Unrecognised output yields "nothing found" rather than an exception.

## Roadmap

1. Verified Java repair: build, diagnose, propose, patch in a copy, re-verify, report.
2. Self-repair: use the same loop on CompileLoop's own failing tests.
3. Multi-language migration through adapters, staged and behind human approval.

AI-generated patches are proposals. They are never applied silently to your working tree.

## Project layout

```
src/main/java/dev/compileloop/build/
  BuildRunner.java        runs Maven with timeout, draining and kill-tree
  BuildResult.java        outcome, exit code, log path, output tail, parsed diagnostics
  BuildOutcome.java       PASS / FAIL / TIMEOUT / START_ERROR / CANCELLED / INFRA_ERROR
  BuildOutputParser.java  Maven text -> compile errors, test failures, summary
  ParsedBuild.java        the structured result of parsing
src/test/resources/maven/ real Maven output captured on Windows, used as parser fixtures
```

## License

Not yet chosen. Add one before accepting contributions.
