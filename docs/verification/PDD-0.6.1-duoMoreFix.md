# PDD 0.6.1-duoMoreFix — Apply 검증용 테스트 후보 보고

## 상태와 배포 제한

**TEST-APPLY / 검증용 후보.** 사용자 지시에 따라 GitHub Apply 및 빌드 검증을 위한 전체 소스 ZIP으로 제공한다. 기존 체크포인트의 코드·테스트는 그대로이며 문서의 배포 상태만 변경했다. 아래 미완료 구현·검증 항목은 남아 있고 기능 완성, 실제 빌드 성공 또는 인게임 안전성을 보증하지 않는다. 실제 Gradle/JUnit/Actions/인게임 검증은 사용자 담당이다. 기존 월드는 백업하고 별도 테스트 월드에서 검증한다.

## 1. 기준 원본

- Repository: kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming
- main SHA: `c589d1d455444e450a47cef8776cca36ae0f2d56`
- tree SHA: `8d23b7692d0e4b22f92922bd1885ad87bb04f547`
- 입력: 사용자 첨부 PDD-0.6.1-duoFix-FULL-SOURCE.zip. 원본 Git Tree 재구성 후 GitHub main Tree SHA와 정확히 일치. 428개 원본 blob, 총 6,040,640 bytes, 실행 권한 포함. IDS 1.0.10의 첨부 문서와 원본 docs 파일의 blob `503c1a3311960732b469171e81515ccc777336d9` 동일.
- 기존 Apply는 build.gradle/settings.gradle/src/main/java가 있는 단일 루트를 요구. 루트 .git/.github/incoming/gradlew/gradlew.bat/gradle 보호. .git은 임시 로컬 검증 인덱스이므로 산출물에서 제외. 원본 428개 파일 경로는 모두 유지. 원격 수정/커밋/푸시/PR/Actions 실행 없음.

## 2. 구현한 코드와 남은 조건

| 범위 | 현재 코드 변경 및 한계 |
|---|---|
| Seed 정규화 | DcrtContactBatch.combine에서 owner-local CellKey 단위 fingerprint 충돌, direct/patch 중복, 충돌 patch model 처리. 직접 target의 patch를 제거하면서 검증된 상대 후보를 material-only 증거로 유지. 그룹·reserve·patch seed 재구성. 원장 validateSeeds 안전 검사는 유지하고 실패 이유 세분화. 단, 서로 다른 지역의 fingerprint 충돌이 개별 native F 적격성/트랙 소비에 영향을 미치는 경우의 통합 검증이 남음. |
| 지역 권한 | DirectionalContactRegions reserve/finish/unknown, PENDING_GRANT, OBSERVED_UNSPENT 추가. DcrtContactBatch는 승인 선택에서 소비하지 않고 bridge의 실제 ledger Grant 후 finish(true). 실패·예외 시 lookup, abortUnissued 또는 quarantine. 기존 Pair/tick generation 정책 보존. 부분 Grant/예외/충돌 지역별 통합 회귀는 미완료. |
| 독립 접근 | 같은 capture와 최초 pre-pose를 이용한 레코드별 양측 실제 AABB 투영 gap 검증, 적격 레코드만 restricted R2/복원에 전달. 전체 불완전 Native는 우회 금지. FullPair 기존 fallback 및 독립 접근의 모든 경계 사례, proof 실패 사유별 실제 통합 테스트 미완료. |
| 공간 연속성 | DcrtSpatialFootprint 신규. 양측 owner-local witness, 실제 coplanar box face, 같은 face/상대 선형 이동, bounded union-find와 실제 face 연결 탐색. ambiguous merge/split은 기존 latch 보존 및 UNKNOWN. 16 tracks, 2048 witnesses, 128 footprints, 262144 작업 상한, R2 공유 예산 유지. 일반 회전/scale 및 넓은 연속 surface의 정확한 매칭은 보수적 UNKNOWN. 지역 재장전은 기존 검증된 World 평면/Plot AABB 분리 경로를 보존; 모든 Plot/Plot 국소 gap 재장전은 미완료. |
| F/Q | MAX_GAME_F=40,000,000, floor=2, gain=.1, maxGroupQ=3,999,999.8 유지. eligible peak max, Pair/tick 한 Credit, 양측 0.5/0.5 유지. Native 100건 포화는 별도 UNKNOWN. native pre-truncate API 확인 불가, 추가 clearCollisions 호출 없음. F cap 로그의 rawPeak/eligiblePeak/cappedF 분리. |
| 접촉면 복원 | 기존 정상 global fit 보존, 실패 시 실제 면 연결 성분으로 anchor 분할 후 각 component의 독립 fit. 기존 partialFit 4 inliers/strict majority, residual 및 cap 유지. 분리 chart마다 같은 Pair 예산. 전체 다중 모델 통합 검증 및 혼합 모델의 경계 조건 확인 미완료. |
| Stress | 기존 물리, Root visited, depth/노드/작업 cap, minQ/TTL, frozen graph/Receipt/ApplyId 및 파일 형식 변경 없음. StressRuntime와 journal의 선택형 timing 계측. ROOT_REENTRY는 정상 정책상 미사용 정산이며 재분배하지 않음. 전파/저장 재시작 회귀는 실제 검증 필요. |
| 로그 | LogSelection의 명시적 선택 우선, 기본 OFF/DEBUG 단독 활성화 없음. RogTrace 주기 샘플링, DCRT/Native 포화 집계 및 상태 전환, grant failure 중복 제한. 일부 PERF 및 Stress/Storage 진단 전용 시간 측정 비활성화. 실제 lifecycle 상태 및 안전 오류 보존. 기존 진단 자료구조/카운터의 OFF 비용 전수 제거와 ON/OFF 물리 동일성 통합 테스트는 미완료. |
| 외부 모드 | 기존 Sable/Create/Aeronautics/Copycats 호출·믹스인 파일을 변경하지 않음. 새 외부 API를 임의 선언하지 않음. 원본 Callback 순서/반환을 그대로 보존. |

