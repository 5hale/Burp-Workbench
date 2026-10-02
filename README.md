# Burp Workbench

Burp Workbench is a single-jar Burp Suite extension that groups workflow modules around a shared core.

Current version: `0.5.1`

## Modules

- `Extractor`: exports selected Burp HTTP responses to local files with manifest, index, summary, duplicate handling, optional JS/JSON beautify, and optional rule-based information tagging.
- `Search++`: provides a tabbed advanced search window for Burp HTTP messages and can pass selected results to Extractor.
- `replace ++`: ordered, project-persisted Proxy replacement and Forward rules, URL/Path scope, configurable hotkeys, and direction-sensitive native Burp message previews.
- `compare ++`: compares captured messages or text with independent Pretty/Raw/Hex tabs for A and B, Korean text, difference navigation, and configurable hotkeys.
- `decoder ++`: live Quick/Advanced conversion popups with independent work tabs, selection capture, configurable hotkeys, and Light/Dark themes.

## Supported Burp versions

Burp Workbench `0.5.1` supports Burp Suite versions from `2025.12`
through `2026.7.1`, inclusive.

| Burp Suite version | Proxy compatibility mode | Support |
|---|---|---|
| `2025.12` through `2026.7.1` | `HISTORY_ID` | Supported target range |
| Earlier than `2025.12` | — | Not supported |
| Later than `2026.7.1` | Runtime capability detection | Outside the supported range of `0.5.1` |

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

The top-level `Extractor` tab manages filter rules; export still starts from HTTP context menus or Search++ results. The original folder chooser keeps `Beautify` and an optional `Filter` checkbox, off by default.

- New projects start with an empty rule list. `Rulesets…` copies selected optional templates into your own list; templates are editable and can be copied. Categories/search are for organization, not automatic personal-data classification. The current catalog is an initial regex-based collection, not the complete upstream detection engines.
- Basic fields are Name, Category, Regex, Pattern, Tag and a free Description. Advanced options include literal/domain match, Body/Headers/All scope, source text or parsed values, capture group, tag numbering, Content-Type/field-name/excluded-value regexes. Group 0 replaces the whole match; a higher group changes only the captured value.
- Ctrl/Shift selection supports batch Copy/Remove/Up/Down. Table columns follow the divider; Hide details preserves selection, filters and edits. Rules, order and drafts are project-persisted, including literals entered by the user: protect the project accordingly.
- Filter OFF preserves ordinary export. Filter ON applies only enabled user rules before saving bodies, metadata and filenames. A filter failure does not fall back to saving unfiltered content. Review outputs before sharing: regex templates can produce false positives or miss information, and this is not complete anonymization.
- Tags such as `§EMAIL_1§` are consistent within an export. Tagged JSON scalars may become strings. `filter_attributes.json` records tags, categories, original types, rule IDs and counts, without sensitive source values. JSON/HTML/XML/CSV/form/text/HTTP parsing uses an 8 MiB input/decoded limit.
- `Test rules…` loads local files or accepts pasted input, without sending requests. Input/Filtered wrap by default; input supports Ctrl+Z/Redo. Highlighted replacement tags can be followed with ‹/›, F3/Shift+F3 or match-row selection. Start/End refer to the matched value/source, not absolute output coordinates. Ambiguous tags already present in the input are not navigated.
- Existing 0.5.0 direct rules and saved mobile/email choices are read into visible rules; the old project state is retained. Mobile/email choices become editable regex templates, so review their coverage rather than assuming the old library's validation. Demo settings are never imported. Invalid/external changes do not silently overwrite saved data.

## Workbench context menus

Owned text inputs offer Cut/Copy/Paste/Select All and applicable Send to Replace/Compare/Decoder actions. Rule tables expose their existing row operations on right-click. Burp's native editor menus are retained; selection and read-only state determine which actions are available.

## replace ++

Open `Proxy > replace ++` next to built-in Match and replace. Replace/Forward management buttons are icons with tooltips. Lists support Ctrl/Shift batch Copy/Remove/Up/Down; divider-aware columns and Hide details preserve the working layout.

