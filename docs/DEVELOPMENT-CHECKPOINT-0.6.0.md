# PDD 0.6.0 개발 이력

**최신 상태:** 0.6.0 릴리스 소스는 World/Plot 이동 및 HeatMap 확인, 차단 Q 집행과 Copycats+ 장식 BE 이동을 연결했다. 최신 검증 및 사용자 실행 절차는 [PDD 0.6.0 릴리스 검증](verification/PDD-0.6.0-Release-Verification.md)을 따른다. 아래 중간본 기록은 역사적 개발 이력이며 최신 구현 상태가 아니다.

2026-11-18. 전체 소스 체크포인트. 설계서 1.0.9의 0.6.0 완성 조건은 미충족 상태다. 아래 dev5 변경 사항이 과거 dev4 기록보다 우선한다.

## dev5 변경과 남은 게이트

- 원격 main SHA는 기존 증거와 동일한 `7e64189d2758c81e45d7d71f066c90e51adc127d`로 읽기 조회했다. 첨부 dev4가 개발 기준이다. 원격 쓰기 없음.
- Copycats+ 공식 NeoForge JAR `3.0.11+mc.1.21.1-neoforge`의 SHA-256 `eab9c71e0cc133c38f1063fc9c892599207240a1f0559ce1e3ecddc4ad7aa759`는 §36과 일치한다. Modrinth version zLlVJXf0, modId copycats, NeoForge >=21.1.200, Create >=6.0.8. JAR에 포함된 인터페이스 및 메서드 이름/descriptor를 확인했다. PDD는 reflection으로 선택적으로 접근하며 JAR를 번들하지 않는다.
- `CopycatMaterial`, `TargetMaterials`에서 위치별 단일/복수 재질, Slab/Byte/Layer 비중, 보드 등 검증 가능한 점유 합집합 및 canonical material revision을 조회한다. 재질이 없는 경우 기본 카피캣으로 추정하지 않는다. 실제 BE의 재질 변화와 형상 변화는 기존 손상/Remainder/변형 기록과 분리한다. F-first, preview, World/Plot 커밋 및 Q-stress에 위치별 물성을 전달하고 승인과 실제 파괴 이벤트 후 다시 비교한다. 모든 형상, 기계/BE 이동, 인게임 드롭·성능 및 로딩 게이트는 아직 통과하지 않았다.
- `TargetDirections`는 동일 native point에서 확인된 full-cube 면과 부호, 접근 증거를 결합한다. 여러 방향/불명확한 후보는 방향 없음으로 처리한다. 실제 지불된 F 그래프 간선에서 일치한 부모 방향만 전달한다. `MovePlan.Evidence`로 F/층/부호/epoch를 인텐트에 묶어 저널에 보존한다. 다른 형상에 대한 방향 승인, 역층 이동 실행, World/Plot 동시 이동 순서 검증은 남았다.
- `MaterialRegistry.contentGeneration`: 정렬된 유효 재질표/상태 정의/정책의 63-bit SHA-256 파생 generation. 프로세스 재시작에 의존하지 않는다. 동일한 새 generation은 재개 가능하며, 옛 숫자 generation이나 달라진 재질표의 미집행 Q는 기존 ApplyId에서 보수적으로 종료한다. 이미 누적된 피해는 재계산하지 않는다. Q-stress 원본 Q 및 계획 ID를 그대로 저장한다.
- World/Plot 저널 schema 5 (이전 1~4 읽기), StressReservationJournal schema 3 (이전 1~2 읽기). 재질 revision 및 방향 증거 round-trip, 같은 카피캣 형상 변경의 sparse state 원자적 rekey, stale generation 거절. `MoveTransactionExecutor`의 만료 후 성공 readback 금지 및 ID alias 방지 보강. 원본 이동 PREPARED 중단은 재시도 대신 격리한다.
- `DamageModel.blockedFixedIncrement`: 이미 자기 E가 반영된 예약 Q에 H와 추가 자기 손상 보정만 적용한다. 정상 피해 60% 재분할/재전파 없음. **이 계산은 실제 이동 실패 거래에 아직 연결되지 않았다.** 0.6.0 완성이라고 주장하지 않는다.
- 다음 단계: 실제 두 위치 World/Plot acquire/validate/setter/readback/release/quarantine gateway, 역층 순서/원본·목적지 incarnation과 claim·glue·기계·연결성, 이후 Sable clean HeatMap witness 확인, 검증된 차단에만 예약 Q의 일회성 영속 사용 및 파괴 경로 연결. BE NBT 이동과 Copycats 특수 형상/기계/드롭 검증. 미확인 경로는 강제 변경하지 않는다.
- dev4 305개 파일 전부 보존, 33개 변경, 5개 신규, 삭제 0개. 기준 main manifest 273개 누락 0개. JUnit `@Test` 선언 dev4 505개 → dev5 516개 (+11). 선언 집계만이며 실행한 테스트 수는 0이다. 정적 수치 대입: E=0, Q=10, H=0의 차단 추가 피해 고정값 6656; E=100은 4437; 철/참나무 double slab H=86 D=53 E=30 T=406; 흑요석 half slab T=493, byte T=123. 실제 Java 빌드, JUnit, Actions, 게임은 미실행.

