# Burp Workbench

Burp Workbench is a single-jar Burp Suite extension that groups workflow modules around a shared core.

Current version: `0.5.0`

## Modules

- `Extractor`: exports selected Burp HTTP responses to local files with manifest, index, summary, duplicate handling, optional JS/JSON beautify, and optional rule-based information tagging.
- `Search++`: provides a tabbed advanced search window for Burp HTTP messages and can pass selected results to Extractor.
- `replace ++`: ordered, project-persisted Proxy replacement and Forward rules, URL/Path scope, configurable hotkeys, and direction-sensitive native Burp message previews.
- `compare ++`: compares captured messages or text with independent Pretty/Raw/Hex tabs for A and B, Korean text, difference navigation, and configurable hotkeys.
- `decoder ++`: live Quick/Advanced conversion popups with independent work tabs, selection capture, configurable hotkeys, and Light/Dark themes.

## Supported Burp versions

Burp Workbench `0.5.0` supports Burp Suite versions from `2025.12`
through `2026.7.1`, inclusive.

| Burp Suite version | Proxy compatibility mode | Support |
|---|---|---|
| `2025.12` through `2026.7.1` | `HISTORY_ID` | Supported target range |
| Earlier than `2025.12` | — | Not supported |
| Later than `2026.7.1` | Runtime capability detection | Outside the supported range of `0.5.0` |

Every supported version uses the same distribution jar. Compatibility mode
selection is automatic and does not require a user setting. The extension is
compiled against Montoya API `2025.12`; Montoya remains a `provided` dependency
and is not bundled in the jar.

This support range is not a claim that every intervening Burp build has been
exercised in the UI. The `replace ++` and `compare ++` sub-tabs use Burp's internal Swing layout;
a layout change can prevent that module from mounting even when Montoya remains
compatible. Replace++ and Compare++ fall back to top-level tabs if no unique Proxy anchor is found. Proxy handlers start after Replace++ initializes successfully. Versions
later than `2026.7.1` require separate compatibility verification.

## Current behavior

- Search++ scans one partition at a time, serializes source scans across its windows, supports responsive cancellation, and isolates malformed items and timed-out regular expressions.
- With Filter off, Extractor processes response bodies sequentially, streams decoded bodies through temporary files, and skips Beautify over its memory budget while still saving the decoded original. Filter on is bounded and fails closed rather than saving an unfiltered fallback.
- Extension unload cancels active work, closes owned windows, and releases registrations and executors through one lifecycle.

Multiple Search++ windows may stay open, but only one extension-wide source scan runs at a time. Results are not capped automatically. A regular expression that exceeds two seconds for one item skips that item and marks the run `incomplete`.

Korean text search remains supported. A declared response charset is used strictly and exclusively. When no charset is declared, Search++ tries UTF-8, MS949, EUC-KR, and ISO-8859-1 in that order. Literal non-ASCII search is decoded incrementally instead of copying an entire body into a Java `String`.

Extractor defaults to a 512 MiB decoded-body safety limit and an 8 MiB Beautify memory budget. They can be adjusted with the Burp JVM system properties `burpworkbench.extractor.maxDecodedBytes` and `burpworkbench.extractor.beautifyMemoryBudgetBytes`.

The memory reduction has explicit trade-offs: Target/Proxy keep the existing 32-pass partition policy, Extractor uses more temporary-disk I/O, and unlimited retained results can still consume disk space and lightweight metadata. Cancelling waits for the active Burp API call to return before another scan can start.

## Extractor

The top-level `Extractor` tab creates and orders filter rules; extraction still starts from the HTTP context menu or Search++ results. In the output-folder chooser, `Filter` sits beside `Beautify` and is off by default.

- Filter off preserves normal extraction. Filter on applies the current rules before writing bodies, metadata and filenames; it never falls back to unfiltered content on a filtering failure.
- Automatic detection is deliberately limited to Korean mobile numbers and email addresses. Mobile detection uses formatting/field context and number validation; arbitrary eleven-digit numbers are not sufficient. Other information requires explicit rules. This is not complete anonymization.
- Add exact strings and free tags such as `§COMPANY_1§`. `LITERAL` matches the exact string; `HOST` matches host boundaries, case-insensitively. Scope is `ALL`, `BODY`, or `METADATA`; earlier custom rules take priority. Automatic tags such as `§PHONE_1§` are stable within an export and avoid existing tags.
- Rules, order, automatic-detection choices and unfinished drafts are saved in the Burp project, including original strings entered in rules. Protect that project accordingly. Invalid stored data is not silently reset; Filter on is blocked if its rules cannot be loaded or validated.
- `filter_attributes.json` describes tags, types, evidence and occurrence counts without original sensitive values. Tagged JSON scalars may become strings. Content-Type guides JSON/HTML/XML/CSV/form/text parsing; Filter has an 8 MiB input/decoded-body limit and rejects unsupported or invalid data instead of lossy conversion.
- `Test file…` compares a local original and filtered result without sending requests. Review filtered outputs before sharing; unconfigured categories can remain.

