# Burp Workbench

Burp Workbench는 공통 core 위에 여러 워크플로우 모듈을 묶은 단일 jar Burp Suite 확장입니다.

현재 버전: `0.5.0`

## 모듈

- `Extractor`: 선택한 Burp HTTP 응답을 manifest, index, summary, 중복 처리, 선택적 JS/JSON Beautify와 정보 태그 필터를 적용해 로컬 파일로 추출합니다.
- `Search++`: Burp HTTP 메시지를 검색하는 탭 기반 고급 검색 창이며, 선택한 결과를 Extractor로 넘겨 추출할 수 있습니다.
- `replace ++`: Proxy 요청·응답 치환과 Forward를 지원합니다. URL/Path 범위, 단축키, 방향별 Burp 기본 미리보기와 프로젝트별 룰 저장을 제공합니다.
- `compare ++`: A/B 각각의 Pretty/Raw/Hex 탭으로 메시지나 텍스트를 좌우 비교합니다. 한글, 차이 탐색과 단축키를 지원합니다.
- `decoder ++`: 작업 탭·선택값 전달·개별 단축키·Light/Dark 테마를 갖춘 Quick/Advanced 라이브 변환 팝업입니다.

## 지원 Burp 버전

Burp Workbench `0.5.0`의 공식 지원 대상 범위는 Burp Suite
`2025.12` 이상부터 `2026.7.1` 이하까지입니다.

| Burp Suite 버전 | Proxy 호환 모드 | 지원 여부 |
|---|---|---|
| `2025.12` 이상 ~ `2026.7.1` 이하 | `HISTORY_ID` | 지원 대상 범위 |
| `2025.12` 미만 | — | 지원하지 않음 |
| `2026.7.1` 초과 | 런타임 기능 자동 탐지 | `0.5.0` 지원 범위 밖 |

지원 범위 안에서는 모두 같은 배포 jar를 사용합니다. 호환 모드는
자동으로 선택되며 사용자가 별도로 설정할 필요가 없습니다. extension은
Montoya API `2025.12`를 기준으로 컴파일하고, Montoya 의존성은
`provided`이므로 배포 jar에 포함하지 않습니다.

이 범위가 중간의 모든 Burp 빌드를 실제 UI에서 검증했다는 뜻은 아닙니다.
`replace ++`와 `compare ++` 하위 탭은 Burp 내부 Swing 배치를 사용하므로 Montoya가
호환되어도 내부 배치 변경으로 모듈을 삽입하지 못할 수 있습니다.
Replace++와 Compare++는 고유한 Proxy 삽입 위치를 찾지 못하면 최상위 탭을 사용합니다. Replace++ 초기화에 성공하면 Proxy 핸들러를 등록합니다. `2026.7.1`보다
최신인 버전은 별도 호환성 확인이 필요합니다.

## 현재 동작 기준

- Search++는 소스를 partition 단위로 처리하고 여러 창의 source scan을 하나씩 실행하며, 검색 취소와 malformed item·정규식 timeout 격리를 지원합니다.
- Filter OFF에서는 Extractor가 응답 body를 순차 처리하고 decoded body를 임시 파일로 streaming하며, Beautify 예산을 넘으면 decoded 원문을 저장합니다. Filter ON은 별도 한도를 적용하고 필터 실패 시 비필터 원문으로 대체 저장하지 않습니다.
- extension unload 시 실행 중인 작업을 취소하고 창·registration·executor를 하나의 lifecycle에서 정리합니다.

Search++ 창은 여러 개 열 수 있지만 실제 source scan은 extension 전체에서 한 번에 하나만 실행됩니다. 결과 개수는 자동으로 제한하지 않습니다. 정규식은 항목당 2초를 넘기면 그 항목만 건너뛰고 결과를 `incomplete`로 표시합니다.

한글 검색은 그대로 지원합니다. 응답에 charset 선언이 있으면 그 charset만 엄격하게 사용합니다. 선언이 없으면 UTF-8 → MS949 → EUC-KR → ISO-8859-1 순서로 검사합니다. 일반 비ASCII 문자열 검색은 응답 body 전체를 Java `String`으로 복사하지 않고 작은 버퍼로 순차 decode합니다.

