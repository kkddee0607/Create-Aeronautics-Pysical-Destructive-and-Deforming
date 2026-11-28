# PDD 0.6.1-duoFix 개발·검증 보고서

## 상태
전체 프로젝트 소스 후보. DCRT 일부 구현, Stress 진단·기존 일괄 무효화 개선, 로그/메모리 계측 개선. 요청 전부의 구현 완료나 0.6.1 gate PASS를 선언하지 않는다. 실제 Gradle/JUnit/Actions/인게임 미실행.

## 기준 및 확보
- repository: kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming
- 최신 main: 6095ceeb81a408ded130ab28324decc2cc384265
- tree: dda886d18f1857bd0807d61f78e49861297419a1
- GitHub API main, commit, 전체 recursive tree 조회. 424 blobs, 5,976,814 bytes, truncated=false.
- 기존 astraFixFix 전체 ZIP + GitHub compare에서 확인된 문서 이동 및 첨부 1.0.10 복원. 로컬 Git write-tree가 최신 main과 정확히 일치. gradlew 100755, 나머지 100644. 최신 설계 blob 503c1a3311960732b469171e81515ccc777336d9.
- 이전 Java/Gradle 기준 f9e55d691c34d79c1c53b967aa6c8031900325b1 이후 변경은 설계 문서 추가 및 이동. 최신 main Actions 조회 결과 실행 없음. 이전 실행 성공은 duoFix의 빌드 검증이 아님.
- 원격 수정/업로드/커밋/푸시/PR/Actions 실행 없음.

