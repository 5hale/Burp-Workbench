# Burp Workbench 0.4.4 리팩토링 검증 보고서

- 검증일: 2026-07-27
- 기준 계획: `docs/refactoring-0.4.4-plan.md`
- 사전 분석: `docs/refactoring-0.4.4-analysis.md`
- 대상 버전: `0.4.4`
- Git 작업: 초기화·commit·reset을 수행하지 않음

## 1. 결론

0.4.4 계획의 구현 항목은 완료됐다. 최신 소스 기준 자동 테스트는 162개를 발견해 161개 성공, 실패 0, Windows에서 성립하지 않는 대소문자 구분 파일시스템 조건 테스트 1개가 중단됐다. 두 서브 에이전트의 독립 감사 후 발견된 취소·출력 호환성 경계 조건도 수정하고 다시 검증했다.

핵심 결과는 다음과 같다.

- Search++가 Target/Proxy 전체 결과를 한 번에 보관하던 경로를 제거하고 기존 32분할 정책 안에서 필터링된 partition만 처리한다.
- 두 번째 검색은 앞 검색의 실제 scan thread가 종료되고 전역 permit이 반환되기 전에는 시작되지 않는다.
- 일반 비ASCII·한글 문자열 검색은 body 전체를 `byte[]`와 `String`으로 복제하지 않고 8 KiB decoder buffer로 순차 검사한다.
- Extractor는 현재 처리 중인 raw body 하나만 heap에 올리고 decoded body는 임시 파일로 순차 처리한다.
- 취소·OOM·일반 fatal 경로에서 가능한 범위의 manifest, index, summary와 구조화 진단을 보존한다.
- 현재 정책인 결과 무제한, 32분할, source별 중복 기준, 출력 schema와 경로 규칙은 유지했다.

## 2. 구현 결과

### 2.1 Search++

- `SearchExecutionCoordinator`가 extension 전체에서 source scan 하나만 허용한다. 대기 queue나 기존 검색 자동 취소는 없다.
- 검색 버튼은 실제 scan 동안 X로 바뀐다. 취소 요청 후 Burp API 호출이 반환하고 scan thread가 분리될 때까지 X 상태와 permit을 유지한다.
- Target와 Proxy는 기존 32개 hash partition을 유지하되, 검색·scope 조건을 Montoya filter 안에서 적용해 각 호출이 전체 source list를 반환하지 않게 했다.
- 결과 본문은 Montoya temp-file-backed message를 사용한다. table/filter view는 결과 객체를 다시 보관하지 않고 primitive index를 사용한다.
- extension·negative filter 입력은 200 ms debounce하고, filter executor는 실행 1개와 대기 snapshot 1개만 허용한다. 취소된 snapshot은 queue에서 제거한다.
- 한글은 계속 지원한다.
  - charset 선언이 있으면 해당 charset만 strict decode한다.
  - 선언이 없으면 UTF-8 → MS949 → EUC-KR → ISO-8859-1 순서로 검사한다.
  - 일반 비ASCII literal은 streaming decode한다.
  - ASCII literal과 HEX는 byte/native 경로를 유지한다.
- regex는 항목당 2초 제한을 적용한다. timeout 항목만 건너뛰고 검색을 계속하며 최종 결과를 `incomplete`로 표시한다.
- 실제 OOM은 자동 retry하지 않고 현재 run을 한 번 종료하며 이미 반영된 부분 결과를 유지한다.
- malformed item 상세 로그는 처음 5개만 남기고 이후에는 합계로 제한한다.
- 창·탭·worker·filter task·result/table/preview/context/repeater reference를 unload에서 해제한다.

### 2.2 Extractor

- 선택 범위 해석을 run directory와 terminal writer 생성 이후로 이동했다. 선택 단계 취소나 OOM도 summary를 남길 수 있다.
- subtree 선택은 Montoya의 scope-filtered Site Map batch를 사용한다. filter predicate는 순수 scope 판정만 수행한다.
- 반환 batch는 원래 순서대로 stable dedupe한다. `method + URL` 문자열을 새로 연결하지 않고 composite key를 사용하며, 두 번째 full reference list 대신 primitive index view를 사용한다.
- 각 응답은 순차 처리한다.
  - 현재 raw body 하나 취득
  - gzip/deflate/br decode 결과를 임시 파일에 기록
  - decoded SHA-256 계산
  - first occurrence 기준 중복 판정
  - 즉시 저장 및 manifest/index 기록
  - 현재 decoded temp file 해제