## 3. API/Gradle 근거

프로젝트의 실제 Sable NeoForge 1.21.1-2.0.6, Create 6.0.10, Aeronautics bundled 1.3.2, Copycats+ 3.0.11 JAR 및 내장 JAR을 조회. Sable native 모듈과 companion JAR을 별도 추출하여 확인. 기존 RapierPhysicsPipeline.processCollisionEffects의 `clearCollisions(J)[D` 호출과 15-double 단위 처리 바이트코드를 확인. Native 100-record truncate 이전의 안전한 관측 API는 미확정이므로 기존 원본 콜백만 사용. 새 알고리즘은 기존 프로젝트의 OwnerResolver, SableConsumerTargets, FrameMath, NativeEffectDirection, ContactCreditLedger, ForcePropagation과 Minecraft BlockPos/AABB에 의존. 실제 Gradle dependency resolution과 compileClasspath/testCompileClasspath/testRuntimeClasspath 전체 해석은 미검증.

Java21, NeoForge21.1.228, ModDevGradle2.0.78, Sable2.0.6, companion1.6.0, JUnit5.11.4 기존 설정 유지. 버전/JAR manifest/Mods 화면/시작 로그 및 신규 승인 policy revision 표기만 0.6.1-duoMoreFix로 갱신. DamageModel/Stress journal 저장 스키마/재질 revision 변경 없음.

## 4. 검증 범위 (실행 테스트 아님)

- Java 21 `JavacTask.parse`: Java 273 파일, 구문 오류 0. 타입 귀속/bytecode 생성/컴파일/Gradle/JUnit 아님.
- `scripts/check-duperfix-contract.py`: PASS, @Test 885개, 테스트 Java 138개, 메타데이터 및 DamageModel import 검사.
- `scripts/check-superfix-assertions.py`: PASS, DCRT/R2/원장/Stress 연결 정적 계약. 새 메서드명과 신규 reserve/finish/공간 검증 경로 검사 추가.
- `git diff --check`: PASS. 기존 @Test 875개 보존; 신규 @Test 10개. 실제 테스트 발견 개수/결과는 미확정.
- 수치 검토: Q(39,999,999)=3,999,999.7, Q(40,000,000 및 유한 초과)=3,999,999.8, NaN/Infinity=0, Q(11)=0.9, Q(12)=1. 60/15/25, 70:30, Stress depth=4/nodes=128/globalWork=512/minQ=1/256/TTL=1200 유지.
- 신규 예상 결과(실제 미실행): `grantFailureDoesNotConsumeAndRequiresFreshProof`, `uncertainGrantDoesNotRestoreFreshAuthority`, `capDoesNotEvictConsumedTrack`, `actualShapeConnectivityNotCellAdjacency`, `sameFaceOrSweptRelativeTranslationStaysContinuous`, `directPatchOverlapKeepsPeerEvidenceWithoutSecondPatchSeed`, `conflictingPatchModelsHoldOnlyAffectedTarget`, `finiteForceSaturatesButInvalidInputDoesNotIssue`, `explicitSelectionOverridesLegacyFlags`, `noExplicitChannelAndDebugAloneRemainSilent`. 모두 설계상 기대값을 작성했으나 PASS 주장 금지.
- 기존 테스트 기대값을 통과 목적으로 변경하지 않음. 전체 테스트 목록, callback contract와 과거 regression은 그대로 유지.
- 필수 추가 작업: 실서비스 DCRT selection/Grant 실패 후 예외/부분 후보별 F 권한·seed 일치, 99 floor+1 wall의 완전 input과 Native 100 포화 구별, 모든 G-REGION-01~08, fingerprint/owner 충돌/반복 source reserve, 기존 stress replay/저장 복구, 로그 OFF/ON 동일 결과의 통합 fixture 작성·정적 검토. 타입/외부 API 전체 사전 검토 미완료. Native 제한은 보수적으로 유지.