## Workbench context menus

Owned text inputs offer Cut/Copy/Paste/Select All and applicable Send to Replace/Compare/Decoder actions. Rule tables expose their existing row operations on right-click. Burp's native editor menus are retained; selection and read-only state determine which actions are available.

## replace ++

Open `Proxy > replace ++`, immediately to the right of the built-in `Match and replace` tab. Its Replace and Forward pages have separate Enabled settings. Rules are edited inline with Add, Copy, Remove, Up, and Down. Replace starts with equal list/detail and preview widths and compact Match/Replace fields; splitters remain resizable and detail sections can be collapsed.

- Rule types: request header, request body, response header, response body, request parameter name, request parameter value, and request first line. Parameter rules cover URL query and UTF-8 form-urlencoded fields, not JSON properties, multipart, or cookies.
- URL/origin matches an HTTP(S) origin exactly, including its effective port. Path matches the raw path without query/fragment: `*` does not cross `/`, `**` includes subpaths, and `/api/**` also matches `/api`. A blank field does not restrict that condition; blank URL and Path, or blank URL with Path `**`, match every origin/path.
- Enabled rules run in list order on Proxy messages immediately before forwarding. New rules start off. Editing scope or replacement behavior switches the rule off until explicitly re-enabled; editing only the comment does not. Copy retains the source rule's enabled state. These rules are separate from Burp's built-in Match and replace rules.
- `Hotkeys` assigns, changes, or clears the extension shortcut; the default is `Ctrl+Shift+Q`. With a selected HTTP message, the shortcut opens the tab and creates a new off rule populated with origin and Path. Without a usable message, it only opens the tab. Shortcut changes last for the current extension load and are not OS-wide hotkeys.
- The context menu `Extensions > Burp Workbench > Send to replace ++` also opens Replace++ with a new off rule. It takes origin/Path from the current editor's request, or the first selected request in a table. The focused Request or Response is copied into Test Preview in full (up to 1 MiB), regardless of text selection; no traffic is sent. The item is disabled when no usable request scope is available.
- Test uses Burp's native request/response editors, font settings, and syntax highlighting. It previews only the selected rule's Type/Match/Replace, independently of its On state and URL/Path scope; it does not send traffic. Computation runs off the UI thread and superseded results are discarded.
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

### Forward

The Forward page routes Proxy requests to another server after Workbench replacement. It does not redirect the browser or send an additional request. Source URLs can remain visible in the address bar and Proxy history even though the response comes from the destination.

- Inputs are `Source URL | Source Path` and `Destination URL | Destination Path`. Source conditions use the same origin/path matching as Replace. Destination URL is an HTTP(S) origin, optionally with a port; the path belongs in Destination Path.
- With both paths blank, the actual matching request path is kept. A blank Destination Path keeps that path; a supplied Destination Path replaces it. Blank Source Path matches all paths. Raw query strings are preserved.
- Enabled rules are evaluated in list order against the original source URL; the last valid match wins. Forward always runs after Workbench Replace, changing the connection service and Host header. This ordering does not control other Burp extensions.
- Forward has separate project-persisted rules and Enable state. First use starts empty and OFF; new rules start OFF. Test only computes the destination URL and does not send traffic.

### Upgrade to 0.5.0

Existing 0.4.9 Replace rules, ordering and enable states are retained. Independent demo rules are not migrated. New Forward settings start empty/OFF; existing product Decoder shortcut preferences remain valid. Unload old extensions or independent demos before loading the new jar to avoid duplicate registrations.

## compare ++

Open `Proxy > compare ++`, immediately to the right of `replace ++`. Paste or load text, or send a focused HTTP message from its context menu or the configurable `Ctrl+Shift+W` shortcut. Request/Response is taken from the focused editor; a selected byte range is imported as Text. History/Site map table hotkey capture is not supported. The first two imports become A/B automatically; further imports do not replace the current pair. Selecting either side updates the comparison in the same tab.

