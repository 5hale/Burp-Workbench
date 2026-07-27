# Burp Workbench Architecture

Burp Workbench is a single Burp extension jar with package-level modules. The goal is to let future tools such as Scanner or Highlighter plug into the same workbench without coupling current modules together.

## Package Layout

```text
com.burpworkbench
  +-- BurpWorkbenchExtension
  +-- platform
  |   +-- WorkbenchModule
  |   +-- ModuleContext
  |   +-- ModuleRegistry
  |   +-- ExtractionHandler
  +-- core
  |   +-- http
  |   +-- selection
  |   +-- filter
  |   +-- codec
  |   +-- ui
  |   +-- util
  +-- modules
      +-- extractor
      +-- search
```

## Dependency Direction

```text
modules.extractor -> core + platform
modules.search    -> core + platform
platform          -> core only when a shared contract requires HTTP types
core              -> no platform/modules dependency
modules.search    -> no modules.extractor dependency
```

`BurpWorkbenchExtension` only creates a `ModuleRegistry`, registers modules, and starts them. It should not contain feature implementation.

## Platform Contracts

- `WorkbenchModule`: lifecycle interface for modules.
- `ModuleContext`: shared access to `MontoyaApi` and registered cross-module services.
- `ModuleRegistry`: starts modules in order and exposes shared handlers.
- `ExtractionHandler`: cross-module extraction boundary used by Search++ without importing Extractor internals.

## Current Modules

### Extractor

Owns context-menu extraction, planning, duplicate detection, decoded body saving, manifest/index/summary generation, and progress UI. It registers `ExtractionHandler` so other modules can request extraction of selected HTTP messages.

### Search++

Owns top menu/context menu entry, advanced search window, post-search filters, negative match filtering, and request/response preview. `SearchSourceScanner` owns hash-partitioned source traversal, scope filtering, deduplication, and cancellation checks; `SearchEngine` owns prepared query matching. Result extraction calls `ExtractionHandler` only.

## Future Modules

Future Scanner, Highlighter, or similar tools should implement `WorkbenchModule`, keep shared logic in `core` only when it is genuinely reusable, and communicate with other modules through small `platform` contracts.
