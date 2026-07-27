# Burp Workbench 0.4.4 리팩토링 사전 분석 보고서

- 분석 대상: 현재 루트의 `0.4.3` 소스
- 결정 반영: 이 문서의 질문·초기 권고 중 Git 초기화 제안은 채택되지 않았다. `.old`를 비교 기준으로만 사용하고 Git 초기화·commit·reset 없이 진행한다.
- 최종 범위와 선택 사항: `docs/refactoring-0.4.4-plan.md`
- 비교 대상: `.old`는 필요할 때만 참고했으며 분석·수정 대상에서 제외
- 작성일: 2026-07-24
- 문서 성격: 구현 계획을 확정하기 전의 소스 분석 및 의사결정 자료
- 구현 상태: 이 보고서 작성 외에 소스·버전·동작은 변경하지 않음

## 1. 결론

0.4.4에서 가장 먼저 다뤄야 할 축은 다음 네 가지다.

1. **Search++의 메모리 방어를 유지한 채 UI, 실행 수명주기, 탭 상태를 분리한다.**
2. **Extractor를 전체 적재형에서 항목별 순차 처리형으로 바꾼다.**
3. **확장 unload, 창, executor, SwingWorker, Montoya registration의 소유권을 명확히 한다.**
4. **검색 중복 기준, 결과 상한, 대용량 메시지 제한처럼 결과 의미가 달라지는 정책은 사용자 결정 후 변경한다.**

현재 Search++는 0.4.3에서 도입한 다음 안전장치 덕분에 이전보다 구조가 좋아졌다.

- Target/Proxy 원본을 32회 hash partition 조건으로 다시 순회해 한 번에 반환되는 Burp 목록 크기를 통상 줄인다. 이는 hard limit은 아니다.
- 일치 결과는 `copyToTempFile()`로 옮겨 본문 전체를 Java heap에 계속 붙잡지 않는다.
- 검색 버튼을 실행 중 X 아이콘으로 바꾸고 cancellation flag와 thread interrupt를 함께 사용한다.
- ASCII 검색은 가능한 경우 Montoya native 검색을 사용하고, 한국어는 UTF-8/MS949/EUC-KR 경로를 별도로 보존한다.
- 기존 테스트에 한국어와 혼합 문자열 경계 사례가 포함되어 있다.

따라서 0.4.4에서 파티션 검색이나 temp-file 저장을 근거 없이 제거하면 안 된다. 먼저 책임 경계와 테스트를 만든 뒤 실제 Burp 데이터로 대안을 측정해야 한다.

반면 Extractor는 현재도 선택 범위의 메시지와 원본·압축 해제 본문을 모두 메모리에 올린 다음 저장한다. 대규모 Site Map 추출에서는 Search++에서 경험한 것과 같은 종류의 OOM이 발생할 수 있다. 이 부분은 단순 정리보다 안전성 개선에 가깝고 0.4.4의 최우선 대상이다.

## 2. 분석 기준선

### 2.1 코드 규모

| 항목 | 현재 값 |
|---|---:|
| main Java 파일 | 51개 |
| main Java 코드 | 6,674줄 |
| test Java 파일 | 12개 |
| test Java 코드 | 2,061줄 |
| 기존 Surefire 테스트 | 82개 성공, 실패 0 |
| Java release | 17 |
| Montoya API | 2025.12 |
| 현재 project/artifact 버전 | 0.4.3 |

가장 큰 production 파일은 다음과 같다.

| 파일 | 줄 수 | 주된 문제 |
|---|---:|---|
| `SearchPlusDialog.java` | 2,068 | UI, 탭, 검색 실행, 취소, 필터, 테이블, 미리보기가 한 클래스에 집중 |
| `SearchEngine.java` | 612 | 필터, MIME/경로, charset, byte/text/regex 검색이 혼재 |
| `ExportService.java` | 482 | 수집, 분석, 계획, 저장, manifest/index/summary를 모두 조정 |
| `SearchSourceScanner.java` | 444 | 소스 접근, 파티션, scope, 중복, 오류, 취소를 담당 |
| `UrlPathMapper.java` | 333 | URL·header·Windows 경로 규칙을 한곳에서 처리 |

82개 성공은 기존 `target/surefire-reports`에 남은 결과를 합산한 값이다. 분석 환경에는 `mvn` 실행 파일이 PATH에 없어 이 분석 턴에서 새로 재실행한 결과는 아니다. 기존 보고서의 생성 시각은 2026-07-24 16:49경이다.

### 2.2 기준선 관리 주의사항

- 현재 루트는 동작하는 Git worktree가 아니다.
- `.old/.git`도 사용할 수 있는 repository가 아니다.
- 0.4.4 구현 전에는 Git 초기화·baseline commit 또는 동등한 복구 가능한 snapshot이 필요하다.
- 분석 중 `README.md`와 `README_ko.md`가 17:03경 `.old`의 짧은 내용과 같은 크기로 바뀌었다. 분석 agent는 두 파일을 수정하지 않았다.
- Java 소스의 refactored 차이는 현재 루트에 남아 있으며 `.old`와 구별된다.

이 보고서는 README 변경 후의 현재 소스 snapshot을 기준으로 한다.

## 3. 유지해야 할 설계와 동작

리팩토링 과정에서 다음은 기본 불변 조건으로 둔다.

