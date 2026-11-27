# Changelog

## 0.1.0

- CRAP core for Kotlin: complexity scanner (own tokenizer: comments, strings, char literals, back-tick names), JaCoCo XML coverage input, text report, CLI with `--changed`, `--coverage`, `--threshold`, `--exclude`, `--strict`, `--diff-base`.
- Kotlin JVM plugin `2.4.21` — the newest 2.x release that Gradle 9.5.0 supports (verified against the Gradle 9.5.0 wrapper).
- JVM target 17, `useJUnitPlatform()`, `jacocoTestReport` with the XML report required, `installDist` for the self-hosting run.
- Self-hosting `Quality` workflow: tests + coverage + CRAP gate at threshold 8.0 on `src/main/kotlin`.
