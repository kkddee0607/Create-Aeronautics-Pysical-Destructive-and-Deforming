# PDD 통합 설계서 1.0.7

**Physical Destruction and Deforming — 1.0.7 전 버전 공통 규범·F 선배분·데이터 소스·월드 적용 계약 통합 개정**

> **개정 기준:** 2026-11-06. 이 문서는 **PDD 특정 모드 버전의 개발 노트가 아니라 모든 후속 버전이 따라야 할 규범적 설계**다. 설계가 규정하는 최종 기능 목표와 각 개발 릴리스의 활성화 범위를 구별한다. 기능의 완료/빌드 통과 여부는 이 문서가 아니라 해당 커밋의 검증 장부에 기록한다.
>
> **1.0.7의 상위 규칙:** **충돌 승인 → 원인별 단일 F 예산 → 측별 F 한도 → 소유 공간 내부 F 선배분·경로별 감쇠 → 블록마다 G(Fᵢ)로 Q 후보 산출 → 그룹·측별 Q 캡 → 각 블록 H/T/D/E 적용 → 별도 Q-stress 계획 → 안전한 서버 커밋·영속화**. 기존 `Q 전체 선계산 → 블록 균등 분배` 문구는 현재/후속 구현의 규범이 아니다. 다른 손상 소스는 검증된 원인별 변환 어댑터를 통해 동일 Q 이후 파이프라인에 들어온다.
>
> **동일 문서 내 충돌 해결:** 아래 모든 개정 삽입과 §17·§29·§30~§33의 **1.0.7 통합 규범**이 기존 1.0.6 역사적 설명·수치·버전 상태 주장보다 우선한다. 이전 §6.1·§22.3/4·§24.1에 남아 있던 선(先)Q 균분, §7.2의 오래된 추론 우선 로딩, §8의 Q-stress와 F propagation 동의어 사용, §23의 오래된 계수는 1.0.7에서 교체한다.

> **1.0.5-A 이력 결론 (1.0.6 규범과 함께 해석):** PDD 게임 기능에 필요한 *충돌 사실·실제 소유 블록·증거 있는 게임 충격 예산*과, 솔버 수준에서만 가능한 *정확한 접촉별 임펄스·회전 접점 속도*를 분리한다. 지금 제공된 Sable/JNI로 작동하는 방식부터 0.3.0을 구현할 수 있다. 별도 JNI exporter는 **정밀도 고도화 옵션**이며 PDD 전체 진행을 차단하는 보편적 필수조건이 아니다. 정밀도 한계를 숨기거나 근사 예산을 실제 임펄스로 표기하는 것은 금지한다.

| 메타데이터 | 값 |
|---|---|
| 문서 버전 | **1.0.7 (전 버전 공통 통합 계약)** — 모드 배포 버전과 독립 |
| 직접 부모 설계 | `PDD-1.0.6-Integrated-Design-Specification.md` (1.0.0~1.0.5 및 R2 요구 승계) |
| 목표 | **PDD 1.0.0** 및 **DVT 1.0 Fracture / DVT 2.0 Big Bang / DVT 3.0 Reinforce** 확장 경계 |
| 플랫폼 | MC JE 1.21.1 · NeoForge 21.1.x · Create 6.0.10 · Aeronautics 1.3.2 · Sable 2.0.6 · Java 21 |
| 참고 소스 스냅샷 | `292dbaf0baafb6eedeaf01a1173f871563dac133` (`main`, 조회 기준 2026-11-06, PDD 0.4.0colFix2) — 현재 영구 버전 표기가 아닌 정적 비교 기준 |
| Sable 공식 소스 | `ryanhcode/sable` `ad8b4d377741fbd923f4d863b2cf9e462daff9a6` (tag `mc1.21.1-2.0.6-neoforge`) |
| Rapier 소스 | Sable Cargo.lock/설계서에 기록된 fork SHA `38e92f117590862481a53df6fc69a5d893e29186` (참조 커밋; 별도 새 네이티브 빌드는 수행하지 않음) |
| 검증 종류 | **직접 JAR `javap -c -p -s`**, 7개 JAR SHA-256, 내장 53개 JNI 선언/심볼 비교, pinned GitHub Rust 원본 호출 경로, GitHub PDD HEAD/트리/워크플로 |
| 이번 산출물 상태 | **1.0.7 통합 설계 문서만 작성**. 신규 Java 기능 구현/Gradle·JUnit·Actions·인게임 검증 미실행. 과거 JAR/API 증거의 출처 범위와 새 제안값은 분리 |

**이력 설명(현재 규범 아님):** 1.0.5 §20~§28의 구체 구현 계약 > 1.0.5 §0~§19의 원칙/요구사항 > 1.0.4 이전 설계. 같은 파일의 일반적 미정 문구보다 새 임시 프로필/알고리즘이 우선한다. 원본 요구 기능을 삭제하거나 안전 불변식을 약화하는 뜻은 아니다. 이 파일 안에 남겨둔 1.0.4 계승 절(물성·영속성 등)은 1.0.5의 source quality/예산/버전 계약에 따라서만 해석한다. 0.3.0의 목표는 *실제 게임에 유효한 비침습 충돌 사건 브리지*이다. 원자적 solver point/impulse는 검증될 때 추가 선택할 수 있으나 이를 기다리며 다시 계측-only 버전을 발행하지 않는다.

---

**1.0.5-A 변경 요약:** §20 개발 프로토콜, §21 실제 블록 타깃 R1~R4, §22 신규 충격 FSM/중복 원장, §23 버전별 시험 기본값, §24 재료·파괴·저장·응력·변형, §25 DVT, §26 릴리스 게이트, §27 두 번의 인게임 검증, §28 허용 대체 구현 경로를 추가했다. §6.1·§7.5/7.6/7.7·§13·§14.3의 불명확한 기본값을 이 구현 계약에 연결했다.



> **1.0.6에서 계승한 안전 불변식 (2026-11-03):** Sable 원본 물리/콜백은 보존한다. R2의 양측 독립 owner-local 충돌 형상과 검증된 분리·새 접근이 있어야 게임 Q를 승인한다. Q는 물리적 충격량·에너지와 다르다. 충격당 한 번 발행한 유한 Credit를 **재료 계획, 안전 커밋, 희소 영속 상태**가 소비한다. 검증되지 않은 좌표·방향·접촉 시점·기하를 보충해 피해를 발행하지 않는다. 실제 코어 계산에 필요한 최소 상태만 보존하고, 고비용 진단과 선택적 정밀 물리 경로는 기본 hot path에 넣지 않는다.

> **현재 우선순위:** **1.0.7 §0~§33의 명시적 통합 규범** > 호환되는 1.0.6 본문 > 1.0.5 및 구 설계의 역사적 설명. 1.0.5의 옛 수치/가상 클래스명/과거 구현 지시는 1.0.6의 승인 게이트·현재 구현 기준과 충돌하면 적용하지 않는다. 안전한 외부 API·물성 계산·저장·분리 계약은 개발 버전과 무관하게 동일하며, 버전 표는 기능을 활성화하는 단계만 정한다.

> **과거 1.0.6 작성 기준:** GitHub `main` `473dbdd58286cda5a84941b3bcc5eb60ec711c81` (`0.3.1QFix`, 2026-11-02 UTC), 공식 Sable 2.0.6 Java/Rust 포렌식 및 `docs/research/`의 plotRog 결정 보고서, `docs/verification/0.3.1QFix.md`. 문서 개정은 **후속 기능 구현이나 인게임 통과를 의미하지 않는다**.

## 0. 판독법·용어·규범의 강제력

> **1.0.7 판독 규칙 / 모든 버전에 공통:** 문서 내 `현재`, `현존`, `구현 예정`처럼 쓰인 과거 1.0.6 본문의 표현은 그 문서의 당시 코드 비교 **이력**이다. 현재 구현 사실은 §29의 고정 SHA 스냅샷으로만 확인하고, 후속 릴리스마다 별도 검증 기록으로 다시 판정한다. 아래의 기능 계약과 안전 불변식은 로드맵 버전 번호와 무관하게 계속 유효하다.

- **규범 출처를 다섯 종류로 기록한다:** `[USER_CONFIRMED]` = 사용자가 명시적으로 정한 동작; `[BASELINE]` = 1.0.6의 보존된 안전 계약; `[DERIVED]` = 동시 만족을 위해 이번 문서에서 형식화한 알고리즘·자료구조 규칙; `[DEV_POLICY]` = 조정 가능한 임시 게임·성능 계수; `[API_EVIDENCE]` = 특정 바이너리·소스와 해시에서 확인된 API 사실. `[DERIVED]`와 `[DEV_POLICY]`를 사용자 확정 물리 상수로 속이지 않는다.
- **입력 산출물:** 1.0.6 전체, F 선배분 설계 v0.1, v0.2, 0.4.1 물성 데이터 소스 보고서, 블록·아이템 CSV 및 현재 구현 차이 분석. v0.1의 미결정 항목은 v0.2로 해결된 범위만 확정하고 남은 이견은 §32에 명시한다.
- **단일 정의:** `F`는 PDD가 충돌 소스에서 승인한 비음수 유한 *게임 강도 지표*, `Q`는 그로부터 계산하는 *게임 피해·변형·응력 예산*. Sable raw force, J(impulse), 물리 에너지, `F`와 `Q`의 단위가 같다는 가정은 금지.
- **모드 버전 독립 개발:** 버전은 기능 게이트를 어떤 릴리스에서 켤지를 정할 뿐, 계산식·소유권·저장·자료 우선순위를 바꾸지 않는다. 새 Sable/NeoForge 버전은 어댑터의 API 검증 대상으로, 검증 없는 시그니처 승계를 금지한다.


### 0.0 버전 및 문서 강제력 (1.0.6, 규범)

- 새 버전 1.0.6은 1.0.5-A의 안전 원칙과 모든 기능 목표를 계승한다. §21~§26의 알고리즘과 실제 API 사실이 다른 곳의 이전 문구와 충돌하면 **1.0.6 내용을 우선**한다.
- **1.0.6 작성 당시** `0.3.1QFix`는 분석 대상 소스였고 World mutation OFF였다. 현재 상태는 §29의 고정 0.4.0colFix2 비교 스냅샷에 한정한다. 보고서에서 '구현 권고'로 언급된 section skip, 지역별 rearm, direction evidence, Receipt, commit journal은 **이미 구현됐다거나 Sable API가 있다는 주장이 아니다**.
- 개발 우선 기준: **정확한 소유권·중복 없는 Q·데이터 손상 방지**를 필수조건으로 고정한다. 이 조건 내에서 서버 비용 절감, 최소 구현, 자연스러운 피해를 순서대로 최적화한다.


- **MUST / 반드시**: 기능·무결성·안전성 계약으로 변경 불가. 변경은 설계 개정 제안과 사용자 승인 후 처리한다.
- **MUST NOT / 금지**: Sable 동작 손상, 근거 없는 입력, 중복 피해, 버전 진척 없는 계측 릴리스 등 허용하지 않는다.
- **SHOULD / 우선**: 타당한 근거가 있을 때만 동등 대안 선택이 가능하다.
- **POLICY**: 사용자 결정을 필요로 하는 게임 규칙 또는 밸런스 값. 미결정 항목은 코드가 안전한 기본값/설정 범위로 분리하되 임의로 영구 확정하지 않는다.
- **EVIDENCE**: 실제 바이너리/JAR·GitHub 소스·단위시험·실행 로그로 확인한 근거. 소스 존재와 게임 실행 성공을 혼동하지 않는다.
- **GATE**: 다음 단계 구현/릴리스 승격을 승인하는 기계적 합격 조건. '다시 조사하기'를 자동으로 뜻하지 않는다.
- **PDD 1.0.0**은 최초 전체 기능 목표 모드 버전, **통합 설계서 1.0.7**은 여러 모드 버전에 재사용되는 독립 규범 문서의 버전이다.
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

### 0.5 통합 설계서 1.0.5의 역사적 결론: 기능 경로와 정밀 소스 경로의 명시적 분리

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

### 1.0.7 기능 범위의 불변 조건

1. 물리 충돌과 World/Plot 접촉은 **충돌 최초 승인·F 선배분·블록별 Q 변환**을 사용한다. `Q_group`을 처음부터 모든 블록에 균등 배분하는 구 정책을 신규 구현에서 재도입하지 않는다.
2. F 전파는 단일 원인/동일 owner-local 공간에서 인접 실제 블록의 **게임 예산 도달 계획**이다. Q-stress는 이미 생성된 블록의 `Q_stress` 몫을 이웃에게 전하는 **별도 피해 메커니즘**이다. 두 종류를 동일 함수·예산으로 이중 계산하지 않는다.
3. 모든 등록 **블록 정의의 H/T/D/E** 및 등록 **아이템 정의의 H/D/E**는 외부 CSV·JAR·추론·기본값의 4단계 **속성별** 결정 과정을 거친다. 아이템 데이터는 재료/레시피의 원천이며 아이템 HP·아이템 파괴 기능을 자동 추가한다는 뜻이 아니다.
4. 정적 물성(`definition+필요 BlockState`)과 좌표별 손상(`damage/deformation/crack/remainder`)은 완전히 분리한다. 실제 World/Plot 변조·시각화·DVT가 추가되어도 같은 식별·원장·서버 권위 계약을 사용한다.
5. 신규 기능은 언제 개발하든 §31의 해당 **능력별 선행 게이트**를 통과해야 한다. 과거 0.4.x/0.6.x 등 표는 구현 순서의 초기 계획이지 다른 버전에서 기능을 개발하지 못하게 하는 기술적 제한이 아니다.


### 1.0 영구적인 최종 기능 정의 (1.0.6, 규범)

물리 구조물 간·World 간 충돌로부터 실제 대상 블록의 누적 손상/파괴/격자 변형/응력과 시각화를 구현한다. 근본 입력은 **검증된 owner-local 대상과 승인된 원인 Credit**이다. 소스 정밀도 미제공은 명시하고, 실제 효과가 있는 경로를 보호된 게임 정책으로 제공한다. 추가 충돌 정밀도/JNI 개발은 선택적 고도화이지 전체 기능의 무조건적 선행조건이 아니다. 1.0.0까지 원래 목표를 삭제하지 않는다.


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

### 2.5 F/CSV 통합의 증거 수명

- 승인된 사용자 게임 규칙(순차 반분 F, 탄성 경로, 정상 종단 전량)과 source ABI/JAR의 객관적 사실을 **서로 다른 장부**로 관리한다.
- `algorithmRevision`은 **F/Q 분배 방식·탄성 감쇠·손상 반올림·응력/변형** 의미 변경 시 갱신한다. `materialGeneration`은 CSV/레시피/프로필/상태 물성 결과가 원자적으로 바뀔 때 갱신한다. `sourcePolicyRevision`/`sourceEvidence`는 원인 strength 전환이나 Sable 증거 품질이 바뀔 때 구분한다.
- 외부/내장 CSV는 `sha256`, schema, 파일 provenance, 처리 행 수/무효 ID 수, snapshot generation을 기록한다. 파일명 동일만으로 같은 물성이라는 주장을 금지한다.
- 분배 중 material reload가 발생해도 기존 episode plan과 Receipt는 승인 시점 세대의 스냅샷을 고정한다. 버전 업으로 기존 손상을 소급 재계산하거나 동일 Credit를 증액하지 않는다.


### 2.0 증거 수명 및 재사용 (1.0.6, 규범)

증거를 ① release JAR/API 정적 선언, ② Sable 공식 pinned Rust 의미, ③ PDD Java 소스 실제 분기, ④ Actions/JUnit, ⑤ 게임 장면의 관찰 사실로 구분한다. A/B witness의 world residual은 진단 전용, plotRog `NativeContactMassEpoch`은 사용한 시점에만 확정한다. QFix World separation은 마지막 World 블록이 아닌 전체 loaded 영역의 긍정 증명이다. 코드 변경/버전 변경 없이 과거 게임 로그를 최신 버전 성공이라고 재사용하지 않는다.


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

### 3.7 네이티브 방향과 이후 버전의 어댑터 계약 — 1.0.7

- **확인 대상은 Sable 2.0.6 내부 Rapier Jar-in-Jar·고정 Rust 소스(공개 API 아님):** `Rapier3D.clearCollisions(long):double[]`(패키지 전용 native), `RapierPhysicsPipeline.processCollisionEffects()`(private), 15-double의 `[3..5] localNormalA`, `[6..8] localNormalB`, `[9..11]/[12..14] localPointA/B`. 이 증거는 **post-solver effect**이고 정확 per-voxel impulse/scene-substep를 제공하지 않는다. Sable 배열은 원본 consumer로 **동일 내용·동일 1회** 전달한다.
- World 정적 collider와 Plot은 frame 및 body owner/epoch 확인이 먼저다. Plot의 Sable effect 소비 시점 pose로 normal을 world로 변환한다. **양 normal 내적 ≤ −0.98, pre-/effect pose quaternion |dot| ≥ 0.995, 동소유 레코드 normal dot ≥ 0.98**은 v0.2 **선택적 방향 증거 승격** 정책의 개발 기준이다. 수치 충족만으로 접촉점·정확한 impulse를 주장하지 않는다. 다중 맨니폴드가 충돌하면 방향 `UNKNOWN`으로 강등하되 이미 별도로 입증된 충격 사건을 무조건 부정하지 않는다.
- signed axis가 verified이고 격자 축의 절댓값 ≥0.98일 때만 특정 축 정보로 쓸 수 있다. 사선 verified normal은 **F 전파의 연속 방향 벡터**로만 사용하고 임의 반올림으로 실제 영구 격자 이동을 허가하지 않는다. `sourceSubstep=-1`, `exactContact=false`, `deformationAuthorized=false`를 유지한다.
- raw native record 100개 제한/크로스 scene/ID 모호성·프레임 불일치·회전 epoch 불안정이면 방향 UNKNOWN. 이때 **0~2 깊이 유한 Preview** 같은 안전 경로는 가능하지만 normal을 만들어 내서는 안 된다. 변형은 방향 증명이 완성될 때까지 VETO.
- 다른 의존 JAR 버전에서는 이 수치·method/name/CoM 변환을 자동 복제하지 않는다. `sourceCapability`를 검사하고 검증된 방향·block adapter만 승격한다. 공용 타입은 `sourceKind`, `quality`, `owner/local frame`, `normal/evidence`, `canDeform`, `policyGeneration`의 의미를 제공하되 새로운 무단 JNI 의존성을 만들지 않는다.


### 3.0 외부 API와 현재 버전 사용 경계 (1.0.6, 규범)

§21.2를 **현재 Sable 2.0.6/PDD 0.3.1QFix에서 사용할 API의 권위 목록**으로 삼는다. `Rapier3D.clearCollisions(long)` post-effect 15-double 배열, `ServerSubLevel`/`LevelPlot`/`EmbeddedPlotLevelAccessor`의 storage↔embedded 좌표, `MergedMassTracker` COM 생명주기, Sable split 단서는 서로 다른 증명 계층이다. 클래스/서명 확인만으로 BE·drop·collider 반응·클래스패스 호환을 확정하지 않는다. 기존 §3.1의 '개별 구현 시그니처 검증 필요' 문구는 미검증인 호출에 그대로 적용한다. `R3` 교차-substep projection, `R4` swept boundary, 타측 world residual 또는 원본 callback hint 단독 승격은 **게임 damage authority 금지**.


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

## 4. 아키텍처·단방향 의존성 및 최소 데이터 계약 (1.0.7)

### 4.4 단방향 책임 경계 — F-first와 물성 로더 병합

```text
[Sable/기타 증명된 DamageSource adapter]
  -> ObservedEvent(원본 불변, quality)
  -> Owner/Target/Separation gate + Episode/CreditId
  -> Source F_event (충돌 전용, finite) + SIDE F_cap(0.5/0.5 기본)
  -> R2 seeds + owner-local FPropagationPlan(parent DAG, path attenuation)
  -> per-block QCandidate = G(assignedF)
  -> group/side Q cap + final per-block Q
  -> Snapshot H/T/D/E(외부CSV/레거시JSON/JAR/추론/기본)
  -> direct/plastic/stress 1회 분리 -> State/MutationIntent
  -> conditional World/Plot safe commit, persistent Receipt & visual delta
```

- `DamageSource`가 force 의미의 `F_event`를 실제로 주지 않는 외부 폭발/투사체까지 원시 `F_raw`를 위조할 필요가 없다. 원인별 검증된 budget adapter가 **같은 단일 Q 계정**에 진입하여 이미 처리된 F-first 결과와 같은 사건으로 중복되지 않도록 한다.
- **최소 코드 재사용:** 현재 `GameImpactCredit`/`ContactCreditLedger`/`ForcePropagation`/`DamageModel`/`MaterialRegistry`를 기능을 보존하며 연결한다. 이미 있는 기능을 감싼 Factory·Manager·PolicyEngine을 이유 없이 새로 만들지 않는다. 필요한 경계는 `MaterialSnapshot`과 World/Plot mutation adapter 두 곳이다.


### 4.1 실제 운영 데이터 경로

```text
Sable 2.0.6 original solver / CCD / callback / post-effect [UNMODIFIED]
   └─ existing processCollisionEffects array observer (once, read-only)
       └─ NativeCollisionTelemetry / boundedDrain
           └─ RealFixRuntimeBridge: dimension + owner + epoch + 15-field validation
               ├─ independent World clearance / Plot-Plot separation proof
               ├─ GameImpactCredit: ARMED/ACTIVE/COOLING, one episode credit
               └─ SableContactRegions R2 owner-local checks (only eligible cases)
                   └─ ContactCreditLedger: issue-once / side A-B / unused
                       └─ MaterialRegistry snapshot + DamageModel planning (0.4.0+)
                           └─ fixed-point sparse damage state + validated intents
                               ├─ World safe commit (0.4.1+)
                               ├─ Plot safe commit (0.5.1+)
                               └─ stress / deform / visual / external sources by release
                                   └─ owner-generation + CAS + save + client delta
```

- **현존 구현 vs 설계 타입:** 위 이름 중 `NativeCollisionTelemetry`, `RealFixRuntimeBridge`, `GameImpactCredit`, `SableContactRegions`, `ContactCreditLedger`, `MaterialRegistry`, `DamageModel`, `BlockDamageState`, `WorldClearanceVerifier`는 0.3.1QFix에 실제 존재한다. `CommitIntent`, `ApplyReceipt`, `DirectionEvidence`, `DamageSource`, `MaterialSnapshot`은 1.0.6의 **설계 계약**이며 이미 구현된 API가 아니다.
- **Ponytail 원칙:** 기존 단일 책임 클래스·자료구조를 재사용한다. Source/Target/Material/Commit을 위해 10개 이상의 새 매니저, 공장, 상태기계를 선제 생성하지 않는다. 새 경계는 변경 실패가 Q를 재발행시키지 않는 곳에만 만든다.
- 사건 조회에는 요청별 immutable snapshot을 사용한다. Native callback/physics 단계에는 월드 변경, 디스크 IO, 레시피 탐색, 강제 청크 로딩을 넣지 않는다.
- Sable 원본 효과 배열과 특수 콜백 반환값·호출 횟수·예외·호출 순서는 유지한다. DVT는 원본 collision hook을 재등록하지 않고 **같은 downstream 손상 원장과 커밋**에 원인을 공급한다.

