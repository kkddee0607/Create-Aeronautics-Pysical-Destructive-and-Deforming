# PDD 0.6.1 | 사전 검토 및 사용자 검증

## 기준과 적용 계약

- 기준: `0.6.0-deformFix`, main `5ceeca289b3e6d5a66b88b95338b39e0ba7235c7`, IDS 1.0.9 및 0.6.1 인수인계.
- GitHub 재귀 트리의 341개 blob 경로·mode·SHA와 로컬 Git index 일치; Git 객체 검사 `git fsck --full` 성공. 기존 파일은 임의 삭제하지 않는다.
- Minecraft 1.21.1 / Java 21 / NeoForge 21.1.228 / Sable 2.0.6 / Create 6.0.10 / Aeronautics 1.3.2 / Copycats+ 3.0.11.
- Apply YAML은 한 개의 `incoming/*.zip`과 한 개의 Gradle 프로젝트 루트(`build.gradle`, `settings.gradle`, `src/main/java`)를 요구한다. `.git`, `.github`, `incoming`, `gradlew`, `gradlew.bat`, `gradle`는 보호된다. 전체 ZIP에는 기존 보호 파일도 포함하되 `.git` 내부 메타데이터는 제외한다.
- `build-gradle.yml`은 Java 21로 `./gradlew test --no-daemon` 후 `./gradlew build --no-daemon`을 실행한다. 보호된 YAML의 아티팩트 표시명은 여전히 `PDD-0.6.0`이지만 JAR 자체는 `physical-destroy-deform-0.6.1.jar`로 생성되도록 버전 설정을 갱신했다. 기존 콜백 테스트 Action은 보존한다.

## 알고리즘과 제한

1. **CONTACT ELASTIC**: 동일 native record의 검증된 상대 블록만 탄성 감쇠에 사용. 각 측의 최초 F는 고유 seed의 기존 균등 배정량을 유지하고 해당 seed의 서로 다른 상대 접촉에 나누어 감쇠한다. 미확정 상대 몫은 unresolved, 중복 witness는 재배정 금지. R2 면 확장으로 생성된 근사 seed는 정확한 상대 재질이 없는 경우 unresolved이며 후속 owner-local 전파 경로는 유지된다. 기존 자기 E/Q 및 자기 최종 손상 보정과 별개로 처리한다.
2. **CHAIN**: 원본 물리 F/Q가 아니라 PDD gameplay F에만 적용된다. `VerifiedContactGraph`는 해당 tick의 실제 native contact record, 양측 외부 충돌면, 월드 접촉점 및 법선 일치, 모든 pre-solver epoch의 정지 pose, 동일 차원·세대·재질을 요구한다. Sable 2.0.6의 `clearCollisions` 배열에는 정확한 solver substep/원인 식별자가 없으므로 동적 후속 충돌의 인과관계는 추정하지 않는다. 직접 Credit이 새로 발급될 수 없는 기존 ACTIVE 정지 접촉만 경계 간선으로 사용한다. 이 조건을 증명하지 못하면 owner-local 계산을 유지하고 교차 전파를 승인하지 않는다. 실제 접촉 record를 Sable이 제공하지 않는 정지 접촉도 이 버전에는 전파되지 않는다.
3. **F/Q 원장**: 각 node는 최소 BFS depth에서 동일 depth 부모 예산을 합산한다. 내부는 기존 6면 이웃, cross-owner는 물리 접촉만 허용하며 방향은 월드 vector를 통해 수신 owner local로 변환한다. 원래 두 측의 cap, 32,768 노드, F/Q 1.0 하한 유지. 신규 경계 깊이 4, 간선 128, owner 8 제한; 모호한 양측 중복 node는 교차 경계 승인 없이 원래 두 측의 예산을 다시 계획한다. 별도 owner Q-stress 알고리즘은 수정하지 않는다. 회전 Plot에서 cross-owner F 방향을 블록 변형 증거로 승격하지 않는다.
4. **영속성/lineage**: native `moveBlocks`의 실제 호출 `AssemblyTransform.apply(BlockPos)`와 `apply(BlockState)`, 원래 source 및 destination의 실제 setter 결과, loaded-only 상태 읽기와 native BE NBT 확인으로 블록 대응을 검증한다. 기존 journal을 사용해 두 leg의 PREPARED 기록 후 원본 소거 및 대상 상태 기록을 검증한다. 불확실한 native 종료나 저장 불일치에서 블록 이동을 재실행하지 않는다. `splitTransferCap` 기본 4096; 초과/미확정 source 상태는 숫자 before-image가 있는 durable quarantine으로 남기고 후속 실제 교체를 기준으로 격리를 만료시킨다. 기존 PDD deformation용 journal 및 18방향 연결성 유지. 월드 저장과 PDD journal은 하나의 ACID 트랜잭션이 아니므로 강제 종료 후 자동 native 이동 재실행 또는 확정되지 않은 상태의 자동 복제는 하지 않는다.

