# PDD 통합 설계서 1.0.0

**Physical Destruction and Deforming — 규범적 개발 기준 / API 계약 / 알고리즘 / 구현 로드맵 / DVT 확장 설계**

| 메타데이터 | 값 |
|---|---|
| 문서 식별자 | `PDD-IDS-1.0.0` |
| 문서 버전 | **통합 설계서 1.0.0** (구현 릴리스 번호와 별개) |
| 목표 산출물 | **PDD 애드온 1.0.0** |
| 대상 플랫폼 | Minecraft Java 1.21.1, NeoForge 21.1.x, Create 6.0.10, Create Aeronautics 1.3.2, Sable 2.0.6, Java 21 |
| 문서 성격 | **시간에 의존하지 않는 규범적 요구사항·인터페이스·알고리즘·버전별 완료 조건** |
| 구현 상태의 출처 | **문서가 아니라 작업 시점 GitHub 기본 브랜치/지정 브랜치, 커밋, CI, 최신 실행 증거** |
| 구현 시작점 참고 | `0.2.11-debug` 이후의 계획을 고정하되, 특정 버전이 이미 구현되었는지는 이 문서에서 주장하지 않는다 |
| DVT 연동 | DVT 1.0 Fracture에 PDD 기능 포함; DVT 2.0 Big Bang / DVT 3.0 Reinforce에 대한 확장 경계만 예약 |
| 통합 근거 | PDD 설계 초안 3.3.2, API·알고리즘 역분석 시트(Optimizer 및 SableImpact 보강), Create: Devastation 장기 설계, ZIP 개발·디버깅 운영 지침 |

> **문서 사용의 최상위 명령:** 이 설계서는 "무엇을 만들 것인가·왜·어떤 불변조건으로·어떤 순서로"를 명령한다. **특정 과거 개발 버전의 구현 여부를 판정하거나 재검증을 요구하는 장부가 아니다.** 매 작업은 먼저 *그 시점 GitHub HEAD* 및 누적 검증 기록을 읽고, 본 문서의 **아직 충족되지 않은 가장 이른 로드맵 단계**를 구현한다. 과거 보고서에 적혀 있다는 이유만으로 기존 검증을 다시 수행하는 전용 릴리스를 만들지 않는다.

---

## 0. 판독법·용어·규범의 강제력

- **MUST / 반드시**: 기능·무결성·안전성 계약으로 변경 불가. 변경은 설계 개정 제안과 사용자 승인 후 처리한다.
- **MUST NOT / 금지**: Sable 동작 손상, 근거 없는 입력, 중복 피해, 버전 진척 없는 계측 릴리스 등 허용하지 않는다.
- **SHOULD / 우선**: 타당한 근거가 있을 때만 동등 대안 선택이 가능하다.
- **POLICY**: 사용자 결정을 필요로 하는 게임 규칙 또는 밸런스 값. 미결정 항목은 코드가 안전한 기본값/설정 범위로 분리하되 임의로 영구 확정하지 않는다.
- **EVIDENCE**: 실제 바이너리/JAR·GitHub 소스·단위시험·실행 로그로 확인한 근거. 소스 존재와 게임 실행 성공을 혼동하지 않는다.
- **GATE**: 다음 단계 구현/릴리스 승격을 승인하는 기계적 합격 조건. '다시 조사하기'를 자동으로 뜻하지 않는다.
- **PDD 1.0.0**은 기능이 모두 완성된 출시 버전, **통합 설계서 1.0.0**은 이 독립 문서의 버전이다.
- **검증 이력**은 별도 `docs/verification/` 또는 GitHub 이슈·CI에 축적하며, 이 문서는 그 결과가 바뀌어도 재작성하지 않는다.

### 0.1 충돌 시 우선순위

1. 이번 작업에 대한 사용자의 **명시적 설계 변경/기능 범위 지시**.
2. 본 통합 설계서의 **규범적 계약** 및 이후 승인된 개정문.
3. 고정 대상 의존 JAR의 실제 시그니처·바이트코드·실행 가능한 API 제약. API 사실이 규범과 충돌하면 기능을 몰래 줄이지 않고 차이를 보고하며 설계 수정 판단을 요청한다.
4. **현재 GitHub HEAD 소스/빌드/테스트**: 구현 존재 여부와 재사용 가능 여부만 결정한다. 설계 목표를 덮어쓰지 않는다.
5. 최신 누적 검증 기록/재현 로그/기능 완료 이슈: 해당 조합·시나리오에 한해서 완료 여부 판정에 사용한다.
6. 비교 애드온 구현/과거 보고서/그 외 자료: 아이디어 및 API 탐색 근거. 규범을 임의 대체하지 않는다.

### 0.2 '지난 구현 상태'를 설계에서 완전히 격리하는 절차

- 본문에는 `최신 구현`, `현재 완료`, `다음 릴리스에서 재검증`, `0.2.11은 아직 ...` 등의 **가변형 완료 주장**을 넣지 않는다. 문서 끝의 근거 목록에만 **분석 대상 소스의 식별자**를 출처로 기록한다.
- 0.2.11·0.1.2 등은 **코드 마이그레이션 시작점 설명**에만 쓰며 향후 개발 단계의 영구 판정표에 넣지 않는다.
- 누적 테스트는 `version+commit+dependency hashes+scenario+result+evidence URI`로 등록한다. 버전이 늘어났다는 이유만으로 API 조사, 현상 재현, 구현 진단을 다시 하지 않는다.
- 다음 단계 시작 시 **계획된 새 코드**와 관련된 회귀만 실행한다. 이전 게이트는 변경 영향 범위에 포함되거나 증거가 명백히 반증된 경우에만 재검증한다.
- 미완료 구현을 '안전성 검증' 이름으로 반복 관측 버전으로 대체해서는 안 된다. 진단은 목표 기능 구현에 **동반되는 도구**이고, 해당 버전 릴리스의 주목적은 명시된 신규 기능이다.

## 1. 개발 목적·기능 정의

PDD는 Create Aeronautics의 물리 구조물(함선·비행체 등), 정적 Minecraft 월드 블록, 서로 다른 물리 구조물의 충돌 결과를 받아 **충격/질량에 따른 블록 손상, 영구 변형, 파괴, 주변 응력 전파, 누적 상태 및 시각화**를 제공하는 NeoForge 애드온이다. 엔진과 충돌의 소유자는 Sable이며, PDD는 충돌의 게임 내 재료 반응을 소유한다.

### 1.1 1.0.0 필수 결과

1. 일반 블록으로 구성된 구조물↔지형 및 구조물↔구조물 충돌에서 **양측 타깃**을 공간/소유자에 맞게 식별한다.
2. 충돌점 정상 방향의 **상대 접촉속도·접촉 유효 질량·회전 성분**으로 피해 예산을 산출한다.
3. 복수 콜백·프레임·동일 충돌 양측 보고를 **하나의 episode 예산 원장**으로 통합한다.
4. **경도(H)**는 직접 손상 저항, **연성(D)**은 파괴 전 변형 능력, **탄성(E)**은 PDD 내부 전파 감쇠를 결정한다. 파괴 임계값은 경도와 별개다.
5. 충돌을 받은 실제 블록에 누적 손상/변형을 유지하고, 실제로 가능한 블록에는 **서버 권위 격자 변형**을 일으키며 임계 도달 시 파괴한다.
6. 주변 블록에 한정된 총예산·탐색 깊이·작업량을 사용한 응력을 전파한다. Sable의 구조물 분리/충돌기 재구성과 일관되게 작동한다.
7. 정적 월드와 Sable plot 각각에서 상태·드롭·BE(블록 엔티티)·저장·재시작·청크 로딩·클라이언트 균열 표시를 안전하게 관리한다.
8. Minecraft 기존 폭발과 투사체/엔티티 타격에서, **기존 시스템을 대체하지 않으면서 추가 손상**을 중복 없이 처리한다.
9. 다수의 대형 구조물이 활동하는 소규모 멀티플레이에서 위험한 무한 작업/메모리 폭주를 방지하고 측정 가능한 성능 상한을 갖는다.
10. DVT 1.0이 손상 엔진을 복제하지 않고 사용할 수 있는 **입력/물성/계획/커밋** 확장 경계를 제공한다.

### 1.2 명시적 비목표

- Sable의 rigidbody solver, collider, 반발·운동량 응답, **이미 구현된 터널링 방지/CCD**를 PDD가 다시 구현하지 않는다.
- 일반 충돌 피해 때문에 Sable `applyImpulseAtPoint`를 추가 호출하거나 `CollisionResult(ZERO,true)`로 정상 접촉을 지우지 않는다.
- 대규모 무너짐·파편 생성을 위해 Sable의 공식 구조물 분리 기능과 경쟁하는 2차 범용 물리 엔진을 만들지 않는다.
- **DVT 2.0**의 연료 기관 연쇄 폭발·전체 구조물 질량에 따른 *추가 게임 피해 증폭*, **DVT 3.0**의 아이템·고글·환경·습윤·부식·가변 질량 기능을 PDD에 선행 구현하지 않는다.
- 비교 애드온의 GPL/LGPL 소스를 무단 복제하지 않는다. 필요한 설계 원리를 독립 구현한다.

### 1.3 책임 소유자와 입력-출력

| 소유자 | 입력 | 출력 | PDD가 하면 안 되는 일 |
|---|---|---|---|
| Minecraft/NeoForge | 블록 레지스트리·차원·청크·폭발·서버 이벤트 | 월드 생명주기·블록 상태·드롭·저장 | 원본 월드 사건 이중 실행 |
| Create | 기계·회전·제작법·블록 | 기계/제작 정보 | Create 네트워크를 임의로 재작성 |
| Create Aeronautics | 물리 구조물 구현 | Sable 상호작용 구조물 | 별도 항공 운동 재계산 |
| Sable | 강체·서브레벨·collider·물리 시간 | 접촉 후보/콜백/질량/pose | 충돌/CCD/structure split 대체 |
| PDD | 신뢰할 수 있는 사건 스냅샷 | 손상·변형·파괴 계획 및 서버 커밋 | 물리 step 중 월드 상태 직접 변경 |
| DVT 확장 | 신규 피해 source / MaterialModifier | PDD 계약에 맞는 추가 budget·물성 | PDD 코어 피해 알고리즘 복제 |

## 2. 증거의 재사용 규칙(반복 검증 방지의 핵심)

### 2.1 증거는 '정적 사실'과 '동적 결과'로 구분

| 등급 | 출처 | 판정 가능한 것 | 판정 불가능한 것 |
|---|---|---|---|
| `ABI` | 고정 해시 JAR + `javap` | 클래스·descriptor·호출 지점·명시 분기 | 실제 게임 충돌 포괄성·좌표 정확성 |
| `SRC` | Git commit SHA + 소스 | 알고리즘 구현 존재·가드/정책/호출 연결 | 모드 로딩 성공·TPS |
| `UNIT` | 커밋별 결정론적 시험 | 순수 수식·상태 인코딩·예산 불변식 | 실제 Sable/Aeronautics 사건 전송 |
| `BUILD` | CI run/artifact hash | 소스 빌드·테스트·패키징 | 피해가 게임에 적용됐다는 사실 |
| `GAME` | 정확한 모드 조합·시나리오·원본 로그/영상 | 해당 장면에서의 실제 동작 | 다른 버전·환경 전체로의 자동 일반화 |
| `PERF` | 부하·하드웨어·설정 동반 측정 | 정의한 성능 예산에서 결과 | 모든 서버 사양에서 무조건 성능 보장 |
| `DESIGN` | 승인된 문서 | 목표·불변조건·로드맵 | 이미 구현됐다는 증거 |

`ABI`와 `SRC`는 **동일 아티팩트 해시와 호출 계약이 유지되는 범위**에서 재사용한다. `UNIT/GAME/PERF`는 테스트 대상 코드와 의존/시나리오 영향 범위가 바뀌지 않는 한 재사용 가능하다. 테스트가 불가능한 상황은 `NOT_RUN`이지 `PASS`가 아니다.

### 2.2 검증 재실행을 허용하는 트리거 — 이 밖의 단순 '문서가 오래됨'은 트리거 아님

- 고정 JAR 버전/해시 변경, 콜백 메서드 또는 JVM descriptor 변경, Mixin 주입 실패.
- **실제 Git 코드**가 테스트된 경로를 변경함(메서드 구현·좌표 변환·물성·저장 형식·스레드/커밋·패킷·옵티마이저 어댑터).
- 새로운 충돌 타입/새 재료 source/새 타깃 공간 등 기존 테스트와 **범위가 다른** 입력 도입.
- 동일 조건에서 관측한 재현 가능 버그, 월드 무결성 위반, 새 모드와의 Mixin 충돌.
- CI/재현 로그에서 기존 게이트의 직접 실패 증거.

반대로 아래 사유만으로는 **재검증 릴리스 금지**: 과거 초안에 '미검증'이라는 문장이 있음, 다른 버전의 릴리스 노트가 진단 상태였음, 새 ChatGPT 대화에 로그가 안 붙어 있음, 이미 검증된 API를 모델이 모르겠다고 함. 근거가 현재 대화에 없으면 GitHub 누적 증거를 조회하고 검증 기록의 **해시·범위·조건을 비교**한다. 못 찾으면 `UNKNOWN`으로 기록하고, 새 버전 목표 구현에 필요한 최소 범위 내에서만 사실 확인을 한다.

