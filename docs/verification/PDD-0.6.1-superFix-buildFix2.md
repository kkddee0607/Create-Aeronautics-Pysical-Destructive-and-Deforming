# PDD 0.6.1 superFix-buildFix2 — JUnit 논리 오류 및 CI 계약 검증

- 기준 GitHub main: `3fef89e0636f51e969a6e334b42cb9994c5fb250` (2026-11-25 UTC).
- 직전 성공: Apply PDD ZIP run `45519409292`.
- 최초 실패: PDD Manual Build run `45519469331` (job `138887371212`), `:test`.
- 정확한 결과: `compileJava`, `compileTestJava`, `processResources`, `processTestResources`, `testClasses` 모두 성공; JUnit 781개 실행, 1개 실패. `jar`/`build`는 테스트 실패로 실행되지 않았음.
- 실패 테스트: `ContactFacePatchTest.actualFourNativeCornerRegionsRecoverEntireFiveByFiveNetheriteFace`, 원본 78행. 같은 `patch.covers(new Vec(.5,65,2.5))`에 대해 `assertTrue`와 `assertFalse`를 연속 요구한 모순.

## 1차 수정 (기존 테스트 의미 복구)

`ContactFacePatch.Result.covers`는 동일 평면의 검증된 다각형과 좌표가 속한 **블록 셀의 양의 면적 겹침**을 의미한다. 경계 셀 중심이 다각형 경계 위에 있어도 실제 셀 면적이 겹치면 `true`이며, 접촉면 바깥 셀은 `false`다. **이동 방향용 엄격한 내부 판정은 별도 `planarInterior`로 검사한다.** 따라서 기존의 `assertTrue`를 보존하고, 모순된 `assertFalse`는 실제 면 밖인 `(5.5,65,2.5)` 거절 검사로 변경했다. 이미 있는 `planarInterior`의 `(0,2)` 배제 검사와 `(2,2)` 포함 검사는 그대로 보존했다.

물리·좌표·소유자·반대편 접촉 증거·F/Q·상태 이전·WAL의 어떤 알고리즘도 바꾸지 않았다. 결과를 맞추기 위해 단순 삭제하거나 수치 변경하지 않았다.

## 2차 검사 — GitHub Actions/Apply 계약

| 파일 | 기능·계약 | 확인/조치 |
|---|---|---|
| `.github/workflows/apply-pdd-zip.yml` | `incoming/`에 ZIP 1개, 소스 루트 자동 식별, `.github`, `incoming`, `gradlew*`, `gradle/` 보호, 나머지는 완전 동기화 | 원본 보존; ZIP 루트 1개 및 보호 파일 Git Blob 기준 대조 |
| `.github/workflows/build-gradle.yml` | Java 21, Gradle Wrapper, `test` 성공 후 `build`, 산출 JAR artifact | 원본 보존. `build/libs/*.jar`는 새 classifier JAR 포함 |
| `.github/workflows/pdd-callback-test.yml` | 별도 callback contract 테스트, Gradle 8.14.3 명시 | 원본 보존. Manual Build Wrapper 8.10.2와 별도인 점은 기존 계약 |
| `.github/workflows/import-pdd-issues.yml` | 이슈 import 전용 | 원본 보존 |
| `build.gradle` | NeoForge 21.1.228, Java 21, Sable 2.0.6, Companion 1.6.0, JUnit 5.11.4, classpaths | 버전·의존성·test task 실행 유지. 실패한 JUnit의 실제 Assertion 내용을 Actions 로그에 표시하도록 `testLogging.exceptionFormat='full'`만 추가 |

Apply의 보호 파일은 ZIP에서 읽히더라도 저장소의 파일을 교체하지 않는다. 본 ZIP에서는 현재 원본 바이트를 유지하며, 빌드 워크플로를 변경하거나 원격 Actions를 실행하지 않았다.

## 2차 검사 — 회귀 및 잠재 실패 점검

1. 기존 `@Test` **781개 보존**. `superFix` 변경 파일 `ContactFacePatchTest`, `Fix3FaceImpactPipelineTest`, `FullSurfaceForceBudgetTest`, `SuperFixBilateralSurfaceTest`의 운영 경로와 예상 F/Q·대칭·접촉면·경계·실패 조건을 재검토한다. 원래 JUnit 실행에서 나머지 780개가 통과했음은 Actions 증거이며 새 ZIP의 재통과를 의미하지 않는다.
2. 동일 불변 ContactFacePatch의 `covers` 호출에 대해 같은 테스트 안에서 참·거짓을 동시에 주장하는 실수를 감지할 수 있도록 `python3 scripts/check-superfix-assertions.py` 정적 검사기를 추가한다. 이 도구는 의도적으로 상태를 변경하는 `prepare()`/`reserve()` 같은 메서드를 검사하지 않는다.
3. `PhysicalDestroyDeform.BUILD_VERSION`·시작 로그, `build.gradle` JAR classifier·manifest의 PDD-Revision을 모두 `0.6.1-superFix-buildFix2`로 통일한다. Minecraft mod metadata의 메이저·마이너 버전 `0.6.1` 및 저장 스키마 6은 유지한다.
4. 실제 Gradle/JUnit/Actions/인게임 환경의 실행은 AI가 수행하지 않는다. Java 구문·정적 계약·ZIP·무결성 검증과 사용자 실제 실행의 상태를 구별한다.
5. Gradle 9.0 deprecation 및 GitHub Actions Node.js 20 경고는 실제 실패의 직접 원인이 아니며 이번 보호된 workflow나 Wrapper 변경 사유가 아니다.

## 사용자 검증

```sh
python3 scripts/check-superfix-assertions.py
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

예상 테스트 선언 수 781개(실제 런타임 조건에 따라 실행 수는 다를 수 있음). 기대 산출물: `physical-destroy-deform-0.6.1-superFix-buildFix2.jar`. 기존 `superFix-buildFix.jar`와 함께 설치하지 않는다.

인게임은 5×5/10×10 면 접촉과 Native 100건 포화, 회전 Plot/Copycat, 빈 접촉 셀, F/Q 보존 및 저장·재접속 두 회차로 검증한다.
