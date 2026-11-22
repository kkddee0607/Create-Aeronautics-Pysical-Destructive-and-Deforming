# PDD 0.6.1 spreadFix — 회귀 및 검증 계약

기준 main: `a6d3123091016081bde150adafc6cd7c7b04ec2c` (351개 원본 파일, 653개 기존 @Test). IDS 1.0.9, Minecraft 1.21.1, Java 21, NeoForge 21.1.228, Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2, 선택 호환 Copycats+ 3.0.11.

## 실제 0.6.1 fix 로그의 근거

- 206 Credit 전부 F/Q PASS, 전체 미확정 F 1.307%, Plot 간 1.948%. 기존 직접 접촉 탄성 계수 0.4 유지.
- 98개 secondary 후보가 모두 STALE_STAMP (74개 audit 로그), history=0, crossEdges=0. 190개 동일 서버 처리 틱의 native/commit 기록에서 GameTime 차이 +1. tick 79663에는 Plot 23/6 approved, World/6 duplicate가 동시에 관측됐다.
- 11개 상태 이전 중 10개 APPLIED, 1개 QUARANTINED. 실패 transfer `53ce44e4-8c57-4be4-ba5d-d7cc42cf70a8`: 금 블록, source Plot `67a4fb82-5e32-4b5c-b9ff-0c5004fdac0a` (-2,-2,0), destination `bbe6a426-65fd-4233-bda2-3cddb3d3cf03` (0,0,0), damage=354, remainder=203, deformation=0. 원본 교체 로그 없음, 목적지 교체 존재, parent orphan 직후. 이후 동일 목적지 8개 DAMAGE_PERMISSION_DENIED. 정확한 CAS 실패 조건은 옛 로그에 없다.

## 불변 계약

- 직접 접촉 `t_c(E)=1-0.4*(1-t(E))`, E=100 F retention 66%; 기존 내부 F retention 15% 및 자기 Q 감쇠, 재질표, stress owner-local Q 원장, 물성/변형 계산식 및 commit 정책 유지.
- 기존 F nodes 32768/측, cross-owner depth 4, edges 128/사건, owners 8, history TTL 20 및 cap 4096, splitTransferCap 4096 유지. 과거 이력 단독으로 간선 승인 금지, 동일 원인의 native Credit 추가 발행 금지. 현재 native 증거와 형상/owner/재질/압축 방향 검증 필수.
- schema 6 기존 판독, 기존 terminal receipt 변경 금지, 원본 Sable callback 횟수/순서/반환 그대로. 새 native 호출, chunk 강제 로드, 실제 블록 이동 재실행, world scan 금지. 기존 V1 격리는 로그/좌표/재질만으로 복원하지 않는다.

## API 검토

첨부 Sable 2.0.6 JAR의 `SubLevelPhysicsSystem.getLevel():ServerLevel`, `SubLevelAssemblyHelper.moveBlocks(ServerLevel, AssemblyTransform, Iterable<BlockPos>):void`, `PhysicsPipeline.readPose(ServerSubLevel,Pose3d)` 및 기존 nested Rapier `clearCollisions(long):double[]` 사용 경로를 대조했다. 신규 의존성 추가 없음. 기존 telemetry의 원본 array 반환 보존. Sable JAR SHA-256 `9c199157843bf953a47dff625fc5bb41c01a90538d35ad18e6996bbcb8a617c2`; Copycats+ `eab9c71e0cc133c38f1063fc9c892599207240a1f0559ce1e3ecddc4ad7aa759`.

`moveBlocks` 바이트코드에서 목적지 setter(offset 529), 목적지 BE load(offset 569)·afterMove(offset 604), 원본 `onBlockStateChange`(offset 882), 원본 `LevelChunk.setBlockState`(offset 892) 순서를 확인했다. 원본 제거 통지가 setter 추적보다 먼저 발생할 수 있다. `REMOVED`는 Plot 격자 점유를 해제하지만 `UNLOADED`는 해제하지 않으므로 단순 UUID 부재는 제거 증거가 아니다.

## 구현 경로

- PRE와 native 효과의 캡처 시점에 서버/차원 객체 identity, 서버 틱, native game time을 함께 기록한다. Post 처리의 현재 서버 틱과 비교하고 정확한 native game time 및 PRE epoch의 일치 여부를 별도로 검증한다. 서로 다른 서버/차원/이전 서버 틱 또는 모순된 배치를 연결하지 않는다. `GameTime+1` 자체는 거절 조건이 아니다. 관찰·그래프 조회 양쪽에 동일 시간 게이트를 적용한다.
- 기존 접촉 history는 서버 틱 기반 TTL로만 유지한다. history 단독 승인 금지. 동일 배치에서 직접 Credit 발행 경계는 secondary 제외. 기존 native 입력 경로·F/Q 상한·탄성 계수 유지.
- 새 상태 이전은 기존 schema 6의 두 receipt를 통해 V2 증거를 기록한 후, 정확한 source/destination before-image에 대한 CAS로 누락된 교체 revision만 한 번 보정한다. 원본 OUT 상태 제거가 선행하고 destination IN에 손상 상태를 원자적으로 발행한다. 두 receipt 양쪽의 증거를 연결하며 이미 적용된 terminal receipt는 수정하지 않는다. 확정된 V2 복구는 native 이동을 재실행하지 않는다.
- 재시작 복구는 서버 스레드에서 실제 로드된 대상만 검사한다. 한 틱 최대 8개, 기존 `MAX_DEFER_TICKS`(1200틱) 유효 기간 및 시도 한도를 유지하고 시간이 역행하면 격리한다. Plot 원본이 미로드인 경우 부재만으로 손상을 이전하지 않는다. 저장된 정확한 원본 center/incarnation, 제거 증거 및 빈 Plot 점유 슬롯을 확인할 수 있어야 한다. 목적지 fingerprint, incarnation, BE payload digest, 독립 격리 및 CAS를 다시 검증한다. 이전의 V1 격리(위 금 블록의 354/203/0)는 자동 복원 대상이 아니다.
- WAL append 결과가 불확실하면 추가 append와 checkpoint를 차단하고 원래 WAL을 보존한다. fault 상태의 종료 checkpoint도 생략한다. 새 V2 marker는 500자 이내로 제한하며 격리 접미사를 붙여도 schema 6 receipt 최대 512자 내에 들어가도록 한다.

