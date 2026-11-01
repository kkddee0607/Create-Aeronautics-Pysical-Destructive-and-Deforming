# PDD 통합 설계서 1.0.5

**Physical Destruction and Deforming — 1.0.5-A 알고리즘·시험값·구현 대안 통합 개정**

> **최상위 결론 (1.0.5-A 개정 포함):** PDD 게임 기능에 필요한 *충돌 사실·실제 소유 블록·증거 있는 게임 충격 예산*과, 솔버 수준에서만 가능한 *정확한 접촉별 임펄스·회전 접점 속도*를 분리한다. 지금 제공된 Sable/JNI로 작동하는 방식부터 0.3.0을 구현할 수 있다. 별도 JNI exporter는 **정밀도 고도화 옵션**이며 PDD 전체 진행을 차단하는 보편적 필수조건이 아니다. 정밀도 한계를 숨기거나 근사 예산을 실제 임펄스로 표기하는 것은 금지한다.

| 메타데이터 | 값 |
|---|---|
| 문서 버전 | **1.0.5 (알고리즘 통합 개정 A)** — 설계 문서, 모드 배포 버전과 무관 |
| 부모 문서 | `PDD-1.0.4-Integrated-Design-Specification-Sable-Source-Revision(1).md` (R2) |
| 목표 | **PDD 1.0.0** 및 **DVT 1.0 Fracture / DVT 2.0 Big Bang / DVT 3.0 Reinforce** 확장 경계 |
| 플랫폼 | MC JE 1.21.1 · NeoForge 21.1.x · Create 6.0.10 · Aeronautics 1.3.2 · Sable 2.0.6 · Java 21 |
| PDD 비교 SHA | `ee5b7cfd8122879570831bdcae2a889be50e3ed6` (`main`, 2026-10-31 UTC; moreRog) |
| Sable 공식 소스 | `ryanhcode/sable` `ad8b4d377741fbd923f4d863b2cf9e462daff9a6` (tag `mc1.21.1-2.0.6-neoforge`) |
| Rapier 소스 | Sable Cargo.lock/설계서에 기록된 fork SHA `38e92f117590862481a53df6fc69a5d893e29186` (참조 커밋; 별도 새 네이티브 빌드는 수행하지 않음) |
| 검증 종류 | **직접 JAR `javap -c -p -s`**, 7개 JAR SHA-256, 내장 53개 JNI 선언/심볼 비교, pinned GitHub Rust 원본 호출 경로, GitHub PDD HEAD/트리/워크플로 |
| 이번 산출물 상태 | **설계서 개정·JAR 정적 API 확인; 새 PDD 코드·Gradle/JUnit·Actions·인게임 실행 검증은 수행하지 않음** |

**해석 순서:** 1.0.5 §20~§28의 구체 구현 계약 > 1.0.5 §0~§19의 원칙/요구사항 > 1.0.4 이전 설계. 같은 파일의 일반적 미정 문구보다 새 임시 프로필/알고리즘이 우선한다. 원본 요구 기능을 삭제하거나 안전 불변식을 약화하는 뜻은 아니다. 이 파일 안에 남겨둔 1.0.4 계승 절(물성·영속성 등)은 1.0.5의 source quality/예산/버전 계약에 따라서만 해석한다. 0.3.0의 목표는 *실제 게임에 유효한 비침습 충돌 사건 브리지*이다. 원자적 solver point/impulse는 검증될 때 추가 선택할 수 있으나 이를 기다리며 다시 계측-only 버전을 발행하지 않는다.

---

**1.0.5-A 변경 요약:** §20 개발 프로토콜, §21 실제 블록 타깃 R1~R4, §22 신규 충격 FSM/중복 원장, §23 버전별 시험 기본값, §24 재료·파괴·저장·응력·변형, §25 DVT, §26 릴리스 게이트, §27 두 번의 인게임 검증, §28 허용 대체 구현 경로를 추가했다. §6.1·§7.5/7.6/7.7·§13·§14.3의 불명확한 기본값을 이 구현 계약에 연결했다.


## 0. 판독법·용어·규범의 강제력

- **MUST / 반드시**: 기능·무결성·안전성 계약으로 변경 불가. 변경은 설계 개정 제안과 사용자 승인 후 처리한다.
- **MUST NOT / 금지**: Sable 동작 손상, 근거 없는 입력, 중복 피해, 버전 진척 없는 계측 릴리스 등 허용하지 않는다.
- **SHOULD / 우선**: 타당한 근거가 있을 때만 동등 대안 선택이 가능하다.
- **POLICY**: 사용자 결정을 필요로 하는 게임 규칙 또는 밸런스 값. 미결정 항목은 코드가 안전한 기본값/설정 범위로 분리하되 임의로 영구 확정하지 않는다.
- **EVIDENCE**: 실제 바이너리/JAR·GitHub 소스·단위시험·실행 로그로 확인한 근거. 소스 존재와 게임 실행 성공을 혼동하지 않는다.
- **GATE**: 다음 단계 구현/릴리스 승격을 승인하는 기계적 합격 조건. '다시 조사하기'를 자동으로 뜻하지 않는다.
- **PDD 1.0.0**은 기능이 모두 완성된 출시 버전, **통합 설계서 1.0.5**는 이 독립 문서의 버전이다.
- **검증 이력**은 별도 `docs/verification/` 또는 GitHub 이슈·CI에 축적하며, 이 문서는 그 결과가 바뀌어도 재작성하지 않는다.

### 0.1 충돌 시 우선순위

1. 이번 작업에 대한 사용자의 **명시적 설계 변경/기능 범위 지시**.
2. 본 통합 설계서의 **규범적 계약** 및 이후 승인된 개정문.
3. 고정 대상 의존 JAR의 실제 시그니처·바이트코드·실행 가능한 API 제약. API 사실이 규범과 충돌하면 기능을 몰래 줄이지 않고 §28의 검증 가능한 대안 경로를 같은 버전에서 시도한다. 사용자 고유의 게임 규칙 선택이 반드시 필요한 경우에만 정책 결정을 요청한다.
4. **현재 GitHub HEAD 소스/빌드/테스트**: 구현 존재 여부와 재사용 가능 여부만 결정한다. 설계 목표를 덮어쓰지 않는다.
5. 최신 누적 검증 기록/재현 로그/기능 완료 이슈: 해당 조합·시나리오에 한해서 완료 여부 판정에 사용한다.
6. 비교 애드온 구현/과거 보고서/그 외 자료: 아이디어 및 API 탐색 근거. 규범을 임의 대체하지 않는다.

### 0.2 '지난 구현 상태'를 설계에서 완전히 격리하는 절차

- 본문에는 `최신 구현`, `현재 완료`, `다음 릴리스에서 재검증`, `0.2.11은 아직 ...` 등의 **가변형 완료 주장**을 넣지 않는다. 문서 끝의 근거 목록에만 **분석 대상 소스의 식별자**를 출처로 기록한다.
- 0.2.11·0.1.2 등은 **코드 마이그레이션 시작점 설명**에만 쓰며 향후 개발 단계의 영구 판정표에 넣지 않는다.
- 누적 테스트는 `version+commit+dependency hashes+scenario+result+evidence URI`로 등록한다. 버전이 늘어났다는 이유만으로 API 조사, 현상 재현, 구현 진단을 다시 하지 않는다.
- 다음 단계 시작 시 **계획된 새 코드**와 관련된 회귀만 실행한다. 이전 게이트는 변경 영향 범위에 포함되거나 증거가 명백히 반증된 경우에만 재검증한다.
- 미완료 구현을 '안전성 검증' 이름으로 반복 관측 버전으로 대체해서는 안 된다. §28에 따라 가장 현실적인 구현 가설을 실제 기능 코드로 작성하고 진단을 함께 넣는다. 진단은 목표 기능 구현에 **동반되는 도구**이고, 해당 버전 릴리스의 주목적은 명시된 신규 기능이다.

### 0.3 통합 설계서 1.0.2 개정 범위(1.0.1 대비)

1. **충돌 대상의 실제 소유 공간을 최우선 식별자로 채택한다.** `BlockTarget = (dimension, STATIC_WORLD 또는 stablePlotId, 해당 공간의 BlockPos, fingerprint)`를 기반으로 피해/변형/파괴 대상을 결정한다. 정적 블록을 plot 좌표로, plot 블록을 외부 월드 블록으로 바꿔서 변경하지 않는다.
2. **블록 식별과 충돌 기하 검증을 분리한다.** 월드 접촉점 일치, 0.25블록 근접 판정, 블록 경계에서의 임의 2블록 여유는 피해 대상의 존재·소유권을 증명하는 조건이 아니다. 월드 충돌점은 기하학 연산을 수행할 단계에서 별도로 검증한다.
3. **미확정 Sable 인수를 확정된 사실로 승격하지 않는다.** `sable$onCollision(hit,other,impactPosition,signedVelocity)`의 시그니처는 확인되나, 각 인수의 전수 좌표계/소유자 생성 경로/콜백 간 동일 접촉 대응 여부는 호출부별로 확인해야 한다. 유사 애드온에서 채택한 패턴을 Sable의 보편적 보증으로 해석하지 않는다.
4. **0.2.13 `G-COL-02` 합격 기준을 교정한다.** 정적↔물리, 물리↔물리, 회전, 다중 접촉, 정지·끼임 상태에서 양측 `TargetKey`와 소유 공간 확인을 필수로 하고, 접촉 좌표 차이만으로 유효한 타깃을 거절하지 않는다. 반대로 기하정보가 검증되지 않았으면 이후 충격량/피해 계산에 사용하지 않는다.
5. **기타 요구사항은 유지한다.** 기존 경도·연성·탄성, episode 단일 예산, Sable 원본 콜백 보존, 0.3.0 이후의 역유효질량·회전 연산, 안전 서버 커밋·로드맵·배포 계약을 축소하지 않는다.

이 개정은 **구현 완료·실제 빌드·게임 합격을 주장하지 않는 규범 변경**이다. 증거 검증 현황은 설계서 바깥의 버전별 장부에 기록한다.

### 0.4 통합 설계서 1.0.3 개정 범위(1.0.2 대비)

이번 개정은 **기능 삭제가 아니라 필수 데이터의 범위와 개발 게이트의 교정**이다. 상세 근거는 §3.3.2 및 §18.2의 바이트코드 감사 자료를 참조한다.

1. **파괴 타깃을 우선 확정한다.** 검증된 `(차원, 소유 공간, stablePlotId, 해당 공간 BlockPos, 블록 상태)`가 피해 대상이다. 서로 반대 방향의 개별 콜백 접촉점 일대일 대응은 타깃 결정이나 인접 블록 응력 전파의 필수조건이 아니다.
2. **접촉 기하와 충격 예산의 정확도 등급을 분리한다.** 정확한 접촉 기반 모델은 접촉점·법선·시점·질량을 요구한다. 검증된 충돌·운동학적 접근 정보가 있으나 정확한 접촉점만 없는 사건은 **명시적 게임플레이 근사 예산**으로 처리할 수 있다. 정확한 물리 에너지/임펄스라고 표시하지 않는다. 유효한 접근 근거 자체가 없으면 피해를 만들지 않는다.
3. **Sable의 네이티브 쌍 데이터는 보조 품질 향상 자료이다.** 15-double 효과 레코드는 한 레코드에 양측 body ID·local point·local normal을 갖지만, 필터링된 post-solver 효과 피드이므로 모든 충돌을 대표하는 단일 필수 입력으로 승격하지 않는다.
4. **응력 전파는 검증된 소유 공간의 블록을 씨앗으로 시작한다.** 별도 정밀 접촉점이 없으면 6면 이웃에 안전한 비방향성 가중치를 적용한다. 총예산 상한·노드별 1회 탄성 완충·다중 유입 합산은 그대로 유지한다.
5. **`G-COL-02`를 타깃·소유권 게이트로 되돌린다.** 기하/좌표 프레임이 불명확한 사실을 격리·기록하는 것은 요구하되, 모든 정상 충돌 유형에 정밀 접촉점 원천을 확보하지 못했다는 이유만으로 타깃 게이트를 막지 않는다. 그 요구는 입력 등급별 `G-MATH-01`에서 검증한다.
6. **새로운 숨은 파괴 조건을 만들지 않는다.** 실패한 기하를 `VERIFIED`로 위장하지 않고, 미검증 scalar·임의 월드 좌표·무근거 상대 속도로 피해를 생성하지 않는다. 승인된 물성·저장·서버 커밋·에너지 원장·원본 콜백 보존·빌드/배포 계약은 전부 유지한다.

**개정 범위 제한:** 충돌 실측 결과로 곧바로 성능이나 물리 정확성을 주장하지 않는다. 1.0.3은 규범적 설계 개정이며, 개발 릴리스·빌드 합격 판정이 아니다.

### 0.5 통합 설계서 1.0.5: 기능 경로와 정밀 소스 경로의 명시적 분리

1. **신뢰할 수 있는 최소 데이터부터 시작한다:** 사건의 출처와 수명, 타깃 소유 공간, 실제 블록 상태, 독립 충격 근거, 중복 없이 차감 가능한 *게임 예산*이 0.3.0 진입 필수다. 모든 사건의 정확한 contact manifold ID/rotation/impulse는 현재 JAR의 API에 없으므로 필수로 강제하지 않는다.
2. **Sable 엔진은 단독 물리 소유자다:** solver, 반발, 충돌기, CCD, 회전/질량 갱신과 특수 callback 결과는 변경하지 않는다. PDD는 파괴 게임 규칙, 재료 반응, 응력, 저장/시각화 및 서버 커밋 소유자다.
3. **서로 다른 이벤트 소스를 혼합해서 가짜 정확도를 만들지 않는다:** `clearCollisions`의 15-double 효과 레코드는 post-solver *힘 이벤트 필터*이고, Sable callback에는 사건/매니폴드 ID와 signed 접근속도가 없다. 두 소스의 결합은 동시 사건의 증명이 아니며 기본 게임 예산은 보수적 상한만 사용한다.
4. **문제 해결을 차단 조건으로 바꾼 과거 설계의 오류를 철회한다:** 기존 기존 `0.3.0=반드시 새 JNI exporter` 규정과 `raw contact를 얻지 못하면 전체 개발 차단` 규정을 폐지한다. 개별 입력이 부족하면 해당 모델의 정밀/방향 처리만 비활성 또는 보수 경로로 처리한다. 게임 피해의 증거가 아예 없으면 예산은 0이다.
5. **원본 null callback 의미 보존:** 일반 블록에 보편 observer를 삽입하면 Sable의 null→non-null JNI 분기가 바뀐다. Sable Collision Damage와 SableImpact의 작동 사례가 이 분기를 안전하다고 *증명*하지 않는다. 기본 `OBSERVE_POST_EFFECT`는 기존 native 결과를 그대로 관측하고 원본 callback은 호출·반환·예외를 바꾸지 않는다. 선택적 universal callback 호환 프로필은 명시된 테스트를 통과할 때만 활성화한다.
6. **물리적 정확도와 게임플레이 정확도를 분리:** `OBSERVED_FORCE_EVENT`, `OBSERVED_CALLBACK`, `CORROBORATED_PROXY`, `SOLVER_NATIVE_VERIFIED` 등 출처·불확실성을 로그와 DTO로 보존한다. 어떤 grade든 `J_raw`/`F_raw`와 `Q_game`을 혼동하지 않는다.
7. **각 버전에 기능을 구현한다:** 0.3.0은 런타임 사건→검증된 A/B target→단일 후보→예산 0 또는 capped game proxy까지 실제 동작해야 한다. 0.3.1은 episode/credit ledger; 뒤 버전은 §14 기능을 모두 구현한다. 근사형 사건이라도 피해 근거가 증명되면 게임 정책 범위에서 진행할 수 있다.
8. **DVT 전체 수명:** 별도 source들은 동일 Budget/Material/Mutation 파이프라인을 사용한다. 해당 모드들이 공유하는 설계 목표를 위해 네이티브 분기 없는 API 계약을 우선한다.

**역사적 의사결정 수정:** 1.0.2/1.0.3은 `TargetResolution != ContactGeometry`를 정립했고, 1.0.4 R2는 native point/impulse 우선의 기술적 정확성을 지향했다. **1.0.5는 둘을 합친다.** 필수 게임 기능은 표준 Sable 2.0.6부터 구현하고, 정밀도 기능은 허가된 원자적 배치의 독립 승격 게이트로 관리한다.

## 1. 개발 목적·기능 정의

PDD는 Create Aeronautics의 물리 구조물(함선·비행체 등), 정적 Minecraft 월드 블록, 서로 다른 물리 구조물의 충돌 결과를 받아 **충격/질량에 따른 블록 손상, 영구 변형, 파괴, 주변 응력 전파, 누적 상태 및 시각화**를 제공하는 NeoForge 애드온이다. 엔진과 충돌의 소유자는 Sable이며, PDD는 충돌의 게임 내 재료 반응을 소유한다.

### 1.1 1.0.0 필수 결과

1. 일반 블록으로 구성된 구조물↔지형 및 구조물↔구조물 충돌에서 **양측 타깃**을 각자의 소유 공간(`STATIC_WORLD` 또는 구별되는 `SABLE_PLOT`의 stable ID)과 그 공간의 `BlockPos`로 식별한다. 타깃 식별·직접 손상·자기 공간 내부 응력의 시작에는 두 콜백 접촉점의 월드 좌표 일치를 요구하지 않는다.
2. **Sable의 변경 없는 충돌 관측 정보**로 게임 충돌의 존재와 target을 먼저 판정한다. 신뢰성 있는 native 접촉별 impulse가 있으면 그 값을 우선 이용하되, 기본 2.0.6 JAR에서는 사후 효과와 보존된 callback을 독립 증거로 삼아 상한 있는 게임 예산을 계산한다. 접점 속도/정확한 임펄스가 없다는 이유로 기능 개발 전체를 중지하지 않는다.
3. 복수 콜백·프레임·동일 충돌 양측 보고를 **하나의 episode 예산 원장**으로 통합한다.
4. **경도(H)**는 직접 손상 저항, **연성(D)**은 파괴 전 변형 허용 능력, **탄성(E)**은 PDD 손상 예산의 1회 완충을 통해 직접 손상 및 이웃 응력을 함께 감소시킨다. 파괴 임계값 `T`는 경도와 별도로 보정한다. 물성은 블록 정의별 자동 산출·재료 프로필·상태·예외 규칙으로 결정한다.
5. 충돌을 받은 실제 블록에 누적 손상/변형을 유지하고, 실제로 가능한 블록에는 **서버 권위 격자 변형**을 일으키며 임계 도달 시 파괴한다.
6. 주변 블록에 한정된 총예산·탐색 깊이·작업량을 사용한 응력을 전파한다. Sable의 구조물 분리/충돌기 재구성과 일관되게 작동한다.
7. 정적 월드와 Sable plot 각각에서 상태·드롭·BE(블록 엔티티)·저장·재시작·청크 로딩·클라이언트 균열 표시를 안전하게 관리한다.
8. Minecraft 기존 폭발과 투사체/엔티티 타격에서, **기존 시스템을 대체하지 않으면서 추가 손상**을 중복 없이 처리한다.
9. 다수의 대형 구조물이 활동하는 소규모 멀티플레이에서 위험한 무한 작업/메모리 폭주를 방지하고 측정 가능한 성능 상한을 갖는다.
10. DVT 1.0이 손상 엔진을 복제하지 않고 사용할 수 있는 **입력/물성/계획/커밋** 확장 경계를 제공한다.

### 1.1.1 1.0.5의 충돌 완성 조건 — 실제 릴리스 가능한 게임 계약

- `GAME_SOURCE`: 일반 블록 충돌을 실제 Sable 사후 효과 관측 경로에서 확보하고, *확인된 범위*의 물리 객체/세계 정적 접촉을 전달한다. 콜백의 원본 동작은 변경하지 않는다.
- `TARGET`: world/plot, plot/plot에서 피해 후보는 각자 owner-local 블록·UUID/차원·state fingerprint가 확인된 경우만 얻는다. 직접 얻지 못한 측은 `UNRESOLVED_SIDE`, **가까운 타 Plot를 임의로 선택하지 않는다**.
- `EVIDENCE`: 실제 native 사후 효과/보존된 원본 콜백을 출처별로 저장한다. 필터링·100개 절단·tick 수준 시간 해상도를 정확히 기록한다. 힘 이벤트의 존재는 충격 관측 근거지만 그 자체가 **물리적 per-point J**는 아니다.
- `BUDGET`: 독립적인 신규 충돌/접근 근거가 있으면 *명시적 상한 게임 proxy*로 손상 가능. 속도 부호나 재접촉 여부가 불명확하면 `CANDIDATE_ONLY` 또는 Q=0. 흔들림/상시 접촉으로 매틱 피해 증식을 허용하지 않는다.
- `ROTATION`: 회전-only 충돌은 게임에서 **실제 타격이 발생하는지**를 대표 장면으로 확인한다. 변형 방향은 검증된 현장 법선 또는 별도 승인된 local geometry에서만 사용하며, 미검증이면 비방향성 손상/응력으로 제한한다. `exact solver rotation`을 허위 주장하지 않는다.
- `SOLVER_OPT`: 나중에 허가된 native solver point/impulse가 제공되면 같은 사건 원장을 통해 *상호배타적* 입력 모델을 승격한다. 이는 0.3.0 게임 기능/물성 엔진을 재작성하는 조건이 아니다.
- `WORLD_CHANGE`: 0.3.0에서는 실제 목표를 로그/비파괴 가상 예산으로 확인하고 월드 변형 OFF; 이후 단계에서 커밋을 켠다.
- **전체 요구 보존:** 1.0.0에서 손상·탄성·연성·변형·파괴·저장·응력·외부 피해·성능·DVT 확장 전부 구현. 특정 물리량이 불가능하다는 이유로 기능 자체를 삭제하지 않고 source 등급에 맞는 의미와 한계를 정의한다.

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
| Sable/Rapier | 강체·서브레벨·collider·접촉 블록 좌표·법선·solver·물리 시간 | **현재 배포 Java에서 가능한 post-effect/기존 콜백·질량/pose; 내부 Rust에만 있는 solver contact는 별도 optional** | 물리 계산/CCD/split을 PDD가 다시 수행 금지 |
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

### 3.1 실제 Sable 2.0.6 Java 시그니처/물리 소유권

| 호출 | 실제 선언/입력 | PDD 사용 목적/제약 |
|---|---|---|
| `BlockWithSubLevelCollisionCallback.sable$getCallback(BlockState)` | `(BlockState)->BlockSubLevelCollisionCallback` | 기존 특수 콜백 수집. **null은 엔진 의미 있는 상태** |
| `BlockSubLevelCollisionCallback.onCollision(int x,y,z, int ox,oy,oz, double px,py,pz,double velocity,boolean hasOther)` | `(IIIIIIDDDDZ)[D` (default) | JNI가 `sable$onCollision(hit,other,Vector3d,velocity)`로 위임해 4 double array 반환; 콜백의 반환/예외를 변경하지 말 것 |
| `sable$onCollision(BlockPos hit,BlockPos other,Vector3d impactPosition,double impactVelocity)` | `CollisionResult` | 이벤트 관측. 좌표는 hit 소유 프레임 및 callback scope 안에서만 확인. velocity는 `hooks.rs`가 `abs()`한 값, signed closing 아님 |
| `Rapier3D.clearCollisions(long)` | `double[]`, 비공개 static native | Sable `RapierPhysicsPipeline.processCollisionEffects()`가 소비. PDD는 **ModifyExpressionValue로 원본 배열을 수정 없이 보존**하고 15-field 배열을 복사/해석 |
| `Rapier3D.step(long,double)` | 비공개 static native | Sable 원본 step. PDD 중복 호출·전후 tick 추정 금지 |
| `MassData.getMass()`,`getInverseMass()`,`getInverseNormalMass(Vector3dc,Vector3dc)` | `double` | 충돌 강도 상한의 **선택적 보조**, 질량·로컬 점/법선 및 정적 무한질량 변환 검증 후 사용 |
| Sable `ServerSubLevel`/`ServerSubLevelContainer` | `getRuntimeId`, stable UUID, block accessor, mass/pose lifecycle | owner mapping, plot 실제 블록 검증, 변경 후 invalidation. 직접 호출은 실제 JAR descriptor/패키지로 고정 |
| `RigidBodyHandle.getLinearVelocity(Vector3d)` / `getAngularVelocity(Vector3d)` | 실제 JAR의 개별 구현 시그니처 검증 필요 | 진단/병진 보조. body 중심 속도만으로 회전 접점의 signed closing을 단정 금지 |
| Sable `getCurrentlySteppingSystem()` | Sable tick/특수 callback 실행 중 시스템 참조 | callback에 개별 `sceneId`가 없으므로 해당 실행 문맥의 디버그/owner 참조로 한정 |

**실제 바이트코드 검증:** Sable callback default `onCollision`은 `BlockPos` 두 개(상대 부재면 null), `Vector3d`와 scalar를 구성하고 `sable$onCollision`을 한 번 호출한 뒤 `tangentMotion.x/y/z`와 `removeCollision`을 4개 double로 반환한다. `MassData.getInverseNormalMass`의 default는 `r=point-centerOfMass`, `n=normalize(normal)`, `r×n`, `I^-1`를 통한 **역유효질량**을 계산한다. PDD가 이를 역학 구현의 중복 코드로 보유할 필요는 없다. 단, 정확한 plot-local 인수 확보 전 강도 계산에 임의 적용하지 않는다.

### 3.2 원본 Rust의 실제 접촉 파이프라인 — 서로 다른 세 경로