- Words, Unicode Characters, and exact Bytes comparison; independent Pretty/Raw/Hex tabs with Auto/UTF-8/MS949/ISO-8859-1 decoding. Raw/Hex preserve original bytes; Hex uses a fixed-width font for aligned byte and ASCII columns.
- Pretty formats disposable JS/JSON copies. Mixed Pretty/Raw or Pretty/Hex includes formatting differences; both Pretty can hide whitespace-only differences. Positions refer to each side's representation (formatted UTF-8 for Pretty). HTTP headers still describe the original body. Unsupported/lossy content falls back to Raw on that side.
- Pretty/Raw use the themed text-field default font (no added Bold), syntax colors and Modified/Deleted/Added backgrounds. This is the Workbench renderer, not Burp's native Pretty/Inspector. Captured bytes and Repeater request snapshots are never rewritten.
- Incoming captures mark the Compare tab orange until selected, without blinking or switching away from the current screen. Compare/Replace shortcuts also work in owned Workbench windows; hotkey settings fields are excluded.
- Previous/next difference buttons and `Alt+Up`/`Alt+Down` move both panes. `Find` is independent literal-text search in the last focused pane: Enter/Shift+Enter or its own arrows move text matches. An empty Find does nothing and disables its arrows. `Ctrl+F` focuses Find.
- `Differences only` hides equal segments without changing the captured bytes. Sync scroll, Wrap, resizable panes, item sorting, Remove/Clear, and cancellation are available.
- `Ctrl+R` in either pane adds that item's original request to Repeater without sending it. Response/selection imports retain the original request and service when available. Paste/Load text has no original service and is not sent to an inferred destination.
- The collection and shortcut assignments last for the current extension load; they are not project-persisted. Captured items are copied, capped at 1 MiB each and 200 items / 32 MiB total, including retained original requests. Requests over 1 MiB are not retained for Repeater.

Comparison runs off the UI thread with exact small-edit and linear-space large-edit paths. A four-second diff calculation budget and cancellation checks prevent unbounded work; decoding and rendering are outside that calculation budget. If comparison cannot complete, the original data remains visible with a clear failure notice instead of an empty result or a false "no differences" result. This module does not register traffic handlers or change Replace++ rules.

## decoder ++

Open from an HTTP editor context menu: `Extensions > Burp Workbench > decode ++`. No top-level Decoder tab is added. Quick/Advanced default to `Ctrl+1` / `Ctrl+2`, configurable in `Hotkeys`. Saved assignments and explicit Clear settings take precedence. Each activation creates a new work tab in that popup. Only selected text is imported; no selection opens blank input. Shortcuts work while Burp or Workbench has focus, not as OS-global shortcuts.

- Quick: URL, Base, Hex, HTML, Unicode, Hash. Advanced: eight live representations. Copy, Swap, Quick-to-Advanced transfer, Unicode prefixes, percent-prefixed Hex, seven Base variants and twelve hashes are supported.
- Input/results use the themed text-field default font without Bold; labels are bold. Decoder starts in Dark mode. The toggle right of Hotkeys selects Light/Dark independently of Burp. Quick targets920×680, remains resizable, and adjusts for font/screen constraints.
- Tabs retain independent inputs/options. × or Ctrl/Cmd+W closes a tab; + opens a blank tab. Quick/Advanced have separate session windows; closing tabs/unloading cancels their workers.
- Only shortcuts persist in Burp preferences, not input/results. Explicit Clear stays disabled across reloads; demo preferences are not imported.
- Advanced Wrap has Auto/On/Off controls. Auto disables wrapping for a logical line longer than 8,192 UTF-16 code units; it never truncates data or inserts newlines. Large edits are debounced and conversion retains only the newest queued work.
- Conversions are local and do not send traffic. Input limit: 1,048,576 UTF-16 code units. Invalid/unsupported data displays an error in a fixed toolbar row; an error does not disable the Advanced action.

## Install

Build the project and load this shaded jar in Burp Suite:

```text
target\burp-workbench-extension-0.5.0.jar
```

This single shaded jar is the distribution for every supported Burp version. Do not install `target\original-burp-workbench-extension-0.5.0.jar`; that file is Maven's unshaded backup and does not include bundled runtime dependencies such as Brotli/Rhino.

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
target\burp-workbench-extension-0.5.0.jar
target\original-burp-workbench-extension-0.5.0.jar
```

Only `target\burp-workbench-extension-0.5.0.jar` is a loadable distribution artifact.

## Architecture

This project intentionally stays as one Maven project and one jar. Java packages provide module boundaries:

- `com.burpworkbench.platform`: module lifecycle and cross-module contracts.
- `com.burpworkbench.core`: genuinely shared selection and MIME policies.
- `com.burpworkbench.modules.extractor`: Extractor module implementation.
- `com.burpworkbench.modules.search`: Search++ module implementation.
- `com.burpworkbench.modules.replace`: replace ++ module implementation.
- `com.burpworkbench.modules.compare`: compare ++ module implementation.
- `com.burpworkbench.modules.decoder`: decoder ++ module implementation.

See `docs/architecture.md` for dependency rules and extension points.
