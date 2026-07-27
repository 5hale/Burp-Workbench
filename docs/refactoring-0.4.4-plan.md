# Burp Workbench 0.4.4 리팩토링 구현 계획

- 기준 소스: 현재 루트의 0.4.3
- 참고 소스: `.old`는 비교 전용
- Git 작업: 초기화·commit·reset을 수행하지 않음
- 목표 버전: 0.4.4
- 상세 분석: `docs/refactoring-0.4.4-analysis.md`

## 1. 확정된 사용자 결정

1. 현재 검색 결과 정책을 유지한다.
   - source별 중복 기준을 임의로 변경하지 않는다.
   - 결과를 시스템이 임의로 자르지 않는다.
   - 현재 32분할 source traversal을 유지한다.
   - Site Map scope, 결과 순서, charset 선택 의미를 임의로 변경하지 않는다.

2. Search++ 화면과 조작 방식을 유지한다.
   - 탭, 검색/X 버튼, filter, preview, context action의 외형과 의미를 바꾸지 않는다.
   - 내부를 controller/session/model/UI helper 경계로 분리한다.

3. Search++ 창은 여러 개 허용하되 source scan은 extension 전체에서 하나만 실행한다.
   - 다른 창에서 검색 중이면 새 검색을 시작하지 않는다.
   - 기존 검색을 자동 취소하거나 queue에 숨겨 두지 않는다.

4. 검색 결과 개수에는 상한을 두지 않는다.
   - temp-file backed result 저장을 유지한다.
   - 탭·table의 중복 reference와 row object만 줄인다.

5. 정규식 검색은 크기만으로 항목을 제외하지 않는다.
   - recoverable item 오류와 regex timeout은 해당 항목만 기록하고 계속한다.
   - timeout이 있으면 검색 상태를 `incomplete`로 표시한다.
   - 실제 `OutOfMemoryError`는 현재 run을 한 번만 종료하고 기존 결과를 보존한다.
   - OOM 자동 재시도는 하지 않는다.

6. Extractor는 결과 의미와 출력 형식을 유지한 채 body를 순차 처리한다.
   - 현재 선택 목록 순서와 decoded-body SHA-256 중복 의미를 유지한다.
   - 기존 상대 경로와 manifest/index/summary schema를 유지한다.
   - 전체 body 누적을 없애고 source list와 현재 항목의 작업 데이터만 유지한다.

7. Extractor의 UI에 없는 status/MIME/extension filter와 도달 불가능한 분기를 제거한다.

8. Extractor의 대형 본문은 가능한 한 그대로 저장한다.
   - decode 결과는 temp file 기반으로 순차 처리할 수 있게 한다.
   - 메모리 예산을 넘는 경우 Beautify만 생략하고 manifest에 이유를 기록한다.
   - 압축 폭주·디스크 부족·개별 IO 실패는 해당 항목 실패로 기록하고 다음 항목을 처리한다.

9. extension unload/reload 시 작업과 자원을 명시적으로 정리한다.
   - 검색·추출 취소
   - Search++/progress 창 닫기
   - executor/worker/result reference 해제
   - registration 정리
   - 이미 저장된 Extractor 파일은 디스크에 `cancelled` 상태로 보존

10. Search++ 메뉴는 `Burp > Search` 위치를 유지한다.
    - 내부 Swing 접근은 adapter로 격리한다.
    - 실패하면 공식 Montoya top-level menu로 fallback한다.

11. 0.4.4는 Extractor와 Search++ 두 모듈을 명시적으로 연결한다.
    - 범용 service registry는 0.5의 실제 세 번째 모듈 요구사항이 생길 때 설계한다.

12. 진단 로그는 평소 간결하게 유지한다.
    - 실패·취소 시 phase, 처리량, heap, skipped/timeout 합계를 남긴다.
    - 반복 malformed 상세 로그는 제한한다.

## 2. 동작 불변 조건

### Search++