### 2.3 검증 장부 스키마(설계 본문 밖에 둔다)

```yaml
requirement_id: PDD-COL-02
build_version: 0.3.0
commit_sha: <40-character-sha>
dependency_hashes: {sable: <sha256>, aeronautics: <sha256>}
evidence_type: UNIT | BUILD | GAME | ABI | SRC | PERF
scenario_id: SHIP_TERRAIN_ORDINARY
result: PASS | FAIL | BLOCKED | NOT_RUN
source_paths: [path/to/file.java]
artifact_url_or_log_path: path/to/evidence
checked_at_utc: <timestamp>
assumptions: [coordinate_transform_generation, physics_callback_phase]
```

장부는 `docs/verification/`에 기계 파싱 가능한 YAML/JSON으로 보관하거나 GitHub 이슈/액션 아티팩트를 연결한다. 새 버전이 만들어지면 장부만 추가하고 **설계서 버전은 그대로 유지**한다. 이슈가 `closed`인지보다 **승인 기준을 실제로 충족했는지**를 우선한다.

### 2.4 다음 버전 선택 알고리즘

```text
load(ApprovedDesignSpec)
head = github.defaultBranch.latestCommit()
read(head.sources, build.gradle, mixins, workflows, evidenceLedger)
for release in FixedRoadmap[ascending]:
    covered = verifyCriteriaFromExistingSourceAndEvidence(release)
    if covered == COMPLETE:
        mark SATISFIED_BY <commit/evidence>; continue
    target = release
    break
implement(target.requiredDeliverables)  # 진단 전용으로 대체 금지
run(target.newTests + actualImpactedRegressionTests)
recordEvidenceInLedger(); publishVersionScope()
```

기존 구현이 상위 마일스톤을 일부 만족한다면 **중복 구현하지 않고 재사용**하되 그 마일스톤에서 빠진 기능을 채운다. 안전상 긴급 수정은 `hotfix`로 허용하며 릴리스 목표를 영구 이동하지 않는다. 계획 변경이 필요하면 이 설계 문서의 버전 개정이 먼저다.

## 3. 고정된 기술 분석 결과와 API 경계

다음 표는 2026-10-28 제공된 **해시가 고정된 업로드 바이너리**에 대한 API·정적 관측 사실을 기준으로 만든 **타깃 시그니처/경계**이다. 전체 제품에서 호출 성공이 이미 입증되었다는 의미는 아니다. 동일 해시 대상으로 **바이트코드 역분석을 처음부터 재수행할 필요가 없다**. ABI 변경 시에만 영향받는 행을 재검토한다.

### 3.1 Sable 핵심 시그니처·인수 역할

| 소유 클래스(간략명) | 메서드 / JVM 인수 | 목적 | 계약·주의점 |
|---|---|---|---|
| `dev.ryanhcode.sable.api.physics.mass.MassData` | `getMass(): double` | 질량 조회 | 0,∞,NaN/물리체 병합 반영 검증; 블록 수로 대체하지 않음 |
| `MassData` | `getInverseMass(): double` | 이동성/역질량 | 정적 상대는 **0**로 모델링 |
| `MassData` | `getCenterOfMass(): Vector3dc` | 회전 중심 | 위치 좌표계 라벨 필요 |
| `MassData` | `getInverseInertiaTensor(): Matrix3dc` | 회전 관성 역행렬 | world/plot 공간 혼합 금지 |
| `MassData` | `getInverseNormalMass(Vector3dc point, Vector3dc normal): double` | 접촉 방향 역유효 질량 | 위치와 방향이 **동일한 frame**이어야 함 |
| `dev.ryanhcode.sable.sublevel.ServerSubLevel` | `getMassTracker(): MassData` | 서브레벨 질량 | 병합 체일 수 있으므로 episode에서 중복 계상 금지 |
| `ServerSubLevel` | `getSelfMassTracker(): MassTracker` | 자체 질량 | 합쳐진 체 질량과 오인 금지 |
| `ServerSubLevel` | `getPlot(): ServerLevelPlot` | Sable plot 접근 | world 블록과 변경 API 다름; 상속형 `LevelPlot` 접근도 존재 |
| `ServerSubLevel` | `getRuntimeId(): int` | 런타임 식별 | 영속 UUID 대신 사용 금지 |
| `ServerSubLevel` | `getSplitFromSubLevel(): UUID` | split 계보 | 저장/상태 이전 시 계보 참고 |
| `ServerSubLevel` | `getUserDataTag(): CompoundTag` | 사용자 데이터 | PDD 저장 여부는 plot 수명 계약 검토 후 결정 |
| `dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle` | `of(ServerSubLevel)` | handle 취득 | 없는/파괴 중인 바디 방어 |
| `RigidBodyHandle` | `getLinearVelocity(Vector3d dest): Vector3d` | 선속도 스냅샷 | solver 이후 속도를 충돌 전 속도로 가정 금지 |
| `RigidBodyHandle` | `getAngularVelocity(Vector3d dest): Vector3d` | 각속도 스냅샷 | 한 물리 step의 pose와 묶기 |
| `dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem` | `get(Level): SubLevelPhysicsSystem` | 물리 시스템 조회 | 차원/레벨 구분 |
| `SubLevelPhysicsSystem` | `getPhysicsHandle(ServerSubLevel)` | 해당 바디 handle | source owner/시점 교차확인 |
| `SubLevelPhysicsSystem` | `getCurrentlySteppingSystem()` | 물리 step 진행 여부 | 월드 변경 안전성 가드의 *한 입력*이지 단독 충분조건 아님 |
| `dev.ryanhcode.sable.api.physics.callback.BlockWithSubLevelCollisionCallback` | `sable$getCallback(BlockState)` | 블록 고유 콜백 선택 | 원본 콜백 보존 래핑 대상 |
| `BlockSubLevelCollisionCallback` | `sable$onCollision(BlockPos hit, BlockPos other, Vector3d impactPosition, double signedVelocity)` | 블록 충돌 효과·응답 | hit·other의 소유 공간, impactPosition frame, scalar 단위 추론 금지 |
| `BlockSubLevelCollisionCallback` | `onCollision(int,int,int,int,int,int,double,double,double,double,boolean):double[]` | 네이티브 연결용 default path | 주 호출 위치/반환형 변경 금지 |
| `dev.ryanhcode.sable.physics.impl.rapier.Rapier3D` | `clearCollisions(long sceneHandle):double[]` | 충돌 효과 레코드 | 15-double 배열은 **보조 계측**이지 전수 event 보증 아님 |
| `dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent` | `getPhysicsSystem(); getTimeStep():double` | 물리 후처리 시점 | 이벤트 수신만으로 블록 변경 원자성 보장 안 됨 |

구체적인 JVM descriptor 및 선언 재현 목록은 **별첨이 아닌 본 문서 3.5절**에 보존한다. Sable `BlockWithSubLevelCollisionCallback`은 인터페이스 명칭이 길므로 위 표에서 소유 클래스 간략명으로 표시했다.

### 3.2 충돌 수집 주 경로와 보조 경로

```text
Sable RapierVoxelColliderBakery.buildPhysicsDataForBlock(BlockState)
  -> BlockWithSubLevelCollisionCallback.sable$getCallback(BlockState)
  -> [PDD: observed callback expression, 원본 반환값 확보]
  -> CollisionObserverDecorator(delegate=original, blockMetadata)
  -> Sable runtime calls delegate-compatible sable$onCollision(...)
     -> copy raw args + source/epoch into PDD bounded intake
     -> invoke original callback exactly once if present
     -> return original CollisionResult unchanged
  -> Sable physics processing continues as before
```

- **주 경로:** `RapierVoxelColliderBakery.buildPhysicsDataForBlock(BlockState)`의 기존 콜백 취득 *표현식 값 래핑*, 권장 `@ModifyExpressionValue`/`@WrapOperation`. **Collision Damage의 `@Redirect`처럼 원본 호출을 대체하지 않는다.** 기존 callback이 null일 때의 신규 no-op 콜백은 native branch를 추가할 수 있으므로 반환값만 같다고 비회귀가 증명되지는 않는다. no-op 반환의 완전한 원본 동등성은 해당 ABI에 대한 별도 smoke scenario로 확인한다.
- **보조 경로:** `RapierPhysicsPipeline.processCollisionEffects()` 안 `Rapier3D.clearCollisions(J)[D` 15개 단위는 기록 누락·스칼라·분포를 진단할 뿐 피해 예산에 더하지 않는다.
- **특수 경로:** `FragileBlockCallback`, `BeltBlockCallback` 원본의 호출/반응은 decorator의 delegate로 반드시 살아 있어야 한다. 메서드 RETURN 계측은 디버그 중복 보조 증거일 뿐 또 하나의 충돌이 아니다.
- **포괄성 실패 대책:** 일반 블록·구조물 간 실제 충돌이 주 수집기로 도달하지 않으면 **다른 원본 보존 경로**를 해당 타깃 단계에서 추가한다. *일반 충돌의 전수 관측을 증명하지 못하는데 효과 배열로 빈칸을 메워 피해를 실행하는 것은 금지*.
- **질량 파악:** `getMassTracker()`와 `getInverseNormalMass()`로 읽고, collider·sublevel·level ↔ body mapping을 같은 physics snapshot에서 결합한다.

### 3.3 비교 애드온의 채택·배제 결정

| 비교 바이너리 | 채택할 지식 | 복제/도입 금지 또는 제한 | 근거 |
|---|---|---|---|
| True Impact 0.5.7-delta | 콜백 래핑, body snapshot, `ImpactMetrics`, 누적 damage/stress/deferred 계층 분리 | **`DamageResolver.resolve`의 상수 `NONE` 반환을 완성된 피해 알고리즘으로 취급 금지**; 라이선스 LGPL-3.0-only 검토 없이 소스 복사 금지 | `DiagnosticCallbackWrapperMixin`, `SableBodyReader`, `BlockDamageAccumulator`, `DamageResolver` 바이트코드 |
| Collision Damage 1.0.8 | 일반 블록용 universal callback 등록 위치, post physics 큐/제거 지연 | 원본 충돌 결과 변경 `CollisionResult(ZERO,true)`·최소 속도 즉시 파괴를 PDD 기본 메커니즘으로 도입 금지 | `RapierVoxelColliderBakeryMixin`, `UniversalFragileCallback` |
| SableImpact alpha 1.1.0 | 태그별 재료 JSON, 국소 `LocalKineticAnalyzer`, 예산 있는 trace, 균열 패킷 | 기존 콜백을 무시하는 `@Redirect`, collision removal, 독립 파편 assembly, 선체 체적 전수 스캔; GPLv3 코드 직접 복제 금지 | `KineticColliderMixin`, `KineticImpactCallback`, `BeamSnapper`, `CrackManager` |
| Sable Optimizer 1.0 | **선택적 지원**할 활동/휴면 상태·질량 갱신/분리 지연 정보 | Optimizer에 코어를 강제 연결·무조건 성공 주장·물리 틱 중 wake로 Sable 동작 변경 금지 | `ActivityTracker`, `ServerSubLevelMixin`, `SubLevelPhysicsSystemMixin` |

**라이선스 준수:** 바이너리 인터페이스·구조를 문서화하는 것과 타 모드의 보호되는 코드를 직접 재배포하는 것은 구별한다. PDD 구현은 독립 작성한다.

### 3.4 Sable Optimizer 1.0 호환성 계약

- `sable_optimizer`는 **선택 의존성**. 미설치 시 해당 클래스의 정적 참조/초기화가 발생해서는 안 된다.
- Optimizer가 `ServerSubLevel.updateMergedMassData`, `prePhysicsTickBegin/prePhysicsTick`, `applyQueuedForces`, Sable heatmap/pose, Create KineticNetwork를 조건부 생략할 수 있다. 이는 물리/질량/분리 시점 변경이지 PDD 계산식을 바꾸라는 뜻이 아니다.
- 변경 직후 `blockChangeEpoch++`, Sable 질량 스냅샷 무효화 및 `MASS_OR_SPLIT_PENDING`으로 진행한다. 재조회/완료 확인 전 다음 사건의 오래된 mass/pose를 신뢰하지 않는다. 재시도 횟수/TTL은 명확히 제한한다.
- 옵셔널 wake는 `ModList.isLoaded("sable_optimizer")` 확인 후 별도 어댑터가 `ActivityTracker.resetActivity(UUID)`와 호환 검증된 방법만 호출하도록 한다. 실패 시 **경고 + 안전 모드/사용자 설정 권고**, 묵살 금지.
- 정확한 Optimizer 구현에서 운영 보수 프로필은 `enableOptimization=false`다. 최적화 ON 환경은 별도 호환성 게이트에서 승인해야 한다. **Optimizer가 설치되어도 무설치와 완전히 같은 물리를 보장한다는 선언 금지.**
- PDD는 Optimizer의 global static substep 카운터를 내부 episode 고유 시간으로 절대로 사용하지 않는다.

### 3.5 정확한 JVM ABI 계약(오류 유형을 컴파일·로딩에서 탐지)

