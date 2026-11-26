# PDD 0.6.1-rogFix — 구현 및 검증 인계

## 기준 및 배포 상태
- 원본: kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming main `f941d8ea4936b2268cad59c92c56a3df480bcfba`, tree `8b8d41fc5fb20cd4db9338b8d928109511c6de15`, 411 blobs / 5,443,313 bytes, truncated=false. 원본 finalLastFix ZIP 복원 후 Git tree 재계산 일치. 통합설계서 1.0.9 및 Apply 보호 경로 보존.
- 0.6.1-rogFix 전체 소스 검증 후보. 구현 범위는 아래와 같다. 실제 Gradle, JUnit, Actions 및 인게임 검증은 사용자 수행. 전체 0.6.1 기능 완료 판정은 보류한다.
- 직전 버전의 실제 Actions 성공: https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/45823758100 (이번 ZIP 검증 결과가 아님).
- 원격 변경 없음. Java 21, NeoForge 21.1.228, ModDev 2.0.78, Sable 2.0.6, companion 1.6.0, JUnit 5.11.4, SLF4J 2.0.16 및 기존 의존성 범위 보존.

## 원인 및 실제 수정
- 기존 로그: 127 Credit의 F/Q 보존 성공에도 미해결 F 가중 비율 35.87%, 양측 복원 6건/264 관계, episode 132 Plot effective seed 66개. 상세 normal sweep 표본 14건 모두 null. 이는 모든 충돌의 실패율이 아니다. Stress에는 스냅샷 무효화 후 계획 0개/1201회 방문 사례 존재. 분리 상태 이전 104건과 실제 변형 1건 확인(직전 버전).
- `BilateralContactSurface`: 먼저 양측 투영 다각형 교차를 계산하고 부호 있는 깊이 범위로 클리핑. 평행면은 기존 EPS와 침투 한도, 경사면은 양의 간격만으로 면적이 생기는 것을 방지. 복합 형상의 같은 셀/바깥 셀에 가려진 부분을 사각형 조각 단위로 제거. 실제 외부면 조회가 불명확하면 UNKNOWN, 독립적으로 완료된 성분은 유지. 후보 범위에 검증된 법선 깊이 여유 반영. 이벤트 내부 외부면 캐시. 조각/탐색 비용 제한 및 초과 시 보수적 처리. `SWEEP_*` 거절 이유를 구분하되 PRE/Effect의 엄격한 평면 sweep 증명은 유지.
- `SableContactRegions`: 기존 검증 캐시를 공유한 외부 셀 조회, UNKNOWN 구분, 동일 확정 집합의 예약 중복 계산 감소, 최대 매칭 edge work cap 262144. cap 도달 시 미해결 후보 상한을 보수적으로 계산(부분 매칭 하한 사용 금지). 기존 두 측의 F 절반, 물성 E 정책, G(F), Q, H/T/D/E 및 고정 수치 불변.
- `ContactCreditLedger`, `RealFixRuntimeBridge`: 실제 승인/봉인 이후 측별 초기 F 및 고유 씨앗, 원본 Native record, 직접/복원 출처, 상대 E 및 보류 결과를 creditId로 연결. 내부 전파와 초기 F를 구분. 미승인 진단이 피해 권한을 만들지 않음. preview policy revision 갱신, 기존 저장 schema 유지.
- `StressRuntime`, `StressResidualWait`, `StressProbeCursor`: 활성 탐색과 의존성 대기, 종료 후 잔여 Q 정산 대기를 구분. 원점/계획 전달 완료된 SEALED 작업만 원래 TTL까지 잠들게 한다. generation 변경·의존성 변화·만료 시 재활성화. 이미 계획된 ApplyId와 Q 소유권 보존. 불필요한 활성 스냅샷/탐색 참조 해제. TTL 연장 없음, 신규 Q 발행 없음. 기존 스냅샷 무효화 정책 자체는 보수적으로 유지.
- `RogTrace`: 기존 R01~R13 호환, 공통 상세 줄 수와 문자 예산, 집계용 예약 예산, 사건 선택, 씨앗 분할 출력. 누락/잘림 카운터. 진단 비활성화 시 새 상세 탐색/출력 준비 생략. 일반 PDD 로그와 집계 자체는 동일 절대 상한 대상이 아님.
- `build.gradle`: 0.6.1-rogFix 버전, JUnit XML 명시, 전체 JUnit 결과 total/passed/failed/skipped 출력. 기존 실패 처리, JUnit 플랫폼 및 classpath 변경 없음. README, PhysicalDestroyDeform, neoforge.mods.toml 버전 갱신. `check-duperfix-contract.py`와 `check-superfix-assertions.py`를 새 계약 및 선언 수에 맞게 갱신.

