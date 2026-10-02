# Burp Workbench Architecture

Burp Workbench is a single Burp extension jar with package-level modules. The goal is to let future tools such as Scanner or Highlighter plug into the same workbench without coupling current modules together.

## Package Layout

```text
com.burpworkbench
  +-- BurpWorkbenchExtension
  +-- platform
  |   +-- WorkbenchModule
  |   +-- ModuleContext
  |   +-- ModuleLifetime
  |   +-- ModuleRegistry
  |   +-- ExtractionHandler
  +-- core
  |   +-- selection
  |   +-- filter
  +-- modules
      +-- extractor
      |   +-- response decoding, hashing, manifest/index utilities
      |   +-- filter: document parsing, prepared tagging rules, project settings
      +-- search
      |   +-- HTTP exchange model and extension filters
      +-- replace
      |   +-- Replace/Forward Proxy rules, native UI adapters, project storage
      +-- compare
      |   +-- capture, exact diff, per-side Pretty/Raw/Hex views, independent navigation
      +-- decoder
          +-- local codecs, popup session tabs, selection-only capture, hotkeys
```

## Dependency Direction

```text
modules.extractor -> core + platform
modules.search    -> core + platform
modules.replace   -> platform + Montoya UI/Proxy/persistence adapters
modules.compare   -> platform + Montoya UI/Repeater adapters
platform          -> core only when a shared contract requires HTTP types
core              -> no platform/modules dependency
modules.search    -> no modules.extractor dependency
modules.replace   -> no modules.extractor/search dependency
modules.compare   -> modules.extractor.BeautifyService for JS/JSON formatting only
modules.decoder   -> platform + Montoya UI/preferences adapters
```

`BurpWorkbenchExtension` creates five modules, explicitly passes Extractor's small `ExtractionHandler` contract to Search++, registers them, and starts the registry. It contains no feature implementation or generic service registry. Replace++, Compare++, and Decoder++ own independent runtimes and close in reverse startup order.

## Platform Contracts

- `WorkbenchModule`: initialization interface for modules.
- `ModuleContext`: shared access to `MontoyaApi`.
- `ModuleLifetime`: owns registrations, workers, windows, and other closeable resources in LIFO order.
- `ModuleRegistry`: starts modules in order, rolls back failed initialization, and performs one reverse-order unload.
- `ExtractionHandler`: cross-module extraction boundary used by Search++ without importing Extractor internals.
- `WorkbenchInput`: local text/native editor selection context, without a global last-message cache.
- `WorkbenchKeys`: owned-UI shortcut dispatch, settings-field exclusion, and physical-event deduplication.
- `WorkbenchMenus` / `TableMenus`: selection-aware text actions and existing row operations, preserving native popups. Cross-module sends use injected actions rather than direct module dependencies.
- `LatestWork`: one running computation and one latest queued replacement, stale-publication rejection, cancellation and idle worker release.

### Decoder and Compare presentation additions

`DecoderRuntime` owns Quick/Advanced modeless windows, each containing independent `SessionTabs`, hotkey registration/fallback, preferences and font watching. Only explicit selections cross into new tabs. Workers and registrations close with their owning tab/runtime. No traffic handler or top-level Decoder tab is installed.

Advanced conversion caches the last canonical bytes/options generation, avoids rewriting unchanged outputs and debounces large edits. Its Auto wrap policy disables visual wrapping above 8,192 UTF-16 units per logical line; explicit On/Off overrides are available. Long pastes disable wrap before inserting content. No policy truncates results or inserts actual newlines. `LatestWork` bounds pending work and releases idle threads after 30 seconds. Only product shortcut preferences are restored; no demo settings are imported.

`PaneComparison` chooses original Raw/Hex bytes or a disposable Pretty UTF-8 copy independently per side. `Comparison` computes changes on those sources and renders each side using its own mode. Hex always uses original bytes and a fixed-width font. Formatting failures fall back on the affected side. Ordinary mixed-view notices are hidden; errors and position provenance remain available.

## Current Modules

### Extractor

Owns context-menu extraction, sequential response-body processing, duplicate detection, decoded body saving, manifest/index/summary generation, and progress UI. Only the current raw body is copied from Montoya; decoded data is streamed through a temporary file and released after that item. Its module exposes a small `ExtractionHandler` so Search++ can request extraction without importing Extractor internals.

The suite tab contains `filter.RulesPanel`, not an extraction dashboard. `ExportChooserPanel` adds only an optional Filter checkbox beside Beautify. `ExtractorRulesProvider` snapshots current edited rules when an export starts, including exports from Search++. Filter OFF uses the original streaming path. ON uses `FilterHook` / `FilterSession` before saving, with bounded document parsing and no unfiltered fallback after failure.

