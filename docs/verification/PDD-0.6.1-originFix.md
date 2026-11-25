# PDD 0.6.1-originFix — 구현 및 검증 인계

## 기준과 상태

- 기준 GitHub `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`, `main` = `6487a78e368f8cf5e4e40d210383d5a547bf2495`; 원본 Git Blob SHA와 파일 실행 권한을 대조한 390개 원본을 기준으로 작성.
- 우선 설계: PDD 0.6.1 접촉면 F / 성능 근본 교정 계획, 통합 설계서 IDS 1.0.9 §30.
- **기능 구현 상태: 일부 구현.** 양측 면 연결, Native 증거 중복 탐색 감소, F 대응 증거 병합 및 후보 fan-out 제거는 반영. 틱 간 인증서 스캔 재개, 정확한 블록별 native impulse, 내구성 보장 비동기 WAL/checkpoint, Stress DAG 전체 스케줄러는 **미구현**. 기능·성능 전체 완료 또는 인게임 합격으로 표시하면 안 된다.
- AI는 Gradle/JUnit 또는 게임을 실행하지 않음. Java 21 파싱, 독립 진단용 javac 타입 검사와 단일면 smoke 검사는 Gradle과 무관한 정적/알고리즘 예비 확인.

## 수정 사항 — 원본 물리 계약 보존

1. `BilateralContactSurface` — `reconstructAll`은 동일 A/B Pose와 접촉 축의 Native 표본을 사건 단위로 묶어 중복 검색을 줄인다. 양쪽 소유자 격자의 접선 방향 이웃을 독립적으로 검사한다. 반대편 접촉면의 후보 셀은 변환된 외부면 투영 범위로 좁히고, 접선 격자 경계에서 양수 면적이 없는 셀을 비싼 다각형 검사 전 제거한다. 비정형·극단 정밀도 구간은 실제 다각형 교차를 재검증한다. 조사 상한 초과는 `incomplete`/빈 결과로 fail-closed; Native POST_EFFECT 임의 확대나 F 복제는 없음.
2. `SableContactRegions` — Native 감사 후 양측 면 복원을 단일 호출로 수행하고, 같은 사건 안에서 ShapeReader의 검증 결과를 기존 `OwnerResolver.NativeAuditBatch`와 공유한다. 면 전체가 인증된 때에만 인증된 상대를 `ContactCreditLedger.Region`로 결합한다. Native 포화, Frame 불일치, 미로드, 검사 한도 초과에는 부분 면을 전체로 봉인하지 않는다. 기존 면 패치 방향 증명과 교차 Owner 검증은 보존.
3. `SableContactRegions.mergeBilateralPairs` — 기존 8개 후보 제한에서 9개 이상 실제 대응쌍이 존재할 때, 각 사실로 확인된 반대편 블록을 singleton 후보로 인코딩한다. 원장에 동일한 NATIVE_EXACT 접촉쌍을 추가로 만들지 않는다. 같은 seed의 미검증 한쪽 Region을 중복 계정으로 유지하지 않는다. 전체 region/side 상한 초과 시 기존 증거를 유지.
4. `ContactCreditLedger` — 실제 F 배분·탄성·Q 계산·정밀도·전파 수치는 변경하지 않음. 증거 인코딩의 의미 변경을 나타내기 위해 `ALGORITHM_REVISION` 식별자만 originFix로 갱신; 과거 레거시 리비전과 저장 스키마 변경 없음.
5. 버전, README, NeoForge 모드 목록, Gradle JAR/Manifest를 `0.6.1-originFix`로 일치. 기존 `.github`, Apply, Wrapper, protected files, 의존성 설정은 변경하지 않음.

## 수치 및 회계 계약

- `F_event` = 기존 GameImpactCredit 승인 강도; A/B 측은 각 50%. 새로운 Native record나 ShapeMatch가 F를 새로 발행하지 않음.
- 기존 균등 시드 `F_side / uniqueSeeds`, 기존 `DamageModel.contactElasticityTransmissionPercent`의 alpha 0.4, G(F), Q cap, H/T/D/E, Damage/Stress/Deform, WAL CAS/Receipt 유지.
- 하나의 시드가 여러 반대쪽 접촉셀과 맞닿을 경우 현재 원장 정책대로 검증된 singleton 상대 집합 사이에 **동일한 시드 F 몫을 균등 분할**. 설계안에서 제안한 **기하 교집합 면적 가중 복수 재질 E 정책은 아직 미구현**; 이를 이미 적용한 것처럼 간주하거나 현재 시험 예상값을 면적 가중으로 수정하면 안 됨.
- 증명되지 않는 다른 Native 접촉 성분, 원본 100건 기록 제한, 상한에 걸린 면은 F 미확정 처리. 실패를 꼭짓점 블록에 무작위 재분배하지 않음.

