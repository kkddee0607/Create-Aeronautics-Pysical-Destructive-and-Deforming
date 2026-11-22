# PDD 0.6.1 fix — 구현 및 검증 계약

기준 main: `1d9408600334fdba3013106f11dca75074621404` (349개 파일). IDS 1.0.9. 실제 0.6.1 인게임 로그에서 최초 F의 44.55%/Plot 간 84.73%가 unresolved였다. tick 72371에 World/Plot 64 + Plot/Plot 36개의 native 기록이 포화되었고 World/Plot은 DUPLICATE_EPISODE, 교차 전파가 없었다.

승인 설계: 기존 0.6.1의 내부/자기 탄성 곡선은 보존하고 상대 직접 접촉 감쇠 강도만 40%로 변경. `t_c(E)=1-0.4(1-t(E))`. 정확한 양측 대응 또는 완전한 상대 후보의 E 일치 증거만 사용. 불확실 재질 추정 금지. 지속 접촉 캐시는 현재 native 접촉과 형상/세대/재질/압축 방향을 재검증하는 보조 증거만 제공. 기존 Sable consumer, solver, 물리 콜백은 변경하지 않는다.

## 구현 및 회귀 검토

- 접촉 후보/F 배정 수정 및 기존 테스트 수치 조정. 동일 native 기록의 완전한 상대 후보 또는 양측 대응 평면 증거를 사용한다. 다른 접촉의 재질을 대입하지 않는다.
- 지속 접촉 그래프: cap에 도달한 배치에서도 반환된 개별 양성 기록을 독립 검증한다. 현재 owner, generation, native point/normal, 형상 및 모든 pre/effect pose를 확인한다. 완전한 큐브만 실제 OBB 접촉 검증을 허용한다. history TTL=20 tick, cap=4096; 이력 단독으로 간선 승인 금지.
- 동일 stamp의 모든 FSM 결정 후 그래프 구성, 승인된 직접 충돌을 경계에서 제외, 기존 순서로 plan/offer. 지속 접촉의 후반 substep 접근만으로 독립 Credit을 승인하지 않으며, 최초 substep의 독립 접근 또는 확인된 분리 후 접근은 별도 사건으로 존중한다.
- 기존 owner-local Q-stress, topology transfer/WAL, Glue/BE/Copycats, 변형, 물성 revision 및 저장 형식은 변경하지 않았다. ContactCreditLedger의 새 revision은 충돌 계획/진단 식별자이며 기존 DamageModel 및 StressReservationJournal revision은 유지한다.

## 수치 및 API

| E | 기존 t(E) | 직접 접촉 t_c(E) |
|---:|---:|---:|
| 0 | 100% | 100% |
| 25 | 70.21% | 88.08% |
| 50 | 45.05% | 78.02% |
| 75 | 25.63% | 70.25% |
| 100 | 15% | 66% |

원래 각 측 F 100이라면 A의 E=100, B의 E=0일 때 A=100/B=66. 양측 E=100이면 각각 66. 후속 내부 감쇠와 자기 Q 보정은 기존대로다. E=100인 로컬 중간 노드와 E=100인 경계 수신 노드의 F=100 직렬 예제: 로컬 할당 50, 내부 감쇠 42.5, 경계 감쇠 2.55, 후속 할당 4.95; 합계 100. F/Q를 섞거나 새 owner의 별도 Q cap을 발급하지 않는다.

Sable 2.0.6의 `PhysicsPipeline.readPose(ServerSubLevel,Pose3d)`, `getLinearVelocity(PhysicsPipelineBody,Vector3d)`, `getAngularVelocity(PhysicsPipelineBody,Vector3d)`, nested Rapier 2.0.6의 `Rapier3D.clearCollisions(long):double[]`를 실제 JAR에서 확인했다. 기존 telemetry/consumer를 사용하며 clearCollisions를 재호출하지 않는다. native record에는 source substep 및 cause ID가 없으므로 인과/형상이 확인되지 않는 경우 unresolved/보류한다. 새 외부 의존성 없음. 기존 노드 상한 32768/측, cross depth 4, edges 128, owners 8 유지. 캐시는 세대 무효화, 분리, 서버 종료 시 정리한다.

