# crap4kotlin

CRAP metric for Kotlin: cyclomatic complexity combined with JaCoCo coverage. A port of the crap4 family core (`crap4js`, `crap4dart`). One `analyze` pass over Kotlin sources, gated at a CRAP threshold.

## The CRAP formula

```
CRAP = CC^2 * (1 - coverage)^3 + CC
```

where `CC` is the cyclomatic complexity of the function and `coverage` is its line coverage as a fraction in `[0, 1]`. Unknown coverage (no JaCoCo record for the function) yields `N/A`: the row is printed last and excluded from the maximum — coverage is required, a CRAP score without coverage is meaningless.

The report is a table `Method | File | CC | Cov% | CRAP`, sorted by CRAP descending (N/A last, ties by file then line), followed by `Max CRAP: <x> (threshold <t>) — FAILED|passed`.

## Install

Gradle 9.5, Kotlin JVM plugin, JDK 17:

```bash
./gradlew installDist          # binary at build/install/crap4kotlin/bin/crap4kotlin
```

or run ad-hoc:

```bash
./gradlew installDist && build/install/crap4kotlin/bin/crap4kotlin src/main/kotlin
```

## CLI usage

```
crap4kotlin [<path>...] [--changed] [--coverage <path>] [--threshold <num>] [--exclude <glob>]... [--strict] [--diff-base <ref>] [--help]
```

- paths default to `src/main/kotlin`; directories are walked for `.kt` files, files are taken as-is
- `--coverage <path>` overrides the coverage file (default `build/reports/jacoco/test/jacocoTestReport.xml`, JaCoCo XML only)
- `--threshold <num>` overrides the CRAP threshold (default `8.0`)
- `--changed` analyzes the git-changed `.kt` files under `src/main/kotlin` (status M/A/R/C/?)
- `--diff-base <ref>` keeps only the functions whose line range intersects the lines added by `git diff --unified=0 <ref>`; mutually exclusive with `--changed`
- `--exclude <glob>` skips files whose project-relative path matches the glob (`**/` crosses segments, `*` and `?` stay in one segment); repeatable
- `--strict` counts a selected file with no coverage entry as 0.0% covered (instead of N/A) and requires explicit paths to exist

Methods counted: every `fun` with a body, primary and secondary constructors and `init` blocks (named `Type.init`), and property accessors `get`/`set` with a body. Lambdas count toward the enclosing method; a local `fun` is its own entry.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | pass |
| 1 | usage error, or a missing/empty/unparseable/record-free coverage file |
| 2 | maximum CRAP above the threshold |

## Coverage format

JaCoCo XML only (`build/reports/jacoco/test/jacocoTestReport.xml`). A `<method>` matches a source method when the class name and the `line` attribute are equal (JaCoCo reports the first body line). The `LINE` counter gives `covered / (covered + missed)`. Compiler-generated methods (name containing `$`) are skipped. A method without a match is an `N/A` row.

Generate the file with:

```bash
./gradlew test jacocoTestReport
```

Note: code that executes only under Robolectric is not seen by the default JaCoCo setup, so such functions show up as `N/A` rows (the Android lane observed this) — the vacuous-gate guard then fails the run instead of passing on zero matched rows.

## Complexity rules

Base 1; +1 for each `if` (also `else if`), `for`, `while` (loop head), `do`, `catch`, each `->` arm of a `when` body (also `else ->`), and each `&&`, `||`, `?:`. The `while` that terminates a `do` block does not count; `->` inside a lambda nested in a `when` body belongs to the lambda.

## Development

```bash
./gradlew test jacocoTestReport installDist
build/install/crap4kotlin/bin/crap4kotlin src/main/kotlin --coverage build/reports/jacoco/test/jacocoTestReport.xml --threshold 8.0
```

Quality gate: the `Quality` workflow runs both commands on every push and pull request; the coverage-file step fails the job when the JaCoCo XML is missing or empty. The tool gates its own repository (self-hosting).

## License

MIT — see [LICENSE](LICENSE).
