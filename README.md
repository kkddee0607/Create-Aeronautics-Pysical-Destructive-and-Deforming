# PDD 0.6.0 deformFix — Physical Destructive and Deforming

Minecraft 1.21.1 · Java 21 · NeoForge 21.1.228 · Sable 2.0.6 · Create 6.0.10 · Aeronautics 1.3.2. 선택 호환: Copycats+ 3.0.11.

[통합설계서 1.0.9](.github/workflows/PDD-1.0.9-Integrated-Design-Specification.md) · [0.6.0 릴리스 검증](docs/verification/PDD-0.6.0-Release-Verification.md)

[0.6.0 BuildFix: CI 실패 원인 및 회귀 검증](docs/verification/PDD-0.6.0-BuildFix.md) — 파괴 한도 및 좌표별 공기 상태를 정확히 검증하도록 테스트를 수정. 게임 런타임·물성 수치·버전은 변경하지 않음.

[0.6.0 Fix: 방향 증거 및 인게임 검증](docs/verification/PDD-0.6.0-Fix.md) — 기존 접촉 형상 감사에서 블록별 이동 방향을 검증하고, 완전히 입증된 평면 내부에 한해 방향을 복원. 포화·모호한 증거에서는 기존 안전 제한 유지. 모드 버전과 저장 스키마는 0.6.0 유지.

[0.6.0 Fix2: 충돌 ID·Plot 생명주기·응력 비용 및 회귀 검증](docs/verification/PDD-0.6.0-Fix2.md). 물성·F/Q 공식 및 응력 상한은 유지하고 중복 작업과 일상 로그 비용을 줄인다. 상세 진단 시 JVM 옵션 `-Dpdd.diagnostics.fullTrace=true`를 사용한다.

[0.6.0 Fix3: 응력 완료 원장 압축·복구 및 반복 연산 절감](docs/verification/PDD-0.6.0-Fix3.md). 월드 백업을 먼저 만들고, 기존 v4 응력 원장은 삭제하지 않는다. 완료 원장만 증명 후 `closed-v1`로 이전한다. 실제 빌드는 사용자 검증 대기.

[0.6.0 Fix4: 대형 충돌 응력 예약·분할 처리·로그 경량화](docs/verification/PDD-0.6.0-Fix4.md). 한쪽 F 상한 32,768, 양쪽 합계 65,536개까지 동일 예약으로 보존. 128개 파생 상한/512 공통 작업량/원본 TTL 유지. 대형 원장만 압축하고 원래 v4 해시 및 CRC를 유지한다. 월드 백업 필수.

[0.6.0 moreFix: 시간 역행 복원·진단 연산 개선](docs/verification/PDD-0.6.0-moreFix.md). 기존 월드 전체 백업 후 적용한다. JAR 파일명은 `physical-destroy-deform-0.6.0-moreFix.jar`, 시작 로그는 `revision=moreFix`이다. 기존 Fix4 JAR와 동시에 설치하지 않는다.

[0.6.0 deformFix: 접착된 Plot 벽 변형 안전 판정](docs/verification/PDD-0.6.0-deformFix.md). 동일 Plot의 검증된 Create Super Glue/Simulated Honey Glue 영역은 그대로 두고, 실제 엔티티와 구분한다. Sable의 18방향 연결성으로 이동 전후 연결을 검증한다. 기존 버전 대신 `physical-destroy-deform-0.6.0-deformFix.jar` 하나만 설치한다. 시작 로그: `revision=deformFix`.

## 기능

- 원본 Sable 콜백과 solver를 보존하는 F-first 충돌 Q, H/T/D/E 재질, 고정소수점 누적 손상 및 파괴.
- World와 Plot의 bounded 6면 Q-stress, 깊이별 합류, 원본 Q 예약과 stable ApplyId, 저장·재개 및 중복 차단.
- 블록별 접촉·부호 증거와 원래 F/층, 파괴 우선 K(D) 이동 판정, 역층 순서, 최대 2축 1칸 이동; 두 위치 CAS, 원본 setter, 손상/변형 이력 이전, 이후 Plot HeatMap 완료 확인.
- 확인된 이동 차단 시 원래 plastic Q 내에서만 추가 손상·파괴, shortfall 및 unused Q 기록, 불확실한 중단은 재실행하지 않고 격리.
- Copycats+ 단일/다중 재질과 형상 보정 T, 재질 revision 및 기존 손상 보존, 안전한 장식 BE의 NBT 이전, 기존 드롭 계약. 기능성/미확인 BE는 VETO.

## 적용 및 검증

전체 소스 ZIP을 기존 저장소 `incoming/`에 넣고 Apply를 실행한다. Java 21에서:

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

실제 Gradle/JUnit, GitHub Actions 및 인게임 검증은 사용자 실행 전에는 NOT_RUN이다. 검증 로그의 주요 태그: `[PDD-0.6.0]`, `[PDD-STRESS-LEDGER]`, `[PDD-STRESS-APPLY]`, `[PDD-MOVE]`, `[PDD-DEFORM]`, `[PDD-DEFORM-Q]`, `[PDD-DEFORM-BLOCKED]`, `[PDD-COPYCAT]`, `[PDD-PERSIST]`. 상세 절차는 검증 문서를 참조한다.

deformFix 기준 moreFix ZIP은 main에서 확인한 커밋 `2723603a965b4b42ff9c806339819497b87d836f`에 업로드된 ZIP과 Git blob SHA가 일치한다. 기존 저장 데이터는 백업한 뒤 사용하고 구버전으로 다운그레이드하지 않는다. 안전하지 않은 소유권, 재질, 이동, 연결성 또는 HeatMap 상태는 허용하지 않는다.
