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
          +-- HTTP exchange model and extension filters
```

## Dependency Direction

```text
modules.extractor -> core + platform
modules.search    -> core + platform
platform          -> core only when a shared contract requires HTTP types
core              -> no platform/modules dependency
modules.search    -> no modules.extractor dependency
```

`BurpWorkbenchExtension` creates the two current modules, explicitly passes Extractor's small `ExtractionHandler` contract to Search++, registers them, and starts the registry. It contains no feature implementation and 0.4.4 intentionally has no generic service registry.

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

## Future Modules

Future Scanner, Highlighter, or similar tools should implement `WorkbenchModule`, keep shared logic in `core` only when it is genuinely reusable, and communicate with other modules through small `platform` contracts.

Implementation classes are package-private unless another package must construct or reference them. The public surface is limited to the Burp entry point, module/platform boundaries, and the genuinely shared selection/MIME policies.