- UTF-8, MS949, EUC-KR 한국어 검색
- 한국어+ASCII 혼합 검색
- charset 선언이 있으면 그 charset만 strict하게 사용하고, 선언이 없을 때만 UTF-8 → MS949 → EUC-KR → ISO-8859-1 후보를 사용
- TEXT/HEX/regex/case-sensitive/negative match 의미
- request/response headers/body 선택
- Target/Proxy/Repeater/Organizer/Context 선택
- status/MIME/extension filter 의미
- source별 현재 중복 기준
- 32 partition traversal
- 결과 temp-file 저장
- 탭별 결과와 preview
- Extract selected/all, Copy URL, Send to Repeater
- 검색 중 X 취소와 완료 후 돋보기 복귀

### Extractor

- 선택 순서
- decoded body SHA-256 기준 최초 항목 우선
- gzip/deflate/br decode 의미
- JS/JSON Beautify 결과와 실패 fallback
- URL→상대 경로 mapping
- 출력 디렉터리 naming
- manifest/index/summary 파일명과 schema
- no-response/duplicate/failed/cancelled 기록 의미

## 3. 작업 트랙

### Track A — Search++ 구조와 실행 수명주기

1. `SearchPlusDialog`에서 다음 책임을 분리한다.
   - run start/cancel/finish 상태
   - 탭 session 저장·복원
   - 결과 table model
   - UI→SearchOptions snapshot
   - icon/layout helper

2. extension 전체 공유 `SearchExecutionCoordinator`를 도입한다.
   - 단일 permit
   - permit owner/run identity
   - 모든 terminal 경로에서 release
   - 창 dispose/unload 시 release

3. `allResults/currentResults/tableModel`의 중복 상태를 줄인다.
   - canonical result는 session 한 곳에서 소유
   - table은 result model을 조회
   - Swing 변경은 EDT에서만 수행

### Track B — Search++ 오류 격리와 정규식 안전성

1. recoverable 오류를 구분한다.
   - malformed candidate
   - temp storage 실패
   - regex timeout
   - source read failure
   - cancellation
   - fatal OOM

2. 정규식 matcher에 item 단위 deadline/operation guard를 둔다.
   - timeout은 retry하지 않음
   - 해당 item만 건너뛰고 scan 계속
   - 최종 상태에 `incomplete`, timeout count 표시

3. OOM terminal 처리
   - 기존 result 유지
   - worker/run reference 해제
   - permit release
   - 검색 버튼 idle 복귀
   - 자동 재실행 없음

4. 로그
   - 기본 1줄 요약
   - 실패 phase와 heap snapshot
   - 처음 5개 item 오류 상세, 이후 합계

### Track C — Extractor 순차 body 처리

1. production `export()` 흐름을 다음으로 변경한다.

```text
selected/source list
  → item metadata
  → current item body decode
  → decoded SHA-256 lookup
  → save/duplicate action
  → manifest/index/summary 기록
  → current body release
```

2. 중복 index를 경량화한다.
   - `sha256 → {relativePath, url}`
   - `ExportCandidate`나 body를 보관하지 않음

3. 현재 순서와 중복 결과를 유지한다.
   - first occurrence wins
   - filter 제거 뒤 모든 item 대상

4. index를 전체 `StringBuilder`로 만들지 않는다.
   - row temp file 또는 streaming sink
   - 최종 HTML 내용과 row 순서 유지

5. 단계별 cancellation check를 추가한다.
   - item 진입
   - decode
   - hash/copy
   - beautify
   - write

### Track D — Extractor 대용량 처리와 출력 무결성

1. decode output을 temp file로 보낼 수 있는 경계를 만든다.
2. Beautify 메모리 예산을 초과하면 원래 decoded body를 저장한다.
3. 이유를 manifest/index/summary에 기록한다.
4. 최종 파일은 같은 디렉터리의 temp file에 쓴 뒤 atomic move를 우선 사용한다.
5. 기존 상대 경로가 충돌하면 deterministic suffix allocator를 사용하되 비충돌 경로는 변경하지 않는다.
6. run directory는 원자적으로 생성한다.
7. progress event는 throttle/coalesce하여 EDT queue 누적을 막는다.
8. progress 창 X는 Cancel과 같은 동작을 한다.
9. 취소된 partial run은 terminal manifest/summary와 함께 보존한다.

