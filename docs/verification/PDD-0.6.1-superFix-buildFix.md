# PDD 0.6.1 superFix-buildFix — Java 21 람다 포착 컴파일 오류 수정

**기준:** GitHub `main` SHA `02063617f0c7d92a10c7e0484a9fa68f15ab776f`, `PDD-1.0.9-Integrated-Design-Specification.md`, 이전 `0.6.1-superFix`.
**실제 실패:** GitHub Actions `PDD Manual Build` run `45517142349`, `Test and Build PDD JAR` job `138879394711`, 2026-11-25 (UTC). `:compileJava` 실패, `:compileTestJava`, JUnit, `:jar`, `build` 미실행.

## 최초 실패 원인

`SableContactRegions.java:361` 의 `ContactFacePatch.commonSurfacePoint(..., p -> ...)`가 변경되는 `for (int side=0;side<2;side++)` 루프 변수 `side`를 람다 안에서 2회 참조하여 Java의 **final 또는 effectively final** 포착 규칙을 위반했다. 첫 두 컴파일 오류는 같은 원인이고, Gradle·Sable·NeoForge 버전 문제가 아니다.

## 수정

- 반복당 `final int pairedSide=side`를 캡처하고 람다 내 `offsets[side]`와 `offsets[1-side]`만 각각 `offsets[pairedSide]`, `offsets[1-pairedSide]`로 교체.
- 원래 `side`를 사용하는 외부 검사 예산, target `proof.verify` 및 좌표/물리 변환은 수정하지 않음. 불변 인덱스 값은 각 반복에서 `side`와 같아서 호출 순서, 대상·반환값, F/Q가 동일함.
- `PhysicalDestroyDeform.BUILD_VERSION`, 시작 로그, Gradle classifier와 PDD-Revision을 `0.6.1-superFix-buildFix`로 표시. **모드 버전 0.6.1과 저장 스키마 6 유지**.
- 기존 전체 JUnit `@Test` **781개 선언 보존**. 이 변경은 자바 컴파일의 이름 바인딩 결함이므로 테스트 기대값을 맞추기 위한 편집·삭제는 하지 않음. 면 검증 운영 경로의 기존 JUnit은 보존.

## 동일 유형 재발 방지 규칙 (향후 PDD 모든 Java 수정에 적용)

1. **Java 구문 파싱만으로 빌드 가능 판정 금지.** `JavacTask.parse()`는 capture/effectively final 오류를 발견하지 못한다.
2. 모든 `->` 및 `::` 변경 위치에서 **람다·익명 클래스가 포착하는 지역변수**를 확인한다. `for` 인덱스, 루프 중 재할당 변수, 누적 값, 조건별로 변경되는 변수는 직접 포착 금지. `final` 지역 변수 또는 불변 스냅샷에 복사 후 사용.
3. 수정된 람다를 같은 Java 21 컴파일러의 독립적인 최소 재현 조각으로 **실제 타입/흐름 분석**할 수 있을 때 검증한다. 파싱 및 grep 결과를 완전 컴파일이라고 표시하지 않는다.
4. 수정한 호출자의 메서드/생성자/제네릭/람다 함수형 인터페이스 반환타입을 실제 시그니처와 대조한다. 기존 공개 API 제거 시 모든 호출자·테스트 검색.
5. Main/Test 모든 새·수정 파일을 대상으로 실행 가능한 메서드 계약, null, 예외, 기존 테스트 수·Assertion, 기존 Gradle/NeoForge 클래스패스를 검토한다.
6. 실제 `compileJava` / `compileTestJava` / JUnit / `build` 성공은 사용자 GitHub Actions 실행 결과로만 확정한다. 1차 실패 후 남은 테스트 실패 가능성을 따로 명시한다.

## 사용자 재검증

```sh
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

확인 순서: `compileJava` → `compileTestJava` → JUnit 781개 선언의 실제 실행 결과 → `processResources` → `jar` → `build`. 이후 기존 Callback Contract / Apply 및 인게임 전파 회귀 확인.

**예상 산출물:** `physical-destroy-deform-0.6.1-superFix-buildFix.jar`.
**AI 검증 범위:** 원본 Git Blob/ZIP 복원·정적 검사·Java 21 독립 람다 캡처 검사·ZIP 무결성. 실제 Gradle/JUnit/Actions/게임 실행은 AI가 수행하지 않음.