### 4.2 소유권과 수명

- `serverRun + dimension + (WORLD 또는 stable Plot UUID) + ownerGeneration`을 사건 식별 기준으로 사용한다. `runtime body id`는 해당 관측 프레임에서만 유효하다.
- `TargetRef`는 `space + stable owner + owner-local/embedded block + fingerprint + ownerGeneration`을 구분한다. World XYZ와 Plot 저장 그리드/embedded XYZ는 결코 혼용하지 않는다.
- Snapshot의 유효성은 새 사건, owner generation 변화, COM epoch 변화, target block 변경, chunk unload, 데이터팩 재설정에 의해 **해당 계층에서만** 무효화한다. 전 계층 일괄 전체 재스캔은 금지한다.
- 사건 크레딧은 발행 뒤 불변이며, 실제 피해/드롭/변형은 **별도 적용 결과**로 기록한다. 손상 예산이 있다고 물리 객체 소유권이 생기는 것은 아니다.

### 4.3 단계별 활성화, 동일한 최종 계약

0.3.1까지 월드 변경은 OFF다. 0.4.0은 재료 계산·피해 계획과 최소 직렬화 자료구조를 완성한다. 0.4.1은 World commit과 최소 안전 저장·복구를 시작한다. 0.5.0은 저장 전체 기능을 완성하고, 0.5.1은 Plot commit 및 split 격리, 0.6.1은 검증된 상태 이전을 완성한다. 0.6.0/0.7.x/0.8.x의 Stress/Deform/Visual/External은 이 코어를 **교체하지 않고** 확장한다.

## 5. 필수 데이터 최소집합과 품질 등급

### 5.7 방향 품질·데이터 가용성의 별도 진리표

| 입력 증거 | F 생성/직접 피해 | F 전파 | Q-stress | 격자 변형 |
|---|---|---|---|---|
| 검증된 `newImpact` + owner/R2 + bounded game F, 방향 미확정 | 허용 | 비방향성 제한/사유 기록 | owner-local 6면의 안전 유한 분배 가능 | 불가 |
| 위 조건 + verified signed native normal | 허용 | 주어진 벡터 기반 좁은 전방 cone | 검증 방향 편향 가능 | 아직 불가; exact move 증거 별도 |
| exact source scene/substep/contact/방향 및 safe target | 허용 | 완전한 방향 증거 사용 | 편향 가능 | 공간·BE·CAS까지 통과 시만 가능 |
| 원인 미승인/소유자 불명/invalid F/중복 승인 | Q=0 | 불가 | 불가 | 불가 |

**강제:** `sourceQuality`, `directionQuality`, `targetQuality`, `mutationEligibility`는 서로 승격을 전염시키지 않는다. 방향이 정확해도 새 타격 증거가 없으면 0 Credit다.


### 5.0 입력 데이터의 수명과 authority (1.0.6, 규범)

`Observation`은 15-field native 입력과 scene/frame 메타데이터이며 실제 block mutation 권한을 갖지 않는다. `TargetRef`는 owner-generation + live collision-shape가 확인돼야 한다. `GameImpactCredit`의 승인 Q는 `sourceQuality`와 신규 접근 조건을 통과한 **게임 예산**이다. `DirectionEvidence`는 비필수이며 현재 Native point/normal 존재만으로 VERIFIED가 아니다. 정확한 solver 충돌 점·impulse가 부재해도 물성/월드 변경 설계 자체는 진행할 수 있지만, 그 입력으로 확인할 수 없는 방향·접점은 미확인으로 남겨야 한다.


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

### 6.6 1.0.7 단일 Credit의 F→Q 순서 (기존 선Q 균분 대체)

1. 오직 검증된 **신규 충돌 원인**에서 `F_event≥0`을 고른다. 여러 native point의 같은 `force_amount` 합산은 금지한다. `F_event`는 원장 밖에서 재가산하지 않는다.
2. 기본 A/B 상한은 `F_sideA=0.5 F_event`, `F_sideB=0.5 F_event`다. 검증되지 않은 측의 몫을 반대측에 재할당하지 않는다. 사용자 승인 없이 두 owner에 각각 `F_event`를 복제하지 않는다.
3. 각 측별 검증된 접촉 씨앗·유효 이웃 사이에서 먼저 F를 분배한다(§30). **그 다음** 각 고유 블록이 `Q_candidate_i=G(F_i)`를 한 번 산출한다.
4. `G(F)=max(0,forceGain×max(0,F−forceFloor))`이고 `Q_group_cap=min(maxGroupQ,G(F_event))`는 **상한**이다. 양측 각각의 기본 cap은 `0.5 Q_group_cap`; 이는 새로운 Q 균등 분배가 아니라 **최종 발행량을 제약하는 cap**이다. 고유 블록 후보합이 cap을 넘으면 안정적 정수 비례 배율·잔여 순서로 줄인다. cap보다 작아도 남는 양을 다른 블록에서 재발행하지 않는다.
5. 분기·탄성·한계로 소모되지 않은 `F`는 `dampedF` 또는 `unresolvedF`로 분류한다. `G(ΣF)`와 `ΣG(F_i)`는 임계값 때문에 일반적으로 다르다. 원인 Q 상한의 미사용분도 **최종 unused**이며 미래 Credit이 아니다.
6. `Q_final_i`에서만 자기 E 1회 적용과 H/T/D/plastic/stress 계획을 수행한다. 이후 `Q_stress`로 전달되는 양은 **이 Q에서 나온 실제 배당**이지 다른 F 이벤트가 아니다.
7. 같은 원인·같은 블록의 여러 seed/path는 **합산 후 딱 한 번** `G`와 `DamageModel`을 실행한다. `F`·`Q`를 물리력·에너지라고 불러선 안 된다.

**일관식:** `F_event = F_A(0.5) + F_B(0.5)`; 각 측 `F_side = ΣassignedF + ΣdampedF + unresolvedF` (반올림 허용오차). `issuedQ=ΣfinalBlockQ≤Q_group_cap`, `ΣsideQ≤Q_side_cap`; `appliedConsumed+unspent+finalUnused+boundedReserved=issuedQ`. `Q_cap−issuedQ`는 미발행이므로 소급 발행 금지.


### 6.0 한정 Credit와 실제 사용의 구분 (1.0.6, 규범)

본 설계의 중심 불변식은 `one source cause -> one bounded group Credit`, `A allocated + B allocated + unused = issuedQ`다. **1.0.7에서 A/B는 F 측 상한을 선배분하고 Q는 블록별로 사후 산출한다 (§6.6)**. 0.3.1QFix의 실제 `ContactCreditLedger`는 이미 예약된 Q를 기록하지만 world 적용 결과는 아직 없다. 0.4.0 이후 실제 damage 계산과 0.4.1 이후 mutation의 성공/실패는 **동일 사건에 대한 별도 ApplyReceipt**로 기록한다. `F_raw`를 solver impulse, 물리 에너지 또는 마인크래프트 피해량으로 단정하지 않는다. 기존 §6의 수학은 정책의 배경이며 실제 `F_first_approved` Q 계산과 1회 탄성 계약은 §22.3/§24.1을 따른다.


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

### 7.0 재료 공통 알고리즘과 데이터 상태 (1.0.6, 규범)

H/T/D/E는 블록 **정의 또는 필요한 BlockState 속성** 기준으로 서버 시작·레시피/데이터팩 갱신 시 캐시한다. 실제 설치된 모든 블록의 레시피 반복 조회 금지. 0.3.1QFix의 `MaterialProperties`는 H/D/E만 있고 `DamageModel`은 선형/올림 프로토타입이므로 0.4.0에서 §24.1의 H/T/D/E·비선형 경도·탄성 1회·`fixedPointScale=256` 계약으로 통합해야 한다. 특히 탄성 곡선은 **§7.4의 `rMin=0.15`, `beta=1.5`**를 보존한다. 미세손상을 무조건 올림해서 1 주지 않는다. DVT 강화/부식은 같은 불변 재료 snapshot + 필요시 동적 희소 modifier를 사용한다.


### 7.0.1 물성의 정적 4계층 원천 규범 (모든 버전)

**[USER_CONFIRMED] 정적 기본값의 속성별 우선순위:** **① 게임 `mods/` 안의 외부 사용자 CSV → ② PDD JAR 내부 기본 블록·아이템 CSV → ③ 레지스트리/태그/구체적 재료 프로필/지원 제작법 기반의 추론 → ④ 명시적 안전 기본값**. 블록 하나를 세계에 설치할 때마다 레시피 추론·파일 읽기 금지. 기존 사용자 `config/pdd-materials.json`은 파괴하지 않고 **첫 번째 '외부 명시 설정 계층' 안에서 호환 입력**으로 취급한다(§7.2.2). 동적 DVT modifier, 보호/불괴, BlockState eligibility는 4계층 **이후의 독립 검증/보정**이다.

| 범주 | 정의별 정적 입력 | 후속 기능에서의 소비자 | 데이터 부재 처리 |
|---|---|---|---|
| BLOCK | `id,H,T,D,E,unbreakable` (+기존 `yieldThreshold`/상태별 차이) | World/Plot block collision, direct damage, F/Q-stress, break/deform | 각 미정 속성만 하위 소스로 보충 |
| ITEM | `id,H,D,E` (+블록 아이템 대응 ID 확인) | 제작법 재료 추론·확장 시스템 | 아이템 T/불괴/블록 파괴 기능을 가짜 생성하지 않음 |
| STATE | 실제 `BlockState`로 확인 가능한 변이값만 | 재료 snapshot의 선택적 state index | 정의값 상속, 무문맥 World 함수 평가 금지 |
| DAMAGE | owner-local 좌표·fingerprint별 `damage/deformation/crack/remainder` | 실제 커밋·저장·시각화 | 물성표에 좌표별 피해값 보관 금지 |

- 표에 `H=255`가 있어도 물리적 불괴가 아니다. CSV `unbreakable=0`으로 바닐라/관리자/claim/BE 보호·원본 솔버 정책을 무력화할 수 없다.
- `D`는 항복 후 변형 허용량, `E`는 PDD 내부 게임 예산 전달/완충 정책, `T`는 파괴 임계값이다. 사용자가 부여한 아이템 값은 실제 게임에서 작용하는 기본 블록의 질량/경도 실측치가 아니다.
- **CSV 전체가 구현되어 있다는 뜻이 아니다:** 2026-11-06 기준 비교 SHA의 `MaterialRegistry`는 블록+JSON·프로필/제작법 캐시까지만 존재하며, bundled block+item CSV 로더는 아직 없다(§29).


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
3. **1.0.7 최종 로딩 순위:** `외부 명시값(CSV·호환 JSON) > JAR 내장 명시 블록·아이템값 > 미정 필드에 한한 등록/태그/재료 프로필/제작법 추론 > 명시 기본값`. 그 뒤 별도 검증된 `BlockState` 차이와 DVT의 허용된 동적 modifier를 적용하되, 사용자 명시값·불괴 정책·타깃 적격성을 조용히 뒤집지 않는다. 원본 1.0.6의 `inferred → family → state → explicit → user`는 **구현 순서/역사적 표현**이며, 사용자 CSV보다 JAR/추론을 우선하는 조항이 아니다.
4. 태그/레지스트리 식별자가 명시하는 재료를 우선 신뢰한다. ID에 `steel`, `rust`, `gold` 등의 문자열이 들어갔다는 사실만으로 재료를 **확정하지 않는다**. 제작법은 여러 후보에서 신뢰도를 평가하는 보조 근거이며, 투입 아이템 비율을 물리적 질량·부피 분율로 자동 간주하지 않는다. 미지원 레시피·비정상 속성은 원소별 fallback 후 이유를 남긴다.
5. 공기·유체·불괴 블록·교체 가능 장식·충돌 형상이 없는 블록은 **별도 target eligibility / protection policy**를 적용한다. `H=0`인 레드스톤 가루·잔디도 실제 접촉이 없으면 Sable 충돌이 생겼다고 가정하지 않는다. 별도 폭발/투사체 피해 대상 여부는 source 정책으로 판정한다.
6. 물성은 immutable registry를 **세대별 원자적 교체**한다. 이미 승인된 사건은 사용한 `materialGeneration`과 `algorithmVersion`을 보존해 재로드 도중 다른 버전의 물성과 섞이지 않도록 한다. 좌표별 누적 손상은 물성 캐시와 분리한다.

**세분화 예:** `METAL/IRON`과 `METAL/STEEL`은 서로 다른 기본값을 가진다. `IRON + CORRODED`는 부식 상태 보정을 받는다. 같은 블록의 약해진 구조가 쉽게 눌리는 현상은 `yieldThreshold` 저하로 표현할 수 있으며, 반드시 `D` 증가를 뜻하지 않는다. 강철은 조성/열처리에 따라 고연성과 저연성 모두 가능하므로 재료명만으로 일방적 순위를 강제하지 않는다. `WHEEL/COMPLIANT` 같은 검증된 **기능 프로필**은 단순 금속 평균보다 우선 적용할 수 있다. 단, 실제 Aeronautics의 철·강철·부식 블록·바퀴 블록 ID 및 구체적 속성은 대상 레지스트리/게임 증거로 확인한 후에만 매핑한다. 부식이 월드 내 시간 경과로 자동 진행되는 기능은 DVT 3.0 범위이며 PDD 1.0에서 선행 구현하지 않는다.


### 7.2.1 CSV 파일·스키마·파서·캐시 (1.0.7 통합)

**기본 리소스 설계 경로(개발 계약, NeoForge 내장 자동 CSV 기능으로 오해 금지):**

```text
PDD JAR:  src/main/resources/pdd/materials/blocks.csv
          src/main/resources/pdd/materials/items.csv
User:     <gameDir>/mods/pdd/materials/blocks.csv
          <gameDir>/mods/pdd/materials/items.csv
Legacy:   <gameDir>/config/pdd-materials.json  (계속 읽기)
```

- 게임 디렉터리의 `mods/` 하위 CSV 외부 값이 사용자 요구의 최우선 정적 소스다. 위 파일 경로 자체는 구현 정책 제안이며, 현재 Sable/NeoForge에 실제 있는 API라 주장하지 않는다. 외부 경로는 정규화·고정된 경계 내에서만 조회한다.
- 블록 헤더 **`id,H,D,E,T,unbreakable`** 필수 인식; 참고 열 `namespace,name,display_name,eligibility,profile,profile_basis,confidence,source,vanilla_strength,vanilla_blast_resistance,vanilla_bbox,notes`는 진단용으로 취급. 아이템 헤더 **`id,H,D,E`** 및 대응 `corresponding_block_id`를 검증할 수 있다. 아이템 스키마에 T를 임의 추가하지 않는다.
- CSV는 UTF-8 및 선택적 BOM, 정식 `"..."`·쉼표 escaping으로 처리한다. `split(",")` 금지. 헤더로 열 위치 판정, 구분된 registry ID 확인, 잘못된 정수/범위/중복 ID의 행 단위 reject. **빈 값=미제공, 명시적 `0`=실제 0**; 외부 속성 일부만 주면 나머지는 하위 소스를 유지한다. 잘못된 범위를 silent clamp로 덮지 않는다.
- 유효 범위 `H 0..255`, `D/E 0..100`, `T 1..255`, `unbreakable∈{0,1}`. `yieldThreshold`는 별도 schema·명시 정책에 없으면 기존 default/프로필 사용. Registry 미등록 ID는 기록하고 skip; 실제 설치 모드의 모든 ID가 파일에 존재한다고 가정하지 않는다.
- 같은 파일 내 ID 중복은 **그 ID의 외부 override 전체 reject**(동일 파일의 마지막 행 승리 금지); 잘못된 행은 주변 정상 행을 저해하지 않는다. 파일 전체 손상·부적합 schema·I/O 실패 시에는 **이전 완성 snapshot을 유지**하고, 최초 로딩이면 정상 JAR 데이터와 하위 fallback으로 진행한다. 외부 명시값이 유효한 범위 밖인 경우 로더 자체가 게임을 크래시하지 않도록 진단하며, 단 새 불완전 snapshot은 절대 원자 공개하지 않는다.
- 오픈/파서 경계 한도는 파일 크기·행수·필드 크기·로그 건수를 운영 설정으로 제한한다. 장애 원인 `INVALID_HEADER`, `INVALID_ID`, `DUPLICATE_ID`, `INVALID_RANGE`, `UNREGISTERED_ID`, `RECIPE_CYCLE`, `LOW_CONFIDENCE`, `IO_FAILURE` 등을 분리한다.
- 레지스트리/recipe/tags가 준비된 **서버 시작 또는 지원되는 안전 reload** 시점에 등록 **블록·아이템 정의**를 캐시한다. 실제 월드에 설치된 블록 좌표를 전수 조사하지 않으며 callback/물리 hot path I/O·레시피 탐색을 금지한다. 속성별 미정 필드에 대해서만 추론하고, 참조 순환·복수 레시피·태그 대체를 결정론적으로 처리한다.
- `MaterialSnapshot(generation, algorithmRevision, sourceHashes, definitions, stateOverrides)`는 불변으로 준비 후 원자 교체한다. 서버 권위 snapshot만이 Q→피해를 결정하며 클라이언트 CSV는 게임 피해에 영향 금지. 이미 승인된 이벤트는 과거 generation을 유지한다.
- **원본 CSV 검증 고정 기록(2026-11-06):** `blocks.csv` 1,844 레코드(`sha256=8977bde3dcad2ba604a0b22ef593712b833b72a2776375b2397bc7dffd780a46`), `items.csv` 2,159(`sha256=b82b6fe736e9ebc0fba75f983df4f7bbdaae1588e2320825343fc4bf3eb33e67`); 두 파일 모두 해당 필수 속성 열에 공란·중복 ID 없음. 단, **레지스트리 등록/밸런스/인게임 합격의 증거가 아니다**. 설명·출처 열을 제거한 경량 JAR 파일은 생성 과정과 새 해시를 남긴다.

### 7.2.2 기존 JSON과 CSV 호환: 동일 4계층 내부의 결정론적 정책

**해석 원칙:** 사용자 명시 4단계는 *정적 데이터 소스 계층*이다. 기존 `config/pdd-materials.json`을 숨기거나 삭제하지 않고, **외부 명시 설정 1계층의 레거시 형식**으로 보존한다. 순위는 **CSV의 동일 필드 > JSON의 동일 필드 > JAR 내장값 > 추론 > 기본값**이지만 JSON을 다섯 번째 별도 설계 계층으로 도입하는 것이 아니라 최상위 **외부 입력의 내부 충돌 해소 순위**다.

| 상황 | 정의별 필드 결과 | 추가 규칙 |
|---|---|---|
| CSV 행·필드 유효, JSON도 동일 정의 필드 설정 | CSV 선택 | 충돌 ID·필드·두 값·선택값을 요약 로그로 표시 (silent ignore 금지) |
| CSV 행 없음/필드 공란, JSON 유효 | JSON 선택 | 기존 사용자 JSON 유지 |
| 둘 다 미제공, JAR 값 있음 | JAR 선택 | static profile 후보가 JAR explicit을 덮지 않음 |
| 모든 정적 명시 소스에 빈 필드 | supported inference, 그다음 fallback | 인덱스/재귀 순환을 건너뛰고 안전 종료 |
| JSON의 기존 state-specific rule | **정의별 값 산출 이후 실제 state 전용 override** | 적용 시 `stateRule` 출처 추적. CSV에서 명시한 정의별 사용자값과 충돌할 경우 기본은 CSV 사용자 명시 필드를 우선하고, 상충 state-rule은 경고·보존/미적용을 명확히 기록. state 우선 정책은 별도 운영 옵션으로만 허가 |

이 충돌 해결 정책은 **1.0.7 설계안의 결정론적 호환 기본값**이지 사용자와 검증을 마친 밸런스 수치가 아니다. 기존 운영자의 파일을 자동 삭제·이름 변경하거나 암묵적으로 무시하지 않는다. 추후 state-selector CSV를 정식 스키마로 추가하는 경우 상태 특정도와 source 우선순위를 동시에 검증해야 한다.

**오버라이드가 보호를 해제하지 않는 순서:** `data value resolution → state-specific material adjustment → optional DVT dynamic modifier → actual collision eligibility / vanilla unbreakable / claim / BE / owner / loaded / CAS veto`. 바닐라의 불괴·보호는 파일의 `unbreakable=0`으로 낮출 수 없다. 물성 변경으로 과거 `damage/deformation`을 지우거나 리로드 순간 새 `T`에 맞춰 기존 블록을 즉시 파괴하지 않는다.

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

`Q_direct`는 **7.4절에서 탄성을 한 번 적용한 `Q_active` 중 직접 손상으로 배정한 예산**이다. 이 식에 `f_E` 또는 연성 보정을 다시 곱하지 않는다. `F`의 개발 기본값은 §24.1의 `F(Q_direct)=Q_direct×damageGain`, `damageGain`은 정책 세대별 명시값으로 단조·유한·비음수 검증한다. 1.0.6 당시 `1.0`은 역사적 후보이고 0.4.0colFix2의 비교 개발값은 `2.6`(§23.1)이다. 사용자 값 확정은 해당 기능 버전 이후이며 설정으로 외부화한다. `H`가 높을수록 동일 `Q_direct`의 피해가 증가하지 않아야 한다. `H=255`도 불괴는 아니며 `UNBREAKABLE`이 우선한다. 비정상 부동소수값은 `INVALID_ENERGY`로 거절하고 계획 생성을 중단한다. 손상 수치 `D_raw`의 `D` 표기는 연성 `D`와 다르므로 구현에서는 `rawDamage`처럼 혼동 없는 이름을 쓴다.

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

### 8.2 절대 혼용 금지: F-first propagation과 Q-stress

| 구분 | F 선배분(충돌 경로) | Q-stress(2차 손상) |
|---|---|---|
| 원천 | **원인 단위** 승인된 게임 F | 이미 확정된 블록 Q의 `stressShare` 예산 |
| 계산 | F의 절반을 현재 블록에 할당, 나머지 자식 전달; **블록마다** `G(F_i)` | Q-stress를 owner-local 연결 이웃에 전달; 새 G(F) 없음 |
| 탄성 | 자식 E가 **실제 부모의 잠정 Q**를 경로별 감소, 부모 E가 후속 F를 감소; 자기 Q의 E는 별도 1회 | Q-stress를 받은 **새 노드에서 E 1회**, 중복 경로 합류는 전처리 |
| 통과/중단 | verified terminal만 나머지 F 전량; 한도/모호성은 `unresolvedF` | 정규화된 이웃 Q 전파; 미사용/보류 잔량이 원래 credit에 귀속 |
| 구현/검증 | 별도 `F-PROP`, `F-LEDGER` | 별도 `STRESS-LEDGER`, `STRESS-APPLY` |