1. `dispatcher.rs`는 voxel collider 쌍과 블록 `pos_a/pos_b`, `col_a/col_b`를 manifold step-local `SableManifoldInfoMap`에 쓴다. index는 `(index << 1)` 형태로 `user_data`에 들어간다. **세계 전체에 공개된 Java getter가 아니다.** 이 블록쌍은 Rust 내부에서 정확한 정보지만 Java `clearCollisions`로 전달되지 않는다.
2. `hooks.rs`의 `modify_solver_contacts`는 `needs_hooks`가 거짓이면 바로 반환한다. hook가 실행되는 경우 `SolverContact.point`와 `context.normal`은 실제 solver 기하이고, `RigidBody.velocity_at_point(contact.point)`로 회전 포함 속도를 계산한다. 거기에 kinematic fake velocity를 추가하지만 실제 코드의 양측 처리와 부호가 완전 대칭이라고 가정하지 않는다. 특히 `velocity = velocity.abs()`로 **접근 부호를 소거**한 뒤 블록 callback으로 넘긴다. 원본 block response/필터를 그대로 유지해야 한다.
3. `event_handler.rs`의 `handle_contact_force_event`는 `total_force_magnitude < 0.1`을 제거한다. 포인트마다 같은 이벤트 전체 `total_force_magnitude`를 `ReportedCollision.force_amount`로 복사한다. `lib.rs::clearCollisions`는 보고 목록을 최대 **100 records**로 자른 뒤 각 record를 15 double로 직렬화·비운다. Sable가 사후 효과로 소비한다.
4. `lib.rs::Rapier3D_step`은 solver step 시작 때 manifold map을 clear한다. 따라서 **post-effect 레코드의 접점과 owner-local block pair를 출처별로 확인하는 것**이 필요하지만, 외부 PDD가 source 존재만으로 map을 조회할 수 있는 것은 아니다.
5. 바이너리 `Rapier3D`의 native 선언 53개와 Linux x86-64 `Java_...Rapier3D_*` JNI export 53개는 **이름 집합이 일치**한다. 일반 solver contact batch export는 없으며, Export/ABI 부재는 *정밀 솔버 원자정보 부재*의 근거이지 *게임 충돌 피해 구현 불가능*의 근거가 아니다.
6. **소스↔빌드 caveat:** pinned 소스 Rust의 `newVoxelCollider` 6인수와 Java/JAR 5인수 불일치는 해소되지 않았다. 해당 소스를 빌드하여 기존 native DLL/SO로 교체하거나 새 PDD ZIP에 포함하면 안 된다. 기존 Sable JAR은 실행 확인된 종속물로 취급하고 **비침습 Java observation**을 먼저 사용한다.

### 3.3 세 비교 애드온의 바이트코드 실제 경로, 채택과 금지

| 대상/근거 클래스 | 확인된 구현 | PDD 채택 | 채택하지 않는 부분 |
|---|---|---|---|
| **True Impact 0.5.7-delta** `DiagnosticContactCaptureMixin`, `SableImpactCapture`, `SableBodyReader` | 1x `clearCollisions(int)` 및 2x `clearCollisions(long)` reflection 경로 탐색, 15-field native 사후 효과/바디 스냅샷 및 속도 차이 비교, 임시 이벤트 집계와 후보 block 탐색 | 기존 2.0.6의 2x 시그니처만 고정하고, 사후 effect 단일 수집·플롯 실제 블록/차원·제한된 큐·스냅샷 validation과 대표 로그 | reflection으로 원본 consumer보다 먼저 `clearCollisions` 재호출하지 않기; 임의 local→world 변환 확정 없이 적용 금지; 강도식 결과를 정확한 solver J로 표시 금지 |
| **True Impact 0.5.7-delta** `computeMetrics`, `DamageResolver`, `SubLevelFractureHelper`, `StressPropagator` | 실제 bytecode에 `J²/(2m)` 및 `0.5*m*v²` 같은 재계산 경로가 존재한다. **`DamageResolver.resolve`는 ACTIVE_IMPACT인 경우에도 `NONE`을 반환**한다. 다른 누적·큐·응력 경로는 존재 | DamageAccumulator/MaterialResponse/Local stress graph/서버 큐 계층 분리라는 구조만 채택 | `DamageResolver`를 작동 완성형 damage engine으로 간주 금지; 질량·운동량 이중 계산 복사 금지; 소스 라이선스 무시 복사 금지 |
| **Sable Collision Damage 1.0.8** `RapierVoxelColliderBakeryMixin`, `UniversalFragileCallback`, `SablePreSolverDamage` | Sable 원본 callback을 받고 일부 null·Fragile을 universal callback으로 대체(설정 조건 포함), 실제 `ServerSubLevel` block accessor를 통한 대상 선택, post physics break/slowdown 큐 | 소유 공간을 구분하여 실제 block mutation에 연결하는 구조, post physics deferred queue | null→non-null 분기 변경, 물리 충돌 제거/감속, 인접 plot을 가까운 거리로 대체 선택하는 동작을 PDD 기본 구현에 포함 금지 |
| **Sable Impact alpha 1.1.0** `KineticColliderMixin`, `KineticImpactCallback`, `LocalKineticAnalyzer`, `BeamSnapper`, `CrackManager` | `@Redirect`가 Sable callback 반환을 `KineticImpactCallback.INSTANCE`로 대체, mass/linear+angular velocity 조회, local scan·연결 탐색·균열/네트워크 | 소규모 제한적 연결 탐색, 재료별 JSON 및 균열 표시 아이디어 | 원본 콜백 무시, 중복 collision removal, 전체 구조물 스캔, 라이선스 확인 없는 코드 직접 복제 금지; **바이트코드 `sable$onCollision(BlockPos,Vector3d,double)`는 2.0.6 인터페이스의 4-인수와 descriptor 불일치**, 정상 작동 장면이 있다는 사실과 현재 업로드된 바이너리의 이 시그니처 호환은 별도 문제 |

**사용자가 보고한 세 애드온 정상 동작**은 게임 기능 가능성의 유효한 현장 관측이다. 그러나 *게임에서 파괴된다*는 사실로 각 애드온이 `per-contact solver impulse`, 물리적으로 정확한 단위, 모든 장면의 변형 방향을 제공한다고 단정하지 않는다. **PDD의 선택:** 기능 계층을 재활용하고 정확도 미검증 수학/원본 물리 개입은 재활용하지 않는다.

### 3.4 Create/Aeronautics/Sable Optimizer 경계

- Create/Aeronautics는 기계/물리 Plot 조립·글루·회전 기계/BE의 기존 동작과 state lifecycle을 소유한다. PDD는 그들을 충돌 솔버로 대체하지 않는다. split/reassembly 이후 좌표·UUID·mass epoch를 새로 확인한다.
- `sable_optimizer`는 선택 의존성이다. 설치 여부를 확인하지 않은 정적 클래스 초기화 금지. lazy/skip 물리 step이 있을 수 있어 fixed `20Hz` 충돌 발생 가정 금지. 일부 최적화 기능이 mass/pose/split 갱신을 지연시키는 상황은 별도 회귀 테스트로 증명한다.
- 이 연구에서 Create/Aeronautics Java의 *모든* API 서명은 추가 역컴파일 완료하지 않았다. 미래 호출은 각 기능 개발 시 실제 해당 JAR `javap`, `compileClasspath` 및 인게임 생명주기를 검증하고 `VERIFIED`로 승격한다. 이것은 구현 누락을 정당화하는 근거가 아니다.

### 3.5 라이선스/ABI/클래스로더

- Sable 본체 `LICENSE.md`: PolyForm Shield 1.0.0. 내장 Rapier는 별도 license 통지. TrueImpact/SableImpact의 라이선스·저작권 조건은 JAR 내 배포 메타데이터와 원본 저장소의 실제 선언을 함께 확인한 뒤 코드 재사용을 결정한다. 기본 방침은 **구조/의미만 역설계, PDD 독자 구현**.
- 네이티브 소스 ABI 대안은 별도 승인을 받기 전 실행·배포하지 않는다. JNI symbol 이름 일치만으로 함수 인수 메모리 계약까지 확정되지 않는다.
- 현재 PDD `build.gradle`: Java 21, NeoForge `21.1.228`, `net.neoforged.moddev 2.0.78`, Sable common `2.0.6`, Companion `1.6.0`, JUnit Jupiter `5.11.4`; 전부 보존. Sable JAR Jar-in-Jar는 Java compiler classpath에 자동 제공된다고 가정 금지.
- PDD 전체 소스 ZIP의 `.github/`, `gradlew*`, `gradle/`, `incoming/` 루트 보호 정책은 기존 Apply 액션 그대로. GitHub 업로드/Actions 실행은 사용자 수행.

### 3.6 정확도 계약: **기능의 선행조건이 아닌 독립 승격 경로**

- **기본 호환 경로(`JAVA_OBSERVED`)**: `RapierPhysicsPipeline.processCollisionEffects`가 `clearCollisions(J)[D`의 원본 배열을 받은 직후 **한 번만 관측**한다. 원본 값을 수정하지 않는다. Sable의 원래 처리를 가로채서 자기 소비자로 바꾸지 않는다. 원본 특별 콜백은 결과 무변경 observer로 유지할 수 있다.
- **보조 식별 경로(`OWNER_VERIFIED`)**: `body runtimeId -> stable plot UUID + dimension + block accessor` 지도. world -1/None을 별도 값으로 취급한다. local point 및 block candidate는 transform/CoM이 검증된 범위에서만 부여하고, 후보 충돌 지점 주위 검색은 **탐색**일 뿐 소유자 증명 자체가 아니다.
- **게임 피해 경로(`GAME_PROXY`)**: 원본 Sable의 사후 event 증거와 신규 impact/gate가 통과할 때만 bounded proxy를 발행. `F_raw`는 빈번한 접촉에서 지속될 수 있으므로 `F_raw` 그 자체나 접촉 record 개수를 1:1로 데미지에 곱하지 않는다.
- **정밀 향상 경로(`NATIVE_SOLVER_OPTIONAL`)**: 실제 post-solve `ContactData.impulse`, 같은 step owner-local block, world normal/point, pre-contact signed speed를 안전한 read-only bridge로 확보하면 등급을 올린다. 그 전 `G-CONTACT-01` 게임 게이트를 native ABI 부재만으로 BLOCKED로 만들지 않는다. 다만 **정밀 네이티브 게이트 자체**는 `NOT_AVAILABLE`로 정확히 표시한다.
- **역학 재계산 비용:** Java에서 모든 충돌에 역관성·회전접점·매니폴드를 재구성하지 않는다. 선택적 질량과 속도는 검증된 실데이터에서만 보조 게임 모델의 계산 근거로 사용. 회전 접근 방향을 모르는 사건에는 무방향성 손상만 허용한다.
- **절대 금지:** native effect가 실제 100개 제한/force threshold를 갖는 점을 무시한 전수 coverage 주장, source/callback 시간 mismatch를 합성 contact ID로 확정, 에너지 SI 단위 증명 없는 joule 표시, 원본 callback return/타이밍의 비의도적 변화.

## 4. 아키텍처·모듈 단방향 의존성

```text
Sable 2.0.6 (원본 solver + CCD + voxel collider + callback + effect)
       │ read-only ModifyExpressionValue on original post-effect array
       ├──────────────> ImpactSourceCapture [NATIVE_POST_EFFECT]
       │                           │
       │ original special callbacks │ optional no-alter wrapper
       └──────────────> CallbackDiagnosticCapture
                                   │
                          SourceCorrelator (optional, NEVER falsely exact)
                                   │
                          OwnerTargetResolver
                                   │  scene/dim+runtime body id ↔ stable UUID
                                   │  actual BlockState/fingerprint validation
                                   ▼
                          SourceQualityGate
                                   │
                         NewImpact/ContinuityGate
                                   │
                         BudgetCandidate(Q_game or zero)
                                   │
                         Group/EpisodeLedger
                                   ▼
                         Material H/T/D/E  →  Direct/Stress/Plastic plan
                                   ▼
                         Server-only safe MutationCommit
                                   ▼
                         Sparse saved state + crack sync + Sable lifecycle

 OPTIONAL: authorized real solver contact batch -> same SourceQualityGate,
           replacing (NOT adding to) this event's proxy budget.
 DVT: explosion/projectile/engine failure/material change -> same ledger+planner+commit.
```

**구현 파일 구조 원칙:** 0.3.0에서는 새로운 12-manager 계층을 만들지 않는다. `NativeCollisionTelemetry`/`SableFrameCapture`/`OwnerResolver`/`ImpactContinuityGate`/`ImpactEpisodeLedger`를 재사용하여 첫 성공 경로를 만든다. `ImpactFaceEstimate`, `ContactPairMatcher`, `NativePlotDirection`, `ImpactLiveAudit`는 현재의 **추정 계산을 사용하는 damage-authority 경로에서 떼어내고 진단/테스트 경로로만 보존**한다. `FrameMath`와 `ImpactMath`는 검증된 순수 모델의 유닛 테스트용으로 당장 삭제할 필요 없지만 hot path에 다시 연결하지 않는다. `SableUniversalCallbackMixin`은 부작용 없는 원본 특수 콜백 계측으로 축소하거나 안전한 경우 제거한다. 원본 null을 래핑하는 분기는 1.0.5 기본 구성에서 사용하지 않는다.

## 5. 필수 데이터 최소집합과 품질 등급

### 5.1 **PDD 1.0 + DVT에 실제로 필요한 값 (강제/선택/불필요)**

| 데이터 | 필요한 시점 | 필수인가? / 실제 제공처 | 게임에서의 역할 |
|---|---|---|---|
| `sourceKind`, `dimension`, `observationTick`, 이벤트 순서, run ID | 0.3.0부터 | **필수**; PDD observer가 부여. native solver step ID라는 주장 금지 | 사건 스코프/원장/중복 보호 |
| source body A/B runtime ID, 세계측 `-1` | world/plot, plot/plot | **원본 post-effect에 존재하면 필수**; 15-double `[0],[1]` | owner lookup·body pair grouping |
| stable Plot UUID 및 owner-local BlockPos와 BlockState fingerprint | 손상·응력·파괴·변형 시 | **타깃별 필수**; Sable sublevel runtime mapping + block accessor | 실제로 수정할 블록 식별. 알 수 없는 측은 피해 금지 |
| 독립적으로 관찰된 충돌 이벤트·유효한 신규 타격/재접촉 증거 | 0.3.0/0.3.1 | **positive 피해 발행 필수**; post-effect 이벤트와 시간상 상태 + 신뢰 가능한 접근/신규 충격 지표 | 자중/정지/슬라이딩 반복 피해 차단 |
| 정책 프로필 `Qmax`, `k`, minImpact, 보수적 정규화 및 sourceQuality | 게임 예산 산출 | **필수**; PDD 설정·검증된 입력 | 게임피해 `Q_game` 생성; 실측 임펄스라고 불러선 안 됨 |
| world/local 접점과 법선 + 회전/CoM transform | 정확한 방향/격자 변형과 일부 proxy | **조건부 필수**; native post-effect local points/normals + 검증된 Sable pose/CoM | 검증 가능한 사건에만 방향 적용, 불확실하면 damage-only/비방향성 stress |
| Sable mass 또는 relative linear velocity | 접근/강도 선택적 정량화 | **조건부**; Sable `MassData`/RigidBodyHandle | 보수 게임 예산 개선, 속도만으로 회전 접점 signed closing 단정 금지 |
| contact per-point solver normal impulse, pre-solver signed `v_n`, native manifold id | *물리 정확도 등급* | **기본 1.0.0 게임 기능의 필수 아님**; 기존 Java API 부재 | 있을 때 정밀도 향상, 필요시 업스트림 별도 인터페이스 |
| full angular velocity, inertia tensor, ω×r Java 재구성 | 정상 게임 hot path | **불필요** | 진단·정밀 수학 비교에서만 사용 |
| 정확한 충돌 접점의 평생 ID, callback pair reciprocal coincidence | 게임피해·상태 저장 | **불필요** | 1개 bodypair/episode 그룹과 실제 owner block 검증으로 처리 |
| 모든 설치 블록의 속성/레시피 반복 탐색 | 물성 등록 | **불필요** | registry/datapack reload마다 정의별 캐시 |

**주의:** source/tick/body pair만으로 '충격'을 확정할 수 없다. `newImpactEvidence`가 실패하면 Q=0 또는 보류이며, **분명한 타격과정에서 event가 나왔더라도** rest/continuous 여부·velocity 접근 여부가 미검증이면 물리적 에너지와 게임 피해를 분리한다.

### 5.2 15-double native post-effect 실제 필드 위치

```text
[0] bodyA id (-1 => static world)
[1] bodyB id (-1 => static world)
[2] force_amount = contact-force EVENT total_force_magnitude (NOT contact impulse)
[3..5] local_normal_a
[6..8] local_normal_b
[9..11] local_point_a
[12..14] local_point_b
```

- record 길이 15, 모든 값 finite, body ID 정수 범위, sentinel 검증. Raw 단위가 명시되지 않은 `force_amount`를 joules/J·step impulse라고 정의하지 않는다.
- `force_amount`는 pinned Rust 소스에서 같은 이벤트의 모든 `point`에 복제된다. 따라서 같은 body pair/tick의 합산이 기본 모델이 아니다.
- pinned Rust 소스상 배열은 **최대 100개 native effects**, 이미 `total_force_magnitude<0.1` 필터가 적용. 실제 배포 바이너리도 동일한지는 해당 버전 게임 로그와 대조한다. `NativeCollisionTelemetry.CAPACITY=2048`과 100 제한은 별개. PDD의 `dropped=0`만으로 native 쪽 무손실을 입증하지 못한다.
- `bodyId=-1`은 Sable native source에서 Plot runtimeId가 없는 collider-side sentinel이다. 기본 게임 모델에서는 정적 후보로 분류하되, **반드시 실제 `ServerLevel`의 물리 collider라는 생성 경로/scene를 확인한 뒤에만 `STATIC_WORLD` TargetKey를 부여**한다. 단순 `-1`만으로 정적 World의 정확한 BlockPos가 확인된 것은 아니다. 다른 차원 같은 runtime ID는 구별한다.
- `local_point`의 정확한 변환에는 해당 **접촉 종료 시점 body/collider pose와 CoM offset**이 필요하다. `world = body.translation+body.rotation*localPoint`를 source 검증 없이 일반화하지 않는다. 하나의 15-array와 tick-end pose가 동일 step이라는 보증도 없다.

### 5.3 타깃 결정의 세 단계

1. **OWNER**: native body ID에 대응하는 Sable `ServerSubLevel`의 runtime ID/UUID, dimension, lifecycle epoch를 stable owner mapping으로 찾는다. `-1`은 해당 scene의 정적 콜라이더측 sentinel이며 실제 ServerLevel static owner인지 추가 검증한다. `unknown`은 다른 plot 자동 선택 금지.
2. **BLOCK CANDIDATE**: original callback의 owner-local `hit BlockPos`를 확보했다면 그 프레임에서 사용; 기본 post-effect는 localPoint를 **입력 frame이 검증된 조건**에서 변환/근처 local voxel 후보로 생성. 찾지 못하면 `TARGET_UNRESOLVED`.
3. **BLOCK VALIDATE**: 실제 `getBlockState`/loaded/air/fingerprint/BE·보호 예외를 검사하고 최종 `TargetKey`로 확정. 좌표 값의 크기, 두 callback point coincidence, 먼 함수의 임의 block chooser로 승격 금지. 후보 다중이면 기하로 **유일**하게 좁힐 수 있을 때만 승인, 아니면 원장 타깃 미확정.

`TargetKey=(dimension,space,stablePlotId?,ownerLocalPos,blockFingerprint,ownerGeneration)`. 사후 `MutationIntent`의 epoch/state와 반드시 비교한다. native 2개 body side를 보고 A/B block이 모두 확정된 경우만 양측 손상 후보를 만든다; 한 측만 확정되면 **그 측만 유한 예산을 차감할 수 있는지** source 정책으로 보수 분배한다.

### 5.4 이벤트 모델/승격 등급

| 등급 | 입력 | 직접 피해 / 방향성 변형 | 결론 |
|---|---|---|---|
| `SOLVER_NATIVE_VERIFIED` | 향후 승인된 scene-step + 원본 block pair + solver-contact impulse/pre-speed | 둘 다 가능, 지표/단위 검증 후 | 선택적 정밀 경로, 현재 ABI 미제공 |
| `CONTACT_GEOMETRY_VERIFIED` | post-effect local point + pose/CoM frame + 독립 position 검증; 명확한 source | 검증된 게임 예산에 따른 damage, 방향은 충분한 geometry로만 | 물리 impulse는 여전히 미확정 |
| `CORROBORATED_GAME_IMPACT` | 실제 Sable effect/callback, unique owner block, 독립 new-impact/접근 gate, bounded 정책 | **보수적 게임피해 가능**, 미확정 방향은 **비방향성 stress 또는 damage-only** | 0.3.0 게임 기본 경로 |
| `OBSERVATION_ONLY` | 단순 callback·force 이벤트·bodypair, 신규 타격/target 불충분 | Q=0 | 디버그 표본 |
| `REJECTED` | owner ID/time/invalid/overflow/position invalid 등 | Q=0 | 손상/커밋 절대 금지 |

**Quality는 증거의 라벨이지 원본 물리 정확도를 바꾸는 코드가 아니다.** 서로 다른 등급의 같은 타격을 중복 발급하지 않는다. native 15-double 상한/필터로 기록 누락이 예상되는 환경에서 *전체 충돌 커버리지*를 약속하지 않는다.

### 5.5 회전·다중 매니폴드와 좌표계 정책

- 회전 물리 계산 자체는 Sable solver가 수행한다. PDD가 필요한 것은 `타격된 실제 블록`과 `승인된 게임 예산`이다. **회전 각속도 벡터의 정확한 수집은 모든 버전의 공통 필수가 아니다.**
- 안정된 법선을 제공할 수 있으면 방향성 stress/deform을 허용. 그렇지 않으면 손상·주변 비방향성 stress는 가능하지만 **실제 격자 이동 방향을 추측하지 않는다**. 변형 작업이 나오는 버전은 이 사건을 `DEFORMATION_DIRECTION_UNAVAILABLE`로 집계한다.
- 동일 body pair에서 서로 직교하는 두 법선은 정상일 수 있다. 다른 레코드 간 `commonDot>=0.98` 일괄 조건으로 유효 사건을 부정하지 않는다. **그렇다고 각 레코드를 별개 충격 예산으로 증식시키지도 않는다.**
- 원본 `hooks.rs`는 `velocity.abs()`를 callback으로 전달한다. 따라서 **그 스칼라로 상대 접근의 방향을 알아낼 수 없다.** 회전-only signed 접근은 Java 바디 중심 병진속도로 대체 불가. 기하와 시간 연속성 등 신뢰성 있는 신규 타격 판별이 없는 경우 `OBSERVATION_ONLY`.
- 1.0.0은 **실제 게임 동작/결과의 기능적 합격**을 목표로 한다. `source=CORROBORATED_GAME_IMPACT`는 원자적 solver energy의 합격 증거가 아니지만, 충돌·피해의 사용자 체감 목적을 위해 허용되는 명시적 게임 모델이다.

### 5.6 원본 callback 보존 특칙

- PDD baseline `SableUniversalCallbackMixin`이 `null` 원본을 `new ObservingCollisionCallback(null)`로 바꾸면 Sable native hooks `contact_events` 생성 분기가 변경된다. 원본 호출 횟수/결과가 같다는 뜻이 아니다.
- 기본 경로는 **수동 collider bakery callback 주입이 필요 없는** 기존 후처리 배열 observer 사용. 이미 callback이 존재하는 특수 블록은 원본 delegate exactly once, 동일 return reference/value, 동일 예외 전달을 보존하는 observer를 허용하되, 관측 실패가 게임을 중단시키지 않도록 분리한다.
- Sable Collision Damage/SableImpact가 함께 설치될 경우 각각의 Mixins가 callback source를 변경할 수 있다. compatibility test에서 callback factory result의 모드 우선순위와 실제 결과를 확인해야 하며, PDD는 자기 wrapper로 다른 모드 구현을 가로채지 않는다.

## 6. 충격 예산·질량·원장 — **물리량과 게임 지표 완전 분리**

### 6.1 우선순위별 지표 변환

```text
if SOLVER_NATIVE_VERIFIED and per-point valid J and newImpact:
    Q_game = policy.F_solver(J, validSpeed?)        // not J itself
else if CORROBORATED_GAME_IMPACT and valid owner/local impact event:
    Q_game = clamp(policy.F_proxy(observation, optional validatedMass/speed), 0, groupCap)
else:
    Q_game = 0; noDamageReason=...
```

- 사용자 수치(P-01/P-16/P-17)는 보수적 프로토타입 프로필 또는 승인된 운영 프로필로 유지; *실제 단위 미확정*인 수치를 `J`/`Joule`로 표현하지 않는다.
- 15-double `[2] force_amount`의 **다중 point 합계 또는 per-point physical energy 변환은 금지**. 별도 정상화된 범위의 Sable 물리 이벤트 강도 **게임 프록시 입력 후보**로만 사용할 수 있으며, 지속 지지 이벤트·접선 접촉·반복은 반드시 zero 또는 상한 조정 게이트를 거친다.
- `MassData.getInverseNormalMass`는 사용하기 전에 plot-local `point`/`normal`과 owner/상대 static 여부를 증명해야 한다. 이 정보가 없을 때는 mass 대신 **mass-free game profile**를 사용하거나 Q=0. 질량을 0/무한으로 가짜 채우지 않는다.
- 회전 관련 signed velocity가 확보되지 않았더라도 `newImpact`로 검증된 사건은 bounded gameplay 예산을 허용하지만 정밀 `kinetic energy`로 표기 금지. **지지/정지 접촉이 구별되지 않으면** 피해 발행하지 않는다.
- 지원 가능한 proxy 예시 함수는 `Q_game = min(C_group, k_proxy * max(0,F_event−F_threshold))` 같은 **게임 스케일 함수**다. 이것은 force 이벤트를 joule로 환산하는 물리식이 아니고, F가 중복 기록되는 경우 `max`/bounded representative reduction을 사용한다. `k_proxy`, `F_threshold`, `C_group`는 §23의 **명시적 개발 프로토타입 기본값**으로 우선 구현하고 각 기능 버전의 시험 결과로 운영 정책을 확정한다. 단, Sable 자체의 실제 물리 단위라고 해석하지 않는다.