1. 한국어 검색을 유지한다.
   - UTF-8, MS949, EUC-KR 관련 기존 테스트를 보존한다.
   - `한글 ABC` 같은 한국어+ASCII 혼합 검색과 대소문자 무시 검색을 회귀 테스트한다.

2. Search++가 전체 Target/Proxy 결과 목록을 동시에 보관하는 구조로 돌아가지 않게 한다.

3. 검색 결과 본문은 계속 temp-file backed 상태로 보관한다.

4. 한 창에서 검색은 직렬 실행한다.
   - 취소 요청 전 검색이 실제로 종료되기 전에 새 run이 겹치지 않게 한다.

5. 구조 이동과 검색 알고리즘 변경을 같은 commit에 섞지 않는다.

6. Maven multi-module, 범용 DI framework, event bus, ServiceLoader 같은 큰 인프라는 도입하지 않는다.

7. 실제로 두 모듈 이상에서 같은 정책을 사용할 때만 `core`로 공통화한다.

## 4. 우선순위 R1: 0.4.4에서 반드시 다룰 후보

### R1-1. Extractor 전체 적재를 순차 처리로 변경

#### 근거

- `core/selection/SelectionResolver.java:27-36`
  - subtree 선택 시 `api.siteMap().requestResponses()`로 Site Map 전체를 한 번에 받는다.
- `core/selection/SelectionResolver.java:55-65`
  - scope에 맞는 항목을 다시 별도 목록에 모은다.
- `modules/extractor/ExportService.java:60-71`
  - 모든 메시지를 `ExportCandidate`로 바꾼 뒤 전체 plan을 생성한다.
- `modules/extractor/ExportService.java:176-199`
  - 각 응답의 raw body와 decoded body를 모두 만든다.
- `modules/extractor/ExportCandidate.java:21-26`
  - raw byte 배열, decoded byte 배열, 원본 `HttpRequestResponse`를 동시에 보관한다.
- `modules/extractor/DuplicateIndex.java:8-20`
  - hash별 첫 번째 `ExportCandidate` 전체를 보관한다.
- `modules/extractor/ExportService.java:102-125`
  - cancellation 확인은 후보 수집·본문 decode·계획 생성이 끝난 뒤에야 시작한다.
- `modules/extractor/ExportIndexWriter.java:10-37`
  - 모든 index row를 모은 뒤 HTML 전체를 다시 하나의 문자열로 만든다.

현재 peak memory는 대략 다음 항목의 합으로 커진다.

```text
Site Map 반환 목록
+ scope 후보 목록
+ 모든 ExportCandidate
+ 모든 raw body
+ content decoding에 성공한 응답의 별도 decoded body
+ 원본 Montoya message 참조
+ duplicate index
+ index row 목록
+ 최종 HTML String
+ beautify 시 source String + output String + output byte[]
```

#### 권장 경계

```text
ExportSource
  → ExportMetadataReader
  → MetadataFilter
  → BodyProcessor(decode/hash/beautify)
  → DuplicateIndex(lightweight)
  → OutputAllocator
  → ManifestSink / IndexSink / Summary
```

각 항목은 다음 순서로 처리한다. Montoya API가 반환한 source batch/list는 남을 수 있으므로 목표 peak는 `source batch + 현재 처리 중인 decoded body` 수준이다.

```text
source item
  → 가벼운 metadata 읽기
  → filter
  → 해당 항목의 body만 decode
  → hash 및 중복 판단
  → 즉시 저장
  → manifest/index 기록
  → body 참조 해제
```

구체적으로는 다음을 권장한다.

- `ExportCandidate.requestResponse` 제거
- `rawBody` 대신 `rawByteCount`만 유지
- duplicate index는 `sha256 → {path, url}`만 저장
- manifest처럼 index도 streaming 작성
- 분석·decode·beautify·write 각 단계에 cancellation check 추가
- progress event를 항목마다 무제한 EDT queue에 쌓지 않고 throttle/coalesce

#### 좋아지는 점

- 자체 pipeline의 body 메모리가 전체 응답 수에 비례하지 않고 source batch와 현재 처리 중인 응답 크기에 주로 비례한다.
- 준비 단계에서도 취소가 동작한다.
- 수십만 항목 추출의 진행률과 실패 위치를 설명할 수 있다.

#### 부작용과 결정점

- 현재 `analyzeCandidates() → plan() → executePlan()` 공개 API와 테스트 구조가 바뀐다.
- 전체 plan을 먼저 확정하지 않으므로 출력 순서와 중복 원본 선택 규칙을 고정해야 한다.
- filter된 항목을 manifest에 남길지 완전히 생략할지 결정해야 한다.

### R1-2. 압축 해제와 Beautify에 항목별 메모리 예산 도입

#### 근거

- `core/codec/ResponseBodyDecoder.java:39-48,85-89`
  - gzip/deflate/Brotli 결과를 제한 없이 `ByteArrayOutputStream`에 쓴다.
- `modules/extractor/ExportService.java:312-320`
  - decoded bytes, Java `String`, beautified `String`, UTF-8 bytes가 한 시점에 공존할 수 있다.
- `modules/extractor/BodyBeautifier.java:56-81`
  - 깊게 중첩된 JSON은 indent 출력이 매우 크게 증가할 수 있다.
- `modules/extractor/ExtractorContextMenuProvider.java:100`
  - 현재 Beautify 기본값은 켜져 있다.

#### 권장 방향

- decoded byte 최대 크기
- 압축 원본 대비 최대 expansion ratio
- Beautify 입력·출력 최대 크기
- 제한 초과 action과 manifest reason
- 전체 작업은 계속하고 해당 항목만 실패 또는 skip

