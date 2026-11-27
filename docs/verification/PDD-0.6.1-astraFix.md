# PDD 0.6.1-astraFix — 개발 및 검증 인계

## 기준 및 상태

- GitHub main `b9535986cf7569d6352b8496d588131db706ea58` / Git Tree `38e372177b5a1c2b0c9842f21beab83b4b7e92f6`. 원본 415 blobs, 5,485,683 bytes. 기존 rogFix 전체 ZIP의 Git Tree, 파일 경로, bytes 및 실행 권한 대조 후 복원했다. 최신 main 재조회 결과 동일하다. 원본 ZIP과 보호 경로는 변경하지 않았다.
- 기준 빌드 성공: https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/45835211553 (rogFix에 대한 성공이지 astraFix의 결과가 아니다).
- 0.5.1-persistFix `1d6559300a9ec9daccc637694c246cf8e92327f6`의 실제 ContactFacePatch를 확인했다. 당시 단일 witness Halo와 .51블록 확장 여유는 복구하지 않았다. 0.5.0-rogFix의 정확한 SHA 및 해당 로그 원본은 이번 작업에서 별도 확보하지 못했다. 사용자가 전달한 Astra의 651건 Sweep 실패와 episode 수치는 독립 재집계한 결과가 아니다.
- 통합설계서 1.0.9 원본 파일은 변경하지 않았다. 본 개발 명령에 의해 승인된 제한적 게임 패치 정책만 변경했다. 0.7.x 기능 추가 없음.
- 상태: 전체 원본을 포함한 수정 소스 검증 후보. 새 접촉면 모델과 Stress 제한적 재검토는 구현했다. 실제 NeoForge 컴파일·JUnit·Actions·인게임 검증은 수행하지 않았다. 꼭짓점 문제 최종 해결 및 0.6.1 전체 기능 완료 판정은 보류한다.

## 1. 접촉 증거 및 근사 모델

- NativeCollisionTelemetry의 Java Contact에 native poll 식별자 추가. 기존 15-double 배열, clearCollisions의 원본 반환, 호출 횟수, mixin, Rust ABI는 변경하지 않았다. NativeContactMassEpoch의 COM 마스킹에서도 poll 식별자 보존. 기존 테스트 생성자는 pollId=0으로 호환하며 이는 근사 확장의 근거가 되지 않는다.
- BilateralContactSurface의 기존 정확 경로, EPS, 1/32 침투 한도, Normal Sweep의 검증 기준 보존. `patchFrame`은 로컬 점과 실제 축 후보를 확보하기 위한 별도 함수로, 정확한 월드 자세를 합성하지 않는다.
- ContactFacePatch는 동일 poll/ContactBatchStamp/양측 effect pose/원본 force scalar/실제 로컬 평면 및 법선축의 대응점 3개 이상을 요구한다. 두 hull 모두 비퇴화 면적 > (1/32)^2, 독립 3점의 높이 > 1/32. 2D affine 대응의 방향, 두 방향 잔차 <= 1/32, singular value 범위 [.98,1/.98]를 검증한다. 이 수치는 기존 1/32 검증 폭과 .98 면 법선 정합을 이용한 보수적 게임 모델 정책이며 실제 solver의 substep 증명이 아니다.
- 두 로컬 hull을 넘어선 외삽 금지. 실제 양측 VoxelShape 외부 면의 겹치는 조각만 사용한다. 공극/단차, 가려진 내부 면, shape 불확정/미로드, 예산 초과를 구분한다. 연결 성분별 독립 noncollinear witness 및 재클리핑을 요구하고, 단일 점 Halo 및 선분 전체면 확대를 금지한다. 접촉점이 부족한 별개 성분은 거절한다. Protruding shape는 이 모델에서 보수적으로 보류한다.
- 기존 exact/direct 씨앗 우선. model은 새로운 owner-local 씨앗만 추가하며, 대응 형상에서 얻은 완전한 후보 집합(E가 동일할 때만)으로 상대 탄성을 계산한다. 후보 8개 초과 또는 E 불일치·미확정은 기존 보류 처리한다. 직접 및 정확 증거는 모델로 덮어쓰지 않는다. 실제 후보의 원본 witness가 동일한 경우에만 모델로 해소한 후보를 예약 집합에서 제거한다. 무관한 증거와 미확정 상한은 보존한다.
- `Prepared.exact()`는 기존 승인 확인 경로, `Prepared.expand()`는 승인 후 정확+게임 패치의 단일 결과를 생성한다. 실제 런타임 `RealFixRuntimeBridge`의 기존 승인/지연 계획/단일 `issueContactSeeds`를 사용한다. 승인된 동일 배치의 모든 증거 복원 전에는 F/Q 지급하지 않는다. 별도 cross-tick queue나 영속 Credit 예약 상태는 만들지 않았다. 같은 배치에서 원래 사건을 한 번 봉인하며, 이후 지속 접촉의 기록을 합산하지 않는다. 발행 전 예외는 기존 abortUnissued 정책을 따른다. owner generation 및 material generation 확인, Copycat 위치별 revision 재조회, 기존 fingerprint 검사 유지. 여러 틱에 걸친 증거 수집은 동일 원래 substep을 증명할 수 없어 구현하지 않았다.
- RegionalContactProof는 모델만으로 신규 충격을 승인하지 않는다. TargetDirections는 모델로부터 변형 축을 생성하지 않는다. VerifiedContactGraph의 native 연결 증거를 그대로 유지하며 모델은 독립 cross-owner edge를 생성하지 않는다. Ledger는 model provenance를 별도 검증하고 정확/모델 중복 seed 및 model-only 신규 승인 경로를 차단한다.
- 측별 50%, 기존 상대 E/내부 F/G(F)/DamageModel/정수 remainder/Q cap 및 Stress 분배 공식 변경 없음. 예약 상한은 기존 최대 매칭을 사용하고 최종 증거 집합 기준으로 정산한다. 실제 F 보류 감소 정도는 신규 인게임 로그 필요.