## 실제 구현과 판단
- DcrtContactBatch / DirectionalContactRegions: 실제 브리지에서 기존 Pair 평균 법선과 pair-wide 재충돌 audit/cooldown을 대체하는 방향별 추적. 원본 레코드/owner/pose/epoch 검증, complete-link 방향 군집 30도/유지 40도, 지역 상태와 consumed latch. Track 16/Pair, witness 2048, 방향 비교 262144, R2 32768 cells/side/pair 예산 분할, gap 형상 128 boxes 및 world footprint 128 targets, 지역 분리 조회 256/tick. 모든 승인 지역 native record의 최대 F를 사용, 동일 Pair/tick 한 Credit. 독립 접근은 earliest compatible PRE linear closing 및 양측 실제 R2 형상의 pre-gap 또는 기존 full-pair clearance를 요구. 지역의 양측 정확한 PRE shape gap은 기존 APPROACH_DELTA 1/32 사용; cardinal full World cube footprint의 실제 gap은 0.25 및 연속 2회. cap/누락/불완전은 보류. 예외에서는 이전 latch 유지, Q=0. 과거 Pair FSM의 실제 full clearance 경로 보존.
- R2/접촉: 각 승인 지역에 기존 BilateralContactSurface, ContactFacePatch를 적용, 승인 이전에는 패치 자체가 충돌 권한을 만들지 않음. 지역 합집합 및 고유 씨앗, 공동 측별 예산, 상대 E 검증과 최대 매칭 예약 유지. 보수적인 지역별 예약 합은 여전히 과대 보류할 수 있음. 패치 진단은 평면/anchor/잔차/중복/모델/상한 실패를 구분. 1/32 허용 오차와 기존 단일 이상점 부분 합의는 유지.
- F: ContactCreditLedger/ForcePropagation의 지역별 seed Orientation map, 유효한 지급 edge에 방향 연결, 동일 layer 유입 합산 및 G 한 번. 상충 방향은 기존 local half 유지, 추가 전파 몫 unresolved, 이동 방향 veto. 이 정책은 설계의 보수적 UNKNOWN/미확정 선택이며 인게임 튜닝값 아님. 기존 70:30, F/Q cap, 물성, 반분/탄성, 직접/변형/Stress 비율 유지. legacy 단일 방향 API/테스트 보존. cross-owner 기존 검증 경로 유지; 전체 다중 region/owner 교차 전파 회귀 미검증.
- Stress: 기존 persisted Unused enum 순서/저널 포맷 그대로. 선택 로그 STRESS 활성화 때만 별도 파생 노드 깊이와 미사용 세부 분류를 관리. ROOT_REENTRY, EARLIER_DEPTH_REENTRY, SAME_LEVEL_BACKEDGE, LATE_ARRIVAL 및 기존 차단/한도 사유를 실제 분기에서 집계. 기존 방향 fallback은 횟수만 기록, 피해 모델 수정 없음. cap deferred 금액 별도. 새 진단은 현재 session seal 범위, 복원/TTL 전체 원장의 완전한 세부분류를 보장하지 않음. 동일 dirty 셀 중복 검증 회피 및 STRESS_DIRTY_BATCH; 기존 일괄 무효화, 원래 TTL, prefix/partition/ApplyId 보존. 계획 생성/전달과 실제 커밋 Receipt의 STRESS_APPLY_RECEIPT를 구분(동일 ApplyId, 예산상 누락 가능).
- 로그: 기본 OFF 및 legacy 옵션 유지. LogSelection의 R07 COPYCAT 독립, R09/10의 LINEAGE_/RECOVERY_/WAL_ 이유 분류, `pdd.rog.traceJob` 상세 R11 작업 선택. NORMAL 핵심 집계, TRACE 세부 기록, 기존 cap 유지. DCRT_AUTHORIZED_INPUT과 실제 GRANTED DCRT_CREDIT_SEAL 구별. 기존 CONTACT_SEED_COORDS/EFFECTIVE 및 F/Q 회계 로그 재사용. 안전 경고 유지. 모든 기존 Reason을 새 Channel로 재분류하거나 요청한 모든 새 로그를 전부 구현한 것은 아님.
- 메모리: 기존 Heap/Direct/Mapped/GC + /proc/self/status VmRSS, /proc/self/smaps_rollup Pss(256줄 읽기 상한), 구간 GC 증감. 미지원 UNAVAILABLE, Rust/GPU UNMEASURED. 실제 장치 RSS/PSS 미측정.
- HeatMap: 기존 per-native-manager denial-only 캐시 재사용, shared cached denial/live read counter 및 HEATMAP_OWNER_WAIT. 기존 강제 live 재검증 유지; 새 Owner별 큐 스케줄링 미구현.
- 복구: 기존 DEFERRED/PREPARED와 quarantine 정책 유지, 기존 recovery transition에 actual loaded/AIR/matching fingerprint/different 상태 진단. fingerprint 일치가 incarnation 증명 아님. APPLIED 재발행/삭제 없음, 저장 형식·Receipt 인덱스·Lineage/Copycat 데이터 포맷 변경 없음.

