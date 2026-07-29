# Burp Workbench 0.4.5 Montoya 호환성 검증 보고서

## 1. 결론

0.4.5 계획의 소스 구현과 자동 검증은 완료됐다. Montoya API
`2025.8`, `2025.10`, `2025.12`, `2026.7` 각각에서 최신 소스를
`clean test`했고, 네 환경 모두 테스트 178개 중 실패 0, 오류 0,
조건부 skip 1개였다.

최종 배포 JAR은 Montoya `2025.8` 기준의 별도 clean package에서
생성한 뒤 아래 경로에 배치했다.

```text
target\burp-workbench-extension-0.4.5.jar
```

정적·단위 검증으로 확인할 수 없는 실제 Burp `2025.9.3`과
`2026.7.1`의 반복 검색 및 heap 측정은 아직 실행하지 않았다.
따라서 자동 release gate는 통과했지만 실제 Burp smoke/heap gate는
이 문서의 수동 검증 항목으로 남아 있다.

## 2. 구현 대조

| 계획 | 구현 및 확인 결과 |
|---|---|
| 버전 `0.4.5`, 기본 Montoya `2025.8` | POM 반영 |
| Montoya dependency `provided` | 유지, 배포 JAR에 Montoya class 0개 |
| 단일 JAR 자동 호환 | runtime capability에 따라 `HISTORY_ID` 또는 `LEGACY_METADATA` 선택 |
| 직접 `item.id()` 금지 | 제품 class 138개 전체 bytecode 검사에서 직접 method reference 0개 |
| 문자열 reflection 및 캐시된 `MethodHandle` | class 초기화 때 한 번 탐지하여 불변 runtime partitioner에 보관 |
| lookup 실패 시 Legacy fallback | method 부재, access/runtime/linkage 실패를 `LEGACY_METADATA`로 처리 |
| ID 항목 실패 격리 | runtime/linkage 실패는 malformed item으로 전달하고 mode는 변경하지 않음 |
| OOM 처리 | `VirtualMachineError`를 포함한 일반 `Error`는 삼키지 않고 기존 fatal 경로로 전파 |
| Legacy key | epoch second, nano, listener port, final request 우선 method/URL을 31 기반 long hash로 조합 |
| metadata 부분 실패 | 숫자 `0`, 문자열 `""`; final request가 없거나 실패하면 request 사용 |
| body·object identity/hashCode 금지 | 구현과 guarded test로 확인 |
| Proxy 32분할·취소 지점 유지 | `PARTITION_COUNT=32`, 기존 partition loop/cancellation 구조 유지 |
| 초기화 정보 로그 | Burp version, partition mode, Montoya compile baseline을 Search++ 초기화 때 output log에 1회 기록 |
| legacy Extractor 정리 | 지정된 타입 5개와 `com.burpworkbench.tests` 테스트 파일 9개 제거 |
| UI·결과 정책 유지 | Search++ UI, 무제한 결과, 한글·ASCII·정규식 검색 정책은 변경하지 않음 |

제거한 타입은 다음과 같다.

- `ExportPlanner`
- `ExportPlan`
- `ExportAction`
- `ExportActionType`
- `StatusFilterMode`

삭제한 legacy 테스트의 중복·압축 해제 후 중복·무응답 시나리오는
현행 `ExportServiceTest`가 실제 파일, manifest, summary까지 검증한다.
이전 status/MIME/extension planner 테스트는 현행 Extractor에 존재하지
않는 정책을 대상으로 하므로 옮기지 않았다.

## 3. 빌드 매트릭스

검증 환경:

- Windows 11
- JDK `21.0.9`, compiler release `17`
- Apache Maven `3.9.9`
- 실행 일자 `2026-07-29` KST

| Montoya API | tests | failures | errors | skipped | 결과 |
|---|---:|---:|---:|---:|---|
| `2025.8` | 178 | 0 | 0 | 1 | 통과 |
| `2025.10` | 178 | 0 | 0 | 1 | 통과 |
| `2025.12` | 178 | 0 | 0 | 1 | 통과 |
| `2026.7` | 178 | 0 | 0 | 1 | 통과 |

skip 1개는 Windows에서 성립하지 않는
`OutputPathAllocatorTest.preservesCaseDistinctPathsOnCaseSensitiveProviders`
조건부 테스트다. 0.4.5 호환 변경과 관계없는 기존 환경 조건이다.

실행 중인 Burp가 프로젝트 `target\burp*.tmp` 파일을 사용 중이어서
기본 `target`을 지우는 첫 clean은 파일 잠금으로 실패했다. Burp
프로세스와 그 임시 파일을 강제로 건드리지 않고, POM의 기본값은
계속 `target`으로 유지하면서 검증 실행에만 다음과 같이 격리된
build directory를 지정했다.

```powershell
mvn -Dmontoya.version=2025.8  -Dworkbench.build.directory=.work\maven-matrix\2025.8  clean test
mvn -Dmontoya.version=2025.10 -Dworkbench.build.directory=.work\maven-matrix\2025.10 clean test
mvn -Dmontoya.version=2025.12 -Dworkbench.build.directory=.work\maven-matrix\2025.12 clean test
mvn -Dmontoya.version=2026.7  -Dworkbench.build.directory=.work\maven-matrix\2026.7  clean test
```

최종 JAR도 Montoya `2025.8`과 격리된 release build directory로
`clean package`한 결과를 `target`의 배포 경로에 복사했다. 따라서
실행 중인 Burp가 만든 잠긴 임시 파일이나 과거 incremental class는
최종 산출물에 들어가지 않았다.

