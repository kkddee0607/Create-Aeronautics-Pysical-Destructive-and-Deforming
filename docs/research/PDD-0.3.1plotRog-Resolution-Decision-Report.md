# PDD 0.3.1plotRog — 원인 확정 및 최소 수정 설계 결정

분석 대상: `/mnt/data/latestlog.txt` (5,402줄), `PDD-0.3.1plotRog-FULL-SOURCE-UNVERIFIED-APPLY.zip`의 실제 Java 소스, `PDD-1.0.5-Integrated-Design-Specification`, `PDD-Sable-2.0.6-Plot-Coordinate-Forensics`. **이 문서는 분석·설계 결과이며 소스 수정·Gradle·인게임 재검증 결과가 아니다.**

## 판정 요약

| 등급 | 내용 | 결정 |
|---|---|---|
| P0 정확성 | World 전체를 직전 월드 블록 **한 개의 AABB**로 대표해 분리 검증 | 재장전 증거에서 제외. 기존 `confirmedWorldClearance` 재사용. Plot↔Plot AABB 분리는 보존 |
| P0 성능 | R2 형상 검사 24,734회 중 23,407회(94.6%)가 `DUPLICATE_EPISODE` 사건에서 실행 | FSM 신호 기준으로 신용 발행 불가능한 상태에는 R2를 생략. 관측·state advance는 유지 |
| P1 정확성/coverage | 4개 이상 실블록이 확인되면 해당 측 격리. `TOO_BROAD=231` | 타깃 상한을 기존 실제 **32 고유 셀 검사 예산**과 일치시켜 측당 최대 32개로 확정. 지역 수 상한 최대 64. 별도 영역 엔진 도입 금지 |
| P1 정확성 | 어떤 측에서 검사 예산이 끝나면 양측 전체 거절 | 실패한 측만 격리, 반대측은 독립 검사 유지; 미검증 몫은 unused |
| P1 근거 품질 | Sable native `clearCollisions` 최대 100건; 72회 한도 도달 | poll-level `CAP_SATURATED` 표기. 누락 추정 금지·기존 유효 타깃 자동 거절 금지 |
| P2 진단 부담 | R4가 Q에 영향 없는 진단인데 R2 실패마다 검사하며 clearance와 예산 공유 | R4는 명시적 디버그/샘플에서만, clearance 우선 및 진단 예산 분리 |
| 유지 | Plot R2 좌표, R3 미승인, Q=64, A/B 반분, FSM 원칙, callback, JNI, mutation OFF | 변경 금지 |

## 1. 정량 근거 (전체 로그 직접 집계)

- `PDD-EPISODE` **964건**: `DUPLICATE_EPISODE=902`, `APPROVED=46`, `INITIAL_CONTACT_UNKNOWN=11`, `TARGET_UNVERIFIED=3`, `APPROACH_UNVERIFIED=2`.
- R2 블록 검사 수는 같은 tick의 `PDD-NATIVE-TARGET.checkedA+checkedB`를 더해 **24,734회**. 이 중 중복 사건에 해당하는 검사 **23,407회**, 승인 사건 **949회**, 초기 불확정 **238회**, 타깃 미검증 **94회**, 접근 미검증 **46회**. 이 수는 검사 **호출 횟수**이지 wall-clock CPU 측정값은 아님.
- R2 품질 `PROVED=489`, `PARTIAL=244`, `TOO_BROAD=231`. TOO_BROAD 사건의 FSM 결과는 **중복 226, 타깃 미검증 3, 초기 접촉 불확정 2**. 즉 `TOO_BROAD=231`을 모두 `실제 피해 누락 231회`라 부르면 잘못된 해석.
- `PDD-VERF-REGION` 상세 진단 **94건**은 `PARTIAL/TARGET_LIMIT_B=45`, `PARTIAL/TARGET_LIMIT_A=7`, `TOO_BROAD/TARGET_LIMIT_A=42`. 보고된 고유 타깃은 초기 제한에 걸려 5~8개 수준에서 잘림. 8개가 전체 최댓값이란 의미 아님.
- 원장 46건 합계 `issuedQ=2888.7759399414062`, `unusedQ=420.3879699707031`(14.55%). 미사용 예산은 한쪽 격리에 따른 안전한 제한이며 누락 블록으로 전용 금지.
- `nativeCapSaturatedPolls=72`. JNI가 실제로 제거한 레코드 수는 판정 불가.
- R3 실증 `r3Verified=0`. Epoch 격리 로그 0건은 미발생이지 기능 전체 통과 아님. 월드 변경 OFF. 종료 정상.

## 2. P0 — 단일 World 블록 기반 재장전 제거