## dev4 보존 근거 및 과거 구현 기록

## 기준과 검증

- GitHub `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming` main `7e64189d2758c81e45d7d71f066c90e51adc127d`, tree `e442c586c89249ef341edeec36df41d2fe6f43df`.
- 기준 273개 파일의 Git blob SHA, 크기, 모드 대조. 기준 `2160b53` 대비 원격 변경은 `.github/workflows/PDD-1.0.9-Integrated-Design-Specification.md` 추가 1건이다. 첨부 설계서가 해당 Git blob과 바이트 단위로 일치한다.
- [기준 파일 목록](verification/0.6.0-dev4-source-manifest.tsv). `BASELINE-SHA256.tsv`는 초기 첨부 267개 파일의 과거 증거다.
- 기존 Workflow, Wrapper 및 Gradle 의존성 버전을 보존했다. Build 워크플로 산출물 표시명 `PDD-0.6.0`, 개발 JAR 버전 `0.6.0-dev4`.
- JUnit 소스의 `@Test` 505개, 기준 430개 대비 75개 추가. 단순 선언 집계이며 실행 결과는 아니다. compileJava, compileTestJava, JUnit, processResources, jar, clean test, build, 실제 Actions, 인게임은 모두 미실행이다.
- ZIP은 모든 기준 파일을 포함한다. 검증용 사본에서 Apply workflow의 원본 Python 동기화 구간을 실행하여 보호 경로 보존과 삭제 동작을 확인한다. GitHub 업로드와 Actions는 사용자 영역이다.

## 현재 소스

**응력** — `StressRuntime`이 승인된 Grant의 World·Plot 직접 인텐트 제출 전에 응력 Q를 예약한다. `StressReservationJournal` schema 2에 원본 인텐트·설정·Q·고정 그래프·확정 계획을 저장한다. 직접 타깃의 전파 시작점이 기록될 때까지 직접 커밋을 보류한다. 로드된 동일 소유 공간의 6방향 후보를 조회하고, 최소 깊이 유입을 합쳐 E를 한 번 적용한다. 실행 예산은 서버 전체 틱당 최대 512 작업이다. 이미 확정된 계획은 미로드 경계의 만료를 기다리지 않고 stable ApplyId로 기존 커밋 서비스에 넘긴다. 만료 처리는 상태 전이 때만 예산을 사용한다. 차단된 잔여 Q는 원래 TTL을 넘겨 재발행하지 않는다. CLOSED는 영속 인계 기록이다. 실제 손상 여부는 커밋 영수증에서 확인한다. [런타임 상세](verification/PDD-0.6.0-Stress-Runtime.md)