## API 근거와 제한
Sable 2.0.6 내장 Rapier JAR의 javap: Rapier3D.clearCollisions(long):double[] (native, package-private), getPose(long,int,double[]); RapierPhysicsPipeline.physicsTick(double), readPose(ServerSubLevel,Pose3d), private processCollisionEffects(). LevelPlot.contains(ChunkPos), toLocal(ChunkPos), getChunkHolder(ChunkPos), getEmbeddedLevelAccessor 확인. 정확한 과거 solver substep 자세/전체 블록 쌍 제공 API는 확인되지 않았다. 기존 ModifyExpressionValue(require=1), 원본 반환 및 콜백 계약 보존. 신규 JNI/solver 수정 없음. Create 6.0.10/Aeronautics 1.3.2/Copycats 3.0.11의 기존 호출 경로 유지; 모든 외부 의존성의 실제 컴파일 성공은 미확인. SLF4J 포매터는 기존 의존성 이용.

## 사전 검증 결과 (실제로 수행한 검사만)
- Java 21 JavacTask.parse: main 129 + test 133 = 262개, syntax errors=0.
- 제한적 javac --release 21: 명시 production 9개 및 필요한 전이 소스, test 11개 타입 검사 exit=0. Minecraft/일부 PDD 외곽 의존성, SLF4J/JUnit assertion 시그니처 대역 사용. RealFixRuntimeBridge의 전체 NeoForge 타입 검사는 미수행(문법 및 호출 검토). 대역은 ZIP에 포함하지 않는다. JUnit 실행 아님.
- 두 Python 정적 계약 스크립트 실제 실행 PASS. 826 -> 841 @Test 선언, 신규 15, 삭제/비활성화 0. 테스트 파일 133개. 실행 개수는 사용자 JUnit XML로 확인해야 한다.
- 신규 `RogFixSurfaceTest` 7개(경사 깊이 띠, 정확한 모서리, 틈, 내부면 가림, 부분 가림, 외부 셀 좌표, 9x9 81씨앗), `RogFixResidualWaitTest` 4개(만료 전 방문 없음, generation 변경, 의존성 wake 1회, 세션 종료), `OriginLastFixContactTest` +1(매칭 예산 상한), `RogTraceTest` +3(공유 예산/예약/zero). `StressRuntimeProtocolTest` 기존 테스트에 미전달 원점/계획이 있을 때 대기 금지 조건 추가. 수치 정책 자체는 변경 없으므로 기존 유효 기대값 보존.
- 독립 산술 검토: 경사 0.1 rad, dy=-0.02인 단위 면의 접촉 띠 면적 0.19933288846518477, dy=0 및 +0.01은 0. 한쪽 F=50000, N=25,R=0 → seed 2000, 전체 50000; N=25,R=5 → seed 1666.6667, 확정 41666.6667, 보류 8333.3333; N=4,R=6 → seed 5000, 확정 20000, 보류 30000; N=0,R=0 → 전체 보류. 감쇠 이전 예시이며 실제 production 실행 아님.
- 실제 Gradle clean test / build / compileJava / compileTestJava / processResources / jar / JUnit / Actions / 게임: 이번 버전 미검증. 프로젝트 규약에 따라 사용자 실제 실행을 대기한다. 실제 red-green JUnit을 수행하지 않음.