Extractor 기본 안전 한도는 decoded body 512 MiB, Beautify 대상 8 MiB입니다. 필요하면 Burp JVM 옵션에서 각각 `burpworkbench.extractor.maxDecodedBytes`, `burpworkbench.extractor.beautifyMemoryBudgetBytes` 시스템 속성으로 조정할 수 있습니다.

메모리를 줄인 데 따른 비용도 있습니다. Target/Proxy는 기존 32회 partition 순회 정책을 유지하고, Extractor는 임시 디스크 I/O가 늘어나며, 결과 개수를 제한하지 않으므로 결과 파일과 경량 metadata가 사용하는 디스크·메모리는 계속 증가할 수 있습니다. 취소 후에는 현재 실행 중인 Burp API 호출이 실제로 반환되어야 다음 검색을 시작할 수 있습니다.

## Extractor

최상위 `Extractor` 탭은 필터 규칙을 작성·정렬하는 화면입니다. 추출은 기존 HTTP 우클릭 메뉴나 Search++ 결과에서 시작합니다. 출력 폴더 선택창의 `Beautify` 오른쪽에 `Filter`가 있으며 기본 OFF입니다.

- OFF는 기존 추출 그대로입니다. ON은 저장 전에 본문·메타데이터·파일명에 현재 규칙을 적용합니다. 필터 실패 시 비필터 원문으로 대체 저장하지 않습니다.
- 자동 탐지는 한국 휴대폰·이메일만 제공합니다. 휴대폰은 표기 형식/필드 문맥과 번호 유효성을 함께 보며 숫자 11자리만으로는 판정하지 않습니다. 다른 정보는 직접 규칙이 필요하며 완전한 익명화 기능은 아닙니다.
- 특정 문자열과 `§회사_1§` 같은 자유 태그를 지정합니다. `LITERAL`은 문자열 그대로, `HOST`는 호스트 경계를 대소문자 구분 없이 매치합니다. 범위는 `ALL`·`BODY`·`METADATA`이고 위쪽 직접 규칙이 우선합니다. `§PHONE_1§` 같은 자동 태그는 한 추출 작업 안에서 일관되며 기존 태그와 충돌을 피합니다.
- 규칙·순서·자동 탐지 선택과 미완성 입력도 Burp 프로젝트에 저장합니다. 규칙에 입력한 원문도 저장되므로 프로젝트를 보호하세요. 저장값을 읽거나 규칙을 검증하지 못하면 Filter ON을 막고 기존 저장값을 조용히 초기화하지 않습니다.
- `filter_attributes.json`에는 태그·타입·근거·횟수를 기록하며 민감 원문값은 넣지 않습니다. 태그로 바뀐 JSON scalar는 문자열이 될 수 있습니다. Content-Type에 따라 JSON/HTML/XML/CSV/form/text를 처리하며 Filter 입력·디코딩 한도는 8 MiB입니다. 미지원·잘못된 데이터는 손실 변환 대신 거부합니다.
- `Test file…`로 로컬 원본과 필터 결과를 비교합니다. 네트워크 요청은 보내지 않습니다. 설정하지 않은 정보는 남을 수 있으므로 공유 전에 결과를 확인하세요.

## Workbench 우클릭 메뉴

소유 입력창에는 Cut/Copy/Paste/Select All과 사용 가능한 Replace/Compare/Decoder 전달 메뉴를 제공합니다. 규칙 목록에는 기존 행 조작을 연결합니다. Burp 기본 편집기 메뉴를 보존하며 선택 상태·읽기 전용 여부에 따라 동작을 활성화합니다.

## replace ++

기본 `Match and replace` 바로 오른쪽의 `Proxy > replace ++`에서 엽니다. Replace와 Forward는 독립된 Enabled 설정을 갖습니다. Add·Copy·Remove·Up·Down과 인라인 편집을 제공합니다. Replace 목록/상세 및 Test 좌우 너비는 기본 50:50이고 Match·Replace는 낮은 높이로 시작합니다. 경계와 입력창 크기를 조절하고 상세 섹션을 접을 수 있습니다.