**원인:** `RealFixRuntimeBridge.java:412–432`는 native 접촉이 없는 틱에 `box()`가 리턴하는 AABB 둘을 비교해 `positivelySeparated=true`로 FSM을 진행. `RealFixRuntimeBridge.java:753–778`의 `box()`는 PLOT에 대해 구조물 전체 world AABB를 반환하지만 WORLD에 대해 최근 R2로 선택한 **단일 블록** AABB를 반환. 현재 월드가 다른 블록에서도 접촉 중일 수 있으므로 이전 블록과 이격된 사실로 *Plot vs World 전체 분리*를 입증할 수 없다. 이 구조적 증명 오류는 소스로 확정되지만, 이번 실제 46회의 Q 중 오발행이 있었는지는 인게임 위치 참값이 없어 미확정.

**정책:**
- PLOT/PLOT: 전체 실제 SubLevel world AABB가 떨어졌으면 분리 인정. 기존 처리 유지.
- PLOT/WORLD: 단일 저장 World 타깃 AABB 분리만으로 FSM `COOLING`, `ARMED`에 진입 금지. 기존 `confirmedWorldClearance(server,dimension,plotBox,remaining)`이 전체 로드 영역의 충돌체 부재를 증명한 경우에만 `positivelySeparated=true` 인정.
- 검사 대상이 32개를 넘거나, 청크 미로드 또는 예산 소진이면 `UNKNOWN`; Q=0/rearm 금지. 기존 2회 연속 안정 분리 조건 유지.
- 명시된 한계: 매우 큰 Plot의 월드 클리어런스가 검증되지 않으면 재충격 발행이 보수적으로 보류됨. 대형 구조물 전용 공간 점유 인덱스는 성능 측정 없이 지금 신설하지 않음.

**회귀:** 2개의 인접 World 블록을 따라 Plot이 연속 접촉하며 이동하는 동안 오래된 블록 AABB가 떨어져도 rearm=0. 전체 World-clearance가 2틱 검증된 후에만 ARMED. Plot↔Plot은 기존 통과 유지.

## 3. P0 — Q 발행 불가능 상태의 비싼 R2 회피

**원인:** `RealFixRuntimeBridge.java:199–200`가 매 이벤트에서 `SableContactRegions.audit()`을 호출한 뒤 `GameImpactCredit.step()`(약 337행)을 호출. FSM 소스 `GameImpactCredit.java:255–256`은 `ACTIVE && (!pendingApproach || burstFrames>=2)`이면 타깃과 무관하게 DUPLICATE를 반환.

**정책:**
- 매 사건의 native 레코드 구조 검증, owner/generation 검증, 힘 피크 수집, FSM state advance, 실제 separation 증거 수집은 그대로 유지.
- `ACTIVE && (!pendingApproach || burstFrames>=2)`이면 **신용 발행 목적의** R2 검사를 생략. `UNINITIALIZED`와 `COOLING`도 최초 접촉은 Q=0이므로 권한이 없지만, 기존 초기 타깃 진단은 일정 빈도로 샘플 보존.
- `ARMED` 및 `ACTIVE+pendingApproach+burstFrames<2`는 반드시 R2 수행. 증가한 힘만으로 승인하지 않고, 이전과 동일한 접근·새 사건 근거 필요.
- 성능 지표는 `R2_AUDIT_ELIGIBLE`, `R2_AUDIT_SKIPPED_DUPLICATE`, `R2_ACTUAL_BLOCK_LOOKUPS`, `R2_SHAPE_CHECKS`, `p95 tick overhead`를 분리. R2 생략은 `R2_REJECTED` 또는 `TARGET_MISSING`으로 거짓 집계하면 안 됨.
- **P0 World separation 증명 수정과 함께 적용**. 그렇지 않으면 생략된 R2 때문에 오래된 World 타깃 좌표가 갱신되지 않아 잘못된 재장전 위험이 커짐.

**회귀:** 기존 승인 Q, episodeGeneration, 동일 사건 중복 재발행 금지, world/plot 연속 슬라이딩, owner unload 및 callback order 모두 동일. 중복 사건 902건을 replay할 때 구조/소유자 확인을 생략하지 않는 범위에서 블록 검사가 크게 줄어야 함.

## 4. P1 — 다중 타깃 정책: 기존 자료구조 사용, 상한 연동

**원인:** `SableContactRegions.java:18`은 한쪽 고유 블록 검사 최대 32회. 하지만 `:210–220`은 타깃 4개 초과로 한쪽 전체 격리. `ContactCreditLedger.java:20–21` 역시 4타깃/16 Region. 실제로 이미 검사에 성공한 5~8개 타깃이 거절됨.

