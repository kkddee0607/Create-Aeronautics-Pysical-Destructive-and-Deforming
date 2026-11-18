# PDD 0.6.0 — Physical Destructive and Deforming

Minecraft 1.21.1 · Java 21 · NeoForge 21.1.228 · Sable 2.0.6 · Create 6.0.10 · Aeronautics 1.3.2. 선택 호환: Copycats+ 3.0.11.

[통합설계서 1.0.9](.github/workflows/PDD-1.0.9-Integrated-Design-Specification.md) · [0.6.0 릴리스 검증](docs/verification/PDD-0.6.0-Release-Verification.md)

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

기준 main SHA: `7e64189d2758c81e45d7d71f066c90e51adc127d`. 기존 저장 데이터는 백업한 뒤 사용하고 구버전으로 다운그레이드하지 않는다. 안전하지 않은 소유권, 재질, 이동, 연결성 또는 HeatMap 상태는 허용하지 않는다.