- Type은 Request header/body, Response header/body, Request param name/value, Request first line의 7종입니다. 파라미터 룰은 URL query와 UTF-8 form-urlencoded 필드를 대상으로 하며 JSON 속성, multipart, Cookie는 포함하지 않습니다.
- URL/origin은 HTTP(S) origin과 유효 포트까지 정확히 일치해야 합니다. Path는 query/fragment를 제외한 raw path를 사용합니다. `*`는 `/`를 넘지 않고, `**`는 하위 경로를 포함하며, `/api/**`는 `/api` 자체도 포함합니다. 빈 필드는 해당 조건을 제한하지 않습니다. URL·Path가 모두 비어 있거나 URL이 비어 있고 Path가 `**`이면 모든 origin/path가 대상입니다.
- 활성 룰은 Proxy 메시지를 전달하기 직전에 목록 순서대로 적용됩니다. 새 룰은 OFF로 시작합니다. 범위나 치환 동작을 수정하면 다시 OFF가 되어 직접 켜야 하며 Comment만 수정하면 On을 유지합니다. Copy는 원본의 On 상태도 복사합니다. Burp 기본 Match and replace 룰과는 별도로 동작합니다.
- `Hotkeys`에서 확장 단축키를 지정·변경·해제하며 기본값은 `Ctrl+Shift+Q`입니다. HTTP 메시지를 선택한 상태에서는 탭을 열고 origin과 Path가 채워진 새 OFF 룰을 만듭니다. 사용할 메시지가 없으면 탭만 엽니다. 변경한 단축키는 현재 확장 로드 동안만 유지되며 OS 전역 단축키는 아닙니다.
- 우클릭 `Extensions > Burp Workbench > Send to replace ++`도 새 OFF 룰과 함께 Replace++를 엽니다. 현재 편집기의 요청을 우선하고 목록에서는 첫 선택 요청의 origin/Path를 사용합니다. 포커스된 Request/Response 전체를 Test Preview로 가져오며(최대 1 MiB), 텍스트 선택 범위로 자르지 않습니다. 네트워크 전송은 없으며 유효한 요청 범위가 없으면 메뉴를 비활성화합니다.
- Test는 Burp 기본 요청/응답 편집기와 폰트·구문 강조를 사용합니다. On 상태 및 URL/Path 범위와 무관하게 선택한 룰의 Type·Match·Replace만 미리 확인하며 트래픽은 보내지 않습니다. 계산은 UI 밖에서 수행하고 오래된 작업 결과는 반영하지 않습니다.
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

### Forward

Forward는 Workbench 치환 이후 Proxy 요청의 실제 목적지를 변경합니다. 브라우저 리다이렉트나 추가 요청 전송이 아니므로 주소창·Proxy history에는 Source가 보여도 응답은 Destination에서 올 수 있습니다.

- 입력 배치는 `Source URL | Source Path`, `Destination URL | Destination Path`입니다. Source는 Replace와 같은 origin/path 조건이며 Destination URL은 포트를 포함할 수 있는 HTTP(S) origin입니다. 경로는 Destination Path에 입력합니다.
- 양쪽 Path가 비면 실제 요청 경로를 유지합니다. Destination Path가 비면 실제 경로를 유지하고, 값이 있으면 그 경로로 바꿉니다. Source Path가 비면 모든 경로가 매치됩니다. 원래 raw query는 유지합니다.
- 활성 규칙을 원래 Source URL 기준으로 목록 순서대로 검사하며 마지막 유효한 일치 규칙이 최종 목적지입니다. Workbench Replace 이후 항상 Forward를 적용하여 연결 서비스와 Host를 바꿉니다. 다른 Burp 확장의 실행 순서를 통제하는 기능은 아닙니다.
- Forward 규칙·전체 Enabled는 별도로 프로젝트에 저장합니다. 최초 사용은 빈 목록/OFF이고 새 규칙도 OFF입니다. Test는 목적지 URL만 계산하고 트래픽은 보내지 않습니다.

### 0.5.0 업그레이드

기존 0.4.9 Replace 규칙·순서·On·전체 Enabled를 유지합니다. 독립 데모 규칙은 가져오지 않습니다. 새 Forward는 빈 목록/OFF이고 기존 제품 Decoder 단축키 설정은 유지합니다. 새 JAR 로드 전 이전 확장·독립 데모를 unload하여 중복 등록을 피하세요.