한 블록에 이미 F-first로 직접 Q를 준 다음 그 **동일 사건의 같은 Q를 응력 경로에서 다시 원본 금액으로 생성하는 것은 불법**. Q-stress는 `Q_active` 내 0.25 개발 몫 등만 가지고 흐른다. 아직 구현되지 않은 실제 stress mutation을 F-first Preview와 혼동하지 않는다. 반복 방문·여러 소유자 좌표 충돌·cap/CHUNK_UNLOADED·stale owner는 두 경로 모두 이유 있는 미사용/보류로 정산한다.


### 8.0 Stress 코어 재사용과 비용 (1.0.6, 규범)

응력은 충돌 강도를 다시 계산하지 않는다. 승인된 Target/Material 계획에서 **사용하지 않은 Stress 몫만** owner-local 6면으로 전달한다. §24.5의 node-once/level merge, 깊이/고유노드/틱 상한, 순환·보류 잔액 정산을 따르며, 이전 깊이에서 처리한 노드에 다시 도착한 예산으로 damage를 증가시키지 않는다. verified direction이 없으면 비방향 균등 분산이며 모르는 방향으로 블록 이동 금지.


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

### 9.6 F/Q 최종 계획의 동결과 MaterialSnapshot 연결

1. Sable native callback/물리 solver 중에는 **관측만 수행**한다. 원인 F와 방향·owner 증거를 freeze한다.
2. `FPropagationPlan` 전체(동시 seed/분기/합류/탄성 전후 F·Q·경계 미결정)를 **물리 외부의 한정된 서버 작업**에서 계산한다. **탄성체가 선행 부모 Q를 변경하므로 발견 즉시 월드 파괴 금지.**
3. 동일 episode의 `materialGeneration`, `algorithmRevision`, `sourcePolicyRevision`, 원래 `TargetKey/fingerprint/ownerEpoch`를 고정한다. 다른 generation이 섞인 Plan은 reject/replan-without-new-credit한다.
4. 최종 `Q_final`로 `DamageModel`/`StatePlanner`가 한 번 계산한다. 정확한 적용성(CAS, chunk, protection, BE, World vs Plot)을 재확인한 **실제 APPLIED 부분만** 누적/드롭/시각화/저장에 반영한다.
5. 양자화/드롭/재시작/분리 시 **원인 Credit**과 **블록별 ApplyId/Receipt**는 독립 수명이다. `F_unresolved`·`Q_unissued`·`Q_unused`를 나중에 자동 피해로 재생하지 않는다.
6. 서버 일시 중지·재로드·unload 도중 새 스냅샷으로 바뀌면 해당 계획은 원장과 소유자 보호 정책에 따라 안전 보류/재검증하고, 과거 원인을 새 사건으로 다시 발행하지 않는다.


### 9.0 모든 버전에 공통되는 데이터 무결성 (1.0.6, 규범)

월드/Plot 변경은 충돌 콜백 밖의 서버 안전 시점에서 실행한다. `Credit -> Plan -> CAS preflight -> owner-specific commit -> re-read -> save/receipt -> visual` 순서를 지키고, 실제 변경 불가능한 후보를 가짜 성공으로 표시하지 않는다. **0.4.1 World 변경 이전** 최소 영속 저장/복원·중복 방지 기반을 필수 구현하며, 0.5.0에서 전량 저장 기능과 migration을 완성한다. **0.5.1 Plot 변경 이전** split stale-state 격리를 선행하고, 0.6.1에서 정확한 old/new block lineage 이전을 완성한다. Minecraft 다중 블록 변경과 외부 SavedData/드롭이 하나의 ACID transaction이라는 증거는 없다. 재시작 정확히 한 번 처리는 별도 검증 대상이다.


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

### 10.2 외부 사건과 F-first의 입력 구분

- **물리 충돌 경로**는 verified `F_event`를 owner별 F-first로 보내고 블록별 `G(F_i)`를 호출한다. 이미 계산된 `Q`를 다시 `F`로 역변환하지 않는다.
- **폭발/투사체/엔티티/DVT 원인**은 원본 게임이 인정한 `DamageSource`와 피해·질량·속도 등 **실제 확보한 값**으로 capped `Q_external`을 생성한다. 같은 사건의 원본 블록 파괴를 보존하고 생존 블록에 추가 방향성 충격을 가할 경우 확정된 원인 ID와 대상 집합을 재검증한다. 폭발 Q를 post-effect F라고 명명하면 안 된다.
- 공통 `CreditId/causeGroup`에서 충돌 source와 외부 source의 공통 부모 원인을 검출해 **중복 발행을 막는다**. Q_external 이후 `MaterialSnapshot → DamageModel → safe commit/receipt`는 동일하게 재사용한다. 외부 source의 전파가 필요하면 검증된 원인별 `PropagationAdapter`를 사용하되 F-first를 오남용하지 않는다.


### 10.0 외부 사건의 원인 공유·독립성 (1.0.6, 규범)

실제 vanilla explosion은 기존 게임 파괴 결과를 유지한다. PDD는 원본 결과가 확정된 뒤 살아남은 검증 대상에만 추가 Credit를 부여한다. 투사체·엔티티·DVT 기관 고장은 각각 **실제 서버 측 원인**과 고유 ID가 필요하다. 원인 간 전파는 `parentCauseGroup`으로 추적하지만, 독립 사건까지 무조건 삭제하거나 복제하지 않는다. 공통 Material/Commit/State 코어 재사용. 관련 공식 hook의 이벤트 순서와 라이프사이클은 해당 기능 구현 시 대상 모드/NeoForge 실제 API로 확인한다.


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

### 11.3 F-first 및 물성 데이터 시스템의 성능 경계

- 개발용 F-first 상한은 **측별 최대 128 고유 노드**, verified native 방향 깊이 **9**, unknown 방향 깊이 **2**, 원본 R2 승인 대상 **측별 32**, R2 regions **64**, World clearance **8192 셀**이다. **서로 다른 단위**이므로 하나의 `MAX`로 합치거나 한도가 다 찬 곳을 “정상 종단”으로 취급하지 않는다.
- verified direction은 `perpendicular²≤0.36×forward²`의 좁은 전방 cone과 첫 1블록 사선(`parallel≥0.5`) 규칙을 따른다. 뒤쪽 전파 금지, 사선이더라도 모르는 실제 grid move 허가 금지. Unknown은 0~2 깊이 비방향성 Preview 한정. 구식 무제한 평면 flood-fill과 층 전체 평균 탄성은 삭제 또는 호환 테스트로 격리한다.
- 충돌 hot path에서는 CSV/JAR/JSON I/O 0회·레시피 추론 0회. `MaterialRegistry` snapshot lookup은 평균 O(1) 목표. 계층별 인덱스와 대형 Plot 전체 스캔을 성능 근거 없이 새로 만들지 않는다. 등록 수는 블록·아이템 **정의 개수에 비례**, 월드 좌표 수에 비례하지 않는다.
- 긴 선체, 광대한 World 잔디 평면, 동시 15명 전투에 대해 계획 노드/분기/불확실 경계/중복거절/CSV reload 시간·힙/GC·P50/P95/P99을 계측한다. 해당 메트릭 값 자체는 아직 실측되지 않았다. cap이 인게임 피격을 누락하면 **정확성 우선으로 분기 증거를 보강**하고 무작정 무제한 설정을 해제하지 않는다.


### 11.0 핫패스 제한과 QFix 계측 (1.0.6, 규범)

`0.3.1plotRog` 실측 로그에서 R2 형상 검사 24,734회 중 23,407회가 `DUPLICATE_EPISODE`였음은 실제 로그 분석의 **호출 건수**이며 CPU time은 아니다. 현 QFix는 Q 승인 불가능한 활성 episode에서 R2를 생략한다. World clearance는 전용 상한을 갖고 R4와 분리되며, native 포화 100은 손실 수 측정이 아니다. **별도 성능 계측**: `R2_eligible/skipped`, 신규 unique cell checks, clearance `IN_PROGRESS/CLEAR/TOO_BROAD`와 최종 같은 tick checks, batch/collision shape allocation, phase별 P50/P95/P99, Native saturation, 로그량. 최적화 시 '승인 Q 수·실제 타깃·owner' 정확성을 함께 비교한다. 새 section occupancy cache는 정확성이 증명되고 병목이 실측되기 전 필수가 아니다.


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

### 12.1 정적 아이템 물성과 DVT 동적 확장 구분

DVT가 아이템/기계/폭발에 추가 데이터를 요구하더라도 PDD의 정적 ItemSnapshot(H/D/E)과 BlockSnapshot(H/T/D/E)을 재사용한다. DVT 3.0의 강화·부식·습윤·추가 질량은 **별도 동적 modifier/상태**로 표현하고 CSV에 좌표별 상태를 삽입하지 않는다. 원본 CSV `profile`, `confidence`, `item_model`은 추론의 출처 메타데이터이지 DVT 효과가 활성화됐다는 증거가 아니다. DVT가 새 damage source를 제공하더라도 Sable original callback의 변경은 허용하지 않는다.


### 12.0 DVT 독립 로드와 공통 계산 재사용 (1.0.6, 규범)

DVT 1.0 Fracture는 PDD의 손상·Stress·Sable split 결과를 재사용한다. DVT 2.0 Big Bang은 고유 기관 폭발 원인·연쇄 상한·기존 폭발 후 survivors를 사용한다. DVT 3.0 Reinforce는 H/T/D/E에 대한 overlay Snapshot으로 작동하며 손상 원장을 다시 만들지 않는다. 외부 의존성 없는 PDD 단독 로드는 항상 요구된다. 새 DVT 전용 충돌 hook/Material damage engine/Commit ledger를 만들지 않는다(§25).


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

## 13. 사용자 운영값과 개발용 기본 프로필 — 전 버전 정책 세대

### 13.2 1.0.7 추가 설정 정책 (버전 번호 독립)

| ID | 정책 필드 | 기본 개발 방향 / 제약 |
|---|---|---|
| `P-24` | `sourceForceGain/Floor`, 원인별 cap, `sideFShare` | F-first 적용; 2측 기본 0.5/0.5, 물리 단위가 아닌 DEV 값, 한 측 실패시 재할당 금지 |
| `P-25` | `fGraphDepth`, `fMaxNodesPerSide`, unknownDepth, cone 폭 | 기본 9/128/2/0.36; 직접 안전·중복 게이트 통과 전 무제한 상향 금지 |
| `P-26` | path elasticity `rMin/beta`, parent attenuation policy | 기본 `0.15/1.5`, 직접 parent 가중 평균, 형제 경로 격리, 각 대상 예산별 원장 별도 |
| `P-27` | CSV 경로·사이즈·reload, legacy JSON 충돌 | 외부 CSV/JSON 1계층, CSV 동일 필드 우선, 경고 로그, 사용자 파일 자동 삭제/무시 금지 |
| `P-28` | material uncertainty / slime T anchor / hash | bundled 데이터는 추정; 코드 보정 T≥64 vs CSV 19 차이는 별도 mismatch 및 테스트, 해제/승인 불명 값은 안전 거절 |
| `P-29` | source policy/data migration | `algorithmRevision/materialGeneration/policyHash`, 기존 Receipt 의미 고정, 기존 피해 무단 소급 수정 금지 |

**상위 수치 유효성:** 운영값을 변경하더라도 finite/≥0, `ΣsideShare≤1`, `ΣQ_final≤groupCap`, `0<rMin≤1`, `beta>0`, 음수·NaN 거절, `G(0)=0`, H/T/D/E byte range, Q-stress 1회, 같은 사건 블록 1회는 불변. 런타임 리로드는 구세대 사건을 새 세대로 재발급하지 않는다.


### 13.0 설정과 정책 세대 (1.0.6, 규범)

본 버전이 **실제 사용자 승인 없이 확정한 게임 밸런스 수치**는 없다. §23은 `DEV_PROVISIONAL` 계약이다. 0.3.1QFix 소스의 `maxTargets=32`, `MAX_REGIONS=64`, World 전체 셀=8192, 증분/최종예산은 **실제 코드의 제한**으로 표시한다. 과거 `multiTargetPerSide=4` 값은 역사적 시험 수치로만 취급한다. 기존 `forceEnter=2`, `gain=0.25`, Q 상한 64, sides 0.5/0.5 보존. 수치를 바꾸면 새 policy revision과 테스트를 요구하며 기존 Credit에 소급하지 않는다.


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

## 14. 구현 로드맵과 선행 계약 — 버전 독립 게이트 + 초기 배치 이력

### 14.0 능력 기반 개발 계획이 버전 번호보다 우선한다

아래 §14.1의 `0.4.1`, `0.5.0` 등은 **이 문서를 작성할 때의 배치 목표/기술적 선후관계의 예시**이지, 이후 0.x·1.x·2.x·DVT 릴리스에서 알고리즘을 다시 정의할 근거가 아니다. 사용자가 특정 버전을 개발하라고 하면 그 버전에 할당된 요구 기능을 누락하지 않고 구현한다. 버전 이름을 바꾸더라도 **§31 capability gate의 의존 관계**(source evidence→F plan→MaterialSnapshot→안전 커밋→영속성→Plot lifecycle→stress→deform→visual/external)와 테스트 불변식은 유지한다.

- F-first는 **충돌 피해 공통 정책**으로 모든 릴리스에 적용한다. 프리뷰와 실제 커밋은 동일한 **고정된 계획**을 다른 실행 게이트에서 소비한다.
- 정적 블록·아이템 CSV는 모든 버전에서 반복 사용할 기본 재료 인프라다. 특정 0.4.1에만 묶어 임시 로더를 만들지 않는다.
- 0.4.1 World 안전 커밋 및 최소 저장 등 원래 **버전 목표는 삭제하지 않는다**. F/CSV 통합을 구실로 World·Plot·stress·deform 목표를 임의 이월하지 않는다.


### 14.1 최초 배치 계획(0.3.x~1.0.0): 버전별 실제 기능 및 선행 안전 요구사항 — **1.0.7 §31 capability gate가 규범**

| 버전 | 반드시 완료할 기능 | 선행 계약/예외 없는 안전조건 | 검증 게이트 |
|---|---|---|---|
| 0.2.12~0.3.0realFix (기존 이력) | 원본 callback 보존, owner 정체성, 15-double observer, R2 후보, read-only 게임 Q 초기 모델 | 변경 전 GitHub 기준·소스 검증. 과거 완료 주장과 1.0.6 신규 검증 구분 | G-COL-01/02, G-OBS-01, G-TARGET-01 |
| **0.3.1QFix** (현존) | 독립 World clearance, 2회 긍정 분리 재장전, 측별 R2 최대 32 unique-cell check, one-credit, 2면 분배 | 전체 AABB 8,192셀 상한·동일 틱 최종 재검증; Sable 원본/서버 상태 불변. 실제 게임 통과는 미확정 | G-LEDGER-01, G-REST-01, G-CLEAR-01 |
| **0.3.1 이후 첫 수정** (필요성 확인 시) | 우선 section-level 안전한 공기/충돌 공간 skip, 스캔 비용·False-negative 수집 | 새로운 빈 공간 증명은 현재 1-tick freshness·loaded/chunk/generation 불변 조건보다 약해서는 안 됨. 별도 대형 Plot 테스트 | G-CLEAR-02 |
| 0.4.0 | 완전한 H/T/D/E 프로필, 탄성 1회, Direct/Plastic/Stress 미리 계산, fixed-point, 희소 key + serializer 순수 테스트 | `DamageModel` prototype의 `ceil` 누적 금지. `DirectionEvidence` optional 필드 정의. 손상/변형/stress 실제 미활성 구분 | G-MATERIAL-01, G-STATE-BASE |
| 0.4.1 | 정적 World 실제 피해 누적·파괴·단일 드롭·권한 검사 | **최소 재시작 안전 저장 + 작업 ID 및 중복 방지 선행**. 서버 thread commit, CAS, unload veto. 분산 트랜잭션 성공 가정 금지 | G-WORLD-01, G-COMMIT-01 |
| 0.5.0 | 청크 희소 저장, 검증된 restore/replace/migration/backpressure 전체 완성 | 누락 대신 bounded backpressure, old fingerprint reject, save-dirty batch | G-SAVE-01 |
| 0.5.1 | 실제 Plot 블록·BE 안전군 변경, 기존 mass/collider lifecycle, 양측 공유 피해 | **split 감지와 old-owner 상태 격리** 선행. 이전 lineage 증거가 없으면 상태 이전 금지 | G-PLOT-01, G-PLOT-LIFE-BASE |
| 0.6.0 | owner-local bounded 6면 응력, level별 유입 합산, 이중탄성 방지 | deterministic visited/depth, 잔액 보존, Q 신규 발행 금지 | G-STRESS-01 |
| 0.6.1 | Sable split/merge/reassembly와 glue/BE/state 이전 | 실제 블록 대응 검증, quarantine 복구/만료 정책, generation 변경 | G-SPLIT-01 |
| 0.7.0 | World의 검증 방향 항복·영구 격자 이동 | 사전 확보된 optional direction만; 목적지·권한·원본 fingerprint preflight | G-DEFORM-WORLD-01 |
| 0.7.1 | Plot의 owner-local 변형·BE/glue/kinetic 및 collider/mass 갱신 | multi-block 안전군, lifecycle 성공 검증, 부분 실패 격리 | G-DEFORM-PLOT-01 |
| 0.8.0 | 균열·fade·클라이언트 delta·재접속 복구 | 실제 손상 영속값과 시각 fade 분리. dirty-only sync | G-VIS-01 |
| 0.8.1 | vanilla 폭발·투사체·엔티티·기타 외부 이벤트 | 고유 cause/parentCause, 원본 폭발 우선, 생존 target만 PDD 추가 | G-EXT-01 |
| 0.9.0 | 대형 함선·10명 이상 PvP 성능 조정, optimizer optional 호환, 메모리 누수/백프레셔 검증 | 성능 계측은 0.3.1부터 누적; 원본 물리 콜백 회귀 0 | G-PERF-01 |
| 0.9.1 RC | 전체 파이프라인 재시작·split·회전·폭발·BE·동시성 통합 | 재현된 회귀 0, 확인 못한 경로 별도 표기 | G-RC-01 |
| 1.0.0 | 공개 배포, 설정·schema migration, 전체 소스 ZIP·JAR 안내 | Actions/게임 결과를 실제 확인한 범위로만 완료 선언 | G-RELEASE-01 |

**기존 기능 삭제 또는 이월 금지:** 위 '선행' 항목은 다음 버전의 전체 범위를 앞당기는 뜻이 아니다. 실제 상태 변조 전에 필요한 최소 안전 계약만 당겨 적용하며 해당 버전 목표는 그대로 완성한다. 0.3.1QFix의 `worldMutation=OFF`도 유지한다.

### 14.2 구현 진행 순서와 종속성

```text
source evidence + owner epoch + owner-local R2
  -> verified separation / reapproach + one Credit
  -> immutable side allocation + unused reconciliation
  -> MaterialSnapshot / fixed-point plan + sparse serializable state
  -> same-source ApplyId + World state CAS / minimal durable save
  -> chunk persistence full lifecycle
  -> Plot safe commit + ownership quarantine
  -> bounded stress; verified Sable split state migration
  -> verified direction World / Plot deformation
  -> visual delta + external cause adapters
  -> profiling + full regression + 1.0.0
```

### 14.3 구현에 사용해야 할 기존 소스와 피해야 할 중복

- `GameImpactCredit.step`과 `ContactCreditLedger.issue`를 동일 사건에 각각 여러 번 호출하지 않는다. `burst` 최초 승인과 첫 이벤트 최대 강도는 §22의 개발 프로필로 고정한다.
- `MaterialRegistry`, `DamageModel`, `BlockDamageState`를 수정·확장한다. 같은 식을 별도의 DVT 피해 엔진에 재구현하지 않는다.
- `SableContactRegions`, `OwnerResolver.NativeAuditBatch`, `NativeContactMassEpoch`의 검증을 통과한 대상만 downstream 전달한다. R3/R4 진단 결과로 대상 추가 금지.
- 단계별 복잡도와 게임 모드를 보고 새 구조의 필요성이 증명될 때만 독립 인덱스나 라이프사이클 adapter를 신설한다.

### 14.4 소스/API 확보 실패 시 변경 원칙

기존 PDD/JAR/Gradle/Actions 소스를 읽을 수 있는 다른 경로를 먼저 시도한다. 다운로드 불가 자체는 기능 개발 중단 근거가 아니다. 그러나 해당 기능에 필요한 클래스·signature·소유권·원자성 계약을 확인하지 못하면 그 기능을 가짜 완료로 선언하지 않는다. 실제로 가능한 단계와 배포 가능한 전체 소스는 계속 진행한다.

### 14.5 개발 상태 구분

`소스 확보 → 구현 중 → 구현 완료(정적 검토) → 전체 소스 ZIP 생성 및 무결성 → Gradle/JUnit 결과(실행한 경우만) → GitHub Actions → 인게임 검증`을 독립 상태로 기록한다. **설계서만 수정한 현재 1.0.6 작업은 구현 완료/ZIP 소스 배포가 아니다.**

## 15. 검증/진단 계획 — 실패 원인마다 하나의 실제 증거

### 15.4 1.0.7 이후 모든 릴리스의 F·CSV 회귀 시험

**수학/충격:** `F_side=100`일 때 고유 단일 연결 5블록 `assigned=[50,25,12.5,6.25,6.25]`, 합=100; 1/2/3블록 종단, 2×2·3×3 씨앗 균등, 다중 분기·diamond 합류, 경로별 부모 탄성만 감쇠, 동일 블록 G 1회, 다른 owner 동일 XYZ 허용, 서브threshold·미로드/한도 잔액은 terminal 금지. `E=0/100`, 부모/자식/형제·자기 Q의 독립 원장, double elastic 사용 금지.

**방향/안전:** verified World/Plot, 회전 및 사선 normal, 점곱 −0.98/0.995/0.98 경계, 서로 다른 manifold 충돌 시 UNKNOWN, unknown-depth2, native-depth9/cone0.36, 100-record native cap, 128-node/32-target/8192-cell 별도 이유. 원본 Sable callback 반환/순서 및 물리 응답 불변.

**CSV/물성:** bundled only / external only / 부분 필드 / `0` 대 빈 칸 / 외부 없는 ID / JAR 없는 ID / 등록되지 않은 ID / item T 부재 / 중복 CSV ID / 따옴표·BOM / 비정상 H-D-E-T / JSON 동시 입력 / state-specific 충돌 / 레시피 DAG 순환 / 불완전 파일 rollback / reload-inflight snapshot freeze / client CSV 권위 금지. `slime T=19(첨부 CSV)` 대 `T≥64(현행 코드)`는 실제 테스트 요구이며 자동으로 동일하다고 주장 금지.