```text
MassData.getMass: ()D
MassData.getInverseMass: ()D
MassData.getCenterOfMass: ()Lorg/joml/Vector3dc;
MassData.getInverseInertiaTensor: ()Lorg/joml/Matrix3dc;
MassData.getInverseNormalMass: (Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)D
ServerSubLevel.getMassTracker: ()Ldev/ryanhcode/sable/api/physics/mass/MassData;
ServerSubLevel.getSelfMassTracker: ()Ldev/ryanhcode/sable/api/physics/mass/MassTracker;
ServerSubLevel.getRuntimeId: ()I
ServerSubLevel.getSplitFromSubLevel: ()Ljava/util/UUID;
RigidBodyHandle.getLinearVelocity: (Lorg/joml/Vector3d;)Lorg/joml/Vector3d;
RigidBodyHandle.getAngularVelocity: (Lorg/joml/Vector3d;)Lorg/joml/Vector3d;
SubLevelPhysicsSystem.getCurrentlySteppingSystem: ()Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;
BlockWithSubLevelCollisionCallback.sable$getCallback: (Lnet/minecraft/world/level/block/state/BlockState;)Ldev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback;
BlockSubLevelCollisionCallback.sable$onCollision: (Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lorg/joml/Vector3d;D)Ldev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback$CollisionResult;
Rapier3D.clearCollisions: (J)[D
ActivityTracker.resetActivity: (Ljava/util/UUID;)V
```

**명시적인 API 미해결을 모델이 임의 추정하지 않는 규칙:** 콜백 `hit/other`의 공간, `impactPosition`의 frame, `signedVelocity`의 단위/물리시점, 질량 API의 좌표 기준, no-op wrapper가 null callback을 바꿀 때의 JNI 분기는 **정적 시그니처만으로 결정되지 않는다**. 따라서 입력은 `UNKNOWN`으로 구분하고 잘못된 값으로 월드 변경하지 않는다. **이를 '문서에서 아직 미검증'이라는 이유로 매 버전 반복 탐색하지 않고**, 대응 로드맵 버전의 acceptance gate에서 *한 번* 확인하며 증거를 장부에 축적한다. 이른 단계의 실패가 있을 때만 재개발하고, 해결한 이후 릴리스는 건너뛴다.

## 4. 아키텍처·모듈 단방향 의존성

```text
Minecraft / NeoForge / Create / Aeronautics / Sable (external)
               |             ^ original response unchanged
               v             |
  bridge.sable / bridge.vanilla / bridge.projectile
               | RawObservation (immutable, frame marked)
               v
    collision.capture -> collision.normalize -> collision.episode
               |        BodySnapshot/TargetSnapshot/EnergyLedger
               v
   material.resolve  ---> mechanics.plan(energy,damage,plasticity,stress)
               |                         |
               |                immutable DamagePlan
               v                         v
  state.read <- state.repository -> mutate.validate -> mutate.commit
                                            |
                               Sable collider/split reconciliation
                                            |
                         state.persist -> net.deltaSync -> diagnostics

   extension SPI: DamageSource/EnergyProvider/MaterialModifier/
                  FractureTopologyProvider (optional DVT 1/2/3)
```

- `bridge/*`는 외부 API/스레드에 종속될 수 있지만 `mechanics/*`는 반드시 순수 Java 산술·불변 DTO 위에서 작동한다.
- 원시 관측과 정규화된 사건, 손상 계획과 월드 변경 명령을 **다른 타입**으로 분리한다. `RawObservation`을 `BlockMutationAdapter`에 직접 전달하지 않는다.
- 모든 `DamageSource`는 하나의 `SourceBudgetId`를 소유한다. 별개 소스의 동일 월드 파괴 시도는 `(TargetKey, blockFingerprint)` CAS/예약으로 중복 방지한다.
- `DVT MaterialModifier`는 resolver에만 연결하고 Sable 물리 질량 조정/연료 폭발 엔진을 PDD 코어에 넣지 않는다.
- 실제 패키지 이름/파일 수는 구현 시 최적화할 수 있지만 **책임·데이터 경계는 강제**한다.

### 4.1 권장 모듈별 공개 계약

| 영역 | 권장 공개 인터페이스 | 입력 → 출력 | 금지 |
|---|---|---|---|
| `bridge.sable` | `SableCollisionAdapter` | 실제 callback/step → `RawObservation` | 충돌 응답 대체/월드 변경 |
| `collision` | `ContactNormalizer`, `EpisodeAssembler` | 원시 스냅샷 → `CollisionSnapshot`, `ImpactEpisode` | 부정확한 frame 자동 보정 |
| `mechanics` | `EffectiveMassModel`, `ImpactBudget`, `DamagePlanner`, `StressPlanner` | snapshot+material → 불변 계획 | 서버 월드 직접 참조 |
| `material` | `MaterialDefinitionRegistry`, `EffectiveMaterialResolver` | block ID+context → 물성 | 설치된 블록마다 레시피 분석 |
| `state` | `DamageStateStore`, `PlotStateStore` | target key → packed state | runtime-only ID로 영구 저장 |
| `mutate` | `BlockMutationAdapter`, `MutationTransaction` | 검증된 intent → 결과/사유 | 물리 stepping 도중 변경 |
| `net` | `DamageDeltaSync`, `CrackRenderer` | server state delta → client view | 클라이언트 권위 피해 계산 |
| `diagnostics` | `EvidenceReporter`, `InvariantMetrics` | 각 단계 카운터 → 로그/테스트 자료 | SELFTEST를 LIVE로 위장 |
| `extension` | `DamageSource`, `EnergyProvider`, `MaterialModifier` | 외부 확장 → 중립 입력 | 코어 직접 패치 의존 |

## 5. 충돌 데이터 스키마와 공간 정규화

### 5.1 강제 불변 DTO

```text
RawObservation {
 sourceType: SABLE_BLOCK_CALLBACK | SABLE_EFFECT_DIAGNOSTIC |
             VANILLA_EXPLOSION | PROJECTILE | ENTITY_HIT | FUTURE_EXTENSION;
 dimension; physicsSystemId; serverTick; physicsStepId?;
 rawHit?; rawOther?; rawContactPoint?; rawSignedVelocity?;
 rawEffectScalar?; originalCallbackType?; originalResponse?;
 originFrame: UNKNOWN | WORLD | PLOT | BODY;
 observedThread; captureSequence; originalCallbackPresent;
}

BodySnapshot {
 bodyKey: (dimension, physicsSystemId, stableBodyIdentity?) |
          STATIC_ENVIRONMENT;
 physicsEpoch; poseEpoch; massEpoch; transformEpoch;
 pose; linearVelocity; angularVelocity; centerOfMass;
 mass; inverseNormalMassProvider; validityFlags;
}

BlockTarget {
 targetSpace: STATIC_WORLD | SABLE_PLOT;
 dimension; stablePlotId?; localBlockPos; blockFingerprint;
 expectedBlockEntityFingerprint?; blockStateEpoch; loadedAtSnapshot;
}

CollisionSnapshot {
 sourceBudgetId; contacts[]; bodyA; bodyB; contactPointWorld;
 unitNormalWorld; closingSpeed; inverseNormalMassA/B;
 materialGeneration; targetA?; targetB?; provenance;
 validatedFlags; transformGeneration; physicsStepId;
}
```

`null`/부재와 `(0,0,0)`을 혼동하지 않는다. `blockFingerprint`는 위치만이 아니라 설치된 `BlockState`, 필요한 블록 엔티티 정체성, 소유 plot/epoch를 포함해야 한다. 이벤트를 서버 틱 경계 너머 저장할 경우 mutable JOML 인수의 **복사본**을 저장한다.

### 5.2 frame 계약

1. `hit`와 `other` 각각의 소유 공간(정적 world 또는 Sable plot)과 차원 식별을 **별도로** 한다.
2. 같은 physics step에서 body pose/position/rotation/center of mass/velocity를 스냅샷으로 획득한다. plot origin과 local block position, world transform을 명시한다.
3. 위치: `p_world = R · p_local + t` (plot origin 포함); 방향/법선·벡터: `v_world = R · v_local`; normal은 정규화한다.
4. 각 바디 무게중심·접촉점·법선·속도는 같은 시각/동일 공간으로 변환한다. `getInverseNormalMass(p,n)`가 요구하는 공간이 world가 아니면 **p와 n을 함께 역변환**한다.
5. 변환/물리 step이 불일치하거나 `NaN`, 무한대, 영벡터, 안정 소유자 조회 실패면 `REJECTED_INPUT` 또는 `DEFERRED`이고, **타깃 월드 변경을 수행하지 않는다**.
6. 실측 좌표가 정상임을 보장하는 시나리오 시험은 `0.2.13` 수용 조건으로 고정한다. 한 번 통과한 동일 ABI·경로에 대해 매 버전 독립 재확인하지 않는다.

### 5.3 선형 + 회전 접촉속도

하나의 기준 프레임으로 정규화한 뒤:

\[
\vec v_A(p)=\vec v_{A,lin}+\vec\omega_A\times(\vec p-\vec c_A)
\]
\[
\vec v_B(p)=\vec v_{B,lin}+\vec\omega_B\times(\vec p-\vec c_B)
\]
\[
\vec v_{rel}=\vec v_A(p)-\vec v_B(p),\quad
v_n=\max(0,-\vec v_{rel}\cdot\hat n)
\]

여기서 단위 법선 `n`은 B→A 방향으로 정의한다. 정적 지형은 `v=0`, `omega=0`이다. 이탈 중의 `v_n=0`을 접촉이라는 이유로 양수로 바꾸지 않는다. 접선 운동 성분을 임의로 법선 충돌 에너지에 합산하지 않는다. Sable `signedVelocity`는 부호·단위·전/후 solver 시점을 확정하기 전 **원시 필드**이며 `abs()`로 사고를 만드는 자료가 아니다.

### 5.4 사전 충돌 샘플의 품질

충돌 뒤 속도만 읽으면 반발 후 접근속도가 0 또는 반대 방향이 될 수 있다. 본 설계는 동일 step의 **pre-solver snapshot**을 1순위, 의미가 확인된 동등 입력을 2순위, 품질 미보증 callback scalar를 진단 전용 3순위로 사용한다. snapshot 부재 시 mass와 임의 보간 속도를 섞어 타깃을 부수지 않는다. `InputQuality = PRE_SOLVER_VALIDATED | EQUIVALENT_VALIDATED | DIAGNOSTIC_ONLY | INVALID`를 사용한다. 활성 피해에는 앞의 두 등급만 허용한다.

## 6. 질량·충격 에너지 및 보존 원장

### 6.1 실제 물리량의 게임 내 대응

두 강체의 정상 방향 역유효질량(회전 관성 포함):

\[
q_i=m_i^{-1}+[ (\vec p-\vec c_i)\times\hat n]^T I_i^{-1}
[(\vec p-\vec c_i)\times\hat n]
\]
\[
m_{eff}=\frac1{q_A+q_B},\qquad E_{normal}=\frac12m_{eff}v_n^2
\]

- API가 같은 frame을 요구하는 경우 `q_i = MassData.getInverseNormalMass(p_i,n_i)`로 계산한다.
- 정적 세계는 `q_static=0`이며 큰 유한 수를 `m_static`에 대입하지 않는다.
- `q_A+q_B<=epsilon` 또는 비유한·음수면 유효값으로 강제 보정하지 않고 사건을 중단한다.
- 표기 `E_normal`은 **Sable 단위계의 충격 강도 지표**, 실측 검증 전 SI 단위의 줄(J)·소모된 실제 solver 에너지라는 주장이 아니다.
- `J*=m_eff v_n`은 법선 접근 임펄스 지표일 뿐 Sable이 적용한 실제 물리 충격량이라고 선언하지 않는다. 가속도/힘을 모르면 무조건 `dt`를 곱하거나 나누지 않는다.

### 6.2 ImpactEpisode: 한 충돌 하나의 예산

`EpisodeKey = (dimension, physicsSystemId, physicsStepId, canonical unordered body pair, spatial contact cluster)`. 단계는 `OBSERVING → SNAPSHOTTED → CANONICALIZED → ACCOUNTED → PLANNED → COMMITTED | REJECTED | EXPIRED`이며 역행하지 않는다. 여러 블록·양측 콜백·native telemetry가 동일 사건이면 **새 예산 생성 금지**. 그 대신 기존 episode의 observation source 목록에 추가한다.

하나의 episode에 정규화된 접점 가중치 `w_i >= 0`, `Σw_i=1`을 배정한다. 검증된 면적/solver 기여 정보가 없으면 동일 중요도의 접점에 균등 배분한다. 블록별 양측 `a_i >= 0`, `b_i >= 0`, `a_i+b_i <= 1`을 적용한다.

\[
E_i=E_{episode}w_i,\quad
E_{i,A}=E_ia_i,\quad E_{i,B}=E_ib_i
\]
\[
\sum_i(E_{i,A}+E_{i,B})+E_{other}+E_{unused}
\leq E_{episode}+\varepsilon
\]

`E_other`는 동일 예산에서 소비/전파/변형으로 **실제 분배된 값만** 가산한다. 직접손상에 사용한 예산을 응력·변형에 다시 전액 넣는 것은 금지한다. 양측 모두 손상해도 충돌 에너지를 두 번 만들지 않는다. 한 episode 안의 미사용 잔여는 다음 tick의 '새 충격'으로 자동 충전하지 않는다.

### 6.3 다중 서브스텝·정지접촉·재접촉

