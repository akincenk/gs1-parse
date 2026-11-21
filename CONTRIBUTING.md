# Contributing

Bug reports with a failing scan are the most useful thing. Please include the exact data
(use `cat -v` or the `--json` output so the GS characters are visible), what you expected, and
what gs1-parse printed. Do not post real serial numbers or anything that identifies a patient
or customer; replace them with made-up values of the same length.

## Working on the code

```sh
./gradlew test installDist
build/install/gs1-parse/bin/gs1-parse --today 2026-11-21 < examples/receiving-dock-scans.txt
```

- Every change to parsing or checks comes with a test in `src/test/kotlin`. Tests pass a fixed
  `today` so two-digit years do not drift.
- AI lengths, character sets and linter names come from the bundled GS1 Barcode Syntax
  Dictionary. Do not hard-code AI tables; if the dictionary is wrong, report it upstream at
  https://github.com/gs1/gs1-syntax-dictionary.
- Check rules against the GS1 General Specifications or the GS1 reference linters, and link the
  section in the pull request.
- Keep the library free of runtime dependencies.
- One topic per pull request. CI must be green (tests on Java 17 and 21, gitleaks, zizmor).

Open work is listed in [ROADMAP.md](ROADMAP.md) and as GitHub issues with the `roadmap` label.
