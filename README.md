# Burp Workbench

Burp Workbench is a single-jar Burp Suite extension that groups workflow modules around a shared core.

Current version: `0.4.7`

## Modules

- `Extractor`: exports selected Burp HTTP responses to local files with manifest, index, summary, duplicate handling, and optional JS/JSON beautify before saving.
- `Search++`: provides a tabbed advanced search window for Burp HTTP messages and can pass selected results to Extractor.
- `replace ++`: edits ordered, project-persisted rules for Proxy request/response replacement, with Korean text, URL/Path scope, configurable hotkeys, and native Burp message previews.

## Supported Burp versions

Burp Workbench `0.4.7` supports Burp Suite versions from `2025.12`
through `2026.7.1`, inclusive.

| Burp Suite version | Proxy compatibility mode | Support |
|---|---|---|
| `2025.12` through `2026.7.1` | `HISTORY_ID` | Supported target range |
| Earlier than `2025.12` | — | Not supported |
| Later than `2026.7.1` | Runtime capability detection | Outside the supported range of `0.4.7` |

Every supported version uses the same distribution jar. Compatibility mode
selection is automatic and does not require a user setting. The extension is
compiled against Montoya API `2025.12`; Montoya remains a `provided` dependency
and is not bundled in the jar.

This support range is not a claim that every intervening Burp build has been
exercised in the UI. The `replace ++` sub-tab uses Burp's internal Swing layout;
a layout change can prevent that module from mounting even when Montoya remains
compatible. Traffic replacement starts only after its tab is mounted. Versions
later than `2026.7.1` require separate compatibility verification.

## Current behavior

- Search++ scans one partition at a time, serializes source scans across its windows, supports responsive cancellation, and isolates malformed items and timed-out regular expressions.
- Extractor processes response bodies sequentially, streams decoded bodies through temporary files, and skips Beautify over its memory budget while still saving the decoded original.
- Extension unload cancels active work, closes owned windows, and releases registrations and executors through one lifecycle.

Multiple Search++ windows may stay open, but only one extension-wide source scan runs at a time. Results are not capped automatically. A regular expression that exceeds two seconds for one item skips that item and marks the run `incomplete`.

Korean text search remains supported. A declared response charset is used strictly and exclusively. When no charset is declared, Search++ tries UTF-8, MS949, EUC-KR, and ISO-8859-1 in that order. Literal non-ASCII search is decoded incrementally instead of copying an entire body into a Java `String`.

Extractor defaults to a 512 MiB decoded-body safety limit and an 8 MiB Beautify memory budget. They can be adjusted with the Burp JVM system properties `burpworkbench.extractor.maxDecodedBytes` and `burpworkbench.extractor.beautifyMemoryBudgetBytes`.

The memory reduction has explicit trade-offs: Target/Proxy keep the existing 32-pass partition policy, Extractor uses more temporary-disk I/O, and unlimited retained results can still consume disk space and lightweight metadata. Cancelling waits for the active Burp API call to return before another scan can start.

## replace ++

Open `Proxy > replace ++`, immediately to the right of the built-in `Match and replace` tab. Rules are edited inline with Add, Copy, Remove, Up, and Down. The list/detail split, Match and Replace fields, and Test editors are resizable; detail sections can be collapsed.

- Rule types: request header, request body, response header, response body, request parameter name, request parameter value, and request first line. Parameter rules cover URL query and UTF-8 form-urlencoded fields, not JSON properties, multipart, or cookies.
- URL/origin matches an HTTP(S) origin exactly, including its effective port. Path matches the raw path without query/fragment: `*` does not cross `/`, `**` includes subpaths, and `/api/**` also matches `/api`. A blank field does not restrict that condition; blank URL and Path, or blank URL with Path `**`, match every origin/path.
- Enabled rules run in list order on Proxy messages immediately before forwarding. New rules start off. Editing scope or replacement behavior switches the rule off until explicitly re-enabled; editing only the comment does not. Copy retains the source rule's enabled state. These rules are separate from Burp's built-in Match and replace rules.
- `Hotkeys` assigns, changes, or clears the extension shortcut; the default is `Ctrl+Shift+9`. With a selected HTTP message, the shortcut opens the tab and creates a new off rule populated with origin and Path. Without a usable message, it only opens the tab. Shortcut changes last for the current extension load and are not OS-wide hotkeys.
- Test uses Burp's native request/response editors, font settings, and syntax highlighting. It previews only the selected rule's Type/Match/Replace, independently of its On state and URL/Path scope; it does not send traffic.
- Rules, ordering, individual On states, and the overall Enabled state are saved automatically in the current project's extension data. Test samples and shortcut assignments are not saved. Persistence across Burp restarts requires a saved project; temporary projects may not retain it. Invalid or externally changed stored data is not silently overwritten.

Text-body replacement uses the declared charset, or UTF-8 when none is declared. It supports gzip/deflate bodies; a changed Brotli body is emitted uncompressed. Unsupported or invalid body encodings are skipped rather than decoded lossily. Proxy messages/output are bounded to 8 MiB, decoded text to 16 MiB, and headers to 256 KiB. Processing also has per-rule and per-message time budgets. Invalid or over-budget rules are skipped without committing that rule's partial edit; successful earlier edits can remain. These are safety limits, not an unlimited binary-replacement facility.

On first use, when no rules have been saved, six header-removal presets are provided. Each uses `Request header`, Regex, an empty replacement, and starts **OFF**, with no URL/Path restriction. Narrow its scope and enable only the rules you need.

| Preset | Match |
|---|---|
| Remove If-Modified-Since | `(?im)^If-Modified-Since.*$` |
| Remove If-None-Match | `(?im)^If-None-Match.*$` |
| Remove Sec-CH headers | `(?im)(s|S)ec-(c|C)h.*` |
| Remove Sec-Fetch headers | `(?im)(s|S)ec-(f|F)etch.*` |
| Remove Cache-Control (optional) | `(?im)^Cache-Control:.*$` |
| Remove Pragma (optional) | `(?im)^Pragma:.*$` |

The presets' `(?im)` flags ignore case and anchor `^`/`$` to individual header lines. Existing user rules retain their regex behavior. Removing cache directives or browser metadata can change caching and server checks. Cookie, Authorization, Origin, and Referer are not removed by these presets. Existing saved rules, including an intentionally empty list, are restored without appending defaults or recreating deleted rules.

## Install

Build the project and load this shaded jar in Burp Suite:

```text
target\burp-workbench-extension-0.4.7.jar
```

This single shaded jar is the distribution for every supported Burp version. Do not install `target\original-burp-workbench-extension-0.4.7.jar`; that file is Maven's unshaded backup and does not include bundled runtime dependencies such as Brotli/Rhino.

## Build

Requirements:

- JDK 17 or newer
- Maven
- Network access for the first dependency resolution
- Burp Suite `2025.12` through `2026.7.1`

```powershell
mvn package
```

Expected build outputs:

```text
target\burp-workbench-extension-0.4.7.jar
target\original-burp-workbench-extension-0.4.7.jar
```

Only `target\burp-workbench-extension-0.4.7.jar` is a loadable distribution artifact.

## Architecture

This project intentionally stays as one Maven project and one jar. Java packages provide module boundaries:

- `com.burpworkbench.platform`: module lifecycle and cross-module contracts.
- `com.burpworkbench.core`: genuinely shared selection and MIME policies.
- `com.burpworkbench.modules.extractor`: Extractor module implementation.
- `com.burpworkbench.modules.search`: Search++ module implementation.
- `com.burpworkbench.modules.replace`: replace ++ module implementation.

See `docs/architecture.md` for dependency rules and extension points.