## 검증 계약

기존 637개 JUnit 보존; 신규 16개, 합계 예상 653개 `@Test`. 실제 Gradle/JUnit, Actions, 게임 실행은 사용자 수행. 5노드 E=0 직렬 경로 50+25+12.5+6.25+6.25=100. 현재 native 기록이 없거나 실제 대응/인과가 불확실하면 보류. 포화는 반환 기록 자체가 무효라는 증거가 아니다.

### 적용

월드 전체 백업. 기존 GitHub 저장소 `incoming/`에 전체 ZIP 하나만 업로드하고 Apply 실행. Java 21에서 `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon`. 기존 `build-gradle.yml` artifact PDD-0.6.1에서 `physical-destroy-deform-0.6.1-fix.jar` 하나만 설치. startup `revision=0.6.1-fix` 확인. 기존 JAR와 병용 금지. 상세 테스트 시 `-Dpdd.diagnostics.fullTrace=true`.

### 세션 1 — 접촉 탄성 및 회귀

1. 동일 질량/속도/접촉 크기로 E=0, E=100 한쪽, 양쪽을 비교. A/B 최초 F의 상대 재질을 확인. `alpha=0.4`, 비감쇠 측 F 유지, E=100 상대 측 66% 유지. 자기 Q 후의 최종 손상률과 구분한다.
2. 넓은 면에서 동일 E 블록 및 혼합 E 블록을 비교. 동일 재질 후보의 F 회복 확인; 실제 모호한 혼합 E 기록은 unresolved 유지. 이전 실험과 `candidateF/assignedF/unresolvedF` 비율을 비교(고정 비율 보장 아님).
3. 금 World/Plot 벽, 탄성 바퀴 도로, Copycat/Glue 변형 회귀. `[PDD-F-INVARIANT] FStatus=PASS QStatus=PASS` 및 기존 변형/응력 결과, static contact에서 반복 credit 없음 확인.

### 세션 2 — 연쇄 충돌과 저장/재접속

1. E=0 P2와 정적 S1을 실제 면 접촉으로 정지시킨 뒤 다른 Plot P1로 P2를 압축. 직접 P1/P2 credit에서 `[PDD-CROSS-OWNER] admitted>0`, `[PDD-CHAIN-LEDGER] crossEdges>0, actualOwners>=3` 및 World S1 할당을 확인. 같은 원인의 P2/S1은 별도 Q 발행 금지. 파괴 여부만으로 판단하지 않는다.
2. 간극을 둔 S1, 역방향, 회전 face, 기존 접촉을 분리한 뒤 독립 충돌을 비교. 간극 유지 시 교차 간선 금지, 별도 독립 충돌은 자체 credit 허용. 미입증 접촉을 성공으로 분류하지 않는다. 기록이 없거나 모호하면 `[PDD-CROSS-OWNER-AUDIT]`의 사유를 확인한다.
3. 손상 Plot 분리/재조립, Glue/Copycat BE와 재질, 저장/종료/재접속 후 상태 및 추가 충돌 검증. 기존 0.6.0/0.6.1 상태 이전, persistence, mutation/stress 태그와 예외를 확인한다.

제출: 두 세션의 `latest.log`와 실제 build/test/Apply 결과. 필요한 주요 로그는 `[PDD-EPISODE]`, `[PDD-CONTACT-PATCH]`, `[PDD-CONTACT-ELASTIC]`, `[PDD-CROSS-OWNER]`, `[PDD-CROSS-OWNER-AUDIT]`, `[PDD-CHAIN-LEDGER]`, `[PDD-F-INVARIANT]`, `[PDD-Q-SEAL]` 및 기존 persistence/transfer 로그. 필수 gate G-SPLIT-01, G-CC-LINEAGE, G-CONTACT-ELASTIC, G-CROSS-OWNER와 0.6.0 회귀는 실제 테스트 전에는 NOT_RUN.