### 6.2 단일 집계/중복 처리

- `SourceObservationKey=(serverRun, dimension, physicsTickObserved, sourceKind, canonicalBodyPair, recordOrdinalWithinBatch)`은 **raw 관측 중복/진단**의 임시 키다. `recordOrdinal`은 contact ID가 아니다.
- `GameEpisodeKey=(serverRun,dimension,ownerA,ownerB,contactPhase/continuousEpisode)`는 짧은 수명 FSM/TTL, 소유자 세대, 신규 접촉 증거로 갱신한다. 임의 다른 tick의 진짜 재타격은 허용해야 하므로 완전 bodypair 영구 latch 금지.
- `GroupCredit=(episode,bodypair,policyBudgetCap)`: 하나의 observation source가 동일 사건에서 record 10개·callback 2회·side A/B 2개로 쪼개져도 게임 예산 1회/그룹 상한. 다중 실제 타격을 하나로 합칠 위험은 별도 로그/테스트에서 측정한다.
- **실제로 동일 사건임이 불명확한 다른 source 종류 사이**를 단순 tick/bodypair로 동등하다고 우기지 않는다. 같은 source의 capture 중복을 정확히 방지하고, 다른 source가 겹치면 conservative priority policy를 적용해 과대 damage를 막는다.
- A/B 개별 damage share 합은 `<= Q_group`; 양측이 서로 독립적으로 전체 Q를 받는 모델을 기본값으로 삼지 않는다. 원본 `ImpactEpisodeLedger`를 source-key/owner-generation에 맞게 교체 또는 확장한다.

### 6.3 안정 접촉에 의한 지속 피해 금지

- `CONTACT_START`는 독립 collision lifecycle 또는 연속적 분리/다시 접근/정당한 speed/effect 변화 증거가 있을 때만 승인. Sable `handle_collision_event`는 이 pinned 소스에서 빈 함수이므로, **Sable가 PDD용 start/end 신호를 자동 전달한다고 가정하지 않는다**.
- PDD는 짧은 Episode FSM `NEW / ACTIVE / QUIET / REARMED`와 Sable 실제 event/velocity 증거를 조합한다. `ACTIVE`의 동일 방향 지지/슬라이딩에는 신규 credit=0. `QUIET`→`REARMED`는 지정 시간 경과만으로 자동 충격 승격 금지, 독립적인 재접근 근거 필요.
- `100` native effect cap/filtered feed 때문에 *이벤트 미수신=실제 충돌 분리*는 아니다. 이를 재장전 기준으로 삼으면 resting contact 파괴 위험이 있으므로 **미수신 단독 rearm 금지**.
- 재타격 인과를 결정할 수 없는 상황에서는 `AMBIGUOUS_CONTINUITY`와 Q=0. 플레이테스트에서 필요한 충돌이 누락되면 실증된 speed/collision state 출처를 추가하거나 운영 정책을 선택한다. 임계 완화만으로 정밀 증거를 만들어내지 않는다.

### 6.4 원장/손상 정산 불변식

- `Q_group>=0` finite; `ΣtargetShare<=Q_group`; `direct+elasticAbsorbed+plastic+stress+unused`의 합은 `Q_group` (허용 오차/양자화 정책 명시).
- elastic 완충은 노드별 예산 유입마다 **정해진 1회** 적용; direct/plastic/stress를 새 credit로 다시 만들지 않는다.
- `J_raw`, `F_raw`, `v_observed`, `Q_game`, `sourceQuality`, `newImpactEvidence`, `episodeState`, `targetAccuracy`, `budgetPolicy` 각각 다른 필드로 로깅.
- 다른 모드의 폭발/엔티티 게임 피해 값은 별도 `DamageSource`로 합류. 같은 사건이면 `causeGroup`으로 게임 예산 중복 억제, 물리적 동일성 추정 금지.

### 6.5 즉시 구현 가능한 수학의 경계

- `ImpactMath.exact`의 `ω×r`·텐서 회전과 유효질량 수학은 단위 테스트 **reference-only**로 보존. `ImpactLiveAudit`의 translation-only proxy 및 `NativePlotDirection`의 전서브스텝 단일 법선 gate는 **0.3.0 damage 승인 판단에서 제외**.
- Native read-only exporter가 미래에 승인되면 raw solver J를 1회 선택하고 기존 proxy Q를 동시에 합산하지 않는 선택형 backend로 연결한다. API 부재는 *정밀 성능 향상 항목*의 미완료이지 재료/손상 기능 개발 정지 사유가 아니다.

## 7. 물성 등록·피해·변형·파괴 모델

### 7.1 저장 단위·물성의 의미

| 값 | 유효 범위 | 물리/게임상의 역할 | 저장 수명 |
|---|---:|---|---|
| 경도 `H` | `u8: 0..255` | 동일하게 배분된 직접 손상 예산에 대한 저항 | 블록 정의/상태 정책 캐시 |
| 연성 `D` | `u8: 0..100` | 파괴 이전에 허용되는 영구 변형 용량 | 블록 정의/상태 정책 캐시 |
| 탄성 `E` | `u8: 0..100` | PDD 피해 예산의 1회 충격 완충. 직접 손상 및 이웃 응력 감소에 동시에 기여 | 블록 정의/상태 정책 캐시 |
| 파괴 임계 `T` | `u8: 1..255` 또는 특수 불괴 플래그 | 누적 손상이 도달해야 하는 파괴 기준. `H`와 구별 | 블록 정의/상태 정책 캐시 |
| 누적 손상 `damage` | `u8: 0..255` | 좌표별 파괴 판정 상태 | 좌표별 희소 영속 상태 |
| 누적 변형 `deformation` | `u8: 0..255` | 좌표별 영구 변형 이력/능력 소진 | 좌표별 희소 영속 상태 |
| 균열 표시 `crackVisual` | `u8: 0..255` | 시각 정보, 물리 손상과 분리 | 좌표별 또는 표시 세션 |

각 숫자의 **논리 저장 폭**은 1바이트 이내다. Java 객체의 실제 힙 점유량을 뜻하지 않는다. `MaterialDefinition`에는 프로필 ID·형상/기능 분류·불괴 정책·필요한 파생 계수를 둘 수 있지만, 좌표마다 공유 물성 전체를 복제하지 않는다.

- `H=255`는 무적이 아니다. `UNBREAKABLE`, `IMMUNE`, 보호/거부 정책만 절대 파괴를 금지한다.
- `D=100`은 쉽게 찌그러진다는 뜻이 아니라 **파괴 전에 큰 영구 변형을 허용할 수 있다**는 뜻이다. 항복 개시 임계 `yieldThreshold`는 별도 재료 특성이다.
- `E=100`은 최대 완충이지만 PDD 충돌 예산의 100% 소멸 또는 Sable의 반발/운동 응답 재계산이 아니다. `E`는 현실의 영률·반발계수·충격 흡수 에너지 실측값과 같지 않다.
- 재료 공학의 연성, 취성, 항복, 인성은 서로 다르다. PDD의 수치는 **Minecraft 게임용 피해/변형 모델**이며 바닐라 수치만으로 실제 물성을 측정했다고 주장하지 않는다.

### 7.2 물성 초기화·재료 계층·조회 우선순위 (설치 블록 전수 스캔 금지)

**기본 설계:** `재료 계열(MaterialFamily) + 구체적 재료(MaterialProfile) + 상태·가공 보정(StateModifier)`를 사용하고, 같은 결과를 얻을 수 있는 단순 블록에는 계층 객체를 늘리지 않고 **최종 캐시 값만 저장**한다. `METAL` 하나의 연성/탄성 상수로 모든 금속을 결정하지 않는다. 순철·강철·구리 등은 서로 다른 구체적 프로필이고, 부식·가공·강화는 필요할 때만 별도 수정자다.

1. 서버 초기화 및 데이터팩/레시피 리로드 시 **`BuiltInRegistries.BLOCK`의 등록 블록 ID**를 열거한다. 블록별 가능한 대표 기본 상태를 해석하며, 실제 `BlockState`에 따라 원시 속성·형상·정책이 달라지는 경우에만 해당 차이를 추가 캐시한다. 임의 월드 좌표가 필요한 API 결과를 없는 월드 문맥으로 확정하지 않는다.
2. 입력: 안전하게 조회 가능한 블록 채굴 경도 `s`, 폭발 저항력 `b`, 바닐라의 명시적인 적정 도구/채굴 등급 태그, 재료 관련 태그, 충돌 형상·기능, 출력/재료 해석이 가능한 제작법. **도구 등급은 재료 강도 자체가 아니라 아이템 드롭·채굴 제한의 약한 보조 힌트**다.
3. 우선 적용 순서: `inferred fallback → material family/profile → optional StateModifier/BlockStateOverride → explicit block override → user/datapack override`. 최종값을 `EffectiveMaterialContext`로 합성한다. 동일 우선순위 충돌은 안정적인 키·명시적 우선순위로 결정하고 진단한다. 이후 DVT 3.0의 동적 `MaterialModifier` 우선순위는 별도 설계에서 확정한다.
4. 태그/레지스트리 식별자가 명시하는 재료를 우선 신뢰한다. ID에 `steel`, `rust`, `gold` 등의 문자열이 들어갔다는 사실만으로 재료를 **확정하지 않는다**. 제작법은 여러 후보에서 신뢰도를 평가하는 보조 근거이며, 투입 아이템 비율을 물리적 질량·부피 분율로 자동 간주하지 않는다. 미지원 레시피·비정상 속성은 원소별 fallback 후 이유를 남긴다.
5. 공기·유체·불괴 블록·교체 가능 장식·충돌 형상이 없는 블록은 **별도 target eligibility / protection policy**를 적용한다. `H=0`인 레드스톤 가루·잔디도 실제 접촉이 없으면 Sable 충돌이 생겼다고 가정하지 않는다. 별도 폭발/투사체 피해 대상 여부는 source 정책으로 판정한다.
6. 물성은 immutable registry를 **세대별 원자적 교체**한다. 이미 승인된 사건은 사용한 `materialGeneration`과 `algorithmVersion`을 보존해 재로드 도중 다른 버전의 물성과 섞이지 않도록 한다. 좌표별 누적 손상은 물성 캐시와 분리한다.

**세분화 예:** `METAL/IRON`과 `METAL/STEEL`은 서로 다른 기본값을 가진다. `IRON + CORRODED`는 부식 상태 보정을 받는다. 같은 블록의 약해진 구조가 쉽게 눌리는 현상은 `yieldThreshold` 저하로 표현할 수 있으며, 반드시 `D` 증가를 뜻하지 않는다. 강철은 조성/열처리에 따라 고연성과 저연성 모두 가능하므로 재료명만으로 일방적 순위를 강제하지 않는다. `WHEEL/COMPLIANT` 같은 검증된 **기능 프로필**은 단순 금속 평균보다 우선 적용할 수 있다. 단, 실제 Aeronautics의 철·강철·부식 블록·바퀴 블록 ID 및 구체적 속성은 대상 레지스트리/게임 증거로 확인한 후에만 매핑한다. 부식이 월드 내 시간 경과로 자동 진행되는 기능은 DVT 3.0 범위이며 PDD 1.0에서 선행 구현하지 않는다.

### 7.3 경도 H와 파괴 임계 T의 공통 원시 입력·독립 보정

경도는 바닐라 강도 속성을 기준으로 자동 추정한다. `s`와 `b`는 **비음수·유한 값일 때만** 아래 후보식에 투입한다. 불괴 파괴 속도(음수)와 강제 보호 정책은 먼저 분기한다.

\[
S=\operatorname{clamp}\left(\frac{\ln(1+s)}{\ln56},0,1\right),\qquad
B=\operatorname{clamp}\left(\frac{\ln(1+b)}{\ln1201},0,1\right)
\]
\[
H_{base}=\operatorname{round}\left(255(0.60S+0.35B+0.05R)\right)
\]
\[
H=\operatorname{clamp}(H_{base}+\Delta H_{material}+\Delta H_{state},0,255)
\]

- `R∈[0,1]`: **실제 확인된** 요구 도구 등급에서 얻는 약한 보조 계수. 명시적 제한이 없다면 `R=0`; 보정 후보는 나무 0.15, 돌 0.35, 철 0.65, 다이아몬드 이상 1.0이다. 각 등급의 실제 판별 태그/버전 API는 구현에서 검증한다. 판별되지 않은 모드 도구의 등급을 이름으로 꾸며내지 않는다.
- 가중치 `0.60/0.35/0.05`, 정규화 기준 `55/1200`, 재료/상태 `ΔH`는 **밸런스 정책 후보**다. 원래 1.0.0의 `0.65/0.35`, `ln51`은 이 후보로 대체하지만, 실제 게임 물성의 실측 사실로 간주하지 않는다.
- 레드스톤 가루·짧은 잔디 등 최소 강도형 장식 블록은 `H=0`, `T=1`의 기준 예외가 될 수 있다. **보강된 심층암**은 채굴 가능한 기준 상한으로 `H=255`, `T=255`를 명시적 앵커로 지정한다. 기반암 등 불괴 블록은 `H=255` 대신 **불괴 정책**이 파괴를 막는다. 앵커 외 수치는 단정하지 않는다.
- **블록의 형상은 재료 경도에 직접 곱하지 않는다.** 유리판·반 블록·철창·바퀴 등의 충돌 접촉 면적/구조적 취약성은 `contactGeometry`, `shapeFactor`, `T` 및 별도 사건 배분 정책에서 처리한다. 형상이 낮다고 금속 자체의 경도를 낮추지 않는다.

파괴 임계값의 **무보정 초기 후보식**은 아래와 같다.

\[
h=H/255,\qquad
T_{base}=\operatorname{round}\left(1+254h^{0.65}\right)
\]
\[
T=\operatorname{clamp}\left(T_{base}+\Delta T_{material}+\Delta T_{state}+\Delta T_{shape},1,255\right)
\]

`T`는 공통 기본값을 `H`에서 시작할 수 있지만, **최종값은 재료 파괴 특성·구조 형상·상태의 별도 보정**을 받는다. 같은 `H`라도 취성 유리와 금속은 서로 다른 `T`를 가질 수 있다. 기본식만으로 모든 재료의 파괴인성을 설명한다고 주장하지 않는다. `T_base` 지수 `0.65`, 모든 `ΔT`는 POLICY다. 제작법/재료 힌트로 두 값이 보정되더라도 피해 계산의 `H`와 파괴 조건의 `T`를 혼합하지 않는다.

### 7.4 탄성 E의 두 역할과 에너지 원장 내 1회 완충

**기준 앵커:** 흑요석 `E=0`, 슬라임 블록 `E=100`. `E`는 0~100을 블록 정의/상태별로 캐싱하고, 재료군·구체적 재료·가공/상태·기능 프로필로 산출한다. 바닐라의 경도/폭발 저항만으로 충격 완충률을 결정할 보편적 물리식은 없다.

기본 완충곡선은 다음 **조정 가능한 후보**로 정의한다.

\[
e=E/100,\qquad
f_E(e)=r_{min}+(1-r_{min})(1-e)^\beta,
\quad 0<r_{min}\le1,\ \beta>0
\]
\[
Q_{active}=Q_{incoming}f_E(e),\qquad
Q_{absorbed}=Q_{incoming}-Q_{active}
\]

- 후보 기본 파라미터: `rMin=0.15`, `beta=1.5`. 두 값은 시험 전 **POLICY**로서 튜닝 가능하다. `E=0`이면 전량 활성화되고, `E=100`에서도 비율 `rMin`만큼의 PDD 피해 예산이 남는다. 바퀴·고무형 블록은 확인된 기능 프로필을 통해 높은 E를 가질 수 있지만, 기본 보호/무적은 아니다. 예시 후보 `E=35`(일반 금속), `E=85`(고탄성 바퀴)는 **실제 Aeronautics 물성 확인값이 아니다**.
- **PDD의 손상 계획 예산만 완충**한다. Sable이 계산한 접촉 힘·임펄스·반발 속도·변형된 질량을 바꾸거나 `applyImpulseAtPoint`를 호출하지 않는다. `ABSORBED`는 solver 에너지가 열/탄성 에너지로 실제 변환됐다는 뜻이 아닌 **PDD 내부 원장 처리 분류**다.
- 원장: `Q_incoming = Q_absorbed + Q_active`; 이어 `Q_active = Q_direct + Q_plastic + Q_stress + Q_unused`. 동일 시점·동일 유입분에 `f_E`를 두 번 적용하거나, 옛 `Q_i(1-e_i)` 감쇠를 추가로 적용하지 않는다. 각 debit은 하나의 원장 계정에서만 차감한다.
- 주변 노드에 도착한 `Q_stress`는 해당 노드의 **새로운 유입 예산**으로 취급하고 그 노드의 `E`를 한 번 적용한다. 복수 경로 유입은 노드에서 합산한 뒤 처리한다. 동일 사건의 같은 노드를 여러 번 재진입시켜 원래 예산을 복제하지 않는다. 회수·순환·최소량·방문 상한은 8절을 따른다.
- **정상 접지 보호:** 정지 접촉이나 동일 episode의 다중 콜백에는 신규 정상 접근 예산을 만들지 않는다(6.3절). 높은 E는 착지 충격 피해를 완화하지만 지면에 닿아 있다는 이유로 반복 피해를 만들지 않는 주된 보호는 episode 계약이다.

### 7.5 에너지→손상 및 경도

\[
h=H/255,\qquad f_H(h)=f_{min}+(1-f_{min})(1-h)^\gamma
\]
\[
D_{raw}=F(Q_{direct}, materialProfile)f_H(h),\quad
0<f_{min}\le1,\;\gamma>0,\;F(0)=0
\]

`Q_direct`는 **7.4절에서 탄성을 한 번 적용한 `Q_active` 중 직접 손상으로 배정한 예산**이다. 이 식에 `f_E` 또는 연성 보정을 다시 곱하지 않는다. `F`의 개발 기본값은 §24.1의 `F(Q_direct)=Q_direct×damageGain`, `damageGain=1.0`으로 확정하여 단조·유한·비음수 검증한다. 사용자 값 확정은 해당 기능 버전 이후이며 설정으로 외부화한다. `H`가 높을수록 동일 `Q_direct`의 피해가 증가하지 않아야 한다. `H=255`도 불괴는 아니며 `UNBREAKABLE`이 우선한다. 비정상 부동소수값은 `INVALID_ENERGY`로 거절하고 계획 생성을 중단한다. 손상 수치 `D_raw`의 `D` 표기는 연성 `D`와 다르므로 구현에서는 `rawDamage`처럼 혼동 없는 이름을 쓴다.

### 7.6 미세 피해 양자화 — 반복 접촉 과대평가 방지

양수의 모든 손상을 `ceil`해 바로 1 damage로 만들면 미세 접촉의 반복으로 비현실적인 붕괴가 발생한다. 소수 누적이 필요할 때는 `damageU8` 밖의 **선택적 부가 1바이트 remainder**를 별도 희소 공간에 저장하거나, 손상 누적 이외의 동등하고 결정론적인 정책을 사용한다.

```text
fixedPoint = damageU8 * scale + remainder
increment = floor(max(0, damageFloat) * scale)
next = min(255*scale + (scale-1), fixedPoint + increment)
damageU8 = floor(next / scale)
remainder = next % scale
```

`scale=256`은 §23의 **구현·검증용 기본값**이며 운영자가 해당 기능 완료 시 수정할 수 있다. 미세손상 누락을 피하려면 추가 희소 `remainder` 1바이트를 개발 기본값으로 적용한다. 세 상태 byte 외 추가 1바이트의 운영 최종 허용 여부는 P-04 확정 대상이다. `crackVisual`을 remainder와 같은 byte에 중복 인코딩하여 두 값의 의미를 훼손하지 않는다. 작은 피해 보존을 위해 월드 전체에 별도 상태를 할당할 필요는 없다.

### 7.7 연성 및 영구 변형

**연성 기준 앵커:** 금 블록 `D=100`, 흑요석 `D=0`. `Ductility`는 `plasticCapacity`와 허용 `DeformationIntent` 범위를 결정하며, **항복 개시값 `yieldThreshold`는 별도 재료/상태 프로필**로 정한다. 연성이 높다는 이유로 무조건 작은 충격에서 먼저 찌그러지는 것은 아니다. 소성 변형 가능 여부는 `Q_plastic`과 항복 조건에 따른다.

`D`는 경도 `H` 또는 탄성 `E`처럼 직접 손상 감소 계수로 중복 적용하지 않는다. 같은 금속이라도 부식·합금·열처리에 따라 `D`, `yieldThreshold`, `T`는 독립 보정한다. 녹슨 철을 쉽게 찌그러지도록 구현할 때는 `yieldThreshold`를 낮출 수 있지만, 부식이 연성을 증가시킨다는 물리적 단정은 금지한다. **도구·경도·폭발 저항으로 연성까지 직접 역산하는 식은 채택하지 않는다.** 초기 재료 프로필 후보와 복합 재료의 제작법 가중 평균은 검증 전 POLICY이고, 제작 재료 개수를 실제 구성 비율로 단정하지 않는다.

초기 연성 후보 프로필(게임 밸런스 예시, 검증·사용자 확정 전):

| 대표 재료 | 예시 D | 구분 |
|---|---:|---|
| 금 블록 | **100** | 확정 앵커 |
| 연질 구리 | 85 | 후보 |
| 일반 철 | 70 | 후보 |
| 나무 판자 | 35 | 후보 |
| 흙 | 25 | 후보(토양의 소성 거동을 게임상 D로 근사) |
| 일반 돌 | 10 | 후보 |
| 유리 | 3 | 후보 |
| 흑요석 | **0** | 확정 앵커 |

서로 다른 재료가 실제로 복합 구성된 것이 확실하고 기여 가중치 `w_i>0`의 의미가 명확할 때만 `D_mix = (Σ w_i D_i)/(Σ w_i)`를 *fallback 후보*로 사용할 수 있다. 합금·기계·타이어를 제작법 재료 개수만으로 평균내지 않는다.

```text
capacity = clamp(round(255 * D / 100), 0, 255)       // §24.1 DEV 기본 모델
yieldQ = max(0, 8 * (1 - D / 200))              // 재료 override 우선
plasticDemand = max(0, Q_plastic - yieldQ)      // verified direction required for actual move
if movable && plasticDemand>0 && oldDeformation < capacity:
    plan safe-grid DeformationIntent + state update
else:
    apply configured blocked-deformation fallback (state-only/absorbed/damage)
```

**실제 변형은 상태 숫자 증가만으로 완료되지 않는다.** PDD 1.0.0은 서버 world/plot에서 **실제 위치 또는 명시적 blockstate 변화를 일으키는 안전한 적어도 하나의 지원 유형**을 기능 합격 대상으로 한다. 일반 블록은 격자에 속하므로 자유 곡면/0.1칸 연속 휨을 요구하지 않는다. 이동할 공간이 없는 경우, BE/운동 부품/접착 연결을 무사히 이전할 수 없는 경우에는 `MOVE_UNSAFE` 및 POLICY에서 정한 fallback을 따른다. 구조물 BE를 소실시키고 '변형 성공'으로 기록하지 않는다.

### 7.8 파괴 조건

`damageU8 >= failureThreshold`이고 `UNBREAKABLE/IMMUNE/VETO`가 없으며 해당 좌표의 fingerprint가 일치하면 `BreakIntent`로 승격한다. 파괴 임계값 `T`는 7.3절에 따라 H에서 출발할 수 있지만 **재료·상태·형상 보정 후 독립적으로 판정**한다. 기본 월드와 plot의 파괴/드롭 소유자를 분리하고, 원본 폭발/사용자 채굴/다른 모드와 경쟁할 때는 동일 fingerprint당 **최대 1회 실제 파괴/드롭**이 허용된다. 보호 대상이 전파 그래프의 통로라는 이유만으로 보호 무시나 우회 파괴를 하지 않는다.

### 7.9 1.0.5 source 품질과 물성 계산의 결합

- 물성 함수는 `Q_game` 단일 입력을 받으며 `force_amount`, 회전속도, Sable contact 수, 누적 callback 횟수를 몰라도 동작해야 한다.
- `CORROBORATED_GAME_IMPACT`의 유한·한정된 Q는 H/T/D/E 및 직접 손상 모델에 합법적 입력이다. 이는 *게임 에너지/예산*이고 SI Joule이 아니다. 동일 정책 프로필이라도 native J 경로로 승격되면 두 입력을 더하지 않는다.
- 방향이 불명확한 event도 direct damage·비방향성 stress를 만들 수 있다. 반면 direction-dependent plastic voxel shift는 검증된 source 방향이 없으면 **보류/abort**, 임의 world face를 선택하지 않는다.
- 손상 상태의 byte 저장, 양자화, 정의별 물성 캐시, 특수 재료/BE 예외 및 안전 커밋은 source 정확도 등급과 무관하게 유지한다.

## 8. 주변 응력·구조물 연결성·파편

