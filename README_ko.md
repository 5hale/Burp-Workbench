# Burp Workbench

Burp Workbench는 공통 core 위에 여러 워크플로우 모듈을 묶은 단일 jar Burp Suite 확장입니다.

현재 버전: `0.4.7`

## 모듈

- `Extractor`: 선택한 Burp HTTP 응답을 manifest, index, summary, 중복 처리, 선택적 JS/JSON beautify와 함께 로컬 파일로 추출합니다.
- `Search++`: Burp HTTP 메시지를 검색하는 탭 기반 고급 검색 창이며, 선택한 결과를 Extractor로 넘겨 추출할 수 있습니다.
- `replace ++`: 한글, URL/Path 범위, 단축키, Burp 기본 메시지 미리보기를 지원하는 Proxy 요청·응답 치환 모듈입니다. 룰 순서와 설정을 프로젝트별로 저장합니다.

## 지원 Burp 버전

Burp Workbench `0.4.7`의 공식 지원 대상 범위는 Burp Suite
`2025.12` 이상부터 `2026.7.1` 이하까지입니다.

| Burp Suite 버전 | Proxy 호환 모드 | 지원 여부 |
|---|---|---|
| `2025.12` 이상 ~ `2026.7.1` 이하 | `HISTORY_ID` | 지원 대상 범위 |
| `2025.12` 미만 | — | 지원하지 않음 |
| `2026.7.1` 초과 | 런타임 기능 자동 탐지 | `0.4.7` 지원 범위 밖 |

지원 범위 안에서는 모두 같은 배포 jar를 사용합니다. 호환 모드는
자동으로 선택되며 사용자가 별도로 설정할 필요가 없습니다. extension은
Montoya API `2025.12`를 기준으로 컴파일하고, Montoya 의존성은
`provided`이므로 배포 jar에 포함하지 않습니다.

이 범위가 중간의 모든 Burp 빌드를 실제 UI에서 검증했다는 뜻은 아닙니다.
`replace ++` 하위 탭은 Burp 내부 Swing 배치를 사용하므로 Montoya가
호환되어도 내부 배치 변경으로 모듈을 삽입하지 못할 수 있습니다.
탭 삽입에 성공한 뒤에만 트래픽 치환을 시작합니다. `2026.7.1`보다
최신인 버전은 별도 호환성 확인이 필요합니다.

## 현재 동작 기준

- Search++는 소스를 partition 단위로 처리하고 여러 창의 source scan을 하나씩 실행하며, 검색 취소와 malformed item·정규식 timeout 격리를 지원합니다.
- Extractor는 응답 body를 순차 처리하고 decoded body를 임시 파일로 streaming하며, Beautify 메모리 예산을 넘으면 decoded 원문을 그대로 저장합니다.
- extension unload 시 실행 중인 작업을 취소하고 창·registration·executor를 하나의 lifecycle에서 정리합니다.

Search++ 창은 여러 개 열 수 있지만 실제 source scan은 extension 전체에서 한 번에 하나만 실행됩니다. 결과 개수는 자동으로 제한하지 않습니다. 정규식은 항목당 2초를 넘기면 그 항목만 건너뛰고 결과를 `incomplete`로 표시합니다.

한글 검색은 그대로 지원합니다. 응답에 charset 선언이 있으면 그 charset만 엄격하게 사용합니다. 선언이 없으면 UTF-8 → MS949 → EUC-KR → ISO-8859-1 순서로 검사합니다. 일반 비ASCII 문자열 검색은 응답 body 전체를 Java `String`으로 복사하지 않고 작은 버퍼로 순차 decode합니다.

Extractor 기본 안전 한도는 decoded body 512 MiB, Beautify 대상 8 MiB입니다. 필요하면 Burp JVM 옵션에서 각각 `burpworkbench.extractor.maxDecodedBytes`, `burpworkbench.extractor.beautifyMemoryBudgetBytes` 시스템 속성으로 조정할 수 있습니다.

메모리를 줄인 데 따른 비용도 있습니다. Target/Proxy는 기존 32회 partition 순회 정책을 유지하고, Extractor는 임시 디스크 I/O가 늘어나며, 결과 개수를 제한하지 않으므로 결과 파일과 경량 metadata가 사용하는 디스크·메모리는 계속 증가할 수 있습니다. 취소 후에는 현재 실행 중인 Burp API 호출이 실제로 반환되어야 다음 검색을 시작할 수 있습니다.

## replace ++

기본 `Match and replace` 바로 오른쪽의 `Proxy > replace ++`에서 엽니다. Add·Copy·Remove·Up·Down과 인라인 상세 편집을 제공합니다. 목록/상세 경계, Match·Replace 입력창, Test 편집기의 크기를 조절하고 상세 섹션을 접을 수 있습니다.