- 같은 body pair의 resting contact가 매 step 반복되더라도 **신규 정상 접근 에너지가 없으면 손상 추가 0**.
- 물리 서브스텝마다 서로 다른 독립 접촉은 cluster+전/후 상태로 구별한다. 접촉 보고 횟수를 충격 횟수로 오인하지 않는다.
- 점유한 물리체의 동일한 pre-step 상대 운동 에너지 변화보다 더 많은 총 PDD 사건 예산을 승인하지 않는다. 불완전한 입력은 보수적 상한 또는 `DEFER` 처리한다.
- 같은 서버 틱에 서로 다른 물리 시스템·차원에서 발생한 사건의 cache/episode ID는 충돌하지 않아야 한다.
- 한 raw record의 `effectScalar`는 주 충돌 episode에 관측 메타데이터로 붙인다. 절대로 별도 `E`로 더하지 않는다.

### 6.4 필수 수학 불변식

`finite(E)`, `E>=0`, `Σ budget debit <= credit`, 정규화 가중치 합 ≤1, event ID의 단일 소유, 배열 순서/양측 콜백 방향 무관, 관측 수 증가만으로 `E`가 증가하지 않음. 모든 예산 감소에는 결과 코드(`DIRECT`, `PLASTIC`, `STRESS_TRANSFER`, `ABSORBED`, `UNRESOLVED`, `EXEMPT`)를 남긴다.

## 7. 물성 등록·피해·변형·파괴 모델

### 7.1 저장 단위와 의미를 바꾸지 않는다

| 값 | 유효 범위 | 물리/게임상의 역할 | 저장 수명 |
|---|---:|---|---|
| 경도 `H` | `u8: 0..255` | 직접 손상 저항 계수 | 블록 정의 캐시 |
| 연성 `D` | `u8: 0..100` | 파괴 전 허용 영구 변형 능력 | 블록 정의 캐시 |
| 탄성 `E` | `u8: 0..100` | **PDD 내부** 이웃 응력 감쇠 | 블록 정의 캐시 |
| 파괴 임계 `T` | `1..255` 또는 특수 불괴 플래그 | 누적 손상 파괴 시점 | 재료/상태 정책 캐시 |
| 누적 손상 `damage` | `u8: 0..255` | 실제 파괴 판정 상태 | 좌표별 희소 영속 상태 |
| 누적 변형 `deformation` | `u8: 0..255` | 변형 이력/능력 소진 | 좌표별 희소 영속 상태 |
| 균열 표시 `crackVisual` | `u8: 0..255` | 시각 정보, 물리 손상과 분리 | 좌표별 또는 표시 세션 |

모든 숫자의 **논리 저장 폭**은 각각 1바이트 이내다. Java 인스턴스 필드가 반드시 한 바이트 실제 힙 메모리라는 뜻은 아니다. `MaterialDefinition`에는 별도의 프로필 ID·불괴 정책·밀도 hint를 추가할 수 있으며 이는 각 속성의 byte 규칙과 별개다.

### 7.2 블록 정의별 초기화 (설치 블록 전수 스캔 금지)

서버/데이터팩 재로드에서 `BuiltInRegistries.BLOCK`의 **등록된 블록 ID**만 열거한다. 기본 `BlockState.getDestroySpeed(...)`와 `Block.getExplosionResistance()`를 경도 추정 입력으로 사용한다. 보조 제작법은 블록 출력 및 해석 가능한 재료만 집계한다. 여러 제작법이 있을 때는 안정적 순서를 택하고 순서 변경이 재현성에 영향을 주지 않도록 기록한다. 사용자 오버라이드는 기본 추론보다 우선한다. 미지원 레시피/속성 오류는 해당 원소 fallback 후 진단한다.

기존 휴리스틱 예시(채택 여부는 게임 밸런스 POLICY):

\[
H=\operatorname{round}\{255[0.65\min(1,\ln(1+\max(0,s))/\ln51)
+0.35\min(1,\ln(1+\max(0,b))/\ln1201)]\}
\]

`s`=채굴 파괴 속도, `b`=폭발 저항. 마이너스 파괴 속도는 **불괴 정책**으로 우선 보호한다. 제작법 가중치 `원래 속성 75% + 재료 평균 25%`는 **휴리스틱이지 검증된 재료 물성 법칙이 아니므로 하드코딩 불변 규칙이 아니다**. 새로운 모드 전체를 한 번 등록하는 것은 허용하지만 세계 모든 설치 블록을 재료 추론 대상으로 삼지 않는다.

**재료 조회 계층:** `BaseByBlockId → optional BlockStateOverride → user/datapack override → EffectiveMaterialContext`. DVT 3.0이 오면 `MaterialModifier`가 이 끝에 추가된다. **동적 조정자의 우선순위**는 DVT 3.0 상세 설계에서 결정하며 PDD가 미리 임의 확정하지 않는다. 물성 캐시는 immutable map으로 세대 교체하고 미처리 사건은 snapshot material generation을 유지한다.

### 7.3 에너지→손상 및 경도

\[
h=H/255,\qquad f_H(h)=f_{min}+(1-f_{min})(1-h)^\gamma
\]
\[
D_{raw}=F(E_{direct}, materialGroup)f_H(h),\quad
0<f_{min}\le1,\;\gamma>0,\;F(0)=0
\]

`F`는 단조·유한·비음수 함수이고 보정 계수는 설정으로 외부화한다. **연성을 `f_H`에 또 곱해 직접 피해를 이중 감소시키지 않는다.** 탄성을 직접 손상 감소 항목으로 다시 넣지 않는다. `H=255`라고 절대 무적이 되지 않되 `UNBREAKABLE` 플래그는 우선한다. 비정상 부동소수값은 0으로 바꿔 숨기기보다 `INVALID_ENERGY`로 진단하고 계획 생성을 중단한다.

### 7.4 미세 피해 양자화 — 반복 접촉 과대평가 방지

양수의 모든 손상을 `ceil`해 바로 1 damage로 만들면 미세 접촉의 반복으로 비현실적인 붕괴가 발생한다. 소수 누적이 필요할 때는 `damageU8` 밖의 **선택적 부가 1바이트 remainder**를 별도 희소 공간에 저장하거나, 손상 누적 이외의 동등하고 결정론적인 정책을 사용한다.

```text
fixedPoint = damageU8 * scale + remainder
increment = floor(max(0, damageFloat) * scale)
next = min(255*scale + (scale-1), fixedPoint + increment)
damageU8 = floor(next / scale)
remainder = next % scale
```

`scale=256`은 구현 후보이지 사용자 합의된 정책 상수가 아니다. **세 상태 바이트 외에 1바이트를 추가 허용할지**는 P-04 사용자 결정 항목이다. `crackVisual`을 remainder와 같은 byte에 중복 인코딩하여 두 값의 의미를 훼손하지 않는다. 작은 피해 보존을 위해 월드 전체에 별도 상태를 할당할 필요는 없다.

### 7.5 연성 및 영구 변형

`Ductility`는 손상량 감소가 아니라 `plasticCapacity`, `yieldThreshold`, 허용 `DeformationIntent` 수치에 반영한다. 경도 저항을 먼저 계산하고 다른 예산 항목과 혼합하지 않는다.

```text
capacity = ductilityToPlasticCapacity(D, materialGroup)
plasticDemand = plasticWork(E_plastic, yieldProfile, collisionDirection)
if movable && plasticDemand>0 && oldDeformation < capacity:
    plan safe-grid DeformationIntent + state update
else:
    apply configured blocked-deformation fallback (state-only/absorbed/damage)
```

**실제 변형은 상태 숫자 증가만으로 완료되지 않는다.** PDD 1.0.0은 서버 world/plot에서 **실제 위치 또는 명시적 blockstate 변화를 일으키는 안전한 적어도 하나의 지원 유형**을 기능 합격 대상으로 한다. 일반 블록은 격자에 속하므로 자유 곡면/0.1칸 연속 휨을 요구하지 않는다. 이동할 공간이 없는 경우, BE/운동 부품/접착 연결을 무사히 이전할 수 없는 경우에는 `MOVE_UNSAFE` 및 POLICY에서 정한 fallback을 따른다. 구조물 BE를 소실시키고 '변형 성공'으로 기록하지 않는다.

### 7.6 파괴 조건

`damageU8 >= failureThreshold`이고 `UNBREAKABLE/IMMUNE/VETO`가 없으며 해당 좌표의 fingerprint가 일치하면 `BreakIntent`로 승격한다. 파괴 임계값은 H와 **별도 정책 변수**이다. 기본 월드와 plot의 파괴/드롭 소유자를 분리하고, 원본 폭발/사용자 채굴/다른 모드와 경쟁할 때는 동일 fingerprint당 **최대 1회 실제 파괴/드롭**이 허용된다. 보호 대상이 전파 그래프의 통로라는 이유만으로 보호 무시나 우회 파괴를 하지 않는다.

## 8. 주변 응력·구조물 연결성·파편

- 초기 그래프는 `BlockTarget`의 **동일 공간 내부 6면 공유 이웃**을 사용한다. `STATIC_WORLD`와 `SABLE_PLOT` 좌표를 단순 정수 덧셈으로 직접 이어 붙이지 않는다.
- `StressGraphProvider`는 장래 접착제·엔진 부품·기계 축·연결 구조를 해석할 수 있는 확장 경계다. PDD 1.0에서 모든 Create 기계 연결 규칙을 새로운 자체 물리 엔진으로 재구현하지 않는다.
- 노드 i의 남은 예산 `Q_i`, 탄성 `e_i = E_i/100`라면 전파 가능 상한은 `Q_i (1-e_i)`. 이웃 가중치 `w_ij >=0, Σ_j w_ij <=1`로 분배한다. 각 노드의 감쇠/국소 소모는 **입력 budget에서 먼저 debit**한다.
- 같은 블록이 여러 경로에서 도달해도 총 유입만 정확히 합산한다. 단순 `visited` bool로 둘째 경로의 에너지를 소실하거나 첫 경로에 예산을 중복 재지급하면 안 된다.
- 최대 깊이, 최대 방문 블록 수, 최소 전파량, per-episode 및 per-tick budget을 적용한다. limit 도달 시 `ABSORBED_BY_POLICY` 또는 **추적 가능한 deferred work**로 마감한다. 수치 정책은 설정에 둔다.
- 구조물 블록 파괴 후 **Sable의 정상 collider rebuild/heatmap/split 경로**를 이용한다. PDD 임의로 `removeSubLevel()`을 호출해 구조물 전체를 삭제하거나 별도의 파편 물리계를 생성하는 것은 금지한다.
- 파편이 분리/합쳐질 때 기존 손상 상태는 해당 **실제 블록의 새 소유 plot/좌표**로 이전 또는 안전 보류한다. Sable split lineage와 데이터 소유권을 기록하고, 떨어진 파편 둘이 함께 정적으로 보인다는 이유만으로 서로 glue 상태가 되었다고 처리하지 않는다.
- 성능을 위해 대형 함선 전체 바운딩 박스를 매 틱 체적 전수 조사하지 않는다. 충돌 지점 주변 제한 영역과 Sable 공식 분리 이벤트에 반응한다.

## 9. 서버 커밋·상태 저장·시각화

### 9.1 엄격한 처리 단계

```text
Sable physics callback (관측만)
  -> lock-free / bounded snapshot intake (source+time+body key)
  -> post-physics phase: episode finalize & read-only reconciliation
  -> server authority scheduler (not stepping; correct level/plot)
  -> validate target fingerprint, owner, loaded chunk, permissions, BE
  -> compute/apply DamageIntent, BreakIntent, DeformationIntent
  -> record dirty state, invalidate collider/mass/split snapshot
  -> reconcile Sable callbacks/collider/split completion
  -> persistent state flush + client tracking delta
```

`ForgeSablePostPhysicsTickEvent`는 물리 후 처리 **요청을 등록하는 시점**이지, 그 이벤트 안에서 모든 `destroyBlock`이 항상 안전하다는 보증이 아니다. `SubLevelPhysicsSystem.getCurrentlySteppingSystem()` 확인, 스레드/서브레벨 상태 확인 및 실제 코드의 안전한 변경 시점 검증을 전제로 한다.

`MutationTransaction`은 단계 `PREPARED → VALIDATED → APPLYING → APPLIED/ABORTED → RECONCILED`를 가진다. 승인된 `BreakIntent`를 무한정 버리지 않고 TTL, retry, snapshot refresh, 대기 보존 여부를 명시한다. 수정을 수행한 후에도 소유 바디의 `massEpoch`와 collider/split 갱신 전엔 **새 사건에 옛 질량을 재사용하지 않는다**.

### 9.2 정적 세계와 plot은 서로 다른 mutation adapter

`WorldBlockAdapter`는 Minecraft 서버 레벨의 실제 블록 변경 계약을 사용한다. `PlotBlockAdapter`는 Sable plot의 고유 block accessor/수정·갱신 경로를 사용한다. 외부 world pos로 변환한 뒤 plot 내부 블록을 월드에서 `destroyBlock`하는 오류를 막는다. **동일 XYZ라도 targetSpace/owner가 다르면 별도 타깃**이다.

commit 직전 검사:

1. 올바른 `ServerLevel`/plot/stable owner가 아직 존재하는가?
2. 대상 청크가 이미 로드되어 있는가? **가상 충돌 때문에 청크를 강제 로드하지 않는다.**
3. `BlockState`와 BE fingerprint가 스냅샷과 같은가?
4. protection/claims/개별 모드 veto/불괴 정책이 허용하는가?
5. 목적 좌표(변형인 경우)와 기존 유체·중력·글루·기계 연결·BE 데이터를 안전하게 보존할 수 있는가?
6. 이번 원인으로 이미 파괴됐거나 다른 원인으로 변경됐는가?
7. Sable stepping/split/탈착 등 안전하지 않은 생명주기 중인가?

실패 시 **손상이 적용되었다는 성공 로그를 출력하지 않는다**. `ABORT_*` 사유를 기록하고 계획의 누적 상태와 실제 변경이 모순되지 않도록 업데이트를 롤백/지연한다. 다중 블록 변경은 NeoForge/Sable가 원자성을 자동 보장한다고 주장하지 않는다. 복구 가능한 변경 집합·전역 순서·compensation 기록 또는 사전 veto로 구현한다.

### 9.3 희소 상태 스키마와 영속성

```text
DamageStorageRecordV1 {
 schemaVersion = 1; targetSpace; dimension;
 stablePlotKey?; localBlockPos; blockFingerprint;
 damage: u8; deformation: u8; crackVisual: u8;
 optionalFixedPointRemainder: u8?; materialGeneration;
 lastUpdateTick; ownerEpoch; persistedChecksum?;
}
```

- **상태가 0인 일반 블록은 별도 개체/레코드를 저장하지 않는다.** 좌표별 key는 chunk/plot 파티션으로 관리한다.
- 1바이트 논리 수치는 unsigned read/write(`&0xFF`)와 값 검증을 사용한다. `pack24`는 계산/직렬화 최적화일 뿐 JVM 개체의 정확한 메모리 용량 보증이 아니다.
- 정적 world는 청크/차원 수명과 맞는 저장소; plot은 **Sable 구조물 수명·플롯 이동/분리/합병/서버 재시작**에 맞는 저장소를 사용한다. 런타임 `getRuntimeId` 단독으로 plot 손상값을 영구 식별하면 안 된다.
- 블록 교체 시 fingerprint 불일치 상태는 stale로 제거한다. 이동/분리 시 실제 블록 이동과 상태 키의 재귀속을 같은 transaction 또는 복구 로그로 연결한다.
- 삭제·월드 언로드·서버 종료에서 중복 작업/파일 손상을 방지하고, 저장/복원 테스트를 통과해야 한다.
- 향후 DVT 3.0의 강화·습윤·부식·질량 동적 상태는 **PDD 손상 3바이트에 강제로 끼워 넣지 않는다**. 별도 상태 레이어 및 schema migration을 사용한다.

### 9.4 균열·시각화·동기화

`damage`와 `crackVisual`은 별개다. 균열 표시가 시간에 따라 사라져도 `damage`가 자동 복구되지 않는다. Minecraft 기본 균열 텍스처 활용 가능성을 우선하고, 클라이언트는 서버가 보내는 **변경분 delta**만 표시한다. 추적 중인 플레이어만 동기화하며 월드 전체 블록을 매 틱 조회하지 않는다. 차원 전환·plot 추적/언로드·reconnect에서 과거 균열이 유령 표시로 남지 않아야 한다. 클라이언트는 서버 피해의 최종 판정자가 아니다.

## 10. 폭발·투사체·엔티티 사건의 PDD 통합

- `DamageSource.VANILLA_EXPLOSION`: vanilla/기존 Aeronautics 폭발의 실제 파괴 결과를 우선 존중한다. 살아남은 블록/물리 구조물에 대한 PDD 추가 손상만 `sourceBudgetId`로 전달한다. 같은 블록의 중복 드롭·중복 파괴 금지.
- `DamageSource.PROJECTILE`: 물리적 속도·질량 정보가 신뢰 가능하면 정규화 예산으로 처리하고, 없으면 **명시적 정책 변환 함수**(`gameDamage→PddEnergyProxy`)를 사용한다. 게임의 health damage 수치를 SI 충돌 에너지로 오인하지 않는다.
- `DamageSource.ENTITY_HIT`: 투사체와 동일한 보호·인과 추적을 적용하되, 모든 일반 생물의 피격이 자동으로 물리 구조물 전체 충격이라는 가정을 금한다.
- 위 source는 각자 이벤트 ID를 가진다. `DamageSource.SABLE_COLLISION`의 동일 사건이 폭발 이벤트를 추가 발생시킨 경우에는 인과 연쇄 ID를 연결하여 예산 소유권을 분리한다.
- **PDD 1.0 범위:** 기본 폭발 잔여 손상, 일반 투사체/엔티티의 블록 피해 통합. **DVT 2.0 범위:** 작동 중 연료기관 충격→폭발, 함선 전체 질량에 따른 *별도* damage amplifier. 엔진 작동 판정과 추가 에너지 생성은 PDD 1.0에서 구현 금지.

## 11. 처리량·큐·서버 성능 계약

1. 물성은 block ID별 immutable registry로 미리 계산. collision hot path에 레시피·전 블록 레지스트리 검색 금지.
2. 입구 큐는 물리 콜백의 **low allocation bounded queue**로 구성. 초과 시 누락 건수·source·원인·처리할 수 없는 사건을 노출하고, 누락이 발생한 피해를 상상으로 채우지 않는다.
3. 손상/파괴 변경 큐는 **유한 work budget**으로 분할. 이미 승인된 파괴 intent는 안전한 지속 큐 또는 명시적 규칙으로 보류·만료·기록하며 조용히 버리지 않는다.
4. 동일 사건 중복 제거, 접점 영역·깊이·노드수 상한, per-tick CPU 작업량, 보호된/미적재 청크 접근 차단을 적용한다.
5. 대형 함선 전체 바운딩 박스 매 틱 체적 스캔 금지. PDD 자체 파편 생성/추가 비행 강체 복제 금지.
6. latency/TPS/메모리 측정 없이 고정 성능 향상률을 주장하지 않는다. 일정한 충돌량을 가진 테스트 시나리오별 p50/p95/p99 처리 비용, 큐 high water, 드롭·보류·재시도, 블록 변경 지연, GC를 측정한다.
7. 사용자용 옵션은 *효과를 없애는 수단*이 아니라 안전한 throughput/품질 프로필. 빠른 모드가 손상 에너지 보존·드롭 유일성 같은 불변조건을 깨서는 안 된다.
8. 옵티마이저 ON/OFF 환경 모두 모드 로딩은 가능해야 하지만, 기본 ON에서 원본 물리 동역학과 100% 동일한 접촉 개수/TPS를 보장하는 것은 PDD 권한 밖이다.

## 12. DVT 지향 확장 인터페이스 — 구현은 미래에

```java
// PDD 1.0에서 보존할 인터페이스 의미를 표현한 의사 코드. 실제 외부 API 아님.
interface DamageSourceAdapter { List<DamageEvent> capture(Context context); }
interface EnergyProvider       { EnergyBudget budget(DamageEvent event, Snapshot snap); }
interface MaterialResolver     { MaterialProfile resolve(BlockTarget t, MaterialContext c); }
interface DamagePlanner       { DamagePlan plan(EnergyBudget e, MaterialProfile m, Snapshot s); }
interface MutationCommitter   { CommitResult commit(DamagePlan p, ServerCommitContext c); }
interface StressGraphProvider { GraphSnapshot neighbors(BlockTarget target, Snapshot s); }
interface MaterialModifier    { MaterialDelta modify(MaterialProfile base, MaterialContext c); }
```

- **DVT 1.0 Fracture:** PDD 1.0의 모든 충돌·손상·응력·파괴·변형·저장·시각 기능을 포함한다. *PDD를 중복 재구현하지 않는다*. 직접 통합/공통 라이브러리/의존 모드 방식을 **통합 단계에서** 의존성·저장 이전·모드 ID/호환성 기준으로 결정한다.
- **DVT 2.0 Big Bang:** `EngineFailureSource`, `ShipMassContext`, `ExplosionBudgetProvider` 등 확장 어댑터를 추가. 연료 기관 폭발 조건/전체 선체 질량 보정식/대규모 연쇄 반응은 DVT 2.0 설계 시 사용자 결정. Sable 실제 mass를 게임 피해 증폭을 위해 무단 수정하지 않는다.
- **DVT 3.0 Reinforce:** 아이템 범위 선택/고글·UI, 사용자 물성 변경, 수분·부식 등의 상태별 물성, **질량 변화**까지 DVT의 `MaterialModifier`/별도 mass update adapter로 연결. 물성 우선순위는 DVT 3.0 정책으로 미리 확정하지 않는다.
- PDD 1.0은 위의 *확장 경계*를 테스트하지만 미래 기능의 가짜 구현, 빈 UI, 연료기관 폭발 초기 코드를 선행 추가하지 않는다.

### 12.1 향후 호환성을 위해 지금 지켜야 할 세 가지 결정

- `DamageSource`와 `MaterialContext`에 **유효시간·대상·원인 ID**를 포함해 별도 사건/동적 환경을 합성할 수 있게 한다.
- `MaterialDefinition`(블록 종류별 정적 base)과 `DamageState`(좌표별 누적 변화)는 분리하고, **물성 오버라이드 저장**은 DVT 레이어가 소유하게 한다.
- `Committer`는 Sable 구조물과 정적 레벨의 여러 사건을 하나의 손상 엔진으로 처리하되 물리 생명주기의 소유권은 Sable에 남겨 둔다.

## 13. 사용자 승인 없이 확정하지 않는 정책과 기본 안전 처리

아래는 API 추적 문제가 아니라 **게임 디자인 결정**이다. 구현 과정에서 개발자가 임시 값으로 테스트할 수는 있지만, 이를 사용자 승인된 밸런스/기능의 영구 규칙이라고 주장하지 않는다. 사용자가 별도 값을 지정하지 않은 경우는 설정 가능한 **명시적 프로토타입 프로필**을 사용하고 모든 값을 로그·설정 문서에서 노출한다. 데이터 파손 위험이 있는 정책 미결정은 보수적 abort.

| 정책 ID | 선택 사항 | 기술적으로 강제할 범위 | 미결정 시 안전 처리 |
|---|---|---|---|
| `P-01` | 에너지→손상 변환 척도, 최소 충격 | 유한/비음수, `F(0)=0`, 단조 함수 | 명시적 테스트 프로필/보수 임계 |
| `P-02` | 경도 함수 `fMin`,`gamma`, 재료별 곡선 | `fMin` 양수, `gamma>0` | 코드 외부 설정 가능한 후보값 |
| `P-03` | 파괴 임계값/불괴 예외/드롭 정책 | 1..255, claims/BE veto | 불괴·보호 우선 |
| `P-04` | 3상태 byte 외 잔여손상 1byte 허용 여부 | 저장 스키마·재현성 보장 | 소수값을 숨겨 손상 1씩 부풀리지 않음 |
| `P-05` | 연성→변형 용량·방향·실제 격자 이동 방식 | BE/기계/글루 안전, 유한 상태 | 안전 블록만 제한 이동; 위험 블록 ABORT |
| `P-06` | 목적 공간 없음/변형 실패 시 처리 | 실패를 '이동 성공'으로 표기 금지 | state-only/abort; 직접 파괴로 임의 전환 금지 |
| `P-07` | 충돌 A:B 피해 분배 비율 | 합계≤1, 원장 추적 | 명시적 설정, 예산 미초과 |
| `P-08` | 응력 전달 깊이·노드·전파 감쇠 | 유한 work budget, 손상/에너지 단위 혼합 금지 | 안전 제한·명시적 잔여 기록 |
| `P-09` | 균열 시각 회복/실제 회복 여부 | crack과 damage 분리 | crack 시각만 fade, damage 유지 |
| `P-10` | 투사체/엔티티 fallback 보정계수 | 게임 피해·물리 에너지 구별 | 물리량 부재는 저신뢰 이벤트로 표시 |
| `P-11` | 서버 큐 예산·성능 목표 TPS | 제한된 작업량, 조용한 드롭 금지 | 보수 예산과 계측 |
| `P-12` | DVT 2.0 기관 폭발/전체 질량 추가 보정 | PDD 1.0 기본 기능과 중복 금지 | PDD 구현 범위 제외 |
| `P-13` | DVT 3.0 상태/사용자 설정 우선순위·질량 변조 | 별도 변경/복원 계약 | PDD 구현 범위 제외 |

**사용자에게 되묻지 않아야 하는 개발자 책임:** Sable API 확인, JVM descriptor, 스레드 안정성, 정상/plot 좌표 변환, 중복 방지, 영속성, Mixin 충돌 탐지, 메시지 직렬화, 단위 테스트, CI, 원본 물리 응답 보존. `P-xx`와 무관한 구현 문제를 사용자 취향 결정으로 돌리지 않는다.

## 14. **고정 개발 로드맵: 0.2.12 → PDD 1.0.0**

**로드맵은 버전별 완료 계약이며, 버전별 '진단 또는 재검증 작업'의 무한 반복을 막는 개발 계획이다.** 각 버전은 하나 이상의 실질 신규 기능이 있어야 한다. 로드맵 버전 번호는 이 문서에서 **앞으로의 PDD 개발 산출물 이름으로 지정**하며, GitHub에 이미 다른 번호가 사용되었다면 *덮어쓰지 않고* 기능 ID와 가장 가까운 실제 개발 버전을 매핑한 장부를 작성한다. 버전 완료 여부를 본 설계 문서 안에 표기하지 않는다.