- 초기 그래프는 **실제 피해 대상 `BlockTarget.localBlockPos`를 씨앗**으로 삼아 동일 공간의 6면 공유 이웃을 사용한다. **교차 검증된 A/B 접촉점 좌표가 없어도** 동일 공간 내부 전파를 수행할 수 있다. `STATIC_WORLD`와 `SABLE_PLOT` 좌표를 단순 정수 덧셈으로 직접 이어 붙이지 않는다.
- **접촉점·법선이 검증된 경우에만** 전달 방향 가중치를 접촉 방향으로 편향시킨다. 기하가 미확정이면 연결된 유효 이웃에 대한 **비방향성 정규화 가중치**로 배분한다. 유효 이웃이 없으면 해당 응력 예산을 출처가 표시된 `UNUSED`/`ABSORBED_BY_POLICY`로 처리한다. 정확 접촉점 부재를 이유로 국소 응력 전파 자체를 막지 않는다. 이 규칙은 **이미 승인된 단일 episode 예산**이 있다는 전제에서만 적용된다.
- `StressGraphProvider`는 장래 접착제·엔진 부품·기계 축·연결 구조를 해석할 수 있는 확장 경계다. PDD 1.0에서 모든 Create 기계 연결 규칙을 새로운 자체 물리 엔진으로 재구현하지 않는다.
- 각 노드의 신규 유입 예산 `Q_i`는 **7.4절의 `f_E(E_i/100)`로 한 번만 완충**한다. 그 결과의 `Q_active,i` 가운데 응력에 배정한 `Q_stress,i`만 이웃에 전달한다. **종전 `Q_i(1-e_i)` 상한식은 폐기**한다. 탄성 때문에 먼저 감소한 `Q_active,i`에 동일 탄성을 다시 곱하지 않는다.
- 이웃 가중치 `w_ij >= 0, Σ_j w_ij <= 1`에 대해 `Q_{i→j}=Q_stress,i·w_ij`로 분배한다. `Q_absorbed,i`, 직접 손상, 변형, 이웃 전달, 미사용 예산은 같은 `Q_i` 원장에서 배타적으로 차감한다. `Q_stress,i` 자체는 `Q_active,i`를 초과하지 않는다.
- 이웃으로 보낸 `Q_stress`는 **이미 원장 내부에서 이전된 동일 예산**이며, 새 episode 예산 또는 최초 credit으로 재계상하지 않는다. 같은 블록이 여러 경로에서 도달해도 총 유입만 정확히 합산하고, 배치별 한정된 처리 단계에서만 **해당 유입에 대한 완충을 한 번** 수행한다. 단순 `visited` bool로 둘째 경로의 에너지를 소실하거나 첫 경로에 예산을 중복 재지급하면 안 된다. 사이클에 동일 원장 토큰을 무한 재투입하지 않도록 event+node+budget 출처를 추적한다.
- 최대 깊이, 최대 방문 블록 수, 최소 전파량, per-episode 및 per-tick budget을 적용한다. limit 도달 시 `ABSORBED_BY_POLICY` 또는 **추적 가능한 deferred work**로 마감한다. 수치 정책은 설정에 둔다.
- 구조물 블록 파괴 후 **Sable의 정상 collider rebuild/heatmap/split 경로**를 이용한다. PDD 임의로 `removeSubLevel()`을 호출해 구조물 전체를 삭제하거나 별도의 파편 물리계를 생성하는 것은 금지한다.
- 파편이 분리/합쳐질 때 기존 손상 상태는 해당 **실제 블록의 새 소유 plot/좌표**로 이전 또는 안전 보류한다. Sable split lineage와 데이터 소유권을 기록하고, 떨어진 파편 둘이 함께 정적으로 보인다는 이유만으로 서로 glue 상태가 되었다고 처리하지 않는다.
- 성능을 위해 대형 함선 전체 바운딩 박스를 매 틱 체적 전수 조사하지 않는다. 충돌 지점 주변 제한 영역과 Sable 공식 분리 이벤트에 반응한다.

### 8.1 1.0.5 응력 씨앗·방향 조건

- `TargetKey`로 확정된 실제 hit 블록을 *응력 씨앗*으로 사용. 응력은 원래 충돌 사건의 `Q_group` 잔액을 **이전**하는 기능이며 새로운 피해 source가 아니다.
- 접촉 normal이 실제 검증되면 해당 owner local frame으로 변환하여 6면 이웃의 방향 가중치를 적용. **불확실한 normal/회전 프레임에서는 6방향 비방향성 보수 profile**을 사용한다. 이 경우 임의 블록 정중앙 법선을 '실측 normal'로 표기하지 않는다.
- 깊이/노드/청크/연산 시간/총 잔액을 bounded BFS로 제한. 동일 에너지의 복수 경로 합산과 cycle 방문, 탄성 1회 처리, 유지할 residual budget을 명시한다.

## 9. 서버 커밋·상태 저장·시각화

### 9.1 엄격한 처리 단계
```text
Sable native solver: original collision/CCD/response (PDD no mutation)
  -> existing Sable processCollisionEffects() receives original clearCollisions(J)[D
  -> PDD observes SAME array once without calling clearCollisions again
  -> validate source record + runtime owner + stable UUID + local candidate frame
  -> validate real owner-local block(s) + original BlockState fingerprint
  -> require independent new-impact evidence, else Q_game=0
  -> choose one bounded GAME_PROXY Q (optional future verified solver J replaces it)
  -> bounded episode/group cap + unique validated target shares
  -> immutable damage/plastic/stress intents; material lookup O(1)
  -> safe server scheduler AFTER physics (world/plot separate adapters)
  -> fingerprint/loaded/permission/BE/pending-split validations
  -> commit once; capture dirty state; reconcile original Sable collider/mass/split
  -> save sparse state and client delta only for APPLIED changes
```

- **PDD는 검증된 작은 후보 영역에서 실제 owner-local 블록을 탐색할 수 있다.** Java post-effect에는 Sable dispatcher의 원본 두 블록 좌표가 없기 때문에 제한적·검증 가능한 후보 탐색은 불가피할 수 있다. 후보가 둘 이상이면 임의로 가까운 다른 Plot/블록을 선택하지 않으며 `TARGET_AMBIGUOUS`로 보류한다. `OtherBlock=UNAVAILABLE`이면 반대편 블록을 임의로 생성하지 않는다.
- 기존 `SablePostPhysicsTick`이나 `NeoForge ServerTickEvent.Post`를 안전 커밋 단계의 후보로 사용할 수 있으나 실제 stepping 종료, 스레드·월드 life cycle/owner 상태, 인접 update 부작용 확인이 필요하다. post 이벤트 수신만으로 안전하다고 단정하지 않는다.
- `MutationTransaction`은 기존 `PREPARED → VALIDATED → APPLYING → APPLIED/ABORTED → RECONCILED`를 유지하고, fingerprint CAS를 통과한 실제 변경만 상태·드롭·클라이언트에 반영한다.
- 원본 Sable 콜백 및 물리 충돌 응답은 Sable 자신이 완료한다. PDD는 같은 충돌 때문에 `Rapier3D.applyForce`/`applyImpulse`를 다시 호출하지 않는다.

### 9.2 정적 세계와 plot은 서로 다른 mutation adapter

`WorldBlockAdapter`는 Minecraft 서버 레벨의 실제 블록 변경 계약을 사용한다. `PlotBlockAdapter`는 Sable plot의 고유 block accessor/수정·갱신 경로를 사용한다. **타깃 키는 실제 블록 접근자에 전달할 좌표로 유지하고, 세계 접촉점 재투영은 변경할 블록을 선택하는 데 사용하지 않는다.** 외부 world pos로 변환한 뒤 plot 내부 블록을 월드에서 `destroyBlock`하는 오류를 막는다. **동일 XYZ라도 targetSpace/owner가 다르면 별도 타깃**이다.

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

### 9.5 충돌 증거의 수명·실제 커밋의 분리
- 기본 `NativeCollisionTelemetry` post-effect record는 **관측 시점/범위가 제한된 Sable effect 작업 데이터**이며 원자적인 native scene-step 보증이 없다. 큐 처리 직후 Java가 owner-local target 및 이벤트 후보에 필요한 최소 필드를 복사한 뒤 버린다. 추후 별도 승인된 Sable solver batch만 자체 step/매니폴드 생명주기에 따라 처리한다.
- `sourceObservationId`·`gameEpisodeKey`·`canonical pair`·`block fingerprint`는 짧은 anti-duplicate/commit TTL만 유지하며 **solver contact ID와 구별한다**. 대형 함선 수천 블록과 매 tick 접촉점 전체를 세계 저장소에 영구 보관하지 않는다.
- plot 분리·조립·질량 갱신·월드 블록 교체 시 이전 관측 tick/owner generation과 runtime mapping을 폐기한다. 확정된 희소 손상 상태만 정식 소유 공간 생명주기 규칙으로 이전한다.
- `mutation=OFF`에서는 어떤 collision 예산 승인/계측이 있어도 `APPLIED`·드롭·균열 패킷을 만들지 않는다. 실제 피해 이후 상태/드롭/클라이언트 일치성을 확인한다.

## 10. 폭발·투사체·엔티티 사건의 PDD 통합

- `DamageSource.VANILLA_EXPLOSION`: vanilla/기존 Aeronautics 폭발의 실제 파괴 결과를 우선 존중한다. 살아남은 블록/물리 구조물에 대한 PDD 추가 손상만 `sourceBudgetId`로 전달한다. 같은 블록의 중복 드롭·중복 파괴 금지.
- `DamageSource.PROJECTILE`: 물리적 속도·질량 정보가 신뢰 가능하면 정규화 예산으로 처리하고, 없으면 **명시적 정책 변환 함수**(`gameDamage→PddEnergyProxy`)를 사용한다. 게임의 health damage 수치를 SI 충돌 에너지로 오인하지 않는다.
- `DamageSource.ENTITY_HIT`: 투사체와 동일한 보호·인과 추적을 적용하되, 모든 일반 생물의 피격이 자동으로 물리 구조물 전체 충격이라는 가정을 금한다.
- 위 source는 각자 이벤트 ID를 가진다. `DamageSource.SABLE_COLLISION`의 동일 사건이 폭발 이벤트를 추가 발생시킨 경우에는 인과 연쇄 ID를 연결하여 예산 소유권을 분리한다.
- **PDD 1.0 범위:** 기본 폭발 잔여 손상, 일반 투사체/엔티티의 블록 피해 통합. **DVT 2.0 범위:** 작동 중 연료기관 충격→폭발, 함선 전체 질량에 따른 *별도* damage amplifier. 엔진 작동 판정과 추가 에너지 생성은 PDD 1.0에서 구현 금지.

### 10.1 각 손상 소스의 독립 provenance와 공통 원장
- **Sable 충돌**: 기본 2.0.6은 §3.2의 **Sable original 15-double post-effect 1회 관측**을 충돌 후보 정본으로 사용한다. 기존 특수 callback은 보조 증거이며 동일 사건에 **별도 피해 credit를 더하지 않는다**. 선택적 원자적 solver batch가 검증되면 그 사건의 15-double proxy와 상호배타적으로 교체한다.
- **Vanilla 폭발**: 원본 Minecraft/NeoForge 폭발 결과 및 생존 대상 블록을 재사용. 이미 파괴된 블록은 PDD로 중복 파괴하지 않으며, Sable의 폭발/구조물 처리 자체를 대체하지 않는다. native contact가 아닌 별도 `EXPLOSION_EVENT` 원인 ID로 공통 원장에 투입한다.
- **투사체/엔티티**: 다른 모드가 검증 가능한 실제 impulse/velocity를 **이미 제공하면 재사용**한다. 제공되지 않을 때만 명시적 `gameDamage→PDD` 정책 함수를 적용하고 이를 `GAMEPLAY_PROJECTILE` 등으로 분리한다. 일반 Minecraft health damage를 Sable solver impact로 속이지 않는다.
- 서로 연쇄된 projectile→explosion→collision은 인과 ID를 연결하되 **실제 별도 사건**의 물리 피해까지 무조건 제거하지 않는다. 단일 블록 실제 변경/드롭은 commit fingerprint CAS로 1회 보장한다.
- DVT 2.0 추가 엔진 폭발/전체 함선 질량 증가 보정은 **본 1.0에서 선구현하지 않는다**. DVT의 새 source adapter가 기존 물성/원장/커밋 코어를 재사용하도록 최소 인터페이스 의미만 고정한다.

## 11. 처리량·큐·서버 성능 계약

### 11.1 1.0.5 실제 Java 관측 경로 최적화

1. **원본 배열 1회 읽기:** Sable `clearCollisions(J)[D`를 PDD에서 새로 실행하지 않고 기존 `processCollisionEffects`의 결과를 관찰한다. 원본 15-double 배열 및 Sable callback 결과는 그대로 반환한다.
2. **고정된 비용:** `R = 15-double post-effect records`, pinned Rust 소스 upstream cap 최대 100건(배포 native 동등성은 게임 계측 필요). PDD 자체의 bounded `R` 큐, 원본 body runtimeId→stablePlotId hash lookup, block candidate 최대 k개, 원장 O(R·k). Native 정확도 미확정 매니폴드 복구 탐색 O(C×R) 금지.
3. **후처리 기록 한계 공개:** pinned Rust 소스의 `total_force_magnitude<0.1` 필터와 `clearCollisions`의 `truncate(100)` 때문에 PDD `dropped=0`이어도 전체 solver contact가 수집된 것은 아니다. 원본을 변경하지 않으며 게임에서 미관측 충돌의 피해를 추정으로 발행하지 않는다.
4. **로컬 block 탐색의 의미:** 기존 native effect는 직접 owner-local block pair를 노출하지 않으므로 *bounded 후보 탐색*은 허용한다. 단, owner/phase/frame/BlockState 검증 없이 가장 가까운 함선 선택하거나 피격 블록을 임의 확정하면 안 된다.
5. **충돌 단계 무변경:** Sable physics thread에서 디스크 IO, 월드 mutation, 무제한 Java callback, JNI 재호출 금지. PDD observer는 검증/복사·큐에 한정하며 JNI callback thread → server safe stage의 ownership을 보존한다.
6. **게임 지표 비용 절감:** 검증된 `F_raw`, episode state, 승인된 최소 body velocity/질량만 사용. 모든 event에 별도 `ω×r`/world inverse inertia/matrix/회전 접점속도 재구성 금지.
7. **MaterialRegistry:** 정의별 H/T/D/E/T/yield를 등록/데이터팩 reload 시 cache; 충돌마다 제작법 탐색/월드 전체 순회 금지. 충돌로 실제 변경된 블록만 sparse state.
8. **bounded planner/commit:** per tick work cap, node/depth, dirty state, retry TTL, unloaded chunks, BE/claims veto, garbage-free diagnostics sample limit. 초과·보류 원인을 기록.
9. **원본 비교:** 고정된 PDD ON/OFF 시나리오에서 Sable callback 수·반환·예외, CCD/solver body pose, split, native effects가 같아야 한다. 이후 PDD 실제 world mutation으로 인한 차이와 관측기 부작용 차이를 분리한다.
10. **조건부 정밀 source:** 업스트림 read-only solver batch를 허가·검증한 환경에서는 native primitive batch를 쓰지만 기본 2.0.6 서버에 설치를 요구하지 않는다. 정밀 source 도입 여부는 프로파일러로 판단한다.

### 11.2 성능·커버리지 실제 계측

| 측정 지표 | 수집 방법 | 잘못된 해석 방지 |
|---|---|---|
| `effectRecordsSeen`, `effectQueuesDropped`, `effectMalformed`, `effectUpstreamSourceMax=100` | Java observer queue, published effect 샘플, debug counters | `native full solver contact count`가 아니다 |
| `ownerKnown`, `ownerStale`, `targetUnique`, `targetAmbiguous` | stable owner + bounded per-block validation | 단순 근접/일치 비율을 타깃 정확도라고 부르지 않음 |
| `newImpactAccepted`, `restSuppressed`, `candidateQ`, `approvedQ`, `duplicateQ` | episode FSM/ledger | 사건 수를 예산/피해 배수로 해석하지 않음 |
| `sablePhysicsStepP50/P95/P99` | 동일 조건 PDD OFF/ON | warmup·optimizer·OS/CPU·tick rate 통제 |
| `pddObserve/owner/ledger/planner/commitMs`, allocations | profiler/structured sampling | 순수 JVM 측정으로 native cost 0을 선언하지 않음 |
| `queueOverLimit`, `worldMutationAbort`, `sparseStateBytes` | 백프레셔, CAS 실패, 청크 상태 | 무음 드롭·메모리 무한 증가 금지 |
| `rotationOnlyCoverage`, `worldPlotCoverage`, `plotPlotCoverage` | 대표 IG-A/IG-B 고정 장면 | 특정 record 미관측은 기능 한계/실패로 보고, 완료로 위장하지 않음 |

**서버 목표:** PvP 10명 이상과 대형 물리 구조물 다중 운행에서 기능·안전성·TPS를 함께 관찰한다. 단일 고정 성능 숫자를 소스 분석만으로 성능 보장값으로 쓰지 않는다.

## 12. DVT 장기 통합 설계 — **필수 데이터 최소화의 의미**

**핵심 결정:** DVT는 Rapier의 `solver J`나 ω를 요구하는 프레임워크가 아니라 **PDD의 game-budget/material/damage/deform/commit 엔진을 재사용하는 게임 시스템**이다. DVT 1/2/3 전체에 공통으로 필수인 것은 `(sourceKind, sourceCauseId, target, Q_game, materialContext, serverCommitEpoch)`이며 source별 물리 값은 입력 어댑터 안에 남긴다.

```java
// 규범적 계약; 현재 컴파일 가능한 외부 Java API의 선언이 아님.
record DamageSource(String kind, String id, long epoch, String causeGroup,
                    String quality, Object sourceMetric) {}
record TargetKey(String dimension,String ownerKind,String stableOwnerId,
                 long ownerGeneration,long blockPos,long blockFingerprint) {}
record BudgetCredit(DamageSource source,TargetKey target,double gameQ) {}
interface SourceBudgetAdapter { /* evidence -> bounded BudgetCredit or rejection */ }
interface MaterialResolver   { /* definition/cache + state/DVT modifier -> H/T/D/E */ }
interface DamagePlanner     { /* BudgetCredit + material + sparse state -> intents */ }
interface SafeCommitter     { /* validated world/plot mutation, BE/claim/rollback */ }
```

- **DVT 1.0 Fracture:** PDD 1.0 damage/plastic/stress/fracture/sparse-save/UI를 호출하거나 통합한다. 별도 collision resolver, own physics solver, double ledger 작성 금지.
- **DVT 2.0 Big Bang:** 기관/연료 폭발 조건은 `EngineFailureSource` → 게임 예산 정규화 → PDD planner. 기관 이력/연료 상태/실제 mass의 선택적 계수는 DVT 전용이며 raw Sable impulse가 필요 없다. 이벤트 규모/연쇄 보호는 bounded cause-group 원장으로 해결.
- **DVT 3.0 Reinforce:** 재료 오버라이드, 부식/수분/보강 효과는 `MaterialModifier`의 DVT 소유 정의 및 블록별 dynamic state로 반영. Sable mass/collider 변경을 요구할 때만 별도 승인된 physics lifecycle adapter를 호출. 서버 snapshot/rollback 및 이미 있는 block property와 충돌 시 precedence를 명시.
- `DamageSource` 원천은 collision뿐 아니라 `vanilla explosion`, `projectile`, `entity`, DVT engine failure도 허용한다. 게임 Q와 material accounting이 공통 단위이므로 **모든 추가 source에 Sable 접점/법선을 요구하지 않는다**.
- 정의별 material과 블록별 dynamic damage/state 분리, callback/event effect의 생명주기 격리, source별 유한 budget을 향후 전체 DVT 빌드가 그대로 사용한다.

## 13. 사용자 운영값과 개발용 기본 프로필 — §23에서 선행 구현

아래는 API 추적 문제가 아니라 **게임 디자인 결정**이다. 구현 버전에서 개발자가 §23의 시험 기본값을 반드시 제공하고 이를 실제 동작·자동시험·인게임 로그에 사용한다. 다만 사용자 승인된 영구 밸런스라고 주장하지 않는다. 사용자가 별도 값을 지정하지 않은 경우는 설정 가능한 **명시적 프로토타입 프로필**을 사용하고 모든 값을 로그·설정 문서에서 노출한다. 데이터 파손 위험이 있는 정책 미결정은 보수적 abort.

| 정책 ID | 선택 사항 | 기술적으로 강제할 범위 | 미결정 시 안전 처리 |
|---|---|---|---|
| `P-01` | 에너지→손상 변환 척도, 최소 충격 | 유한/비음수, `F(0)=0`, 단조 함수 | 명시적 테스트 프로필/보수 임계 |
| `P-02` | 경도 기본식 가중치·정규화·도구 등급·`fMin`,`gamma`, 재료/상태 보정 | `H:0..255`, `fMin` 양수, `gamma>0`, 입력 유효성 | 7.3/7.5절의 노출된 후보값 |
| `P-03` | `T_base` 지수·재료/형상별 T 보정·불괴 예외/드롭 정책 | `T:1..255`, claims/BE veto, 보강된 심층암 앵커 | 불괴·보호 우선, 7.3절 후보식 |
| `P-04` | 3상태 byte 외 잔여손상 1byte 허용 여부 | 저장 스키마·재현성 보장 | 소수값을 숨겨 손상 1씩 부풀리지 않음 |
| `P-05` | 재료군 연성값, `D→plasticCapacity`, 별도 `yieldThreshold`, 변형 방향·격자 이동 | 금 블록 D=100/흑요석 D=0, BE·기계·글루 안전 | 재료 후보 프로필 + 안전 블록 제한 이동 |
| `P-06` | 목적 공간 없음/변형 실패 시 처리 | 실패를 '이동 성공'으로 표기 금지 | state-only/abort; 직접 파괴로 임의 전환 금지 |
| `P-07` | 충돌 A:B 피해 분배 비율 | 합계≤1, 원장 추적 | 명시적 설정, 예산 미초과 |
| `P-08` | 응력 전달 깊이·노드·이웃 가중치 | 유한 work budget, 탄성 이중 감쇠 금지 | 안전 제한·명시적 잔여 기록 |
| `P-09` | 균열 시각 회복/실제 회복 여부 | crack과 damage 분리 | crack 시각만 fade, damage 유지 |
| `P-10` | 투사체/엔티티 fallback 보정계수 | 게임 피해·물리 에너지 구별 | 물리량 부재는 저신뢰 이벤트로 표시 |
| `P-11` | 서버 큐 예산·성능 목표 TPS | 제한된 작업량, 조용한 드롭 금지 | 보수 예산과 계측 |
| `P-12` | DVT 2.0 기관 폭발/전체 질량 추가 보정 | PDD 1.0 기본 기능과 중복 금지 | PDD 구현 범위 제외 |
| `P-13` | DVT 3.0 동적 상태/사용자 설정 우선순위·질량 변조 | 별도 변경/복원 계약 | PDD 구현 범위 제외 |
| `P-14` | 재료 계열·프로필별 H/T/D/E 보정과 태그/제작법 분류 신뢰도 | 앵커 우선·오버라이드 결정론·모드 fallback·정의별 캐시 | 최소 프로필로 시작, 미분류는 일반 fallback |
| `P-15` | 탄성 완충곡선 `rMin`,`beta`, 바퀴/슬라임 기능 프로필 | `0<rMin<=1`, `beta>0`, 흑요석 E=0/슬라임 E=100, 1회 debit | 7.4절 후보값, 충돌 시 불괴/무적 금지 |
| `P-16` | 정밀 접촉이 없는 사건의 근사 게임 예산 허용 여부, `k_proxy`, 단계별 cap·방향 소스·회전만 있는 타격 처리 | 실제 소유자·검증된 신규 타격 근거·중복 없는 단일 원장 필수; 질량은 검증 시 선택적; 근사와 정확 결과 혼합 금지; `0<=k_proxy<=1`; 실제 에너지의 증명된 하한 아님 | **별도 표시된 보수적 프로토타입 프로필**에서만 근사 허용. §23 `DEV_PROVISIONAL` 계수·cap를 설정 파일 기본값으로 자동 사용; 접근/타깃 근거 없음은 `NO_APPROVED_BUDGET` |

**사용자에게 되묻지 않아야 하는 개발자 책임:** Sable API 확인, JVM descriptor, 스레드 안정성, 정상/plot 좌표 변환, 중복 방지, 영속성, Mixin 충돌 탐지, 메시지 직렬화, 단위 테스트, CI, 원본 물리 응답 보존. `P-xx`와 무관한 구현 문제를 사용자 취향 결정으로 돌리지 않는다.

### 13.1 1.0.5 추가 정책과 불변 안전 기본값

| ID | 사용자/운영 선택 | 강제 조건/미결정 기본값 |
|---|---|---|
| `P-17` | native impulse→game budget 함수 | 현재 Sable JAR에서는 **미제공 optional source**. no-native 상황에서 game proxy를 무효화하지 않음 |
| `P-18` | 다중 point의 1회 group cap / A·B 배분 | `Σ shares <= Q_group`, 다른 source 중복 발급 금지; 설정 없으면 보수 한계 및 log |
| `P-19` | 일반 Java 관측 profile에서 허용할 game proxy/최소 신규 충격 증거 | 명시된 **테스트/운영 정책 + verified targets + newImpact evidence**가 충족되면 피해 예산 허용; 미충족은 Q=0. **native 미제공만으로 프로젝트 BLOCKED 금지** |
| `P-20` | 장래 Sable native 확장/라이선스 | 업스트림 허가 전 native fork/companion 재배포·원본 대체 금지. 공개 배포와 인게임 검증 별도 |
| `P-21` | post-effect force 이벤트→게임 피해 스케일/최소 threshold | `F_raw`는 contact당 J가 아님. 모드별 시나리오/상한/휴지 게이트 측정. 기본 프로필은 샘플값임을 명시 |
| `P-22` | 방향 미검증 충돌의 변형 동작 | 직접 damage 및 비방향성 stress 허용, 실제 블록 격자 이동은 `DEFORMATION_DIRECTION_UNAVAILABLE`로 중단 |
| `P-23` | native 사후 효과 100개 제한과 과부하 때 누락 | *누락 추정 피해 금지*, 관측 수/비율/queue/drop 로그, 부하 및 게임 기대 동작 범위 공개 |