- Type은 Request header/body, Response header/body, Request param name/value, Request first line의 7종입니다. 파라미터 룰은 URL query와 UTF-8 form-urlencoded 필드를 대상으로 하며 JSON 속성, multipart, Cookie는 포함하지 않습니다.
- URL/origin은 HTTP(S) origin과 유효 포트까지 정확히 일치해야 합니다. Path는 query/fragment를 제외한 raw path를 사용합니다. `*`는 `/`를 넘지 않고, `**`는 하위 경로를 포함하며, `/api/**`는 `/api` 자체도 포함합니다. 빈 필드는 해당 조건을 제한하지 않습니다. URL·Path가 모두 비어 있거나 URL이 비어 있고 Path가 `**`이면 모든 origin/path가 대상입니다.
- 활성 룰은 Proxy 메시지를 전달하기 직전에 목록 순서대로 적용됩니다. 새 룰은 OFF로 시작합니다. 범위나 치환 동작을 수정하면 다시 OFF가 되어 직접 켜야 하며 Comment만 수정하면 On을 유지합니다. Copy는 원본의 On 상태도 복사합니다. Burp 기본 Match and replace 룰과는 별도로 동작합니다.
- `Hotkeys`에서 확장 단축키를 지정·변경·해제하며 기본값은 `Ctrl+Shift+9`입니다. HTTP 메시지를 선택한 상태에서는 탭을 열고 origin과 Path가 채워진 새 OFF 룰을 만듭니다. 사용할 메시지가 없으면 탭만 엽니다. 변경한 단축키는 현재 확장 로드 동안만 유지되며 OS 전역 단축키는 아닙니다.
- Test는 Burp 기본 요청/응답 편집기와 폰트·구문 강조를 사용합니다. On 상태 및 URL/Path 범위와 무관하게 선택한 룰의 Type·Match·Replace만 미리 확인하며 트래픽은 보내지 않습니다.
- 룰 필드·순서·개별 On·전체 Enabled는 현재 프로젝트의 extension data에 자동 저장합니다. Test 샘플과 단축키 지정은 저장하지 않습니다. Burp 재시작 후에도 유지하려면 저장 프로젝트를 사용해야 하며 임시 프로젝트는 보존되지 않을 수 있습니다. 잘못되거나 외부에서 변경된 저장 데이터를 조용히 덮어쓰지 않습니다.

텍스트 body 치환은 선언된 charset을 사용하고, 선언이 없으면 UTF-8을 사용합니다. gzip/deflate body를 지원하며 Brotli body가 변경되면 압축을 풀어 전달합니다. 지원하지 않거나 잘못된 인코딩은 손실 변환하지 않고 건너뜁니다. Proxy 메시지/출력은 8 MiB, decoded text는 16 MiB, 헤더는 256 KiB로 제한하며 룰별·메시지별 시간 예산도 적용합니다. 잘못되거나 예산을 초과한 룰은 그 룰의 부분 변경을 반영하지 않고 건너뛰며, 앞서 성공한 변경은 유지될 수 있습니다. 무제한 바이너리 치환 기능은 아닙니다.

저장된 룰이 없는 최초 사용 시 헤더 제거용 기본 룰 6개를 제공합니다. 모두 `Request header`, Regex, 빈 Replace 및 **OFF** 상태이며 URL/Path는 제한하지 않습니다. 필요한 룰에만 범위를 설정하고 On을 켜세요.

| 기본 룰 | Match |
|---|---|
| If-Modified-Since 제거 | `(?im)^If-Modified-Since.*$` |
| If-None-Match 제거 | `(?im)^If-None-Match.*$` |
| Sec-CH 계열 제거 | `(?im)(s|S)ec-(c|C)h.*` |
| Sec-Fetch 계열 제거 | `(?im)(s|S)ec-(f|F)etch.*` |
| Cache-Control 제거 (선택) | `(?im)^Cache-Control:.*$` |
| Pragma 제거 (선택) | `(?im)^Pragma:.*$` |

기본 패턴의 `(?im)`은 대소문자를 무시하고 `^`/`$`를 각 헤더 줄 기준으로 적용합니다. 기존 사용자 룰의 정규식 동작은 변경하지 않습니다. 캐시 지시나 브라우저 메타데이터를 제거하면 캐시·서버 검사 동작이 달라질 수 있습니다. Cookie·Authorization·Origin·Referer는 기본 룰로 제거하지 않습니다. 이미 저장한 룰은 빈 목록도 그대로 복원하며 기본 룰을 덧붙이거나, 지운 룰을 다시 생성하지 않습니다.

## 설치

프로젝트를 빌드한 뒤 아래 shaded jar를 Burp Suite에 로드합니다.

```text
target\burp-workbench-extension-0.4.7.jar
```

이 단일 shaded jar가 지원하는 모든 Burp 버전의 공통 배포 파일입니다. `target\original-burp-workbench-extension-0.4.7.jar`는 설치하지 마세요. 이 파일은 Maven이 남기는 unshaded backup이며 Brotli/Rhino 같은 번들 런타임 의존성이 포함되지 않습니다.

## 빌드

요구 사항:

- JDK 17 이상
- Maven
- 최초 dependency resolution을 위한 네트워크 접근
- Burp Suite `2025.12` 이상 ~ `2026.7.1` 이하

```powershell
mvn package
```

예상 빌드 산출물:

```text
target\burp-workbench-extension-0.4.7.jar
target\original-burp-workbench-extension-0.4.7.jar
```

Burp에 로드할 배포 산출물은 `target\burp-workbench-extension-0.4.7.jar` 하나뿐입니다.

## 아키텍처

이 프로젝트는 의도적으로 하나의 Maven 프로젝트와 하나의 jar를 유지합니다. Java package로 모듈 경계를 나눕니다.

- `com.burpworkbench.platform`: 모듈 lifecycle과 모듈 간 contract
- `com.burpworkbench.core`: 실제로 공유되는 selection·MIME 정책
- `com.burpworkbench.modules.extractor`: Extractor 모듈 구현
- `com.burpworkbench.modules.search`: Search++ 모듈 구현
- `com.burpworkbench.modules.replace`: replace ++ 모듈 구현

의존성 규칙과 확장 지점은 `docs/architecture.md`를 참고하세요.
