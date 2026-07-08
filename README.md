# Burp Workbench

Burp Workbench is a single-jar Burp Suite extension that groups workflow modules around a shared core.

Current modules:

- `Extractor`: right-click export of selected Site map, Proxy history, Search results, or message editor items while preserving host/path structure, decoded response bodies, duplicate detection, manifest, summary, and local HTML index output.
- `Search++`: modeless advanced search for Burp HTTP messages with Korean text search, hex search, location/source controls, post-search filters, negative match filtering, request/response preview, and result extraction through the shared extraction handler.

## Build

Requirements:

- JDK 17
- Maven
- Network access for the first dependency resolution
- Burp Suite with Montoya API support

```powershell
mvn test
mvn clean package
```

Install this jar in Burp:

```text
target\burp-workbench-extension-0.4.1.jar
```

## Architecture

This project intentionally stays as one Maven project and one jar. Java packages provide module boundaries:

- `com.burpworkbench.platform`: module lifecycle and cross-module contracts.
- `com.burpworkbench.core`: shared HTTP, selection, filter, codec, UI, and utility code.
- `com.burpworkbench.modules.extractor`: Extractor module implementation.
- `com.burpworkbench.modules.search`: Search++ module implementation.

See `docs/architecture.md` for dependency rules and extension points.

## Local Work Files

`.work/` is for local TODO/DONE/work process notes and is intentionally ignored by git.