### Track E — Module lifecycle과 정리

1. `ModuleRegistry`의 특별한 nullable handler slot과 암묵적 초기화 순서를 제거한다.
2. 두 모듈을 명시적으로 wiring한다.
3. module lifetime이 registration/window/worker를 소유하게 한다.
4. Registry 하나가 unload를 역순 실행한다.
5. 사용되지 않는 코드를 제거한다.
   - `RequestResponseDetailDialog`
   - Extractor hidden filter/planner dead branch
   - legacy Beautify alias
   - 사용되지 않는 helper/overload
6. public API는 Burp entry point를 제외하고 필요한 범위로 축소한다.

## 4. 테스트 계획

### 기존 동작 characterization

- 기존 82개 테스트 유지
- 한국어 UTF-8/MS949/EUC-KR 및 혼합 검색
- Search++ 32 partition 호출/중복/취소
- Extractor duplicate/filter/output golden behavior

### 신규 Search++ 테스트

- 전역 coordinator가 동시 두 run을 허용하지 않음
- cancel/failure/OOM terminal 경로에서 permit release
- regex timeout 뒤 다음 item 처리
- timeout 최종 상태 `incomplete`
- stale run/chunk/done이 새 session을 덮지 않음
- 결과 상한 없이 모든 결과 유지
- 여러 탭 결과 reference 수명

### 신규 Extractor 테스트

- 여러 item 처리 중 이전 body가 duplicate index에 남지 않음
- 동일 decoded body의 first occurrence 유지
- 순차 결과가 기존 plan 결과와 동일
- 대형 Beautify skip 후 원문 저장
- gzip/deflate/br decode 실패 격리
- cancellation이 분석 단계에서도 동작
- partial manifest/summary 완결성
- index streaming 결과 golden 비교
- output collision allocator
- 동시 run directory 생성

### Lifecycle 테스트

- module initialize 실패 rollback
- unload 역순
- registration close
- 열린 Search++ 창/executor 종료
- 실행 중 Extractor worker 취소

## 5. 검증 게이트

1. `mvn clean test`
2. `mvn clean package`
3. 전체 테스트 실패 0
4. shaded JAR에 Rhino/Brotli 포함, Montoya 제외
5. 한국어 검색 회귀 테스트 성공
6. 동일 검색 연속 2회 및 취소 후 재검색
7. synthetic 대량 Extractor에서 body 누적 없음
8. 기존 출력 golden 비교
9. 서브 에이전트 독립 코드 리뷰
10. 이 계획의 각 항목을 완료/미완료/변경으로 최종 감사

## 6. 범위 제외

- 결과 개수 자동 제한
- Proxy/Target 중복 의미 변경
- partition count/adaptive traversal 변경
- Site Map scope 의미 변경
- 검색 결과 정렬 정책 변경
- Extractor 출력 schema/디렉터리 구조 변경
- 범용 module/service registry
- 새로운 검색 문법·source·DB index
- Git 초기화·commit

## 7. 구현 후 감사

- 구현 상태: 완료
- 자동 검증: 162개 발견, 161개 성공, 실패 0, Windows 조건부 1개 중단
- 독립 안정성 감사: P0/P1 잔여 결함 없음
- 최종 JAR: `target/burp-workbench-extension-0.4.4.jar`
- SHA-256: `5F3E9F5C555CAE111BC4F3E905F00C0105B5FC5D548B7E984BEBB5E5D79DD392`
- 상세 결과·수정 방향·부작용·수동 release gate:
  `docs/refactoring-0.4.4-verification.md`

실제 Burp 데이터가 필요한 heap·UI·unload 수동 검증은 최종 검증 보고서의 8절에 분리했다. Git은 사용자 결정대로 초기화하거나 변경하지 않았다.