`filter.Documents` selects JSON/HTML/XML/CSV/form/text/HTTP handling. Jackson and jsoup are bundled; the input/decoded limit is 8 MiB. `FilterSession` prepares ordered enabled regex/literal/host rules, applies scope/capture/MIME/field/exclusion conditions, protects tags and uses cooperative regex budgets. There are no hidden automatic mobile/email branches or external detector services. Numbered tags are stable within an export; attributes store rule IDs/types/counts without sensitive values. Tagged JSON scalars may become strings.

`FilterRulesStore` persists product rules under a new product key, detects external changes and preserves backups. Its legacy reader (`FilterRuleStore`) converts saved 0.5.0 direct rules and detector choices to visible editable rules without overwriting old state; corrupt data blocks filtering and demo keys are ignored. `RuleCatalog` provides opt-in templates with source/license notes, not full upstream detector equivalence. `RulesPanel` supports category/search, multi-selection, basic/Advanced fields and reversible Hide details. `RuleTestDialog` accepts local/pasted input with Wrap/Undo and output-local tag highlighting/navigation; source offsets are not output coordinates.

### Search++

Owns top menu/context menu entry, advanced search window, post-search filters, negative match filtering, and request/response preview. `SearchSourceScanner` owns the existing 32-way hash-partitioned source traversal, scope filtering, source-transaction delivery, and cancellation checks; it preserves each matching transaction supplied by a source instead of choosing one representative for a method-and-URL pair. Search results copy message data to Burp-managed temporary files so retained response bodies do not remain as ordinary heap-backed copies. `SearchEngine` owns prepared query matching and per-item regular-expression deadlines. `SearchExecutionCoordinator` grants one source-scan permit across all Search++ windows without queuing or auto-cancelling another window. Result extraction calls `ExtractionHandler` only.

### replace ++

Owns one `replace ++` tab immediately to the right of Proxy's built-in `Match and replace`, with separate Replace/Forward pages, ordered rules, inline editing, native HTTP Test editors, a configurable shortcut, Proxy request/response handlers, and project-specific persistence. It neither imports nor changes Burp's built-in rule list.

- `ReplacePanel` and resizable/collapsible components own UI state. List/detail and Test splits start at 50:50; Match/Replace fields start at their compact minimum height and remain draggable. `BurpPreviewEditor` adapts native request/response editors so Burp controls fonts, views and highlighting. Test calls `TrafficEngine` off the UI thread through `LatestWork`, isolates the selected rule and excludes its enabled state and URL/Path scope.
- `ProxyTabMount` finds a unique compatible Proxy tab container, inserts its owned component, and removes only that component on cleanup while preserving existing tabs. This is internal Swing integration, not public Montoya sub-tab registration. An unavailable/ambiguous anchor falls back to a public suite tab; compilation alone does not certify a Burp UI layout.
- `HotkeyBinding` owns registrations and rejects stale queued callbacks after rebind or close. `ScopeCapture` imports origin/raw Path into a new off rule and snapshots the whole focused Request/Response (not just selected bytes) for Test Preview. Shortcut changes last for the current load. Neither capture nor Test sends traffic.
- `RuleSession` publishes immutable rule snapshots to traffic callbacks and coordinates `RuleStore`. Schema2 stores fields, order, individual On states and Match case; schema1 loads with case-ignore. Valid sessions stay globally active and each rule controls application. It retains the previous valid snapshot, refuses silent resets of malformed/unknown data, and detects external changes before saving. Test messages and shortcut preferences are not part of the schema. `DefaultRules` supplies six disabled header-removal presets only when the primary state key is absent; loading them performs no write. A saved empty list remains empty, and existing/corrupt data is never replaced with defaults.
- `ScopeMatcher` implements normalized exact HTTP(S) origins and bounded raw-path globs independently of replacement regexes. Blank conditions are unrestricted; bare `**` is unrestricted Path. The query string is not part of Path scope.
- `ProxyTrafficHandler` applies rules only in request/response send callbacks, preserving interception decisions and annotations. It checks applicable scope before copying bounded message bytes. Received callbacks pass through unchanged. `TrafficEngine` applies eligible Replace rules in list order and commits only complete successful results; invalid rules are isolated. Its parsed message/body cache belongs to one byte-array generation and is invalidated after each modification.
- `ForwardSession` uses the existing independent product key. Valid sessions stay active, new rules are OFF and no demo state is imported. `ForwardEngine` matches original-source conditions in list order; the last valid match wins. Request Forward runs after Replace and changes `HttpService`, Host and optionally Path while preserving the raw query. A blank destination path retains the actual request path. Browser redirects and extra sends are not involved. Forward Test is a pure destination calculation.

