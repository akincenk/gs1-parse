# Roadmap

Planned work, about 30 hours in total. Each item is a GitHub issue with the `roadmap` label;
the number in front is the issue number. Estimates are rough.

## Correctness

1. **Evaluate association rules (3 h).** The dictionary lists `req=` (mandatory pairings, for
   example (02) needs (37)) and `ex=` (invalid pairings, for example (01) with (02)). Parse the
   patterns (`01+21`, `31nn`, `394n`), evaluate them over all elements, report errors. Option to
   turn it off for callers that combine several barcodes on one item.
2. **Warn about a probable missing separator (2 h).** When a variable-length value ends with
   digits that would parse as one or more valid elements (`10AB1215270115` = batch `AB12` +
   best-before 2027-01-15), add a warning that names the AIs it may contain. Found while testing
   the receiving dock example. **Done:** only when the first swallowed AI has a date or check
   digit test; random batch numbers matched (20) and (31nn) too often without that rule.
3. **Hint for substitute separator characters (1 h).** Some keyboard-wedge scanners send `^`,
   `~` or a text marker like `{GS}` instead of ASCII 29. When a character-set error hits one of
   these, say so in the message, and let `ParseOptions` take a multi-character marker.
4. **ISO 3166 numeric country codes (1.5 h).** Implement the `iso3166` and `iso3166999` linters
   with a bundled code list and a test that the list matches the alpha-2 list from the JDK.
5. **Remaining linters (4 h).** `csumalpha`, `pieceoftotal`, `hyphen`, `latitude`, `longitude`,
   `iban`, `mediatype`, `packagetype`, `importeridx`, `posinseqslash`, `nozeroprefix`,
   `hasnondigit`, `couponcode`, `couponposoffer` and the rest, each checked against the GS1
   reference linter behaviour, with tests.

## Features

6. **GS1 Digital Link URIs (3.5 h).** Parse `https://example.com/01/09506000134352/10/AB12?17=280531`
   into the same result, using the `dlpkey` qualifier order from the dictionary and percent
   decoding.
7. **Encoder (2.5 h).** Build element strings from a list of (AI, value) pairs: raw form with GS
   only where needed, bracketed form, and a human readable line. Round-trip tests with the parser.
8. **GTIN helpers (1.5 h).** Normalize GTIN-8/12/13 to GTIN-14, compute check digits, read the
   indicator digit and detect variable measure items (indicator 9).
9. **Typed values for multi-part AIs (2 h).** Date ranges (7007), temperatures with sign
   (4330-4333), date and time with optional minutes and seconds (8008), postal code with country
   (421), piece of total (8026).
10. **CSV output in the CLI (1.5 h).** `--csv` writes one row per element (scan number, AI,
    title, raw, typed value, errors) for scan logs that go into a spreadsheet.

## Quality and release

11. **Property-based tests (2 h).** Generate random valid element strings from the dictionary,
    encode, parse, compare. Feed random bytes and assert the parser never throws.
12. **Dictionary update script (1.5 h).** Script that downloads a given upstream commit,
    records it in the README, and runs the tests; a scheduled job (at most once a day) that opens
    an issue when upstream has a newer release.
13. **Publish the library (2 h).** Maven coordinates (JitPack or Maven Central), KDoc on the
    public API, Dokka HTML docs, a changelog.
14. **Native CLI build (2 h).** GraalVM native-image build of the CLI for Linux and macOS in a
    release workflow, with checksums attached to the GitHub release.