**실제 변경:** World/Plot 서버 스레드 CAS, claim/BE veto, once-only drop, sparse damage+remainder, 재시작 Receipt·stale owner, split/merge/GLUE, stress node-once, deform grid safety, visual delta, parentCause 외부 입력, TPS/P95/heap. 각 릴리스는 영향받은 기존 테스트 보존 및 신규 JUnit 추가를 요구한다.

**검증 분리:** 수학 property 테스트·독립 javac 성공·GitHub `compileJava`·`compileTestJava`·JUnit 개별 수·Gradle `build`·Actions·인게임은 모두 **별도 결과**. 0.4.0colFix2 이전 Actions의 `277 tests completed, 1 failed`는 새 1.0.7 구현 시험 성적이 아니다.


### 15.0 원천·검증·인게임 사례의 분리 (1.0.6, 규범)

각 개발 기능은 `sourceUnit`, `owner/target`, `first contact/rest/reimpact`, `fixed Q`, `material`, `save/commit`, `lifecycle`, `performance`를 독립 판정한다. 0.3.1QFix 테스트에서 >32 셀, 8192 상한, 동일 tick final clearance의 정적 단위 테스트와 실제 게임에서 대형 함선 성공을 혼동하지 않는다. 0.4.1 이후 저장·재시작·BE/claim veto, 0.5.1 이후 split/ghost-state, 0.6 이후 합류/순환, 0.7 이후 방향·이동, 0.8 이후 동기화/외부 원인 테스트를 §26/§27에 따라 누적한다.


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

### 16.1 문서 개정과 코드 릴리스의 다른 산출물

**통합설계서 1.0.7 편집**은 Markdown 문서 산출이므로 그 자체로 모드 기능 구현·빌드 성공을 의미하지 않는다. 이후 **코드 개발/버그 수정 릴리스**는 최신 GitHub `main` HEAD/파일 내용/Actions 계약을 먼저 확보하고, 전체 Java·리소스·테스트·설정 원본을 보존하여 **전체 프로젝트 소스 ZIP**을 제공해야 한다. GitHub clone/zip 다운로드 실패 때도 파일별 읽기·기존 실제 확보 소스로 진행한다. 기존 Apply 보호 대상 `.github/`, `incoming/`, Gradle Wrapper 및 root 정책은 변경하지 않는다. Java 컴파일/API 검증·Actions/인게임 결과를 가짜로 선언하지 않는다.


### 16.0 문서 단독 개정과 소스 배포 계약 구분 (1.0.6, 규범)

1.0.6 문서 작업은 **설계 문서 작성**으로, 0.3.1QFix 소스 수정·모드 배포를 수행하지 않는다. 실제 개발 명령 시 최신 GitHub SHA/전체 소스/JAR/컴파일 의존성과 `.github/workflows` Apply·Build·Test를 다시 확인한다. 기존 Apply 보호 정책(`.github`, `incoming`, `gradlew`, `gradlew.bat`, `gradle` 등)은 현재 소스에 대해 보존하고, 프로젝트 전체 Apply-eligible 소스 ZIP과 보호 파일 유지 기준을 구분한다. Gradle/JUnit 미실행은 실제 실행 성공으로 표기하지 않으며, 가능할 때 `./gradlew clean test --no-daemon`과 `./gradlew build --no-daemon`을 실행한다.


- 개발 시작 시 GitHub `main` HEAD **전체 SHA와 트리**, 전체 소스 기준을 확인하고 사용자 제공 소스 archive와 차이를 기록. GitHub Actions `.github/workflows/{build-gradle,pdd-callback-test,apply-pdd-zip,import-pdd-issues}.yml`을 보호한다.
- 대상 Gradle Java 21/NeoForge 21.1.228/ModDev 2.0.78/JUnit 5.11.4/Sable 2.0.6 종속 계약을 임의로 수정하지 않는다. 빌드 실패가 있으면 실제 classpath/bytecode/테스트 출력을 보고 원인을 고친 뒤 **전체 clean test+build 재실행**.
- **전체 ZIP**: 루트 또는 유일하게 식별되는 프로젝트 root에 `build.gradle`,`settings.gradle`,`src/main/java` 필수. 기존 Apply script는 `.github/`, `.git/`, `incoming/`, `gradlew`, `gradlew.bat`, `gradle/` 루트를 보호하므로 전용 소스 ZIP에는 제외할 수 있으나 이 파일은 GitHub에서 보존되어야 한다. 하나의 incoming ZIP만 업로드, 이름의 임의 부정확한 릴리스 표시 금지.
- 실제 `compileJava`, `compileTestJava`, `processResources`, `jar`, JUnit total/passed/failed/skipped, `clean test`,`build`, GitHub Actions run ID/SHA, 인게임 true/false, JAR 설치·로그, ZIP integrity 전부 **별도 PASS/FAIL/NOT_RUN**으로 보고.
- 유사 애드온은 PDD 구현의 안전 검증을 대체하지 않는다. 원본 callback 호출 횟수/반환/예외, special Fragile/Belt, 서버/클라, Sable scene split/owner, no-world-mutation while observing을 회귀한다.
- 핵심 기능의 관측 및 게임피해 경로는 정상 설정에서 **기본 활성**, 개발자가 고급 JVM 플래그를 사용자에게 요구하여 핵심 동작을 숨기지 않는다. 실제 월드 변경은 지정 버전부터 활성화.
- 라이선스: Sable PolyForm Shield/다른 애드온 라이선스 범위를 각각 확인. PDD의 설계·알고리즘은 독립 구현. PDD Source ZIP에 Sable/타 모드 native/JAR를 몰래 포함하지 않는다.

## 17. 전체 통합 알고리즘 — 모든 버전 공통 의사코드 (1.0.7)

**설계 의사코드.** 아래 함수명·타입은 Sable/NeoForge에 실제로 존재한다고 주장하지 않는다. 검증된 외부 Java API는 §3/§21에서 따로 관리한다. `enabled(CAPABILITY)`는 릴리스 버전 번호가 아니라 §31의 충족 게이트로 정한다.

```text
onBootstrapOrSupportedReload(server):
    assert registries_and_recipes_ready
    # immutable; never scan all placed blocks or read CSV during collision
    bundledBlocks, bundledItems = parse_and_validate_classpath_csv()
    externalBlocks, externalItems = parse_optional_mods_csv()
    legacyJson = parse_existing_config_json_without_deleting_it()
    blocks = resolve_each_field(externalCSV > legacyJSON > JAR > inferred > default)
    items  = resolve_each_field(externalCSV > legacyJSON > JAR > inferred > default)
    states = validate_world_independent_state_overrides(blocks, legacyJson)
    nextSnapshot = immutable_new_generation(blocks, items, states, sourceHashes,
                                            algorithmRevision, materialGeneration)
    atomic_publish(nextSnapshot)  # failure: retain prior valid snapshot

SablePhysicsStep:
    original solver/CCD/callbacks/effect consumer runs unchanged
    PDD observes exactly SAME 15-double result once (no second clearCollisions)
    capture bounded source evidence (original content/call count/return unchanged)

ServerSafeObserve:
    validate finite native array, source kind, scene, dimension, owner IDs/epochs
    verify R2 real owner-local BlockState + collision shape; mark uncertain sides
    verify positive separation/reapproach and newImpact, not native silence
    select ONE source F_event from approved collision event representative
    if no approved newImpact: mark Q=0, end
    issue ONE CreditId with group cap = min(maxGroupQ, G(F_event))
    allocate F_sideA=F_event*0.5, F_sideB=F_event*0.5 # side caps only
    capture materialSnapshot and algorithmRevision at approval

ServerSafePlanPerSide:
    collect verified initial contact seeds in each stable ownerSpace
    direction = validate_native_normal_frames_or_UNKNOWN()
    # owner-local bounded propagation; parent-child DAG, not World-wide BFS
    graph = build_bounded_forward_F_graph(seeds, direction, loaded, shapes, caps)
    distribute F: each nonterminal node assigns half, passes half to
                  verified next-layer children; proven terminal assigns all
    apply elasticity of parent to its outgoing F (t(E_parent))
    join same-depth incoming F before single-node G(F_i)
    for each child with verified parent flow:
        attenuate only that actual parent's still provisional Q,
        weighted by its delivered-to-child F and t(E_child)
    unresolved/unknown/depth/cap/belowFloor -> reasoned unusedF, NOT terminal
    candidateQi = G(assignedFi) for each unique block once
    cap deterministically by sideQCap and groupQCap (no new Q)
    plan = freeze(finalQi, source/owner/target/material/algorithm generations)

ServerSafeMaterialPlan:
    for each verified target and frozen finalQi:
        resolved = lookup_snapshot_only(target.BlockState)
        if eligibility/protection fails: unusedQi, not fake applied damage
        qActive = finalQi * t(E_self) # self Q elasticity EXACTLY ONCE
        qDirect/qPlastic/qStress = partition_fixed_budget(qActive)
        fixedDamage = quantize_floor256(qDirect * damageGain * f_H(H))
        tentativeState, moveIntent, breakIntent = bounded_plan_only()
        optional stress continuation consumes ONLY the qStress share
        unsupported capability => plannedUnused, not retroactive replay

ServerSafeCommit (only after capability gate):
    check unique ApplyId and immutable material/owner policy generation
    preflight real owner-local block, chunk loaded, state/BE fingerprint,
              claims, unbreakable, plot split and mutation safety
    actual WorldBlockAdapter or PlotBlockAdapter execute on safe server thread
    re-read actual state and persist change/Receipt; drop once as proven
    on failure: ABORT/BOUNDED_DEFER/QUARANTINE, never new Credit
    client delta only for APPLIED changes

ExternalSources (when supported):
    preserve vanilla explosion/third party side effects; validate actual cause
    source-specific Q adapter -> SAME downstream MaterialPlan/Commit
    parentCause / causeGroup prevents collision/explosion double budgeting

Optional future solver source:
    select verified native impulse backend INSTEAD OF proxy backend per event
    never sum impulse-derived and post-effect-derived Q for same cause
```

**핵심 불변식:** 원본 물리 불변; valid owner·newImpact 전에는 F/Q=0; 양측 독립 F 반분; 전파한 F를 복제하지 않음; `Q_i`는 F 분배 완료 후 1회 생성; 자식 탄성은 실제 부모 경로만; 자기 Q 1회 감쇠; branch/merge determinism; Q-stress와 F 별도 원장; snapshot freeze; World/Plot 권한과 원자성 제한; 저장·드롭 정확히 한 번은 실행 증거 없는 한 미확정.

## 18. 기준 아티팩트·근거 위치 색인 (이 절은 구현 상태 판정표가 아니다)

### 18.5 1.0.7 정적 입력·정합 및 관측 근거 (새 기준, 과거 SHA 대체 아님)

| 자료 | 고정 식별/상태 | 직접 사용 범위 |
|---|---|---|
| PDD `main` | `292dbaf0baafb6eedeaf01a1173f871563dac133` (2026-11-06 확인) | 0.4.0colFix2 소스 기능·미구현 판독. 다음 릴리스 자동 기준 아님 |
| 1.0.6 원본 | `docs/PDD-1.0.6-Integrated-Design-Specification.md` / 이 작업 첨부 | 기존 0~29 모든 요구와 불변식 승계 |
| F v0.1 | `docs/PDD-0.4.0-F-First-Impact-Propagation-Design-v0.1.md` | 사용자 F-first·반분·앞선 Q/후속 F 감쇠·정상 종단 요구 |
| F v0.2 | `docs/PDD-0.4.0-F-First-Impact-Propagation-Design-v0.2.md` | verified normal gate, 128/9/2, cone, path-parent Q, slime T≥64 정책 |
| 물성 소스 보고서 | `PDD-0.4.1-Material-Data-Source-Resolution-Report(1).md` | 정적 4단계, 블록/아이템 CSV, JSON 호환 및 reload 위험 |
| 블록 데이터 | CSV 1,844 ID, SHA-256 `8977bde3dcad2ba604a0b22ef593712b833b72a2776375b2397bc7dffd780a46` | **추정값**; JAR 내장 전 registry/물성 충돌 검증 필요 |
| 아이템 데이터 | CSV 2,159 ID, SHA-256 `b82b6fe736e9ebc0fba75f983df4f7bbdaae1588e2320825343fc4bf3eb33e67` | 레시피 재료 분석·향후 DVT 입력. item T/직접 피해 생성 아님 |
| 구현 대조 보고 | `PDD-0.4.0colFix2-IDS-Implementation-Gap-Analysis.md` | 기준 SHA의 연결 구현/preview/미구현 구분 |
| GitHub Actions 실패 | run `42056043630`: Java main/test 컴파일 성공, JUnit 277중 1 실패 | 테스트 구버전 checkpoint filename 불일치. 1.0.7 구현 빌드 검증 아님 |

**자료 범위:** CSV의 데이터 행 수는 실제 서버에 설치된 모든 모드의 registry 전수 확인 수와 동일하지 않다. Sable Java/JNI 2.0.6 소스·JAR의 기존 §18.1 해시와 v0.2 normal 계약을 구분한다. 설계 미결정을 과거 코드 동작이라는 이유로 사용자 확정 계약으로 승격하지 않는다.


### 18.0 1.0.6 추가 근거 및 미확인 구분 (1.0.6, 규범)

- **최신 기준:** `473dbdd58286cda5a84941b3bcc5eb60ec711c81` (`0.3.1QFix`); `https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/blob/473dbdd58286cda5a84941b3bcc5eb60ec711c81/docs/verification/0.3.1QFix.md`.
- **보고서 원문:** `docs/research/PDD-Sable-2.0.6-Plot-Coordinate-Forensics.md`와 `docs/research/PDD-0.3.1plotRog-Resolution-Decision-Report.md`는 업로드된 동명 보고서의 Git blob 내용과 동일한 자료다. 좌표 포렌식은 이전 0.3.1fix의 오차 원인, plotRog 결정 보고서는 Q 중복/R2/편측 정책, QFix 검증 문서는 전체 AABB와 8192 한계를 설명한다.
- **직접 확인:** Sable 2.0.6 JAR `javap`에서 `EmbeddedPlotLevelAccessor`의 `getBlockState/setBlock/removeBlock/destroyBlock` 및 `ServerSubLevel` split 관련 메서드 **선언** 확인. Java bytecode 선언은 실제 Sable lifecycle 안전성을 보증하지 않는다.
- **아직 미확인:** 1.0.6 설계에 제안한 chunk-section collision-empty 증명 API, local pair collision occupancy, per-contact substep pose, World/Plot mutation 원자성·재시작 exactly-once, DVT 통합 실행 결과. 성능 향상 폭과 사용 편의성은 플레이테스트/프로파일링 전 추정이다.
- 이전 §18.3의 `moreRog` SHA 등은 소스 변화 기록이며 **현재 HEAD가 아니다**.


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

## 19. 통합 설계서 1.0.7 완료 판정과 설계 안정화 규칙

### 19.2 1.0.7 설계 충족 판정 — 문서 내용과 프로그램 동작 분리

- 이 문서의 편집 완료는 **문서 통합**에만 해당한다. F-first 구현이 전체 게임에서 작동·안전 커밋이 성공·CSV가 자동 등록되었다는 의미가 아니다.
- 전 버전 규범 완료란 §17 F/Q 분배 + §7 static MaterialSnapshot + §8 Q-stress 분리 + §9 Receipt/영속성 + §10 external+DVT 접합까지 설계상의 경계·알고리즘·실패 사유가 일관되게 정의된 것을 뜻한다.
- 게임 기능 완료는 해당 Git SHA의 소스·실제 JAR API/callpath·정식 JUnit·Gradle/Actions·인게임 검증 후에만 선언한다. 미확인 외부 ABI는 의심되는 call을 임의 구현해 API 검증 완료라고 적지 않는다.


### 19.0 현재 작업 완료 범위 (1.0.6, 규범)

완료: GitHub 현재 HEAD/트리와 정적 Java/QFix 보고서·Sable JAR 선언을 근거로 한 **통합 설계서 1.0.6 개정**. 미완료: 1.0.6 알고리즘의 코드 구현, 실제 Gradle/JUnit/Actions 재실행, 인게임 복구·성능 검증. 기존 §19의 1.0.5 검토 상태 문구는 **이전 문서 작성 당시의 기록**이며 새로운 1.0.6의 게임 검증 성적이 아니다.


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

**1.0.5 시점의 역사적 결과:** 과거 문서는 1.0.5 작성과 정적 역공학만 완료했다. **이번 1.0.7 결과는 전 버전 공통 계약을 갱신한 설계 문서 생성**이며, 새 Java 기능·Gradle/JUnit/Actions·인게임은 미실행이다. 최신 모드 구현 현황은 §29의 `292dbaf0...` 스냅샷과 별도 검증 장부를 따른다.

---

## 20. 1.0.5-A 규범 보완 — 설계 문서를 실제 구현 가능한 계약으로 사용하는 방법

### 20.4 다른 버전에서의 1.0.7 준수 판정

개발자는 어떤 PDD 모드 버전에서든 **원인/owner 증거·F 분배·node Q/탄성·정적 데이터 결정·snapshot 동결·safe commit·state/Receipt·회귀·전체 ZIP**을 같은 입력/출력 계약으로 유지한다. 기능이 아직 활성화되지 않은 릴리스에서는 결과를 `PLANNED/UNUSED/NOT_IMPLEMENTED`로 구분한다. 새 릴리스에서 성능 최적화 목적으로 계수·캡·자료 저장 방법을 바꾸더라도 기존 유한/중복·원본 물리·신뢰 경계를 위반하지 않아야 하며 새 revision+전/후 테스트를 제공한다.


### 20.0 공통 설계 계약과 릴리스별 구현 범위 (1.0.6, 규범)

같은 시스템을 릴리스마다 다른 엔진으로 재설계하지 않는다. 이 문서의 **Owner/Target, One Credit, MaterialSnapshot, ApplyId, SparseState, Sable lifecycle**은 모든 후속 버전과 DVT에 공통이다. 각 릴리스는 §14의 범위에 해당하는 코드와 JUnit/게임 게이트만 추가한다. 실제 API가 없을 경우 정확성은 격리하고 근거가 있는 대체 경로만 채택한다. 특정 버전 미완성으로 전체 설계의 나머지 단계 개발을 포기하지 않는다.


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

## 21. 관측 소스·좌표계·타깃·공간분리 API 계약 (1.0.7)

### 21.8 native normal ⇒ F 방향 증거, 정확 impulse 아님

v0.2의 signed normal 안정성 조건(`dotAB≤−0.98`, `abs(dot(pre/effect pose))≥0.995`, 동일 프레임 내 dot≥0.98, cone0.36)과 `sourceSubstep=-1`을 `DirectionEvidence`의 **선택적 권한**으로 취급한다. R2 owner-local 대상/새 타격/owner epoch를 이 판정으로 대체하지 않는다. Plot/World와 Plot/Plot의 좌표계 및 실제 rigid body pose를 각각 검증한다. Native cap·다중 법선·사선 불명확은 해당 방향 권한을 강등하되 원본 원인/콜백에 허위 오류를 추가하지 않는다. 정확한 grid shift·mass·impulse는 별도 source capability로 검증한다.


### 21.1 원본 Sable 2.0.6 출처와 15-double 의미

- 검증된 Java 관측 경로: `RapierPhysicsPipeline.processCollisionEffects()`에 전달되는 `Rapier3D.clearCollisions(long)` 결과 **15개 double 한 레코드**를 원본 호출 후 read-only로 복사한다. JNI를 새로 호출하지 않는다. 원본 callback 반환, 예외·횟수, CCD/solver와 특수 블록 동작을 보존한다.
- 필드 인덱스: `[0] bodyA`, `[1] bodyB`, `[2] F_raw`, `[3..5] A local normal`, `[6..8] B local normal`, `[9..11] A local witness`, `[12..14] B local witness`. `bodyId=-1`은 이 확인된 경로에서 World static 콜라이더를 뜻한다. 서로 다른 두 witness는 하나의 점일 의무가 없다. NaN/Inf, 잘못된 ID, 불완전 배열은 거절한다.
- 같은 contact force가 여러 witness point 레코드에 반복될 수 있으므로 **그룹의 F_raw는 최고값**, `sum(points)` 금지. `F_raw`는 솔버 임펄스나 운동 에너지가 아닌 게임 프록시 입력. native 최대 100 레코드 보고는 **포화 표시**이지 실제 잘린 수를 측정한 값이 아니다.
- **좌표 사실**: 동적 Plot의 `P_body= P_storage − C`, `P_storage=P_body+C`, `P_embedded=P_storage−plot.getCenterBlock()`, `P_world=T+R(P_body)`(scale 1인 확인 경로). `C=rotationPoint`의 COM epoch가 바뀌면 raw point에 최신 C를 기계적으로 적용해서는 안 된다. 정적 World witness는 확인된 static collider 경로에서 월드 위치로 사용한다. 일반 Collider offset/비단위 scale까지 이 공식을 무조건 확장하지 않는다.

### 21.2 API 사용 위치, 증명 상태, 금지된 사용

| 실제 위치/API | 0.3.1QFix 실제 사용 또는 검증 | 허용 목적 | 금지/추가 검증 |
|---|---|---|---|
| `Rapier3D.clearCollisions(long)` | Sable 원본 `processCollisionEffects`가 호출 | 원본 반환 배열 1회 관측 | PDD 재호출·반환 변형·physics thread IO 금지 |
| `NativeCollisionTelemetry.capture/drain`, `NativeEffectGroups.aggregate` | PDD Java 소스 | 15필드 검증, 큐, peak, poll 포화 | native coverage 전체 보장 주장 금지 |
| `SableContactRegions.audit` + `OwnerResolver.NativeAuditBatch` | PDD Java 소스 | 두 측 독립 collision-shape 근접 R2 실블록 증명 | 포인트 ordinal을 contact ID로 오인·R3 projection 권위화 금지 |
| `NativeContactMassEpoch` / `OwnerFrame` | PDD Java 소스 | 각 substep COM·Plot 일관성 확인 | frame 흔들린 측을 임의 보정하지 않음 |
| `ServerSubLevel.getPlot`, `getSplitFromSubLevel`, `getSplitFromPose` | Sable JAR 선언 직접 확인 | owner/Plot 접근·split 관련 **단서** | split 메서드만으로 moved block 일대일 대응 확정 금지 |
| `LevelPlot.getCenterBlock/getCenterChunk` 및 `EmbeddedPlotLevelAccessor.getBlockState` | Sable JAR/실제 PDD 판독 | Plot storage와 embedded 상대좌표 명시 | native storage 좌표를 상대좌표 accessor에 그대로 전달 금지 |
| `EmbeddedPlotLevelAccessor.setBlock(BlockPos,BlockState,int,int)`, `removeBlock(BlockPos,boolean)`, `destroyBlock(BlockPos,boolean,Entity,int)` | Sable 2.0.6 JAR `javap`에서 **선언 확인** | 0.5.1 이후 plot owner-local 실제 변경 후보 | BE·질량·collider·drop·split 부작용과 thread 안전이 자동 검증됐다고 주장 금지 |
| `GameImpactCredit.step/evidence`, `ContactCreditLedger.issue` | PDD Java 소스 | 신규 episode 신용과 이미 검증된 양측 분배 | side/share 재발행·`availableQ=0`을 실제 소비 완료로 해석 금지 |
| `WorldClearanceVerifier` | PDD 0.3.1QFix 소스 | World 전체 AABB의 동일 tick 긍정 빈 공간 증명 | 8,192셀 초과에서 CLEAR 간주, 여러 tick 읽기를 같은 시점으로 간주 금지 |
| `MergedMassTracker.uploadData` | 소스 포렌식 | COM 업데이트 시점 위험 파악 | COM epoch 부재를 현재 frame으로 추정 복구 금지 |
| `RigidBodyHandle.getAngularVelocity/getLinearVelocity`, `MassData` | 1.0.5 소스·JAR 참고 | 향후 검증 방향·타격 강도 선택적 보조 | 원본 solver impulse 대체하는 추측 계산 금지 |

