# Burp Workbench 0.4.6 Search++ transaction 보존 검증 보고서

## 1. 현재 상태

0.4.6 구현과 자동 검증은 완료했다. 네 Montoya API 기준의 격리 clean test, 최소 API 기준 clean package, shaded JAR·bytecode 검사를 실행했다.

실제 Burp Suite `2025.9.3`과 `2026.7.1` 수동 검증은 아직 실행하지 않았다. 따라서 자동 검증 통과는 기록하지만 실제 Burp 반복 검색과 heap 검증 완료는 선언하지 않는다.

상태 구분:

| 검증 구분 | 상태 |
|---|---|
| 소스·단위 회귀 검증 | 통과 — 187 tests, failures 0, errors 0, 환경 조건부 skip 1 |
| Montoya API build matrix | 통과 — `2025.8`, `2025.10`, `2025.12`, `2026.7` |
| 0.4.6 shaded JAR 검사 | 통과 |
| Burp `2025.9.3` 수동 검증 | 미실행 |
| Burp `2026.7.1` 수동 검증 | 미실행 |

## 2. 구현 대조 결과

구현, 회귀 테스트와 JAR을 다음 기준에 대조했다.

| 계획 항목 | 확인 기준 | 현재 상태 |
|---|---|---|
| 프로젝트 버전 | POM 및 manifest `0.4.6` | 통과 |
| Exact context 보존 | Site Map table/Proxy history 선택 순서와 개수 유지 | 통과 |
| Site Map tree 유지 | context item 미확장, 기존 scope와 Target 기본값 | 통과 |
| Scanner dedup 제거 | method+URL `seen`/`ExchangeKey` skip 없음 | 통과 |
| Source transaction 보존 | 같은 endpoint record가 각각 한 번 결과가 됨 | 통과 — Proxy 182건 identity 검증 포함 |
| Transaction별 filter | status/MIME/negative filter가 각 canonical result에 적용 | 통과 |
| Repeater 예외 | cache는 method+URL별 최신 한 건, scanner 추가 dedup 없음 | 통과 |
| Extractor 경로 분리 | Search++는 occurrence 보존, 직접 메뉴와 decoded-body 중복 정책 유지 | 통과 |
| Body 보관 | result message는 Burp-managed temporary file copy 사용 | 통과 |
| 32 partition·호환 mode | Target/Proxy 32회, Legacy/History ID 유지 | 통과 |

## 3. 자동 검증 결과

### 3.1 핵심 회귀

- Target와 Proxy의 같은 method+URL transaction을 입력 개수만큼 보존
- Proxy 동일 endpoint 182건을 32 partition에서 identity 기준 정확히 한 번씩 처리
- 음수 history ID, 동일 legacy metadata 및 partition hash collision에서도 결과 누락 없음
- Target와 Proxy에 같은 endpoint가 있을 때 source별 결과 보존
- Context와 Organizer의 같은 endpoint transaction 보존
- RAW source에서 앞 항목이 query 불일치여도 뒤의 같은 endpoint 일치 항목 표시
- exact context 선택의 순서와 개수 보존
- Site Map tree의 기존 scope 의미와 Target 기본 source 유지
- Repeater cache의 method+URL별 최신 한 건 정책 유지
- malformed item, 정규식 timeout, 취소와 재검색 격리

위 회귀를 포함한 전체 187개 테스트가 통과했다. 동일 endpoint malformed item과 정규식 timeout 뒤의 정상 이웃 transaction 보존도 별도로 확인했다.

### 3.2 Filter 회귀

같은 method+URL의 `200 JSON`, `404 HTML`과 무응답 transaction을 보존한 canonical fixture로 다음을 확인했다.

| 동작 | 기대 결과 |
|---|---|
| all status | 세 transaction 모두 표시 |
| 2xx | 200 transaction만 표시 |
| 4xx | 404 transaction만 표시 |
| JSON | JSON transaction만 표시 |
| HTML | HTML transaction만 표시 |
| 2xx → 4xx → all | 재scan 없이 `1 → 1 → 3` |
| 한 응답에만 negative token | 해당 transaction만 숨김 |
| status + MIME + negative | 각 transaction에 조건 교집합 적용 |
| response 없음 | all에서는 표시, status filter에서는 제외 |

위 필터 전환은 하나의 canonical result 목록을 재사용하는 테스트로 모두 통과했다. source 재scan 없이 visible index만 `3 → 1 → 1 → 3`으로 다시 계산하며, JSON·HTML·negative·조합 조건도 transaction별로 통과했다.

### 3.3 Build matrix

각 API 버전마다 별도의 build directory에서 다음 clean test를 실행했다. 최종 clean package는 Montoya `2025.8` 기준 `.work\release-final-0.4.6`에서 실행했다.

```powershell
mvn -Dmontoya.version=2025.8 clean test
mvn -Dmontoya.version=2025.10 clean test
mvn -Dmontoya.version=2025.12 clean test
mvn -Dmontoya.version=2026.7 clean test
mvn clean package
```

기록할 값:

| Montoya API | tests | failures | errors | skipped | 상태 |
|---|---:|---:|---:|---:|---|
| `2025.8` | 187 | 0 | 0 | 1 | 통과 |
| `2025.10` | 187 | 0 | 0 | 1 | 통과 |
| `2025.12` | 187 | 0 | 0 | 1 | 통과 |
| `2026.7` | 187 | 0 | 0 | 1 | 통과 |