본 §13의 과거 `P-16` 설명 중 `정밀 접촉이 없는 사건의 근사 허용 여부`는 **P-19/P-21에 의해 세분화**한다. *소유자 확인과 신규 타격 입증 없이* 단순 raw scalar로 피해를 만드는 옵션은 어떤 프로필에도 허용하지 않는다.

## 14. 개발 로드맵 — 0.3.0realFix에서 정식 PDD 1.0.0까지

### 14.1 각 버전은 완결된 기능 계약을 가진다

| 버전 | 새 버전에서 실제 구현해야 할 기능(생략 금지) | 기존 moreRog에서 교체/재사용할 항목 | 검증 게이트 |
|---|---|---|---|
| `0.2.12` | 기존 Sable 원본 콜백 호출/반환/예외 보존 | `ObservingCollisionCallbackTest` 보존 | `G-COL-01` |
| `0.2.13` | world/plot 각자 block owner UUID/space/loaded/fingerprint | `OwnerResolver`, `TargetGate` | `G-COL-02` |
| **`0.3.0realFix`** | **현재 2.0.6 post-effect 비침습 단일 수집 + 검증된 owner-local 타깃 후보 + 유일 타깃 확정 + quality/newImpact 단계 + 기본 Q_game proxy 후보/zero + structured log**. 정적/plot 및 plot/plot, 회전/다중 접촉의 target/coverage 실제 평가. World mutation OFF | 기존 `RapierCollisionTelemetryMixin`, `NativeCollisionTelemetry`, owner/frame readers 활용. `SableUniversalCallbackMixin` 원본 null 변경 제거. 정확한 법선/회전 계산을 필수 승인에서 격리 | **`G-OBS-01`, `G-TARGET-01`, `G-SABLE-REG-01`**. 새 JNI exporter는 필수 아님 |
| **`0.3.1`** | 관측 그룹·신규 충격 판정 FSM, episode 재장전 증거, A/B 예산 분배, cap/duplicate/미관측 처리 | `ImpactEpisodeLedger`, `ImpactContinuityGate`를 source 정확도에 맞춰 재구성 | `G-LEDGER-01`, `G-REST-01` |
| `0.4.0` | 정의별 material 재료·레시피 분류, H/T/D/E 한정값, elastic/direct/plastic/stress 재료 반응, 1byte/양자화 | `MaterialRegistry`, `DamageModel`, `BlockDamageState` 보존하고 실제 원장에 연결 | `G-MATERIAL-01` |
| `0.4.1` | static world 안전 누적 손상→파괴→드롭(충돌 callback 내부 mutation 금지) | server-safe commit adapter 신규, Sable vanilla system 부작용 안 건드림 | `G-WORLD-01` |
| `0.5.0` | 희소 저장/청크 재시작/블록 교체 검증/상태 초기화 | 피해 상태와 정적 material definition 분리 | `G-SAVE-01` |
| `0.5.1` | 동일 원장 양측 Plot/World damage share, 실제 Plot 블록/BE commit, mass/collider 갱신 예약 | world좌표로 다른 Plot 추정 금지 | `G-PLOT-01` |
| `0.6.0` | 실제 owner-local seed의 bounded 6면 stress, 방향 verified이면 가중치, 미검증이면 비방향 | 별도 solver/접점 재계산 없이 graph 탐색 | `G-STRESS-01` |
| `0.6.1` | Sable split/merge/reassembly, glue 보존, stable target/state migration | Sable native split 재구현 금지, topology epoch 무효화 | `G-SPLIT-01` |
| `0.7.0` | static world의 항복/영구 격자 변형, claims/BE 제한, 방향 검증 없는 충돌의 안전한 미이동 결과 | PDD 추정 법선을 무조건 쓰지 않음 | `G-DEFORM-WORLD-01` |
| `0.7.1` | Plot 영구 변형, local frame 방향·접착/BE/mass-collider lifecycle, 공간 보존/rollback | world block center와 angular pose 역산 반복 금지 | `G-DEFORM-PLOT-01` |
| `0.8.0` | crack visual, 표시만 시간 회복, 실제 손상은 영속, client tracking delta/재접속 | raw collision 전체 동기화 금지 | `G-VIS-01` |
| `0.8.1` | vanilla explosion/projectile/entity input adapters, 원래 피해 중복 방지, DVT source 준비 | Sable collision이 아닌 source에도 동일 ledger 적용 | `G-EXT-01` |
| `0.9.0` | 최적화/호환성, Sable Optimizer optional, 네이티브 없는 profile·부하 누락률 기록, 여러 OS JAR 런타임 | 필요 없는 bridge/관측 hot path 제거 | `G-PERF-01` |
| `0.9.1` | 실제 다중 구조물 PvP 인게임 RC, 방향성 변형/회전-only/연쇄 파괴/저장 및 회귀, DVT 인터페이스 수명 | 오류 발생 분기만 정밀 재조사, 불필요한 물리 재구현 금지 | `G-RC-01` |
| `1.0.0` | 배포/설정/schema migration/API 문서, 실제 테스트 증거/월드 백업/설치·호환 계약 | 전체 소스 ZIP + 빌드 아티팩트 JAR 구분 | `G-REL-01` |

**버전 분리 원칙:** 0.3.0에서 §22의 최소 one-credit/사건중복 방지를 반드시 포함하고, 0.3.1에서 재장전·수명·다중 타깃을 확장한다. 0.3.0에서 콜백/네이티브 데이터의 품질을 증명하려고 미래 0.6 응력·0.7 변형·0.8 균열 코드를 가짜로 넣지 않는다. 반대로 **0.3.0의 이벤트 경로·owner 타깃·게임 예산 후보**는 반드시 함께 실제 구현하고 검증한다. 과거 이미 통과한 테스트는 근거가 유효하면 승계하되, 새 source path의 회귀를 재검증한다.

### 14.2 0.3.0realFix 변경 파일별 실제 구현 지시

| 위치 | 처리 | 단일 책임과 근거 |
|---|---|---|
| `mixin/RapierCollisionTelemetryMixin.java` | **보존·수정** | Sable `clearCollisions(J)[D`의 원본 배열을 Sable consumer에게 그대로 반환하고, PDD 복사본만 bounded 수집. Mixin target descriptor/require 유지 |
| `diagnostics/NativeCollisionTelemetry.java` | **보존·강화** | 15-field record와 body/world sentinel 검증, 차원/tick 품질, lost/overflow/read-order 수치, 원본 effect `F_raw` 별도 유지 |
| `mixin/SableUniversalCallbackMixin.java` | **주경로에서 제거하거나 기존 non-null 특수 콜백만 보존** | null callback→non-null injection은 기본 금지. factory의 원래 결과/횟수/예외를 완전히 보존하는 회귀 테스트 |
| `diagnostics/ObservingCollisionCallback.java` | **선택적 읽기 전용 진단** | original delegate exactly once 및 동일 반환 reference/exception. `null`의 신규 callback 생성 금지 |
| `collision/SableFrameCapture.java` | **보존·역할 축소** | Sable 배치 수명/owner mapping 진단. tick pre/post pose를 정밀 native contact phase로 사칭 금지 |
| `collision/OwnerResolver.java` | **핵심으로 재사용·강화** | runtime-id → stable UUID, A/B owner local block target/state fingerprint. 완전히 검증되지 않으면 reject |
| `collision/ImpactLiveAudit.java` | **damage 결정 경로에서 제외** | translation game proxy/회전 상한을 실제 solver contact 예산으로 오해하지 않도록 진단만 |
| `collision/NativePlotDirection.java` | **damage 결정 경로에서 제외** | 같은 body-pair의 다른 manifold normal을 전체 병합/일괄 거부하지 않는다. 기존 post-effect local normal은 개별 후보로 분석만 |
| `collision/ContactPairMatcher.java`, `ImpactFaceEstimate.java` | **관측/회귀 진단만** | reciprocal callback point coincidence/nearby block을 exact-contact 교정 수단으로 사용 금지 |
| `collision/FrameMath.java`, `ImpactMath.java` | **테스트·reference로 보존** | 수학적으로 검증된 메서드를 불필요하게 삭제하는 것보다 메인 메커니즘에서 사용하지 않고 격리. 새 Sable API 확인 시 필요할 때 활용 |
| `collision/ImpactContinuityGate.java`, `ImpactEpisodeLedger.java` | **0.3.0의 최소 newImpact gate, 0.3.1에서 확장** | resting/slide/grind 중복 피해 0을 기본값으로 구현 |
| `PhysicalDestroyDeform.java` | **주 파이프라인 교체** | callback-pair/geometry/proxy 중심 승인 제거 → raw effect 1회 수집 → owner target → quality → bounded game candidate → non-mutating audit |
| `src/test/**` | **기존 108개 선언(과거 장부 기준) 보존 및 영향 테스트 추가** | counts는 실제 JUnit 실행 수로 최종 확인, 삭제/비활성화 금지 |
| `build.gradle`, `neoforge.mods.toml`, README | **버전만 변경, 계약 보존** | Java 21/NeoForge/Wrapper/Gradle/JUnit/Actions/Apply 불변 |

**중요:** `NativeCollisionTelemetry`의 `NativePlotDirection.prepare` 결과를 `ImpactLiveAudit.evaluate`에 넘겨 회전 충돌 proxy budget을 생성하는 기존 moreRog *주경로*는 근거가 다른 사후 법선들을 하나로 평가한다. **교체 대상**이지 버그를 덮기 위해 dot threshold를 느슨하게 할 대상이 아니다.

### 14.3 0.3.0에서 구현할 최소 파이프라인 결정적 의사코드 (§21~§23 상세 구현 우선)

```text
// 1. Sable original pipeline callback (unchanged)
raw15 = Sable.RapierPhysicsPipeline.processCollisionEffects().originalClearCollisionsResult()
PDD.captureCopy(raw15, dimension, observedTick, boundedCapacity)
return raw15  // SAME array reference; Sable original behavior unchanged

// 2. server tick post (read-only in 0.3.0)
for raw in PDD.drainAndValidate():
    if malformedOrOverflowOrUnknownDimension(raw): reject(reason); continue
    ownerA = resolveRuntimeOwner(raw.idA /* -1 = world */,dimension)
    ownerB = resolveRuntimeOwner(raw.idB /* -1 = world */,dimension)
    if notValidOwners(ownerA, ownerB): reject(OWNER_UNKNOWN); continue
    targetA = resolveViaR1toR4(raw, ownerA, sideA, calibratedFrameOrEvidence)
    targetB = resolveViaR1toR4(raw, ownerB, sideB, calibratedFrameOrEvidence)
    if neitherVerified: auditOnly; continue
    event = classifySource(raw, targetA, targetB, PROVENANCE=POST_EFFECT)
    novel = newImpactGateBySection22FSM(event, continuityState, verifiedGapAndForceRise)
    if not novel: candidateQ=0; audit(NO_NEW_IMPACT)
    else: candidateQ=min(64, 0.25*max(0, peakF(event)-2.0))  // §23 provisional
    if !finite(candidateQ): reject
    grantAtMostOneGroupCredit(event, candidateQ, verifiedSides) // minimal 0.3.0 ledger
    emitImmutableBudgetCandidate(event, candidateQ, quality, validSides)
    // NO mutation, NO callback overrides, NO reconstructed exact physical J

// 3. 0.3.1 extends the already-existing single-credit ledger with formal rearm/multitarget
// 4. 0.4.0+ material/stress planners and safe commit consume Q_game only
```

**API/함수는 알고리즘 의미를 표현하는 가명**이며 `processCollisionEffects().originalClearCollisionsResult()` 같은 public API가 실제 존재한다는 뜻이 아니다. 구현은 `@ModifyExpressionValue`로 Sable 내부 `clearCollisions(J)[D` **결과값을 한번 관측**하는 기존 확인된 hook를 이용한다. 대상 단위가 부족한 입력은 무조건 피해가 발생하는 게 아니라 `audit-only`로 남는다.

### 14.4 소스 수집이 충분하지 않을 때의 우선 순위 — 임의 설계 전환 금지

1. **실제 bytecode/frame 문제:** `local_point/normal` 의미를 pinned Sable와 라이브 단일-plot flat-face collision에서 확인한다. 필요한 부분만 수정하며 `G-TARGET-01` 통과 전 side 선택 금지.
2. **일반 블록의 event 누락:** `clearCollisions` threshold/100 cap의 실측 rate/왜 없는지를 분석한다. Sable callback wrapper를 기본으로 강제 삽입해 native behavior를 바꾸는 것은 **후순위이며 명시 승인+통합 회귀 필요**.
3. **회전-only 타격 new-impact를 판별 못함:** body의 실제 angular state/pose/point phase를 *옵셔널 정량화*로 수집하고 보수 game proxy를 검증한다. 가능하면 Sable existing `getInverseNormalMass`; **source API가 제공하지 않는 signed approach를 `abs`로 발명하지 않는다**. 특정 유형만 미지원이라고 보고하고 정밀도 옵션 제안.
4. **전수 solver J/정밀 방향이 명백히 필요해지는 경우**에만 업스트림 **별도 최소 native batch**를 검토. 무허가 Sable native 교체 금지. **그 자체는 0.3.0 일반 기능 완료를 중지시키지 않음.**

### 14.5 개발·빌드·릴리스 상태기계

```text
PLANNED -> HEAD+SOURCE_PINNED -> IMPLEMENTED_ALL_VERSION_FEATURES
-> LOCAL_CLEAN_TEST_PASS -> LOCAL_BUILD_PASS -> ACTIONS_PASS
-> GAME_PASS(<=2 scenarios) -> FULL_SOURCE_ZIP_READY -> USER_APPLY_HANDOFF
or BLOCKED(feature-specific reproducible evidence, exact missing input)
```

- `G-OBS-01`에 필요한 Sable post-effect Java 기존 ABI의 존재를 다시 조사하는 전용 버전을 만들지 않는다. 이미 확정된 JNI 클래스/descriptor를 사용한다.
- `G-NATIVE-SOLVER-OPT`는 별도 기술 연구 상태로 남긴다. 정상 game proxy가 기준 장면에서 작동하는데 native exporter가 없다는 이유로 모든 PDD 버전을 `BLOCKED`로 표시하지 않는다.
- 기능 구현과 성공 주장 분리. `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon` *실행 결과만* PASS이며 과거 SHA Actions 성공은 이번 코드 성공이 아니다.

## 15. 검증/진단 계획 — 실패 원인마다 하나의 실제 증거

### 15.1 0.3.0realFix 필수 테스트

| ID | 실험/검증 | 합격 조건 |
|---|---|---|
| `UT-SABLE-CALLBACK-01` | Sable special callback 원본 delegate/exception/null | 호출 수/반환 reference/exception 동일, null source 새 callback 0 |
| `UT-OBS-15` | native 15 double, sentinel -1, NaN, malformed arrays, 100+ records | 잘못된 source reject; original array unchanged; Sable effect 처리 정확히 1회 |
| `UT-OBS-LIMIT` | PDD 큐 포화 및 upstream 100 limit | upstream observable cap 명시; PDD queue overflow 별도 카운터; 조용한 추정 피해 없음 |
| `UT-OWNER-01` | WORLD(-1)/Plot/Plot↔Plot stable ID/차원 분리 | 다른 owner를 가까움으로 선택 0, 정확히 유효 state만 타깃 |
| `UT-OWNER-02` | Sable split/rebuild/runtime ID 재사용 | old epoch/tick 대상 reject, state fingerprint CAS |
| `UT-POINT-01` | local vs world frames, CoM, rotated/transformed plot | 유일 target만 승인, ambiguous는 Q=0/부분 side 검증 이유 로그 |
| `UT-QUALITY-01` | raw force/post-effect/normal/velocity의 실제 source | `force_amount`를 J/접점별 joule로 쓰지 않음 |
| `UT-REST-01` | 100틱 안정 지지·슬라이딩·끼임 vs 신타격 | 후속 연속 episode에서 게임 크레딧 0; 실제 재타격은 independent evidence 필요 |
| `UT-ROT-01` | Plot 회전-only 모서리, 다른 법선 다중 접촉 | 확실한 사건은 게임 예산 후보; 불명확하면 *왜* 누락됐는지 로그. 유효한 서로 다른 normal 일괄 배제 금지 |
| `UT-BUDGET-01` | bodypair A/B reverse, same raw 10 points/2 callbacks | group budget 복제 없음, A+B debit ≤ cap, 양측 정상 반환 영향 0 |
| `UT-COMPAT-01` | True Impact/Collision Damage/SableImpact 각각 단독 및 혼합 설치 | PDD 주 경로 callback override 개입 없음, 문제 모드 조합은 명시 지원 범위로 분리 |
| `UT-JAR-ABI` | 실제 Sable/Sable Companion/Create/Aeronautics JAR·classpath | 모든 직접 호출 class/method/access/descriptor 실제 일치; nested jar 자동 compile 가정 금지 |
| `UT-BUILD-01` | `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon` | 실제 compileJava,compileTestJava,JUnit,processResources,jar,build 성공, 기존 test 비활성화 0 |

**게임에서 이미 작동한다는 비교 애드온 관측**은 `UT-COMPAT-01`의 PDD 변경/타깃과 별도 기록으로 존중한다. 분석의 목표는 그 코드를 무조건 모방하는 것이 아니라 사용자 체험의 정상 동작을 PDD에서 *회귀 가능 계약*으로 만드는 것이다.

### 15.2 최대 두 번의 실제 인게임 검증 (버전 0.3.0)

**세션 A — Plot↔World 선형 충돌 + stop/recontact:** 동일 월드에서 stone Plot을 정적 돌벽에 충돌시키고 정지·슬라이딩/접선·충분한 분리 이후 재접촉을 연속 확인한다. 가능한 실제 양측 block, dimension/owner, raw record count, sourceQuality, newImpactEvidence, Q_game, original callback count/effect count를 로그로 남긴다. 정지/접선은 무근거 재데미지 0. owner 미확정 event는 다른 블록에 잘못 피해를 주는 대신 `TARGET_UNRESOLVED`이며 **그 원인이 구체적 필드로 기록**돼야 한다. 0.3.0 world mutation OFF를 반드시 확인.

**세션 B — Plot A↔Plot B 회전/모서리·다중 contact + 특수 callback:** 회전하는 구조물의 돌/체인 또는 사용자가 확실히 관측할 수 있는 구조용 블록이 서로 부딪히게 한다. 다른 bodypair/owner, 회전-only와 병진 대조, 두 다른 법선, 콜백 있는 벨트/fragile의 원본 반응을 구분한다. post-effect가 물리의 모든 접촉을 커버하지 못한다면 `COVERAGE_LIMIT/UPSTREAM_FILTERED`로 구별하고 *그 사건만* game budget 미승인. 성공한 game impact에서는 한 episode credit 및 두 실제 owner-local target이 나타나야 한다. Sable callback/response 변화는 FAIL.

**출력 형식 (예시 형식, 실측값 아님):**

```text
[PDD-SOURCE] run=... dim=... observedTick=... type=POST_EFFECT records=... queueDropped=... upstreamMax=100 originalArrayUnchanged=true
[PDD-TARGET] bodyA=-1|id bodyB=id ownerA=STATIC_WORLD|UUID ownerB=UUID targetA=... targetB=... quality=... frame=... fingerprint=... status=VERIFIED|AMBIGUOUS
[PDD-IMPACT] episode=... source=... impactEvidence=... newImpact=true|false quality=... rawForce=... Q_game=... cap=... reason=... mutation=OFF
[PDD-LEDGER] episode=... candidate=... sharedCredit=... debited=... unused=... duplicate=... sourceKind=...
[PDD-SABLE-REGRESSION] callbackOriginalCalls=... callbackReturnMismatch=0 nativeOriginalArrayChanged=0 originalEffects=... mixinErrors=0
```

**합격/보류 구분:** 관측된 샘플 건수=0은 AUTO FAIL/원인 조사, 필수 타깃을 알고도 다른 Plot를 선택하면 FAIL, `F_raw`만으로 수치 피해를 만들어 repeated rest가 증가하면 FAIL. 실제 특정 회전 장면이 관측 피드에 없으면 **coverage limitation**으로 별도 기록하고 그 경로의 0.3.0 게임 기능 성공을 주장하지 않는다. 모든 기능이 정적 API로 확보되지 않은 것을 *예외 없이* 전체 게임 구현 불가능으로 판정하지 않는다.

### 15.3 0.4.0–1.0.0 기능 회귀 누적

- 물성 H/T/D/E과 재료 정의 캐시/레시피, 상태별 보정 및 독립 1byte 계약, 탄성 1회, 미세손상 양자화.
- 0.4.1+ world block CAS·드롭·보호/BE 안전, 0.5.0+ 청크/재시작/저장 migration, 0.5.1+ 양측 Plot state.
- 0.6+ node/depth bounds·잔액 보존·Sable split/reassemble, 0.7+ 방향 미확정 이동 금지·기계/BE/claims 회귀.
- 0.8+ 균열 fade만·실제 손상 유지·두 클라이언트 delta·폭발/투사체 중복 정책.
- 0.9+ `sable_optimizer` optional ON/OFF, 성능/OS 지원/동접 PvP, Sable original callbacks/physics/CCD 변화 0, DVT 확장 어댑터.

**최종 테스트 결과 표기는 `PASS`, `FAIL`, `NOT_RUN`, `BLOCKED`, `INCONCLUSIVE`로 엄격히 구분**. 이전 moreRog GitHub Actions의 성공은 `realFix` 성공이 아니다.

## 16. GitHub·모바일 배포를 위한 강제 개발 프로토콜

- 개발 시작 시 GitHub `main` HEAD **전체 SHA와 트리**, 전체 소스 기준을 확인하고 사용자 제공 소스 archive와 차이를 기록. GitHub Actions `.github/workflows/{build-gradle,pdd-callback-test,apply-pdd-zip,import-pdd-issues}.yml`을 보호한다.
- 대상 Gradle Java 21/NeoForge 21.1.228/ModDev 2.0.78/JUnit 5.11.4/Sable 2.0.6 종속 계약을 임의로 수정하지 않는다. 빌드 실패가 있으면 실제 classpath/bytecode/테스트 출력을 보고 원인을 고친 뒤 **전체 clean test+build 재실행**.
- **전체 ZIP**: 루트 또는 유일하게 식별되는 프로젝트 root에 `build.gradle`,`settings.gradle`,`src/main/java` 필수. 기존 Apply script는 `.github/`, `.git/`, `incoming/`, `gradlew`, `gradlew.bat`, `gradle/` 루트를 보호하므로 전용 소스 ZIP에는 제외할 수 있으나 이 파일은 GitHub에서 보존되어야 한다. 하나의 incoming ZIP만 업로드, 이름의 임의 부정확한 릴리스 표시 금지.
- 실제 `compileJava`, `compileTestJava`, `processResources`, `jar`, JUnit total/passed/failed/skipped, `clean test`,`build`, GitHub Actions run ID/SHA, 인게임 true/false, JAR 설치·로그, ZIP integrity 전부 **별도 PASS/FAIL/NOT_RUN**으로 보고.
- 유사 애드온은 PDD 구현의 안전 검증을 대체하지 않는다. 원본 callback 호출 횟수/반환/예외, special Fragile/Belt, 서버/클라, Sable scene split/owner, no-world-mutation while observing을 회귀한다.
- 핵심 기능의 관측 및 게임피해 경로는 정상 설정에서 **기본 활성**, 개발자가 고급 JVM 플래그를 사용자에게 요구하여 핵심 동작을 숨기지 않는다. 실제 월드 변경은 지정 버전부터 활성화.
- 라이선스: Sable PolyForm Shield/다른 애드온 라이선스 범위를 각각 확인. PDD의 설계·알고리즘은 독립 구현. PDD Source ZIP에 Sable/타 모드 native/JAR를 몰래 포함하지 않는다.

## 17. 통합 알고리즘 의사코드 — 소스→예산→손상→DVT

```text
SableStep: keep original solve, CCD, callback, post-effect, output unchanged
 PDD observes same post-effect array once (do not call clearCollisions again)

PddOnServerPostTick:
 observations = boundedDrain()
 for o in observations:
   validate15FieldSourceAndWorldSentinel(o)
   ownerPair = mapRuntimeBodiesToActualStablePlotOrStaticWorld(o)
   targets = resolveAndVerifyActualOwnerLocalBlockCandidates(ownerPair,o)
   grade = classifyQualityByEvidence(o,targets)
   if grade lacks actual target or new impact proof:
       ledger.observeZero(o,reason); continue
   qGame = oneConfiguredBoundedProxyFromSingleRepresentative(o)
   ledger.offerOneEventCandidate(o,targets,qGame,grade)

PddOnBudgetClose (0.3.1+):
 for episode in ledger.closedEpisodes:
   credit = boundedSingleCredit(episode)
   shares = allocateOnceToValidatedATargetAndBTarget(credit)
   assert sum(shares) <= credit
   plans = MaterialPlanner.resolveUsingDefinitionCache(shares, H,T,D,E)
   for p in plans:
      applyElasticOnce(p)
      divideIntoDirectPlasticStressUnused(p)
      boundedStressToActualNeighborBlocks(p)  // if 0.6.0+
   queueImmutableMutationIntents(plans)

PddServerSafeCommit (0.4.1+):
 for intent in limitedQueue:
   if unloaded || differentFingerprint || wrongOwnerGeneration || claimOrBEUnsafe || physicsStepping:
       rejectOrDeferBounded(intent)
   else:
       applyToActualWorldOrPlotBlock(intent)
       persistSparseDelta(); invalidateSablePoseMassColliderEpochIfNeeded()
       publishClientVisualDeltaForCommittedResultOnly()

DvtSource:
 engineFailure/explosion/projectile/materialChange -> SourceBudgetAdapter
 -> SAME GroupLedger -> MaterialPlanner -> SafeCommit

OptionalSolverUpgrade:
 if true solver J/point plus atomically matched blocks+step become available:
     use SOLVER_NATIVE_VERIFIED as replacement for SAME event's game proxy
     NEVER add both budgets; never rewrite Sable response physics
```

