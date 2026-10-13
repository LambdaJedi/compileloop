# Known limitations

## Model behavior

### Small models can misdescribe their own fix

Observed with `qwen2.5-coder:7b` against a fixture where `public int add(int a, int b)`
had a body of `return "x";`. The compiler diagnostic read:

    incompatible types: java.lang.String cannot be converted to int

The model's *summary* claimed the fix was to change the method's **return type** to
`int`, even though the return type was already `int`. What the model's **diff** actually
did was change the **body** to `return a + b;` — which is correct.

So on the same attempt: summary wrong, diff right. This is a known behavior of small
code models. They anchor on the word "types" in the diagnostic and describe a type
change without re-reading the method signature in the source excerpt.

**Not a CompileLoop bug.** A larger code model reads the method signature and describes
the fix accurately. Benchmarking the same fixture across models is exactly what
blueprint §13 asks for — see `docs/benchmark-methodology.md`.

**Implication for users.** The summary is a claim by the model. The diff is what gets
validated and applied. Read the diff, not the summary.

## Parser policy

### Hunk header counts are advisory

`UnifiedDiff` follows `git apply --recount`: when a hunk header's declared counts
disagree with the body's actual ' '/'-'/'+' markers, the body wins. This was necessary
in practice — models routinely write `@@ -5,7 +5,7 @@` for a 3-line body. What is still
rejected, unchanged: malformed headers, missing file headers, unknown body prefixes,
empty hunks, NUL bytes, binary patches.

## Platform notes

### Symlink tests are skipped on Windows without developer mode

`PatchValidatorTest` and `WorkspaceTest` include tests that create symlinks to verify
the escape checks. Windows only allows this with Developer Mode enabled or an
elevated process; otherwise the tests are skipped via JUnit assumption, not failed.
The Linux CI job exercises them.