정규식 검색의 크기 제한과 Extractor 압축 해제 제한은 서로 다른 설정이어야 한다.

#### 부작용과 결정점

- 정상적인 초대형 파일이 제한에 걸릴 수 있다.
- 초과 시 원본 압축 body를 저장할지, 아무 것도 저장하지 않을지 결정해야 한다.
- 기본 MiB 값은 실제 사용 데이터에 맞춰 정해야 한다.

### R1-3. SearchPlusDialog를 동작 보존 방식으로 분해

#### 근거

`SearchPlusDialog.java`의 책임 범위는 다음과 같다.

| 범위 | 책임 |
|---|---|
| 78-270 | UI field, editor, executor, window 초기화 |
| 305-673 | 탭 생성·이름 변경·저장·복원 |
| 675-1115 | layout, filter control, listener |
| 1153-1368 | 검색 run, worker, 취소, 실패, OOM log |
| 1370-1545 | UI → options, 후처리 filter, table row |
| 1547-1745 | preview, Extractor, clipboard, Repeater |
| 1749-2068 | custom layout와 icon |

현재 검색 실행과 탭 전환, table state가 Swing component와 강하게 결합되어 있어 다음 동시성·수명주기 interleaving을 Swing 없이 테스트하기 어렵다.

- X 클릭 후 실제 worker 종료
- 탭 전환 중 이전 run의 `process()`/`done()`
- 취소된 run의 chunk가 새 결과에 추가되는지
- stale `done()`이 새 run의 상태를 덮는지
- 창 dispose 또는 extension unload 중 executor 종료

#### 권장 최소 분리

- `SearchPlusWindow`
  - Swing component와 사용자 event만 담당
- `SearchSessionController`
  - 시작, 취소, 완료, 실패, run generation 담당
- `SearchSession`
  - 한 탭의 query snapshot, result, filter state 담당
- `SearchTabStore`
  - 탭 추가·닫기·전환·이름 관리
- `SearchResultTableModel`
  - 결과를 JTable에 표시하는 단일 source of truth
- `SearchOptionsMapper`
  - UI snapshot을 immutable options로 변환
- `SearchUiSupport`
  - icon과 layout helper

`SearchEngine`과 `SearchSourceScanner` 알고리즘은 첫 분해 단계에서 그대로 둔다.

#### 좋아지는 점

- 취소와 stale run을 headless 단위 테스트할 수 있다.
- UI 수정이 검색 엔진을 건드리지 않는다.
- `allResults`, `currentResults`, tab copy, table row의 다중 상태를 줄일 수 있다.

#### 부작용

- 내부 파일과 명시적인 전달 객체가 늘어난다.
- characterization test 없이 한 번에 옮기면 탭/취소 회귀 가능성이 높다.

### R1-4. Search++ 후처리 필터 경계 분리

#### 근거

- `SearchPlusDialog.java:1442-1451`
  - 모든 결과에 MIME/status/extension과 negative search를 다시 적용한다.
- `SearchPlusDialog.java:1454-1465`
  - filter 적용, table reset, row 재생성을 Swing event 흐름에서 수행한다.
- `SearchEngine.java:468-495`
  - negative regex 또는 일부 한국어 case-insensitive 경로는 part 전체를 byte 배열과 `String`으로 변환할 수 있다.
- `SearchPlusDialog.java:1518-1533`
  - 결과마다 `DefaultTableModel.addRow()`를 호출한다.

검색 자체가 background여도 수천 개 결과에 negative filter를 적용하면 UI thread가 정지하고 순간 메모리가 증가할 수 있다.

#### 권장 방향

- 먼저 filter 계산과 Swing 반영을 분리하고 실제 결과 수로 EDT 시간을 측정
- 기준을 넘으면 immutable/versioned result snapshot을 background filter job에 전달
- 100~300ms debounce
- filter generation token으로 stale 결과 폐기
- filtered index 계산만 background에서 수행하고 table model 교체/event는 EDT에서 수행
- table model batch update 또는 custom model 사용
- 탭별 canonical result 하나와 filtered index/view만 유지

#### 부작용

- background 전환 시 필터 결과가 즉시가 아니라 짧은 지연 후 표시된다.
- 실행 중 필터를 계속 바꿀 때 취소·generation 규칙이 추가된다.
- mutable 목록을 worker와 EDT가 같이 읽으면 새 race가 생기므로 snapshot 경계가 필수다.

### R1-5. extension/module lifecycle과 자원 소유권 정리

#### 근거

- `BurpWorkbenchExtension.java:13-16`
  - `ModuleRegistry`가 지역 변수라 초기화 뒤 제어할 수 없다.
- `platform/WorkbenchModule.java:3-6`
  - initialize만 있고 close/unload 계약이 없다.
- `platform/ModuleRegistry.java:21-26`
  - 중간 초기화 실패 시 rollback이나 역순 close가 없다.
- `ExtractorModule.java:15`, `SearchPlusModule.java:19`
  - context menu registration 반환값을 보관하지 않는다.
- `SearchPlusModule.java:21`
  - 메뉴 uninstall만 unload handler에 등록한다.
- `SearchPlusDialog.java:1727-1730`
  - 개별 창 close 때만 executor를 종료한다.
- `ExtractorContextMenuProvider.java:144-174`
  - 실행 중 SwingWorker를 모듈이 추적하지 않는다.

