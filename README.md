# Burp Workbench

Burp Workbench is a single-jar Burp Suite extension that groups workflow modules around a shared core.

Current version: `0.4.2`

## Modules

- `Extractor`: exports selected Burp HTTP responses to local files with manifest, index, summary, duplicate handling, and optional JS/JSON beautify before saving.
- `Search++`: provides a tabbed advanced search window for Burp HTTP messages and can pass selected results to Extractor.

## Install

Build the project and load this shaded jar in Burp Suite:

```text
target\burp-workbench-extension-0.4.2.jar
```

Do not install `original-burp-workbench-extension-0.4.2.jar`; that file is Maven's unshaded backup and does not include bundled runtime dependencies such as Brotli/Rhino.

## Build

Requirements:

- JDK 17 or newer
- Maven
- Network access for the first dependency resolution
- Burp Suite with Montoya API support

```powershell
mvn test
mvn clean package
```

Expected build outputs:

```text
target\burp-workbench-extension-0.4.2.jar
target\original-burp-workbench-extension-0.4.2.jar
```

## Architecture

This project intentionally stays as one Maven project and one jar. Java packages provide module boundaries:

- `com.burpworkbench.platform`: module lifecycle and cross-module contracts.
- `com.burpworkbench.core`: shared HTTP, selection, filter, codec, UI, and utility code.
- `com.burpworkbench.modules.extractor`: Extractor module implementation.
- `com.burpworkbench.modules.search`: Search++ module implementation.

See `docs/architecture.md` for dependency rules and extension points.