**순서·복잡도:** Sable native 효과 수집 O(R), owner/body mapping O(R), target validation O(R·k) where k is a small bounded local candidate count, ledger O(U), bounded stress O(maxNodes). World 전체·Plot 전체 블록/레시피 per tick 스캔과 원본 callback×native records Cartesian matching을 금지한다.

**실측의 한계:** 0.3.0에 정확한 phase/impulse/signed approach가 없다는 사실을 숨기지 않는다. 관측/target/게이트가 불충분한 사건은 0 budget; 정상으로 보이는 사건이 일어나지 않으면 자세한 원인 로그로 source/target/impact 단계별 차이를 확인해 고친다. World mutation이 OFF인 버전에서 실제 파괴가 일어났다고 주장하지 않는다.

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
8. **이전** 통합 설계서 1.0.1의 물성 설계 입력: 사용자와의 경도 자동 산출·금/흑요석 연성 기준·보강된 심층암 경도/파괴 기준·흑요석/슬라임 탄성 기준·Aeronautics 바퀴 보호·재료/상태/기능 프로필·에너지 원장의 1회 완충에 관한 설계 대화. **직접 확인된 바이너리 API 사실과 분리된 디자인 요구/수식 후보**이다.

9. **통합 설계서 1.0.2 개정 근거:** 사용자와의 0.2.13 충돌 좌표 분석/설계 수정 지시; `PDD-0.2.13-G-COL-02-FINAL-functional-full-source.zip`의 `collision/CollisionFrame.java`, `collision/ContactPairMatcher.java` 및 검증 문서의 기존 **2블록 AABB/0.25블록 reciprocal 기준**, 업로드된 동일 SHA Sable·Collision Damage·Sable Impact·True Impact 바이너리의 구조 비교. 이 자료는 설계 교정 근거이지 해결/인게임 합격 증거가 아니다.

10. **통합 설계서 1.0.3 설계 감사 근거:** `PDD-Caveman-three-addon-architecture-audit-2026-10-30.md` (동일 해시 유사 애드온 3종과 Sable 원본 JVM 바이트코드, PDD 0.2.13 소스, 인게임 로그 비교). `sablecollisiondamage`의 `resolveShipTarget`, SableImpact의 상이한 callback descriptor, True Impact의 `SubLevelFractureHelper.executeInternal`과 `StressPropagator.propagate`, Sable `Rapier3D.clearCollisions(J)[D`와 `RapierPhysicsPipeline.processCollisionEffects`의 검증된 호출 관계를 참고했다. 바이너리 정적 검증이며 각 모드의 실제 게임 피해 정확도 증명은 아니다.
11. **규범 원문:** `PDD-1.0.2-Integrated-Design-Specification.md`, SHA-256 `2a97faa2a95ea8c0de98b7a11d7818c77327c6370791343910e3fd72a28cdc95`. 1.0.4는 그 1.0.3의 기능 범위·물성 원장·저장·UI·DVT 경계를 보존하면서 실접촉 데이터 및 전 버전 알고리즘 계약을 개정한다.

### 18.2.1 1.0.5 직접 검증 근거와 소스 인덱스

**PDD `main` SHA** `ee5b7cfd8122879570831bdcae2a889be50e3ed6`: moreRog `PhysicalDestroyDeform.onServerTick`가 `NativePlotDirection.prepare` → `ImpactLiveAudit.evaluate` → proxy credit을 사용하고, `SableUniversalCallbackMixin`은 null callback도 `ObservingCollisionCallback`으로 감싼다. 기존 3개 Mixin 이름과 `pdd.mixins.json` required=true를 확인했다. 이전 moreRog Actions success는 *해당 빌드*의 증거로만 사용.

**Sable 공식 pinned Rust source:** `ryanhcode/sable` `ad8b4d377741fbd923f4d863b2cf9e462daff9a6`:

| 위치 | 실제 확인 내용 | 적용 결정 |
|---|---|---|
| `sable_rapier/src/main/rust/rapier/src/dispatcher.rs` L374–418, L624–681 | `SableManifoldInfo`에 각 원본 블록좌표·collider `col`와 `user_data` bit-packed step-local index | Sable 내부에서 식별 가능하나 Java observer가 직접 읽을 수 있는 **공개 API 아님** |
| `.../hooks.rs` L24–120, L124–275 | `needs_hooks` short-circuit, `SolverContact.point`, `context.normal`, 양 body의 `velocity_at_point`, fake velocity, `abs()`, `handle_block_params`, `onCollision` 인수 구성 | collision callback scalar는 signed closing 아님; callback 원본 보존, 정확한 회전 재계산 hot path 불필요 |
| `.../event_handler.rs` L25–70 | `total_force_magnitude<0.1`이면 skip; manifold→point 순회에서 **동일 total_force_magnitude**를 각각 `ReportedCollision.force_amount`로 저장 | per-point impulse 아님, 효과 수/스칼라 누적 금지 |
| `.../lib.rs` L511–550/L1243–1296 | native `step()` 시 manifold map clear 후 solve, `clearCollisions()`에서 배열 **100건 truncate**하고 15 doubles/record | Java observation은 best-effort 제한된 effect 피드. Native 전체 보증 안 됨 |
| `.../scene.rs` L113–131 | map은 `DashMap<usize,SableManifoldInfo>`, `pos_a/pos_b/col_a/col_b` 등 | Java 원천 block pair 노출 없음 |
| `Rapier3D.class`와 내장 `sable_rapier_x86_64_linux.so` | 53 native method 선언 + 53 export 이름 일치. 일반 solver contact exporter 없음 | 기본 구현에서 신규 native dependency를 요구하지 않음 |

**고정 업로드 바이너리 메서드:** `trueimpact-DiagnosticContactCaptureMixin.{TI$callClearCollisions1x,TI$callClearCollisions2x,TI$doProcessUnsafe}`, `trueimpact-SableImpactCapture.{process,computeMetrics,enqueueEmbeddedContact,enqueueActiveVsActiveContact}`, `trueimpact-DamageResolver.resolve`, `scd-RapierVoxelColliderBakeryMixin.sablecollisiondamage$wrapCollisionCallback`, `scd-SablePreSolverDamage.{resolveCollisionTarget,resolveShipTarget,flushPendingBlockBreaks}`, `si-KineticColliderMixin.redirectSableCallback`, `si-KineticImpactCallback.sable$onCollision(BlockPos,Vector3d,double)`, `sable-MassData.getInverseNormalMass`, `sable-BlockSubLevelCollisionCallback.onCollision`. 모든 메서드 이름은 JAR의 `javap -c -p -s` 출력에서 실제 확인했다.

**출처/해시(원본 JAR SHA-256):**

| 파일 | SHA-256 |
|---|---|
| Sable 2.0.6 NeoForge | `9c199157843bf953a47dff625fc5bb41c01a90538d35ad18e6996bbcb8a617c2` |
| 내부 Rapier JAR | `0f0ec5f9a0cdbff937fb36542eb568988f27e6eb7f848cb4df35ad202ced53a7` |
| True Impact 0.5.7-delta | `7c0c97b8a8709920e542ba2e1f142f959dee89a0ec5927e99b3be34ad3963c9c` |
| Sable Collision Damage 1.0.8 | `a053bc5aba63563918608dd9a53077d7dc832ce32066322fc0f0235c0deda62a` |
| SableImpact alpha 1.1.0 | `5d1f29ff10600c7f300924d8b767b18ce314ea434694772296a47778c113d4b1` |
| Sable Optimizer 1.0 | `0fd54d275472dfeb2cfffc83f0ae51fc880cd47cadff07637028d4fdce1da636` |