**변형 기반** — `MovePlan.quantizeDirection`은 X/Z/Y 동률 순서와 보조 축 50% 기준을 반영한다. `WorldPersistentJournal` schema 4는 변형 이력을 U8로 저장하고 schema 1~3을 읽는다. 이동 거래의 준비 기록에 원본·목적지 상태와 세대 번호를 결합하며, 확인된 이동의 sparse 상태 이전을 한 WAL 레코드로 반영한다. `MoveTransactionJournal` 및 `MoveTransactionExecutor`에 두 위치의 이동 전 상태, 준비 후 중단 시 격리, 재시작 시 쓰기 재시도 방지를 추가했다. 이 두 클래스에는 실제 게임 호출자가 없다. 현재 게임의 변형 이동과 이동 차단 추가 피해는 미구현이다. [JAR 근거](verification/PDD-0.6.0-Movement-API-Evidence.md)

## 다음 구현 순서

1. **변형 방향과 원장.** 원본 native R2 레코드의 블록 및 부호 방향을 같은 레코드에서 확인한다. 소유자 단위 평균 법선은 이동 승인에 쓰지 않는다. 원래 Credit의 F, 층, plastic Q를 묶고 충격 전 손상으로 임계치와 이동 조건을 계산한다. 파괴 우선, 역층·역키 순서, 같은 블록·사건당 한 번의 이동을 보장한다.
2. **실제 World·Plot 이동.** 로드·owner·상태·세대 번호·보호·목적지/대각 경로·BE·glue·기계·연결성 조건을 확인하고 두 위치를 함께 감시한다. 내구성 있는 준비 기록 뒤 정상 setter를 사용한다. Plot은 Sable HeatMap의 이후 정상 완료와 같은 소유자의 원본·목적지 상태를 다시 확인한다. 실패 시 확인 가능한 원상 복구 또는 두 위치 격리. 확인된 이동 차단에만 `min(G(F_i*(1-K)), plasticReserve)`를 추가 피해로 소비한다. H와 자기 피해 보정만 적용하며 E나 직접 피해 60% 분할을 되풀이하지 않는다. 이미 종료된 응력 파생 plastic Q를 재사용하지 않는다.
3. **Copycats+ 3.0.11.** 설계서 1.0.9 §36–37의 실제 JAR 및 API를 확인한다. 선택적 World/Plot 어댑터, 단일·다중 활성 슬롯, H/D/E와 형상별 T, 재질 교체 revision/CAS 및 기존 손상 보존을 구현한다. F 전파에서 E를 처음 읽기 전에 재질 스냅샷을 확정한다. 실제 카피캣 상태·BE 및 드롭을 대상으로 검증하고 기능성 BE의 미확인 경로는 보호한다. 이 ZIP은 외부 모드 JAR를 포함하지 않는다.
4. **검수.** 원래 콜백 계약과 중복 방지 보존. 응력 런타임은 재시작 시 프로세스별 재질 generation 불일치로 보수적으로 보류될 수 있다. 레코드 상한(8,192개/64 MiB)과 디스크 실패 경로, 부분 확정 이후 소유자 및 재질 변경, 두 WAL 사이의 중단 시점을 재검토한다. Copycats+ 추가 후 기존 테스트와 신규 수치를 정적으로 점검한다. README와 전체 ZIP 갱신 후 실제 Gradle·JUnit·Actions·인게임을 사용자가 실행한다.

## 두 인게임 세션에 묶을 검증

1. World 응력 line/diamond, 중복·저장·재시작, 미로드 경계, 빈/막힌 변형 경로, 단일/다중 카피캣과 재질 교체.
2. Plot 두 UUID의 경계 및 split, 이동 후 HeatMap·질량·충돌체, Copycat NBT·드롭·재시작. 실제 커밋 전후 소유자·위치·손상 및 중복 적용 여부 확인.

미구현 기능은 해당 구현을 추가한 뒤 검증한다. `latest.log` 전체, 모드 버전, JVM 설정, 재현 위치/Plot UUID, 게임 틱을 보존한다. 응력 관련 로그는 `[PDD-STRESS-LEDGER]`, `[PDD-STRESS-APPLY]`, `[PDD-COMMIT]`, `[PDD-PLOT]`, `[PDD-PERSIST]`다. 상세 응력 로그는 `-Dpdd.stress.trace=false`로 끌 수 있다.