**확정한 개발 정책:** 한쪽 **검증된 고유 타깃 최대 32개**, 양측 합쳐 최대 64개 `Region` 대표 엔트리. 이유: 새 블록 탐색 횟수를 늘리지 않고, 현재 `MAX_CHECKS_PER_SIDE=32`가 증명 가능한 고유 블록 수의 자연스러운 상한이기 때문. `ContactCreditLedger.Region`과 `split()`을 그대로 재사용. §23의 기존 `multiTargetPerSide=4`는 `DEV_PROVISIONAL`로만 정의되어 있으므로 **버전별 정책 개정 기록**을 남기되 사용자 최종 밸런스 확정으로 오인하지 않음.

- Q는 계속 **사건당 한 번**, `Q_group<=64`, 양측 `<=32/32`, 단일 측 32개면 `32/32=1`씩 균등 분배. 물리 impulse 분산의 실측식이 아니라 현행 game Q 규칙의 결정론적 확장.
- Side가 미검증/검사 미완료인 경우 해당 side Q는 unused. 반대 side의 검증된 타깃/몫은 보존. 양측 모두 미검증이면 발행 금지.
- `record ordinal`이나 임의 인접성을 새 물리 manifold ID로 간주하지 않음. 단순 지오메트리상 연결된 블록이라고 추가 Q 생성하지 않음.
- `MAX_REGIONS`는 최종 최대 64개를 수용. Pair region 중복은 지금처럼 고유 target로 합치고, 최대 영역 초과 시 singletons로 재구성. **새 그래프·군집·연속면 엔진은 현재 불필요**.
- 33번째 **고유 셀 검사**가 필요해지면 해당 측을 `SIDE_A/B_INCOMPLETE`로 격리; 다른 측을 계속 검증. 안전하게 증명 못한 셀에는 Q 없음. `owner mismatch`와 invalid native input에는 기존 fail-closed 유지.
- 이벤트 포화/검사 예산 제한으로 **전체 충돌 면적의 완전성**을 증명하지 못했다는 사실을 별도 상태로 표시; 그래도 실제 확인된 블록만 후보로 삼음.

**회귀:** 5/8/16/32개의 서로 다른 실블록을 가진 동일 사건은 Q 증가 없이 모두 타깃으로 기록. 33번째 고유 셀 필요 시 편측 격리, Q conservation. 중복 접점 100개는 Q 한 번. 기존 `MAX_CHECKS_PER_SIDE=32` 불변. 1~4 타깃의 기존 분배 결과 동일.

## 5. P1 — Native 100개 포화 처리

**기술적 사실:** Sable 2.0.6 Rust `event_handler.rs`는 충돌 점별 힘 이벤트를 `reported_collisions`에 기록. `lib.rs::clearCollisions`는 100개만 Java로 보고하므로 100개 수신은 포화 가능성이지 손실 건수 증명 아님. 제한은 scene-wide drain에 해당하며 pair-specific exact total이 아님.

- 현재 `NativeCollisionTelemetry.nativeCapSaturatedPolls()` 전역 누적 계측과 100개 상한을 보존.
- 정확도 필요한 후속 변경에서는 `capture` 단계에서 poll별 `saturated` 상태를 메타데이터로 저장하고 동일 서버 tick/scene 그룹에 전파. 단일 body pair의 `rawRecords<100`이라도 그 poll 자체가 100이었다면 `SOURCE_CAP_SATURATED`일 수 있다.
- 정보 부족에 기반한 음성 증거(`not observed => no contact`, `not observed => separated`) 사용 금지. 일부 실증된 타깃에 이미 발행된 Q를 재계산해 추가 발행하지 않음.
- JNI 교체·native 100 상향·Sable callback overwrite는 **현 버전에서 하지 않음**. 전체 solver contact 보장이 필요해지는 버전에서 upstream Sable 읽기 전용 `voxel pair/step` ABI 검토.

## 6. P2 — 불필요한 진단/캐시 비용 억제