API 종류 구분: 위 목록의 클래스/메서드는 현재 사용/정적 존재 확인을 분리했다. 외부 클래스의 `compileClasspath`, `testCompileClasspath`, `testRuntimeClasspath`, 게임 런타임 및 Jar-in-Jar 버전 호환은 **해당 기능 개발 시 실제 해결**한다. `sable_optimizer`는 선택적이며 optimizer ON/OFF 간 callback/physics 불변성을 시험한다.

### 21.3 실제 타깃 승인: R1~R4 사용 권한 명확화

1. **R1**: 실제 Sable callback에 owner scope와 블록 좌표가 확인된 경우만 해당 owner의 블록 후보. 특수 callback hint의 시점·강도는 독립 source approval이 없다면 Q의 단독 근거가 아니다.
2. **R2 (현행 주요 권위 경로)**: native A/B witness를 **서로 독립** body-local/World 좌표로 해석하고, owner UUID·generation·COM epoch·실제 loaded BlockState·VoxelShape와 per-side point를 검증한다. 두 witness의 세계 위치 차이가 `0.25`를 넘는다는 이유로 선행 거절하지 않는다.
3. **R3**: 타측 world projection으로 생성한 후보는 native record별 같은 substep의 pose를 보유하지 않는 한 **진단용**. 실제 Q 대상 승인에 사용하지 않는다.
4. **R4**: 구조물 swept/경계 후보는 **진단용**, `WorldClearanceVerifier` 예산과 분리하며 성능이 필요하면 debug/sampling에서만 수행한다. 후보를 Q-credit 대상 또는 clearance 증거로 쓰지 않는다.

`R2`는 측당 최대 **32개의 신규 고유 셀 검증**을 허용한다. 이것은 32개 블록의 무조건적 승인이나 모든 충돌 면적의 완전성 보장이 아니다. 33번째 신규 셀 검증이 필요해 검사 미완료라면 해당 측은 `SIDE_A/B_INCOMPLETE`로 격리한다. 다른 측의 검증된 몫은 계속 허용한다. 한 native group에 속한 중복 witness를 새로운 에너지/타격으로 만들지 않는다.

### 21.4 인덱스, 위치와 검증 시점

- 운영상 `runtime body id -> stable UUID/World` 조회는 **해당 tick/scene에 제한된 해시**로 구현한다. owner lifecycle 이벤트 기반 갱신이 실제 제공되는지는 별도로 검증한다. 현재 `RealFixRuntimeBridge.readServerBounds`는 서버 tick마다 Plot bounds를 읽으므로 기존 1.0.5의 '이벤트 기반 인덱스 완성' 문구를 현행 구현 사실로 표기하면 안 된다.
- 실제 target key에서 World는 world `BlockPos`; Plot은 embedded 상대 `BlockPos`를 사용한다. Plot 저장 그리드는 `P_storage`, 변환 전 source point는 `P_body`, World 좌표는 `P_world`로 이름을 구별한다.
- R2 검사 cache는 같은 **사건/단일 서버 tick**에서만 유효하다. 실제 Shape/BlockState가 바뀌거나 Plot owner generation이 바뀐 이후 cache 재사용 금지. 블록 프린트에는 registry ID·필요 상태 속성 및 안전한 BE identity를 쓰고 BE NBT 전체를 매 충돌마다 동기 직렬화하지 않는다.
- 재료·응력·변형은 검증된 `TargetRef`를 받아서만 작업한다. 이 downstream 계층에서 world/local 기하를 다시 추정하지 않는다.

### 21.5 World↔Plot 및 Plot↔Plot 분리 증명 (0.3.1QFix 사실)

- **Plot↔Plot**: 검증된 world AABB가 실제 분리된 경우 전체 pair separation 긍정으로 사용할 수 있다. 겹침만으로 지속 접촉 확정은 하지 않는다.
- **Plot↔World**: 직전에 충돌한 World 블록 1개의 AABB와 떨어졌다는 사실만으로 전체 World 분리 승인 금지. 현재 `WorldClearanceVerifier`는 Plot의 보수적인 **전체 확장 World AABB**를 대상으로 로드된 충돌 가능 블록/Plot chunk overlap을 확인한다.
- 증명 상한: 최대 **8,192 World 셀**. 증분 검사 256 cells/pair/tick, 전역 1,024 incremental checks/tick, 최종 **같은 server tick 전체 재검증 8,192 global confirmation checks/tick**. 여러 tick에 걸쳐 읽은 구간만으로 `CLEAR` 반환 금지. **두 번의 연속 긍정 CLEAR**가 `COOLING → ARMED` 근거다. `TOO_BROAD`, `UNAVAILABLE`, `IN_PROGRESS`, `WAIT_ATOMIC_CONFIRM_BUDGET`, `BLOCKED`는 긍정 증거가 아니다.
- 이 조건은 안전하지만 커다란 함선이나 빈 AABB 내부에 정적 블록이 있는 장면에서 false-negative가 가능하다. 불명확한 경우는 **Q=0 또는 재장전 보류**, 통과로 꾸미지 않는다.

### 21.6 대형 Plot 최적화의 독립 검증 조건

**우선 순서**: ① 운영 로그에서 검사량/TOO_BROAD/월드 false-negative 빈도 계측; ② 안전한 Minecraft 청크 섹션 fast-path로 **실제로 완전히 비충돌임이 확인된 구간**만 skip; ③ 여전히 필요하면 Sable이 증명하는 실제 Plot collision occupancy 기반의 좁은 영역을 선별한다. ②의 빈 섹션 판단은 블록이 공기인지뿐 아니라 collider/BlockState/외부 모드 변경·청크 load·동일 tick freshness를 충족해야 한다. ③은 collider point 시점/COM epoch/소유자 정확성이 입증되기 전 권위 경로에 도입하지 않는다.

- Chunk section API가 없다면 임의 캐시를 새로 만들지 말고 현재 보수 검사 유지. 추측으로 `TOO_BROAD`를 `CLEAR`로 승격시키지 않는다.
- 개별 블록 조회 상한을 넘지 않으면서 전체 대상 영역이 같은 시점에 비충돌이라는 불변식을 보존하는 방법만 채택. per-pair 검사 제한·전역 최종 확인 비용을 분리 기록하고 구간 무효화를 시험한다.
- **장기 대안**: 검증된 contact-region별 지역적 재타격은 `PairEpisode`의 별도 독립 접근 증거 및 **원래 Q 발행 횟수 상한**을 유지하는 경우에 한해 도입한다. 단순 힘 상승, native silence, 지형 접촉 후보 수는 재장전 권한이 아니다.

### 21.7 거절 및 인게임 사례

- 전체 World clearance가 미증명인 동안 함선이 앞선 벽 블록을 떠나 다음 블록에 닿으면 Q는 재장전되지 않는다.
- 경첩·계단·철창 등 복수 Shape에서는 **각 owner-local shape만** 검증한다. 불완전한 측은 격리하며 '첫 후보'를 임의 정답으로 삼지 않는다.
- 회전-only 타격에서 검증된 R2 source contact와 과거 분리 증거가 있으면 게임 Q 계산 가능하지만 정확한 회전 접점속도/변형 방향까지 승인한 것은 아니다.
- 서버 청크 미로드, Plot split 진행, owner epoch 변경, COM epoch 실패는 관련 side/intent를 fail-closed한다. 무효한 World/Plot 좌표로 변경·드롭 절대 금지.

### 21.9 외부 API 사용 위치 및 버전 교체 시 재검증 목록

| 외부 제공 또는 소스 경계 | 해당 바이너리/현재 증거 | PDD에서의 허용 용도 | 불가능하거나 추가 검증 필요한 사용 |
|---|---|---|---|
| `Rapier3D.clearCollisions(long):double[]` | Sable 2.0.6 내장 `sable_rapier` Jar-in-Jar에서 `static native` **패키지 전용** 선언 확인 | 원본 Sable 소비자가 호출한 반환 배열을 **그대로 한 번 관측** | PDD의 직접 재호출·스칼라 합산·정확 impulse 가정 금지 |
| `RapierPhysicsPipeline.processCollisionEffects()` | Sable 2.0.6 내장 `sable_rapier`에서 **private** 메서드 선언 확인 | native 15-double 소비 시점 및 프레임과 passive observer 연결 | 소비 순서·원본 콜백 반환/예외 변경 금지 |
| Sable `ServerSubLevel`/`activeSubLevels`, runtime ID/stable UUID/owner generation | 제공 Sable Jar 및 당시 소스 | body/scene와 검증된 Plot 소유자 연결 | runtime ID만 영구 손상 ID로 저장 금지 |
| Sable `EmbeddedPlotLevelAccessor`의 `getBlockState`/`setBlock`/`removeBlock`/`destroyBlock` 선언 | 제공 Sable Jar에서 선언 확인 | **기능 게이트 이후** Plot local 상태 접근/변경 어댑터 후보 | 실제 server thread/split/BE/drop/mass/collider 안전성은 선언만으로 증명되지 않음 |
| Sable `MassData.getInverseNormalMass` | 2.0.6 static bytecode 확인 | 검증된 native local point/normal/mass epoch일 때만 선택적 proxy 품질 향상 | 무증거 point로 정확 유효질량 생성 금지 |
| Minecraft `BuiltInRegistries.BLOCK`/아이템 레지스트리, `BlockState`, `ResourceLocation` | 현재 프로젝트 코드 및 MC/NeoForge API | 등록 **정의별** MaterialSnapshot 및 실제 타깃 ID 확인 | 배치된 모든 블록의 레시피/상태를 매 충돌 재탐색 금지 |
| Minecraft recipe/tag manager/`BlockItem`/`ItemStack` | 현재 `MaterialRegistry` 코드 경로 | 지원 가능한 레시피를 초기화/리로드 시 정렬·추론 | 아이템 수·레시피 비율을 물리 질량으로 단정 금지 |
| classpath resource loader/일반 Java UTF-8·CSV parser/`FMLPaths` | 새 1.0.7 **구현 설계**, API 세부 검증 별도 | 외부/JAR CSV parse, 검증 및 atomic publish | NeoForge가 CSV 자동 로딩 API를 제공한다고 주장 금지 |
| Minecraft World safe block mutation + SavedData/receipts | 실제 구현 시 API 검증 대상 | 서버 thread/CAS/권한/BE/청크 기반 World apply | 외부 데이터와 블록/드롭이 자동 ACID transaction이라고 주장 금지 |
| Create Aeronautics mechanical/glue/ship lifecycle | 제공 Aeronautics 1.3.2 JAR, 별도 런타임 게이트 | Plot 블록 변조/분리 이후 원래 collider/mass/split 처리 | PDD가 독자 솔버·분리 엔진을 대체하거나 호출 존재를 추측 금지 |

**버전 전환 규칙:** 위 선언은 Sable 2.0.6/MC1.21.1/NeoForge21.1.x 조합에 대한 확인 범위다. 이후 모드 버전에서는 클래스·method·descriptor·access modifier·Jar-in-Jar·compile/test/runtime classpath와 실게임 생명주기를 다시 검증한다. 외부 API 변경을 이유로 이미 승인된 소유자·F/Q·영속성 안전 불변식을 낮추지 않는다.

## 22. 신규 충격·유한 예산·실제 소비 계약 (1.0.7)

### 22.0 1.0.7의 원장 분배 교정 (구 버전보다 우선)

- 예전 §22.3 `Q_candidate = 0.25 × max(0,F_first−2)`와 §22.4 `Side A=Q/2; target Q 균등`은 **0.3.1QFix의 역사적 정책**이다. 새로운 게임 예산의 규범은 **`F_event → F_side 0.5/0.5 → owner-local F first → G(F_i) → capped Q_i`**다. §6.6/§30을 적용한다.
- `F_event`는 승인 시점 대표 peak를 사용해 **한 번** 봉인하고 source/owner/cause/episode마다 재발행하지 않는다. `forceGain`/`forceFloor`/`maxGroupQ`는 프로필 revision에 묶이고 0.4.0colFix2 기준 `0.161/2.0/65536`은 개발용 참고값이며 0.3.1QFix의 `0.25/2.0/64`를 새 버전에 혼합하지 않는다.
- `ContactCreditLedger`가 `Q_group` 상한을 예약하더라도, 이는 노드별 Q를 이미 계산했다고 뜻하지 않는다. 최초 씨앗 수/분기 순서/동일 좌표 다중 owner를 검증한 다음 블록별 Q를 생성한다. 재료 snapshot은 동일 사건에 고정한다.


### 22.1 충돌 그룹과 episode의 서로 다른 책임

- `GameImpactCredit.Pair=(serverRun, dimension, canonical ownerA, ownerB + ownerGeneration)`은 이벤트 시점의 안정적인 물리 소유자 관계다. `record ordinal`·native runtime ID는 **영속 episode/credit ID가 아니다**.
- 동일 pair/tick의 유효 `F_raw`를 `peak=max(force)`로 집계하고 한 사건의 여러 Native contact point가 Q를 복제하지 않게 한다. 단일 Sable `clearCollisions` poll의 100건 도달은 **cap-saturated**, 초과 소실 수를 생성하지 않는다.
- `NativeEffectGroups.aggregate`의 샘플링/그룹 구조와 별개로 실제 R2는 `SableContactRegions`의 검증된 source contacts만 사용한다. 잃은 기록을 근접 블록으로 추정해 보충하지 않는다.

### 22.2 0.3.1QFix 실제 FSM 계약

```text
UNINITIALIZED --2 independent verified separation ticks--> ARMED
ARMED --native contact + independently verified approach + R2 target + valid force--> ACTIVE
ACTIVE --continued contact, sliding, silence without proof--> ACTIVE / Q=0
ACTIVE --positive full-pair separation--> COOLING
COOLING --2nd consecutive positive separation--> ARMED
COOLING --contact before rearm--> ACTIVE / Q=0
owner generation or run invalidated -> UNINITIALIZED / stale authority removed
```

- 기존 `GameImpactCredit.step()`의 실제 분기는 **신뢰할 수 없는 최초 접촉**에 Q=0이며, 재장전된 Pair에서 `pendingApproach` 최초 최대 2개 frame까지 확인한 뒤 승인 시 폐쇄한다. `quietFrames`·`risingRatio` 기반 `risingEdge` 헬퍼가 소스에 있어도 현행 main bridge에서 권위 부여에 사용 중이라고 주장하지 않는다.
- `ACTIVE && (!pendingApproach || burstFrames>=2)`인 명백한 중복은 R2를 생략할 수 있다. 원본 15필드 관측, body ownership, scene generation, FSM 갱신, separation proof 및 진단 표본은 유지한다. 실행 최적화가 owner 검증/차원 검증을 생략하지 않는다.
- **새 지역 재타격**은 기존 pair/global clearance를 우회하는 기본 기능이 아니다. 향후 도입 시 `LocalImpactEvidence`는 독립적인 signed approach/검증된 gap close와 새 원인을 요구하며, 지속 접촉 중 다른 블록 위치가 관측됐다는 이유만으로 자동 재장전 불가.

### 22.3 최초 타격 peak와 Q_game: 검증 규칙

```text
F_pair_tick   = max(valid F_raw in same owner pair and observed tick)
# 과거 0.3.1QFix 참고식 (현재 1.0.7 기본 알고리즘 아님)
Q_legacy_candidate = clamp(0.25 * max(0, F_first_approved - 2.0), 0, 64.0)
# 1.0.7: first-approved F is sealed once, then per-side F-first algorithm in §30
Credit = issueOnce(pair, episodeGeneration, F_first_approved,
                   groupQCap = min(maxGroupQ, G(F_first_approved)))
```

과거 0.3.1QFix `GameImpactCredit.step()`은 **최초 승인 frame의 peak**를 사용한다. 과거 1.0.5 문서의 `F_episode=max(first-impact burst window)` 전체 선확정과는 다르다. 두 식을 같은 구현이라고 서술하지 않는다.

**1.0.6 선택:** 구현 난이도와 중복 위험을 줄이기 위해 `F_first_approved`를 0.4.x~1.0.0 기본 게임 정책으로 유지한다. 첫 타격의 최대 force 정확도 개선이 게임 로그에서 필요한 경우에만 이미 인증된 2-frame 제한 내에서 **승인 지연 후 단 한 번 발행**하는 대체 전략을 선택한다. 이미 발행한 Q를 뒤늦게 증액하거나 다음 Native record에서 다시 발행 금지. 정확한 물리 impulse/energy 승격은 upstream solver scene/substep+voxel+단위 증명을 전제한다.

### 22.4 한 사건, 측별 검증 대상, 원장 불변식

- `ContactCreditLedger`의 실제 현재 상한은 `MAX_TARGETS_PER_SIDE=32`, `MAX_REGIONS=64`, `MAX_ENTRIES=4096`, idle TTL 600 tick. 이 값들은 현재 **실제 소스 정책**이며 사용자 영구 밸런스 확정값이 아니다. §23의 예전 multiTarget=4를 **32 개발용 값으로 교체**한다.
- **1.0.7에서는 `Side A=F/2`, `Side B=F/2`를 먼저 예약한다.** 미검증 측은 그 F/Q 상한을 미사용으로 마감하며 성공 측으로 넘기지 않는다. 각 측의 씨앗은 F를 동등히 시작하되 **추가 이웃은 F 경로를 따라 반분·합류**한다. 블록별 `G(F_i)` 후에만 Q cap을 적용한다. 예전 target별 Q 균등 분배 문구는 역사적 동작으로만 남긴다.
- **과거 0.3.1QFix 이력**의 `Grant.availableQ()=0`, `Grant.spent()=0`은 실제 커밋이 아직 없어 Share를 전부 reservation으로 봉인한다는 뜻이다. 0.4.0 이후 이를 '사용 완료'로 계산하면 안 된다.
- **1.0.6 downstream 계약**: Credit 발행과 Apply 결과를 구분한다. `issuedQ = allocatedA+allocatedB+unused` (고정소수점 반올림 정책), `credited Q`는 사건별 한 번, 동일 Apply ID의 실제 성공은 한 번, `appliedConsumed+finalUnused+reservedForBoundedWork <= issuedQ`. `unused`로 이미 결산한 금액은 다시 소비·재발행 불가.
- 원장 TTL로 승인 기록을 GC했더라도 이미 저장된 손상/커밋 결과를 초기화하지 않는다. 장기 중복 방지는 persisted `ApplyId`/작업 결과 또는 대상 블록 상태 검증으로 제공한다. 물리 원장 RAM TTL만을 월드 상태의 정확히 한 번 실행 보장으로 사용할 수 없다.

### 22.5 실패·재시도 분류와 테스트

- `UNOBSERVED`, `SOURCE_CAP_SATURATED`, `TARGET_INCOMPLETE`, `CLEARANCE_UNKNOWN`은 서로 다른 상태다. Event 없는 tick은 접촉 종료가 아니라 **UNKNOWN**. 측별 증명 부족은 반대측 Q 몫을 없애지 않는다.
- `APPLY_ABORTED`는 `CREDIT_NOT_ISSUED`와 다르다. Abort는 원래 Q를 재발행하지 않는다. 재시도는 **같은 ApplyId, 같은 owner generation, 같은 target fingerprint, 유한 TTL**로만 허용한다. 검증 불능의 재시도는 최종 unused/quarantine.
- 필수 테스트: 4/32/100 native point 동일 event Q 한 번, 5/8/16/32 실제 target 모두 Q 증가 없이 수용, 33번째 신규 셀 시 해당 측 quarantine, 20 tick rest/sliding no Q, World 인접 2블록 연속 접촉 no rearm, 2회 실제 분리→새 episode Q 한 번, owner split/unload/reboot no stale credit, `F=NaN`, id overflow, queue overflow Q=0, side conservation.

## 23. 개발용 수치 프로필·성능 상한과 정책 세대 (1.0.7 우선, 1.0.6 이력 병기)

### 23.1 1.0.7 교정된 **현재 개발용** 참고 프로필 (아래 과거 1.0.6 표보다 우선)

| 기능/상수 | 값 | 단위·상태 |
|---|---:|---|
| `newImpact forceEnter`, `G(F) forceFloor`, `forceGain` | `2.0`, `2.0`, `0.161` | 0.4.0colFix2 확인값/DEV; Sable SI force 아님 |
| `maxGroupQ` / A,B side cap | `65536`; side 0.5 each | 원인당 한도; target마다 65536 중복 사용 금지 |
| `MAX_NODES_PER_SIDE` / native / unknown depth | `128` / `9` / `2` | `ForcePropagation` 개발값; 물리 법칙 아님 |
| direction cone / first oblique move | `perpendicular²≤0.36×forward²`, first `parallel≥0.5` | 검증된 native normal에서만, 뒤쪽 금지 |
| native normal AB / pose / manifold | `≤−0.98` / `|dot|≥0.995` / `≥0.98` | **방향 품질 테스트용** threshold, 실제 exact contact/impulse 증명 아님 |
| F/Q elasticity `t(E)` | `0.15+0.85×(1−E/100)^1.5` | F 후속 전달/실제 부모 선행 Q/자기 Q는 **각자 다른 대상 계정**; 겹친 금액 중복 감쇠 불가 |
| `direct/plastic/stress` | `0.60/0.15/0.25` | 자기 노드 `Q_active` 분배, 미활성 몫은 unused |
| `hardnessFactor` | `0.08+0.92×(1−H/255)^1.5` | 0.4.0colFix2 개발식; H255도 불괴 아님 |
| `damageGain`, `fixedPointScale` | `2.6`, `256` | 현행 preview 관측값; 옛 1.0.6 §24.1의 1.0은 역사적 후보 |
| `slime` 물성/임계 | H0, D80, E100, **T≥64** | 현행 코드의 직접 보정. 첨부 CSV T19와 불일치하여 확정 밸런스 아님. 소스별 차이를 별도 검증/로그해야 함 |
| 원래 원장 R2, clearance | 최대 32 target/측; 64 regions; 8192 World cells | 서로 다른 기준/한도, 각각 고유 사유 |
| `stressDepth`, `stressMaxNodes` | `4`, `128` | 실제 Q-stress 별도 미래/기능 게이트에서만 |

