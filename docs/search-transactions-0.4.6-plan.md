# Burp Workbench 0.4.6 Search++ transaction 보존 계획

## 1. 목적

Search++가 같은 HTTP method와 URL을 가진 여러 요청·응답을 하나의 대표값으로 줄이지 않고, 검색 조건에 일치한 source transaction을 각각 결과로 보존하게 한다. 사용자가 Site Map table이나 Proxy history에서 정확히 선택한 항목도 선택 순서와 개수를 그대로 Search++에 전달한다.

이 변경은 검색 결과 정책만 바로잡는다. 기존 32 partition 순회, Site Map tree scope, Proxy Montoya 호환 mode, 무제한 결과, 한글·ASCII·정규식 검색, 취소·재검색 및 항목별 오류 격리 정책은 유지한다.

## 2. 확인된 원인

0.4.5에는 서로 다른 두 단계의 method+URL 중복 제거가 있다.

1. Site Map table, Proxy history와 같은 exact context 선택은 Search++ 창을 열기 전에 공용 `SelectionResolver`를 통과한다. 이 resolver는 method+URL이 같은 항목 중 첫 항목만 남긴다.
2. `SearchSourceScanner`는 각 source를 순회하면서 다시 method+URL key를 `seen` set에 넣고, 같은 key의 이후 항목을 결과에서 제외한다.

Target과 Proxy의 32 partition은 항목을 안정된 partition 하나에 배치하기 위한 메모리 제어 수단이다. scanner의 method+URL set은 partition 중복을 막는 데 필요하지 않으며, 서로 다른 transaction을 제거하는 결과 정책으로만 작동한다.

또한 주 검색과 화면 filter의 적용 시점이 다르다. 주 검색에 일치한 canonical 결과를 만든 뒤 status, MIME, extension, negative filter를 적용한다. 기존에는 canonical 결과를 만들기 전에 method+URL 대표 하나만 남기므로, 같은 endpoint의 `200 JSON`과 `404 HTML` 중 하나가 먼저 사라져 사용자가 나중에 선택한 filter가 해당 transaction을 복구할 수 없다.

## 3. 승인된 결과 정책

### 3.1 Source transaction 보존

- 같은 method와 URL이어도 별개의 source record이면 별도 검색 결과로 보존한다.
- request body, response status, response headers/body, time 등이 다른 transaction을 합치지 않는다.
- 값이 모두 같더라도 source가 별도 record로 제공한 항목은 각각 한 번 처리한다.
- Target과 Proxy에 같은 HTTP 교환이 각각 존재하면 source별 결과를 각각 표시한다.
- 결과 순서는 각 source와 partition이 제공하는 기존 순서를 유지하며 별도의 정렬이나 대표값 선택을 추가하지 않는다.
- 검색 결과 수를 자동으로 제한하거나 시스템이 임의로 filter하지 않는다.

정상 완료 검색의 “정확히 한 번” 보장은 검색 중 source와 partition metadata가 안정적이고 항목 접근이 정상이라는 전제에 적용한다. 32회의 Burp API 호출 사이에 source 자체가 변경되는 경우에는 Burp API가 하나의 snapshot을 제공하지 않으므로 절대적인 snapshot 보장을 하지 않는다.

### 3.2 Exact context와 Site Map tree

- Site Map table, Proxy history, Search results, message editor/viewer의 exact context는 `selectedRequestResponses()`가 제공한 순서와 개수를 보존한다.
- Search++ exact context를 위해 공용 `SelectionResolver`의 중복 제거 정책을 변경하지 않는다. Search++가 exact 선택을 별도 경로로 전달한다.
- Extractor가 사용하는 `SelectionResolver`와 Extractor의 기존 선택·중복 정책은 유지한다.
- `SITE_MAP_TREE`는 exact 항목 목록으로 확장하지 않는다. 선택 URL에서 만든 기존 `SelectionScope`를 유지하고, Search++ 창의 기본 source도 기존처럼 Target만 선택한다.
- Site Map tree의 scheme, host, effective port와 path 기반 scope 의미는 변경하지 않는다.

### 3.3 Repeater cache 예외