- R4는 `RealFixRuntimeBridge.java:310–325`에서 권위 없는 shadow만 수행. `probeWorldR4()`와 `confirmedWorldClearance()`는 같은 `remainingR4Checks`를 소비하는 현재 구조. 반드시 **정확성 증명용 World clearance를 먼저 보장**, R4는 진단 옵션 및 샘플/별도 남은 예산에서만 실행.
- `VERF_ENABLED=true` 기본 설정과 모든 event의 다수 `INFO` 로그가 큰 파일을 만든다. 검증 빌드에 상세 로그를 두되 운영 기본은 건수/상태 집계와 승인/오류 표본만 남기고 동적 디버그 옵션으로 상세 로그를 켜도록 한다. 단, 로그 변경만으로 기능 완성 주장 금지.
- `OwnerResolver.NativeAuditBatch`가 셀마다 서버 dimension, Plot, accessor를 반복 조회하고 `shape.toAabbs()`를 매 접점 호출함. 사건 단위로 owner/context/voxel bounds를 재사용하면 추가 블록 검사 없이 비용 감소. **우선 P0 최적화의 실제 CPU p95 결과를 측정한 뒤** 필요 시 이 국소 최적화 추가.

## 7. 반드시 그대로 보존할 계약

- Sable 2.0.6 Body-local 로컬 접점+COM, static World 직접 좌표, `getCenterBlock()` 기준 embedded 변환, 양측 독립 R2; 0.25 witness 거리 거절 금지.
- R3 교차 물리 서브스텝 Q 승인 금지; R4 잠정 후보 Q 금지. COM epoch 미증명 측 격리 유지.
- `Q<=64`, pair/episode one-credit, 양측 분배 비율 `0.5/0.5`, 미검증 측 몫 UNUSED; `GameImpactCredit.step` FSM 상태 순서 및 콜백/반환값 보존.
- Sable JNI/모드 의존성/Gradle/Apply Actions 계약, 서버·클라이언트 경계, owner generation, fingerprint, 미로드 청크 접근 금지.
- `worldMutation=OFF`; 다른 버전의 손상·응력·변형 기능 임의 선구현 금지.

## 8. 적용 순서와 검증 게이트

1. **P0 World clearance**: Plot↔World stale-block 반례를 테스트로 고정. Plot↔Plot 분리 회귀. 긍정 분리 신호의 출처 별도 진단.
2. **P0 중복 R2 gating**: 이벤트 상태 갱신은 동일하게 유지하면서 진단과 신용 발행 R2를 분리. 로그 재생으로 46건 승인/episode 및 중복 방지 일치, R2 필요 시 스캔 실행 확인.
3. **P1 32-cell 타깃 수용**: `SableContactRegions`·`ContactCreditLedger`·관련 테스트와 좁은 설계 정책만 개정. 기존 4타깃/32-Q 분배 테스트 보존.
4. **P1 side-aware budget exhaustion**: 실패 측만 격리·반대측 보존, malformed/owner-mismatch 이전과 동일 fail-closed.
5. **P1 cap-aware telemetry**: poll별 source coverage; cap만으로 Q 자동 승인/거절하지 않음.
6. **P2 R4·로그/shape-cache 최적화**: 운영 성능 측정 결과가 실제 이득을 보일 때만 선택 적용.

**인게임 검증 최대 2세션**: (1) B1→A1/A2 Plot↔World, 돌벽 표면을 따라 지속 접촉/슬라이딩 후 진짜 분리 및 재타격. `WORLD_CLEARANCE`가 실제 검증되지 않았으면 ARMED 금지, Q 중복 금지. 5개 이상 verified world targets 있을 때 증거가 확인된 측만 합 Q 내에서 분배. (2) B1→B2/B3 Plot↔Plot, 다중 면·모서리 충돌, 5~32 타깃을 검증하고 Q 보존. Epoch/100 cap 계측, latency 분리. 일치하지 않은 모든 사례는 특정 tick/bodypair/owner UUID/targetBlock/R2-check/FSM/Q와 함께 제출.

## 변경 범위 초안 (구현 승인 범위 아닌 설계 보고)

필수 수정 예상: `collision/RealFixRuntimeBridge.java`, `collision/SableContactRegions.java`, `collision/ContactCreditLedger.java`, 연관 테스트, 관련 검증 문서·§23 정책 기록. Native 포화 poll 메타데이터를 정확히 전달하기 위해 필요한 범위에서만 `diagnostics/NativeCollisionTelemetry.java`와 메타데이터 소비 경로 변경. `GameImpactCredit.java`의 **FSM 수식·상태전이 자체는 변경하지 않음**. `OwnerResolver` 미세 최적화는 측정 전까지 보류. Sable native/JNI·Mixin·R3·R4 물리 근사 재작성·Java/Gradle/Actions 설정 변경 없음.

**검증 상태:** 원인 분석·정책 결정 완료. 소스 수정/실제 Gradle/JUnit/인게임 재시험 미실행. 기술적으로 구분된 불확실성은 `이번 승인 중 실제 잘못된 World rearm 발생 여부`, `Sable native의 잘린 실제 레코드 수`, `새 최적화의 CPU p95 효과`.