## compare ++

`replace ++` 바로 오른쪽의 `Proxy > compare ++`에서 엽니다. Paste/Load로 텍스트를 가져오거나, HTTP 편집기의 우클릭 메뉴 또는 변경 가능한 기본 단축키 `Ctrl+Shift+W`을 사용합니다. 포커스된 편집기에서 Request/Response를 구분하고 선택 영역이 있으면 해당 byte 구간을 Text로 가져옵니다. History/Site map 목록에서의 단축키 수집은 하지 않습니다. 처음 두 항목은 A/B에 자동 배정하고 이후 가져온 항목은 현재 비교 쌍을 바꾸지 않습니다. 좌우 항목을 선택하면 같은 탭에서 바로 비교합니다.

- Words·유니코드 Characters·정확한 Bytes 비교, A/B 각각의 Pretty/Raw/Hex와 Auto/UTF-8/MS949/ISO-8859-1 디코딩을 지원합니다. Raw/Hex는 원본이고 Hex는 고정폭 글꼴로 byte·ASCII 열을 정렬합니다.
- Pretty는 내장 formatter로 JS/JSON 표시용 복사본을 정리합니다. Pretty/Raw·Hex 혼합 비교는 정리 공백도 차이에 포함하고 양쪽 Pretty는 공백만의 차이를 숨길 수 있습니다. 위치는 각 표현 기준(Pretty는 정리 복사본 UTF-8)이며 HTTP 헤더는 원본 본문을 설명합니다. 미지원/손실 디코딩은 해당 쪽만 Raw로 돌아갑니다.
- Pretty/Raw는 테마 적용 일반 입력창 기본 폰트(Bold 추가 없음), 구문색과 Modified/Deleted/Added 배경색을 사용합니다. Burp native Pretty·Inspector가 아닌 자체 렌더러이며 저장 항목과 Repeater용 원래 요청은 바꾸지 않습니다.
- 항목을 받으면 현재 화면을 유지하고 Compare 탭을 선택할 때까지 주황색으로 표시합니다. 깜빡이지 않습니다. Compare/Replace 핫키를 Workbench 소유 창에서도 처리하며 단축키 설정 입력 중에는 실행하지 않습니다.
- 위쪽 차이 화살표 및 `Alt+Up`/`Alt+Down`은 좌우 차이 구간을 이동합니다. `Find`는 마지막 포커스 본문의 문자열 검색 전용으로, Enter/Shift+Enter와 별도 화살표가 검색 일치 위치를 이동합니다. 빈 Find는 아무 동작도 하지 않으며 검색 화살표가 비활성화됩니다. `Ctrl+F`로 Find에 이동합니다.
- `Differences only`는 원본 bytes를 유지한 채 같은 구간을 숨깁니다. 동기 스크롤, Wrap, 패널 크기 조절, 목록 정렬, Remove/Clear와 비교 취소를 지원합니다.
- 좌우 본문에서 `Ctrl+R`은 해당 항목의 원래 요청을 Repeater에 추가할 뿐 Send하지 않습니다. Response/선택 영역도 가능한 경우 원래 요청과 서비스를 함께 보관합니다. Paste/Load 텍스트는 원래 서비스가 없으므로 목적지를 추측해 보내지 않습니다.
- 수집 목록과 단축키 지정은 현재 확장 로드 동안만 유지하며 프로젝트에 저장하지 않습니다. 입력당1MiB, 전체200개/32MiB로 제한하며 Repeater용 원래 요청도 총량에 포함합니다. 1MiB를 넘는 원래 요청은 Repeater용으로 보관하지 않습니다.

비교는 UI 밖에서 실행하며 작은 차이와 큰 차이에 각각 정확한 비교 경로를 사용합니다. 큰 차이는 선형 보조 메모리로 계산합니다. 4초 diff 계산 예산과 취소 검사를 유지하며 디코딩·화면 렌더링은 이 계산 예산에 포함되지 않습니다. 비교를 끝내지 못하면 원문과 실패 이유를 표시하고, 빈 화면이나 잘못된 '차이 없음' 결과로 처리하지 않습니다. Compare++는 트래픽 핸들러를 등록하거나 Replace++ 룰을 변경하지 않습니다.