#### 권장 방향

- module initialize 결과를 `ModuleLifetime` 또는 `AutoCloseable`로 표현
- Registry가 registration, window manager, executor, worker를 역순 close
- extension unload handler는 Registry 하나만 등록
- unload 시 검색·추출 취소 → 창 dispose → registration 해제
- 초기화 실패 시 이미 시작한 모듈 rollback

Burp가 extension unload 때 registration을 자체 정리할 가능성은 있다. 여기서 확인된 문제는 registration 누수를 단정하는 것이 아니라, 애플리케이션이 registration·window·worker를 한곳에서 rollback/close할 수 있는 소유권이 없다는 점이다.

추가로 `ModuleRegistry`는 nullable `ExtractionHandler` 한 칸만 특별 취급하고, Search 모듈은 Extractor가 먼저 초기화된 순서에 의존한다. 순서를 바꾸거나 Extractor 초기화가 실패하면 Search에는 null handler가 영구 주입되고 추출 요청은 조용히 false가 된다. `boolean extract()`도 “UI를 열었음”, “작업 시작”, “완료” 중 무엇을 뜻하는지 모호하다. 두 모듈만 유지한다면 명시적 wiring과 필수 dependency 검증이 typed registry보다 단순하다.

범용 DI는 필요하지 않다.

#### 결정점

- unload 때 부분 추출 결과를 보존할지
- 한 모듈 초기화 실패 시 전체 extension을 실패시킬지, 나머지 모듈은 살릴지

### R1-6. Extractor 출력 충돌과 동시 실행 무결성

#### 근거

- `UrlPathMapper.java:80-96`
  - host/port 중심으로 폴더를 만들며 HTTP/HTTPS scheme을 별도 namespace로 나누지 않는다.
- `UrlPathMapper.java:295-320`
  - sanitize와 길이 축약, 그리고 Windows 파일시스템의 대소문자 비구분으로 다른 URL이 같은 경로가 될 수 있다.
- `ExportService.java:257-270`
  - 이미 점유된 출력 경로인지 확인하지 않고 `Files.write()`한다.
- `DuplicateIndex.java:15-20`
  - body hash 중복만 찾고 path collision은 다루지 않는다.
- `ExportService.java:134-143`
  - `exists → createDirectories`가 원자적이지 않다.
- `ExtractorContextMenuProvider.java:144-174`
  - 여러 export를 동시에 시작할 수 있다.

같은 초에 두 export가 시작되면 같은 run directory를 공유할 가능성이 있다. URL path도 sanitize/축약 뒤 충돌할 수 있다.

#### 권장 방향

- 원자적 unique run directory 생성과 retry
- path collision을 URL hash suffix로 결정적으로 분리
- 직접 최종 경로에 쓰기보다 temp file → atomic move로 부분 파일을 방지
- data를 `content/`, manifest/index/summary를 `_meta/`처럼 나누는 구조는 호환성 승인 뒤 선택

#### 결정점

- Extractor 작업을 전역 직렬화할지
- 동시 실행은 허용하되 output만 완전히 격리할지
- 기존 출력 디렉터리 구조 호환이 필수인지

## 5. 우선순위 R2: 구조 분리 뒤 결정할 항목

### R2-1. Search source identity와 중복 기준

`SearchSourceScanner.java:362-365`는 각 source 내부에서 source 고유 identity 대신 공통 `method + URL` 중복 키를 사용한다. source 사이를 전역 dedupe하는 것은 아니다.

이 정책은 같은 URL에서 시각, 상태 코드, response body가 다른 Proxy transaction을 하나로 줄인다. 현재 테스트도 이 동작을 정상으로 고정한다. 따라서 이를 바꾸는 것은 단순 리팩토링이 아니라 검색 결과 의미 변경이다.

권장 후보는 다음과 같다.

- Proxy: `item.id()` 기준으로 각 transaction 유지
- Target: 실제 Burp 반환 순서·중복 의미와 Montoya API에서 사용할 수 있는 identity를 먼저 검증한 뒤 정책 결정
- Context: 사용자가 선택한 실제 item 우선
- Repeater: cache 자체 identity와 retention 정책 사용

결과 수, temp-file 수, 표시 순서가 늘 수 있으므로 사용자 결정이 필요하다.

### R2-2. 고정 32분할과 adaptive source traversal

`SearchSourceScanner.java:263-290`은 Target과 Proxy 전체를 32번 filter 순회한다.

장점:

- hash가 고르게 분포하면 Burp가 한 번에 반환하는 목록을 대략 1/32로 줄여 heap peak를 낮춘다.
- 이전 OOM의 직접 방어선이다.

비용:

- 전체 데이터 소스를 32회 검사한다.
- 결과 원본 순서를 잃을 수 있다.
- hash 분포가 기울면 한 partition이 여전히 클 수 있다.
- Target/Proxy API 호출이 검색마다 32회다.

권장 접근은 즉시 제거가 아니라 전략 경계를 만든 뒤 측정하는 것이다. source 크기를 사전에 안전하게 알 수 있는 API가 없으므로 “single pass를 먼저 시도하고 OOM이면 partition으로 fallback”하는 설계는 허용하면 안 된다.

```text
PartitionedTraversal  // 현재 안전 경로
SinglePassTraversal   // 크기가 확실히 작은 context 또는 명시적 opt-in
AdaptiveTraversal     // 안전성이 사전에 증명된 조건에서만 선택
```

