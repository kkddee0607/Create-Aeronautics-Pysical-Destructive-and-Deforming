# PDD 0.6.1 superFix — 접촉면 복구·양측 F 증명 및 회귀 검증

**기준**: GitHub main `eb25244f133442550449ece88b3ff87128ad84fc`, IDS 1.0.9. 최우선 수정 목표는 기존 0.4.0 계열의 직육면체 전체 접촉면을 실제 PDD `issuePropagatedTargets()` 경로까지 연결하는 것이다. `0.6.1-optiFix3-buildFix`의 기능·영속성·API 계약을 보존한다.

## 실패 원인과 실제 수정

1. Native 극단점에서 복원된 `ContactFacePatch` 셀은 기본적으로 반대편 대상 증거가 없어 `ContactCreditLedger.contactPlan()`에서 `CONTACT_UNPROVED`로 보류됐다.
2. Native 관측 100건 포화는 `TargetDirections.forEvent()`를 비활성화해 접촉면 재질 증명까지 차단했다. superFix는 **방향·변형 권한의 기존 거절은 유지**하되, 유효한 Native 개별 원본 기록에 대해서만 `VerifiedContactGraph.revalidateGeometry()`의 원래 양측 프레임 검증을 별도로 사용한다.
3. 기존 `strictlyWithinPolygon`은 경계 셀 중심을 배제했다. superFix는 기존 movement/planarInterior의 엄격 검증은 남기고, 양측 면 재질 대응에는 **실제 다각형·각 블록 셀의 양수 면적 교집합**을 적용한다.
4. 두 면이 같은 셀 안에 각각 존재한다는 것만으로는 양측 간 교집합을 증명할 수 없다. `commonSurfacePoint`는 두 Native 증거 다각형과 양측 셀, 정확한 Pose/동일 프레임을 모두 교차 검증하고 양수 면적의 내부점을 선택한다. 이후 **양쪽 실제 VoxelShape 외부면을 기존 `NativeAuditBatch`로 다시 검사**하고 고유 대상·fingerprint가 일치해야만 `opposingCandidates`로 연결한다.
5. 각 Native 개별 면별로 대응 Pose를 검증한다. 서로 다른 프레임이나 비정합 면, 불완전한 면, sparse halo, 점·선 겹침, 공기, 미확인 재질은 계속 보류된다.

이 증명은 새로운 solver/manifold/Native 접촉이나 Cross-owner 전파 간선을 생성하지 않는다. 기존 단일 Credit과 F 분배 슬롯을 그대로 사용하며, 미증명 슬롯의 F는 원래 원장대로 unresolved 처리한다. 실효 충격·변형·파괴·Q 기대값을 임의로 증폭하지 않는다.

### 변경한 기존 테스트 (삭제/비활성화 없음)

- `Fix3FaceImpactPipelineTest`: 구형 `issuePropagated()` 대신 실제 `issuePropagatedTargets()`로, 양측 재질 증거 제공 및 F/Q/중복 경로를 검증.
- `FullSurfaceForceBudgetTest`: 실제 `issuePropagatedTargets()` 경로로 전환하고 25+25 개 블록의 기존 분배·Q 기대값 보존.
- `ContactFacePatchTest`: 경계 셀은 피해 증명 후보로 받아들이되 movement direction의 엄격한 내부 증명과 분리. 같은 셀의 분리된 두 접촉 다각형은 거절, 실제 교집합은 내부점 확인.

기존 호환성·기하·저장·응력·드롭·외부 API·Sable 콜백 테스트는 계약이 동일하여 **그대로 유지**한다. 모든 Java 테스트 소스와 `@Test` 선언을 조사했으며, 구형 순수 API 자체에 대한 테스트는 지원하는 인터페이스이므로 삭제하지 않는다.

### 추가 회귀 시나리오