The module lifetime owns its runtime, which stops traffic mutation before UI cleanup, deregisters Proxy/hotkey handlers, stops timers, flushes pending project changes, releases native previews, and removes only its own tab. Proxy handlers register after successful UI initialization, independently of eventual internal-tab mounting. Initialization failures close this module's resources without taking down other modules. Replace/Forward keep their product keys and individual On states; Replace schema2 adds Match case.

Replacement safety limits are separate from Search++ and Extractor limits: 8 MiB message/output bytes, 16 MiB decoded text bytes, 256 KiB headers, 1,000 applicable rules per message, 250 ms per rule, and one second of aggregate engine work per message. Scope preflight has a separate 250 ms budget. These are processing budgets, not a hard end-to-end Burp latency guarantee. Invalid or over-budget rules do not commit their partial edits; successful preceding edits can remain. Query/form parameter rules support UTF-8 URL query and form-urlencoded data; body rules use declared charset or UTF-8, preserve gzip/deflate coding, and emit modified Brotli bodies without content coding.

The product compiles against Montoya `2025.12` with Java 17 bytecode. Its supported Burp target range is `2025.12` through `2026.7.1`; versions outside that range and changes to internal Proxy UI layouts require separate verification.

### compare ++

`ComparePlusModule` owns `CompareRuntime` through `ModuleLifetime`, without another Burp extension entry point or unload registration. The runtime owns its panel, shortcut, context menu, optional top-level tab, retry/font timers and comparison worker. Queued initialization/capture is rejected after close. Failed startup releases only this module's resources; cleanup attempts all owned resources even if a registration cannot be released, and retains failed registrations for a subsequent cleanup attempt.

The normal UI is a `compare ++` sub-tab immediately right of Replace++. It uses an independent Swing adapter and does not import Replace++ internals. If that anchor is absent it can mount after built-in Match and replace; an unavailable/ambiguous host falls back to a public Montoya suite tab. It removes only its owned component. Capture uses message-editor context and optional byte selection, never a selected-history-list fallback. Snapshot bytes are copied, and original requests/services are bounded for the explicit Repeater action; no network Send is performed.

`DiffEngine` uses a bounded Myers trace for small edit distances and exact Hirschberg linear-space LCS for large ones. A shared four-second diff budget and interruption checks bound computational work, not decoding or Swing rendering. Unicode Characters keep code points intact; Bytes compare raw data. `Comparison` maps results back to source line/byte positions for Text/Hex and optional equal-segment hiding. A failure is a distinct state that preserves original-data presentation, not an empty successful diff. `LatestComparison` allows one running computation and one replacement and rejects stale publications.

`ComparePanel` retains the two-pane layout and independent controls: difference arrows/Alt+Up·Down navigate both sides, while Find/Enter/Shift+Enter only search literal text in the last focused pane. Empty Find never navigates. `SyntaxEditor` paints lexical syntax and visible difference ranges with the themed `JTextField` default font (no added Bold); it is not a native Pretty parser. Ctrl+F enters Find and Ctrl+R adds the source request to Repeater. The session collection is bounded to1MiB/item,200items/32MiB including source requests, and is released on unload. These comparison limits do not alter Search++ or Extractor policies.

Incoming Compare captures do not select tabs or request focus. `TabAttention` marks the owning Compare tab and its enclosing tabs orange, without pulsing, until selected; unload restores colors and releases listeners. `WorkbenchKeys` supplies Compare/Replace shortcuts within owned extension UI, with settings-capture exclusion and native/Swing event deduplication. Decoder uses the same scope helpers. `WorkbenchInput` attaches local capture providers to owned native editors and Compare panes: native selections remain bytes, plain Swing selections are UTF-8, and no global message cache is retained. Its full-message capture supports Replace's direction-sensitive Test Preview; shared menus reuse these capture routes.

### 0.5.1 editing and list lifecycle

Replace/Forward/Compare lists support multi-selection, batch operations and width-aware columns. Hide details/Items retains the working split. Valid ON edits remain ON; invalid drafts are editable and become OFF at a rule/page exit. TextUndo resets history across programmatic rule loads. Compare keeps immutable originals and A/B-specific raw drafts, including single-side presentation; updates are debounced and preserve caret/undo. Drafts stay within session byte budgets, while Pretty/Hex/lossy decoding are read-only. Extractor Test tag navigation is output-local, and ambiguous tags already in input are excluded.

## Future Modules

Future Scanner, Highlighter, or similar tools should implement `WorkbenchModule`, keep shared logic in `core` only when it is genuinely reusable, and communicate with other modules through small `platform` contracts.

Implementation classes are package-private unless another package must construct or reference them. The public surface is limited to the Burp entry point, module/platform boundaries, and the genuinely shared selection/MIME policies.