## decoder ++

HTTP 편집기 우클릭 `Extensions > Burp Workbench > decode ++`로 엽니다. Burp 최상위 Decoder 탭은 추가하지 않습니다. Quick/Advanced 기본 키는 `Ctrl+1` / `Ctrl+2`이며 `Hotkeys`에서 변경합니다. 저장된 키와 명시적으로 해제한 설정이 기본값보다 우선합니다. 핫키마다 해당 팝업에 새 작업 탭을 만들고 선택 텍스트만 전달하며 선택이 없으면 빈 입력으로 엽니다. Burp/Workbench 창 포커스에서 동작하며 OS 전역 단축키는 아닙니다.

- Quick은 URL·Base·Hex·HTML·Unicode·Hash, Advanced는8개 표현을 라이브 변환합니다. Copy·Swap 및 Advanced 전달, Unicode 접두 표기, percent Hex, Base7종과 Hash12종을 지원합니다.
- 입력·결과는 테마 적용 JTextField 기본 일반 폰트이고 설명 라벨은 Bold입니다. Decoder는 기본 Dark로 시작하며 Hotkeys 오른쪽에서 Burp와 독립적으로 Light/Dark를 고릅니다. Quick 기본은920×680이며 폰트/화면 제약에 맞춰 조정하고 크기 변경을 허용합니다.
- 각 탭은 입력·옵션을 독립 유지합니다. × 또는 Ctrl/Cmd+W로 닫고 +로 빈 탭을 추가합니다. Quick/Advanced는 별도 세션 창이며 탭 닫기/unload 시 작업을 취소합니다.
- 단축키만 Burp preferences에 저장하고 입력·결과는 저장하지 않습니다. 명시적으로 Clear한 키는 재로드 시에도 해제 상태이며 데모 설정은 가져오지 않습니다.
- Advanced Wrap은 Auto/On/Off입니다. Auto는 한 논리 줄이 8,192 UTF-16 코드 단위를 넘으면 줄바꿈을 끕니다. 값을 자르거나 실제 개행을 넣지 않습니다. 큰 입력은 편집 반영을 지연하고 대기 변환은 최신 작업 하나만 유지합니다.
- 로컬 변환만 수행하고 트래픽을 보내지 않습니다. 입력 한도는 1,048,576 UTF-16 코드 단위입니다. 오류는 고정된 상단 행에 표시하며 오류가 발생해도 Advanced는 사용할 수 있습니다.

## 설치

프로젝트를 빌드한 뒤 아래 shaded jar를 Burp Suite에 로드합니다.

```text
target\burp-workbench-extension-0.5.0.jar
```

이 단일 shaded jar가 지원하는 모든 Burp 버전의 공통 배포 파일입니다. `target\original-burp-workbench-extension-0.5.0.jar`는 설치하지 마세요. 이 파일은 Maven이 남기는 unshaded backup이며 Brotli/Rhino 같은 번들 런타임 의존성이 포함되지 않습니다.

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
target\burp-workbench-extension-0.5.0.jar
target\original-burp-workbench-extension-0.5.0.jar
```

Burp에 로드할 배포 산출물은 `target\burp-workbench-extension-0.5.0.jar` 하나뿐입니다.

## 아키텍처

이 프로젝트는 의도적으로 하나의 Maven 프로젝트와 하나의 jar를 유지합니다. Java package로 모듈 경계를 나눕니다.

- `com.burpworkbench.platform`: 모듈 lifecycle과 모듈 간 contract
- `com.burpworkbench.core`: 실제로 공유되는 selection·MIME 정책
- `com.burpworkbench.modules.extractor`: Extractor 모듈 구현
- `com.burpworkbench.modules.search`: Search++ 모듈 구현
- `com.burpworkbench.modules.replace`: replace ++ 모듈 구현
- `com.burpworkbench.modules.compare`: compare ++ 모듈 구현
- `com.burpworkbench.modules.decoder`: decoder ++ 모듈 구현

의존성 규칙과 확장 지점은 `docs/architecture.md`를 참고하세요.