`RepeaterCache`는 Search++ 결과를 Repeater로 보낼 때 method+URL별 최신 항목 하나를 보관하는 기존 정책을 유지한다. 따라서 Repeater source에는 cache snapshot에 존재하는 항목만 들어온다.

Scanner는 그 snapshot에 추가 method+URL 중복 제거를 적용하지 않고, snapshot이 제공한 각 항목을 한 번씩 검색한다. 0.4.6은 Repeater cache를 전체 전송 이력 저장소로 바꾸지 않는다.

### 3.4 Filter 의미

- 주 검색에 일치한 모든 transaction을 canonical 결과로 보존한다.
- status, MIME, extension, negative filter는 canonical transaction마다 독립적으로 평가한다.
- filter 변경은 source를 다시 scan하지 않고 보존된 canonical 결과를 다시 평가하는 기존 동작을 유지한다.
- 정규식 filter timeout은 해당 transaction의 기존 timeout 정책만 적용하며 같은 endpoint의 다른 transaction에 영향을 주지 않는다.

## 4. 구현 변경

1. 프로젝트와 배포 산출물 버전을 `0.4.6`으로 올린다.
2. Search++ context menu provider에서 exact context를 공용 dedup resolver로 축약하지 않고 안전한 선택 snapshot으로 전달한다.
3. Site Map tree 분기는 기존처럼 빈 context item 목록과 선택 scope 목록을 전달한다.
4. `SearchSourceScanner`의 source별 `seen` set, `ExchangeKey`와 method+URL skip 분기를 제거한다.
5. Target, Proxy, Context, Organizer 및 Repeater snapshot의 각 후보를 기존 query, scope, 취소와 오류 정책에 따라 한 번씩 처리한다.
6. canonical result 저장과 후처리 filter 구조는 유지한다. 보존되는 각 결과의 message data는 기존처럼 Burp-managed temporary file로 복사한다.
7. Repeater cache의 method+URL별 최신 항목 정책과 공용 `SelectionResolver`의 Extractor 동작은 변경하지 않는다.
8. README 영문·한글은 현재 버전, 배포 JAR 경로와 공식 지원 범위만 갱신하고 0.4.6 변경 내역이나 changelog를 추가하지 않는다.
9. 과거 0.4.4 refactoring 및 0.4.5 compatibility 문서는 당시 정책의 기록이므로 수정하지 않는다.

## 5. 유지하는 불변식

- Java 17
- 최소 Burp `2025.9.3`, 최대 검증 Burp `2026.7.1`
- Montoya compile baseline `2025.8`, dependency scope `provided`
- Proxy `HISTORY_ID`/`LEGACY_METADATA` 자동 선택
- Target와 Proxy 각각 32 partition
- 검색 결과 무제한 정책
- 검색 중 X 버튼 취소와 취소 후 새 검색
- 한글 charset fallback, ASCII 및 정규식 검색
- malformed item과 정규식 timeout의 항목별 격리
- 부분 결과 보존과 extension 전체 source-scan permit
- Search++ UI, 설정 항목과 결과 열 형식

## 6. 자동 테스트 계획

### 6.1 Scanner transaction 보존

- Target 96개 고유 URL과 같은 method+URL의 추가 transaction 1개를 모두 97개 결과로 받는다.
- Proxy 96개 고유 URL과 같은 method+URL의 추가 transaction 1개를 모두 97개 결과로 받는다.
- Proxy 같은 `GET + URL` 182개를 양수·0·음수 history ID에 분산하고, 32회 history 호출 후 182개 identity가 각각 정확히 한 번 결과에 포함되는지 확인한다.
- `LEGACY_METADATA`에서 time, listener port, method와 URL이 같은 여러 record와 의도적인 hash collision을 만들어도 모든 record가 보존되는지 확인한다.
- Target 한 건과 같은 endpoint의 Proxy 182건을 함께 검색해 source별 총 183건인지 확인한다.
- Context와 Organizer에 같은 method+URL의 여러 transaction을 넣고 모두 보존하는지 확인한다.
- RAW source에서 첫 transaction은 query 불일치, 두 번째 같은 endpoint transaction만 일치할 때 두 번째 결과가 누락되지 않는지 확인한다.

### 6.2 Context 진입