adaptive 여부와 결과 순서 보존 요구는 사용자 결정이 필요하다.

### R2-3. 대용량 단일 메시지와 정규식

`SearchEngine.java:468-495`는 정규식과 일부 Unicode case-insensitive 검색에서 선택 part 전체를 `byte[]`로 복사하고 charset마다 `String`을 생성한다.

전체 source 누적 문제는 아니지만 단일 응답이 매우 크면 peak가 커질 수 있다.

권장 분리:

- `LiteralByteMatcher`
- `EncodedLiteralMatcher`
- `DecodedTextMatcher`
- `RegexMatcher`
- `CharsetPolicy`

literal 검색은 streaming decoder/matcher로 개선할 수 있다. 임의 정규식은 완전한 streaming 의미를 보존하기 어렵기 때문에 크기 제한, 경고, skip, 사용자의 명시적 override 중 하나가 필요하다.

### R2-4. charset 정책

현재 `SearchEngine.java:243-278,431-548`은 한국어 지원과 matcher가 결합되어 있다.

- 선언 charset이 없으면 UTF-8, MS949, EUC-KR, ISO-8859-1 후보를 사용한다.
- charset이 선언되어 있으면 선언된 charset만 사용한다.
- decoder는 malformed/unmappable byte를 `REPORT`로 처리해 해당 charset 검색을 실패시킨다.

한국어 검색 지원 자체는 유지해야 하는 불변 조건이다. 다만 서버가 잘못된 charset을 선언했을 때 fallback을 계속 시도할지는 별도 정책이다.

### R2-5. Search 결과와 창 수명 정책

현재:

- 탭마다 `allResults`와 `currentResults`를 가진다.
- Dialog에도 같은 두 목록이 있다.
- 목록 복사는 본문 복사가 아니라 result reference 배열 복사지만, 결과·table row·metadata는 탭 수에 따라 누적된다.
- 각 Search++ 창은 별도 single-thread executor를 갖는다.
- 여러 창을 열면 여러 대규모 검색이 동시에 실행될 수 있다.
- `RepeaterCache`는 상한 없는 `LinkedHashMap`이며 extension lifetime 동안 유지된다.
- Repeater source는 Burp 전체 Repeater tab을 읽는 것이 아니라 Search++가 “Send to Repeater”한 항목만 담은 내부 cache다.

`SearchOptions`는 `EnumSet.copyOf()`로 만든 mutable set을 accessor에서 그대로 노출하므로, options를 실질적인 immutable snapshot으로 만들려면 immutable copy 또는 전용 value object가 필요하다.

결정이 필요한 항목:

- 탭별 결과 무제한 보존 여부
- configurable max results와 truncated 표시 허용 여부
- 다중 창 동시 검색, 전역 직렬 검색, 단일 창 재사용 중 선택
- Repeater cache 상한과 source 명칭

### R2-6. 공통 정책과 실제 `core` 경계

현재 실제 양쪽 모듈에서 공유하는 중심은 `core.selection`과 `core.filter`다.

반면 다음은 사실상 한 모듈에서만 사용한다.

- `core.http.HttpExchange`, `HttpExchangeFactory`: Search 전용
- `core.codec.ResponseBodyDecoder`: Extractor 전용
- `core.util.Hashes`, `JsonLines`: Extractor 전용
- `core.ui.RequestResponseDetailDialog`: 사용처 없음

권장 방향:

- 실제 공유되는 것만 `core` 유지
- Search Montoya adapter/model은 Search 내부로 이동
- Extractor codec/hash/JSONL은 Extractor 내부로 이동
- 공개 패키지 API를 외부가 사용하지 않는다는 확인 후 visibility 축소

### R2-7. 오류와 malformed item 정책 통일

같은 malformed message가 위치마다 다르게 처리된다.

- `HttpExchange.java:20-51`: 빈 URL, `GET`, `-1`로 변환
- `SelectionResolver.java:88-93`: identity fallback key 사용
- `RepeaterCache.java:31-36`: 별도 fallback key 사용
- `ExportService.java:448-472`: 가짜 URL과 기본값 사용
- `SearchSourceScanner.java:347-373`: visitor 오류까지 malformed item으로 흡수할 수 있음

권장 방향:

- 경량 `HttpMessageMetadata` 또는 `SafeHttpFields`
- 정상, 누락, malformed를 구분
- source read failure, candidate read failure, temp storage failure, cancellation을 서로 다른 결과로 전달
- OOM 실패 로그와 source phase는 보존

부분 성공이 명시적 실패로 바뀔 수 있으므로 오류 정책도 사용자 승인 대상이다.

## 6. 우선순위 R3: 정리와 빌드 품질

### 6.1 삭제 또는 축소 후보

정적 참조 기준 후보이며, 외부 코드가 public API를 사용하지 않는다는 확인 후 처리한다.

