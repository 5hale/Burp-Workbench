# Burp Workbench

Burp Workbench는 공통 core 위에 여러 워크플로우 모듈을 묶은 단일 jar Burp Suite 확장입니다.

현재 버전: `0.5.1`

## 모듈

- `Extractor`: 선택한 Burp HTTP 응답을 manifest, index, summary, 중복 처리, 선택적 JS/JSON Beautify와 정보 태그 필터를 적용해 로컬 파일로 추출합니다.
- `Search++`: Burp HTTP 메시지를 검색하는 탭 기반 고급 검색 창이며, 선택한 결과를 Extractor로 넘겨 추출할 수 있습니다.
- `replace ++`: Proxy 요청·응답 치환과 Forward를 지원합니다. URL/Path 범위, 단축키, 방향별 Burp 기본 미리보기와 프로젝트별 룰 저장을 제공합니다.
- `compare ++`: A/B 각각의 Pretty/Raw/Hex 탭으로 메시지나 텍스트를 좌우 비교합니다. 한글, 차이 탐색과 단축키를 지원합니다.
- `decoder ++`: 작업 탭·선택값 전달·개별 단축키·Light/Dark 테마를 갖춘 Quick/Advanced 라이브 변환 팝업입니다.

## 지원 Burp 버전

Burp Workbench `0.5.1`의 공식 지원 대상 범위는 Burp Suite
`2025.12` 이상부터 `2026.7.1` 이하까지입니다.

| Burp Suite 버전 | Proxy 호환 모드 | 지원 여부 |
|---|---|---|
| `2025.12` 이상 ~ `2026.7.1` 이하 | `HISTORY_ID` | 지원 대상 범위 |
| `2025.12` 미만 | — | 지원하지 않음 |
| `2026.7.1` 초과 | 런타임 기능 자동 탐지 | `0.5.1` 지원 범위 밖 |

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

최상위 `Extractor` 탭은 필터 규칙 관리 화면입니다. 추출은 기존 HTTP 우클릭 메뉴나 Search++ 결과에서 시작하며 폴더 선택창의 Beautify 옆 Filter는 기본 OFF입니다.

- 새 프로젝트의 규칙 목록은 비어 있습니다. `Rulesets…`에서 원하는 템플릿만 선택 추가하고 수정/Copy합니다. 카테고리·검색은 규칙 관리용이지 개인정보 자동 분류가 아닙니다. 현재 룰셋은 초기 regex 모음이며 참고 도구의 전체 탐지 엔진과 같지 않습니다.
- 기본 입력은 Name·Category·Regex·Pattern·Tag·자유 Description입니다. 접힌 Advanced에는 문자열/도메인 매치, Body/Headers/All 범위, 원문/파싱된 값, 캡처 그룹, 태그 번호, Content-Type·필드명·제외 regex 조건을 제공합니다. 그룹0은 전체 매치, 1이상은 해당 캡처 값만 치환합니다.
- Ctrl/Shift 다중 선택으로 Copy/Remove/Up/Down을 일괄 처리합니다. 중앙 분할에 따라 목록 열 너비가 조절되고 Hide details는 선택·필터·입력값을 유지합니다. 규칙·순서·미완성 초안과 입력한 원문도 프로젝트에 저장하므로 프로젝트를 보호하세요.
- Filter OFF는 기존 추출 그대로입니다. ON은 활성 규칙만 본문·메타데이터·파일명에 적용하며 실패 시 비필터 원문으로 대체 저장하지 않습니다. regex의 오탐·누락이 가능하며 완전한 익명화 기능이 아니므로 공유 전 결과를 확인하세요.
- `§EMAIL_1§` 같은 번호 태그는 추출 작업 안에서 일관됩니다. 태그로 바뀐 JSON scalar는 문자열이 될 수 있습니다. `filter_attributes.json`에는 태그·카테고리·원래 타입·규칙ID·횟수를 기록하고 민감 원문은 넣지 않습니다. JSON/HTML/XML/CSV/form/text/HTTP 입력·디코딩 한도는8 MiB입니다.
- `Test rules…`에서 파일/붙여넣기 입력을 검사하며 네트워크 요청은 보내지 않습니다. Input/Filtered 기본 Wrap, 입력 Ctrl+Z/Redo, 결과 태그 강조와 ‹/›·F3/Shift+F3·매치 행 선택으로 커서 이동을 제공합니다. Start/End는 매치 값/문서 내부 좌표이며 출력 절대 좌표가 아닙니다. 원문에 이미 같은 태그가 있으면 모호한 이동은 생략합니다.
- 기존0.5.0 직접 규칙과 저장한 휴대폰/이메일 선택을 새 목록의 보이는 규칙으로 읽고 옛 저장값을 보존합니다. 탐지 선택은 수정 가능한 regex 템플릿이 되므로 이전 라이브러리 검증과 동일하다고 가정하지 마세요. 데모 설정은 가져오지 않으며 손상/외부 변경 저장값을 조용히 덮어쓰지 않습니다.