## 미충족/위험
- DCRT는 방향 기반 보수적 트랙이다. 같은 방향의 별도 공간 패치/연속적 공간 footprint matching, 완전한 split/merge consumed mapping, 같은 지역의 긍정 gap 없는 별도 국소 재타격, 일반 Plot↔Plot 지역 분리 증명, 회전 점속도 omega×r, 정확한 native substep ID는 미완료. 지역 separation은 제한적 cardinal World full-cube footprint 또는 기존 full pair clearance만 가능. 100-cap은 신규 승인 거절; 기존 대형 충돌에서 보류가 늘 수 있음. strict pre-gap 및 unique R2 witness가 실제 타격에서 부족하면 유효 사건까지 거절 가능. 99+1 방향 군집/closing 각각의 단위 검증을 작성했으나 production end-to-end 승인은 인게임 미검증. 모든 G-REGION-01~08 완료 아님.
- 지역별 seed 방향 출처를 F 엔진에 연결했지만 완전한 region ID/seed provenance 영속화 및 모든 per-region 로그, 모든 다중 방향 합류 경로/양측 cross-owner 회귀는 미완료. 다중 모델/복수 이상점 복원은 실제 실패 원인이 미확정이라 신규 알고리즘을 무조건 적용하지 않음. 기존 successful patch 재현 필요.
- Stress의 완전한 영향 경로별 incremental graph 및 direct-commit 관련 제한 안정화 대기(기존 mutation callback에 원인 식별 근거 부족)는 미구현. 기존 일괄 재검증 유지, 중복 dirty 차단만 실제 최적화. 미사용 Q 감소가 성공 지표 아님. 모든 Root의 F provenance, 모든 edge/level log와 지속적인 실제 Receipt correlation은 미완료. 방향 fallback은 계측 후 결정. 모든 old ordinal/재시작/65536 root gate 회귀 실제 미검증.
- HeatMap은 기존 manager denial cache 계측만 보강, 신규 Owner queue 대기 스케줄러 미구현. 기존 WAL/Receipt recovery 경로 계측; 모든 APPLIED와 실제 월드 상태 대조, 원자적 저장 및 exactly-once Drop 증명 미충족. 기존 Receipt/Stress archive의 모든 비용 계측 및 추가 압축 최적화 미완료. 기존 Lineage/BE/glue/Copycat/변형 코드 유지하되 실제 기능 회귀 미검증.
- 로그 유틸리티는 Reason 접두어 기반의 일부 분류 개선; 전체 ROG Reason의 완전한 Channel 분리, 모든 강제 필수 로그, 활성 전체 구조 메모리 추정 등 미완료. 안전 경고는 OFF에서도 유지. 예산 초과 로그는 생략 가능.
- 비정상 종료 원인, 실제 Native 메모리 사용량, 실제 성능 개선 및 물리 정확성은 미확정. 완전한 0.6.1 기능/빌드/Actions/인게임 gate 전부 미검증.

## API 및 의존성
실제 첨부 4 JAR CRC 검사 정상. Sable 2.0.6 outer 내 sable_rapier 2.0.6, sable-companion-common 1.6.0, Veil 4.3.2 확인. Rapier3D package native clearCollisions(long):double[], getPose(long,int,double[]):void, RapierPhysicsPipeline.physicsTick(double), private processCollisionEffects(), readPose(ServerSubLevel,Pose3d):Pose3d javap 확인. LevelPlot.contains(ChunkPos), toLocal(ChunkPos), getChunkHolder(ChunkPos), getEmbeddedLevelAccessor 확인. Create 6.0.10의 Flywheel 1.0.6/Ponder 1.0.82/Registrate nested; Aeronautics 1.3.2 bundled의 aeronautics/offroad/simulated nested 확인. Copycats+ 3.0.11 ICopycatBlockEntity.getMaterial/getConsumedItem/hasCustomMaterial, IMultiStateCopycatBlockEntity.getMaterialItemStorage, IMultiStateCopycatBlock.storageProperties/partExists/vectorScale/getVectorFromProperty 실제 javap 확인. 이번 버전 신규 외부 API 호출/Native ABI/솔버 변경 없음. 전체 NeoForge/Minecraft 실제 의존성 해석은 사용자 검증 필요; Jar-in-Jar를 자동 compileClasspath로 가정하지 않음.