| 후보 | 현재 상태 | 제안 |
|---|---|---|
| `RequestResponseDetailDialog` | production/test 사용처 없음 | 삭제 |
| `RepeaterCache.addAll()` | 사용처 없음 | 삭제 |
| `SearchEngine.search(List, options)` | production 사용 없음, 테스트 편의 | package-private 또는 test helper로 이동 |
| `ExportOptions.withSaveBeautifiedJavascriptCopy()` | 이전 명칭 잔재 | 삭제 |
| `ExportOptions.saveBeautifiedJavascriptCopy()` | 이전 명칭 잔재 | 삭제 |
| `ExportSummary.incrementBeautifiedJavascript()` | 이전 명칭 잔재 | 삭제 |
| `ExportOptions.skipReason()` | 호출 없음 | filter 정책 확정 후 삭제 또는 사용 |
| `ExportAction.failed()` | planner에서 생성 안 함 | streaming 결과 모델과 함께 정리 |
| `ExportActionType.FAILED/CANCELLED` | 정상 planner 경로에서 생성 안 함 | 실행 결과와 계획 모델을 분리 |
| `ManifestRecord` 일부 overload | 호출 없음 | 사용 API만 남김 |
| `ExportCandidate.withBody()` | 사실상 테스트 helper | test fixture로 이동 |

Extractor의 status/MIME/extension filter는 domain에는 있지만 실제 UI는 Beautify만 전달한다. 현재 테스트는 filter된 항목을 기록 없이 완전히 제외하는 동작을 정상으로 고정한다. 따라서 다음 중 하나를 선택해야 한다.

- 숨겨진 filter 기능과 도달 불가능한 filtered summary 경로를 제거
- Extractor UI에 filter 기능을 실제로 노출

### 6.2 중복 정책 공통화 후보

다음은 두 곳 이상에서 같은 정책이 반복된다.

- context menu invocation 목록과 selected item 추출
- status pattern parse/normalize/match
- `method + URL` dedupe key
- safe HTTP metadata 읽기
- MIME/extension 판정 일부

공통화는 한 번에 거대한 utility를 만들지 말고 작은 value object나 policy로 제한한다.

### 6.3 낮은 우선순위 성능 후보

- `JavaScriptBeautifier.java:22-44`
  - 각 JS 파일마다 Rhino scope를 만들고 vendor script를 다시 평가한다.
  - thread/lifetime을 명확히 한 뒤 compiled script 또는 초기화 비용 재사용을 검토한다.
- `SearchEngine.java:159-183`
  - HEX/literal byte 탐색은 단순 `O(N×M)`이다.
  - 실제 profile에서 CPU 병목이 확인되면 KMP/Boyer-Moore 계열을 검토한다.
- `UrlPathMapper`
  - 파일은 크지만 책임은 비교적 cohesive하다.
  - 줄 수만 보고 여러 클래스로 강제 분해하지 않는다.

### 6.4 빌드·릴리스 품질

현재 `pom.xml`에는 compile/test/shade 기본 구성은 있으나 다음 검증은 없다.

- reproducible build timestamp
- Maven Enforcer
- JDK 17/21 CI
- coverage gate
- shaded JAR 내용 검증
- checksum 생성
- project 수준 LICENSE / THIRD_PARTY_NOTICES / CHANGELOG

0.4.4에서 모든 정적 분석 도구를 한꺼번에 넣는 것은 권장하지 않는다. 최소 gate는 다음 정도가 적절하다.

1. `mvn clean verify`
2. JDK 17 compile/test
3. 선택적으로 JDK 21 호환 build
4. shaded JAR에 Rhino/Brotli 포함, Montoya 제외 확인
5. release JAR SHA-256 생성
6. behavior change를 기록하는 CHANGELOG

## 7. 권장 목표 구조

```text
com.burpworkbench
  platform
    ModuleRegistry
    ModuleLifetime
    ExtractionHandler

  core
    selection
    filter
    http metadata policy     // 실제 양쪽 공유가 확인될 때만

  modules.search
    ui
      SearchPlusWindow
      SearchResultTableModel
      SearchUiSupport
    application
      SearchSessionController
      SearchSession
      SearchTabStore
      SearchOptionsMapper
    source
      SearchSourceScanner
      traversal strategies
    match
      SearchEngine facade
      literal/text/regex matchers
      CharsetPolicy
    model
      HttpExchange
      SearchResult

  modules.extractor
    ui
      ExtractorDialog
      ExportProgressDialog
    application
      ExportCoordinator
    source
      ExportSource
    processing
      MetadataFilter
      BodyProcessor
      DuplicateIndex
    output
      OutputAllocator
      ManifestSink
      IndexSink
      SummarySink
```

이 구조는 최종 class 이름을 확정한 것이 아니라 책임 경계를 설명하는 초안이다.

## 8. 테스트 및 검증 전략

### 8.1 구조 변경 전 characterization test

Search++:

- UTF-8/MS949/EUC-KR 한국어 검색
- 한국어+ASCII 혼합 대소문자 무시 검색
- 두 번째 검색 버튼 클릭 시 취소
- 취소 완료 전 새 run이 겹치지 않음
- 탭 전환 중 old chunk와 stale `done()` 처리
- source별 선택과 context scope
- temp-file storage 실패가 malformed item으로 숨지 않음
- 후처리 negative filter의 현재 결과

Extractor:

- 현재 output path, manifest, index, summary golden output
- body SHA-256 중복의 현재 의미
- filter된 항목의 현재 생략 동작
- no-response, decode failure, beautify failure
- subtree scope의 현재 해석

Platform:

- module 시작 순서
- ExtractionHandler wiring
- unload 전 현재 registration 상태

### 8.2 구조 변경 뒤 신규 회귀 테스트