**이 표는 물리 실측·사용자 영구 승인값이 아니라 소스와 v0.2 설계의 일치성 검사 프로필이다.** 새로운 자료·튜닝으로 다른 계수를 쓸 수 있으나 `algorithmRevision`/`materialGeneration`/회귀 테스트를 갱신한다. 아래 기존 §23 `0.25/64`, `damageGain=1.0` 등은 이전 릴리스의 이력이며 혼합 사용 금지.


**DEV_PROVISIONAL**: 아래는 기능 확인을 위한 임시 게임 계수다. Sable의 원본 솔버 단위가 아니다. 동작 중 policy 변경은 신생 episode부터 적용하며, 이미 발행한 Credit/MaterialSnapshot/작업 결과를 소급 재계산하지 않는다.

| 도입 버전 | 키 | 1.0.6 기준값 | 구분 및 정책 |
|---|---|---|---|
| 0.3.0+ | `forceEnter`, `forceFloor`, `forceGain`, `maxGroupQ` | `2.0`, `2.0`, `0.25`, `64` | 현행 QFix 후보식, 게임 단위. 실제 Native impulse 아님 |
| 0.3.1 | `rearmSeparatedFrames` | `2` | 실제 독립 긍정 분리 2회; silence 불가 |
| 0.3.1 | `maxNativeCellsPerSide`, `multiTargetPerSide`, `maxRegions` | `32`, `32`, `64` | 확인된 코드 정책, 별도 군집 알고리즘 불필요 |
| 0.3.1 | `shareA`,`shareB` | `0.5`,`0.5` | 실패 측 잔액은 unused, 성공 측 Q 증액 금지 |
| 0.3.1QFix | `clearanceMaxCells` | `8192` | **현행 hard bound**. 8193+ UNVERIFIABLE, CLEAR 아님 |
| 0.3.1QFix | `clearanceChecksPerPairTick`, `clearanceIncrementalGlobal`, `clearanceAtomicGlobal` | `256`, `1024`, `8192` | 타 tick 스캔은 후보, 같은 tick 최종 전체 증명 필수 |
| 이후 성능 개정 | `safeEmptySectionSkip`, `collisionOccupancyProof` | `OFF`, `OFF` | 새 API 검증·동일 tick 증명·성능 차이 확인 시에만 enable |
| 0.4.0 | `fMin`, `gamma`, `rMin`, `beta`, `yieldQ`, `fixedPointScale` | `0.08`,`1.5`,`0.15`,`1.5`,`8`,`256` | H/E 곡선, 선택 정책. 기존 §7의 안전 범위 준수 |
| 0.4.0 | `directShare`, `plasticShare`, `stressShare` | `0.60`, `0.15`, `0.25` | 합=1. 비활성 분기 몫은 unused, 자동 direct 전용 금지 |
| 0.4.1 | `worldBreakCapPerTick`, `dropMode` | `64`, `VANILLA_IF_ALLOWED` | 커밋·저장 안전 기반 선행, claims/BE veto 우선 |
| 0.5.0 | `maxSparseEntriesPerChunk`, `saveDirtyBatch` | `4096`, `128` | 보류/압력 관리, 무음 유실 금지 |
| 0.5.1 | `plotMutationCapPerTick`, `plotBEHandling` | `32`, `VETO_UNSAFE` | split 기본 격리 포함 |
| 0.6.0 | `stressDepth`, `stressMaxNodes`, `stressMinQ`, `stressMaxNodesPerTick` | `4`,`128`,`1/256`,`512` | node 1회 유효 처리, 순환 잔액 정산 |
| 0.6.1 | `splitTransferCap`, `orphanStatePolicy` | `4096`,`QUARANTINE` | 검증된 lineage일 때만 이동 |
| 0.7.0 | `worldMoveCap`, `deformSingleBlockMaxStep` | `16`,`1 block` | 방향 미검증·충돌·claims·BE이면 이동 금지 |
| 0.7.1 | `plotMoveCap`, `unsafeMechanicalPolicy` | `8`,`VETO_UNSAFE` | verified owner local move + Sable lifecycle |
| 0.8.0 | `crackFadeTicks`, `visualUpdateCap` | `200`,`256/tick` | damage는 회복하지 않고 표시만 fade |
| 0.8.1 | `explosionResidualGain`,`projectileHealthGain`,`entityHitGain` | `0.25`,`1.0`,`0.5` | 새 cause 기준, 원본 vanilla 결과 후 생존 target만 |
| 0.9.0 | `maxObservedRecords`,`maxQueuedCommit`,`p95OverheadAlert` | `2048`,`1024`, 기존 1.0.5 성능 경보 프로필 | source 상한과 Sable 100-cap을 혼동하지 않음 |

**수치 확인 순서:** 각 버전에서 `정확성·안전성 PASS` 후에만 성능/자연스러움 튜닝을 수행한다. `NOT_RUN`과 `FAIL`을 수치 조정으로 감추지 않는다. 무효한 단위/overflow/owner 변경은 설정으로 우회 불가. 설정 키 이름은 **설계 명칭**이며 실제 `pdd.realfix.*` 시스템 프로퍼티와 자동으로 동일하다고 가정하지 않는다. 실제 CLI/서버 config는 구현 시 기존 키와 호환되게 연결하고 문서화한다.

## 24. 재료·손상·저장·응력·변형의 실제 처리 (1.0.7 통합)

### 24.0 1.0.7 재료·F-first·DamageModel의 단일 계약

- `MaterialRegistry` 정의별/상태별 불변 캐시 입력은 외부 CSV/호환 JSON → JAR 블록·아이템 CSV → 미정 필드만 추론 → 명시 기본값 (§7.2.1~2). 이것을 실제 **각 좌표의 희소 손상** 데이터와 혼합하지 않는다.
- DamageModel은 **`Q_target = F-first에서 노드별로 변환·캡된 최종 Q_i`**만 받는다. 과거 Q 균등분배 입력을 가정하거나 받은 F를 여기서 다시 다른 블록으로 전파하지 않는다.
- child E에 의한 **부모의 미확정 Q 경로 감쇠**, parent E에 의한 **후속 F 감쇠**, `DamageModel`의 **해당 노드 자기 Q E 1회**는 분리 계정이다. 같은 Q 금액에 E를 임의 두 번 적용하거나 형제 경로 전체 평균으로 희석하지 않는다.
- `Q_active=Q_target×t(E_self)` 이후 `Q_direct=0.60Q_active`, `Q_plastic=0.15Q_active`, `Q_stress=0.25Q_active`, `rawDamage=Q_direct×damageGain×hardnessFactor(H)`, `damageFixed=floor(rawDamage×256)`을 기본 구현 검증 프로필로 둔다. 현재 source `damageGain=2.6`과 일치하도록 단위 테스트한다. 특정 모드 버전 전용 식이 아니라 정책 세대로 관리한다.
- `slime_block`: 첨부 CSV `T=19` vs 소스 v0.2 보정 `T≥64`는 **실제 데이터 충돌**이다. 신규 JAR 표 채택 시 외부 사용자 override > JAR(보정 반영 여부를 출처와 함께 결정) > 추론 > 기본값으로 일관되게 해석하고, 명시 JAR값을 코드 앵커가 몰래 덮지 않도록 한다. 당장의 migration 기본 보정 `T≥64`를 유지하려면 **내장 데이터에 T64 이상을 명시하고 versioned balance diff로 남긴다**; 외부 CSV의 명시 T는 유효범위·안전정책을 만족하면 override할 수 있다. T19가 그 자체로 물리적으로 틀렸다고 주장하지 않는다.


### 24.1 공통 재료 모델: H/T/D/E, 1회 탄성, 정수 회계 — 아래 과거 구현 기술은 당시 이력

**1.0.6의 과거 구현 판독 기록(최신 상태 아님):** 과거 초기 PDD 버전에서는 `MaterialProperties`에 H/D/E만 존재하고 `DamageModel`이 선형·`ceil`을 사용했다. 그러나 **0.4.0colFix2 고정 비교 SHA에서** `MaterialProperties`는 H/T/D/E 및 별도 항복·불괴, `DamageModel`은 H/E 비선형 곡선·`fixedPointScale=256` 계산을 갖췄다. 여전히 이 코드는 **Preview 계산**이고 실제 World/Plot 커밋의 검증이 아니다. 1.0.7에서 새 물성 CSV/ItemSnapshot은 **미구현**(§29).

**수정할 최종 의미**:

| 속성 | 자료/수명 | 알고리즘 의미 |
|---|---|---|
| `H` | 0..255, 정의별 또는 관련 BlockState별 캐시 | 직접 손상의 비선형 저항, 무조건 불괴를 뜻하지 않음 |
| `T` | 1..255 + 별도 unbreakable flag | 누적 피해 임계값. H와 동일 변수가 아님 |
| `D` | 0..100, percentage byte | 항복 뒤 허용되는 영구 변형 capacity; 보호/기계 정책과 독립 |
| `E` | 0..100, percentage byte | **해당 노드에 입장한 Q에 1회만** 게임 충격 완충. 후속 Direct/Plastic/Stress 재적용 금지 |
| `damage`, `deformation`, `crackVisual` | each u8, changed blocks only | 영속 손상/변형, 시각 표시 분리 |
| `remainder` | 0..255, optional per changed block | fixed-point 1/256 누적 정확도 보전 |

**정의별 캐싱**: 서버의 block registry/tag/레시피/명시 프로필/user override를 시작/데이터팩 갱신 때만 분석한다. 동일 레시피 여러 개는 안정적인 priority+ID로 정한다. `external CSV + legacy JSON > bundled JAR explicit > inference(profile/recipes/tags) > fallback` 정적 원천 순위. DVT dynamic modifier와 BlockState/보호 정책은 별도 층에서 적용하며, 외부 명시값과 충돌은 §7.2.2에 따라 기록한다. 실제 active BlockState에 따라 재료가 바뀌는 경우 **필요한 속성만** cache key에 포함한다. 전체 설치 블록의 레시피 역추론 금지. `MaterialSnapshot`은 material generation, 계산 revision과 함께 승인 사건에 묶어 이후 reload 때문에 이미 승인된 계산이 달라지지 않도록 한다.

**한정 예산 식 (개발 기본값):**

```text
Q_target = capped final per-block Q_i AFTER F-first propagation and G(F_i); finite and >= 0
E01 = clamp(E/100,0,1)
elasticTransmission = rMin + (1-rMin)*(1-E01)^beta # rMin=0.15, beta=1.5
Q_active = Q_target * elasticTransmission          # ELASTIC EXACTLY ONCE
Q_absorbed = Q_target - Q_active
Q_direct = 0.60 * Q_active
Q_plastic = 0.15 * Q_active
Q_stress = Q_active - Q_direct - Q_plastic          # remaining 0.25, deterministic
hardnessFactor = 0.08 + 0.92 * (1-H/255)^1.5
rawDamage = Q_direct * damageGain * hardnessFactor # current DEV reference=2.6; revisioned policy
incrementFixed = floor(max(0,rawDamage) * 256)
newTotalFixed = min(255*256+255, oldDamage*256 + oldRemainder + incrementFixed)
newDamage = floor(newTotalFixed/256)
newRemainder = newTotalFixed mod 256
```

`newTotalFixed`는 0..(255*256+255) 범위에서 포화한다. `newDamage >= T`일 때 보호·불괴·BE 정책을 통과한 경우에만 `DestroyIntent`가 생성된다. 파괴가 veto되면 상태 기록과 파괴 결과를 별도로 처리하며, 피해 상태의 값 범위를 벗어나게 하지 않는다. T는 불괴 플래그와 별개다. 위 Q 회계와 1byte 손상은 보존해야 하지만 재료 response는 게임 모델이지 실제 에너지 보존 공식이 아니다. 모든 중간 계산은 finite/range/saturation 검사. **정수 fixed-point 예산 단위로 보정이 필요한 부분은 결정론적 나머지 분배**를 쓴다. 0<rawDamage<1에 매번 정수 피해 1을 부여하는 `ceil` 금지.

- 분할된 Q 중 `plastic/stress` 경로가 아직 없는 버전은 해당 몫을 `UNUSED_PLANNED`로 정산한다. 0.6.0/0.7.0 출시 후 **과거 미적용 금액을 소급 계산하지 않는다**.
- 연성 `capacity=round(255*D/100)`, 개발용 `yieldQ=max(0,8*(1-D/200))`을 적용한다. 재료별 항복 임계 override가 있으면 그것을 우선한다. `plasticIncrement=min(capacity-currentDeformation,floor(max(0,Q_plastic-yieldQ)*deformGain))`이며 `deformGain=1.0`은 임시값이다. 소성 이동이 실제로 불가능하면 일회성 가상 deformation 영속값을 성공으로 저장하지 않는다.
- property `E`를 `DamageModel.neighborStress`에서 매번 별도 차감하면 이미 `Q_active` 계산에 적용한 탄성이 중복될 수 있다. Stress graph는 **새 이웃 노드의 E**에만 한 번 적용한다.
- source quality가 빈약하면 H/T/D/E를 아무리 정확히 알아도 Q를 생성하지 않는다.

### 24.2 발행 Credit, 계획, 실제 적용 결과를 분리

**필수 2계층**: (1) `ContactCreditLedger`의 불변 `CreditId/Grant`, (2) **미구현 설계**인 bounded `ApplyId/ApplyReceipt`와 실제 세계·블록 변경 결과. 이미 정의된 `CreditId`, `TargetKey`, `BlockDamageState`는 재사용하고 동일 내용을 새 Source Factory/Manager에 중복하지 않는다.

```text
CreditId: serverRun, dimension, stableOwnerPair+generations, episodeGeneration,
          sourceKind or explicitly independent causeGroup
ApplyId: CreditId + target(owner space/id/local position/fingerprint) + actionKind
Plan: target's Q share, materialGeneration, algorithmRevision,
      plannedDamage/Move/Break/Stress, expectedOwnerGeneration/BlockState
Receipt: PREPARED | DEFERRED | APPLIED | ABORTED | PARTIAL_QUARANTINED,
         exact reason + last validated state + bounded retry metadata
```

**계약**: Credit를 발행한 뒤 실패한 Commit 때문에 Credit를 다시 발행하지 않는다. `APPLIED`인 ApplyId가 중복 접근되면 재실행하지 않는다. `DEFERRED`는 유한 시간 내에 같은 소유자/프린트/결정론적 계획만 재검증한다. 시점이 달라 안전증거가 소멸하면 `ABORTED`/quarantine으로 회계 마감한다. `unused`는 future Q reserve가 아니다.

**원자성 제한:** Minecraft 다중 블록 mutation, SavedData, 엔티티 드롭이 하나의 ACID 트랜잭션이라고 가정하면 안 된다. 한 블록의 안전 변경을 우선한다. 실패한 다중 작업에서 무조건 '원상복구 성공'으로 표시하지 말고 `PARTIAL_QUARANTINED`를 유지한다. 서버 비정상 종료 시 저장된 Receipt와 실제 BlockState/BE를 **재대조**한 뒤 가짜 재파괴·드롭을 차단한다. 재시작 전체 상황에서 정확히 한 번 드롭을 보장하려면 별도 영속 저널 및 게임 API 실제 부작용 경로 검증이 필요하며 **검증 전 보장 문구 사용 금지**.

### 24.3 0.4.1 World Commit과 0.5.0 영속성 선후관계

0.4.0에서 `SparseStateKey` 및 serializer/validation 순수 테스트를 구현한다. 0.4.1에서 실제 World 파괴를 사용하기 전에 **최소 운영 재시작 복원·정상 저장·중복 적용 방지**를 연결한다. 0.5.0에서는 원래 요구된 chunk sharding, dirty batch, 값 범위, 블록 교체 검증, schema migration, cap/backpressure, startup repair/구조적 테스트를 **모두 완성**한다.

```text
WorldCommit:
  server thread + already loaded chunk + dimension match + no unsafe physics phase
  re-read owner generation + expected registry/state fingerprint + BE identity
  protection/claims, unbreakable, event cancellation, unsafe multiblock veto
  assign same ApplyId; prepare safe state/receipt
  use ServerLevel supported block APIs, preserve vanilla lifecycle/drop context
  re-read block and commit result (never trust returned boolean alone)
  save damage/receipt dirty shard; request client delta only on applied change
  if failure: bounded retry with same ID and fresh CAS OR explicit abort
```

- 실제 블록 파괴와 드롭은 기존 Sable/vanilla 로직을 덮어쓰지 않는다. callback 안에서 변경 금지.
- `StateKey=(dimension, world/PlotUUID, ownerGeneration, localBlockPos, fingerprint)`을 논리 식별자로 사용한다. 영속 파일의 실제 키 인코딩은 안정한 버전/포맷과 검증된 블록 replacement policy에 따라 정한다. runtimeBody ID/Java `hashCode`를 영속 ID로 쓰지 않는다.
- `damage,deformation,remainder`는 실제로 변경된 블록에만 보관. 표시 전용 `crackVisual`을 손상 원본값의 대용으로 삼지 않는다. zero-state는 저장하지 않는다. Load는 bounds+CRC/schema/fingerprint·owner 검증 후에만 적용한다.
- 청크 unload/서버 재시작/블록 교체 시 **오래된 손상을 다른 새 블록에 적용하지 않는다**. 포화되면 상태를 소리 없이 누락하지 않고 bounded queue/backpressure·거절 이유를 남긴다.

### 24.4 0.5.1 Plot 실제 변경과 0.6.1 split 상태 이전

```text
PlotCommit:
  verify stable Plot UUID + dimension + ownerGeneration + loaded embedded accessor
  convert native storage -> embedded BlockPos exactly once
  revalidate block fingerprint/BE/claims + Sable mutation phase
  use actual EmbeddedPlotLevelAccessor API, verify returned state/side effects
  check mass/collider/split/glue lifecycle via Sable-supported mechanism
  owner/split changed -> quarantine old sparse states, invalidate stale caches
  only after verified old/new block mapping -> migrate damage/remainder/BE state
```

- **Sable JAR 2.0.6에서 실제 확인:** `EmbeddedPlotLevelAccessor.setBlock(BlockPos,BlockState,int,int)`, `removeBlock(BlockPos,boolean)`, `destroyBlock(BlockPos,boolean,Entity,int)`, `getBlockState(BlockPos)`; `ServerSubLevel.getPlot`, `getSplitFromSubLevel`, `getSplitFromPose`. **클래스·서명 존재만** 확인됐고 해당 호출의 모든 mass/collider/BE/drop 부작용은 아직 미검증이다.
- 0.5.1 필수 최소 기능: Plot 변경과 동시에 owner generation/분리 발생을 **탐지하고**, 실제 옮겨진 블록을 확실히 알 수 없다면 이전 소유자의 피해 레코드를 `ORPHAN_QUARANTINE` 처리한다. 0.6.1의 완전 이전까지 ghost damage의 타 Plot 복제를 금지한다.
- 0.6.1 기능: Sable의 실제 split/merge/reassembly 결과, moved block 좌표 대응, glue/BE 연결 상태를 확인해서 `oldOwner, oldPos -> newOwner, newPos`로 정확히 이동한다. 단순 Plot UUID, 세계 좌표 근접, `getSplitFromPose` 하나로 동일 블록 판정 금지.
- 월드 변경이 일부 성공하고 split이 진행 중인 동안은 미확정 상태를 다른 object에 적용하지 않는다. 지원하지 않는 다중 기계/BE는 좁은 veto 및 실패 근거를 출력한다.

### 24.5 0.6.0 bounded Stress: 순환·합류 결정론

1. 검증된 손상 타깃의 **동일 owner-local** 6면 이웃만 탐색한다. 다른 Plot/World로 좌표만 같다고 전파하지 않는다.
2. 각 `frontier[depth]`에 들어온 동일 `target` 예산은 **해당 depth에서 전부 합산**한다. 그 노드는 그 episode에 **처음 만난 최소 depth에서 한 번만** 처리한다. 이전 depth에서 이미 처리했다면 늦게 유입된 몫은 `LOOP_OR_LATE_UNUSED`로 계정 마감한다.
3. 해당 node에 처음 도착한 Q에서 **그 node의 E만 한 번** 적용한다. Direct/Plastic/Stress로 분기하고 Stress만 이웃에게 전송한다. 이미 Direct 또는 Plastic으로 분배한 Q를 다시 Stress에 합산하지 않는다.
4. 검증된 owner-local normal이 있으면 인접면 가중치를 쓰고, 없다면 6면 중 유효 연결 이웃에 균등 분배한다. 불가한 이웃 몫을 다른 곳에 임의 증액하지 않는다.
5. `depth<=4`, `uniqueNodes<=128`, `perTick<=512`, `minQ>=1/256` 기본. 상한/미로드/실패는 `DEFERRED_BOUNDED` 후 TTL 경과 시 명시적 `UNUSED`. 새로운 원인/episode 발행 금지.
6. 재료/블록 상태가 traversal 중 변경되면 그 node의 계획을 버리고 재검증한다. 시작 Credit 이후 source 에너지를 다시 읽거나 계산하지 않는다.

**의도적 단순화:** 동일 node를 여러 depth에서 반복 처리하는 물리적 다중파 확산은 1.0.0 기본 목표가 아니다. 한 번 처리 정책은 연결성의 모든 경로를 모델링하지 못할 수 있지만, 순환 에너지 증폭·서버 폭주를 예방한다. 필요성이 실제 재현되면 추가 모델을 별도 revision에서 비교한다.

### 24.6 0.7.0 / 0.7.1 변형: 방향 증거는 0.4.0부터 선택적으로 보관