- duplicate index에는 body 대신 SHA-256, 최초 상대 경로와 URL만 남긴다.
- HTML index는 row spool에 streaming한 뒤 최종 파일을 조립한다. 전체 HTML을 하나의 `StringBuilder`로 만들지 않는다.
- output collision 검사는 trie와 next-suffix hint를 사용해 반복 충돌의 선형 재탐색을 제거했다. 기존 `__2`, `__3` 경로 정책은 유지한다.
- JSON Beautify는 출력 byte budget을 지키는 bounded builder와 단계별 취소 검사를 사용한다.
- JavaScript Beautify는 보수적 preflight, Rhino instruction observer, 취소 검사와 최종 UTF-8 출력 budget 검사를 적용한다.
- Beautify 예산을 넘거나 실패하면 `beautify_failed`를 기록하고 decoded 원문을 원래 경로에 저장한다.
- 기본 decoded body 제한은 512 MiB, Beautify budget은 8 MiB다.
- 정상 UI 취소와 unload는 cooperative cancellation token을 사용해 metadata writer를 interrupt로 손상시키지 않는다.
- 외부 interrupt나 copy 중 취소도 terminal 기록 동안 interrupt를 일시 해제한 뒤 원래 interrupt 상태를 복원한다.
- cancellation row 기록 이후 manifest/index close가 실패하면 성공한 취소로 숨기지 않고 fatal로 유지한다.
- writer 생성 실패, decode 실패, 취소, Error 경로에서 소유한 임시 파일을 정리한다.
- fatal·취소 진단에는 phase, processed/total, heap, saved/skipped/duplicate/failed 합계를 기록한다. 정상 summary 파일 형식에는 `Errors:`나 내부 진단을 추가하지 않는다.

### 2.3 구조와 lifecycle

- `BurpWorkbenchExtension`이 Extractor와 Search++를 명시적으로 연결한다.
- Search++는 Extractor 구현을 import하지 않고 `ExtractionHandler` contract만 사용한다.
- 기능별 HTTP model, codec, filter, hashing과 출력 구현은 각 모듈 package 안으로 이동했다.
- production top-level public type은 entry point, platform/module boundary, 실제 공유 selection/MIME 정책으로 제한한 11개다.
- startup 실패 rollback과 unload는 `ModuleLifetime`/`ModuleRegistry`의 역순 cleanup을 사용한다.
- 사용하지 않는 legacy dialog, Extractor filter/planner branch, helper·overload를 제거했다.

## 3. 유지한 동작

- Search++ 탭, 검색/X 버튼, preview, context action과 결과 선택 방식
- Target, Proxy, Repeater, Organizer, Context source 선택
- request/response headers/body, TEXT/HEX/regex/case/negative 검색
- UTF-8, MS949, EUC-KR 한글과 한글·ASCII 혼합 검색
- 기존 32분할 traversal과 source별 중복 기준
- 결과 개수 무제한 정책
- Extractor 선택 순서와 decoded SHA-256 first occurrence 우선
- gzip/deflate/br, JS/JSON Beautify, URL 경로 mapping
- manifest/index/summary 파일명·주요 schema·정상 summary 형식
- no-response, duplicate, failed, beautify_failed, cancelled 기록

## 4. 좋아진 점

- Search++는 각 partition에서 검색에 맞는 batch만 반환받아 전체 Target/Proxy list 적재로 인한 수 GiB 급증 경로를 제거했다.
- 같은 창에서 재검색할 때 이전 worker와 source scan이 겹치지 않는다.
- 한글 literal 검색은 전체 body 복사 없이 순차 decode하므로 큰 단일 응답의 순간 heap 사용도 줄었다.
- filter 입력 중 매 keystroke마다 큰 snapshot을 queue에 쌓지 않는다.
- Extractor peak heap은 전체 응답 body 합계가 아니라 Montoya filtered batch reference와 현재 처리 중인 raw body 크기에 주로 비례한다.
- decoded 확장 데이터와 index row는 disk-backed이므로 압축 해제 결과와 전체 HTML이 heap에 누적되지 않는다.
- 취소·OOM·일반 fatal을 분리해 partial 결과와 원 예외를 더 일관되게 보존한다.
- module 내부 구현의 public surface와 모듈 간 결합이 줄어 0.5 이후 확장 시 변경 범위가 작아졌다.

## 5. 부작용과 남은 한계