**모든 단계의 공통 Definition of Done (DoD):** `(a) 신규 소스/테스트 변경 존재, (b) 실물 JAR 빌드 또는 미실행 사유의 명시, (c) 단계별 적어도 1개의 기능 acceptance test, (d) 이전 단계의 변경 영향 회귀, (e) 기능-코드-증거 매핑, (f) 소스 ZIP/Actions 워크플로와 배포 일치, (g) 원본 Sable 응답 보존과 월드 안전 불변식 준수`. 무관한 구 게이트 전체를 처음부터 반복하지 않는다.

### 14.1 릴리스 순서·의존성·기능 완료 매트릭스

| 예정 버전 | 고정 신규 목표 | 필수 산출물 | 합격 게이트 | 다음 단계 해금 |
|---|---|---|---|---|
| **0.2.12** | **일반 블록 포함 충돌 수집** | 원본 보존 callback decorator, original-null 분기, 필수 주입/카운터, 실제 episode 입력용 raw DTO | `G-COL-01` 일반 블록·Fragile·Belt 각 관측; 원본 응답 비회귀 | 충돌값 정규화 |
| **0.2.13** | **소유 공간·좌표·시간 정규화** | world/plot owner mapping, frame-stamped snapshot, pose epoch, pre-solver 샘플 | `G-COL-02` 지형/plot/ship/ship + 회전 충돌 좌표·속도 정합 | 질량 입력 |
| **0.3.0** | **질량·회전 기반 충격 산출** | Sable MassData/Handle adapter, contact-effective-mass, valid/invalid quality flags | `G-MATH-01` 정적/양측/회전/무효 입력의 결정론·실게임 입력 연결 | 예산 원장 |
| **0.3.1** | **중복 episode 통합** | pair+step+cluster key, 중복 제거, 사건 상태기계, energy ledger, raw telemetry correlation | `G-EP-01` 양측·다중 접점·정지접촉의 에너지 중복 0 | 손상 계산 |
| **0.4.0** | **재료별 직접 손상·누적값 산출** | MaterialDefinition/threshold, pure `DamagePlanner`, hardness 독립성, fractional policy abstraction | `G-DMG-01` H/D/E 역할 불변성, byte·threshold, mass-speed 단조성 | 정적 파괴 |
| **0.4.1** | **정적 세계 최초 파괴 적용** | WorldBlockAdapter, safe server mutation queue, fingerprint CAS, drops exactly once | `G-WORLD-01` 실제 지형 충돌→손상 누적→파괴, 원본 드롭 무중복 | 저장 |
| **0.5.0** | **정적 세계 희소 영속화** | chunk/dimension DamageStateStore, schema migration/fingerprint stale cleanup, 저장·로드 | `G-SAVE-01` 저장/재시작/교체/언로드 후 손상 일치 | plot 상태 |
| **0.5.1** | **물리 구조물 양측 피해·plot 반영** | PlotBlockAdapter, 양측 피해 분할, collider/mass invalidation, plot state owner | `G-PLOT-01` ship↔terrain·ship↔ship 양측 피해 및 Sable 충돌기 갱신 | 응력 |
| **0.6.0** | **이웃 응력 전파** | bounded 6-neighbor/graph, energy debit, aggregate contributions, per-tick work budget | `G-STRESS-01` 다중 경로 합산·순환 종료·예산 보존, 실제 연쇄 손상 | 분리 |
| **0.6.1** | **파단·분리·재조립 상태 일관성** | Sable split/mass/collider reconciliation, plot remap/persistence, glue/part lineage safeguards | `G-SPLIT-01` 반파·파편/휴면·재조립에서 loss/ghost/double damage 0 | 변형 |
| **0.7.0** | **실제 안전 격자 변형** | PlasticityPlanner, DeformationIntent, safe movable whitelist, ABORT/rollback, BE 보호 | `G-DEFORM-01` 실제 위치·blockstate 변화 + state 보존, 불가 타깃 원자적 실패 | 구조물 변형 |
| **0.7.1** | **world/plot 변형 통합** | PlotDeformationAdapter, 연결부/기계/BE 예외, deformation persistence | `G-DEFORM-02` 구조물 내 실제 변형 및 물리/저장 정합, 원본 운동 보존 | 시각화 |
| **0.8.0** | **균열 표시 및 클라이언트 동기화** | crack state renderer, delta packet, tracking cleanup, visual recovery independent of damage | `G-VIS-01` 다중 클라이언트·재접속·서브레벨에서 균열 정합 | 외부 피해 |
| **0.8.1** | **기본 폭발/투사체/엔티티 피해** | Explosion/Projectile/Entity adapters, source correlation, vanilla outcome protection | `G-EXT-01` 기존 폭발 동작 보존·잔여 손상·중복 드롭 0 | 호환성 |
| **0.9.0** | **성능·옵셔널 모드 호환성** | Sable Optimizer optional adapter, saturation guard, profiling/metrics, Mixin conflict matrix | `G-PERF-01` optimizer 미설치/보수 설정/활성화 지원 범위 표기 + 대형 충돌 측정 | 릴리스 후보 |
| **0.9.1** | **전체 기능 통합·배포 준비** | ABI pin/test, 사용자 정책 구성/문서, 버전 마이그레이션, regression matrix, DVT SPI tests | `G-RC-01` 필수 1.0 acceptance test 전부, blockers 0, 재현 가능한 빌드 | 정식 릴리스 |
| **1.0.0** | **Fracture 안정 릴리스** | 소스/JAR/tag, 사용법·설정·API 문서, 변환·복원/서버 부하 결과, DVT extension contract | `G-REL-01` 0.9.1 전항 유지 + 최종 인게임·재배포/라이선스·버전 호환성 확인 | DVT 1.0 개발 |

**버전 고정 원칙:** 위 순서의 각 기능이 이전 릴리스에 이미 완전하게 있다면 해당 단계에 `SATISFIED_BY` + 커밋/증거를 등록하고 다음 **미충족** 단계로 진행한다. 기존 버전의 완료 여부를 추측해서 재구현하지 않는다. 필요하면 `0.x.y+1` patch hotfix를 발행할 수 있으나 고정 계획의 다음 마일스톤 신규 목표를 지우거나 검증판으로 대체하지 않는다.

### 14.2 각 버전 개발 범위의 구체적인 실행 계약

#### `0.2.12` — 관측을 끝내고 **범용 수집기를 제품 기능으로 연결**

**개발:** `RapierVoxelColliderBakery.buildPhysicsDataForBlock`에서 *원본 callback 반환값을 받아* decorator를 삽입한다. 원본 callback은 정확히 한 번 호출하고 반환 객체를 보존한다. 일반 비공기 블록의 null callback에 observer를 붙일 때 발생하는 JNI 분기 변경 가능성을 별도 회귀 검증한다. Sable effects feed는 진단 메타데이터로만 유지한다. `RawObservation`에 원인/소유자/타임스탬프를 붙이고 overflow 카운터를 갖춘다.

**판정:** 보통 돌/목재 ↔ 선체 접촉이 실제로 수집되고, Fragile/Belt/TNT 기존 callback 동작이 원본과 같아야 한다. **관측 로그만 출력하는 반복 릴리스는 불합격**이며 후속 norm 단계가 소비할 자료형/접근 경로가 코드로 존재해야 한다. 주입 실패는 fail-fast.

#### `0.2.13` — 좌표계와 물리 시점에 이름을 붙이는 정규화

**개발:** `BlockTarget` space/plot/차원 분기, transform snapshot, 원본 frame 라벨/validity 플래그, pre/post 스냅샷 구별. 콜백의 `hit`, `other`, `impactPosition`, `signedVelocity`는 API 및 실제 호출 시점에서 해석하되 임의 m/s 변환 금지. 인게임 접촉점을 서버 world에 debug marker/structured log로 대응시킨다.

**판정:** 정적 세계, plot-world, plot-plot, 회전 경계에서 올바른 타깃을 찾고, 잘못된 plot/malformed 입력은 안전 거절한다. *미확정 frame 필드로 피해를 처리하지 않는다.* 검증 결과는 `G-COL-02` 장부에 남기고 다시 조사하지 않는다.

#### `0.3.0` — 충격 강도 산출 구현

**개발:** 같은 시각의 Sable `MassData`/rigidbody pose·속도에서 정상 접근 상대속도·effective mass·episode energy 후보를 계산한다. 정적 지형의 역질량 0, 법선 정규화·회전 관성의 정확한 처리를 구현한다.

**판정:** 정지/스침/접근/반발/회전 속도 변화에 대한 단위시험, 실측 입력 산출 경로, NaN/0분모 거절. 초기 수치는 tuning profile로 분리하되 속도·질량 민감도 자체는 동작해야 한다.

#### `0.3.1` — 중복 충돌 회계 완성

**개발:** `EpisodeAssembler`와 per-episode budget ledger. 양측 callback/15-double 보조 피드 상관관계, 동일 물리 서브스텝/지속 접촉의 누적 방지, 여러 접점 정규화 배분.

**판정:** 콜백이 2배로 기록돼도 같은 episode의 총 허가 에너지가 증가하지 않는다. 모든 소모/흡수/잔여 budget은 계정별로 추적 가능. 동일 사건에 두 월드 mutation intent가 중복 생성되지 않는다.

#### `0.4.0` — 경도/연성/탄성의 실제 계산 경계

**개발:** 재료 캐시에서 `failureThreshold`/flags/curve profiles를 제공하고, 사건 에너지의 할당량→손상/변형 후보를 산출한다. runtime per-block 상태 byte/잔여 정책 분리. 마이크로 충돌의 `ceil` 인위 피해 증폭 제거.

**판정:** H 증가와 직접 손상 비증가, D 증가와 직접 경도 손상 불변, E 증가와 이웃 전파 비증가. 한 byte의 부호 해석/파괴 threshold 테스트가 재현 가능. **다음 버전에서 직접 world 파괴에 연결할 실제 DamageIntent가 필요**.

#### `0.4.1` — 월드 안의 첫 완전한 파괴 사례

**개발:** 스냅샷 기반 `WorldBlockAdapter`, 안전 서버 단계 commit, fingerprint/veto, drop uniqueness. 한 번의 일반 구조물→돌 지형 충돌에서 누적 손상이 반영되고 설정 임계 초과 시 정적 블록이 파괴된다.

**판정:** Minecraft `ServerLevel` 파괴가 실제 실행되고 의도하지 않은 인접/다른 차원 블록은 변하지 않는다. 승인된 목표를 '로그 관측'만으로 통과 처리하지 않는다.

#### `0.5.0` — 손상된 정적 블록만 저장·복원

**개발:** 차원/청크별 sparse store, 상태 pack, `schemaVersion`, block fingerprint, dirty flushing, 교체/파괴 시 삭제, shutdown/재시작 복원.

**판정:** 같은 블록을 재시작 후 다시 충돌시키면 누적 손상이 이어지고, 블록을 교체하면 옛 손상이 전이되지 않는다. 0인 블록 저장이 전체 월드 크기와 비례하지 않는다.

#### `0.5.1` — 구조물 양측에 파괴를 실행

**개발:** Sable plot 전용 adapter, body pair 양측 에너지 분할, `MassOrSplitPending` snapshot invalidation, plot-local 상태 persistent key. 선체↔지형, 선체↔선체에서 모두 실제 손상 적용.

**판정:** plot 블록을 일반 world 좌표에서 파괴하는 사고 없이 분리/콜라이더 업데이트를 확인한다. 정적 지형과 선체의 중복 에너지 계산은 없다. 부서진 선체의 질량/충돌기가 지연 반영될 경우 명시적 pending.

#### `0.6.0` — 주변 응력의 실제 전파

**개발:** bounded BFS/graph, 엣지 가중치, 탄성, 중복 경로 합산, 손상/변형 후보 생성. 반환되지 않은 energy는 계정에서 흡수/유예를 명시한다.

**판정:** 1회 접촉→근접 블록 추가 손상으로 연결되고 그래프가 cycle여도 종료한다. 대형 충격에서 work budget 상한 유지. 일체형 구조물의 내부 연결성은 별도 Sable 원본 분리 기능을 침해하지 않는다.

#### `0.6.1` — 부서진 구조물 생명주기 보존

**개발:** plot 분할과 잔해 소유 이전, save remapping, collision/mass epoch invalidate, 휴면 물리체 고려, 이격 파편의 재조립 ID/접착 연결 보존.

**판정:** 반파 후 분리된 두 물리체의 좌표·손상·강제 로딩·원본 분리 동작 일치. 물리체를 장시간 정지했다고 즉시 '파괴된 잔해'라고 단정하지 않으며, Sable 공식 분리를 대체하지 않는다.

#### `0.7.0` — 손상 수치가 아니라 실제 변형

**개발:** 사용자 정책으로 승인된 안전 블록 타입에 한정한 격자 이동 또는 blockstate 전환, 원자적 실패/보상. 상태 이동·드롭/BE/충돌 예약 확인.

**판정:** 최소 하나의 정적 world 지원 블록이 영구 변형하고, 막힌 목적지·소유권 불일치·unsafe BE에서는 월드를 손상 없이 중단한다.

#### `0.7.1` — 물리 구조물에서도 실제 변형

**개발:** plot-local deform transaction, Sable collider invalidation과 물성/피해 상태 재귀속, 기계/블록 엔티티 예외처리.