## 2. Stress 스냅샷 변경

- `StressSnapshotRepair`로 기존 관측 graph에서 변경된 SOLID를 DENIED sink로 치환. EMPTY와 DENIED는 원래 관측을 보존한다. DENIED edge는 기존 방향 가중치를 유지해 독립 경로의 몫을 임의 증가시키지 않는다. 새 replacement block의 material을 사용해 재계산하지 않는다.
- 원래 reservation/Q/TTL/ApplyId로 bounded Session을 재검토한다. 이전 영속 plan prefix와 이미 정산된 각 Q partition/reason이 그대로 보존되는 경우에만 후속 결과를 기존 sealProgress로 영속화한다. 이전 출력 셀 또는 UNAVAILABLE/미관측 변경 셀, prefix/partition 불일치에서는 기존 잔여 정산 보류로 복귀한다.
- RESERVED는 변경된 sink graph를 먼저 영속화하며 SEALED는 이전 prefix가 검증될 때만 새 graph와 plan을 함께 저장한다. 이전 plan 재발행은 없다. 기존 residual wait/wake/원래 TTL/합류/Node-once 및 저널 스키마 유지. 수정된 기존 EMPTY를 새 블록으로 재조회하지 않는다.
- 제한: 이 방식은 graph 전체의 bounded replay이지 모든 의존 경로를 개별 분할·재계산하는 엔진이 아니다. 이미 지급된 경로가 달라지는 경우 보수적으로 포기한다. 원래 충돌 시점에 아직 관측되지 않았던 셀의 과거 상태를 재현할 수 없으며, 기존 lazy graph 탐색의 역사적 상태 한계가 남는다. 모든 상황의 Stress 부분 복구를 보증하지 않는다.

## 3. 진단 및 성능

