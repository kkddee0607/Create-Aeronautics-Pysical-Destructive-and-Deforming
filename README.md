# PDD 0.6.1-duperFix-buildFix — Physical Destructive and Deforming

Minecraft 1.21.1 · Java 21 · NeoForge 21.1.228 · Sable 2.0.6 · Create 6.0.10 · Aeronautics 1.3.2. 선택 호환: Copycats+ 3.0.11.

[통합설계서 1.0.9](.github/workflows/PDD-1.0.9-Integrated-Design-Specification.md) · [0.6.0 릴리스 검증](docs/verification/PDD-0.6.0-Release-Verification.md)

**현재 배포판: 0.6.1-duperFix-buildFix.** 이전 duperFix의 양측 충돌면 복원 알고리즘은 유지하면서 GitHub Actions #45583354353에서 발생한 `compileTestJava`의 `DamageModel` import 누락을 수정했다. 기존 실행에서는 `compileJava`와 `processResources`는 통과했지만 JUnit·JAR는 실행되지 못했다. 이번 수정판의 실제 빌드 성공은 아직 미검증이다. 물성 수치, F/Q, WAL, Sable 원본 콜백은 변경하지 않았다.

**현재 설치 대상:** `build/libs/physical-destroy-deform-0.6.1-duperFix-buildFix.jar` **한 개만 설치한다.** 기존 PDD JAR와 동시 설치하지 않는다. NeoForge 인게임 모드 목록과 서버 시작 로그는 `0.6.1-duperFix-buildFix`를 표시한다. [BuildFix 검증·JUnit 계약 검토](docs/verification/PDD-0.6.1-duperFix-buildFix.md) · [duperFix 접촉면 설계 및 검증](docs/verification/PDD-0.6.1-duperFix.md).

**아래는 이전 버전의 변경 기록이다.** 과거 ZIP/JAR 설치 지시는 현재 버전에 적용하지 않는다.

[0.6.1 superFix-buildFix2 JUnit 수정·CI 계약 점검](docs/verification/PDD-0.6.1-superFix-buildFix2.md). 기준 main SHA `3fef89e0636f51e969a6e334b42cb9994c5fb250`. 2026-11-25 Actions: 기존 Java 컴파일 성공, JUnit 781건 중 1건의 상충된 테스트 기대값 수정. 기존 superFix 양측 접촉면/F/Q/저장 알고리즘은 변경하지 않음. 당시 설치 대상은 `physical-destroy-deform-0.6.1-superFix-buildFix2.jar`였다.

[0.6.1 superFix-buildFix 컴파일 수정 및 재발 방지](docs/verification/PDD-0.6.1-superFix-buildFix.md). 기준 main SHA `02063617f0c7d92a10c7e0484a9fa68f15ab776f`. 당시에는 `physical-destroy-deform-0.6.1-superFix-buildFix.jar`만 설치하도록 안내했다. 기존 접촉면 검증 알고리즘·F/Q·저장·JUnit 기대값은 변경하지 않는다.

[0.6.1 superFix 접촉면 복구 및 회귀 검증](docs/verification/PDD-0.6.1-superFix.md). 기준 main SHA `eb25244f133442550449ece88b3ff87128ad84fc`. 기존 `0.6.1-optiFix3-buildFix`의 물리·저장·진단 계약은 보존하고, 양측 동일 프레임 접촉 증명이 확인된 실제 면 셀만 추가 F를 배정한다. 이전 버전의 구현·설계 검증 내용이다. 당시 설치 대상은 superFix-buildFix2였다.

[0.6.1 optiFix3-buildFix 컴파일 수정 안내](docs/verification/PDD-0.6.1-optiFix3-buildFix.md). 이번 수정 기준 main SHA `57e19941da4716c78745ab15813935f6f690d97a`.

[0.6.1 optiFix3 검증 및 인게임 절차](docs/verification/PDD-0.6.1-optiFix3.md). 원래 optiFix3 개발 기준 SHA `077cc8769b170732ee7668bfdfbe6e3efd442a68`. 기존 월드를 백업한 뒤 수정판 `physical-destroy-deform-0.6.1-optiFix3-buildFix.jar` 하나만 사용한다. 기존 데이터 디렉터리는 유지한다. [optiFix2 검증](docs/verification/PDD-0.6.1-optiFix2.md). [spreadFix 검증](docs/verification/PDD-0.6.1-spreadFix.md) · [이전 0.6.1 fix 검증](docs/verification/PDD-0.6.1-Fix.md).

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
- 실제 양측 접촉 블록의 탄성 감쇠 강도는 기존 곡선의 40%이며 단일 원장에 차감한다. 완전하게 입증된 후보 집합의 E가 일치할 때만 대응 재질로 사용할 수 있다. 불확실한 F는 unresolved로 닫는다.
- 동일 물리 프레임의 검증된 지속 압축 접촉을 별도 owner 전파 간선으로 사용한다. 독립 충돌로 승인된 경계 및 인과관계가 불명확한 경계는 제외한다. 접촉 이력은 현재의 물리·형상 증거를 대신하지 않는다.
- optiFix3는 기존 물리·저장 포맷·WAL fsync를 유지하면서 World/Plot WAL 채널을 재사용하고, manifest가 내구화된 **이후** 구세대 파일 정리를 제한된 백그라운드 작업으로 분리한다. Stress 저장 단계·manifest 세부 I/O를 계측하고 최대 절대 미확정 F 진단을 보완한다. 고위험 비동기 WAL/손상 지연 커밋은 **구현하지 않았다**. 상세 계약과 기존 로그 비교: [optiFix3 검증](docs/verification/PDD-0.6.1-optiFix3.md).

## 적용 및 검증

전체 소스 ZIP을 기존 저장소 `incoming/`에 넣고 Apply를 실행한다. Java 21에서:

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

실제 Gradle/JUnit, GitHub Actions 및 인게임 검증은 사용자 실행 전에는 NOT_RUN이다. 검증 로그의 주요 태그: `[PDD-0.6.0]`, `[PDD-STRESS-LEDGER]`, `[PDD-STRESS-APPLY]`, `[PDD-MOVE]`, `[PDD-DEFORM]`, `[PDD-DEFORM-Q]`, `[PDD-DEFORM-BLOCKED]`, `[PDD-COPYCAT]`, `[PDD-PERSIST]`. 상세 절차는 검증 문서를 참조한다.

deformFix 기준 moreFix ZIP은 main에서 확인한 커밋 `2723603a965b4b42ff9c806339819497b87d836f`에 업로드된 ZIP과 Git blob SHA가 일치한다. 기존 저장 데이터는 백업한 뒤 사용하고 구버전으로 다운그레이드하지 않는다. 안전하지 않은 소유권, 재질, 이동, 연결성 또는 HeatMap 상태는 허용하지 않는다.