**판정:** 물리 구조물의 변형 뒤 서버 저장·클라이언트 표시·충돌기·기존 운동 응답 일치. BE/기계의 안전 보존이 불가능한 대상은 정책에 따른 `MOVE_UNSAFE`이며 파손된 조립체로 위장하지 않는다.

#### `0.8.0` — 실제 손상 시각화

**개발:** client tracking/delta sync, world/plot crack state presentation, fade 정책, reload/재접속 cleanup.

**판정:** damage가 그대로인 상태에서도 crackVisual만 감소할 수 있고, 두 클라이언트가 동일 서버 누적 상태를 관측한다. 다른 차원/이동한 plot의 ghost crack이 없다.

#### `0.8.1` — 기존 폭발/발사체와 완전 연결

**개발:** 원본 폭발 파괴 결과 기반 잔여 블록 damage, projectile/entity fallback energy adapter, 인과/source ID와 중복 드롭 방지.

**판정:** 기존 Minecraft/ Aeronautics 폭발 메커니즘 유지 + 생존 주변부 손상. 한 블록의 드롭/commit 단일 처리. DVT 엔진 폭발 조건을 선행 추가하지 않는다.

#### `0.9.0` — 옵티마이저·경합·부하 안정화

**개발:** Optimizer optional adapter, missing-class safe bootstrap, stale mass/split guard, 원본/Optimizer OFF/ON 구분 호환성, 경쟁 모드 조합(해당 조합은 지원 여부를 명시), 서버 TPS·메모리·큐 계측.

**판정:** 옵티마이저가 **없어도** 모드 로드/손상 정상, OFF 안전 모드 회귀 통과. ON은 명시된 지원 범위만 호환 선언. 위험이 해결되지 않은 조합을 근거 없이 '지원'으로 만들지 않는다. 심각한 무한 큐/월드 오염 없음.

#### `0.9.1` — 완료 기준을 증거로 닫는 RC

**개발:** 문서의 acceptance matrix 완전 실행, source ZIP/빌드/테스트의 일치성, 모든 필수 source 상태 및 저장 형식 migration, 사용자 설정 스키마/오류 메시지 정리. DVT SPI 테스트.

**판정:** 제품 주요 장면을 갖춘 실제 게임 증거, 차단 버그 0개. 구 버전에서 통과한 미변경 수학/ABI는 누적 증거로 인수하고 새 변경분만 재검증한다. **진단 전용 RC는 불허**.

#### `1.0.0` — 릴리스

**개발:** 버전 tag와 JAR 서명/체크섬, 설치/호환 목록·설정·저장 이전 설명, 기능 데모/재현로그, DVT 1.0 Fracture 통합을 위한 공개 경계 정리.

**판정:** 필수 기능 전부가 실제 게임에서 작동하며 월드 무결성 및 멀티플레이 성능 게이트 통과. Sable/Create/Aeronautics 원본 동작 회귀. 이후 DVT 별도 개발로 이관.

### 14.3 릴리스 전환 상태기계

```text
PLANNED
  -> STARTED (selected from latest GitHub + evidence ledger)
  -> IMPLEMENTED (source includes required feature, not just logs)
  -> VERIFIED (stage acceptance criteria + targeted regression)
  -> RELEASED (artifact + source + evidence pinned)
  OR BLOCKED (specific blocker with proof and owner; NOT a replacement release)
```

- 버전이 `BLOCKED`되면 **동일 버전에서 막힌 실제 원인에 대한 수정**을 한다. 임의로 다음 버전 숫자를 붙여 같은 계측을 반복하지 않는다.
- 새 버전 후보가 이미 완료된 기능을 되풀이한다면 그 버전은 릴리스하지 않고 `SATISFIED_BY`로 처리한다.
- 개별 테스트는 `PASS`, `FAIL`, `NOT_RUN`, `BLOCKED`, `NOT_APPLICABLE`로 기록한다. `NOT_RUN`을 `PASS`로 재분류하지 않는다.
- 버그 수정 patch는 대상 불변식·회귀만 검증하고 **다음 정규 버전 목표**를 대체하지 않는다.

## 15. 테스트 계약: 어떤 사실을 어느 게이트에서 한 번 확인하는가

**원칙:** 과거 테스트의 실행 여부를 이 문서에서 판정하지 않는다. 아래는 *합격 판정 사양*이다. 해당 버전 개발자가 가능한 시험을 실행하고, 실제 결과를 GitHub 검증 장부에 등록한다. 같은 메서드·해시·입력 분류가 유지되는 동안 이전 결과를 재사용한다.

### 15.1 순수 알고리즘 및 데이터 테스트

| 시험 ID | 입력 / 장면 | 반드시 확인할 불변식 | 책임 버전 |
|---|---|---|---|
| `UT-FRAME-01` | plot 원점 이동/회전, world static | 위치·벡터·법선 별도 변환, 동일 epoch | 0.2.13 |
| `UT-FRAME-02` | NaN normal / 0 vector / 다른 step | `INVALID` 및 월드 변경 0 | 0.2.13 |
| `UT-MASS-01` | 정적 무한 질량 B | q_B=0; A의 정상 유효질량 결과 유한 | 0.3.0 |
| `UT-MASS-02` | 가동 A/B, 병진만 | 순수 병진의 환산질량과 일치 | 0.3.0 |
| `UT-MASS-03` | 회전 날개 끝 접촉 | `omega×r` 반영; 레버암 증가 시 유효질량 변화 가능 | 0.3.0 |
| `UT-SPEED-01` | 접근/이탈/스침 | 이탈 및 순수 접선의 `v_n=0` | 0.3.0 |
| `UT-EP-01` | 동일 콜백 2회·양측 reversed order | episode 예산 정확히 1개, 계획 중복 0 | 0.3.1 |
| `UT-EP-02` | 동일 충돌 접점 n→2n 증가 | 총 입력 예산 변화 없음 | 0.3.1 |
| `UT-EP-03` | 지속 접촉 N step | 신규 접근 없으면 손상 추가 없음 | 0.3.1 |
| `UT-EP-04` | 임의 사건 순서/중간 실패 | 모든 budget finite, debit≤credit | 0.3.1 |
| `UT-MAT-01` | H=0..255 / 동일 E | H 증가 시 직접 피해 비증가 | 0.4.0 |
| `UT-MAT-02` | 동일 E/H, D 변화 | 직접 경도 피해량 불변 | 0.4.0 |
| `UT-MAT-03` | 동일 incoming, 탄성 변화 | 이웃 전달량 비증가 | 0.4.0/0.6.0 |
| `UT-DMG-01` | 손상 0/1/254/255, fractional | byte 범위·포화·재현 가능한 잔여 정책 | 0.4.0 |
| `UT-DMG-02` | 경도 255 / 불괴 플래그 | 경도 단독은 무적 아님, 불괴는 보호 | 0.4.0 |
| `UT-MUT-01` | 동일 블록에 충돌+폭발 | 최대 1회 드롭/파괴 | 0.4.1/0.8.1 |
| `UT-MUT-02` | 블록 교체/청크 언로드/BE veto | stale intent는 안전한 abort, ghost damage 없음 | 0.4.1 |
| `UT-SAVE-01` | sparse state 0/255, long session | `pack→unpack` 동치, 깨끗한 블록 0개 | 0.5.0 |
| `UT-SAVE-02` | server restart, plot split | 올바른 owner/좌표로 복원, 오래된 ID가 새 구조물에 적용 안 됨 | 0.5.0/0.6.1 |
| `UT-STRESS-01` | 사이클·여러 유입 경로 | 종료, 정확한 합산, 총예산 미초과 | 0.6.0 |
| `UT-STRESS-02` | 깊이/노드 상한 초과 | 작업 상한 유지, 유예/흡수 출처 기록 | 0.6.0 |
| `UT-DEF-01` | 격자 이동 가능/충돌/BE unsafe | 성공 시 상태 동반 이전, 실패 시 world 원상 | 0.7.0 |
| `UT-NET-01` | 플레이어 재접속/plot 이동 | delta 순서/ghost crack 방지 | 0.8.0 |
| `UT-OPT-01` | Optimizer 없음 | 옵셔널 클래스 linkage 실패 0 | 0.9.0 |

### 15.2 실제 인게임 통합 시나리오

| 시나리오 ID | 실제 장면 | 합격의 증거 / 금지된 오판 | 연결 버전 |
|---|---|---|---|
| `IG-01` | 보통 돌/목재 블록 함선이 정적 돌 지형에 충돌 | 콜백+정규화+피해+실제 파괴까지 목적에 맞는 단계의 원본 로그; INIT만으로 불합격 | 0.2.12→0.4.1 |
| `IG-02` | 대형 함선 두 개가 직접 충돌 | 구조물 두 ID·양측 타깃·동일 에너지 원장·실제 양측 피해 | 0.5.1 |
| `IG-03` | 구조물 회전 날개 끝 충돌 | `omega`, 접촉점 `r`, normal 접근 speed가 산출 결과를 바꿈 | 0.3.0 |
| `IG-04` | 장시간 정지 접촉/마찰 접촉 | 반복 callback은 측정되나 비정상 누적 damage 없음 | 0.3.1 |
| `IG-05` | TNT·취약 블록·벨트·특수 콜백 | 원본 Sable callback 결과/기존 기능 동일; PDD 중복 event 금지 | 0.2.12→1.0.0 회귀 |
| `IG-06` | 1블록 교체 후 이전 좌표 재충돌 | 옛 손상 stale 상태가 새로운 블록에 전이되지 않음 | 0.5.0 |
| `IG-07` | ship split, 잔해, 다시 조립 | plot state·glue/owner·collider·질량이 일치, 물리체를 임의 삭제하지 않음 | 0.6.1 |
| `IG-08` | 낮은 연성 vs 높은 연성, 변형 가능 지형/구조물 | 실제 격자/블록 상태 변형, 바이트만 변화한 것을 성공으로 오인하지 않음 | 0.7.0/0.7.1 |
| `IG-09` | 여러 플레이어가 같은 선체 손상 관찰 | 동일 균열, 재접속/거리/차원 변경 후 정합 | 0.8.0 |
| `IG-10` | TNT/폭발 인접 구조물과 화살/대포 발사체 | 원본 폭발/투사체 반응 유지, 남은 블록 손상, 드롭 중복 0 | 0.8.1 |
| `IG-11` | 빠른 선체의 지형 충돌 | Sable 기존 터널링 방지/CCD 사용, PDD 별도 CCD 없이 정확한 손상 | 0.9.1 |
| `IG-12` | 옵티마이저 설치 없음·설치/OFF·설치/ON | 지원된 조합에서 크래시/손상 누락 없음; 원본과 물리 동일성 무조건 요구 금지 | 0.9.0 |
| `IG-13` | 많은 대형 구조물·10명 이상 참여 서버 부하 | TPS/메모리/수집 드롭·보류/큐 상한의 정량 기록, 무한 작업 없음 | 0.9.0→1.0.0 |
| `IG-14` | 세계 저장/재시작/청크 언로드/재적재 | sparse 손상·변형/plot 소유 정보 보존 | 0.5.0→0.9.1 |
| `IG-15` | 원본 Create/Aeronautics 기능만 수행(충돌 피해 없음) | 회전·동력·접착·조립·분리·기존 충돌 응답 비회귀 | 1.0.0 전체 |

**시험 결과 기록에 필요한 문맥:** 정확한 모드 조합, 서버/클라이언트 버전, Sable/Native 해시, 사용자 정책, 충돌 구조물 구성·재현 단계, 서버 시간/physics step, 원본 로그의 사건/커밋 ID, 전후 블록 상태·시각 자료, CI run 및 결과. '게임에 들어갔다'는 것만으로 기능 합격을 선언하지 않는다.

### 15.3 모든 버전 공통 실시간 불변성 경고

- `HOOK_APPLIED`: 주입 지점 적용의 증거. **실제 충돌 수집을 의미하지 않는다.**
- `RAW_OBSERVATION`: 엔진에서 신호를 받음. **손상/파괴가 성공했다는 뜻이 아니다.**
- `EPISODE_ACCOUNTED`: 정상화/중복 제거·예산 승인 완료. **서버 커밋과 구별**.
- `MUTATION_COMMITTED`: fingerprint/owner 검사를 통과하여 실제 world/plot 변경됨.
- `RECONCILED`: collider/mass/split 데이터가 새 블록 상태를 반영함.
- `PERSISTED`: 저장 상태/재시작 불변식으로 입증 가능.
- `CLIENT_SYNCED`: 추적 클라이언트가 delta를 받아 올바르게 표시.

각 단계 카운터는 합산/실패 사유를 분리한다. 별도 `SELFTEST` 또는 인공 생성 사건은 `LIVE_DATA`로 표기 금지.

## 16. GitHub·모바일 배포를 위한 강제 개발 프로토콜

### 16.1 개발 시작 시 1회 확인

