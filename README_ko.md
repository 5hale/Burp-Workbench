# Burp Workbench

Burp Workbench는 공통 core 위에 여러 워크플로우 모듈을 묶은 단일 jar Burp Suite 확장입니다.

현재 버전: `0.4.3`

## 모듈

- `Extractor`: 선택한 Burp HTTP 응답을 manifest, index, summary, 중복 처리, 선택적 JS/JSON beautify와 함께 로컬 파일로 추출합니다.
- `Search++`: Burp HTTP 메시지를 검색하는 탭 기반 고급 검색 창이며, 선택한 결과를 Extractor로 넘겨 추출할 수 있습니다.

## 설치

프로젝트를 빌드한 뒤 아래 shaded jar를 Burp Suite에 로드합니다.

```text
target\burp-workbench-extension-0.4.3.jar
```

`original-burp-workbench-extension-0.4.3.jar`는 설치하지 마세요. 이 파일은 Maven이 남기는 unshaded backup이며 Brotli/Rhino 같은 번들 런타임 의존성이 포함되지 않습니다.

## 빌드

요구 사항:

- JDK 17 이상
- Maven
- 최초 dependency resolution을 위한 네트워크 접근
- Montoya API를 지원하는 Burp Suite

```powershell
mvn test
mvn clean package
```

예상 빌드 산출물:

```text
target\burp-workbench-extension-0.4.3.jar
target\original-burp-workbench-extension-0.4.3.jar
```

## 아키텍처

이 프로젝트는 의도적으로 하나의 Maven 프로젝트와 하나의 jar를 유지합니다. Java package로 모듈 경계를 나눕니다.

- `com.burpworkbench.platform`: 모듈 lifecycle과 모듈 간 contract
- `com.burpworkbench.core`: 공유 HTTP, selection, filter, codec, UI, utility code
- `com.burpworkbench.modules.extractor`: Extractor 모듈 구현
- `com.burpworkbench.modules.search`: Search++ 모듈 구현

의존성 규칙과 확장 지점은 `docs/architecture.md`를 참고하세요.