## 사용자 검증 단계 및 주의점

1. 파일 하나만 `incoming/`에 올리고 기존 Apply를 사용. 기존 PDD JAR는 제거하고 `physical-destroy-deform-0.6.1-originFix.jar`만 설치. 백업 후 적용.
2. Java 21: `python3 scripts/check-duperfix-contract.py`; `python3 scripts/check-superfix-assertions.py`; `./gradlew clean test --no-daemon`; `./gradlew build --no-daemon`. CI는 기존 `Build`, `Test`, `Callback Contract Test`, `Apply` 보호 정책에 따라 사용자 실행. AI 검수에서 `@Test` 정적 집계는 원본 792 + 신규 6 = 798. **실제 통과 건수 미확정**.
3. 1차 게임: 같은 속도/질량으로 5×5/10×10 정면·한쪽 구멍·한쪽만 긴 면·Native 표본 100건 재현. 각 Credit의 `surfaceMatches`, `surfacePairChecks`, `completeSurface`, `candidateSets`, `CONTACT_UNPROVED`, Layer 0 F 수령 좌표, damage/commit 좌표와 F/Q PASS를 비교. 접촉 셀에 F가 있어도 물성 임계값 때문에 파괴되지 않을 수 있음.
4. 2차 게임: 서로 회전된 Plot, 다중 접촉·Copycat, 별도 접촉면, 분리/재접속과 저장 복구를 검사. 같은 장면에서 전 버전과 originFix의 PDD `R2-PERF`, `BRIDGE`, Stress, World/Plot Commit, `JOURNAL-IO`, TPS 및 장시간 메모리 점유 비교. DEBUG 진단 세션은 성능 비교 표본으로 사용하지 말 것.

## 명시적인 남은 기술적 위험

- **틱 분할 부재:** 현재 `SableContactRegions.audit`는 원본 승인 흐름 내 동기 처리. 100×100 면은 독립 Java 21 smoke에서 10,000개의 인증 대응쌍 / 10,000 실제 다각형 검증으로 탐색 가능했지만 큰 게임 면에서는 서버 틱 지연이 여전히 가능. 16,384 시드/측, 32,768 region 이벤트 상한 유지. 틱 간 커서를 추가하려면 source pose/owner generation/block+BE shape/material revision의 사건 스냅샷과 F/Q 봉인 지연이 함께 필요하며 이번에는 적용하지 않음.
- **디스크 I/O:** `WorldPersistentJournal` 동기 `FileChannel.force`와 checkpoint의 주 틱 대기는 그대로 남아 있음. 비동기 WAL은 durable ACK 이전 CAS·월드 mutation·receipt의 순서를 정확히 보존해야 하므로 API·실제 회복 스키마 검증 없이 변경하지 않음. 따라서 저장발 렉 완치 여부는 주장하지 않음.
- **Native 자료 한계:** 15-double POST_EFFECT에서 블록별 원본 solver impulse를 제공하지 않음. 새 면은 F_game event proxy의 **증명된 재분배**이지 실제 per-block native F가 아님. 동일 사건의 미관측 독립 접촉 성분까지 탐지할 수 없음.
- **형상 범위:** 기존 `fromRecord`의 법선 축 정렬·작은 관통/간격 제한 유지. 회전된 격자는 양측 탐색하지만 각 구조물의 접촉면이 로컬 축에서 크게 기운 비축정렬 충돌은 기존 안전 정책에 따라 미확정될 수 있음.

## 정적 예비 검사

- Java 21 소스 파싱: 246 파일, 0 syntax diagnostics. 외부 클래스패스 type-check가 아님.
- 수정한 `BilateralContactSurface`는 Java 21 **독립 스텁 타입 컴파일** 통과. 실제 NeoForge, Create, Sable compileClasspath 통과가 아님.
- 순수 원리 smoke: 5×5 = 25 matches/25 checks, 10×10 =100/100, 40×40 =1600/1600, 100×100 = 10000/10000, Native 100 anchors = 한 연결면 25 checks, 구멍 1개 = 24 matches. **실제 JUnit 실행/게임 TPS 시험이 아님**.
- 빌드·JUnit·processResources·jar·Actions·게임 모두 사용자 검증 전 `NOT_RUN`.
