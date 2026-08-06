# Burp Workbench

Burp Workbench is a single-jar Burp Suite extension that groups workflow modules around a shared core.

Current version: `0.4.6`

## Modules

- `Extractor`: exports selected Burp HTTP responses to local files with manifest, index, summary, duplicate handling, and optional JS/JSON beautify before saving.
- `Search++`: provides a tabbed advanced search window for Burp HTTP messages and can pass selected results to Extractor.

## Supported Burp versions

Burp Workbench `0.4.6` supports Burp Suite versions from `2025.9.3`
through `2026.7.1`, inclusive.

| Burp Suite version | Proxy compatibility mode | Support |
|---|---|---|
| `2025.9.3` | `LEGACY_METADATA` | Supported |
| `2025.10` through `2026.7.1` | `HISTORY_ID` | Supported |
| Earlier than `2025.9.3` | — | Not supported |
| Later than `2026.7.1` | Runtime capability detection | Not yet guaranteed by version `0.4.6` |

Every supported version uses the same distribution jar. Compatibility mode
selection is automatic and does not require a user setting. The extension is
compiled against Montoya API `2025.8`; Montoya remains a `provided` dependency
and is not bundled in the jar.

Versions later than `2026.7.1` may work when their Montoya API remains
compatible, but they are outside the guaranteed range until they are verified.

See `docs/compatibility-0.4.5-verification.md` for the compatibility matrix and
verification status.

## Current behavior

- Search++ scans one partition at a time, serializes source scans across its windows, supports responsive cancellation, and isolates malformed items and timed-out regular expressions.
- Extractor processes response bodies sequentially, streams decoded bodies through temporary files, and skips Beautify over its memory budget while still saving the decoded original.
- Extension unload cancels active work, closes owned windows, and releases registrations and executors through one lifecycle.

Multiple Search++ windows may stay open, but only one extension-wide source scan runs at a time. Results are not capped automatically. A regular expression that exceeds two seconds for one item skips that item and marks the run `incomplete`.

Korean text search remains supported. A declared response charset is used strictly and exclusively. When no charset is declared, Search++ tries UTF-8, MS949, EUC-KR, and ISO-8859-1 in that order. Literal non-ASCII search is decoded incrementally instead of copying an entire body into a Java `String`.

Extractor defaults to a 512 MiB decoded-body safety limit and an 8 MiB Beautify memory budget. They can be adjusted with the Burp JVM system properties `burpworkbench.extractor.maxDecodedBytes` and `burpworkbench.extractor.beautifyMemoryBudgetBytes`.

The memory reduction has explicit trade-offs: Target/Proxy keep the existing 32-pass partition policy, Extractor uses more temporary-disk I/O, and unlimited retained results can still consume disk space and lightweight metadata. Cancelling waits for the active Burp API call to return before another scan can start.

## Install

Build the project and load this shaded jar in Burp Suite:

```text
target\burp-workbench-extension-0.4.6.jar
```

This single shaded jar is the distribution for every supported Burp version. Do not install `target\original-burp-workbench-extension-0.4.6.jar`; that file is Maven's unshaded backup and does not include bundled runtime dependencies such as Brotli/Rhino.

## Build

Requirements:

- JDK 17 or newer
- Maven
- Network access for the first dependency resolution
- Burp Suite `2025.9.3` through `2026.7.1`

```powershell
mvn test
mvn clean package
```

Expected build outputs:

```text
target\burp-workbench-extension-0.4.6.jar
target\original-burp-workbench-extension-0.4.6.jar
```

Only `target\burp-workbench-extension-0.4.6.jar` is a loadable distribution artifact.

## Architecture

This project intentionally stays as one Maven project and one jar. Java packages provide module boundaries:

- `com.burpworkbench.platform`: module lifecycle and cross-module contracts.
- `com.burpworkbench.core`: genuinely shared selection and MIME policies.
- `com.burpworkbench.modules.extractor`: Extractor module implementation.
- `com.burpworkbench.modules.search`: Search++ module implementation.

See `docs/architecture.md` for dependency rules and extension points.