`PATCH_MODEL_ACCEPTED`, `PATCH_INCONSISTENT_CORRESPONDENCE`, `PATCH_NO_CONNECTED_SUPPORT`, `PATCH_UNKNOWN_OR_CAP`, `BILATERAL_COVERAGE`, `CONTACT_COVERAGE`, `CONTACT_CREDIT_RESERVED`, `CONTACT_CREDIT_SEALED_INPUT`, `CONTACT_SEED_SEALED`, `CONTACT_SEED_COORDS`, `CONTACT_SEED_EFFECTIVE`, `CONTACT_PATCH_CONSENSUS`, `STRESS_PARTIAL_REPLAY`, `STRESS_REPLAY_SEALED`, `STRESS_REPLAY_DEFERRED`, `STRESS_JOB_SUMMARY` 사용. `CONTACT_SEED_COORDS`는 DIRECT_NATIVE/BILATERAL_EXACT/OWNER_LOCAL_PATCH를 구분한다. `CONTACT_SIDE_SEAL`은 실제 patchConsensus와 예약/미확정 F를 출력한다. 기존 seedLimit/줄 수/문자 수 제한 유지. 상세 geometry 로그는 일부 샘플링될 수 있다. 기존 저장·Commit·Drop·네트워크·Lineage 진단을 변경하지 않았다.

동일 사건의 NativeAuditBatch shape cache 재사용, 근사 모델 face cache, 32,768 work 제한, 기존 32,768 고유 셀/측 및 16,384 seed/측, 32,768 region 상한 적용. 전체 Plot/World 스캔 및 신규 의존성 없음. 복원 추가 비용의 실제 MSPT 개선이나 장시간 메모리 성능은 미검증이다.

## 4. 변경 및 테스트

원본 415개 파일 모두 포함. 변경 15개, 신규 5개(본 보고서 포함), 삭제 0개. Java main 131개, test 135개, 총 266개. JUnit @Test 선언 841 → 856 (+15); 11개 AstraFixContactPatchTest, 4개 AstraFixStressRepairTest. 기존 NativeCollisionTelemetryTest 2개에 실제 poll 식별 및 원본 배열 보존 조건을 추가했다. 기존 정확한 Sweep/양측 복원 거절 및 기존 물리·안전·회계 테스트는 삭제하거나 기대값 완화하지 않았다. 과거 ContactFacePatch 전면 금지 정적 검사를 새 통합 경로 및 회계 검증으로 교체하고, 폐기된 단측 Halo 경로 금지 유지. `check-duperfix-contract.py`는 실제 856 선언으로 갱신. Gradle 버전 및 manifest, NeoForge Mods 버전, BUILD_VERSION 및 README 동기화. Java21/NeoForge21.1.228/ModDev2.0.78/JUnit5.11.4/Wrapper/기존 클래스패스 및 JUnit XML/결과 출력 유지. gradle.properties는 변경 필요가 없어 그대로 보존.

새 시험: 1/3/5/9 크기 paired patch 및 회전 effect pose, ring/부분 중첩, sparse/collinear, poll/force 분리, 대응 불일치/unknown/예산, 분리 성분, 실제 ledger 균등 F/중복 방지, 같은 증거에 한정한 예약 해소, 혼합 상대 E, 모델 신규 승인 금지, exact/model 호출 분리; Stress 독립 branch 원래 가중치, prefix/재시작, unknown/published cell 차단, prefix 변경 거절.

## 5. 실제 수행한 사전 검증