- Valid sessions are always active: the global Enabled switches have been removed. Each rule's On controls actual application. New rules start OFF. Valid edits retain the existing On/OFF state; invalid drafts remain editable and become OFF when leaving the rule/page. Originally OFF rules do not turn ON automatically. Text inputs support Ctrl+Z/Redo.
- Replacement matching ignores case by default. Match case opts into case-sensitive literal/regex matching. URL/origin and Path conditions are independent of that option.
- Targets include request/response first lines, headers and bodies, plus request query/form parameters. Request headers exclude the request line; Response first line can change the HTTP version/status/reason without editing headers or body. Whole binary payloads, arbitrary structured parameters, multipart and trailer rewriting are not provided.
- Origin scope is exact `http(s)://host[:port]`; Path uses raw path globs (`*` within a segment, `**` across segments), without query. Empty conditions are unrestricted.
- Test Preview uses direction-sensitive native Burp editors without sending traffic. It compares the sample with the result; the two preview widths start equal and the rule fields start compact. Scope capture/default Ctrl+Shift+Q adds an OFF rule and snapshots the focused whole request/response.
- Rules, order, individual On and Match case persist in project extension data. Existing schema1 rules load with case-insensitive matching; schema2 stores the explicit flag and retains a previous backup. Samples/hotkey changes are not part of rule storage. Corrupt or externally changed state is not silently reset.
- Replace is applied in list order immediately before Proxy forwarding. Invalid or over-budget edits do not commit partial changes. Limits: 8 MiB message/output, 16 MiB decoded text, 256 KiB headers, 1,000 applicable rules, 250 ms per rule and one second aggregate engine work; scope preflight has a separate 250 ms budget. Query/form rules use UTF-8. Body rules preserve declared charset and gzip/deflate; modified Brotli bodies are emitted without content coding.

### Forward

Source URL | Source Path and Destination URL | Destination Path are edited as two rows. Forward runs after Replace, always last; the last valid matching rule in list order wins. It changes HttpService/Host and optionally Path while preserving the raw query, not the browser address bar and not an extra request.

Source URL is an exact origin. With an empty Destination Path, the actual request path is retained; with an empty Source Path, its path condition is unrestricted. Empty paths on both sides retain the matched request path. New Forward rules are OFF in an initially empty list. Test computes the destination only.

### 0.5.1 upgrade

Existing product Replace/Forward rules and their individual On states are kept; the old global Enabled setting no longer disables a valid session. Demo rules are not imported. Extractor reads legacy product choices as described above. Unload the old extension/independent demos before loading the new JAR to avoid duplicate handlers.

## compare ++

Open `Proxy > compare ++` next to Replace++. Capture from the focused HTTP editor/context menu or Ctrl+Shift+W; selected bytes are imported as Text. Paste/Load and `+` empty items also work. The first two imports are assigned A/B. With `+`, an empty side is used first; otherwise the last active side (initially A) receives the new item.

- A/B independently select Pretty/Raw/Hex, encoding, and text. A alone or B alone supports view/settings/find. Raw whole view is editable with Ctrl+Z/Redo; Pretty/Hex/Differences only and lossy decoding are read-only. Each side keeps its own draft, even for the same item; the original bytes remain unchanged. ↶ restores that side's original.
- Diff refresh is debounced by 250 ms and preserves caret/undo. Words/Unicode Characters/Bytes comparison, exact changes, Sync, Wrap, Differences only and literal Find are retained. Empty Find never navigates differences; Alt+Up/Down moves differences, Enter/Shift+Enter moves Find matches.
- Items supports Ctrl/Shift batch Copy/Remove, adjustable columns and the existing hide/show toggle. Copy uses the working content; captured original requests remain the source for the explicit Repeater action (Ctrl+R). Capturing or comparing never performs a network Send.
- Session limits are 1 MiB/item, 200 items and 32 MiB total including source requests and drafts. Compare data is not project-persisted and is released on unload.
- Incoming captures mark the tab orange until selected without pulsing or stealing focus. Native Pretty/Raw/Hex controls in Replace previews remain Burp-owned.

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
target\burp-workbench-extension-0.5.1.jar
```

This single shaded jar is the distribution for every supported Burp version. Do not install `target\original-burp-workbench-extension-0.5.1.jar`; that file is Maven's unshaded backup and does not include bundled runtime dependencies such as Brotli/Rhino.

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
target\burp-workbench-extension-0.5.1.jar
target\original-burp-workbench-extension-0.5.1.jar
```

Only `target\burp-workbench-extension-0.5.1.jar` is a loadable distribution artifact.

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