- `DirectionEvidence=(owner frame, normal source, source tick/substep quality, transformEpoch, signedOrientation)`는 **설계 타입**이다. 기존 0.3.1QFix에 유효한 direction이 항상 존재하지 않는다. 0.4.0부터 optional slot을 두어 검증된 사건에서만 채운다. 데이터가 없는 과거 사건은 UNKNOWN.
- 유효한 A/B witness local normal 값이 있다고 `verifiedDirection`까지 승인하는 것은 아니다. native substep/COM 변화·스케일·부호/좌표계, 실제 충격받는 블록 방향을 검증한다. 부족하면 **Direct damage는 허용되어도 격자 이동은 금지**한다.
- `DeformationIntent=(ownerLocalBlock, targetDirection, oneBlockMaxStep, plasticBudget, ownerGeneration, stateFingerprint)`. 이동 후보가 air/replaceable, 같은 owner, loaded, claims·BE·기계 연결·gravity/조립 glue 안전한 경우에만 preflight 통과. 다중블록/BE 미지원이면 veto, 자동 파괴 치환 금지.
- World는 실제 ServerLevel 변경 및 이웃 갱신; Plot은 embedded 좌표와 Sable mass/collider/glue lifecycle를 사용한다. 실패 시 손상 증거·실제 변형 성공 표기를 분리하며 구조물 일부만 이동한 상태를 성공으로 간주하지 않는다.
- 변형 계획의 `deformationU8`은 실제 격자 이동 성공 전에는 물리적 이동 완료 상태로 영속화하지 않는다. 미지원/실패 plastic 예산을 **unused 또는 명확한 잠정 상태**로 기록한다. 이 값을 최종 damage로 다시 바꾸지 않는다.

### 24.7 0.8.0 시각화 / 0.8.1 외부 입력 및 DVT 재사용

- 균열 표시 시각화는 owner-local 손상 상태에서 파생된 **dirty target/client tracking delta**만 전송한다. `crackFadeTicks=200` 후 화면 표시만 회복, 실제 damage/remainder 영속값 유지. 매 tick 모든 블록 스캔 금지; 변경 및 만료 시점에 갱신한다.
- 폭발: vanilla/원본 Aeronautics 결과 우선, 원래 파괴되지 않고 생존한 블록에만 PDD residual Q 계획. 투사체/엔티티는 서버 권위의 실재 충돌/피해만 입력으로 허용한다. 별도 `causeId`, 공통 `parentCauseGroup`으로 재귀 중복을 차단한다.
- DVT는 같은 `DamageSource -> bounded Credit -> Material -> SafeCommit` 경로에 연결한다. `PROJECTILE -> EXPLOSION -> CONTACT`의 실제 인과 관계와 독립 타격을 구분하지 않은 무차별 de-dup 또는 무조건 중복 발행 모두 금지한다.

## 25. PDD/DVT 범용 API와 변경 비용 최소화 원칙 (1.0.7)

### 25.4 미래 API의 역호환과 지연 승격 조건

- 외부 Sable/Create API 직접 참조는 **실제 고정 JAR와 compile/test/runtime/게임 classpath 확인** 후에만 추가한다. 기존 `Rapier3D.clearCollisions()` 중복 호출, 원본 콜백 `null`→non-null 변경, 잘못된 `World ↔ Plot` 번역을 숨기는 wrapper는 금지.
- DVT가 재료 값을 요구할 때 static `blockItemMaterialSnapshot`을 조회하고, 실제 변경 상태는 별도 `DynamicMaterialModifier` 계약으로 전달한다. 별도의 DVT용 독립 H/E 피해 엔진은 만들지 않는다.
- 필요가 증명되지 않은 Adapter/Factory/Policy 계층은 구현하지 않는다. `MaterialRegistry`, `DamageModel`, `ForcePropagation`, `ContactCreditLedger`를 우선 확장한다. ABI 차이가 실제로 발생한 모드 버전에 한해서 source capability adapter를 추가한다.


**아래는 내부 애드온 설계 타입**이다. 현행 Sable/PDD JAR의 실제 클래스 또는 이미 구현된 공개 인터페이스가 아니다. 실제 구현에서 Java record와 기존 `ContactCreditLedger` 타입을 재사용할 수 있으면 새 interface/manager를 만들지 않는다.

```java
record DamageSource(String kind, UUID causeId, UUID parentCauseGroup,
                    String dimension, String quality, long serverRunEpoch) {}
record TargetRef(String dimension, String ownerSpace, UUID ownerId,
                 long ownerGeneration, long packedOwnerLocalBlock, String fingerprint) {}
record MaterialSnapshot(int hardnessU8, int thresholdU8, int ductilityPctU8,
                        int elasticityPctU8, long materialGeneration, boolean unbreakable) {}
record Credit(UUID creditId, DamageSource source, double totalGameQ, String algorithmRevision) {}
record ApplyId(UUID creditId, TargetRef target, String actionKind) {}
// DirectionEvidence, SparseDamageState, ApplyReceipt are OPTIONAL downstream types.
// All classes/signatures above are design pseudocode, NOT verified external APIs.
```

### 25.1 공통 API 적용 경계

- **Source 계층**: 실제 충돌·폭발·투사체·기관 고장 각각의 증거, 원인 ID, 품질 등급을 생성한다. 소스별 계산식을 하나의 giant switch에 몰아 넣지 말고 범용 Credit와 실제 어댑터만 공유한다.
- **Credit 계층**: 승인된 사건만 한 번 예산 발행. 동일 `parentCauseGroup`이라도 독립 실제 사건의 수치 처리 정책은 명시적으로 결정한다. `sourceKind`와 `physicalUnitVerified`의 혼용 금지.
- **Material/Planner 계층**: 정의 캐시·고정소수점·탄성 1회·안전 예산 분할을 재사용한다. DVT 전용 DamageModel을 새로 만들지 않는다.
- **Commit 계층**: World와 Plot의 실제 좌표 공간 및 라이프사이클만 분기한다. 상태 저장/피해·드롭 중복 정책은 공통이다.
- **External class loading**: PDD 단독 실행에 DVT JAR를 요구하지 않는다. 외부 DVT API를 static init에서 직접 참조하지 않고 설치·실제 클래스패스 확인 뒤 좁은 adapter를 활성화한다.

### 25.2 DVT 단계별 구현

- **DVT 1.0 Fracture**: 검증된 파쇄 원인을 공통 Credit에 연결. `Fracture`는 독립 손상/파편 엔진이 아니라 PDD의 `Stress + SafeCommit + Sable split`을 재사용한다. 각 파편 lineage 없이 저장 상태를 자동 복사하지 않는다.
- **DVT 2.0 Big Bang**: 실제 엔진·연료·폭발 원인 확인 후 유일 causeId 발행, `Q_engine=clamp(strength*gain,0,cap)` 게임 변환 사용. 재귀 연쇄 최대 hop/visited engine 및 동일 원인 그룹 중복 처리 제약. 원본 폭발 이벤트와 추가 PDD damage는 후처리 결과에만 접근한다.
- **DVT 3.0 Reinforce**: `USER_OVERRIDE > EXPLICIT_BLOCK > DVT_STATE_MODIFIER > MATERIAL_PROFILE > INFERRED`의 우선순위로 Snapshot을 생성한다. `MaterialRegistry` 자체를 per-impact 재빌드하지 않고 변경된 material generation 또는 sparse modifier만 반영한다. 부식/습윤은 실제 필요한 위치만 상태로 보관한다.

### 25.3 API/복잡성 승격 조건

새 외부 의존성·새 JNI ABI·네이티브 solver 복제를 기본 해결책으로 채택하지 않는다. `누락된 source data를 실제로 확인 → 기존 Sable Java API/원본 수명주기 이용 가능성 조사 → 국소 Mixin/read-only adapter → 공식 소스 수정 또는 native ABI` 순서로 검토한다. API 부재가 전체 PDD 빌드를 차단하지는 않지만 해당 기능의 정확도·안전성 **게이트**는 미통과로 기록한다. 최적화 매니저도 profiler로 병목이 확인된 경우에만 도입한다.

## 26. 각 버전의 구현 완료, 설계 충족, 실제 검증 게이트 (1.0.7)

### 26.0 버전과 독립적인 승격 게이트

**규범:** 릴리스 이름이 `0.4.x`, `1.0.x` 또는 DVT여도 §31의 동일 `CAP-...` 게이트를 적용한다. **F-first Preview 통과 ≠ World 실제 파괴 통과 ≠ stress Q 실제 피해 ≠ 저장/재접속 검증**. 테스트 결과 상태는 `PASS/FAIL/NOT_RUN/BLOCKED/INCONCLUSIVE`로 구분하며 JAR 빌드와 인게임을 분리한다. 새로운 릴리스의 테스트는 변경된 코드 경로와 관련 회귀에 집중하되 기존 JUnit을 삭제·skip해 성공 처리하지 않는다.


'설계 개정 완료'는 코드를 빌드했다는 뜻이 아니다. 구현 전·후를 독립 보고한다. 기존 JUnit 삭제/비활성화 금지. 각 기능에 `algorithmRevision`, input evidence, policy revision, owner generation, rejection reason을 남긴다.

| 버전 | 핵심 실제 구현 검증 | 신규 회귀·실게임 검증 |
|---|---|---|
| 0.3.1QFix | verified separation 2회, region ≤32/side, Q once, World clearance 원자적 증명 | 8192/8193 grid, 32/33 unique cells, 1-world-block false rearm, load/owner change/100 cap |
| 0.3.1 후속 개선 | section skip의 비충돌 증명 및 QFix 동일 안전조건, profiler 상 이득 | air section stale/BE collision/async edit/partial chunk 대형 Plot 2회 재충격 |
| 0.4.0 | registry H/T/D/E, non-duplicate Credit, `floor*256` remainder, E once, serializer 기본 | monotonically resistant H, E once, fine Q aggregation, reload generation, disabled feature unused |
| 0.4.1 | 실제 World commit, 최소 reload-safe sparse state, at-most-once applyId 결과, vanilla drop/BE veto | 1개 stone break once, restart between prepare/apply, block changed abort, claims protection |
| 0.5.0 | chunk shard restore/replace/migration, state budget, dirty batching | chunk unload/reload, data corruption, >4096 backpressure, material generation mismatch |
| 0.5.1 | Plot native accessor commit, split quarantine, Sable mass/collider lifecycle 확인 | 회전 Plot block changed + 동일 worldXYZ 불변, split 직후 ghost state 없음, BE veto |
| 0.6.0 | bounded stress node once, explicit unused, no re-elastic from same node | diamond DAG 합류, 1->2->1 고리, cross-owner 차단, max-node TTL |
| 0.6.1 | official Sable block lineage validated migration + glue/BE preservation | 두 파편의 서로 다른 damage, merge/reassembly 오염 방지, stale epoch |
| 0.7.0 | known direction & free grid 1칸 World 이동, unknown veto | 정상 1칸 이동, tie-axis, blocked destination, claims/BE safe abort |
| 0.7.1 | Plot local deformation and lifecycle, no partial successful status | rotate 90°, glue, BE, collider/mass and split/inertia consistency |
| 0.8.0 | dirty delta / fade-only visual / tracking | two clients, reconnect, damage remains after visual fade, empty updates O(0) |
| 0.8.1 | external source cause group + vanilla original behavior preserved | projectile/vanilla explosion survivor, repeated callback no extra damage |
| 0.9.0 | P50/P95/P99, allocations, Native saturation/clearance hot path, optimizer optional | 10+ player / large ships / same settings OFF/ON, CPU vs sample coverage |
| 0.9.1 | total callback contract, server-client, plot+world, persistence, crash/rollback | 2-session integrated checklist, full negative cases, Actions build/JUnit |
| 1.0.0 | stable schema/policy, dependency alignment, full-source ZIP and built JAR | actual clean test+build, Actions, installation, in-game tested scope |
| DVT1~3 | PDD single ledger/Material/Commit, optional extension loading | duplicate causes, chain cap, material overlay generation, PDD without DVT |

**검증 라벨:** `PASS`, `FAIL`, `NOT_RUN`, `BLOCKED`, `INCONCLUSIVE`. 정적 Java 검토·JAR의 메서드 선언 확인과 `compileJava`, `compileTestJava`, 실제 JUnit/Gradle, Actions, 인게임 기능을 반드시 별도 표기한다. Java 독립 소스 컴파일 또는 stub runner 통과는 공식 Minecraft 모드 빌드 통과의 대용이 아니다.

## 27. 인게임 2회 제한과 필수 로그 — 새 버전마다 축적하는 시험 체계

### 27.1 1.0.7 인게임 두 세션의 통합 증거 수집

- **세션 A — 원인/F/Q/물성:** World↔Plot, Plot↔Plot(가능하면 회전/사선) 최초 충돌·정지·실제 분리 후 재충돌을 한 세션에서 재현. `[PDD-NATIVE-NORMAL]`, `[PDD-F-PROPAGATION]`, `[PDD-F-LEDGER]`, material snapshot generation/source, split Q, `unresolvedF`, `Q_unused`, `worldMutation`을 한 번에 수집. 콜백 변경/중복 원인/예산 초과/미로드 정상 종단 허위 승인 0이어야 한다. CSV 사용자값 시험은 정의별/설정별 값·출처 로그 비교로 병행한다.
- **세션 B — 실제 커밋/영속성(해당 능력 활성 릴리스만):** 승인된 일반 World/Plot 블록의 실제 손상·파괴·드롭·BE/claim veto, 저장 재시작·복원, Sable collider split/owner 이전, Q-stress와 변형·시각화·폭발 원인 중 그 릴리스가 지원하는 기능을 한 세션에 결합. 지원하지 않은 기능을 FAIL이 아니라 **NOT_IMPLEMENTED (not release scope)** 로 기록한다. 릴리스 범위에 포함된 미구현 기능을 NOT_IMPLEMENTED로 면책하지 않는다.
- 한정 FPS/성능과 부정사유도 동시에 수집한다. 최초 타격·중복·세대/중복 메시지·실제 APPLIED/ABORTED를 구별한다. 두 세션만으로 모든 물리 상호작용을 증명했다고 주장하지 않는다.


### 27.0 최대 두 세션으로 검증 가능한 기능 범위 (1.0.6, 규범)

**세션 A (World/Plot·저장)**: 정확한 server/mod 버전, stable Plot UUID, 작은 Plot(전체 AABB≤8192 cells)로 연속 접촉→완전 분리 2회→재충격을 시험한다. 큰 Plot에서 `TOO_BROAD`가 나오면 safety limitation으로 구분한다. 버전 0.4.1+에서는 World 손상·파괴·1회 드롭·서버 재시작/블록 교체 및 BE/claim veto 포함. `PDD-WORLD-CLEARANCE`, `PDD-EPISODE`, `PDD-LEDGER`, `PDD-MATERIAL`, `PDD-COMMIT`, `PDD-SAVE`를 구현된 단계에 한해 확인한다.

**세션 B (Plot/Plot·분리/응력·변형·클라이언트)**: 두 UUID 구조물을 충돌·분리·재충격한다. 이후 버전에서 split lineage, 부분 파괴·0.6 BFS 합류, 0.7 direction 검증·격자 이동, 0.8 클라이언트 동기화/외부 사건을 차례로 포함한다. 검증되지 않은 기능 로그가 아직 없는 버전에서 테스트 성공을 주장하지 않는다. 실제 실패 시 전체 `latest.log`, Actions full failed job, SHA, Plot UUID, 충돌 순서, 위치, 해당 버전 설정과 타임스탬프를 받아 분석한다.


**세션 A: 대표적인 수직·병진 충돌 + 같은 자리 지속 접촉**

1. Sable Plot 단일 석재/사슬/보강 블록을 정적 돌벽에 충돌시키고, 각각 독립적으로 바디 ID/UUID 및 `ownerLocal` 실제 블록을 눈으로 확인한다. 그 다음 움직이지 않고 20틱 접촉시켜 중복 credit이 없는지 관측한다. World의 똑같은 XYZ가 Plot 물체로 잘못 수정되지 않아야 한다.
2. `PDD-OBS(source=POST_EFFECT,rawRecords,dim,bodyIds,forceMax,discarded/unknown)`, `PDD-TARGET(ownerGeneration,uuid,frameMethod=R1~R4,target,shapeDistance,reason)`, `PDD-EPISODE(state,previousSeparated,forceRise,creditCount,Q,share,duplicateReason)` 세 로그가 구체적 실제 값을 포함하는지 확인한다. `F=42`일 때 정책 Q=10 예시와 동일 계산인지 단위테스트 결과와 대조한다.
3. 다음 버전의 damage commit이 활성화되면 서버 변경 결과를 별도로 확인하고 original callback 관련 Sable 기능을 비교한다.

**세션 B: 회전/Plot↔Plot/다중 충돌 + split/재시작/외부 source**

1. Plot 두 개의 서로 다른 방향 모서리와 회전 중 구조물끼리 충돌시켜 양쪽 UUID와 실제 서로 다른 블록이 식별되는지 확인한다. 둘 사이의 pose를 분리시킨 뒤 다시 충돌해 **새 사건은 1회**, 지속 접촉에는 0회 credit인지 확인한다.
2. 해당 버전부터 split/BE/변형/저장/균열/폭발·투사체 기능을 시험에 추가한다. 과거부터 존재하는 테스트는 과잉으로 다시 수행할 필요 없으나 **변경 영향 범위의 회귀 테스트는 누락 금지**.
3. 실패 시 장면-이벤트-실제 owner 상태-원장/커밋 ID로 원인을 추적한다. 사용자가 재현할 수 있는 로그 예시/성공 기준을 제공하고 `unresolved`를 `PASS`로 바꾸지 않는다. `native upstream 100 cap`에서 일부 충돌이 사라질 수 있으므로 외부 observer가 `dropped=0`이라도 full coverage 주장 금지.

**최소 구조화된 필드:** `runId,dimension,serverTick,ownerUUID/runtimeId/epoch,originalSource,rawShapeEvidence,frameMethod,actualBlockState,sourceQuality,phase,forcePeak,policyVersion,policyProvisional,gameQ,creditedA/B,reason,worldMutationState,commitOutcome,sourceSha`. 로그는 rate limit·요약 카운터를 모두 포함하며 정상 사건/거절 사건의 대표 예시가 각각 나와야 한다.

## 28. 문제 발생 시 최소 변경·증거 우선 해결 트리 (1.0.7)

### 28.1 F/CSV 장애의 최소 변경 해결 순서

`Q=0`이면 source/신규 충격/owner/R2 → F ledger → Q per-node cap → material snapshot → target eligibility 순서로 확인한다. 이상 F와 impulse 원인 추정부터 하지 않는다. `wide terrain/hang`이면 native cone/unknown depth/128 node/unresolved frontier, `형제 피해 감소`면 parent→child link·가중 E, `과대 피해`면 self E 중복/분기 복제/Receipt 재발행, `물성 값 틀림`이면 CSV/JSON/JAR/추론 source 로그와 material generation, `기존 손상이 사라짐`이면 피해 영속 저장 vs 정적 테이블 분리부터 확인한다. 실패한 기능을 제외하거나 log-only로 바꿔 릴리스를 성공 처리하지 않는다.


**0.3.1QFix 현재 제한:** `CLEAR`가 오지 않으면 먼저 `BLOCKED/TOO_BROAD/UNAVAILABLE/WAIT_ATOMIC_CONFIRM_BUDGET`을 구분한다. 전체 AABB에 다른 World collider가 있으면 현재 안전성 제약에 따라 Q는 보류한다. **새 승인 조건을 느슨하게 해서 해결하지 않는다**. 실제 병목이 확인되면 안전한 section-level fast-path를 시험하고, 더 정밀한 occupancy는 Sable 동일 frame/epoch 증거 확보 후 적용한다.

**타깃 누락:** native source 15필드/owner/COM epoch/R2 per-side shape를 확인한다. A/B world witness residual은 진단만 한다. R3 cross-substep, R4 경계 추정으로 임의 Q 금지. 필요하면 원본 Sable source와 확인된 API로 국소 read-only observer를 확장한다.

**지속 접촉 중 중복 피해 또는 재타격 무효:** scene/owner generation과 `GameImpactCredit` 실제 state·episodeGeneration·연속 CLEAR 2회·verified approach를 먼저 확인. 원본 first approved peak와 과거 burst-max 설계 차이를 혼동하지 않는다. 다른 충격 지역의 독립 원인이 확인되기 전 Pair 전체 rest를 force ratio만으로 rearm하지 않는다.

**물성 피해 비정상:** 동일 원인의 Q 중복, `ceil` 기반 미세손상, 동일 노드 E 2회, material generation, unavailable plastic/stress 몫의 direct 몰아주기를 우선 검사한다. 실측이 아닌 `F_raw`의 forceGain만 조정해 원인을 숨기지 않는다.

**서버 재시작 후 유령 피해/중복 드롭:** `ApplyId`, fingerprint, owner epoch, 실제 World/Plot state, 저장 Receipt를 조사한다. 실제 ACID 보장이 없는 API에서 정확히 한 번 수행을 주장하지 말고 장부·재시작 재대조·격리를 통해 막는다. 결함을 숨기기 위해 저장 기능을 끄거나 damage record를 무음 폐기하지 않는다.

**Plot split/BE/collider 실패:** `EmbeddedPlotLevelAccessor` 실제 API, Sable lifecycle, old/new block lineage를 검증한다. 실패한 주변 기계/BE만 좁게 veto하고, 증명되지 않은 상태 이전을 격리한다. `Rapier3D.changeBlock`/`removeSubLevel`을 추측 호출해 원본 solver를 대체하지 않는다.

**Stress/Deform 이중 적용:** 같은 Credit의 node-depth visited, fixed-point remainder, actual owner-local direction, preflight, retry ID 확인. 비활성 경로의 값을 나중에 자동 재생하지 않는다.

**성능:** 비용이 높은 stage(R2, clearance, material, Commit, logging)를 profiler와 카운터로 분리한다. 이미 존재하는 batch/캐시/제한 설정을 먼저 재사용한다. 새 index가 필요하면 정확도 게이트를 고정한 채 대표 대형 Plot에서 실제 P95 및 유효 충격 인식률을 비교한다. `sable_optimizer` ON/OFF는 각각 검증한다.

**API·빌드 실패:** GitHub 소스 개별 파일과 실제 대상 JAR/Jar-in-Jar의 클래스·생성자·메서드 descriptor 및 compile/test/runtime classpath를 확인한다. 수정 가능한 문제를 정적 검토 후 구현하고 전체 프로젝트 소스 ZIP을 제공한다. 빌드/Actions/게임을 실제 실행하지 못한 사실을 각각 명시한다. 동작하지 않는 모드를 PASS라 보고하지 않는다.

**기본 종료 규칙:** 좁고 실제로 검증 가능한 수정만 적용한다. 데이터 유실·잘못된 owner 피해·콜백 변조를 피하기 위한 가드는 삭제하지 않는다. 한 버전 기능을 임의로 다음으로 넘기지 않는다. `정적 검토`, `실제 빌드`, `실제 JUnit`, `Actions`, `인게임`을 구별한다.


## 29. 1.0.7 변경 전후 대응표 및 구현 증거 (스냅샷/규범 분리)