`SuperFixBilateralSurfaceTest`는 5×5 양측 전체 면 복원→양측 재질 페어링→실제 `issuePropagatedTargets()`→F/Q 원장, 실제 100개 Native 접촉 기록 상한·방향 권한 차단 유지, 한쪽 가운데 빈 블록, 비평면 Source, 다른 물리 Pose, 기존 Native 직접 쌍 보존을 검사한다.

총 정적 `@Test` 집계: **781개**(기준 773개 + 신규 8개; 기존 테스트 다수의 실제 호출 경로 및 assertion 수정). **실제 JUnit 실행 결과가 아니며**, 성공 개수는 사용자의 Gradle 테스트에서 확정한다.

## 불변 계약

- 물리·물성 F/Q, alpha=0.4, 내부·Cross-owner 2차 전파, 변형·응력·파괴·드롭, owner/local/world 좌표·generation, 장애 격리 보존.
- Sable 2.0.6 original callback 인수·호출·반환 변경 없음; Create 6.0.10, Aeronautics 1.3.2, Copycats+ 3.0.11 의존성 추가 없음.
- World·Plot WAL, fsync, schema6, manifest, ApplyId·중복 방지 및 이전 세대 정리 정책 변경 없음.
- 캐시는 사건 범위 기존 `NativeAuditBatch`를 사용하며 전역 상주 접촉면 캐시를 만들지 않음. 기존 `16,384 targets/side`, `32,768 unique audited cells/side`, `32,768 regions` 상한 보존.

## 사용자 실행

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

빌드 통과 기준: `compileJava`, `compileTestJava`, JUnit 781개 선언의 실제 결과 확인, `processResources`, `jar`, `build`; 기존 `Build`, `Test`, `Callback Contract Test`, `Apply` Actions 보존. 예상 JAR: `physical-destroy-deform-0.6.1-superFix.jar`.

### 인게임 테스트 1: 넓은 단일 면

- 백업 월드에서 5×5~10×10 실제 직육면체 Plot 면과 평면 World/Plot을 정면 충돌시킨다.
- 수집: `[PDD-CONTACT-PATCH]`의 `facePatchAddedA/B`, `patchMaterialPairedA/B`, `candidateSets`, `unpaired`; `[PDD-F-LEDGER]`의 `CONTACT_UNPROVED`, `unresolvedF`, `[PDD-CHAIN-LEDGER]`, 블록별 F/Q/손상·파괴 로그.
- 성공: 정확한 양측 접촉 영역의 가장자리·내부에도 F가 분배되고 불필요한 미증명 슬롯이 줄어들며, 충돌 사건별 F/Q 불변식 PASS·중복 Drop 0. 파괴 여부는 각 블록의 Q와 물성 임계값에 종속되므로 동일하게 파괴될 필요는 없음.

### 인게임 테스트 2: 거절·호환·재접속

- 일부 공기·반블록·Copycat·회전 Plot/Plot·분리된 접촉면의 복합 충돌 후 정상 저장·종료·재접속.
- 성공: 비접촉/공기/미확인 상태의 허위 F/Q 0, Sable 콜백 원본 보존, 실효 World/Plot 손상·NBT·owner generation 복원, F/Q PASS, 영수증 재발행 없음. 네이티브 100건 포화 시 방향·변형 승인 없이 증명된 양측 재질만 지급.
- 실패 시 최초 `compileJava` 또는 `compileTestJava` 오류 및 JUnit 실패 Assertion, 인게임 관련 Credit ID와 접촉 패치/원장/저장 로그를 제출.

## AI 검증 한계

Java 21의 컴파일러 파서로 전체 Java 문법을 검토하고, 외부 API 신규 의존성 및 자료형·좌표·F/Q 계약을 정적으로 분석했다. 실제 Gradle/JUnit/Actions/인게임은 AI가 실행하지 않았으므로 **미검증**이다. 실제 Sable이 동일 시점의 정확한 양측 Pose를 제공하지 못한 사건에서는 기존 fail-closed 정책으로 F를 보류할 수 있다. 이 증명 가능한 범위 밖으로 임의 충돌면을 확장하지 않는다.