- Target/Proxy는 기존 정책대로 source를 32회 순회한다. heap은 줄지만 Burp API 호출과 CPU 비용은 유지된다.
- Montoya API가 list를 반환하므로 Search++에는 partition batch 하나, Extractor subtree에는 scope-filtered batch 하나가 메모리에 존재한다.
- 검색 결과를 자동 제한하지 않으므로 많은 실제 match는 temp file, disk 공간과 경량 result metadata를 계속 사용한다.
- regex는 의미 보존을 위해 현재 항목 body를 byte array와 decoded `String`으로 만들 수 있다. 매우 큰 단일 응답은 순간 메모리를 사용할 수 있고 timeout 결과는 불완전할 수 있다.
- 잘못 선언된 charset에도 다른 charset으로 fallback하지 않는다. 선언 charset을 authoritative하게 본다는 확정 정책의 결과다.
- extension·negative filter 반영에는 최대 약 200 ms debounce 지연이 있다.
- debounce·탭 전환·stale chunk의 개별 controller와 plan은 자동 검증했지만 실제 `SearchPlusDialog`를 띄운 전체 Swing event 조합은 실 Burp 수동 확인 대상으로 남는다.
- 취소는 진행 중인 Burp native/API 호출을 강제 종료하지 않는다. 호출이 반환할 때까지 검색 X 또는 Extractor background 작업이 남을 수 있다.
- Extractor는 decoded temp file 때문에 disk I/O와 임시 disk 사용량이 증가한다.
- 512 MiB decode 제한을 넘는 항목은 failed로 기록된다. 제한을 높이면 disk와 처리 시간이 증가한다.
- JS preflight는 보수적이다. 예산 경계의 정상 입력도 Beautify를 생략하고 원문 fallback할 수 있다. Rhino 결과 문자열은 라이브러리 특성상 생성 후 최종 크기를 확인하므로 극단적인 입력의 순간 메모리 증가 가능성은 남는다.
- 취소·실패한 Extractor run은 의도적으로 partial 출력 디렉터리를 보존한다.
- unload 시 5초 안에 끝나지 않는 interrupt 무시 native 작업은 강제 중단하지 않고 timeout 로그를 남긴다.

## 6. 자동 검증

로컬 환경에는 Maven 실행 파일이 없어 JDK 21의 `javac --release 17`과 로컬 Maven dependency cache를 사용하는 `.work/verify-local.ps1`로 동일 소스와 테스트를 검증했다.

```text
containers found:      24
containers successful: 24
tests found:           162
tests started:         162
tests successful:      161
tests failed:          0
tests aborted:         1
```

중단된 1개는 Windows `Path`가 대소문자를 구분하지 않을 때 실행하지 않는 `OutputPathAllocatorTest.preservesCaseDistinctPathsOnCaseSensitiveProviders`다.

주요 회귀 검증 범위:

- UTF-8/MS949/EUC-KR, 한글·ASCII 혼합, decoder buffer 경계, malformed trailing byte
- Search 전역 permit, 실제 thread detach 전 재검색 차단, stale callback와 terminal outcome
- 32 partition, source filter, dedupe, cancellation, malformed item, regex timeout
- filter queue 제한, canonical result/index view와 table model
- Extractor 순서, decoded-body 중복, no-response, 압축 decode와 decode limit
- selection 단계 취소/OOM terminal 파일
- cancel 및 interrupt-during-copy terminalization과 interrupt 복원
- metadata close 실패를 fatal로 유지하는 두 경계 조건
- Beautify output/Rhino execution budget과 원문 fallback
- temp writer 생성 실패 cleanup, index streaming, output collision 10,000회
- 정상 summary exact 형식, fatal summary와 Burp 구조화 로그
- module startup rollback, reverse unload와 resource cleanup

컴파일 중 두 비차단 진단이 있었다.

- Rhino 사용부의 deprecated API 경고
- 제한된 Windows sandbox에서 JDK zipfs가 dependency JAR filesystem을 닫을 때 출력한 `AccessDeniedException`

두 경우 모두 compiler exit code와 test launcher exit code는 0이었고 class 생성과 전체 테스트가 완료됐다.

## 7. JAR 검증

최종 산출물:

```text
target\burp-workbench-extension-0.4.4.jar
size: 1,632,928 bytes
entries: 644
SHA-256: 5F3E9F5C555CAE111BC4F3E905F00C0105B5FC5D548B7E984BEBB5E5D79DD392
```

검사 결과:

- `Implementation-Version: 0.4.4`
- `com.burpworkbench.BurpWorkbenchExtension` entry point 존재
- `META-INF/services/burp.api.montoya.BurpExtension` 내용 정상
- Rhino와 Brotli runtime 포함
- provided dependency인 Montoya API class 제외
- `module-info.class` 제외
- 중복 archive entry 0

## 8. 실 Burp 수동 확인 항목

다음 항목은 실제 Burp 데이터와 UI가 필요한 release gate이므로 이 환경에서 자동 실행하지 못했다.

- 기존 재현 데이터로 첫 검색과 연속 두 번째 검색의 heap 추이 비교
- 검색 중 X → 실제 종료 → 새 검색 순서
- Site Map context에서 초기 아이콘과 선택 scope 확인
- charset 선언 있음/없음의 한글·혼합 검색
- 다수 match의 preview, filter, Extract selected/all
- 대용량 압축 응답 Extractor, 중간 취소와 partial 산출물
- extension unload/reload 후 worker·창·menu·registration 잔존 여부

자동 검증 기준 release 차단 결함은 남아 있지 않다. 위 수동 항목에서 실제 Burp API 동작 차이가 발견되면 0.4.4 patch 대상으로 다룬다.
