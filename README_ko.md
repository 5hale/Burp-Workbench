# Burp Workbench

Burp Workbench는 공통 core 위에 여러 워크플로우 모듈을 묶은 단일 jar Burp Suite 확장입니다.

현재 버전: `0.4.6`

## 모듈

- `Extractor`: 선택한 Burp HTTP 응답을 manifest, index, summary, 중복 처리, 선택적 JS/JSON beautify와 함께 로컬 파일로 추출합니다.
- `Search++`: Burp HTTP 메시지를 검색하는 탭 기반 고급 검색 창이며, 선택한 결과를 Extractor로 넘겨 추출할 수 있습니다.

## 지원 Burp 버전

Burp Workbench `0.4.6`의 공식 지원 범위는 Burp Suite
`2025.9.3` 이상부터 `2026.7.1` 이하까지입니다.

| Burp Suite 버전 | Proxy 호환 모드 | 지원 여부 |
|---|---|---|
| `2025.9.3` | `LEGACY_METADATA` | 지원 |
| `2025.10` 이상 ~ `2026.7.1` 이하 | `HISTORY_ID` | 지원 |
| `2025.9.3` 미만 | — | 지원하지 않음 |
| `2026.7.1` 초과 | 런타임 기능 자동 탐지 | `0.4.6` 보장 범위 밖 |

지원 범위 안에서는 모두 같은 배포 jar를 사용합니다. 호환 모드는
자동으로 선택되며 사용자가 별도로 설정할 필요가 없습니다. extension은
Montoya API `2025.8`을 기준으로 컴파일하고, Montoya 의존성은
`provided`이므로 배포 jar에 포함하지 않습니다.

`2026.7.1`보다 최신인 Burp도 Montoya API 호환성이 유지되면 동작할
수 있지만, 별도 검증 전에는 `0.4.6`의 공식 보장 범위에 포함하지
않습니다.

호환성 matrix와 검증 상태는
`docs/compatibility-0.4.5-verification.md`에서 확인할 수 있습니다.

## 현재 동작 기준

- Search++는 소스를 partition 단위로 처리하고 여러 창의 source scan을 하나씩 실행하며, 검색 취소와 malformed item·정규식 timeout 격리를 지원합니다.
- Extractor는 응답 body를 순차 처리하고 decoded body를 임시 파일로 streaming하며, Beautify 메모리 예산을 넘으면 decoded 원문을 그대로 저장합니다.
- extension unload 시 실행 중인 작업을 취소하고 창·registration·executor를 하나의 lifecycle에서 정리합니다.

Search++ 창은 여러 개 열 수 있지만 실제 source scan은 extension 전체에서 한 번에 하나만 실행됩니다. 결과 개수는 자동으로 제한하지 않습니다. 정규식은 항목당 2초를 넘기면 그 항목만 건너뛰고 결과를 `incomplete`로 표시합니다.

한글 검색은 그대로 지원합니다. 응답에 charset 선언이 있으면 그 charset만 엄격하게 사용합니다. 선언이 없으면 UTF-8 → MS949 → EUC-KR → ISO-8859-1 순서로 검사합니다. 일반 비ASCII 문자열 검색은 응답 body 전체를 Java `String`으로 복사하지 않고 작은 버퍼로 순차 decode합니다.

Extractor 기본 안전 한도는 decoded body 512 MiB, Beautify 대상 8 MiB입니다. 필요하면 Burp JVM 옵션에서 각각 `burpworkbench.extractor.maxDecodedBytes`, `burpworkbench.extractor.beautifyMemoryBudgetBytes` 시스템 속성으로 조정할 수 있습니다.

메모리를 줄인 데 따른 비용도 있습니다. Target/Proxy는 기존 32회 partition 순회 정책을 유지하고, Extractor는 임시 디스크 I/O가 늘어나며, 결과 개수를 제한하지 않으므로 결과 파일과 경량 metadata가 사용하는 디스크·메모리는 계속 증가할 수 있습니다. 취소 후에는 현재 실행 중인 Burp API 호출이 실제로 반환되어야 다음 검색을 시작할 수 있습니다.

## 설치

프로젝트를 빌드한 뒤 아래 shaded jar를 Burp Suite에 로드합니다.

```text
target\burp-workbench-extension-0.4.6.jar
```

이 단일 shaded jar가 지원하는 모든 Burp 버전의 공통 배포 파일입니다. `target\original-burp-workbench-extension-0.4.6.jar`는 설치하지 마세요. 이 파일은 Maven이 남기는 unshaded backup이며 Brotli/Rhino 같은 번들 런타임 의존성이 포함되지 않습니다.

## 빌드

요구 사항:

- JDK 17 이상
- Maven
- 최초 dependency resolution을 위한 네트워크 접근
- Burp Suite `2025.9.3` 이상 ~ `2026.7.1` 이하

```powershell
mvn test
mvn clean package
```

예상 빌드 산출물:

```text
target\burp-workbench-extension-0.4.6.jar
target\original-burp-workbench-extension-0.4.6.jar
```

Burp에 로드할 배포 산출물은 `target\burp-workbench-extension-0.4.6.jar` 하나뿐입니다.

## 아키텍처

이 프로젝트는 의도적으로 하나의 Maven 프로젝트와 하나의 jar를 유지합니다. Java package로 모듈 경계를 나눕니다.

- `com.burpworkbench.platform`: 모듈 lifecycle과 모듈 간 contract
- `com.burpworkbench.core`: 실제로 공유되는 selection·MIME 정책
- `com.burpworkbench.modules.extractor`: Extractor 모듈 구현
- `com.burpworkbench.modules.search`: Search++ 모듈 구현

의존성 규칙과 확장 지점은 `docs/architecture.md`를 참고하세요.
