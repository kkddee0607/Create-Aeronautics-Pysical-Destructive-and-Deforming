# PDD 0.6.1-originFix-buildFix — 실패 분석 및 검증 인계

## 기준
- 기준 저장소: `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`
- main SHA: `238037e563bb5c4c231f8bde605c14e7a69598f5`
- 원본 392개 파일: Git Blob SHA, 경로, 실행 권한 대조
- 직접 실패 로그: GitHub Actions `45613784031`, `2026-11-25`, JUnit 798개 실행 중 1개 실패
- 실패: `DuperFixBilateralSurfaceTest.disconnectedPartialCollisionBoxesDoNotBridgeTheFloodFill()` line 130, expected 1 actual 2
- compileJava, compileTestJava 성공. JUnit 실패로 jar/build 후속 단계 미실행.

## 원인
`BilateralContactSurface.Search.run()`의 양측 탐색에서, 한쪽 격자의 두 인접 충돌 면은 이어져 있어도 반대편 블록의 두 부분 면은 실제로 떨어져 있을 수 있다. originFix는 진행 중인 측의 `touchingFaceCells`만 검사하고 반대편 대응 셀이 교체될 때 반대편의 `touchingFaceCells`를 검사하지 않았다. 그 결과 떨어진 A면이 B의 연속된 충돌면을 경유하여 허위로 하나의 연결된 접촉 영역으로 승격됐다.

## 코드 변경
- `BilateralContactSurface.Search.connectedTransition()`에서 변경된 **양측 각각**에 대해 외부면 접선 이음새 연결성을 확인. 원래와 동일한 셀은 여러 상대 셀과 실제로 접촉할 수 있으므로 이음새 추가 요구하지 않음.
- 정상적인 `1개 A셀 ↔ 2개 B셀` 겹침, 양측 100×100 정렬 평면, Native 기록 중복 통합 등 기존 originFix 기능을 유지.
- 기존 실패 JUnit 기대값 1을 바꾸지 않고 정확한 대응 블록 쌍까지 고정. A/B 반전 공백 회귀 및 두 개의 독립적인 Native 증거가 있는 불연속 접촉면 승인 회귀를 추가.
- F/Q, 탄성 0.4, G(F), 손상·파괴·Stress, Sable 원본 콜백, owner 세대·좌표, WAL·fsync·checkpoint/ACK 계약 유지.
- 신규 의존성 없음. Apply 보호 정책 및 Gradle Wrapper 변경 없음.

## 기존·신규 JUnit 검토
- 이전 `@Test` 798개 유지. 신규 테스트 2개. 최종 정적 선언 수 800개.
- `disconnectedPartialCollisionBoxesDoNotBridgeTheFloodFill` 원래 기대값 1 유지 (거짓 면 승격 금지).
- `connectedAFaceCannotBridgeDisconnectedBBoxes` 반대 방향도 동일하게 1.
- `independentNativeWitnessesCanValidateTwoDisconnectedAreas` 직접 Native 증거가 두 영역 모두 존재하면 2.
- `OriginFixSurfaceTest.bSideFrontierReachesSecondOpposingCellWhenASideHasOneCell` 기존 기대값 2 유지.
- `OriginFixSurfaceTest.largeAlignedFaceTestsOnlyTrueOpposingCellsNotBoundaryFanout` 100×100 = 10,000 블록 쌍, geometry checks 10,000 유지.
- 새로운 기하·탄성·전파 수식 변경이 없으므로 다른 테스트의 수치 기대값을 임의 변경하지 않음.
- `scripts/check-duperfix-contract.py`에 현재 버전 및 선언 수 800 반영. `scripts/check-superfix-assertions.py` 하한도 800으로 갱신.

## AI 사전 검증과 한계
- 전체 Java 문법 파싱 및 변경 메서드의 정적 논리·타입 검토 수행.
- 동일 Cell/인접 Cell, 누락 Cell, 비연결 형상, 직접 Native 다중 증거, 회전·1:N 대상·성능 상한의 예상 경로를 별도 검토.
- **프로젝트 규정에 따라 실제 Gradle 또는 JUnit은 AI가 실행하지 않음.** 기존 Actions의 나머지 797개 통과는 이번 수정판 800개 통과를 의미하지 않음.
- 물리 이벤트에 Native가 단일 점만 주는 경우와 Sable post-effect 시점의 한계는 여전히 존재하며, 모든 인게임 F 분포나 TPS 개선을 확정하지 않는다.
- World/Plot WAL sync I/O, 대형 Stress/Commit 작업과 틱 간 면 복원 재개는 구현하지 않았다.

## 사용자 실행 및 판정
1. 전체 ZIP 파일 하나만 기존 `incoming/`에 업로드하고 Apply 실행. 다른 ZIP과 중복 업로드하지 않음.
2. `python3 scripts/check-duperfix-contract.py`
3. `python3 scripts/check-superfix-assertions.py`
4. `./gradlew clean test --no-daemon`
5. `./gradlew build --no-daemon`
6. `compileJava`, `compileTestJava`, JUnit 800개, `processResources`, `jar`, Actions 결과를 확인. 인게임에서는 단일 Native 점에서 연결된 넓은 면의 F 배분과 불연속 접촉면의 거짓 전파 방지를 각각 한 번씩 확인.
7. 인게임 상세 로그는 필요할 때만 켜고, F/Q 원장, `CONTACT_UNPROVED`, `[PDD-CONTACT-PATCH]`, `[PDD-R2-PERF]`, World/Plot Commit 및 저장 로그를 제출.

예상 단일 설치 JAR: `physical-destroy-deform-0.6.1-originFix-buildFix.jar`. NeoForge 표시·시작 로그·README·JAR Manifest는 동일 버전.