## 5. Apply 검증용 후보 사용자 검증

AI는 실행하지 않는다. 사용자:
```
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```
compileJava, compileTestJava, JUnit, processResources, jar, build, GitHub Actions, 인게임: 모두 NOT_RUN (새 버전). 이 ZIP을 incoming/에 등록한 후 Apply 완료 및 빌드 성공 여부를 확인한다. 빌드가 실패하면 인게임 시험에 사용하지 않는다.

일반 실행: `-Dpdd.logs=off`. 선택 채널: native, contact, force, damage, stress, deform, commit, copycat, lineage, storage, network, perf. `all` 및 `off` 지원. pdd.logs가 있으면 기존 legacy flag보다 우선. DEBUG는 상세도일 뿐 채널 활성화가 아님. 운영상 필수 오류는 항상 출력될 수 있음.

세션 1 (검증용 후보): `-Dpdd.logs=native,contact,force,perf -Dpdd.diagnostics.mode=DEBUG -Dpdd.rog.maxPerTick=128 -Dpdd.rog.maxCharsPerTick=65536 -Dpdd.rog.seedLimit=128`. 같은 재료/구조로 바닥 주행→독립 벽 충돌→지속 압착→분리 2회 이상→재충돌→같은 방향의 별도 패치→동시 충돌→대형 면. DCRT_APPROACH, DCRT_WITNESS_PROOF, DCRT_AUTHORIZED_INPUT, DCRT_CREDIT_SEAL, CONTACT_SEED_SEALED, PDD-Q-ISSUE, NATIVE_CAP_TRANSITION, PDD-F-CAP, F/Q 정산 확인. 바닥/지속 밀기 신규 Q=0, 신규 입증 충돌만 1 Credit, 최대 F 상한 유지, 포화/불완전 input은 UNKNOWN. 실제 F가 상한을 초과한 경우에만 F-CAP이 나타나며 Native 상한만으로 출력·지급하지 않음.

세션 2 (검증용 후보): `-Dpdd.logs=stress,commit,damage,deform,copycat,lineage,storage,perf -Dpdd.diagnostics.mode=DEBUG -Dpdd.rog.maxPerTick=128 -Dpdd.rog.maxCharsPerTick=65536 -Dpdd.rog.seedLimit=128`. 직선/분기/합류/순환/다중 Root 전파 및 실제 변형, Copycats, Plot 분리, 저장/재시작. PDD-STRESS-LEDGER, PDD-STRESS-APPLY, Receipt, Commit, WAL/recovery, cleanup 확인. 미사용 Q 포함 보존, 동일 ApplyId 재소비 금지, Root 재진입 미사용 처리, 실제 월드와 Receipt 일치. 두 세션 전체 latestlog와 크래시 발생 시 crash-report 제출.

## 6. 파일 변경 목록

수정 22개:
- `README.md`
- `build.gradle`
- `scripts/check-duperfix-contract.py`
- `scripts/check-superfix-assertions.py`
- `src/main/java/com/physicaldestroydeform/PhysicalDestroyDeform.java`
- `src/main/java/com/physicaldestroydeform/collision/ContactCreditLedger.java`
- `src/main/java/com/physicaldestroydeform/collision/ContactFacePatch.java`
- `src/main/java/com/physicaldestroydeform/collision/DcrtContactBatch.java`
- `src/main/java/com/physicaldestroydeform/collision/DirectionalContactRegions.java`
- `src/main/java/com/physicaldestroydeform/collision/GameImpactCredit.java`
- `src/main/java/com/physicaldestroydeform/collision/RealFixRuntimeBridge.java`
- `src/main/java/com/physicaldestroydeform/collision/SableContactRegions.java`
- `src/main/java/com/physicaldestroydeform/diagnostics/LogSelection.java`
- `src/main/java/com/physicaldestroydeform/diagnostics/RogTrace.java`
- `src/main/java/com/physicaldestroydeform/plot/PlotCommitService.java`
- `src/main/java/com/physicaldestroydeform/stress/StressReservationJournal.java`
- `src/main/java/com/physicaldestroydeform/stress/StressRuntime.java`
- `src/main/java/com/physicaldestroydeform/world/JournalIoMetrics.java`
- `src/main/java/com/physicaldestroydeform/world/WorldCommitService.java`
- `src/main/java/com/physicaldestroydeform/world/WorldPersistentJournal.java`
- `src/main/resources/META-INF/neoforge.mods.toml`
- `src/test/java/com/physicaldestroydeform/diagnostics/LogSelectionTest.java`

신규 3개:
- `src/main/java/com/physicaldestroydeform/collision/DcrtSpatialFootprint.java`
- `src/test/java/com/physicaldestroydeform/collision/DuoMoreFixRegionTest.java`
- `docs/verification/PDD-0.6.1-duoMoreFix.md`

삭제 0개. 기존 워크플로, Wrapper, 리소스, 설계서 유지.