## 사전 검증 (실제로 실행한 정적 검사)
- check-duperfix-contract.py PASS: 버전/manifest/Mods/README/DamageModel import, 875 @Test, 137 test source files.
- check-superfix-assertions.py PASS: 실제 DCRT live routing, source max, one ledger path, 기존 patch/exact/Stress guards. 구 pair-wide 승인 문자열 guard는 실제 새 경로에 맞춰 변경. 기존 안전 guard 제거 없음.
- Java 21 JavacTask.parse: 271 source units, syntax errors 0.
- Java 21 제한적 javac: production explicit 34 units, related test explicit 7 units, exit 0. Minecraft 및 OwnerResolver adapter signature stubs, SLF4J 및 JUnit API compile-only stubs 사용. 실제 구현된 DcrtContactBatch, DirectionalContactRegions, GameImpactCredit, NativeApproachEvidence, SableContactRegions, ContactFacePatch, ForcePropagation, ContactCreditLedger, StressPropagation, LogSelection/RogTrace 등을 포함. 전체 RealFixRuntimeBridge/StressRuntime/World/Plot service는 실제 NeoForge type checking 미수행, 구문 및 호출 경로 수동 검토. 대역 파일은 ZIP에 포함하지 않음. 대역 Assertion은 실행하지 않았고 self-test도 실행하지 않음.
- JUnit 기존 864 -> 875 선언, +11, 삭제/비활성화 0. DuoFixRegionTest 7, NativeApproachEvidenceTest 1, ForcePropagationTest 1, StressPropagationTest 1, LogSelectionTest 1, PlotHeatMapReadCacheTest 기존 assertion 보강. JUnit 실제 결과 미검증. 수치 가상 검토: 승인 wall 50000/30000 max50000→Q cap 4999.8; E=0 F seed 두 개 100씩→root local 각50, merge F100→local50/G4.8, 혼합 outgoing50 미확정, 합계 F200; Root 두 개 Q100+50가 서로만 연결될 경우 ROOT_REENTRY150, 기존 LOOP_OR_LATE_UNUSED150. 실제 시험 실행 결과가 아님.
- Gradle 버전/manifest 변경, 테스트 JVM opt-in contact,force,commit,stress,copycat. Java21/NeoForge21.1.228/JUnit5.11.4/Wrapper/기존 dependencies 유지. JUnit XML/집계 출력 보존. Gradle 실제 compileJava/compileTestJava/test/processResources/jar/clean test/build 전부 미실행. Actions/인게임 미실행.

## 파일 변경
수정 31개, 추가 4개(본 보고서 포함), 삭제 0개. 기존 protected 경로 `.github`, `incoming`, `gradlew`, `gradlew.bat`, `gradle` 무변경. 새 전체 프로젝트 428 파일.

### 수정
- `README.md`
- `build.gradle`
- `scripts/check-duperfix-contract.py`
- `scripts/check-superfix-assertions.py`
- `src/main/java/com/physicaldestroydeform/PhysicalDestroyDeform.java`
- `src/main/java/com/physicaldestroydeform/collision/BilateralContactSurface.java`
- `src/main/java/com/physicaldestroydeform/collision/ContactCreditLedger.java`
- `src/main/java/com/physicaldestroydeform/collision/ContactFacePatch.java`
- `src/main/java/com/physicaldestroydeform/collision/ForcePropagation.java`
- `src/main/java/com/physicaldestroydeform/collision/GameImpactCredit.java`
- `src/main/java/com/physicaldestroydeform/collision/NativeApproachEvidence.java`
- `src/main/java/com/physicaldestroydeform/collision/RealFixRuntimeBridge.java`
- `src/main/java/com/physicaldestroydeform/collision/SableContactRegions.java`
- `src/main/java/com/physicaldestroydeform/collision/TargetDirections.java`
- `src/main/java/com/physicaldestroydeform/diagnostics/LogSelection.java`
- `src/main/java/com/physicaldestroydeform/diagnostics/RogTrace.java`
- `src/main/java/com/physicaldestroydeform/diagnostics/RuntimeDiagnostics.java`
- `src/main/java/com/physicaldestroydeform/material/TargetMaterials.java`
- `src/main/java/com/physicaldestroydeform/plot/NeoForgePlotGateway.java`
- `src/main/java/com/physicaldestroydeform/plot/PlotCommitService.java`
- `src/main/java/com/physicaldestroydeform/plot/PlotHeatMapReadCache.java`
- `src/main/java/com/physicaldestroydeform/stress/StressPropagation.java`
- `src/main/java/com/physicaldestroydeform/stress/StressRuntime.java`
- `src/main/java/com/physicaldestroydeform/world/WorldCommitCore.java`
- `src/main/java/com/physicaldestroydeform/world/WorldCommitService.java`
- `src/main/resources/META-INF/neoforge.mods.toml`
- `src/test/java/com/physicaldestroydeform/collision/ForcePropagationTest.java`
- `src/test/java/com/physicaldestroydeform/collision/NativeApproachEvidenceTest.java`
- `src/test/java/com/physicaldestroydeform/diagnostics/LogSelectionTest.java`
- `src/test/java/com/physicaldestroydeform/plot/PlotHeatMapReadCacheTest.java`
- `src/test/java/com/physicaldestroydeform/stress/StressPropagationTest.java`

