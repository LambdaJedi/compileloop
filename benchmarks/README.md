# CompileLoop Benchmark Results

## Overview

CompileLoop was evaluated against 20 benchmark fixtures using three local code models. The fixtures cover syntax errors, type mismatches, symbol errors, test failures, and multi-file issues.

All models were run locally through Ollama. Results reflect this benchmark setup and should not be interpreted as a general ranking of coding models.

## Results

| Model | Repairs | Repair Rate | Compile Recovery | Test Recovery | Mean Latency |
|---|---:|---:|---:|---:|---:|
| qwen2.5-coder:7b | 9/20 | 45% | 6/15 (40%) | 3/5 (60%) | 29s |
| qwen2.5-coder:1.5b | 4/20 | 20% | 4/15 (27%) | 0/5 (0%) | 12s |
| deepseek-coder:6.7b | 1/20 | 5% | 0/15 (0%) | 1/5 (20%) | 39s |

Across the three models, 60 benchmark runs were performed.

## Stop Conditions and Failure Analysis

Stop conditions were triggered 27 times:

- `NO_PROGRESS`: 21
- `AI_UNAVAILABLE`: 6

Other runs ended with a verified candidate, `NOT_FIXED`, or an infrastructure error.

### Model-specific observation

With `deepseek-coder:6.7b`, the model sometimes identified the likely fix in its explanation but generated a unified diff whose context did not match the source. `PatchApplier` rejected these patches with `DID_NOT_APPLY`.

This illustrates that identifying a plausible fix is not sufficient: the generated patch must also apply correctly and pass verification.

## Benchmark Fixtures

| Fixture IDs | Category | Examples |
|---|---|---|
| 01–05 | Syntax errors | Missing semicolon, unclosed brace, bad generics, illegal token, missing return |
| 06–10 | Type mismatches | String used as int, wrong argument type, double used as int, invalid operand, boolean used as int |
| 11–13 | Symbol errors | Undefined variable, incorrect method name, missing import |
| 14–17 | Test failures | Off-by-one error, wrong expected value, incorrect string result, boundary condition |
| 18–20 | Multi-file issues | Call-site mismatch, interface override mismatch, helper bug |

## Reproducing the Benchmarks

Install Ollama, then pull the models you want to test:

```bash
ollama pull qwen2.5-coder:7b
ollama pull qwen2.5-coder:1.5b
ollama pull deepseek-coder:6.7b
```

Build the project:

```bash
mvn -B -DskipTests package
```

Run all configured models:

```bash
benchmarks/run.sh
```

Run one model:

```bash
benchmarks/run.sh qwen2.5-coder:7b
```

Customize attempts and AI timeout:

```bash
ATTEMPTS=5 AI_TIMEOUT=600 benchmarks/run.sh
```

The runner skips existing result files so a benchmark run can be resumed.

## Results and Reproducibility

Detailed outputs are stored under `benchmarks/results/`. Each run's `repair.json` records configuration and a `startedAt` timestamp. Check these files when comparing runs or documenting the exact model versions and configuration used.

## Limitations

- Results cover the 20 fixtures in this benchmark suite and do not establish performance on all real-world repositories.
- Latency and repair success depend on the local hardware, model version, Ollama setup, and benchmark configuration.
- Regression rate is not reported because regression detection was not measured in these runs.
- A generated candidate is not automatically a correct repair. Review the patch and its verification evidence before accepting it.