**상위 근거 연결:** [PDD 기준 저장소](https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming), [pinned Sable repo](https://github.com/ryanhcode/sable/tree/ad8b4d377741fbd923f4d863b2cf9e462daff9a6), `dispatcher.rs`, `hooks.rs`, `event_handler.rs`, `scene.rs`, `lib.rs`는 같은 SHA에 고정해 추적한다. **소스 정합의 범위:** Java JNI 이름/호출부와 Rust 공식 태그를 확인했으나 제공 native 바이너리의 모든 runtime semantics가 공식 소스와 동일하다는 증명은 아니다. 실제 JNI 5인수/6인수 불일치를 무시하여 원본 mod를 직접 교체하지 않는다.

### 18.2.2 검증 사실 vs 미검증/정책 후보 — 재설계 발생 원인 차단

| 항목 | 판단 | 추후 필요한 검증 |
|---|---|---|
| Java `clearCollisions(J)[D` 15-field 배열 존재 | **확정** (Sable JAR/내부 JAR) | Mixin 실제 로딩 및 Sable original contract 게임 회귀 |
| post-event 기록에 body IDs와 local normal/point 포함 | **확정** (pinned Rust source) | 제공 JAR의 local point frame, post step pose/CoM 의미와 후보 유일성 |
| `force_amount`가 개별 solver J/실제 joule | **거짓** (source 반증) | 게임 proxy policy만 보정 가능 |
| collider callback `signedVelocity`에 접근 부호가 있음 | **거짓/오해** (Rust `abs()`) | 정밀 회전 signed 정보는 별도 source 필요 |
| `SableManifoldInfo.pos_a/b`를 Java에서 바로 조회 가능 | **거짓** (현재 Java API 부재) | 선택적 native bridge만 제공 가능 |
| 세 유사 모드가 게임에서 정상 작동 | **사용자 현장 관측** | PDD 자체 호환성/정밀도 테스트와 구분 |
| TrueImpact `DamageResolver`가 완성 피해 계산기 | **거짓** (바이트코드 `NONE` 반환) | 다른 누적/commit 경로와 혼동하지 말 것 |
| 일반 Java 경로가 회전-only 모든 접촉에서 완전 피해 제공 | **미입증** | 대표 IG-02 회전 인게임 검증, 부족하면 특정 source 보완 |
| 기본 JNI exporter 없으면 PDD 게임 파괴 개발 불가능 | **거짓** (대체 path 존재) | 기능 커버리지/피해 정확도 목표를 실제 gate로 분리 |
| 임의 local normal과 tick pose로 모든 접촉 정확하게 복원 | **미입증** | flat face, edge, multi-manifold, rotated PLOT 실제 frame 검증 |

**이 절을 영구 진리표로 사용하지 않는다:** 새로운 Sable JAR 해시·원본 코드 변경·실제 게임에서의 재현 가능한 반례가 있으면 검증 장부를 갱신하고, 규범 변경이 필요한 경우에만 설계 문서를 추가 개정한다. 정적 소스 검증 결과만으로 새로운 코드의 빌드나 게임 합격을 주장하지 않는다.

### 18.3 소스 판독 기준 GitHub SHA의 역사적 기록
원본 1.0.4의 과거 참고 SHA `d02a915b…`는 당시의 읽기 근거일 뿐이다. 1.0.4 R2 당시 프로젝트 검사 기준은 GitHub `main` 커밋 **`ee5b7cfd8122879570831bdcae2a889be50e3ed6`**, 트리 135 항목, 변경 중인 구현 계열 `0.3.0-moreRog`이다. 기존 `.github/workflows/build-gradle.yml`, `pdd-callback-test.yml`, `apply-pdd-zip.yml`을 보존해야 한다. 이 역시 다음 개발 세션에서 HEAD가 유지된다는 약속이 아니다.

**분리:** Sable source tag/revision과 PDD repository HEAD는 서로 다른 SHA다. 둘 중 하나의 버전 번호가 같더라도 다른 쪽의 CI/게임 합격을 자동 승계하지 않는다. 로컬 JAR 해시·native 플랫폼 해시·Sable 확장 빌드 인수/exports 및 license trace를 프로젝트 검증 장부에 별도 기록한다.

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

## 19. 통합 설계서 1.0.5 완료 판정과 설계 안정화 규칙

1. Sable의 solver/CCD/원래 callback/원래 effects/Plot mass·split lifecycle 변화 없음. 일반 블록 null callback을 의도치 않게 non-null로 만들지 않음.
2. 실제 Sable 2.0.6 Java 사후 충돌 효과의 **한 번 관측**과 데이터 검증(15 double, 100-record upstream limit, `force_amount` 의미)이 동작하고, 원본 processing이 반드시 유지됨.
3. `STATIC_WORLD` 및 각 `SABLE_PLOT`의 stable owner UUID, owner-local block, BE/claims/fingerprint의 유일하고 검증된 타깃만 피해. Frame·phase 불명확 시 reject 또는 game-only downgrade.
4. `0.3.0realFix`: 모든 입력에서 양측 owner 후보/단일 target/quality/reason/Q_game 또는 Q=0를 로그로 출력하고, 실제 월드 변경은 아직 OFF. 대표 게임의 **작동 가능한 game proxy**와 coverage를 검증. Native 정밀 batch 부재는 optional 기록이지 universal blocker 아님.
5. `0.3.1+`: `F_raw`와 `J_solver` 및 `Q_game`의 단위와 source 분리, 신규 충격 FSM, resting/recontact/회전 테스트, bodypair 그룹 상한, A/B/탄성/직접/소성/응력 debit 총계 보존.
6. §7–§11 원래 H/T/D/E·물성 자동등록·희소상태·byte·누적손상·경도/연성/탄성·실제 영구 변형/파괴·stress·split·폭발/투사체·server transaction·BE·claims·크랙/UI 기능 **삭제/임의 이월 없이** 각 로드맵 버전에 구현.
7. DVT 1.0은 PDD 엔진 재사용, DVT 2.0의 기관 폭발, DVT 3.0의 보강·동적 물성을 같은 `DamageSource→GameBudget→Planner→Commit` 경계로 확장; 미래 기능에 가짜 구현 추가 금지.
8. 표준 Gradle Wrapper/NeoForge/Java/JUnit/Actions/Apply 보존, `clean test`, `build`, compileJava/compileTestJava/processResources/jar, 공식 JUnit 결과 **실제 실행 확인**, GitHub Actions 구별. 바이트코드 분석을 빌드 합격으로 표시 금지.
9. 실제 인게임 검증에서 소유 블록·원본 효과·피해 예산·거절 사유·서버 상태 변경·분리 후 소유권·클라이언트와 영속 상태가 원인부터 출력까지 추적되고, 최대 2세션 프로토콜을 지원.
10. ZIP은 전체 PDD 소스(보호 파일은 GitHub 원본 유지), 단일 Gradle project root/CRC/필수 파일/중복 ZIP 검증 후 인계. GitHub 직접 업로드 금지.

### 19.1 정확도/기능 별도 완료 상태

| 상태 | 의미 | 출시 영향 |
|---|---|---|
| `JAVA_SOURCE_VERIFIED` | 2.0.6 observer 및 source/frame 검증 | 0.3.0 기능 경로 진행 허용 |
| `GAME_IMPACT_VERIFIED` | 대표 실제 충돌의 신규 피해 및 중복 방지 확인 | 0.3.0+ 게임 기준 승인 |
| `GAME_DAMAGE_COMMITTED` | 서버 실제 손상/파괴, 저장과 시각화 전 단계 검증 | 해당 버전 world mutation 승인 |
| `SOLVER_ATOMIC_IMPULSE_VERIFIED` | 정확한 per-point solver impulse/phase/owner 전송 | **선택적 물리 정밀도 승격**. 기본 PDD 전 개발의 선행조건 아님 |
| `BUILD_VERIFIED` | 실제 clean test/build + 관련 Actions 시험 | 소스 작성/ABI 감사와 별도 |
| `RELEASE_VERIFIED` | 모든 해당 버전 기능·검증·ZIP/인게임 완료 | 정식 완료 선언 조건 |

**문서 자체의 현재 결과:** 통합설계서 **1.0.5 작성 및 정적 역공학/설계 결정 반영**. 0.3.0realFix 신규 구현, 공식 Gradle/JUnit, Actions, 새 인게임 테스트 **미수행**. 1.0.5 설계 문서 작성 완료를 PDD 릴리스 완료로 표기하지 않는다.

---

## 20. 1.0.5-A 규범 보완 — 설계 문서를 실제 구현 가능한 계약으로 사용하는 방법

> **본 절 §20~§28은 1.0.5 본문의 미확정 함수·정책·순서를 구현 가능한 절차로 보완한다.** 사실로 확인된 API는 `E-...`, 설계자가 선택한 초기 알고리즘은 `A-...`, 시험용 수치는 `P-DEV-...`, 게임에서 검증할 가설은 `H-...`으로 구분한다. 검증된 적이 없는 가설을 API 보증으로 표기하지 않는다. 이 구분은 설계 변경 없이 실측에 따른 튜닝을 허용하기 위한 것이다.

### 20.1 네 가지 요청에 대한 영구 운영 원칙

1. **개발자 임시 설정과 사용자 정책 확정은 다른 상태다.** 해당 기능이 도입되는 버전에서 먼저 `DEV_PROVISIONAL` 값으로 완전한 기능·테스트·설정 파일을 구현한다. 사용자 확정 전에도 코드/정상 빌드/JUnit/인게임 검증을 진행한다. 기능 버전의 시험 결과를 공개한 뒤 운영자가 값을 `USER_APPROVED`로 승격하며, 사용자가 특별히 값을 정하지 않으면 명시적 개발 기본값을 그대로 동작 가능한 기본 프로필로 유지한다. **미승인=설정 없음=피해 0**으로 만들지 않는다. 단, `유효하지 않은 타깃/차원/권한/소스`는 정책과 무관하게 피해 0이다.
2. **검증 가능한 가설로 먼저 구현한다.** 소스 API가 존재하고 호출·데이터 품질을 시험할 수 있으면 구현·테스트·빌드를 진행한다. 실제 게임에서 실패하면 최소 경로를 수정하여 같은 개발 버전의 완성품으로 재검증한다. 정적 분석만으로 `PASS` 선언 금지; 동시에 이론적 정밀 데이터 부재만으로 0.3.0 전체 빌드 착수 금지하지 않는다.
3. **정확성은 구현의 증거 수준에 맞춰 표시한다.** `(A) 검증된 JNI/Java 사실`, `(B) 일관된 게임플레이 모델`, `(C) 시험 전 가설`, `(D) 수치 튜닝`을 분리한다. 각 알고리즘은 입력 타입/생명주기/정규화/상한/반례/출력 로그를 가져야 한다. `C`가 테스트에서 틀리면 설계서의 공통 안전 규칙을 유지하면서 구현 대안을 순서대로 적용한다.
4. **대체 구현은 규범의 일부다.** 정상 경로가 막히면 §28의 결정 트리를 따라 동일 버전에서 다른 공식 API, 한정된 Mixin, 좁은 탐색, 보수적인 정책을 선택한다. 이는 안전·저장·콜백 보존과 같은 **필수 불변식**을 완화하는 권한은 아니다. 값·함수 교체는 구현+실측 문서 `algorithmRevision`으로 기록하고 설계서 전체를 매번 재작성하지 않는다.
5. **검증 완료라는 말은 실제 결과만 뜻한다.** 빌드 가능 가설과 실제 Gradle 성공, 게임에서 실제로 관측된 결과를 반드시 구분한다. 사용자 검증이 필요한 인게임 부분만 후속 인계하고, 코드/테스트/빌드/ZIP은 개발자가 가능한 환경에서 먼저 완료한다.

### 20.2 버전별 정책 확정 절차 — 사용자에게 기술 문제를 전가하지 않음

```text
모드 개발 버전 도입
 -> 해당 버전의 P-DEV 기본 프로필 로드 (사용자 입력 불필요)
 -> 필수 안전 불변식에 대한 단위·통합 검증
 -> 인게임 1~2회의 대표 장면에서 설정·입력·거절·결과를 기록
 -> 사용자 선택 가능한 조정 범위/부작용/기본 추천값을 릴리스 노트에 표시
 -> 사용자가 값을 변경해 승인하면 USER_APPROVED로 승격
 -> 명시 승인 없으면 DEV_PROVISIONAL 기본값으로 계속 작동
```

`P-DEV` 값은 기술적 사실이나 선례 애드온의 수치와 동등하다는 주장이 아니다. 개발 버전별 파일에 `policySchema`, `origin=DEVELOPER_PROTOTYPE`, `chosenAtVersion`, `min/max`, `units=GAME_Q|TICKS|BLOCKS`, `userOverride`를 기록한다. 이후 사용자가 확정한 값이 기술적 제약을 위반하지 않는 한 유지한다. 각 버전의 데이터 마이그레이션 시에는 사용자가 직접 수정한 설정을 재설정하지 않는다.

### 20.3 선례에서 무엇을 실제로 가져오는가

| 근거 | 업로드 JAR 바이트코드로 확인된 사실 | PDD에서 채택할 기술 | 수치를 복사하면 안 되는 이유 |
|---|---|---|---|
| Sable pinned Rust `event_handler.rs`+`clearCollisions` | 소스상 힘 이벤트 임계 `0.1`, 포인트마다 동일 `total_force_magnitude` 기록, 배열 최다 100개 (배포 바이너리 조건은 런타임 재확인) | effect 수집, max/대표값, 레코드 중복 방지, 자체 버퍼 상한 | `0.1`은 Sable native 보고 임계이지 PDD 파괴 임계가 아님 |
| True Impact 0.5.7-delta `SableImpactCapture`, `TrueImpactConfig` | 스냅샷·충돌·지연 큐/재료 단계를 분리하고 감지 기본값 예시 `GLOBAL_DETECTION_THRESHOLD_J=40.0` 존재 | 소스→후처리→재료→커밋 책임 분리, 검증 가능한 튜닝/진단 | 해당 `40.0`을 Sable `force_amount`의 임계값으로 재사용하면 단위가 혼동됨 |
| Sable Collision Damage 1.0.8 `Config` | `MIN_BREAK_SPEED` 개발 기본값 `15.0`, `STATIC_SLOWDOWN_PER_BLOCK` `3.0` | 피해 조건·구조물 처리·입자량을 설정으로 분리 | `15.0`은 해당 애드온의 속도 정책이며 Sable 사후 힘의 단위가 아님; PDD는 추가 감속 금지 |
| Sable Impact alpha 1.1.0 `Config`, `KineticImpactCallback` | `impactMultiplier` 기본 `1.0`, 하위 `LocalKineticAnalyzer`·`BeamSnapper`·`CrackManager` 분리 | 지역 국소 연결 분석, 균열 표시, 피해 계수의 독립 설정 | 해당 JAR의 3인수 `sable$onCollision` 시그니처가 이 Sable 배포본과 다르므로 호출 코드를 그대로 복제하면 안 됨 |

**API 확인:** `SubLevelContainer.getContainer(ServerLevel)` → `ServerSubLevelContainer.getAllSubLevels()`, `ServerSubLevel.getRuntimeId()/getUniqueId()/getPlot()/logicalPose()`, `LevelPlot.getEmbeddedLevelAccessor()`, `EmbeddedPlotLevelAccessor.getBlockState()/setBlock()/removeBlock()/destroyBlock()`가 실제 Sable 2.0.6 JAR에 존재한다. `RigidBodyHandle.getLinearVelocity(Vector3d)/getAngularVelocity(Vector3d)`와 `MassData.getMass()/getInverseNormalMass(Vector3dc,Vector3dc)`도 존재한다. **해당 타입들이 실제 PDD Gradle classpath와 런타임에 연결되는지는 컴파일·게임 테스트로 별도 확인**한다. 컴파일되는 메서드가 안전한 서버 콜백 시점에 호출 가능한지도 따로 확인한다.

## 21. 충돌 소스·좌표·실제 블록 판정 구현 계약 (`G-OBS`, `G-TARGET`)

### 21.1 변환 없이 사용할 값과 변환이 필요한 값 구분

```text
Sable Rapier event-handler
    collider1 / collider2 ──(Java effect에는 collider handle 자체가 없음)
       └ contactPair manifolds[*].points[*]
          body ID 또는 -1, force_amount, local_n1/n2, local_p1/p2
    clearCollisions(J) -> 15-double effect, 최대 100건
```

- **E-01** pinned Sable Rust `event_handler.rs`에서 `local_p1/p2`는 Rapier contact point의 **각 collider-local** 좌표다. `SubLevel.logicalPose`의 원점과 임의로 동일시할 수 없다. `-1` 정적 World collider의 local point 역시 세계 절대 좌표라고 **가정 금지**. Collider 자체의 pose/offset이 Java effect에 없는 상태에서는 `world = plot.translation + rotation*raw.local_p1`을 보편 공식으로 확정하지 않는다.
- **E-02** Sable `dispatcher`에는 해당 매니폴드의 실제 block `pos_a/pos_b`가 있고, `hooks`는 이 값을 원본 콜백에 넘긴다. *실제 callback이 등록된 블록*의 owner-local `hit`은 별도 검증을 통과하면 최우선 목표 위치다. 그러나 없는 callback을 임의로 생성해서 모든 일반 블록을 통과시키면 JNI 분기가 변경될 수 있다.
- **E-03** 사후 effect에서 `bodyA/bodyB=-1`은 Plot runtime id가 없는 collider 측 sentinel이며 실제 정적 Minecraft Level과의 대응은 collider 생성 경로를 확인해야 한다. 같은 id 값이라도 **dimension+physicsSystem generation**이 바뀌면 다른 소유자다.
- **A-01** 기본 구현은 *body ID → owner*, *접촉점 후보 좌표계*, *실제 블록 조회*를 세 함수로 나눈다. 어떤 함수를 실패하더라도 다른 Plot를 인접 탐색으로 소유자 대체하지 않는다.

### 21.2 Owner 인덱스 — 이벤트마다 전체 Plot 순회 금지

`onServerLevelStart/ServerSubLevelAdded/Removed`, 분리/조립 및 서버 틱 경계의 안전한 시점에 `OwnerIndex[(dimension,runId,runtimeId)] = (stableUUID,ownerGeneration,sublevelRef,plotAccessor,lifecycle)`를 만든다. 최초 구축은 `ServerSubLevelContainer.getAllSubLevels()`를 **1회** 읽는다. 이후 변경 이벤트를 사용하되 이벤트 누락 방지용 저빈도 점검/변경된 generation에서만 재구축한다. **native 콜백 스레드에서 live Sable 컨테이너를 열거하지 않는다.** ID `-1`은 `(STATIC_COLLIDER_CANDIDATE,ServerLevel)`로 시작하여 collider-origin이 검증되었을 때만 `(STATIC_WORLD,ServerLevel)`로 승격하고, `id>=0`은 반드시 명시적인 Sable 매핑에서만 찾는다. 유효하지 않은 `id`, 타차원, 제거된 owner, 변경된 UUID, 미로딩/청크 부재를 거절한다.

`OwnerIndex`로 확정한 plot의 블록 조회는 `plot.getEmbeddedLevelAccessor().getBlockState(ownerLocalBlockPos)`와 `hasChunk/청크 확인`을 사용한다. 정적 월드는 해당 차원의 `ServerLevel.getBlockState(worldBlockPos)`를 사용한다. `getBlockState`만으로 air/ghost/shape/BE/claims가 모두 합격한 것은 아니므로 `validateTarget()`에서 추가 검사한다.

### 21.3 후보 위치 해석 단계 — 고정된 4단계 우선순위

**R1 직접 콜백 증거:** 원본이 non-null인 기존 Sable 콜백에서 `hit`의 소유 Plot 및 local frame을 증명할 수 있으면 `(owner,hit)`을 후보로 사용한다. `onCollision`/`sable$onCollision`이 모두 관측될 수 있어 직접 콜백의 중복 단일화만 수행한다. 원본 파괴/반발 지시·return 참조 및 예외는 변경하지 않는다. **콜백이 효과 배열 record와 동일 사건이라는 주장을 하지 않는다.** source가 독립일 경우 양쪽의 예산은 중복 합산하지 않는다.

**R2 교정된 native local-point:** 런타임이 collider-local point → 소유 Plot-local point에 필요한 **전체 변환**을 API·스냅샷에서 확보했을 때만 사용한다. `C_world=ColliderPose_at_contact(C_local)`; `P_owner=SubLevelOwnerPose_at_corresponding_phase^-1(C_world)`; source side가 정적이라면 `P_owner=C_world`. `ColliderPose`는 `RigidBody pose`와 항상 일치한다고 가정하지 않는다. 질량중심·embedded origin·collider 상대 transform을 각각 검증한다. 수치 비교는 단일 평면, 회전 90°, 평행 이동, 양측 서로 다른 좌표 원점 실험으로 검증한다. *단순 회전 quaternion을 통한 점 변환만으로 R2 합격 금지*.

**R3 제한된 경계 후보 탐색:** R2의 변환이 보정·검증된 하나의 side로부터 **세계 접촉점 후보**를 얻은 경우, 다른 side의 owner가 이미 지정됐다는 전제에서 그 owner의 블록 공간으로 투영하여 탐색한다. `floor(point)` 1개뿐 아니라 경계 epsilon(개발 초깃값 `1/32`블록) 때문에 인접 6개까지의 후보 집합을 생성한다. `|Candidates|<=8 per side`로 제한하고, 실제 `BlockState`/충돌 shape/AABB 안의 표면 근접도 및 source가 가리키는 동일 Plot에 속하는지 검증한다. `shape`가 복수고 단일 피격 블록을 구별하지 못하면 `TARGET_AMBIGUOUS`; **월드 전체 반경 확대 금지**. 첫 블록을 임의로 선택하지 않는다.

**R4 실험용 swept-boundary 확인:** R1~R3가 실패하지만 예측 가능한 이동 Plot의 이전 pose/현재 pose, 실제 body world AABB, 좁은 충돌 평면을 확보했다면 `swept AABB intersect near plane`에서 **이미 로드된 1~2블록 두께의 좁은 띠**만 조사해 단일 후보인지 확인한다. 이 경로는 `TARGET_INFERRED_PROVISIONAL`이며, 0.3.0 테스트에서 ground-truth로 확인되기 전 `TARGET_VERIFIED`로 표시하지 않는다. 탐색 상한 `maxBoundaryCandidates=32`/owner, `maxTotalCandidateChecksPerTick=1024`, 필요한 chunk가 비어 있으면 강제 로드하지 않는다. 대상이 여럿인 평면/기계 접촉에서 가짜 단일 블록을 만드는 대신 `TARGET_MULTI_UNRESOLVED` 진단을 남긴다.

**사실상 무자료(`-1` World side + unknown collider pose)에 대한 처리:** java effect만으로 정적 블록의 절대 위치를 항상 구할 수 없을 수 있다. **반드시 R3/R4 또는 기존 콜백의 검증 가능한 연결 경로를 실제 게임에서 시험**한다. 모두 실패해도 `G-OBS`의 native 관측 구현·빌드 자체는 완료할 수 있다. 그러나 `G-TARGET`의 정적/Plot 전유형 성공을 거짓으로 통과시키거나, 콜백을 무단 삽입하지 않는다. 이 경우 §28에 따라 해당 어댑터를 최소 확장하고 같은 버전에서 테스트한다.

### 21.4 단일 타깃 검증 알고리즘

```java
// 설명용 의사코드: 아래 record/type은 설계 타입이며 Sable 공개 클래스가 아니다.
TargetResolution resolve(Observation e, Side s, OwnerIndex idx) {
    Owner o = idx.resolve(e.dimension, e.run, e.bodyId(s));
    if (o == null || o.removed || o.ownerGeneration != e.generation) return OWNER_STALE;
    CandidateSet candidates = fromExistingCallbackIfVerified(e,s,o);
    if (candidates.empty()) candidates = fromCalibratedColliderPose(e,s,o);
    if (candidates.empty()) candidates = fromOtherSideVerifiedWorldContact(e,s,o,8);
    if (candidates.empty()) candidates = fromBoundedSweptBoundary(e,s,o,32);
    List<TargetKey> valid = distinct(candidates).stream()
        .filter(c -> c.ownerEquals(o) && c.chunkAlreadyLoaded())
        .filter(c -> !c.blockState().isAir() && c.collisionShapeRelevant())
        .filter(c -> !c.isProtected() && c.blockFingerprintMatches())
        .filter(c -> c.fromVerifiedNativeCallback()
            ? c.callbackOwnerScopeVerified()
            : c.geometryResidualWithinTestedTolerance()).toList();
    return valid.size()==1 ? VERIFIED(valid.get(0))
         : valid.isEmpty() ? TARGET_UNRESOLVED : TARGET_AMBIGUOUS;
}
```

- 후보가 2개여도 **정책적으로 분배 가능한 서로 다른 실제 접촉 surface를 독립적으로 확인한 사건**은 `MultiTargetGroup`에 넣을 수 있다. 이 경우 모든 후보는 실제 블록이어야 하고 group Q가 늘어나지 않아야 한다. 0.3.0 기본은 단일/양측 타깃 우선; 다중 분배는 0.3.1에서 확장한다.
- `blockFingerprint`는 vanilla block registry ID + 필수 상태 속성 + BE가 있는 경우 안전한 ID/버전으로 계산한다. 임의 mutable object identity나 raw Java `hashCode()`를 영속 데이터의 신뢰 가능한 해시로 사용하지 않는다. **BE 전체 NBT를 매 충돌마다 동기 직렬화하지 않는다**. 실제 commit 전에 BE identity/모드 veto를 재확인한다.
- `world`/`plot`의 XYZ를 섞지 않고, 동일 수치 좌표라도 owner가 다르면 다른 블록으로 취급한다.

### 21.5 좌표 검증의 합격 사례 및 실패 대처

| 테스트 | 기대 | 실패 시 같은 버전 안의 대체 경로 |
|---|---|---|
| Plot 석재 1개가 정적 석재 평면에 수직으로 접근 | source body ID, Plot UUID, Plot-local 석재, 정적 BlockPos를 각각 확인 | R2 world/plot 각각의 collider 상대 pose 감사 → R3 surface 후보 확인 |
| Plot 둘, 서로 다른 UUID, 한쪽 yaw=90° | `TargetKey` 양쪽을 서로의 공간에서 정확히 선택 | owner transform의 역연산·CoM·sweep 재보정; pose 불일치면 R1/다른 검증 데이터 |
| 기둥 모서리·계단·철창 | 후보 다중이면 정확도 미확정; 처음 나온 블록 임의 선택 금지 | 충돌 shape bounding/다중 분배 증거 확인 후 0.3.1에서 group |
| 몸체 회전-only가 벽에 부딪힘 | 각속도 재구성 없어도 위치·이벤트와 Q 후보가 생성 가능한지 기록 | 시간별 pose sweep + R4, 회전-only 원장 처리 시 별도 event gate |
| 청크 언로드·구조물 split 중 | 원본 이벤트는 소비되되 타깃 커밋은 취소 | 새 generation으로 인덱스 복구 후 새 이벤트만 허용 |

## 22. 신규 충격·지속 접촉·다중 이벤트를 하나의 예산으로 만드는 알고리즘

### 22.1 물리 이벤트의 한계를 반영한 입력

Sable `handle_collision_event`는 제공된 pinned Rust 소스에서 비어 있다. 같은 소스의 `handle_contact_force_event`는 힘 이벤트가 0.1 이상일 때만 처리하고, `clearCollisions`는 최대 100개를 보고한다. **배포 JNI의 정확한 cap/filter가 소스와 같은지는 실제 native 게임 로그와 비교해 확인해야 한다**(§3.2의 소스-바이너리 차이). 그러므로 **이벤트가 안 보임 ≠ 분리 증거**이고 **이벤트가 보임 ≠ 신규 충돌 증거**다. 소스에 없던 `contactStarted`·`manifoldId`·`signedClosingVelocity`를 만들어내지 않는다. 반면 실제 **첫 고강도 힘 상승 + 이전 분리된 형상 이력**을 게임 신규 타격의 정의로 채택할 수 있으며 이것은 *게임 정책*이다.

### 22.2 0.3.0에서 바로 존재해야 하는 최소 FSM, 0.3.1의 정식 원장

```text
OwnerPairKey = (serverRun, dimension, min(ownerKeyA,ownerKeyB), max(...), ownerGenerationA/B)
State = UNINITIALIZED | ARMED | ACTIVE | COOLING

UNINITIALIZED:
  1~2 안정된 관측 시점에 이전 접촉이 없고 '분리된 형상' 증거 확보 -> ARMED
  첫 관측부터 접촉 중이거나 owner 변경 직후 -> ACTIVE, 초기 충격 Q=0, reason=INITIAL_CONTACT_UNKNOWN
ARMED:
  force event peak >= forceEnterThreshold AND confirmed unique target(s)
  AND (verified relative closing OR prior calibrated separated pose/swept gap OR new shape impact rising edge)
  -> ACTIVE, credit one group (한 번만)
ACTIVE:
  원본 Sable source의 동일 ownerPair 후속 force/callback -> 동일 에피소드에 통합, 새 credit=0
  validated separation (bounded object/face gap) occurs -> COOLING
COOLING:
  separation persists for 2 consecutive physics observations -> ARMED
  contact returns before confirmed gap -> ACTIVE (새 credit=0)
owner generation/change, world unload, restart -> UNINITIALIZED (기존 credit 재사용 금지)
```

- **`prior calibrated separated pose/swept gap`**: 같은 두 owner가 *실제로 떨어져 있음*이 한 번 이상 확인된 scene에서만 인정한다. `global AABB`가 분리되었으면 충분한 증거이지만 넓은 AABB가 겹친다고 즉시 접촉 중이라는 뜻은 아니다. AABB 겹침은 `UNKNOWN`, 충돌 surface 지역의 signed separation을 검증할 수 있으면 그 값을 우선한다.
- **rising edge**는 AABB가 겹치는 대형 Plot/긴 접촉면을 위한 **게임적 잠정 보완**이다. 초기 접촉 기준선과 이후 힘 peak의 상승이 `F_peak >= max(F_enter, F_baseline * riseRatio)`임을 요구하고, **같은 contact 지역에서 별도로 검증된 새 접근/속도반전/간극 감소 중 하나가 반드시 추가**되어야 한다. `quietFrames>=2`는 신호 잡음을 걸러내는 보조조건일 뿐 단독 분리 증명이 아니다. *미수신=분리* 단독 인증 금지 및 `oneRearmPerVerifiedApproach`를 준수한다.
- **회전-only**: 선례의 `RigidBodyHandle.getAngularVelocity(Vector3d)` 또는 연속 pose quaternion 비교는 **필요한 분기에만** 호출한다. `abs(omega)`를 힘으로 대체하지 않는다. `point`가 검증된 경우 `v_contact=v_linear + omega×(point−COM)`과 source normal의 부호 검증을 **선택적 신규 충격 게이트**로 사용한다. point/normal이 없으면 검증된 회전 swept-boundary의 gap 감소 + 실제 force rise에 한하여 `GAME_PROVISIONAL_ROTATION`, 그 외에는 observation-only. 이것은 3.2절의 '엔진 물리 재구성 금지'와 양립한다: Sable solver를 돌리지 않고 **희소 사건의 판정에만 기존 상태를 사용하는 것**이다.
- *기본 제어:* 초기 접촉에서 피해가 누락될 수 있다는 사실을 로그에 표시하고, 0.3.0 게임에서 기구를 분리한 채 대기 후 타격하는 장면으로 시작한다. 0.3.1은 검증된 target-contact 지역 단위로 episode를 분할할 수 있으나, **같은 force event가 여러 구역에 복사된 때 전체 Q는 한 번만** 차감한다.

### 22.3 사후 힘의 집계 — 포인트별 수치 합산 금지

```text
pairTickPeakF = max(F_raw for all valid records in same ownerPair+physicsObservedTick)
F_episode = max(pairTickPeakF among the qualifying first-impact burst window)
Q_candidate = min(C_group, k_force * max(0, F_episode - F_floor))
Q_granted = MIN(Q_candidate, groupCreditRemaining)
```

`F_floor`는 Sable의 0.1 물리 필터와 독립적인 PDD **게임** 초기값이다. 원본 `total_force_magnitude`는 manifold의 여러 `point`에 중복 복제될 수 있으므로 `sum(points)` 금지. `F_episode`가 solver impulse나 에너지임을 주장하지 않는다. `F_raw < threshold`는 단순 충돌의 존재를 배제하지 않으며, 낮은 힘에서도 `OBSERVATION_ONLY`로 계측할 수 있다.

서로 다른 `DamageSource`에서 같은 원인(`causeGroup`)이 전달되면 우선순위를 `authorized solver impact > Sable native observed game event > preexisting special callback hint`로 둔다. 동일 사건의 소스들이 서로 다른 강도라는 이유로 서로의 Q를 단순 더하지 않는다. 별도 투사체·폭발의 *새로운 원인*은 별도 credit으로 관리한다.

### 22.4 신뢰할 수 있는 최소 예산 장부

```text
Credit(groupId, issuedQ, availableQ, allocatedA, allocatedB, spent, unused, algorithmRev)
INVARIANTS:
  finite(issuedQ) && issuedQ>=0
  allocatedA+allocatedB+unused == issuedQ (fixed-point rounding tolerance)
  spent <= issuedQ
  source duplicate, callback duplicate, two sides, same native force duplicated by points
     NEVER increase issuedQ
  availableQ is nonincreasing after initial issuance
```

- 두 target 모두 검증되면 개발 기본 `shareA=0.5`,`shareB=0.5`; 하나만 검증되면 `shareVerified=0.5`, 나머지 절반은 `UNUSED/UNKNOWN_SIDE_RESERVED`로 기록한다. 이는 **안전하게 측정 가능한 쪽만** 피해를 받도록 하는 최초 임시 정책이지 물리적 에너지 보존식이 아니다. `targetA`와 `targetB`가 같은 실제 블록/owner면 중복 합쳐 한 번만 배정한다.
- 0.3.0은 group one-credit/side max와 이중 발행 방지를 **최소한 완성**한다. 0.3.1은 `episodeGeneration`, 분리/rearm 증거, multi-source causeGroup, 다중 지역 hit의 제한적 분배, 높은 부하에서 원장 TTL/GC를 마무리한다. 따라서 0.3.0 단위 예산이 0.3.1에서 의미가 바뀌지 않는다.
- 한 그룹은 한 `sourceTruth`를 선택한다. `Record ordinal`은 로그용 배열 순번일 뿐 동일 contact ID가 아니다.

### 22.5 테스트 불변식

- 1개 native event가 4개의 `ReportedCollision` point로 보고되면 `Q`는 4배가 아니라 **동일값**.
- 사건 직후 20틱 정지 접촉 및 슬라이딩은 `ACTIVE` 유지, 신규 credit 총 0.
- **증명된 분리** 후 재접근하면 새 episode에서 한 번 credit.
- 서로 다른 Plot 소유자 ID가 비슷해도 ownerKey 충돌 금지; 오염된 generation 원장 전부 폐기.
- `F=NaN`, 배열 15배수 아님, ID overflow, 잘못된 차원, queue overflow는 Q=0 및 원인 코드.

## 23. 각 버전에 채워 넣을 시험용 수치 프로필과 영구 확정 시점

> 아래 값들은 **연산 테스트가 가능하도록 개발자가 새로 선택한 임시 숫자**다. 사용자 승인값이 아니며 세 애드온의 숫자를 실제 PDD 단위로 환산한 것도 아니다. Sable native `F_raw`의 단위는 **임의 게임 프로필 입력**으로만 사용한다. 모든 수치는 테스트 설정/서버 config에서 바꿀 수 있다. 구체적인 API 성공 여부는 여전히 실제 Gradle·게임 테스트가 결정한다.

| 최초 구현 버전 | 정책 키 | `DEV_PROVISIONAL` 기본값 | 유효 범위·비고 | 해당 버전 완료 시 사용자 확정 대상 |
|---|---|---|---|---|
| 0.3.0 | `forceEnter`,`forceFloor`,`forceGain`,`maxGroupQ` | `2.0`,`2.0`,`0.25`,`64.0` | F_raw 임의 game scale; 0.1 Sable 임계와 다름. Q∈[0,64] | 충돌 강도-예산 기본 느낌 |
| 0.3.0 | `maxCandidatesPerSide`,`boundaryCandidates`,`geometryEpsilon` | `8`,`32`,`0.03125 block` | 상한 초과 시 후보 미확정, 강제 로드 금지 | 성능/coverage 우선순위 |
| 0.3.0 | `firstObservationWarmup`,`mutationEnabled` | `2 tick`,`false` | 첫 접촉 unknown으로만 Q=0 | 0.3.0은 mutation OFF 고정 |
| 0.3.1 | `rearmSeparatedFrames`,`risingRatio`,`quietFrames` | `2`,`1.8`,`2` | 시간만 지나면 재장전 금지; 유효 분리/접근 증거 필수 | 재타격 민감도 |
| 0.3.1 | `shareA`,`shareB`,`multiTargetPerSide` | `0.5`,`0.5`,`4` | 공유 Q 합≤1, 검증 안 된 side 몫 발행 금지 | 양측 피해 균형 |
| 0.4.0 | `fMin`,`gamma`,`rMin`,`beta`,`yieldGameQ`,`fixedPointScale` | `0.08`,`1.5`,`0.15`,`1.5`,`8.0`,`256` | 경도/탄성·손상·항복. 양자화 remainder는 희소 선택 저장 | 재료 반응 강도, 경도 곡선 |
| 0.4.0 | `directShare`,`plasticShare`,`stressShare` | `0.60`,`0.15`,`0.25` | 총합 1, 실제 plastic 불가능한 경우 그 몫을 `unused`로; stress 버전 전까지 미반영 잔액으로 보관/정산 | 직손상/변형/응력 배분 |
| 0.4.1 | `worldBreakCapPerTick`,`dropMode` | `64`,`VANILLA_IF_ALLOWED` | claims/BE/월드권한 우선; BE/작업 실패 시 abort | 드롭·파괴 속도 |
| 0.5.0 | `maxSparseEntriesPerChunk`,`saveDirtyBatch` | `4096`,`128` | 비어있는 항목 저장하지 않음; 누락 대신 backpressure | 상태 보존과 메모리 비용 |
| 0.5.1 | `plotMutationCapPerTick`,`plotBEHandling` | `32`,`VETO_UNSAFE` | 먼저 simple block; BE 안전하지 않은 종류는 가짜 성공 금지 | Plot에서의 처리 정책 |
| 0.6.0 | `stressDepth`,`stressMaxNodes`,`stressMinQ`,`stressMaxNodesPerTick` | `4`,`128`,`1/256`,`512` | 유한 탐색·원장 보존, 6면 이웃 | 피해 전파 범위 |
| 0.6.1 | `splitTransferCap`,`orphanStatePolicy` | `4096`,`QUARANTINE` | 분리 lineage가 불확실하면 격리 후 검증 | 파편/상태 승계 |
| 0.7.0 | `worldMoveCap`,`deformSingleBlockMaxStep` | `16`,`1 block` | 목적지 비어 있고 블록 안전군/충격 방향 검증된 경우만 | 변형 가능 범위 |
| 0.7.1 | `plotMoveCap`,`unsafeMechanicalPolicy` | `8`,`VETO_UNSAFE` | BE/glue/운동 기계/부분 다중블록 훼손 금지 | Plot 변형 허용군 |
| 0.8.0 | `crackFadeTicks`,`visualUpdateCap` | `200`,`256 / tick` | 표시만 회복, 실제 damage 유지 | 균열 회복 시간 |
| 0.8.1 | `explosionResidualGain`,`projectileHealthGain`,`entityHitGain` | `0.25`,`1.0`,`0.5` | 게임 Q; 원래 폭발 결과가 먼저 확정된 생존 블록만 | 외부 피해 민감도 |
| 0.9.0 | `maxObservedRecords`,`maxQueuedCommit`,`p95OverheadAlert` | `2048`,`1024`,`2ms` | 100건 native upstream 제한 별도; `2ms`는 **경보**이지 성능 보증/기능 OFF 근거 아님 | 서버 부하와 설정 |
| 0.9.1 | `rcRequiredScenarios`,`baselinePreservation` | `2 test sessions`,`MANDATORY` | 세션 A/B에 여러 하위 시나리오 묶기 | 1.0 출시 가부 |
| 1.0.0 | `policySchema`,`migrationMode` | `1`,`BACKUP_THEN_UPGRADE` | 설정/손상 schema의 결정론적 마이그레이션 | 운영 기본 프로필 최종 승인 |
| DVT 1.0 | `fractureMultiplier`,`fractureDepth` | `1.0`,`4` | PDD 예산/그래프 재사용, 새 물리 solver 없음 | 파쇄 강도 |
| DVT 2.0 | `engineExplosionGain`,`maxChainHops` | `1.0`,`6` | 별도 origin/cause-group, 연쇄 상한 | 기관 폭발 밸런스 |
| DVT 3.0 | `reinforcementTBonus`,`corrosionRateTick` | `32`,`0 (default no passive decay)` | T 255 상한, 동적 modifier·별도 상태 | 강화/부식 속도 |

**예산 예시 — 계산 검증용:** `F_peak=42`, `F_floor=2`, `forceGain=0.25`면 `Q_group=min(64,0.25×40)=10`. 두 target 검증 시 각 `5`; 하나만 검증 시 해당 쪽 `5`, `UNUSED=5`; `F_peak=1.8`이면 `Q=0`. 4개 point가 모두 `F=42`여도 `Q_group=10`. 이 예시는 실제 단위/운영 밸런스의 정확성을 증명하지 않고 **정책 구현의 재현성**만 증명한다.

**물성 세트:** §7.3의 H/T 추정식, §7.4의 `rMin/beta`, §7.7의 금/흑요석 D 앵커를 시작점으로 활용한다. 실제 블록 속성마다 임시 프로필을 채울 때 `Generic = H/T 공식, D=25,E=20`를 **fallback only**로 사용하고, 특수 충돌/장식/기계 블록은 explicit override로 분기한다. `D/T/H/E`의 사용자 영구값은 해당 **0.4.0 재료 기능 완료 후** 승인 받는다. 사용자 승인 전에도 설정 파일과 단위테스트는 완성한다.

## 24. 손상·탄성·연성·저장·응력·변형의 결정론적 처리

### 24.1 0.4.0 재료 처리 순서 — 손상 계산을 매 버전 다시 바꾸지 않음

```text
1. get EffectiveMaterial using registry cache + stateOverride + protection;
   invalid or UNBREAKABLE -> reject (state unchanged).
2. Q_event belongs to one GroupCredit; side allocation already fixed.
3. at target i: Q_absorbed = Q_in * (1 - fE(E_i / 100)); Q_active = Q_in - Q_absorbed.
4. split Q_active into direct (0.60), plasticIntent (0.15), stress (0.25).
   unavailable feature/unsafe target => portion to UNUSED (not secretly direct).
5. rawDamage = max(0,Q_direct) * damageGain * fH(H_i/255)
   with provisional damageGain=1.0, fH=0.08+0.92*(1-h)^1.5.
6. accumulate via fixed point x256, u8 integer + optional u8 remainder;
   never round every tiny impact up to 1.
7. damage >= T and protection allows -> DestroyIntent;
   otherwise store DamageState; plastic only if validated direction+yield/capacity;
   stress sends existing unspent credit to bounded graph.
8. repeat at commit with (ownerGeneration,stateFingerprint,permissions,chunk loaded,BE veto).
```

- `directShare + plasticShare + stressShare = 1`은 **계정별 목적 분할**이지 Sable 충돌 에너지 보존 법칙이 아니다. `0.3.0~0.3.1`에서는 계산 전 Q의 합/배분만, `0.4.0`에서 가상 material plan을 전부 계산한다. 응력/변형을 실제 적용하지 않는 개발 버전은 `UNUSED_PLANNED`로 정산하고 가짜 저장·변형 결과를 출력하지 않는다.
- **미세손상:** `fixedPointScale=256`, `Δraw = floor(rawDamage*256)`, `damageU8=floor((prev+Δraw)/256)` 및 `remainder`를 희소 레코드에 유지한다. `damage=0,remainder=0,deformation=0,crack=0`인 레코드는 삭제. `RawDamage`가 매우 작아 `Δraw=0`인 경우 원래 원장의 미사용분을 기록하고 무한 소수 상태를 늘리지 않는다.
- **연성:** 개발 모델 `plasticCapacity = round(255×D/100)`, `yieldQ=max(0,8×(1−D/200))` (D=0도 유한 항복기준이지만 실제 변형 가능 용량은 0), `Δdeformation = min(capacity-current, floor(max(0,Q_plastic−yieldQ)×deformGain))`, `deformGain=1.0`. **연성 높은 금속이 언제나 약하다는 뜻이 아니다.** 재료별 실제 `yieldThreshold` override 우선; 부서짐은 damage T 별도 판단. `Δdeformation>0`이어도 실제 격자 이동은 0.7.0/0.7.1 조건 통과까지 `PLANNED`만 보관한다.
- **물성 자동분류:** registry/태그 기반 명시 프로필 → 블록 속성 로그 정규화 fallback → 레시피 기반 설명 가능한 후보(다수이면 검증된 최우선만) → explicit override → datapack/user override. 충돌 때 레시피 파싱 금지. `blockState`별 변동이 있는 경우 cache key는 `(registryBlockId,relevantStateProperties,materialGeneration)`.
- **기능별 정책 미설정:** 안전성에 관계없는 숫자는 `P-DEV`를 사용. 저장/충돌 정보 누락은 자료를 생성하지 않고 해당 action만 실패·재시도·격리한다.

### 24.2 0.4.1 / 0.5.1 정적·Plot 커밋

```text
immutable Intent created in server-safe stage
 -> per-dimension staging with idempotencyKey=(causeGroup,targetKey,actionKind)
 -> re-read owner + loaded chunk + BlockState fingerprint + blockEntity identity
 -> call protection/veto, verify no active Sable physics mutation
 -> World: ServerLevel vanilla block APIs with correct drop context
    Plot: ServerSubLevel.getPlot().getEmbeddedLevelAccessor()
          .setBlock / .removeBlock / .destroyBlock on *owner-local BlockPos*
 -> check boolean result, re-read state; only then APPLIED
 -> trigger/observe Sable existing onBlockChange/mass/collider/split lifecycle
 -> persist applied change and issue client delta
 -> ABORTED: record exact reason; compensating journal or conflict retry
```

- `EmbeddedPlotLevelAccessor`의 `getBlockState`, `setBlock`, `removeBlock`, `destroyBlock` 메서드는 업로드 Sable 2.0.6 JAR에서 실제 확인했다. 단, **경로의 스레드 안전성/BE 저장/Collider 갱신 부작용은 아직 인게임 검증 전**이다. 외부 `SablePreSolverDamage` 등의 원본 작업 예약 패턴을 참고하되, 별도의 `Rapier3D` mutation 호출을 추측으로 덧붙이지 않는다.
- **원자성의 한계:** Minecraft 블록 API가 여러 블록의 원자적 transaction을 제공한다고 가정하지 않는다. 다중 이동/분리 전에 `prevalidate(all)`하고, 가능한 경우 원본 상태를 소유 공간별 journal에 기록한다. 도중 실패하면 이미 적용한 항목을 안전하게 보상/격리하고 결과를 `PARTIAL_ABORT`로 남긴다. 드롭은 실제 파괴 성공 뒤 **정확히 한 번** 생성한다. 결과 조용한 누락 금지.
- **기계/BE:** 처음에는 단순 완전 블록을 파괴/이동하며 커밋 경로를 검증한다. 베드·문·대형 기계·BE·다중 블록은 lifecycle veto 또는 검증된 그룹 transaction으로 처리하고 테스트에 추가한다. 서버가 원본 Aeronautics 기계 물리/접착 이벤트를 지원하는 경우 **그 네이티브 순서를 존중**한다. 모르는 타입을 전부 자동 파괴 성공이라고 주장하지 않는다.

### 24.3 0.5.0 희소 상태 영속성 + Split/merge 무결성

- `StateKey=(dimension,ownerUUID/world,ownerGeneration?,ownerLocalBlockPos,stateFingerprint)`로 shard를 chunk/Plot별 관리한다. runtimeId는 저장키에 넣지 않는다. 여러 차원을 하나의 map으로 혼합하지 않는다.
- `damage,deformation,crackVisual,remainder`는 각각 u8이며 `materialGeneration`/checksum/lastAppliedCauseId는 필요 시 별도 메타데이터다. 모든 블록에 객체 생성 금지.
- dirty shard만 NBT 혹은 지원된 SavedData 계약에 보관. **실제 로드에서** schema 검증, 중복 좌표·잘못된 포맷·값 범위·오래된 fingerprint는 격리/기록하고 해당 블록에 잘못된 피해가 복원되지 않게 한다.
- Split: 공식 Sable split 결과에 `oldOwnerUUID -> [(newOwnerUUID, transform, movedPositions)]` lineage가 있을 때만 좌표 상태를 재귀속한다. 일대일 이동 증거 없으면 `ORPHAN_QUARANTINE`에 보류하고 과거 위치의 ghost damage를 제거한다. 단순 Plot UUID 근접/월드 좌표 일치로 상태를 합치지 않는다.
- 실제 Sable `ServerSubLevel.getSplitFromSubLevel()/getSplitFromPose()` 존재 확인. **이것만으로 모든 이동 블록의 정확한 위치 lineage가 증명되는 것은 아니다.** 실제 이동/재조립 이벤트와 블록 변화 목록으로 검증해야 한다.

### 24.4 0.6.0 응력 그래프: BFS+유입 묶음, 이중 탄성 금지

1. 실제 손상 seed의 **owner-local** 6방향 이웃과 glue/블록 기능별 경계를 검사한다. 다른 owner로 단순 건너뛰지 않는다.
2. `frontier[level]`에 `(target,amount,sourceCreditId)`를 넣고 **같은 level 같은 블록**에 들어온 금액을 먼저 합산한다. `sourceCreditId`는 회계 용도이며 새 에피소드를 생성하지 않는다.
3. 대상에서 `Q_active=Q_in*fE(E)`, `Q_absorbed=Q_in−Q_active`; direct/plastic/stress로 **한 번만** 나눈다. `Q_stress`만 이웃으로 보내고 `Q_direct`·`Q_plastic`을 더한 새 피해 재원을 만들지 않는다.
4. 방향성 normal이 verified면 `w`를 `max(0,dot(localNormal,neighborFace))` 기반으로 계산한 뒤 정규화한다. 그 외에는 연결된 6면 이웃에 균등 `w=1/N`. 정지/claims/shape가 block하면 해당 몫은 `UNUSED`, 다른 이웃으로 증식시키지 않는다.
5. `depth<=4`, unique node<=128, per-tick<=512, minQ>=1/256. **동일 episode+node+depth**를 반복 처리하지 않고 여러 경로에서 온 액수는 합산한다. 사이클의 이미 방문된 노드로 돌아가려는 에너지는 소비/잔액으로 기록한다. queue가 찼으면 줄이지 않고 bounded deferred work를 남겨 TTL 만료 시 명시적 `EXPIRED_UNUSED` 기록.
6. **회계 시험:** 1→2→1 고리, 분기 후 재합류, 같은 블록에 두 경로가 들어오는 사례에서 최초 `Q_group`보다 총 사용·흡수·잔액이 커지지 않아야 한다.

### 24.5 0.7.0 / 0.7.1 실제 격자 변형 — 실패를 파괴로 속이지 않음

- 입력 `DeformationIntent=(ownerLocalStart, verifiedDirection, integerStep, requestedPlasticWork, ownerGeneration, stateFingerprint)`. `verifiedDirection`은 **검증된 world/Plot local normal**, 아니면 `DEFORMATION_DIRECTION_UNAVAILABLE`로 해당 **이동만** 보류한다. 손상/응력은 이미 계산된 경우 계속 유지한다.
- `moveCandidate = start + sign(verifiedDirection dominant axis)`를 우선 테스트한다. tie 축은 `X>Y>Z`의 안정적 우선순위로 결정하되 동률 때문에 실제 점을 발명한 것으로 표시하지 않는다. 명시적 플레이테스트에서 물체를 안쪽으로 밀어야 하므로 **impact normal의 부호 검증**을 포함한다.
- source/target은 같은 owner, 목적지는 이미 로드, 대체 가능/air, claims·BE·기계 연결·물리 지지 위반 없음, 고정 블록 이외는 veto. 미충족이면 `DEFORMATION_ABORT_REASON`을 남기고 **파괴 전환을 자동 수행하지 않는다**.
- World는 `ServerLevel`의 실제 state 이동과 이웃 notify. Plot은 `EmbeddedPlotLevelAccessor`가 제공하는 실제 state/BE 갱신과 Sable mass/collider/heatmap 재계산 수명주기를 준수한다. **중간 상태를 임의로 두 tick에 나눠 구조물 glue를 잃지 않는다.** 한 그룹을 먼저 전체 preflight하고 commit/rollback 처리한다.
- `deformationU8`은 **소성 이력량**이고 실제 world/Plot 한 칸 이동과 별도다. 실제 이동이 실패하면 `deformationApplied`로 표시하지 말고 `potentialPlastic=UNUSED_OR_CARRIED_POLICY`로 정산한다.

### 24.6 0.8.0 시각화 / 0.8.1 외부 피해

- 표시 세기는 `visual=round(255×damage/T)`의 상한 연속값으로 시작하되 원래 `damageU8`은 변하지 않는다. `visualDecay`는 테스트 기본 200tick 동안 단계별 0으로 하강시킬 수 있고, 피해가 다시 발생하면 증가한다. 클라이언트에는 dirty target만 보내며 packet에는 dimension/ownerUUID/localPos/visualVersion/expiry가 포함된다. 언로드·재접속에서 유령 균열 0.
- 폭발: vanilla/Sable의 실제 파괴 결과와 world/Plot owner가 동기화된 **사후 생존 대상**만 받아 `Q_extra = clamp(residualGameSignal *0.25,0,C_group)`. 이미 파괴된 상태, 같은 원인 duplicate는 추가 Drop/credit 금지.
- 투사체/엔티티: 가능한 경우 NeoForge/Minecraft의 검증된 원인 ID, source entity UUID, hitResult/targetBlock, damage amount를 수집. 물리 impulse가 없으면 `Q_game = clamp(healthDamage*1.0,0,C_group)` (entity=0.5)라는 **게임 정책**을 사용한다. 타격 시점을 기준으로 *정확한 블록인지* 확인, 이미 제거된 투사체의 동일 원인 재발행 금지. 근거 없는 밀폐된 Plot 내부 타격은 별도 local ray 검증으로 좁힌다.

## 25. DVT 1.0/2.0/3.0까지 재사용되는 공통 구현 계약

PDD와 DVT의 **공통 안정 API는 native 물리 엔진이 아니라 게임 credit+material+intent+commit**이다. DVT는 Sable 콜백을 재등록하지 않으며 자체 damage ledger를 추가로 유지하지 않는다. 아래는 **개발할 PDD 내부 API 설계 타입**으로, 현재 모드 JAR이 제공한다는 의미가 아니다.

```java
record DamageOrigin(String kind, UUID causeId, String dimension, long serverEpoch,
                    String quality, boolean physicalUnitVerified) {}
record TargetRef(String dimension, String ownerSpace, UUID stableOwner,
                 long ownerGeneration, long packedLocalBlockPos, String stateKey) {}
record GroupBudget(UUID groupId, DamageOrigin source, double totalGameQ,
                   String algorithmRevision, String provisionalPolicyId) {}
record DamageAllocation(GroupBudget credit, TargetRef target, double allocatedGameQ) {}
record MaterialSnapshot(int hardnessU8, int thresholdU8, int ductilityPctU8,
                        int elasticityPctU8, int generation, boolean unbreakable) {}
record DamagePlan(DamageAllocation source, MaterialSnapshot material,
                  String intentType, double budgetDebit) {}
// 실제 구현 시 접근 제한자/패키지/스레드 계약·classloader까지 검증.
```

- **DVT 1.0 Fracture**: `SABLE_COLLISION` 또는 `DVT_FRACTURE` 원인의 budget을 PDD `DamagePlanner→StressGraph→SafeCommit`에 위임. 파쇄 기준은 PDD `H/T/E/D` 프로필 및 DVT 전용 `fractureMultiplier=1`로 튜닝하며 PDD 원장 중복 발행 없음. 파편 분리는 **Sable 기존 split 경로**.
- **DVT 2.0 Big Bang**: `DVT_ENGINE_FAILURE` 원인은 엔진 상태/연료 검증 후 **새 causeId**로 등록한다. `Q_engine=clamp(explosionStrength*engineExplosionGain,0,configBound)`; 함선 질량 보정이 들어가면 `MassData.getMass()`의 유효성/게임 스케일만 사용하고 멀티캐스트 budget을 하나의 그룹으로 발행한다. 인접 기관 연쇄는 BFS `maxChainHops=6`, `visitedEngineId`, 이미 폭발한 엔진 중복 차단. 연료상태가 미검증인 경우 엔진 폭발을 추정 생성하지 않는다.
- **DVT 3.0 Reinforce**: `MaterialModifier(sourceDVT,id,priority,validTime,deltaH,deltaT,deltaD,deltaE)`를 immutable snapshot에 합성한다. `USER_OVERRIDE > EXPLICIT_BLOCK > DVT_STATE_MODIFIER > MATERIAL_PROFILE > INFERRED` 우선순위를 기본값으로 두고 동일 우선순위는 stable ID로 정렬한다. 강화는 `T+=32` 같은 테스트값을 적용하되 `u8` 범위를 벗어나지 않으며, 파손된 좌표별 손상 원장을 다시 생성하지 않는다. 부식/습윤은 별도의 동적 희소 상태에 저장하고 실제 Sable mass/collider 갱신이 필요한 경우만 안전한 lifecycle adapter 호출.
- **원인 전파**: `PROJECTILE->EXPLOSION->CONTACT`는 서로 다른 실제 사건이라면 각자 고유 causeId를 가지되 parent causeGroup을 공유하고, 그룹별 explicit game policy로 재귀 중복을 억제한다. 각 commit의 target fingerprint CAS로 최종 파괴/드롭 1회.
- **호환성**: DVT 확장 jar 없이 PDD 단독 정상 로드. DVT 구체 클래스를 PDD static 초기화 경로에 직접 import해 실행 오류 만들지 않는다. 인터페이스/전송 모델은 Java 21/NeoForge classpath 검증 후 versioned API로 제공한다.

## 26. 기능 버전별 '구현 완료'와 '값 확정' 판정표

**서로 다른 개발 채팅이 이 표를 따라야 한다.** 단일 버전에 할당된 기능은 구현하고, 코드 속 `algorithmRevision`, 기본 프로필 및 실패 근거를 남긴다. 독립 테스트가 어려운 경우 자체 deterministic fixture를 만들어 실게임에서 판정 가능한 로그를 남기되, 테스트 삭제/비활성화 금지.

| 버전 | 반드시 구현한 뒤 검사할 실제 알고리즘 | 신설해야 할 대표 JUnit/실게임 기준 | 값 확정 시점 |
|---|---|---|---|
| 0.3.0realFix | §21 owner/target R1→R4 + §22 one-credit 최소 FSM + §23 bounded proxy; Sable 원본 effect observer·null callback 보존 | 15필드/NaN/overflow/2면 중복, 단일 BlockPos, world/plot와 plot/plot, 회전-only shadow. 인게임 mutation OFF | `force/geometry/first credit` 초기값 공개, 피드백 취합 |
| 0.3.1 | §22 rearm/episode owner generation + multi-target bounded distribution | 지속 접촉 20tick 무중복, 분리 후 재타격 1회, 4point 1credit, reboot owner epoch | rearm·양측 비율 확정 후보 |
| 0.4.0 | §24.1 registry cache, H/T/D/E, elastic/direct/plastic/stress conservation, fixed point | 단조 H, E 1회, 1/256 손상, 중복 debit 0, datapack reloading | 물성 곡선·항복/배분값 |
| 0.4.1 | §24.2 world block mutation + 보호·Drop·idempotency | 단일 stone 파괴 정확히 1회; 불괴/claims veto, callback 내부 변조 0 | 정적 드롭/파괴 cap |
| 0.5.0 | §24.3 chunk sparse persistence/restore/replace | reload damage 유지, 바뀐 block fingerprint drop, unload 안전 | remainder·저장 정책 |
| 0.5.1 | §24.2 Sable Plot accessor commit, BE/collider/mass lifecycle | owner-local block만 변형/파괴, World 동좌표 무변경, PLOT 변경 반영 | Plot별 cap/BE 대상 |
| 0.6.0 | §24.4 6-face BFS·다중 유입 회계 | DAG 합류, 원형 연결, Q 총계 상한, max nodes/depth | 응력 범위 |
| 0.6.1 | §24.3 split UUID lineage + glue/BE/state migration | ship 2개 파편 분리, 과거 손상 이동, 재조립 시 불연결 합병 금지 | 격리/상태 승계 |
| 0.7.0 | §24.5 world 격자 1칸 변형, conflict preflight | 자유 1칸 변형 가능, 목적지 막힘 abort, 드롭 중복 0 | worldMove 정책 |
| 0.7.1 | §24.5 Plot 격자 변형 및 mass/collider/BE/glue life cycle | 회전 Plot의 local 방향, 구조물 물리 정상, BE veto | Plot 이동/기계 예외 |
| 0.8.0 | §24.6 crack and client dirty delta | UI visual fade 후 damage unchanged, 재접속 ghost 없음 | fade 속도 |
| 0.8.1 | §24.6 vanilla explosion/projectile/entity adapters | 원본 폭발 드롭 유지, 잔여 생존 피해만 추가, 투사체 1회 | 외부 피해 비율 |
| 0.9.0 | §11 profile/optimizer/GC/backpressure | 10+ player 대표 부하, P95, optimizer optional on/off, queue overflow reason | 서버 운영 cap |
| 0.9.1 | 2회 게임 시나리오에 전체 콜백·월드/Plot·회전·split·외부피해·재시작 통합 | 기존 회귀+신규 통합 전부 PASS, 남은 미지원/성능 수치 명시 | RC 밸런스 |
| 1.0.0 | 독립 설치, migration, 사용 설명, full-source ZIP, 배포 JAR 구분 | clean test/build, Actions, game RC, ZIP CRC, schema v1~ | 운영자 확정·기본 프로필 릴리스 |
| DVT 1~3 | §25 내부 API·원장 재사용, 각각 파쇄/기관폭발/보강+부식 | PDD 부재 의존 오류/중복 ledger/원인 순환 여부 각 테스트 | DVT 각 기능 버전 종료 |

**안전성의 합격을 완화하지 않는 것:** 숫자 튜닝은 사용자의 영역이지만 `원본 callback 보존`, `owner/target 증거`, `실제 world mutation 성공 여부`, `원장 차감/중복 금지`, `법선/좌표 검증`, `Gradle/JUnit/Actions 실제 결과`는 기술적 사실이다. 이 항목을 사용자의 취향으로 전가하거나, 디버그 로그만 추가한 버전을 전체 기능 구현 완료로 판정할 수 없다.

## 27. 인게임 2회 제한과 필수 로그 — 새 버전마다 축적하는 시험 체계

**세션 A: 대표적인 수직·병진 충돌 + 같은 자리 지속 접촉**

1. Sable Plot 단일 석재/사슬/보강 블록을 정적 돌벽에 충돌시키고, 각각 독립적으로 바디 ID/UUID 및 `ownerLocal` 실제 블록을 눈으로 확인한다. 그 다음 움직이지 않고 20틱 접촉시켜 중복 credit이 없는지 관측한다. World의 똑같은 XYZ가 Plot 물체로 잘못 수정되지 않아야 한다.
2. `PDD-OBS(source=POST_EFFECT,rawRecords,dim,bodyIds,forceMax,discarded/unknown)`, `PDD-TARGET(ownerGeneration,uuid,frameMethod=R1~R4,target,shapeDistance,reason)`, `PDD-EPISODE(state,previousSeparated,forceRise,creditCount,Q,share,duplicateReason)` 세 로그가 구체적 실제 값을 포함하는지 확인한다. `F=42`일 때 정책 Q=10 예시와 동일 계산인지 단위테스트 결과와 대조한다.
3. 다음 버전의 damage commit이 활성화되면 서버 변경 결과를 별도로 확인하고 original callback 관련 Sable 기능을 비교한다.

**세션 B: 회전/Plot↔Plot/다중 충돌 + split/재시작/외부 source**

1. Plot 두 개의 서로 다른 방향 모서리와 회전 중 구조물끼리 충돌시켜 양쪽 UUID와 실제 서로 다른 블록이 식별되는지 확인한다. 둘 사이의 pose를 분리시킨 뒤 다시 충돌해 **새 사건은 1회**, 지속 접촉에는 0회 credit인지 확인한다.
2. 해당 버전부터 split/BE/변형/저장/균열/폭발·투사체 기능을 시험에 추가한다. 과거부터 존재하는 테스트는 과잉으로 다시 수행할 필요 없으나 **변경 영향 범위의 회귀 테스트는 누락 금지**.
3. 실패 시 장면-이벤트-실제 owner 상태-원장/커밋 ID로 원인을 추적한다. 사용자가 재현할 수 있는 로그 예시/성공 기준을 제공하고 `unresolved`를 `PASS`로 바꾸지 않는다. `native upstream 100 cap`에서 일부 충돌이 사라질 수 있으므로 외부 observer가 `dropped=0`이라도 full coverage 주장 금지.

**최소 구조화된 필드:** `runId,dimension,serverTick,ownerUUID/runtimeId/epoch,originalSource,rawShapeEvidence,frameMethod,actualBlockState,sourceQuality,phase,forcePeak,policyVersion,policyProvisional,gameQ,creditedA/B,reason,worldMutationState,commitOutcome,sourceSha`. 로그는 rate limit·요약 카운터를 모두 포함하며 정상 사건/거절 사건의 대표 예시가 각각 나와야 한다.

## 28. 기술 한계가 나타났을 때의 유연한 변경 트리 — 빌드를 중단하는 대신 대안을 구현

**A. Java 기존 API로 충돌 수집 성공 / 타깃만 실패** → `OwnerIndex`·콜라이더/pose R2 변환 디버그 → R3 경계 shape 검사 → R4 보수 swept geometry → 기존 non-null callback target 보강 → 필요한 경우 **Sable 2.0.6의 확인된 Java 호출 경로에 한정된 작은 Mixin/Accessor**를 추가해 정확한 per-block 정보 캡처. 각 선택은 **원본 물리 결과·호출 순서·데이터 구조 변경 금지**와 기존 Build/Test/Apply를 유지한다. 여전히 불가능하면 `G-TARGET`의 해당 장면을 FAIL로 남기고 그 누락의 재현 근거를 보고한다; `G-OBS` 합격과 전체 빌드 실행 자체를 취소하지 않는다.

**B. 신규 타격 판정이 계속 관측만 됨** → 두 body의 pre/post 실제 velocity 및 pose를 관련 episode에만 추가 캡처 → 추정 사전 접근/분리 거리·힘 상승 gate를 선택적으로 향상 → 회전-only 사건에만 verified ω×r 또는 swept geometry를 추가 → 2회 게임 테스트에서 `rest/slide/impact/reimpact` 반례를 비교한다. `onCollision`의 abs scalar로 signed 접근을 발명하지 않는다. **긍정 예산이 없다는 이유로 숫자 threshold만 0에 가깝게 변경 금지**.

**C. 손상 수치가 비정상/잔뜩 파괴됨** → 같은 source 15배열의 force 중복/조인트 이벤트/재발행 증거 분석 → 1credit·groupCap·material 계정으로 수정 → 정책 계수만 조정. 재료식이 물리량과 다르다는 이유로 Sable solver 자체 교체 금지.

**D. Plot 변경·BE·split 문제** → `EmbeddedPlotLevelAccessor`로 실제 owner-local mutation 동작 추적 → 공식 lifecycle observer / deferred queue / state fingerprint CAS 구현 → 영향 받은 기계·BE를 좁게 veto하고 안전한 대체 처리. 직접 `Rapier3D.changeBlock` 또는 `removeSubLevel` 호출을 추측해서 해결하지 않는다.

**E. 하드 제약(기존 API에서 필요한 정보가 정말 없음)** → 그 기능의 내부 `Adapter`만 교체하고 사용자 입력 없이 가능한 Java Mixin / official lifecycle / 최소 조회를 순서대로 평가. 네이티브 새 ABI가 유일한 현실적 경로인 특정 **선택적 정밀 계측**에 대해서만 `NATIVE_OPT_UNAVAILABLE` 표시. 소스 수정 권리/라이선스 없이 원본 Sable 배포물을 덮어쓰지 않는다.

**F. 코드가 컴파일 또는 JUnit 실패** → 원본 Sable JAR/Companion/Jar-in-Jar 및 compileClasspath/testCompileClasspath/testRuntimeClasspath를 실제 확인 → 해당 signature/이벤트 시점 수정 → 같은 버전에서 `./gradlew clean test --no-daemon`과 `./gradlew build --no-daemon` 전체 재실행. 기존 Actions를 우회하지 않는다.

**기본 종료 방식:** `동작하는 최선의 구현 + 테스트 + 설정 가능한 임시 수치 + 미지원/미검증 명시 + 전체 소스 ZIP`을 목표로 한다. 단, 실제 빌드 실패한 코드를 완성품이라 하지 않는다. 개발자 판단으로 변경하는 것은 **내부 구현 방식과 잠정 수치**뿐이고, 사용자가 요구한 기능을 조용히 제외하는 것은 허용하지 않는다.