### 추가
- `src/main/java/com/physicaldestroydeform/collision/DcrtContactBatch.java`
- `src/main/java/com/physicaldestroydeform/collision/DirectionalContactRegions.java`
- `src/test/java/com/physicaldestroydeform/collision/DuoFixRegionTest.java`
- `docs/verification/PDD-0.6.1-duoFix.md`

## 사용자 실제 검증
1. 기존 월드 백업. 전체 ZIP을 기존 incoming/ Apply로 적용(동시에 ZIP 1개). Build 및 Callback Contract Test 진행.
2. `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon`. 실제 결과 JUnit XML, 실패 시 첫 오류 및 stack trace 제출. 생성 예상 JAR `build/libs/physical-destroy-deform-0.6.1-duoFix.jar`.
3. 세션 A: `-Dpdd.logs=contact,force,stress,perf -Dpdd.diagnostics.mode=DEBUG -Dpdd.rog.maxPerTick=128 -Dpdd.rog.maxCharsPerTick=65536 -Dpdd.rog.seedLimit=128`. 바닥 정지/주행→벽 타격(바닥 제외, 새 벽 승인 한 번)→지속 밀기(0)→긍정 gap 2회→재타격(한 번); 새 방향 복수 지역의 공유 F max, seed 고유성 및 F/Q 보존; 5×5 정렬 3회 및 9×9, 공극, 회전, Plot↔Plot. DCRT_REGION_CLUSTER/APPROACH/AUTHORIZATION/EXCLUDED_SUPPORT/CREDIT_SEAL, CONTACT_SEED_COORDS/EFFECTIVE, F_CREDIT_SUMMARY. 실제 회전/같은 방향 지역 및 cap 보류 사례는 실패/미검증으로 별도 분리. TRACE 상세는 단일 episode에 한정.
4. 세션 B: `-Dpdd.logs=stress,commit,damage,deform,copycat,lineage,storage,perf -Dpdd.diagnostics.mode=NORMAL`. 직선/분기/합류/순환/다중 root, 관련 셀 변경 및 TTL, Q 정산·실제 Receipt 연결, 파괴/변형/Drop, Copycat, 분리·합체·재조립, 저장·정상 재시작. STRESS_ROOT_SUMMARY, STRESS_UNUSED_BREAKDOWN, STRESS_DIRTY_BATCH, STRESS_PARTIAL_REPLAY, STRESS_APPLY_RECEIPT, RECOVERY_RECONCILIATION, HEATMAP_OWNER_WAIT, MEMORY_PROCESS_SUMMARY. 기본 OFF는 명령어 제거 후 세션 안 재시작하여 일반 로그 무출력과 기능 동일 여부 확인. 필요한 특정 작업은 `-Dpdd.rog.traceJob=<정확한 jobId>`.
5. 실제 종료 문제가 재현되면 Android logcat/런처 종료 코드/hs_err 및 Native 크래시 로그 확보. 로그 끝이 잘린 사실만으로 OOM 확정 금지.

0.7.0 진입 금지: G-REGION-01~08, G-F-SURFACE/CROSS, G-STRESS-REPLAY, G-SPLIT-01, G-CC-LINEAGE 및 기존 활성 gate 실제 통과 필요.