- Site Map table과 Proxy history exact 선택에 같은 method+URL의 여러 transaction을 제공하고 Search++ 입력 순서와 개수가 그대로인지 확인한다.
- message editor/viewer의 단일 선택 fallback을 유지하는지 확인한다.
- Site Map tree는 context item을 확장하지 않고 기존 scope를 전달하며 Target만 기본 선택되는지 확인한다.
- 공용 `SelectionResolver`의 method+URL 정책과 Extractor 회귀 테스트는 계속 통과해야 한다.

### 6.3 Transaction별 filter

같은 method+URL이며 주 query에 모두 일치하는 `200 JSON`과 `404 HTML` transaction을 사용한다.

- all status에서는 2건
- 2xx에서는 200 transaction 1건
- 4xx에서는 404 transaction 1건
- JSON과 HTML filter에서 각각 해당 transaction 1건
- 2xx → 4xx → all 전환 시 재검색 없이 `1 → 1 → 2`
- negative token이 한 응답에만 있을 때 해당 transaction만 숨김
- status, MIME와 negative filter 조합을 transaction별 교집합으로 평가
- response가 없는 transaction은 all status에서 보이고 status filter에서만 제외

Extension filter는 URL에서 계산하므로 같은 URL transaction을 구별하는 기준으로 사용하지 않는다. 서로 다른 URL에 대한 기존 extension filter 회귀만 유지한다.

### 6.4 Repeater, 취소와 오류

- Repeater cache에 같은 method+URL을 두 번 추가하면 기존처럼 최신 한 건만 snapshot에 남는다.
- 서로 다른 method+URL의 Repeater snapshot 항목은 scanner에서 각각 한 번 처리한다.
- K번째 결과에서 취소하면 이미 발행한 K건에 identity 중복이 없고, 이후 재검색은 전체 결과를 새로 만든다.
- malformed item과 정규식 timeout은 해당 transaction만 제외하고 같은 endpoint의 정상 이웃 transaction을 계속 처리한다.
- `HISTORY_ID` 항목 실패 시 mode를 바꾸지 않고 기존 malformed 정책을 유지한다.

### 6.5 빌드와 배포

- Montoya `2025.8`, `2025.10`, `2025.12`, `2026.7` clean test matrix
- `mvn clean package`
- `Implementation-Version: 0.4.6`
- 최종 산출물 `target\burp-workbench-extension-0.4.6.jar`
- Montoya class 미포함, Brotli/Rhino 포함
- 제품 bytecode에 `ProxyHttpRequestResponse.id()` 직접 reference 없음

## 7. 실제 Burp 검증 계획

- Burp `2025.9.3`: `LEGACY_METADATA`, Site Map tree domain scope, Site Map table exact context, Proxy 반복 endpoint 검색, 두 번째 검색과 취소 후 재검색
- Burp `2026.7.1`: `HISTORY_ID`에서 같은 시나리오
- 같은 endpoint transaction을 여러 개 준비해 결과가 대표 1건이 아니라 실제 일치 transaction 수와 같은지 비교
- status/MIME/negative filter가 같은 endpoint의 각 transaction에 독립 적용되는지 확인
- `NoSuchMethodError`, OOM, 결과 identity 중복, 비정상 loop가 없는지 확인
- 첫 검색, 두 번째 검색, 취소 후 재검색의 결과 수와 heap 사용을 기록

## 8. 예상 영향과 완료 조건

같은 endpoint의 일치 transaction 수만큼 결과 행과 temporary file 수가 증가한다. 따라서 temporary disk 사용량, UI metadata, 재filter CPU와 Extract-all 작업량은 endpoint 대표값 정책보다 커질 수 있다. 반면 method+URL `HashSet`은 제거되고 response body는 heap의 일반 복사본으로 계속 보유하지 않는다.

다음 조건을 모두 충족하면 0.4.6 작업을 완료한 것으로 본다.

- exact context와 scanner가 승인된 transaction 보존 정책을 구현한다.
- Repeater cache와 Site Map tree 예외가 그대로 유지된다.
- transaction별 filter 및 취소·오류 회귀 테스트가 통과한다.
- 지원 API build matrix와 배포 JAR 검사가 통과한다.
- 실제 Burp 검증의 실행 여부와 결과가 verification 문서에 사실대로 기록된다.