## 사전 검증 및 테스트

원본 653개 + 신규 34개(충돌 및 시간/전파 12, lineage/CAS/복구 22) = **687개 @Test 정적 집계**. 기존 테스트 제거·비활성화 없음. 최종 총 실행 수는 JUnit 발견 결과로 확인한다. Java 21 파서로 223개 Java 소스 구문 검사 오류 0, `git diff --check` 통과, 독립 순수 Java 수학·원장·전파 타입 검사 통과. Minecraft/NeoForge/Sable/JUnit 전체 컴파일 및 실제 실행 결과는 NOT_RUN; JAR API 검토와 가상 실행은 런타임 시험을 대체하지 않는다. 기존 `.github`, Gradle Wrapper, Gradle settings 및 properties 원본과 동일.

## 적용

1. 기존 월드 전체를 백업한다. 하나의 버전만 설치한다. 기존 격리 블록의 손상 상태는 추정 복원하지 않는다.
2. `incoming/`에 전체 ZIP 하나만 업로드해 기존 Apply 진행. 기존 `.github`, wrapper, gradle 보호 규칙 유지.
3. 사용자: `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon`. Build Actions artifact PDD-0.6.1의 `physical-destroy-deform-0.6.1-spreadFix.jar`. 실제 테스트는 사용자 실행 전 NOT_RUN.
4. 시작 로그 `revision=0.6.1-spreadFix` 확인. 필요 시 JVM 옵션 `-Dpdd.diagnostics.fullTrace=true`.

## 세션 A — 연쇄 충돌 및 탄성/회귀

1. E=0의 P2가 정적 S1에 실제 면 접촉한 상태에서 P1로 압축한다. `PDD-CROSS-OWNER-AUDIT`의 시간·증거 상태, `PDD-CROSS-OWNER admitted>0`, 동일 Credit의 `PDD-CHAIN-LEDGER crossEdges>0, actualOwners>=3` 및 World S1 F 할당을 함께 확인한다. 기존 P2/S1 duplicate Q=0. 물리 기록이 누락되거나 방향/형상/인과가 불확실하면 거절이 정상이다. 모든 formerly stale 후보가 승인돼야 한다는 의미는 아니다.
2. 실제 간극, 역압축, 회전 Plot, 독립 후속 충돌, 장시간 정지 접촉 대조. 분리 및 독립 충돌은 별도 정당한 Credit 허용; 정지에서는 신규 F 발행 금지. 각 `PDD-F-INVARIANT FStatus=PASS QStatus=PASS`.
3. E=0/E=100 단측/양측 및 혼합 재질, 슬라임 바퀴와 도로, 금 World/Plot 변형 회귀. alpha=0.4, E=100 상대 측 초기 F 66%, 기존 내부 E 보존. 동일 조건으로 비교하며 단순 최종 파괴율을 F 유지율로 해석하지 않는다.

## 세션 B — 마지막 파편 및 영속성

1. 피해가 다른 금 블록들을 포함한 Plot을 분리한다. 잔류 블록, 여러 파편, 마지막 원본 Plot 제거를 각각 관찰한다. 원본/목적지 state 및 revision, transferId, damage/remainder/deformation 모두 일치하고 이전 1회만 적용돼야 한다.
2. Copycats+ 재질/소비 아이템/BE NBT, Glue가 있는 구조물 분리·조립·해체 회귀. 정확한 native setter 및 readback 증거가 없는 경우 격리가 정상이다. 정상 이전 목적지는 이후 충돌에서 권한 거절되지 않아야 한다.
3. 정상 저장·종료 및 재접속 후 상태와 추가 충돌. 강제 종료 시험은 원본 월드가 아닌 복제본에서만 실행한다. 증명된 상태 이전 재실행 시 중복 손상/드롭 금지, 불확실한 기존 V1 격리 보존.
4. 수집: `latest.log`, 실제 Apply/Gradle/JUnit 결과. 필수 게이트 G-SPLIT-01, G-CC-LINEAGE, G-CONTACT-ELASTIC, G-CROSS-OWNER, 0.6.0 회귀는 실제 검증 이전 미확정. STALE_STAMP 이후의 형상/인과 실패 사유는 별도 진단한다.