- Registry rollback과 역순 unload
- 열린 Search++ 창과 executor 종료
- 실행 중 Export worker 취소
- 분석/decode/beautify/write 각 단계의 cancellation
- 10,000개 이상 synthetic item에서 자체 pipeline이 현재 처리 중인 body 외의 body를 누적하지 않는지 확인
- gzip/deflate/Brotli 제한과 expansion ratio
- 깊은 JSON과 대형 JS Beautify 제한
- 동일 URL의 서로 다른 transaction
- HTTP/HTTPS, Windows 대소문자 비구분, sanitize, 긴 경로, 서로 다른 URL의 path collision
- 동시 export의 unique run directory
- 개별 malformed/IO 실패 후 다음 항목 계속
- streaming index/manifest 완결성
- shaded JAR 내용

### 8.3 실제 Burp acceptance test

최소 시나리오:

1. Target + Proxy 대규모 ASCII 검색
2. 같은 검색을 두 번 연속 실행
3. 검색 중 X 취소 후 재검색
4. UTF-8/MS949/EUC-KR 한국어 검색
5. negative filter와 regex filter
6. Site Map tree scope 검색
7. 동일 URL의 여러 Proxy transaction
8. 대규모 subtree Extractor
9. gzip/deflate/br 추출
10. Extractor 취소와 부분 결과 확인

권장 관찰 항목:

- 시작 heap
- source batch/partition별 peak
- 결과 100/1,000/10,000개 시 heap
- 취소 반응 시간
- 검색 종료 뒤 회수 가능한 heap
- 두 번째 검색의 peak
- temp file 수와 정리 시점

정량 합격 기준은 사용자가 원하는 결과 상한과 테스트 데이터 규모를 정한 뒤 계획서에 명시한다.

## 9. 잠정 진행 순서

아래 순서는 질문 답변 전의 초안이며 확정 계획이 아니다.

1. 0.4.3 기준선 복구 가능 상태 확보
2. 한국어·취소·탭·출력 characterization test 추가
3. module lifecycle과 Search controller/session/view 분리
4. Search 후처리 filter와 result table model 분리
5. Extractor streaming pipeline과 output allocator 도입
6. decode/beautify 제한과 cancellation 확대
7. 사용자 결정에 따라 source identity, result limit, traversal 정책 적용
8. dead/legacy API 정리
9. stress/실제 Burp 검증
10. 문서·CHANGELOG·0.4.4 package

## 10. 계획 확정을 위해 필요한 질문

### Q1. 0.4.4 변경 범위

다음 중 어느 수준을 원하는가?

- A. 구조만 바꾸고 동작은 완전히 유지
- B. 구조 분리 + 명백한 메모리/lifecycle/output 안전성 수정
- C. 검색 결과 의미와 UI 정책까지 적극 변경

**권장: B.** 결과 의미가 바뀌는 항목은 아래 질문별 승인을 받은 뒤 별도 단계에서 처리한다.

### Q2. 외부 API와 기준선

다른 extension이나 별도 Java 코드가 이 JAR의 `com.burpworkbench.*` public class를 직접 사용하는가?

**권장: 사용처가 없다면 package 이동, visibility 축소, 미사용 public API 삭제를 허용한다.**

현재 루트에 Git history가 없다. 구현 전에 이 폴더를 새 Git repository로 초기화하고 현재 0.4.3을 baseline commit으로 남겨도 되는가?

**권장: 예.** 원하지 않으면 별도 snapshot 디렉터리나 archive 방식을 지정해야 한다.

### Q3. source별 중복 의미

Proxy/Target에 같은 method+URL이지만 시간, status, body가 다른 항목이 있으면 어떻게 할까?

- 모두 표시·저장
- 최신 항목만
- 현재처럼 첫 항목만

**권장: Proxy는 모든 transaction을 유지한다.** Target의 “최신” 또는 node identity는 현재 코드와 API에서 보장되지 않으므로 실제 Burp 반환 의미를 먼저 확인하고, 그 전까지는 구조 분리 단계에서 현재 동작을 유지한다. Context에서는 사용자가 직접 선택한 item을 우선한다.

### Q4. source traversal, 표시 순서, Site Map scope

메모리 안전성을 위해 현재 32분할을 계속 유지할까, 안전성이 사전에 증명된 작은 context 또는 명시적 opt-in에서만 single-pass를 허용할까?

**권장: 0.4.4 기본 경로는 32분할을 유지한다.** OOM 발생 뒤 fallback하는 방식은 복구가 불가능하므로 금지하고, single-pass는 실제 측정과 안전 조건이 생긴 뒤에만 추가한다.

partition 뒤 Proxy time/id 같은 **새로운 stable 표시 순서**를 정의할 필요가 있는가? Target 원래 traversal 순서는 복원 근거가 없어 그대로 복원한다고 약속할 수 없다.

또 Site Map tree에서 선택한 node는 항상 subtree로 해석해도 되는가? 현재 점(`.`) 기반 파일/디렉터리 추정은 `.well-known`과 확장자 없는 파일을 잘못 분류할 수 있다.

**권장: tree 선택은 항상 subtree, table/message 선택은 선택 item만 사용한다.**

### Q5. Search 결과 보관과 동시 실행

검색 결과가 수십만 건이면 configurable 최대 보관 수를 둘 수 있는가?

**권장: 높은 기본 상한을 설정 가능하게 한다.** 상한 뒤에는 `copyToTempFile()`도 하지 않고 전체 match count만 집계한다. UI는 `전체 일치 M / 보관 N / truncated`를 구분하고, Extract all은 보관된 N개만 대상임을 표시한다. 조용히 결과를 버리거나 완료로 표시하면 안 된다.

