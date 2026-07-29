# Burp Workbench 0.4.5 Montoya 호환성 구현 계획

## 1. 목적

- Burp Suite `2025.9.3` 이상에서 하나의 Search++ 배포 jar를 사용한다.
- Montoya API `2025.10`에서 추가된 `ProxyHttpRequestResponse.id()`를 Burp `2025.9.3`에서 직접 호출해 발생한 `NoSuchMethodError`를 제거한다.
- 0.4.4의 Proxy 32분할 검색, 결과 정책, 검색 취소, malformed item 격리와 메모리 특성을 유지한다.
- 최신 Burp 전용 빌드와 구형 Burp 전용 빌드를 따로 배포하지 않는다.

## 2. 지원 및 빌드 기준

- 최소 지원 Burp: `2025.9.3`
- 기본 컴파일 Montoya API: `2025.8`
- Montoya dependency scope: `provided`
- Java: 17 이상
- 배포 버전: `0.4.5`
- 단일 배포 산출물: `target\burp-workbench-extension-0.4.5.jar`
- `target\original-burp-workbench-extension-0.4.5.jar`는 Maven shade 전 backup이며 배포하지 않는다.

Montoya `2025.8`을 컴파일 기준으로 고정해 최소 지원 런타임에 없는 API를 일반 제품 코드에서 정적으로 호출하지 못하게 한다. Burp가 제공하는 Montoya 구현을 사용해야 하므로 Montoya API는 shaded jar에 포함하지 않는다.

## 3. 런타임 호환성 전략

Proxy partition key 전략을 다음 두 mode로 분리한다.

| mode | 적용 런타임 | partition key |
|---|---|---|
| `HISTORY_ID` | `ProxyHttpRequestResponse.id()`를 제공하는 Burp `2025.10` 이상 | Proxy history ID |
| `LEGACY_METADATA` | 해당 메서드가 없는 Burp `2025.9.3` | Montoya `2025.8`에 있는 metadata로 계산한 long hash |

선택 기준은 Burp 버전 문자열 비교가 아니라 실제 런타임의 `id()` 기능 유무다. 기능은 호환성 경계 안에서 한 번 탐지하고 재사용한다. `id()`를 호출하는 최신 경로는 구형 JVM linkage가 일어나지 않도록 일반 제품 bytecode에서 직접 참조하지 않는다.

기능이 있으면 `HISTORY_ID`를 사용한다. 기능이 없거나 접근할 수 없으면 `LEGACY_METADATA`를 사용하며 검색 자체를 실패시키지 않는다. 자동 선택 결과는 진단할 수 있도록 mode 이름으로 한 번 기록한다.

## 4. `LEGACY_METADATA` key 규칙

구형 key는 초기값에서 시작해 다음 값을 순서대로 `31` 기반 long hash에 섞는다.

1. Proxy item time의 `epochSecond`
2. Proxy item time의 `nano`
3. listener port
4. `finalRequest()`가 있으면 그 request, 없으면 `request()`의 method
5. 같은 request의 URL

각 accessor가 실패하거나 값이 없으면 숫자는 `0`, 문자열은 빈 문자열로 처리한다. 한 필드의 문제 때문에 item이나 전체 검색을 실패시키지 않는다.

다음 값은 key에 사용하지 않는다.

- request/response body
- Java object identity
- 구현체의 `hashCode()`

이 규칙은 body 복사와 대형 할당을 피하고, 동일한 Proxy item이 32회 traversal 중 항상 같은 partition 하나에만 들어가도록 하기 위한 것이다. 서로 다른 item의 hash 충돌은 허용한다. 충돌은 같은 partition에 배치될 뿐 결과 deduplication이나 검색 의미를 바꾸지 않는다.

## 5. 변경 범위

### 포함

- Proxy partition key 계산을 별도 호환성 책임으로 분리
- 런타임 기능 탐지 및 `HISTORY_ID`/`LEGACY_METADATA` 자동 선택
- Search++ Proxy filter에서 선택된 key 사용
- 선택 mode 진단 로그
- 최소 API 및 두 mode에 대한 자동 테스트
- 프로젝트/문서/산출물 버전을 `0.4.5`로 갱신

### 제외

- Target partition 정책 변경
- partition 수 32 변경 또는 adaptive partition 도입
- 검색 결과 상한, 자동 필터, 검색 순서 정책 변경
- 한글/charset/정규식 검색 정책 변경
- Extractor 동작 변경
- Burp 버전별 별도 jar
- Montoya API를 shaded jar에 포함

## 6. 구현 순서

1. `pom.xml`의 프로젝트 버전을 `0.4.5`, 기본 `montoya.version`을 `2025.8`로 변경하고 `provided`를 유지한다.
2. Proxy partition key를 제공하는 작은 호환성 경계를 만든다.
3. 최신 API의 history ID를 안전하게 사용할 수 있는 runtime capability 탐지를 구현한다.
4. 승인된 metadata hash 규칙으로 legacy 전략을 구현한다.
5. `SearchSourceScanner`가 직접 `item.id()`를 호출하지 않고 선택된 전략만 사용하게 한다.
6. mode별 partition 안정성, fallback, 예외 격리와 32분할 중복 방지를 테스트한다.
7. 최소 API 빌드와 최신 API 호환 검사를 모두 실행한다.
8. README와 설치/빌드 산출물 경로를 갱신한다.
9. 자동 및 수동 검증 결과는 구현 완료 뒤 별도 verification 문서에 기록한다.

## 7. 검증 기준

### 자동 검증

- `mvn test`가 Montoya `2025.8` 기준으로 통과한다.
- `mvn clean package`가 성공하고 `target\burp-workbench-extension-0.4.5.jar`를 만든다.
- 제품 코드에 `ProxyHttpRequestResponse.id()` 정적 호출이 남지 않는다.
- `id()`를 제공하는 test double은 `HISTORY_ID` mode를 선택한다.
- `id()`가 없는 test double 또는 동등한 legacy fixture는 `LEGACY_METADATA` mode를 선택한다.
- 같은 item은 32개 partition 중 정확히 하나와 일치한다.
- legacy key는 같은 metadata에 결정적이며 body와 object identity의 영향을 받지 않는다.
- time, request, final request 또는 listener 정보가 없거나 accessor가 실패해도 검색이 계속된다.
- 기존 Search++, Extractor와 lifecycle 회귀 테스트가 통과한다.

### 수동 Burp 검증

- Burp `2025.9.3`: extension 로드, `LEGACY_METADATA` 선택 로그, Tools > Proxy 검색 완료, 재검색과 취소 확인
- Burp `2025.10` 이상: extension 로드, `HISTORY_ID` 선택 로그, 같은 Proxy 검색 완료
- 최신 지원 Burp: Target, Proxy, Site map, Context 검색과 Extractor handoff smoke test
- 각 런타임에서 `NoSuchMethodError`, 중복 결과 증가, item 누락, 비정상적인 heap 증가가 없는지 확인

## 8. 완료 조건

- 지원 범위와 배포 파일이 README/README_ko 및 POM과 일치한다.
- Burp `2025.9.3`과 최신 Burp가 동일한 shaded jar를 로드한다.
- 런타임 capability에 따라 두 mode가 자동 선택된다.
- Proxy 검색의 32분할 및 사용자 결과 정책이 유지된다.
- 자동 테스트, package 검증과 실제 Burp 양쪽 수동 검증 결과가 기록된다.