## API 및 수치 검토

- 제공 Sable JAR에서 `SubLevelAssemblyHelper.moveBlocks(ServerLevel, AssemblyTransform, Iterable<BlockPos>): void`, `AssemblyTransform.apply(BlockPos): BlockPos`, `apply(BlockState): BlockState`, `getLevel(): ServerLevel`, `getRotation(): Rotation`, `LevelPlot.getEmbeddedLevelAccessor`, `getCenterBlock`, `getSubLevel` 확인. `SplitListener.addBlocks`는 정확한 old→new 대응표의 독립 증거가 아니다.
- 실제 `moveBlocks` bytecode offset 335/343의 좌표/상태 변환과 407의 BE NBT 저장을 원본 호출 1회씩 유지하며 관찰, 529/892의 LevelChunk setter로 결과 검증. Aeronautics 1.3.2 bundled SimAssemblyHelper `disassembleSubLevel`은 동일 `moveBlocks`, `assembleFromSingleBlock`은 `assembleBlocks`를 호출한다. 별도 모드가 이 native 경로를 우회하면 동일성 추정으로 이전하지 않는다.
- Sable 2.0.6 Jar-in-Jar Companion 1.6.0과 native Rapier 2.0.6 포함 확인. 기존 compileOnly / testImplementation / testRuntimeOnly 설정을 유지하고 새로운 의존성을 추가하지 않는다. 실제 Gradle dependency 해석은 사용자 실행 대상이다.
- `t(E)=0.15+0.85(1-E/100)^1.5`. E=0/100에서 측별 F=100의 유지량은 100/15. 양측 E=100일 때 `contactDampedF=170`, 이후 자기 Q 감쇠 독립 적용. E=50의 정확한 유지율은 약 45.0520382%. 비선형 F→Q로 최종 손상률 2.25%라고 단정하지 않는다.
- E=0의 F=100 단일 5블록 경로: 50+25+12.5+6.25+6.25=100. Q는 기존 각 node의 환산/하한/측별 cap을 적용한다. F 원장에 `contactDampedF`를 별도 계정으로 추가한다.
- 탄성 있는 중간 블록의 기존 내부 후속 F 감쇠와 새로운 owner 경계에서의 상대 접촉 감쇠는 서로 다른 두 단계이다. 예를 들어 송신 블록 E=100이라면 후속 F의 기존 내부 전달률 0.15 이후 같은 블록이 상대 접촉체로 작용하는 신규 경계의 0.15를 추가 적용한다. 동일 직접 접촉 자체를 다시 처리하지는 않는다.
- 원장 revision `PDD-IDS-1.0.9/0.6.1-CONTACT-ELASTIC-CHAIN`; 기존 피해/응력 수식 revision 및 저장 디렉터리, authoritative WAL schema를 변경하지 않는다. 진단용 preview checkpoint는 새 policy namespace를 사용하며 이전 파일을 덮어쓰거나 피해로 소급 적용하지 않는다.

## 사용자 검증 (실제 실행 NOT_RUN)