## 남은 위험 및 미구현 범위
- 정확한 Native substep 자세가 미확인이다. 회전·접선 이동을 포함하는 swept geometry 권한은 추가하지 않았다. 1/32 Anchor 및 법선 제한은 유지하며, 지난 로그와 같은 큰 불일치에서 접촉면 복원 실패가 남을 수 있다. 복합 면의 탐색 연결은 기존 box seam 기반이므로 실제 클리핑된 다각형의 연속성을 완전히 증명하지 않는다. 가짜 접촉 및 누락 0건은 실제 시험 필요.
- 기존 F/Q·변형·파괴·Lineage·Copycat·영속화·네트워크 경로 보존은 해당 기능의 완전 구현/검증과 동의어가 아니다. 119개 시나리오 전체 신규 JUnit 작성 아님. 부분 스냅샷 무효화 후 독립 경로 재계산은 미구현. HeatMap owner 대기열 전체 재구성, 장기 Receipt 압축, 신규 R14~R16/공통 schema2/testCaseId, 전 단계 네트워크 송수신/Drop/Lineage 구조화 로그는 미구현 또는 기존 진단 유지. 외부 Sable 패킷/Catwalk 오류 원인 미확정으로 외부 모드 변경 금지 유지. 실제 성능 향상 수치 없음.
- 이전 종합 계획 전체 충족이라고 선언하지 않는다. 정확한 접촉 범위, merged/reassembly, Copycat NBT/Glue/Drop, 강제 종료 복구 및 성능은 실증 필요.

## 사용자 검증 절차
1. 전체 ZIP을 기존 incoming 경로에 등록하고 Apply 실행. 기존 보호 파일(.github, gradlew, gradlew.bat, gradle, incoming)은 수정하지 않는다.
2. `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon` 또는 기존 Manual Build 및 Callback Contract Test. `PDD JUnit: total=... passed=... failed=... skipped=...` 및 build/test-results/test XML을 보존한다. 산출물 예상 `physical-destroy-deform-0.6.1-rogFix.jar`.
3. JVM 진단 인수(기존 속성 + 신규 속성):
```
-Dpdd.rog.enabled=true
-Dpdd.diagnostics.mode=DEBUG
-Dpdd.rog.maxPerTick=512
-Dpdd.rog.maxCharsPerTick=262144
-Dpdd.rog.seedLimit=128
-Dpdd.rog.summaryTicks=100
```
필요시 재현 사건에 `-Dpdd.rog.traceEpisode=<episode 번호>`를 사용. 일반 로그 포함 절대 출력 상한은 아님.
4. 세션 A: 새 손상 없는 동일 재질로 1x1,3x3,5x5(3회),9x9 정면, 5x5 부분 겹침/중앙 구멍, 실제 모서리/꼭짓점, 경사/회전, Plot-Plot, 지속 접촉 및 실제 분리 후 재충돌. 완전 정렬 동시 면 접촉에서 각 측 1/9/25/81개 실제 유효 씨앗; 비접촉 제외, 각 측 F 절반 및 F/Q 보존. 질량/속도/초기 자세를 기록. 직렬·합류 Stress, 미로드/스냅샷 변경 시험 후 원래 1200틱 TTL 이상 서버 유지. `STRESS_WAIT_BEGIN`, `STRESS_WAKE`, `STRESS_QUEUE`, `STRESS_JOB_SUMMARY`에서 잔여 대기 방문 억제 및 정산 1회 확인.
5. 세션 B: 이동 가능/차단 변형, 파괴·Drop, 단일/다중 Copycat 및 재질 교체, Plot 분리·합체·재조립/BE/Glue, 미완료 작업 저장/재실행 후 상태·Receipt·Q 확인. 이전 세션 로그를 덮어쓰기 전에 저장.
6. 제출: 전체 latest.log(재시작 전후 포함), Actions/JUnit XML, JVM 인수, 시험 순서 및 구조물 배치, JAR 버전, 오류 시 crash report. 핵심 로그 `NATIVE_CAPTURE_AUDIT`, `NATIVE_CONTACT_RECORD`, `SWEEP_*`, `FACE_DEPTH_CLIP`, `CONTACT_COVERAGE`, `CONTACT_SEED_COORD_SUMMARY`, `CONTACT_SEED_COORDS`, `CONTACT_SIDE_SEAL`, `CONTACT_SEED_EFFECTIVE_SUMMARY`, `CONTACT_SEED_EFFECTIVE`, F/Q invariant, `DIAGNOSTIC_COVERAGE_SUMMARY`, Stress queue/wake. credit ID 및 측별 local 좌표로 연결. offset/end/total의 완전성 및 truncated/suppressed를 점검. 로그 누락은 PASS가 아니다. 5x5의 중앙 포함 25개 초기 씨앗을 직접 확인하기 전까지 0.7.0 진행 금지.