## 4. 호환 및 회귀 테스트 범위

새 호환 테스트는 다음을 확인한다.

- API별 runtime `id()` capability와 실제 캐시된 MethodHandle 호출
- 음수 history ID를 포함한 정확히 한 partition 배치
- ID runtime/linkage 실패 후에도 `HISTORY_ID` mode 고정
- Legacy key의 32회 호출 안정성
- 동일 URL 대량 요청과 동일 timestamp 요청의 분산
- 의도적인 31 기반 hash 충돌
- time, listener port, final request, request, method, URL의 null·예외
- 한 metadata accessor 실패가 다른 정상 metadata를 버리지 않는지
- body 및 object `hashCode()`를 읽지 않는지
- key가 충돌해도 서로 다른 Proxy 결과를 제거하지 않는지
- Proxy 단독 및 Target+Proxy 단일 실행
- context scope가 Target과 Proxy 양쪽에 적용되는지
- 한 ID 불량 항목 뒤의 정상 항목을 계속 검색하는지

기존 회귀 테스트는 다음 정책을 계속 확인한다.

- partition 사이 취소와 다음 run permit 회수
- 취소·종료 뒤 새 검색 실행 가능
- 한글 charset fallback, ASCII literal, 정규식
- 정규식 item timeout 후 다음 item 처리
- malformed item 격리
- canonical result와 partial/filter 결과 보존
- Extractor streaming, cancellation, OOM 전파 및 terminal file 처리

## 5. 최종 JAR 검사

| 항목 | 결과 |
|---|---|
| 파일 | `target\burp-workbench-extension-0.4.5.jar` |
| 크기 | 1,687,080 bytes |
| SHA-256 | `9C0A4EC2428A46DAC11383C601EE69708699F829CA21CD37E86FC22BE255874E` |
| `Implementation-Version` | `0.4.5` |
| Montoya service entry | `com.burpworkbench.BurpWorkbenchExtension` |
| 제품 class | 138 |
| 직접 `ProxyHttpRequestResponse.id()` bytecode reference | 0 |
| Montoya class entry | 0 |
| Rhino entry | 461 |
| Brotli entry | 20 |
| 제거 대상 legacy class | 0 |

최소 API `2025.8` clean compile 성공은 제품의 모든 정적 Montoya
참조가 최소 API에 존재한다는 build gate다. reflection 구현에 필요한
문자열 `"id"`는 의도적으로 남으므로 단순 문자열 검색 대신
`javap -verbose`의 소유 타입이 명시된 `Methodref`와
`InterfaceMethodref`를 검사했다.

## 6. 독립 안정성 감사

두 독립 감사에서 P0/P1 결함은 발견되지 않았다.

감사에서 발견한 낮은 확률의 linkage 경계는 수정했다. capability
탐지 중 `LinkageError`는 Legacy fallback하고, 선택된 ID handle이
특정 item에서 `AbstractMethodError` 같은 linkage 오류를 내면 그
item을 malformed로 격리한다. OOM 같은 JVM fatal error는 여전히
전파한다. 이 경계는 최신 API matrix에서 실제 reflective handle을
호출하는 테스트로 다시 검증했다.

## 7. 동작상 비용과 남은 위험

- `LEGACY_METADATA`는 32번의 Proxy history filter 순회마다 metadata
  hash를 다시 계산한다. 전체 history를 캐시하지 않으므로 메모리는
  보존하지만 `HISTORY_ID`보다 CPU 비용이 크다.
- metadata가 완전히 같은 item이나 hash collision은 같은 partition에
  모일 수 있다. 결과를 제거하지는 않지만 해당 partition의 반환
  batch가 다른 partition보다 커질 수 있다.
- ID accessor가 불량인 한 item은 Burp가 32번 filter를 호출하는 동안
  각 pass에서 malformed로 관찰될 수 있다. 검색은 유한한 32 pass
  뒤 끝나고 경고 출력은 기존 제한 뒤 억제되지만, malformed 진단
  수치는 실제 고유 item 수보다 클 수 있다.
- 결과 상한은 추가하지 않았으므로 사용자가 매우 넓은 검색을 하면
  결과 metadata와 임시 저장 공간은 계속 증가할 수 있다.
- 단위 테스트와 API JAR matrix는 실제 Burp 내부 history 구현과
  heap 회수 시점을 재현하지 않는다.

## 8. 실제 Burp 수동 gate

아래 항목은 아직 **미실행**이다. 자동 검증 결과로 성공했다고
간주하지 않는다.

| 런타임 | 확인 항목 | 상태 |
|---|---|---|
| Burp `2025.9.3` | `LEGACY_METADATA` 로그, Proxy 첫 검색·두 번째 검색·취소 후 재검색, `NoSuchMethodError`/OOM | 미실행 |
| Burp `2026.7.1` | `HISTORY_ID` 로그, 같은 세 검색 흐름과 기존 결과 수 | 미실행 |
| 양쪽 | 첫/두 번째/취소 후 재검색의 결과 수, 시작/peak/종료 heap 비교 | 미실행 |
| 양쪽 | 한글·ASCII·정규식, Target+Proxy, Site map/context smoke test | 미실행 |

수동 검증에서는 같은 프로젝트와 검색어를 사용하고 각 실행마다
결과 수, 시작 heap, peak heap, 종료 후 heap을 기록해야 한다.
두 번째 검색이 첫 번째와 같은 결과 수로 끝나고, 이전 실행의
collection을 계속 보유한 형태로 heap baseline이 누적 상승하지
않는지를 확인한다.