- Java 21 JavacTask.parse 전체 266 파일: 구문 오류 0.
- 실제 production 소스 33개(새 ContactFacePatch, 실제 SableContactRegions/ContactCreditLedger/ForcePropagation/NativeCollisionTelemetry/StressSnapshotRepair/StressPropagation/StressReservationJournal 등) 및 테스트 소스 3개를 외부 Minecraft/SLF4J/JUnit, OwnerResolver/WorldCommitCore/StressCommitHandoff의 제한적 시그니처 대역과 함께 `javac --release 21 -proc:none` 타입 검사. 성공. 대역은 ZIP에 포함하지 않았다. RealFixRuntimeBridge 및 StressRuntime 전체의 실제 NeoForge 타입/링크는 미검증이며 관련 실제 호출 시그니처 및 수정 코드 문법 검토를 수행했다.
- `python scripts/check-duperfix-contract.py`: PASS(856 @Test, 135 test source). `python scripts/check-superfix-assertions.py`: PASS.
- 독립 유리수 모델로 1/3/5/9 크기 접촉 셀 수, ring 16/부분 9, 8,181개 F partition 입력, 독립 Stress branch Q 보존 검토. 이것은 실제 Java 알고리즘 실행이나 JUnit 결과가 아니다.
- Sable 2.0.6 실제 JAR의 nested Rapier `clearCollisions(long): double[]`, `RapierPhysicsPipeline.processCollisionEffects():void`, `activeSubLevels`, `LevelPlot.getChunkHolder(ChunkPos)` 확인. 기존 원본 mixin, Sable/Companion Maven 좌표 및 클래스패스 유지. Create 6.0.10, Aeronautics 1.3.2, Copycats+ 3.0.11 JAR의 기존 클래스/리소스 확인. 새 외부 API 호출 없음. 정확한 native manifold/substep/block pair ID 미제공은 해결하지 못했다. 실제 Gradle 의존성 해석은 사용자 확인 필요.
- 실제 compileJava, compileTestJava, JUnit, processResources, jar, clean test, build, astraFix Actions, 인게임 모두 미실행/미검증. 테스트 선언 수는 실제 실행 수가 아니다. 실제 JUnit RED/GREEN을 실행하지 않았다(프로젝트 지침 v3.0).

## 6. 사용자 검증

Apply 후 `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon`. 기존 Callback Contract Test 및 Build 실행. JAR `physical-destroy-deform-0.6.1-astraFix.jar` 하나만 설치. 테스트 전 월드 백업.

JVM: `-Dpdd.rog.enabled=true -Dpdd.diagnostics.mode=DEBUG -Dpdd.rog.maxPerTick=512 -Dpdd.rog.maxCharsPerTick=262144 -Dpdd.rog.seedLimit=128 -Dpdd.rog.summaryTicks=100`. 시작 로그에서 실제 설정 확인. 지정 Episode만 상세 기록하려면 기존 `pdd.rog.traceEpisode` 사용. 출력 제한에 걸리면 필수 좌표 누락이므로 해당 시험 미검증.

A: 정렬 1/3/5/9면, 5x5 최소 3회(실제 완전 면 접촉에서 측별 25개), 미세회전/접선 이동, 중앙 공극, 부분 겹침, 진짜 꼭짓점, Plot↔Plot, 반복 접촉 및 재충돌. 초기 F는 `CONTACT_SEED_COORDS`, `CONTACT_SIDE_SEAL`, `CONTACT_SEED_EFFECTIVE`로 확인한다. 모델과 정확 증거를 구분한다. 단일 Native anchor로 임의 전체 확장하지 않으며 실제 물리 접촉 결과와 근사 모델의 게임 정책을 혼동하지 않는다.

B: 충돌 후 응력 graph 셀 교체(변경 셀 기존 Q 재발행 없음, 독립 경로 전달 및 prefix 확인), 미로드 및 TTL 원래 1,200틱, 변형 이동/차단, 파괴/Drop, Copycats+ 재질 변경, 분리/합체/재조립, 저장 후 재실행. `STRESS_PARTIAL_REPLAY`, `STRESS_REPLAY_SEALED`, `STRESS_REPLAY_DEFERRED`와 Q 원장, `ApplyId`/Receipt 및 실제 readback을 확인. 실제 지원되는 Sable 기능의 범위에서 시험한다.

최종 종료 기준: 정확한 접촉 또는 승인된 제한적 게임 패치에서 실제 초기 씨앗 및 F 지급 확인, F/Q 보존/중복 0, 비접촉 확장 0, 기존 기능 회귀 없음. 동일 poll에 실제 substep 식별자가 없으므로 모델의 정확성은 실제 테스트를 통해 검증해야 한다. 특히 sparse·불일치 witness·complex shape에서는 모델이 보류될 수 있다. 0.6.1의 전체 기능 완료 및 0.7.0 진입은 아직 선언하지 않는다.
