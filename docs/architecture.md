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
      +-- search
      |   +-- HTTP exchange model and extension filters
      +-- replace
          +-- Proxy rules, native UI adapters, traffic transformation, project storage
```

## Dependency Direction

```text
modules.extractor -> core + platform
modules.search    -> core + platform
modules.replace   -> platform + Montoya UI/Proxy/persistence adapters
platform          -> core only when a shared contract requires HTTP types
core              -> no platform/modules dependency
modules.search    -> no modules.extractor dependency
modules.replace   -> no modules.extractor/search dependency
```

`BurpWorkbenchExtension` creates the three current modules, explicitly passes Extractor's small `ExtractionHandler` contract to Search++, registers them, and starts the registry. It contains no feature implementation or generic service registry. `ReplacePlusModule` owns Replace ++ independently; it does not route traffic through Extractor or Search++.

## Platform Contracts

- `WorkbenchModule`: initialization interface for modules.
- `ModuleContext`: shared access to `MontoyaApi`.
- `ModuleLifetime`: owns registrations, workers, windows, and other closeable resources in LIFO order.
- `ModuleRegistry`: starts modules in order, rolls back failed initialization, and performs one reverse-order unload.
- `ExtractionHandler`: cross-module extraction boundary used by Search++ without importing Extractor internals.

## Current Modules

### Extractor

Owns context-menu extraction, sequential response-body processing, duplicate detection, decoded body saving, manifest/index/summary generation, and progress UI. Only the current raw body is copied from Montoya; decoded data is streamed through a temporary file and released after that item. Its module exposes a small `ExtractionHandler` so Search++ can request extraction without importing Extractor internals.

### Search++

Owns top menu/context menu entry, advanced search window, post-search filters, negative match filtering, and request/response preview. `SearchSourceScanner` owns the existing 32-way hash-partitioned source traversal, scope filtering, source-transaction delivery, and cancellation checks; it preserves each matching transaction supplied by a source instead of choosing one representative for a method-and-URL pair. Search results copy message data to Burp-managed temporary files so retained response bodies do not remain as ordinary heap-backed copies. `SearchEngine` owns prepared query matching and per-item regular-expression deadlines. `SearchExecutionCoordinator` grants one source-scan permit across all Search++ windows without queuing or auto-cancelling another window. Result extraction calls `ExtractionHandler` only.

### replace ++

Owns one `replace ++` tab immediately to the right of Proxy's built-in `Match and replace`, an ordered rule list, inline rule editing, native HTTP Test editors, a configurable shortcut, Proxy request/response handlers, and project-specific rule persistence. It neither imports nor changes Burp's built-in rule list.

- `ReplacePanel` and the resizable/collapsible components own UI state. `BurpPreviewEditor` adapts Montoya's native request/response editors so Burp controls font, message views, and highlighting. Test calls the same `TrafficEngine` as live traffic but isolates the selected rule and deliberately excludes its enabled state and URL/Path scope.
- `ProxyTabMount` finds a unique compatible Proxy tab container, inserts its owned component, and removes only that component on cleanup while preserving existing tabs. This is an internal Swing integration, not a public Montoya sub-tab registration API. Failure to mount leaves traffic replacement inactive; successful compilation alone does not certify a Burp UI layout.
- `HotkeyBinding` owns Montoya registrations and rejects stale queued callbacks after rebind or close. `ScopeCapture` reads only the selected request's origin and raw Path, creating a new off rule rather than overwriting an existing one. Shortcut changes last for the current load.
- `RuleSession` publishes immutable rule snapshots to traffic callbacks and coordinates `RuleStore`. The project extension-data schema stores rule fields, order, individual enabled states, and the overall enabled state. It retains the previous valid snapshot, refuses silent resets of malformed/unknown data, and detects external changes before saving. Test messages and shortcut preferences are not part of the schema. `DefaultRules` supplies six disabled header-removal presets only when the primary state key is absent; loading them performs no write. A saved empty list remains empty, and existing/corrupt data is never replaced with defaults.
- `ScopeMatcher` implements normalized exact HTTP(S) origins and bounded raw-path globs independently of replacement regexes. Blank conditions are unrestricted; bare `**` is unrestricted Path. The query string is not part of Path scope.
- `ProxyTrafficHandler` applies rules only in the request/response send callbacks, preserving interception decisions, annotations, and the request's HTTP service. It checks applicable scope before copying bounded message bytes. Received callbacks pass through unchanged. `TrafficEngine` applies each eligible rule in list order and commits only a successful rule's complete result; invalid rules are isolated and later valid rules can continue within the remaining budget.

The module lifetime owns its runtime, which stops traffic mutation before UI cleanup, deregisters Proxy and hotkey handlers, stops timers, flushes pending project changes, releases native previews, and removes only its own tab. Proxy handlers are registered only after the tab mounts. Asynchronous UI initialization failures close this module's resources without taking down Extractor or Search++.

Replacement safety limits are separate from Search++ and Extractor limits: 8 MiB message/output bytes, 16 MiB decoded text bytes, 256 KiB headers, 1,000 applicable rules per message, 250 ms per rule, and one second of aggregate engine work per message. Scope preflight has a separate 250 ms budget. These are processing budgets, not a hard end-to-end Burp latency guarantee. Invalid or over-budget rules do not commit their partial edits; successful preceding edits can remain. Query/form parameter rules support UTF-8 URL query and form-urlencoded data; body rules use declared charset or UTF-8, preserve gzip/deflate coding, and emit modified Brotli bodies without content coding.

The product compiles against Montoya `2025.12` with Java 17 bytecode. Its supported Burp target range is `2025.12` through `2026.7.1`; versions outside that range and changes to internal Proxy UI layouts require separate verification.

## Future Modules

Future Scanner, Highlighter, or similar tools should implement `WorkbenchModule`, keep shared logic in `core` only when it is genuinely reusable, and communicate with other modules through small `platform` contracts.

Implementation classes are package-private unless another package must construct or reference them. The public surface is limited to the Burp entry point, module/platform boundaries, and the genuinely shared selection/MIME policies.