skip 1건은 Windows의 case-insensitive 파일시스템에서만 제외되는 `OutputPathAllocatorTest.preservesCaseDistinctPathsOnCaseSensitiveProviders`다. 기능 실패나 API 버전 차이에 의한 skip이 아니다.

### 3.4 배포 JAR 검사

- 산출물: `target\burp-workbench-extension-0.4.6.jar`
- manifest: `Implementation-Version: 0.4.6`
- 제품 bytecode의 `ProxyHttpRequestResponse.id()` 직접 method reference: 0개
- 모든 Montoya reference가 compile baseline `2025.8`에 존재
- Montoya class: JAR에 포함하지 않음
- Brotli/Rhino runtime class: JAR에 포함
- `target\original-burp-workbench-extension-0.4.6.jar`는 배포하지 않음

검사 결과:

- `Implementation-Version: 0.4.6`, Java class major version `61`(Java 17)
- 제품 class 139개, `ProxyHttpRequestResponse.id()` 직접 source/bytecode reference 0개
- Montoya class 0개, Rhino class 461개, Brotli class 20개
- target 배포 JAR 크기 1,688,273 bytes
- target 배포 JAR SHA-256: `C036C906164BE9E6DE1B48DA2A1B2E3B8DCB7C50477707121F870C9FD646B4C7`

기본 `target`의 `mvn clean package`는 현재 Burp 프로세스가 잡고 있는 `target\burp16582744029927785686.tmp\10` 잠금 때문에 clean 단계에서 실패했다. 대신 격리 디렉터리에서 clean package를 통과시킨 뒤 기본 `target`에는 `mvn package`로 배포 JAR을 생성했다. 두 shaded JAR의 625개 ZIP entry 내용 hash는 모두 동일하고 ZIP timestamp만 다르다. Burp에서 기존 extension을 unload하면 기본 `target` clean package를 다시 실행할 수 있다.

## 4. 실제 Burp 수동 검증 — 미실행

자동 test double과 bytecode 검사는 Burp UI의 실제 context event, source list와 heap 특성을 완전히 대신할 수 없다. 아래 검증은 실제 Burp에서 별도로 수행해야 하며 현재 모두 미실행이다.

### 4.1 Burp `2025.9.3`

- 예상 Proxy mode: `LEGACY_METADATA`
- extension 로드 및 Search++ 초기화 로그
- Target > Site map tree에서 domain 선택 후 scope가 해당 domain으로 제한되는지
- Site Map table에서 같은 endpoint 여러 transaction exact 선택 후 모두 검색되는지
- Proxy history 같은 endpoint 반복 transaction 검색 결과 수
- 첫 검색, 두 번째 검색, 검색 취소 후 재검색
- status/MIME/negative filter의 transaction별 결과
- `NoSuchMethodError`, OOM와 비정상 반복 여부
- 검색 전·중·후 heap과 결과 수

상태: 미실행.

### 4.2 Burp `2026.7.1`

- 예상 Proxy mode: `HISTORY_ID`
- `2025.9.3`과 동일한 Site Map tree, exact context, Proxy 반복 검색 및 filter 시나리오
- 첫 검색, 두 번째 검색, 검색 취소 후 재검색
- 결과 identity 중복과 누락 여부
- `NoSuchMethodError`, OOM와 비정상 반복 여부
- 검색 전·중·후 heap과 결과 수

상태: 미실행.

### 4.3 수동 결과 기록표

| Burp | mode | 시나리오 | 입력/일치 transaction | 표시 결과 | heap 전/최대/후 | 결과 |
|---|---|---|---:|---:|---|---|
| `2025.9.3` | `LEGACY_METADATA` | 첫 검색 | 미실행 | 미실행 | 미실행 | 미실행 |
| `2025.9.3` | `LEGACY_METADATA` | 두 번째 검색 | 미실행 | 미실행 | 미실행 | 미실행 |
| `2025.9.3` | `LEGACY_METADATA` | 취소 후 재검색 | 미실행 | 미실행 | 미실행 | 미실행 |
| `2026.7.1` | `HISTORY_ID` | 첫 검색 | 미실행 | 미실행 | 미실행 | 미실행 |
| `2026.7.1` | `HISTORY_ID` | 두 번째 검색 | 미실행 | 미실행 | 미실행 | 미실행 |
| `2026.7.1` | `HISTORY_ID` | 취소 후 재검색 | 미실행 | 미실행 | 미실행 | 미실행 |

## 5. Release 판정 기준

다음 항목이 모두 사실로 기록되기 전에는 이 문서에서 0.4.6 전체 검증 완료를 선언하지 않는다.

1. 전체 단위·회귀 테스트 통과
2. 네 Montoya API build matrix 통과
3. 0.4.6 shaded JAR 및 bytecode 검사 통과
4. Burp `2025.9.3`의 `LEGACY_METADATA` 수동 검색 통과
5. Burp `2026.7.1`의 `HISTORY_ID` 수동 검색 통과
6. 양쪽 Burp에서 exact context, tree scope, 반복 검색, 취소·재검색과 transaction별 filter 확인
7. 결과 누락·중복, `NoSuchMethodError`, OOM 또는 비정상 loop가 없음을 확인