## Workbench 우클릭 메뉴

소유 입력창에는 Cut/Copy/Paste/Select All과 사용 가능한 Replace/Compare/Decoder 전달 메뉴를 제공합니다. 규칙 목록에는 기존 행 조작을 연결합니다. Burp 기본 편집기 메뉴를 보존하며 선택 상태·읽기 전용 여부에 따라 동작을 활성화합니다.

## replace ++

기본 Match and replace 옆 `Proxy > replace ++`에서 엽니다. 관리 버튼은 툴팁이 있는 아이콘이며 Ctrl/Shift 다중 선택으로 Copy/Remove/Up/Down을 일괄 처리합니다. 목록 열은 중앙 분할 폭을 따르고 Hide details는 작업 배치를 유지합니다.

- 전체 Enabled 버튼은 제거했습니다. 정상 세션은 상시 활성이고 실제 적용은 개별 규칙 On으로 관리합니다. 새 규칙은 OFF입니다. 정상 편집은 기존 ON/OFF를 유지하고 오류 초안도 편집은 허용하되 규칙/페이지에서 떠날 때 OFF 처리합니다. 원래 OFF는 자동 ON되지 않습니다. 텍스트 입력에 Ctrl+Z/Redo를 제공합니다.
- 치환 매치는 기본 대소문자 구분 없이 동작하며 Match case를 켜면 Literal/Regex 모두 대소문자를 구분합니다. URL/origin·Path 조건 정책은 별개입니다.
- 요청/응답 첫 줄·헤더·본문 및 요청 query/form 파라미터를 지원합니다. 요청 헤더는 요청 첫 줄을 제외하며 Response first line은 헤더/본문을 건드리지 않고 HTTP 버전·상태·문구를 바꿉니다. 전체 바이너리·임의 구조 파라미터·multipart·trailer 치환은 제공하지 않습니다.
- Origin은 정확한 `http(s)://host[:port]`, Path는 query를 제외한 raw path glob입니다. `*`는 segment 안, `**`는 segment를 넘으며 빈 조건은 제한 없음입니다.
- Test Preview는 방향에 맞는 Burp 기본 편집기로 샘플/결과를 보여주며 트래픽은 보내지 않습니다. 좌우 너비는 기본 동일하고 규칙 입력창은 낮게 시작합니다. 기본 Ctrl+Shift+Q 범위 수집은 새 OFF 규칙과 포커스된 전체 요청/응답 샘플을 가져옵니다.
- 규칙·순서·개별 On·Match case는 프로젝트에 저장합니다. schema1은 case-ignore로 읽고 schema2는 명시 플래그 및 이전 백업을 유지합니다. Test 샘플/핫키 변경은 룰 저장 대상이 아니며 손상/외부 변경 저장값은 조용히 초기화하지 않습니다.
- Proxy 전송 직전 목록 순서로 처리합니다. 불량/예산 초과 규칙의 부분 변경은 반영하지 않습니다. 안전 한도는 메시지/출력8 MiB, 디코딩 text16 MiB, 헤더256 KiB, 적용 규칙1,000개, 규칙250ms·엔진 합계1초이며 범위 preflight는 별도250ms입니다. Query/form은UTF-8, 본문은 선언 charset과gzip/deflate를 유지하며 수정된Brotli 본문은content coding 없이 내보냅니다.