정적 검토 기준: 기존 @Test 600개, 신규 @Test 37개, 총 637개 예상. Java 소스 218개 구문 파싱 및 JDK 기반 순수 계산 클래스 8개 타입 검사에서 오류 없음. 전체 게임 의존성 클래스패스 컴파일이나 JUnit 실행 결과가 아니다. 기존 확장 접촉면의 상대 재질 미확정 seed 보류로 인한 피해 감소 가능성을 회귀에서 우선 확인한다. Glue는 Sable의 기존 별도 native 이동 경로를 유지했으나 엔티티 이전 대응을 독립적으로 검증하지 않았으므로 G-CC-LINEAGE의 실제 판정은 보류한다.

월드와 PDD 데이터 전체 백업 후 기존 JAR을 제거한다. Apply 성공·Callback Contract Test 성공 후 수동 `PDD Manual Build` 실행. 로컬 확인:

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

실패하면 최초 `compileJava`/`compileTestJava`/`test`/`processResources`/`jar` 실패 지점과 전체 로그를 제출한다. 필요하면 세 가지 Gradle classpath dependency report도 첨부한다. AI는 실제 Gradle, JUnit, Actions, in-game을 실행하지 않았다.

### 세션 1: G-CONTACT-ELASTIC / G-CROSS-OWNER / 0.6.0 회귀

1. 동일 질량·속도·접촉 개수를 맞춰 흑요석/슬라임 및 탄성 없는 바퀴/고탄성 바퀴의 충돌을 비교한다. 접촉만 바꾸고 다른 재질은 동일하게 유지한다.
2. 금 World 벽과 금 Plot 벽의 변형, 기존 Q-stress, 차단 시 회귀 확인.
3. P2가 정적 S1 벽에 정지하고 있는 상태에서 P1을 P2에 충돌시킨다. 다음 시도에서는 벽 사이 간격을 둔다. rotated Plot도 비교한다. 접촉 증거가 없거나 동적 인과를 구분할 수 없으면 cross-owner 예산을 보류해야 한다.
4. 정지 차량에서 신규 원인 Credit이 반복 발급되지 않으며, 독립 접근이 새로 증명된 충돌만 별도 Credit을 발급하는지 확인한다.
5. 로그 `[PDD-CONTACT-ELASTIC]`, `[PDD-CROSS-OWNER]`, `[PDD-CHAIN-LEDGER]`, `[PDD-F-INVARIANT]`, `[PDD-Q-SEAL]`, `[PDD-STRESS-LEDGER]`, `[PDD-DEFORM]`을 제출한다. 성공 기준: F/Q 원장 보존, 직접 접촉 감쇠는 정확히 한번, 미검증 owner 경계에는 피해 없음, 별도 독립 원인 인정.

### 세션 2: G-SPLIT-01 / G-CC-LINEAGE / 0.6.0 저장·종료 회귀

1. 서로 다른 기존 damage/remainder/deformation을 가진 여러 블록으로 두 파편·다중 파편을 만들고 기존 Plot의 잔류 블록도 검사한다.
2. Plot 조립·해체 및 여러 Plot에서 실제 native 블록 이동이 이루어지는 합체 절차를 비교한다.
3. Glue·Copycat의 슬롯, 소비 아이템, BE NBT 및 배치 유지 여부 확인. 안전하게 검증되지 않은 이전은 격리되어야 한다.
4. 저장 후 종료, 재시작 및 같은 블록 재충돌. 기기에 무리한 강제 종료 실험은 복사한 월드에서만 수행한다.
5. `[PDD-LINEAGE]`, `[PDD-TRANSFER]`, `[PDD-PERSIST]`, `[PDD-PLOT]`, `[PDD-COPYCAT]`, `[PDD-STRESS-LEDGER]`, 시작/종료 로그를 수집한다. 성공 기준: old/new 상태 대응 일치, 잔류 상태 보존, 이전 중복·ghost 없음, 모호한 journal은 재발급 없이 격리.

각 gate의 인게임 PASS는 실제 로그가 있어야 확정된다. 기기 성능에 따른 TPS·일반 지연은 이 기능의 정확성 결함과 구분한다.