| 영향 영역 | 1.0.6/과거 설계의 상태 | 1.0.7 규범적 결론 | 검증/구현 근거 |
|---|---|---|---|
| 충돌 Q 배분 | Q 선계산 후 A/B/타깃 균등 | **F 승인→A/B F 반분→owner-local 반분 F→블록별 G(Fᵢ)→Q cap** | v0.1/v0.2, `ForcePropagation`, `ContactCreditLedger` |
| F 전파 종단 | Q-stress 잔여 UNUSED와 구분 불명확 | **실제 이웃 부재가 증명된 종단만 F 전량** | v0.1/v0.2, 방향 증거·unloaded cap 구분 |
| 탄성 | 각 노드 Q 완충 + Q-stress 중심 | 선행 **실제 부모** Q 감쇠, 자기 outgoing F, 자기 Q 1회; 형제 경로 침범 금지 | v0.2, current path-parent overload |
| 법선 | R2와 exact contact 논의 | 검증된 native signed direction은 **F cone 허용**. exact impulse/voxel shift 아님 | Sable 2.0.6 고정 레코드, normal quality thresholds |
| F/Q 계수 | QFix `0.25/64` + 1.0.6 후보 `damageGain=1.0` | 소스 비교 DEV `0.161/65536`, `damageGain=2.6`, 수정 시 policy revision | 0.4.0colFix2 `GameImpactCredit`, `DamageModel` |
| 물성 원천 | 암묵 추론/프로필/사용자 JSON | **CSV 외부 > JAR 내장 > 추론 > fallback**; JSON은 외부 호환 소스 | 물성 소스 보고서 및 CSV 스키마 |
| 아이템 | 블록 기준 추론에 종속 | **별도 ItemSnapshot H/D/E**, 레시피 추론 입력, 임의 item T 금지 | items.csv 2,159 ID, 구현은 미확인/미적용 |
| Slime | 코드 T≥64, CSV T19 | 충돌을 밸런스 차이로 공개, 신규 내장표의 T 및 override 정책 명시 | `MaterialProfiles`, blocks.csv |
| 저장/상태 | 1/256 remainder/Preview/World commit 별도 | static snapshot vs sparse damage/Receipt 분리, 사건 동안 세대 고정 | 상태/원장 불변식 |
| 전파 종류 | F-first vs 0.6 stress 혼동 | F 생성 분배와 Q-stress의 source/debit 분리 | §8/§17/§30 |
| 버전별 개발 | 0.4.1/0.6.x에 기능 귀속 | **버전 독립 CAP gate**, 원래 버전별 필수 범위/안전 선행은 보존 | §14/§26/§31 |

### 29.1 2026-11-06 고정 스냅샷 — 프로그램 구현을 설계 목표로 가장하지 않음

- PDD GitHub `main` 기준 SHA: `292dbaf0baafb6eedeaf01a1173f871563dac133`, `0.4.0colFix2`. 이전 사용자 요청의 **체크포인트 단위시험 한 줄 수정본 ZIP**은 당시 GitHub 업로드가 이루어지지 않아 main 결과로 간주하지 않는다.
- **연결 구현/Preview:** 원본 Sable 15-double 관측, owner/R2 대상, episode/중복 원장, 승인 game F, path-parent F-first 전파와 finite cap, 블록 정의/상태·레시피·JSON 물성 캐시, H/T/D/E/예상 damage, fixed-point, sparse preview/CRC codec. 실제 원본 콜백·방향 판정·전파 정상 동작은 게임 실측이 별도로 필요.
- **실제 월드 적용 아님:** World/Plot `destroy/move`, drop/BE, Q-stress로 인한 실제 주변 블록 피해, 서버 영구 sparse SavedData, verified Plot split lineage 이전, client crack delta, 외부 폭발/투사체/엔티티 cause, DVT 구현 없음. `PreviewDamageLedger`와 `PreviewCheckpoint`는 정식 월드 영속 상태가 아니다.
- **정적 물성 CSV 아직 미구현:** 두 첨부 데이터파일은 JAR 내부 리소스/런타임 파서로 연결되어 있지 않으며, 레지스트리 일치·실제 성능/밸런스 합격 미검증.
- **마지막 관측 Actions run** `42056043630`: `compileJava`, `compileTestJava`, `processResources` 성공, JUnit **277 중 1 실패** (`PreviewCheckpointTest` 기대 filename 불일치), jar/build 미실행. 새 설계 1.0.7에 대한 빌드/테스트/인게임 결과 아님.
- **설계 상태:** 본 문서 1.0.7은 규범 갱신이고 기능 구현/게임 합격 보고서가 아니다.

---

## 30. F 선배분의 결정론적 상세 알고리즘 — 모든 충돌 릴리스 공통

### 30.1 증거 및 측별 시작

`EventId=(serverRun,dimension,sourceKind,canonicalOwners,generations,episodeGeneration,policyRevision)`에 승인된 `F_event≥0`을 1회만 저장한다. **F는 raw force의 이름을 빌린 게임 강도**다. source 여러 15-double 레코드에서 같은 값을 합산하지 않는다. 소유자별 budget `F_side=A/B share=0.5 F_event`. 한 owner만 검증됐다고 다른 측의 상한을 가져오지 않는다. R2 검증한 실제 표면 블록만 최초 seed이며 다중 seed에는 동일한 초기 F를 나눈다. 타깃 owner/local XYZ 및 generation이 중복되는 seed는 합치고 fingerprint 충돌은 reject.

### 30.2 그래프, 방문, 방향

- 같은 owner-local 공간에서 face-adjacent(6면) 실제 유효 블록만 탐색한다. 정적 World(-1)과 Plot, Plot↔Plot은 **절대 정수 좌표 직접 결합 금지**.
- Plan은 각 노드가 한 depth에 처음 확정되는 **방향 있는 유한 acyclic level graph**를 사용한다. `neighbors`는 실제 loaded/owner/fingerprint/eligibility를 검증해 `PRESENT`, `EMPTY`, `UNKNOWN`을 돌려준다. UNKNOWN을 종단으로 해석 금지.
- `verified normal`: owner-local inward vector가 검증된 경우 `forward=Δ·n`; 첫 1축 이동은 `forward≥0.5`, 이후 `perpendicular²≤0.36 forward²`의 narrow cone 안에서만 전파한다. forward < 0.49면 거절, 소유자의 사선 local normal을 임의 격자 face로 반올림하지 않는다.
- `UNKNOWN normal`: 보수 비방향성 6면 0..2 깊이만; depth limit에서 **이웃 상태를 별도 확인**해 `진짜 EMPTY`일 때만 종단 전량. 128 node, 9/2 depth 등 cap은 개발 정책이고 그 자체는 terminal proof가 아니다.
- 방향 역방향으로 돌아온 블록·이미 확정된 노드는 재계산/재방문하지 않는다. 같은 층의 자식으로 2개 부모가 도착하면 **F를 합친 뒤 해당 자식 1회** 처리한다. HashMap 순회 순서가 결과를 좌우하지 않도록 position/owner 정렬한다.

### 30.3 F 흐름과 정상 종단

```text
for each depth, each unique node n with incoming R_n:
  determine all PROVED eligible unvisited children C_n and uncertainty
  terminal = C_n.empty && no unknown/depth-limited/frontier evidence
  if terminal: localF_n=R_n; outF_n=0
  else: localF_n=R_n/2; pendingForward=R_n/2
  if unknown/blocked: unresolvedF+=pendingForward; outF_n=0
  elif C_n.exists:
        outF_n=pendingForward * t(E_n)
        dampedF+=pendingForward-outF_n
        for c in sorted(C_n): F_in[c] += outF_n / |C_n|
  # if belowFloor and meaningful expansion disallowed => out goes to unresolvedF
  rawQ_n = G(localF_n) exactly once (after F graph available)
```

정상 종단의 `F` 전량 인수는 **단일 완성 경로뿐 아니라 실제 미처리 유효 이웃이 없고 불확실성이 없는 모든 분기 말단**에도 적용한다. 순환으로 이미 방문한 블록만 남았을 때의 종료는 그래프의 후보·검증 범위가 완전한 경우에만 정상으로 판정한다. 미로드/한도/불확실 방향에서 남은 F를 마지막 방문 블록에 몰아줘서는 안 된다.

### 30.4 탄성: 실제 부모 경로별 Q와 자기 후속 F

`t(E)=rMin+(1-rMin)(1−E/100)^beta`, 개발 기본 `rMin=.15`, `beta=1.5`. `t(0)=1`, `t(100)=.15`.

- **후속 F 완충:** 각 부모 `n`의 다른 자식으로 향하는 F만 `t(E_n)`을 곱한다. 이미 그 부모에 할당된 local F나 unrelated branch를 사후 변경하지 않는다.
- **선행 부모 Q 완충:** 모든 자식 `c`가 동일 단계에 확정되고 `n→c` 실제 전달량 `w_nc>0`이 검증되면, `Q_pre_n=G(localF_n)`에 `T_parent = (Σ_c w_nc t(E_c))/(Σ_c w_nc)` **한 번** 곱한다. 즉 `Q_afterParent_n=Q_pre_n × T_parent`. `w_nc`는 부모에게서 자식으로 실제 보낸 F로, 다중 부모 자식에서는 각 부모 기여를 별도 보존한다. 다른 형제나 전혀 연결되지 않은 다른 부모의 탄성은 영향을 주지 않는다. 자식이 한 명이면 그 자식의 `t(E)`가 부모 Q에 적용된다.
- **자기 Q 완충:** 이렇게 **최종 확정된** `Q_afterParent_n`에 대해 `DamageModel`은 `Q_active_n=Q_afterParent_n×t(E_n)`를 *해당 Q 계정에 정확히 한 번* 적용한다. 이는 앞선 Q와 후속 F라는 별개 대상을 혼동한 중복 차감이 아니다. 양산 코드에서 `E_n`을 Q_active에 다시 곱하거나 neighborStress에서 같은 노드 E를 재적용하는 것은 금지.
- **부모 Q 감쇠 범위:** 직접 부모가 실제로 다음 child에게 보낸 경로만; v0.1의 미정 '모든 선행 블록을 무차별 감쇠'는 현재 v0.2 설계와 일치하지 않으므로 채택하지 않는다. 같은 층의 합류 자식은 Q 한 번. 부모별 가중치는 반드시 실제 전송 F로, 전역 layer-average 금지.
- 탄성으로 감소한 F는 `F_damped`, Q는 `Q_parentElasticDiscount`/`Q_selfElasticAbsorbed`로 각각 다른 계정에 기록한다. 서로 다른 단위의 금액을 더해 `Q_cap`에 맞추면 안 된다.

### 30.5 Q 변환·cap·damage

`G(f)=max(0,0.161×max(0,f−2.0))`는 0.4.0colFix2 참고 정책이며 운영 변경 가능. 한 노드에 cap 65536을 따로 주는 식은 금지. `Q_groupCap=min(config.maxGroupQ,G(F_event))`; `Q_sideCap=0.5×Q_groupCap`(현재 기본 A/B). `Q_raw_i=G(localF_i)`, `Q_path_i≤Q_raw_i`. 각 측 `ΣQ_path_i`가 cap보다 크면 
`scale=min(1,Q_sideCap/ΣQ_path_i)`로 비례 조정하고, `Q_i=Q_path_i×scale`을 1/256 또는 별도 안전한 고정소수점 단위로 결정론적 나머지 배정한다. 그룹 전체 cap도 두 측 합산으로 검증한다. 미발행분은 `Q_unissued`, 경로 감쇠는 `Q_discounted`, 실제 safe commit 거절 몫은 `Q_unused`/Receipt로 기록한다.

- 예시: **측별 F=100**, 탄성=0, 5블록 직렬 연결에서 `F_i=[50,25,12.5,6.25,6.25]`이고 `G(F_i)=[7.728,3.703,1.6905,0.68425,0.68425]`; 합 `14.4900`, `G(100)=15.778`과 다르다. 이것은 forceFloor의 결과이고 남은 Q를 다시 다른 타깃에 발행하지 않는다.
- `Q_active=Q_i×t(E_self)`, `Q_direct=0.60 Q_active`, `Q_plastic=0.15 Q_active`, `Q_stress=0.25 Q_active`, `rawDamage=Q_direct×2.6×(0.08+0.92(1-H/255)^1.5)`는 임시 개발 정책. 손상 증분은 `floor(max(0,rawDamage)×256)`, u8 damage+remainder 범위에서 포화. 같은 이벤트 여러 callback에서 올림 1 피해 반복 생성 금지.
- 최종 `Q_i`가 계산됐다고 actual APPLIED/Drop/Move가 성공하는 것은 아니다. 실제 World/Plot 안전 변경과 영속 Receipt는 별도 gate가 필요하다.

### 30.6 F/Q 회계의 독립 정합성

```text
F_event = F_sideA + F_sideB
F_side = Σ_unique_assignedF + Σ_F_damped + F_unresolved
Q_rawTotal = Σ_i G(F_assigned_i)
Q_rawTotal = Q_parentElasticDiscount + Q_afterParentTotal
Q_afterParentTotal = Q_sideOrGroupCapClipped + Q_unissuedByCap
issuedQ = Σ_i Q_i (after cap, before self material response)
issuedQ = Q_selfAbsorbed + Q_active + Q_targetVetoUnused
Q_active = Q_direct + Q_plastic + Q_stress + Q_residualUnused
Q_appliedConsumed + Q_nonWorldAbsorbed + Q_unusedFinal + Q_boundedReserved = issuedQ
```

상기 등식은 **단위·계정 층을 혼동하지 않을 때**만 뜻이 있다. 특히 Q material response와 `Q_targetVetoUnused`는 서로 다른 타깃 집합에 대해 정산하고, `Q_appliedConsumed`는 direct/plastic/stress 분배의 선지출 숫자가 아니라 **실제 적용된 의도 예산**이다. `Q_nonWorldAbsorbed`는 자기 탄성 또는 그 밖의 승인된 비월드 소비이고, 이미 `Q_unusedFinal`로 처리한 경우 양쪽에 **중복 계상하지 않는다**. `D_raw` 같은 게임 damage 증가량을 Q budget으로 그대로 가산하지 않는다. 모든 단계에서 유한값, 비음수, 타입, `ε` 허용오차, 합계 상한, double conversion 방지 검증을 수행한다.

## 31. 능력 기반 개발 게이트: 특정 개발 버전에 종속되지 않는 구현 계약

| Capability | 반드시 있어야 할 입력/출력 | 반드시 통과할 검증 | 초기 릴리스 계획상 위치(구속력 없는 참고) |
|---|---|---|---|
| `CAP-SOURCE` | Sable 원본 보존, 검증된 sourceQuality/scene | JNI ABI, original callback count/order/return, native cap | 0.3.x |
| `CAP-OWNER` | actual loaded World/Plot R2 owner-local target·epoch | World/Plot/Plot, rotation, body ID reuse | 0.3.x |
| `CAP-EPISODE` | new-impact proof, single Credit, resting contact reject | 2회 positive separation, no-silence rearm, bounded Q | 0.3.x |
| `CAP-F-FIRST` | side F, parent DAG, verified terminal/unresolved | 1/2/3/5, join/cycle, native/unknown normal, parent E | 0.4.0 preview |
| `CAP-MATERIAL-STATIC` | Block H/T/D/E + Item H/D/E, 4 sources, snapshot | CSV/JSON/registry/recipe/reload/override tests | 0.4.1 데이터 기능 |
| `CAP-DAMAGE-PLAN` | H/T/D/E, 1/256, Q direct/plastic/stress | finite/monotonic/one-time absorption/fixed-point | 0.4.x |
| `CAP-WORLD-COMMIT` | World CAS, permission, BE, receipt, minimum durable | 실제 파괴/드롭 1회 + reboot 재대조 | 0.4.1 |
| `CAP-SPARSE-PERSIST` | chunk/dimension durable damage, migration | unload/restart/schema/fingerprint/stale block | 0.5.0 |
| `CAP-PLOT-COMMIT` | Plot local safe block API, Sable lifecycle, quarantine | Plot split/BE/collider/mass + no stale writes | 0.5.1 |
| `CAP-Q-STRESS` | separate Q-stress graph, node-once budgets | cyclic/diamond/neighbor E, no duplicate F | 0.6.0 |
| `CAP-OWNER-LINEAGE` | verified old/new block lineage split/merge | old owner stale state/ghost damage 0 | 0.6.1 |
| `CAP-WORLD-DEFORM` | verified direction, occupied-cell preflight | actual grid move, rollback/BE veto | 0.7.0 |
| `CAP-PLOT-DEFORM` | Plot owner-local movement & mechanics/collider safety | BE/glue/kinetic Sable changes under cap | 0.7.1 |
| `CAP-VISUAL` | server damage vs crackVisual delta | reconnect/unstale/fade does not heal damage | 0.8.0 |
| `CAP-EXTERNAL` | vanilla explosion survivor, projectile/entity causeGroup | no duplicate original drop/PDD impact | 0.8.1 |
| `CAP-DVT-REUSE` | source adapter + static material + separate dynamic state | no second core damage model | DVT 1~3 |
| `CAP-PERF` | bounded collision CPU/heap/TPS & overload data | actual p95, 15-client scenario, no silent hit loss | 0.9.x |
| `CAP-RELEASE` | full project source ZIP & protected Apply + build/game evidence | SHA, files, CRC, build/JUnit/Actions/game separately | 모든 코드 릴리스 |

**개발 법칙:** 해당 개발 버전의 설계상 기능은 모두 구현하고 상위 안전 게이트를 충족한다. 그 버전에 없는 후속 capability를 구현 완료라고 보고하지 않는다. 1.0.7 문서는 모든 버전에 동일한 뜻으로 적용하고, 버전 변경만으로 새 게임 모델을 도입하지 않는다. 기존 Gradle Wrapper·NeoForge·JUnit·Actions/Apply 계약을 임의 변경하지 않는다.

## 32. 정책 확정·권고·실증 필요를 명시적으로 분리

| ID | 판단 | 내용 | 필요한 후속 조치 |
|---|---|---|---|
| `DEC-F-01` | **사용자 확정** | F를 블록에 순차 반분·종단 전액, 블록별 G(F) | 바뀌는 모든 Q 선계산 루틴 교체 |
| `DEC-F-02` | **사용자 확정** | 실제 탄성체가 선행 Q 및 후속 F를 감소 | 부모 경로만 감쇠, 형제 오염 금지 |
| `DEC-F-03` | **v0.2 개발 규범** | 다중 seed 균등, parent-local weighted Q, single-node join, Q 자기 E | 공식 JUnit + 게임 방향·물성 재현 필요 |
| `DEC-N-01` | **API 증거 + 개발 임계** | 15-double native normal, dot/pose/cone/unknown 규칙 | 다른 버전/회전·다중 contact ABI 재검증 |
| `DEC-Q-01` | **DEV 계수** | forceGain .161, floor2, cap65536, damageGain2.6, 0.60/0.15/0.25, H/E 곡선 | TPS/게임 밸런스에서 조정 가능. 실측 물리값 아님 |
| `DEC-M-01` | **사용자 확정** | 외부 CSV > JAR > 추론 > fallback, 정의별 1회, block+item | 실제 로더/리로드·레지스트리 일치 구현 필요 |
| `DEC-M-02` | **1.0.7 권고 기본** | legacy JSON은 외부 계층으로, 같은 필드면 CSV 우선; 속성별 공란 보충 | 사용자 JSON 손실/무시 방지, conflict log·회귀 시험. 기존 운영 설정과의 UI 설명 필요 |
| `DEC-M-03` | **데이터 미검증/충돌** | slime CSV T19 ≠ 코드 T≥64, stone E 등 값 불일치 | 내장 밸런스 표/JSON/CSV diff 및 실제 게임 보정, 임의 실측 주장 금지 |
| `DEC-M-04` | **미확정 구현 정책** | CSV safe reload 관리자 명령·상한·재시작 정책, state selector 증강 | 운영 정책 검증. 최소 안전 기준은 서버 시작/재시작 반영 |
| `DEC-M-05` | **미검증 런타임 사실** | 설치 서버 모든 block/item ID·상호 모드 레시피 및 성능 | 실제 NeoForge server registry/heap/TPS 측정 |
| `DEC-GAME-01` | **아직 기능 구현 아님** | 실제 World/Plot 변경·stress Q·deform·visual·DVT 및 exactly-once | 각 CAP gate+직접 게임 테스트 필요 |

**확정 범위에 관한 주의:** 사용자에게서 직접 승인되지 않은 숫자·JSON 충돌 정책·new parser path/enum 이름은 명시적으로 `[DEV_POLICY]` 또는 `[DERIVED]`인 설계 기본값이다. 문서 버전이 올라갔다는 이유만으로 **실제 API에 존재한다고 단정하거나 게임 검증을 통과했다고 표시하지 않는다**. 이 구분은 다른 버전으로 이동해도 보존한다.

## 33. 1.0.7 전구역 반영/추적 체크리스트

| 영향 섹션 | 개정 반영 내용 |
|---|---|
| §0–2 목적·문서·근거 | 문서 버전 독립, F-first/물성 4계층, 정책/증거 분리, snapshot revision |
| §3–5 API·아키텍처·필수 입력 | native signed normal 검증, source quality/owner/방향 분리, 단방향 F-first flow |
| §6/§22/§30 원인 예산 | side **F** 반분, 블록별 G(F), cap clipping, parent Q·F 회계, 단일 episode |
| §7/§24 물성·Damage | CSV/JAR/추론/fallback+JSON 호환, Block/Item schema, H/T/D/E, slime T diff, Q 직접/응력 별도 |
| §8/§9/§10 응력·변조·외부 | F propagation과 Q stress 분리, plan freeze, safe World/Plot/Receipt, external causeGroup |
| §11–13 성능·DVT·설정 | bounded 128/9/2/cone, O(1) snapshot, DVT modifier 별도, P-24~P-29 |
| §14–17 개발·시험·배포·의사코드 | 버전 독립 CAP gates, 회귀·두 세션, 전체 소스 ZIP 규칙, F-first runtime pseudocode |
| §18–21 근거·완료·기술 | 자료별 원본 해시/SHA, 오래된 완료 주장의 이력화, ABI·normal 경계 |
| §23/§26–29 정책값·판정 | 최신 dev 참고 계수, 실제 확인 범위, 빌드/게임 구분, 변경 대응표 |

### 33.1 최종 문서 확인 항목

- 1.0.6의 **목표 기능·기본 보안 불변식/월드 저장/BE/드롭/라이선스/Actions/Github Apply**가 제거되지 않았다.
- 1.0.7의 F-first·parent-local 감쇠·안전한 종단/성능 상한·CSV 4계층·JSON 호환 및 ItemSnapshot의 출력 계약을 모든 관련 구역에서 참조한다.
- 1.0.6의 오래된 Q 균분·기준값 0.25/64, `damageGain=1.0`, `현존 소스=QFix`는 **역사적 기록**으로만 표기하며 1.0.7 기본 설계에서 사용하지 않는다.
- 이 문서는 **코드/Actions 성공**을 선언하지 않는다. 새 소스 구현 시 사용자의 전체 소스 ZIP 규약 및 별도의 증거 장부를 적용한다.