### Forward

Source URL | Source Path, Destination URL | Destination Path 두 줄로 입력합니다. Forward는 Replace 후 항상 마지막에 적용하며 목록 순서에서 마지막 유효 매치가 우선합니다. HttpService·Host와 선택적 Path를 변경하고 raw query는 보존합니다. 브라우저 주소창 변경이나 별도 요청 전송이 아닙니다.

Source URL은 정확한 origin입니다. Destination Path가 비면 실제 요청 경로를 유지하고 Source Path가 비면 경로 조건에 제한이 없습니다. 둘 다 비면 매치된 요청 경로를 유지합니다. 최초 목록과 새 규칙은 OFF이며 Test는 목적지만 계산합니다.

### 0.5.1 업그레이드

기존 제품 Replace/Forward 규칙·개별 On은 이어서 사용합니다. 이전 전체 Enabled는 정상 세션을 더 이상 비활성화하지 않습니다. 데모 규칙은 가져오지 않습니다. Extractor의 기존 선택 읽기 방식은 위 설명을 따릅니다. 중복 핸들러를 피하려면 새 JAR 로드 전 이전 확장/독립 데모를 unload하세요.

## compare ++

Replace++ 옆 `Proxy > compare ++`에서 엽니다. HTTP 편집기 우클릭/기본 Ctrl+Shift+W로 수집하며 선택 byte는 Text로 가져옵니다. Paste/Load와 `+` 빈 항목도 지원합니다. 처음 두 수집은 A/B 자동 지정, +는 빈 쪽부터 사용하고 양쪽이 있으면 마지막 활성 쪽(초기A)에 새 항목을 지정합니다.

- A/B 각각 Pretty/Raw/Hex·인코딩·내용을 설정하고 한쪽만 있어도 보기/설정/Find가 됩니다. Raw 전체 보기에는 Ctrl+Z/Redo 편집을 제공합니다. Pretty/Hex/Differences only와 손실 디코딩은 읽기 전용입니다. 같은 항목도 A/B 수정본은 독립이며 원본 byte를 보존합니다. ↶는 해당 쪽 원본 복구입니다.
- 편집250ms 후 차이를 갱신하고 커서/Undo를 유지합니다. Words·Unicode Characters·Bytes, 정확한 차이, Sync·Wrap·Differences only와 문자열 Find를 유지합니다. 빈 Find는 차이 이동을 하지 않으며 Alt+Up/Down은 차이, Enter/Shift+Enter는 검색 결과로 이동합니다.
- Items 목록은 Ctrl/Shift 일괄 Copy/Remove, 열 너비 조절, 기존 표시/숨기기를 지원합니다. Copy는 작업 내용을 사용하며 명시적 Repeater(Ctrl+R)는 수집한 원본 요청을 사용합니다. 수집/비교가 네트워크 Send를 수행하지 않습니다.
- 한도는 항목1 MiB·200개·전체32 MiB(원본 요청/수정본 포함)입니다. Compare는 프로젝트에 저장하지 않고 unload 때 해제합니다.
- 새 수집은 포커스를 빼앗거나 반짝이지 않고 선택 전까지 탭을 주황색으로 표시합니다. Replace 미리보기의 Burp 기본 Pretty/Raw/Hex는 그대로 유지합니다.

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
target\burp-workbench-extension-0.5.1.jar
```

이 단일 shaded jar가 지원하는 모든 Burp 버전의 공통 배포 파일입니다. `target\original-burp-workbench-extension-0.5.1.jar`는 설치하지 마세요. 이 파일은 Maven이 남기는 unshaded backup이며 Brotli/Rhino 같은 번들 런타임 의존성이 포함되지 않습니다.

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
target\burp-workbench-extension-0.5.1.jar
target\original-burp-workbench-extension-0.5.1.jar
```

Burp에 로드할 배포 산출물은 `target\burp-workbench-extension-0.5.1.jar` 하나뿐입니다.

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