이 상한은 결과 저장 메모리만 제한하며, 한 partition에서 Montoya가 반환하는 후보 목록 peak는 줄이지 않는다.

여러 Search++ 창은 계속 허용할까?

- 여러 창에서 대규모 검색 동시 실행
- 여러 창은 허용하지만 source scan은 extension 전체에서 직렬화
- 단일 창만 재사용

**권장: 여러 창은 허용하되 대규모 source scan은 전역 직렬화한다.**

상한 없는 내부 `RepeaterCache`도 크기/시간 기반으로 제한하고, 실제 Burp Repeater 전체가 아니라 Search++가 보낸 항목만 포함한다는 명칭을 표시해도 되는가?

### Q6. 대형 regex와 charset

단일 메시지가 매우 클 때도 regex/Unicode decoded 검색을 무제한 수행해야 하는가, 아니면 configurable 크기 제한·경고·skip·수동 override를 허용하는가?

**권장: 기본 제한 + 명시적 override.** 제한으로 건너뛴 항목이 있으면 검색 상태를 `complete`가 아닌 `incomplete`로 표시하고 skip 건수·크기·phase를 남긴다.

한국어 검색은 현재 direct-byte 경로와 UTF-8/MS949/EUC-KR 테스트를 반드시 유지한다. 일반 한국어 literal의 streaming 개선은 목표로 두되 구현·stress test 전에 “무제한 지원”을 보장하지 않는다.

서버가 잘못된 charset을 선언했을 때도 UTF-8/MS949/EUC-KR fallback을 계속 시도해도 되는가?

**권장: fallback을 허용하고 진단에 실제 사용 charset을 남긴다.**

### Q7. Extractor 중복, filter, 출력 호환성

현재는 URL이 달라도 decoded body SHA-256이 같으면 두 번째 파일을 생략한다. 이를 유지할까?

- 현재처럼 두 번째 경로 생략
- URL 경로는 모두 만들고 가능하면 hard-link
- URL 경로마다 실제 복사

**권장: 동작 보존 단계에서는 현재 생략 정책을 유지한다.** URL별 경로 완전성이 더 중요하다고 답하면 hard-link/copy 정책을 별도 단계에서 적용한다.

현재 UI에는 없는 status/MIME/extension filter를 제거해도 되는가?

- 제거해 streaming pipeline을 단순화
- Extractor UI 기능으로 복원

**권장: 0.4.4에서는 제거한다.** 필요하면 이후 명시적 기능으로 다시 추가한다.

기존 파일 상대 경로와 manifest/index schema의 하위 호환이 필수인가? `content/`와 `_meta/`로 분리하는 구조 변경을 허용하는가?

**권장: 0.4.4 구조 분리 단계에서는 외부 경로/schema를 보존하고 collision allocator를 추가한다.** 명시적으로 schema 변경을 허용하면 versioned output 구조로 분리한다.

### Q8. Extractor 제한, 실패, 취소 수명주기

decoded body, expansion ratio, Beautify 입력/출력에 configurable limit을 두고 초과 항목만 기록 후 계속 진행해도 되는가? 평소 필요한 최대 단일 응답 크기는 대략 몇 MiB인가?

**권장: 제한을 두고 초과·malformed·개별 파일 IO 실패는 항목별로 기록한 뒤 계속 진행한다.** manifest 자체 생성 실패처럼 결과 신뢰성을 보장할 수 없는 경우만 전체 중단한다.

Extractor는 한 번에 하나만 실행할까? progress 창 X를 누르면 Cancel 버튼과 같게 처리해도 되는가? extension unload 때 검색·추출을 취소하고 관련 창을 닫아도 되는가?

**권장: Export 직렬 실행, X=취소, unload=전체 작업 취소.**

취소·오류가 난 partial run directory는 terminal manifest/summary와 함께 보존할까, 자동 삭제할까?

**권장: 보존하고 명확한 `cancelled`/`partial` 상태를 기록한다.**

### Q9. 메뉴 위치와 향후 모듈

Search++가 반드시 `Burp > Search` 바로 아래에 있어야 하는가?

- 반드시 유지: 현재 Swing menu 삽입을 작은 adapter로 격리
- 위치보다 안정성 우선: 공식 Montoya top-level menu만 사용

현재 방식은 Burp UI 구조와 영문 `"Burp"`, `"Search"` 문자열에 의존한다.

또 0.4.x 안에 Scanner/Highlighter 같은 세 번째 모듈을 실제로 추가할 계획이 있는가?

- 없다면 두 모듈을 명시적으로 wiring하고 dependency 누락을 시작 시 실패 처리
- 있다면 작은 typed service registry 도입

**권장: 실제 세 번째 모듈 계획이 없다면 명시적 wiring을 유지한다.**

현재처럼 Search가 Extractor 기능을 전제로 하는 동안 Extractor 초기화 실패 시 전체 extension을 명시적으로 실패시킬까, Search만 제한 모드로 띄울까?

**권장: 조용한 제한 모드는 피하고, 필수 dependency라면 원인을 로그에 남기고 시작을 실패시킨다.**

## 11. 질문 답변 뒤 만들 계획서

사용자 답변을 받으면 다음 내용을 확정한 별도 0.4.4 구현 계획서를 작성한다.

- 포함/제외 범위
- 동작 불변 조건
- 결과·중복·제한 정책
- 단계별 파일 이동과 class 책임
- 단계별 테스트
- 메모리 및 취소 acceptance 기준
- commit/rollback 경계
- 버전·문서·package 산출물