1. 연결된 GitHub 저장소의 **현재 기본 브랜치 최신 SHA**, `build.gradle`, `META-INF/neoforge.mods.toml`, `.github/workflows`, `src/` 전체와 `docs/verification/`을 확인한다.
2. 본 통합 설계서에서 다음 미충족 로드맵 기능 ID를 선택한다. 그 기능을 구현할 실제 소스 경로/타깃 API/필요 시험만 찾는다.
3. 실제 대상 JAR 해시가 고정 기준과 같은지 확인한다. 같으면 **기존 ABI 결과를 그대로 인수**한다. 다르면 변경된 부분만 reverse/compile 확인한다.
4. 이전 버전에서 이미 구현된 모듈은 수정 이득/호환 위험이 없으면 그대로 사용한다. 재구현은 실측 결함 또는 아키텍처 충돌 근거가 있는 경우에만 시행한다.
5. 요구 기능의 `ON/OFF`, 상세 로그 수준, 최초 실행 실패 여부, 월드 변경 허용 범위를 확정한다. 이번 버전의 목적을 **기본 OFF로 숨기지 않는다**.

### 16.2 한 버전의 Source ZIP 계약

```text
PDD-<version>/
  src/main/java/...            # 실행에 필요한 전체 신규·기존 소스
  src/main/resources/...       # mods.toml, mixin config, assets, lang 등
  src/test/java/...            # 새 목표 및 변경 영향 회귀 테스트
  build.gradle
  gradle.properties
  settings.gradle
  docs/verification/<version>.yaml or .md
  PDD-<version>-RELEASE-NOTES.md
```

저장소의 보호된 `.github/`, `gradlew`, Gradle wrapper는 기존 Apply ZIP 워크플로를 기준으로 보존한다. 기존 ZIP 적용 방식이 변경되면 **당시 실제 GitHub 워크플로가 우선**이다. GitHub Actions에서 Gradle 테스트·JAR 빌드·산출물 업로드·실제 JAR 파일명과 버전 정합을 확인한다. 로컬 PC의 CLI/IDE 사용을 전제로 안내하지 않는다.

### 16.3 버전별 완료 기록 템플릿

```markdown
# PDD <version> — 구현 및 검증 장부
- Parent commit: <sha>
- New commit: <sha>
- Planned milestone: <0.x.y / function ID>
- New user-visible functionality: <what actually happens>
- Source changed: <paths+methods>
- Dependencies and exact JAR hashes: <...>
- Acceptance tests: <IDs and PASS/FAIL/NOT_RUN with linked raw evidence>
- Inherited prior gates (not rerun): <IDs, commit and why unaffected>
- Changed-path regression: <IDs and outcome>
- Build run / artifact path / sha256: <...>
- Active flags and rejected paths: <...>
- Remaining blocker (only relevant to next target): <...>
- Next fixed milestone: <version>
```

### 16.4 금지되는 개발 패턴

- 새 릴리스인데 실제로는 이전 버전의 API 진단 코드만 재컴파일했다.
- GitHub 소스가 이미 구현한 기능을 과거 초안의 `미구현` 문구 때문에 새로 작성했다.
- 포괄성 미검증 native effects array를 모두 충돌한 일반 블록의 타깃이라고 단정해 파괴했다.
- `require=0`, `Boolean.getBoolean()` 등으로 핵심 관측/게임 기능을 조용히 OFF 처리했다.
- 소스 ZIP을 만드는 데 성공했다는 이유로 실제 Gradle/JAR/인게임 결과를 통과라고 작성했다.
- C++/Rust 네이티브 충돌 소스 추정만으로 업로드 바이너리 내부 구현이 똑같다고 판정했다.
- 다른 라이선스의 애드온 바이트코드/소스 전체를 그대로 포함한 채 라이선스 검토를 건너뛰었다.

## 17. 실제 충돌·위험 처리 시 의사코드(설계 알고리즘)

```text
onSableOriginalCallback(rawArgs, originalCallback):
    raw = copyRawAndAttachOwnerStep(rawArgs)
    result = (originalCallback != null)
             ? originalCallback.sable$onCollision(rawArgs) // exactly once
             : equivalentOriginalNoOpResult()            // verify this branch
    recordIntake(raw, result, originalCallback != null)
    return result                                          // never rewrite

onPhysicsSnapshotAvailable(system, step):
    rawBatch = intake.drain(system, step)
    validated = normalizer.reconcileFramesAndPreImpact(rawBatch)
    for item in validated:
        if !item.isValid: diagnostics.reject(item.reason); continue
        episode = assembler.mergeByBodyPairStepAndCluster(item)
        if episode.isDuplicated: continue
        episode.attachBodyMassPoseAndBlocks(item)
        budget = impactModel.oneTimeEnergyBudget(episode)
        ledger.open(episode.sourceBudgetId, budget)
        for targetAndWeight in episode.canonicalTargets:
            allocation = ledger.reserveWeightedShare(targetAndWeight)
            material = resolver.resolve(targetAndWeight.target, materialGeneration)
            intent = planner.plan(allocation, material, oldDamageState)
            stressPlanner.addBoundedIntents(ledger, intent)
        sealedPlans.enqueue(ledger.sealAndProveConservation())

onServerCommitSafePhase(levelOrPlot):
    if sable.isStepping(levelOrPlot): deferWithReason(STEPPING); return
    for intent in sealedPlans.upToBudget():
        if !ownerExists || !loaded || fingerprintChanged || veto || unsafeBE:
            postponeOrAbortAccordingToPolicy(intent)
            continue
        result = mutationAdapterFor(target.space).commit(intent)
        if result.applied:
            stateRepository.applyCommittedStateTransition(intent)
            invalidateMassPoseColliderEpoch(target.body)
            scheduleNativeReconciliation(target)
            scheduleClientDelta(intent)
        else:
            diagnostics.recordFailedCommit(result.reason)

onReconciliationAfterSableLifecycle(body):
    reconcilePlotOwnerAndSplitLineage(body)
    refreshMassAndColliderSnapshotFromSable(body)
    onlyThenClearMassOrSplitPending(body)
    persistDirtyStateAndSendScopedClientUpdates()
```

`equivalentOriginalNoOpResult()`와 `onServerCommitSafePhase`의 실제 호출 조건은 **대상 ABI/서버 실행 시점에 맞는 적절한 메서드로 구현해야 한다**. 이 문서는 존재하지 않는 공식 Sable API를 발명하지 않는다. 위 의사코드를 그대로 복사해서 자바 빌드 가능하다고 주장해서도 안 된다.

## 18. 기준 아티팩트·근거 위치 색인 (이 절은 구현 상태 판정표가 아니다)

### 18.1 고정 바이너리 해시 및 용도

| 아티팩트 | SHA-256 (제공 원본 기준) | 분석 용도 |
|---|---|---|
| `sable-neoforge-1.21.1-2.0.6.jar` | `9c199157843bf953a47dff625fc5bb41c01a90538d35ad18e6996bbcb8a617c2` | Sable callback, MassData, ServerSubLevel, physics pipeline |
| `create-1.21.1-6.0.10.jar` | `ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a` | Create 기계·레시피·회전 시스템 |
| `create-aeronautics-bundled-1.21.1-1.3.2.jar` | `a3f330d4757640f8715d991a43eedb826e4d0e51558d859c843ae6693bd35f1e` | Aeronautics 및 내장 모듈, 구조물 연동 |
| `true_impact-0.5.7-delta.jar` | `7c0c97b8a8709920e542ba2e1f142f959dee89a0ec5927e99b3be34ad3963c9c` | 데이터 계층, 콜백 래핑, 손상 누적 비교 |
| `sablecollisiondamage-1.0.8.jar` | `a053bc5aba63563918608dd9a53077d7dc832ce32066322fc0f0235c0deda62a` | 범용 callback 주입, 후물리 변경 비교 |
| `sableimpact-alpha-1.1.0.jar` | `5d1f29ff10600c7f300924d8b767b18ce314ea434694772296a47778c113d4b1` | 국소 구조 분석·균열, callback 대체 위험 비교 |
| `sable_optimizer-1.0(2).jar` | `0fd54d275472dfeb2cfffc83f0ae51fc880cd47cadff07637028d4fdce1da636` | 휴면·질량·힘·분리 업데이트 지연 호환성 |

위의 7개 파일 SHA-256은 이 문서 작성 시 로컬 업로드 파일을 직접 해시 계산해 대조했다. Sable/Aeronautics에 포함된 `META-INF/jarjar/` 내부 라이브러리도 실제 빌드에서 필요한 경우 함께 classpath에 포함한다. 다른 JAR 빌드를 같은 API로 단정하지 않는다.

### 18.2 참조 자료(원본의 시간 종속 문구는 본 통합 설계서로 치환)

1. `PDD-1.0.0-Design-Draft-3.3.2-Sable-Tunneling-Revision(1).md`: 목적·물성·Sable CCD 제외·손상/응력/저장/폭발/투사체 기능의 **규범적 원형**. 21~27절 등 과거 당시의 구현·이슈/CI 현황은 **본 설계서의 최신 상태로 수입하지 않음**.
2. `PDD-1.0.0-Integrated-API-and-Algorithm-Research-Updated-SableOptimizer(1).md`: 바이트코드 근거, 명확한 JVM descriptor, True Impact / Collision Damage / SableImpact 분석, 에너지·episode, Optimizer 호환 위험. 문서 내 구현 현황 서술은 **정적 당시 스냅샷**으로만 이용.
3. `Create-devastation.pdf`: DVT 1.0 Fracture, 2.0 Big Bang, 3.0 Reinforce 목표와 PDD의 현재 범위, 미래 인터페이스/상태 분리 원칙. 초안의 구 구현 번호는 승계하지 않음.
4. `PDD-Source-ZIP-Development-and-Debugging-Guidelines(2).md`: 모바일 GitHub 업로드·검증, 핵심 기능 기본 ON, 실패 관측/기능 목적 역추적, 실측과 인공 SELFTEST 구별.
5. GitHub 저장소: `https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`.
6. GitHub 설계 원본 이슈 #74: `https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/issues/74` (설계 개정의 출처이지 향후 개발 최신 상태가 아님).
7. Sable 원본 콜백/물성/진단 코드의 작업 기준 읽기: `src/main/java/com/physicaldestroydeform/` 및 `docs/PDD-0.2.11-RUNTIME-COLLISION-TEST.md` (역사적 사례, 본 설계의 현재 구현 근거로 재사용 금지).

### 18.3 문서 작성 시 확인한 프로젝트 읽기 기준의 기록

2026-10-28 GitHub 연결로 확인한 프로젝트 기본 브랜치는 `main`, 해당 시점 커밋은 `d02a915b397bf9e3988645da76b8a56f10283d6e`였다. **이 값은 작성 근거의 provenance일 뿐 '모든 향후 개발에서 최신 HEAD'라는 뜻이 아니다.** 새 개발 세션은 다시 GitHub HEAD를 확인해야 하며, 동일 해시가 유지된다면 중복 파일 판독을 생략할 수 있다. 또한 이 문서의 릴리스 로드맵이 위 커밋에서 이미 구현됐다는 주장을 하지 않는다.

### 18.4 어떤 분석을 언제 갱신해야 하는가

| 문서/증거 | 갱신 필요 트리거 | 갱신할 장소 |
|---|---|---|
| 규범적 기능 범위·로드맵 | 사용자의 **설계 요구 변경**, 근본적으로 양립 불가능한 실제 API 사실 발견 | **통합 설계서 새 버전** |
| PDD 코딩 진척/미구현 목록 | 새 커밋/새 릴리스 | GitHub 소스 + `docs/verification/`/release note |
| API 시그니처/물리 인수 | 의존 JAR 해시 또는 호출 경로 변경, 실제 반례 | 정적 API 검증 장부; 필요시 설계의 ABI 섹션 개정 |
| 게임에서 관측한 충돌 | 새 기능/영향받는 변경/재현 버그 | `GAME` 시나리오 기록/로그/이슈 |
| 밸런스 계수·서버 성능 수치 | 사용자 정책 선택 또는 측정값 갱신 | 서버 설정/profiles + 별도 결과 보고 |
| DVT 2/3 세부사항 | 해당 장기 릴리스 설계 착수 | DVT 독립 설계서 (PDD 1.0 규범 유지) |

---

## 19. 통합 설계서의 최종 판정 기준

**PDD 통합 설계서 1.0.0은 다음 다섯 가지를 영구 규범으로 고정한다.**

1. **하나의 목표:** 질량·접촉 정상 속도·물성·누적 손상에 따른 구조물과 정적 블록의 실질 손상·변형·파괴.
2. **하나의 책임 경계:** Sable의 물리 운동/CCD/분리는 유지하고 PDD는 피해 계획·안전 커밋·저장을 담당.
3. **하나의 알고리즘 체계:** callback 보존 → 공간·시간 정규화 → episode 단일 예산 → 경도/연성/탄성 적용 → 내부 응력 → 서버 변경 → 저장·시각화.
4. **하나의 버전별 구현 계약:** §14에 각 버전의 신규 기능과 완료 게이트를 고정. 문서의 과거 진단 상태는 다음 버전 목표를 결정하지 못한다.
5. **하나의 미래 확장 원칙:** PDD 1.0을 DVT 1.0 Fracture로 재사용하고 Big Bang/Reinforce는 독립 확장 레이어로 구현한다.

본 문서는 *개발 결과 보고서가 아니므로* `현재 n.n.n에서 구현됨/안 됨`을 업데이트하지 않는다. **매 버전의 최신 사실은 GitHub와 누적 검증 장부에만 존재한다.** 이 원칙을 지켜야 개발 방향이 과거 초안의 스냅샷에 붙잡히지 않고 PDD 1.0.0의 실질적 완료로 수렴한다.
