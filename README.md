# gs1-parse

[![CI](https://github.com/akincenk/gs1-parse/actions/workflows/ci.yml/badge.svg)](https://github.com/akincenk/gs1-parse/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/akincenk/gs1-parse/badge)](https://scorecard.dev/viewer/?uri=github.com/akincenk/gs1-parse)

Kotlin library and command line tool that reads GS1 barcode data (GS1-128, GS1 DataMatrix,
GS1 QR Code, GS1 DataBar) and turns it into checked, typed values: GTINs and SSCCs with their
check digit verified, dates with the right century, weights and prices with the decimal point
in place, counts as numbers.

```
$ gs1-parse "(01)09506000134352(17)280200(3103)001250(10)AB12"
scan: (01)09506000134352(17)280200(3103)001250(10)AB12
  (01) GTIN: 09506000134352
  (17) USE BY or EXPIRY: 2028-02-29 (no day given, end of month)
  (3103) NET WEIGHT (kg): 1.250 kg
  (10) BATCH/LOT: AB12
  valid
```

## Who it is for

Developers who write receiving, picking, inventory or pharmacy scanning software on the JVM and
need to read what a scanner sends from a case label or a DataMatrix code, without native code.
It also helps when you are debugging a scanner setup: the CLI shows exactly where a scan breaks
(missing separator, bad check digit, impossible date).

## What it does

- Reads raw scanner output: optional symbology identifier (`]C1`, `]d2`, `]Q3`, `]e0`, `]J1`),
  element strings separated by the GS character (ASCII 29, what scanners send for FNC1).
- Reads the bracketed form printed under barcodes, `(01)09506000134352(10)AB12`. A literal `(`
  inside a value is written `\(`.
- Knows every Application Identifier in the official
  [GS1 Barcode Syntax Dictionary](https://github.com/gs1/gs1-syntax-dictionary), which is bundled
  as a resource. Lengths, character sets and optional parts come from that file, not from a
  hand-made table.
- Checks: GS1 mod-10 check digits, dates (`YYMMDD`, day `00` where allowed), times, ISO 4217
  currency codes, ISO 3166 alpha-2 country codes, yes/no and non-zero flags, maximum lengths,
  character sets 82 and 39, the same AI twice with different values.
- Checks the pairing rules from the dictionary: (02) needs (37), (10) needs a GTIN, (01) must
  not appear with (02), and so on. GS1 applies these to all barcodes on an item together, so
  when you parse one barcode of several, turn them off with `ParseOptions(checkAssociations = false)`
  or `--no-associations`.
- Typed values: `Date` (century from the GS1 sliding window rule), `DateTime`, `Decimal` with
  unit for (31nn)-(36nn) measures and (39nn) amounts and prices, `Money` with currency for (391n)
  and (393n), `Count` for (30) and (37), `Text` for everything else.
- Warns when a fixed-length AI that is not in the predefined-length table, such as (422), is not
  followed by a separator.
- Never throws on bad input. Every problem comes back with its position in the scan.

Not done yet, and listed per element as `notChecked` so nothing passes silently: ISO 3166
numeric country codes and the less common linters (`csumalpha`, `pieceoftotal`, `latitude`,
`iban`, ...). Company prefix position checks (`gcppos1`, `gcppos2`) need GS1's prefix allocation
data and are skipped. See [ROADMAP.md](ROADMAP.md).

One limit no parser can fix: when a scanner drops the separator after a variable-length field,
the next AI becomes part of that field. `10AB12` followed by `15270115` without GS reads as batch
`AB1215270115`. The data is still valid, so it is kept as scanned, but a warning says
`(10) may contain (15) 270115; was a separator lost?`. The warning only fires when the end of the
value parses as complete elements and the first of them passes a date or check digit test, so
a lost separator before (21), (20) or a measure such as (3103) is not reported.

## Stack

Kotlin 2.4 on the JVM (Java 17 or newer), Gradle 9 with the wrapper, kotlin.test with JUnit 5.
No runtime dependencies besides the Kotlin standard library.

## How to run

Needs a JDK 17 or newer.

```sh
./gradlew test          # run the tests
./gradlew installDist   # build the CLI into build/install/gs1-parse
build/install/gs1-parse/bin/gs1-parse --help
```

Parse one or more scans, or pipe a scan log, one scan per line:

```sh
gs1-parse "]C10109506000134352" "(00)106141411234567897"
gs1-parse --today 2026-11-21 < examples/receiving-dock-scans.txt
gs1-parse --json "(01)09506000134352(3912)1241999"
gs1-parse --sep '^' '010950600013435210AB12^17280531'  # scanner sends ^ for FNC1
```

Exit status is 0 when every scan is valid, 1 when at least one is not, 2 for usage errors.

As a library (not published to a repository yet, see the roadmap; build the jar with
`./gradlew jar` for now):

```kotlin
val r = Gs1.parse("]d2" + "0109506000134352" + "17280531" + "10B7731" + Gs1.GS + "21SN000183")
if (r.isValid) {
    val expiry = (r["17"]?.value as Gs1Value.Date).date   // 2028-05-31
    val serial = r["21"]?.raw                              // SN000183
} else {
    r.errors.forEach { println("${it.position}: ${it.message}") }
}
```

`ParseOptions(today = ...)` fixes the reference date for two-digit years, which keeps tests stable.

## Similar projects

- [gs1/gs1-syntax-engine](https://github.com/gs1/gs1-syntax-engine): GS1's own reference
  implementation in C, with Java (JNI), JavaScript/WASM and Swift bindings. It implements every
  linter and also encodes. Use it if you need complete validation and can ship native code.
  gs1-parse is plain Kotlin and returns typed values instead of strings.
- [PeterBrockfeld/BarcodeParser](https://github.com/PeterBrockfeld/BarcodeParser) (JavaScript),
  [xremix/SwiftGS1Barcode](https://github.com/xremix/SwiftGS1Barcode) (Swift),
  [lamoda/gs1-barcode-parser](https://github.com/lamoda/gs1-barcode-parser) (PHP): parsers with
  hand-written AI tables for other languages.

## Bundled data

`src/main/resources/io/github/akincenk/gs1/gs1-syntax-dictionary.txt` is an unmodified copy of
the GS1 Barcode Syntax Dictionary from
[gs1/gs1-syntax-dictionary](https://github.com/gs1/gs1-syntax-dictionary) at commit `c63d8a1`,
Copyright BWIPP project, Zint project and GS1 AISBL, licensed under the Apache License 2.0
(copy next to the file). GS1 is a registered trademark of GS1 AISBL; this project is not
affiliated with GS1.

## License

MIT, see [LICENSE](LICENSE). The bundled dictionary keeps its Apache 2.0 license.
