# PDD 0.6.0 deformFix — 검증 및 배포

## 기준

- Repository: `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`, main `2723603a965b4b42ff9c806339819497b87d836f` (read-only GitHub API 및 ls-remote 확인).
- Main recursive Git tree: 340 blobs, truncated=false. 339개의 실제 프로젝트 파일을 직전 moreFix ZIP/로컬 복원본과 경로·크기·Git blob SHA·실행 권한까지 비교하여 모두 일치. 나머지 하나는 main 루트에 업로드한 더 이전 배포물 `PDD-0.6.0-moreFix-FULL-SOURCE.zip`이며, 내용의 Git blob SHA `dd59edb926aea1a0241f650706de0c952d87ea53` 역시 로컬 압축파일과 동일. 중첩 배포 ZIP만 제외하고 전체 실제 프로젝트를 기반으로 제작.
- IDS 1.0.9 원본과 제공 설계서 바이트 동일. Minecraft 1.21.1, Java 21, NeoForge 21.1.228, Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2; Copycats+ 3.0.11 선택 호환. 클래스패스/저장 스키마/모드 버전 변경 없음.
- 실제 인게임 로그 `latestlog (1).txt`의 시작 642행은 moreFix. World APPLIED 이동 15건. Plot 이동 preflight 17건 모두 `DIAGONAL_CORRIDOR_BLOCKED` (4538행부터), 대상 gold_block, 1축 이동 및 air 목적지. 이 메시지는 점유·권한·엔티티를 하나로 묶어 정확한 단일 veto를 기록하지 않았음. 접착제 사용 및 1칸 대각선 연결성은 사용자 실측. 접착제 때문에 차단될 수 있는 실제 코드 경로와 별개의 6방향 연결성 과잉 veto를 확인했다.

## 수정 사항

1. Sable 2.0.6의 `SubLevelHeatMapManager.DIRECTION_OFFSETS` 초기화 및 탐색은 면 6개 + 모서리 12개를 사용한다. 새 `PlotMoveTopology`는 동일한 18방향 연결성으로 **이동 전 연결되어 있던 모든 이웃이 이동 후에도 목적지에 연결되는지** 확인한다. 세 축이 동시에 1인 꼭짓점 8개는 제외한다. 검증 범위는 이전과 동일한 원점 ±2셀(최대 125개), 캐시로 동일 셀 재조회 방지. 미확인 경로나 범위 외 우회 경로는 UNKNOWN/유예. stress 전파의 6면 그래프는 바뀌지 않는다.
2. Create JAR `SuperGlueEntity.contains(BlockPos):boolean`, `isGlued(LevelAccessor,BlockPos,Direction,Set):boolean` 및 Simulated 1.3.2 (Aeronautics Jar-in-Jar) `HoneyGlueEntity.contains(BlockPos):boolean`, `SimAssemblyContraption.checkAndCacheGlue(LevelAccessor,BlockPos,BlockPos):boolean` 바이트코드 확인. 모두 블록 중심이 기존 AABB 안에 있는지를 기반으로 접착 여부를 판단한다. 두 정확한 클래스+등록 ID 쌍만 알려진 접착 영역 엔티티로 구분한다.
3. Plot gateway는 영역 엔티티의 level, anchor 및 반열림 AABB 수평 네 귀퉁이가 동일한 현재 Plot 할당에 포함되는지를 `SubLevelContainer.getPlot(ChunkPos):LevelPlot`로 검증한다. 검증된 동일 owner 접착 영역은 source/corridor/physical query와 Create 연결 검사에서만 물리 장애물 취급하지 않는다. 해당 glue 엔티티를 이동·복제·삭제·재설정하지 않는다. 블록이 기존 영역 밖으로 이동하면 접착 관계가 바뀔 수 있고, 기존 영역 안으로 이동하면 기존 glue 규칙이 적용될 수 있다. 다른 owner 또는 증명 실패한 알려진 glue는 UNKNOWN, 일반 엔티티는 기존 차단, World glue veto는 기존대로 유지. 실제 다른 Plot AABB 교차와 World occupancy 검증 역시 유지.
4. `EmbeddedPlotLevelAccessor.getEntities(Entity,AABB,Predicate):List`는 local->allocated offset을 적용한다. Sable의 `SubLevelInclusiveLevelEntityGetter`가 세계 및 다른 Plot 쿼리 결과도 포함하므로 raw entity 좌표와 동일 owner 판정을 구분한다. 회전한 다른 Plot broad-phase의 보수적 거절 및 일반 엔티티의 기존 거절은 남아 있다. 신규 glue class 로딩/직접 링크 또는 외부 의존성을 추가하지 않았다.
5. 접착 영역 허용만으로 이동을 승인하지 않는다. 기존 loaded/claim, BE, kinetic, sticky, unit shape, survival, 두 축 경유 공간, 실제 World 물리 공간/다른 Plot 교차, 지문+재질+세대 CAS, Q 예약, 정상 setter와 취소 이벤트, HeatMap 완료 witness, 질량 readback 및 격리를 유지한다.
6. 기존 혼합 진단을 `PLOT_CORRIDOR_OCCUPIED`, `PLOT_CORRIDOR_CLAIM_DENIED`, `PLOT_CORRIDOR_ENTITY`, `PLOT_CORRIDOR_GLUE_OWNER_UNVERIFIED` 및 source 대응 코드로 분리했다. 기존 `PHYSICAL_ROUTE_UNVERIFIED`, `PLOT_CONNECTIVITY_ISOLATION`, Create/기계 veto도 그대로 사용한다. `-Dpdd.deform.trace=true` (기본값)에서 차단 경로를 확인한다.

변경하지 않은 범위: F/Q·H/T/D/E·기존 자체 탄성 감쇠·응력 상한·deadline·고정소수점·충돌 콜백 및 solver·World gateway·Copycats+·시간 역행 복구·저장/복원. 양측 접촉 탄성 감쇠와 진자충돌은 향후 버전. Cross-owner split/merge/reassembly 및 glue 이전은 IDS 0.6.1 이후 범위. 동일 owner 영역의 유지 외에 다른 접착 데이터 마이그레이션을 시도하지 않는다.

## JUnit 기대 결과 (실행 전 정적·수치 검토)

기존 `@Test` 590개 유지, 신규 10개, 총 600개 예상. 기존 test/빌드 workflow는 모든 테스트를 실행하며 고정 테스트 수 임계값이 없으므로 테스트 정의와 기대 결과만 갱신. 실제 테스트 실행 수·성공 여부는 Actions 결과로 확정.

| 신규 테스트 | 입력 및 기대값 |
|---|---|
| thinWallCanDentOneCellIntoAirWhileRetainingEdgeConnections | 원점 (-3,0,0), 목적지 (-3,0,-1), XY 평면 면 이웃 4개: CONNECTED |
| allTwelveEdgeDirectionsAreConnections | 12개 signed edge offset 각 1회: CONNECTED |
| threeAxisCornerDoesNotConnectDestination | 목적지 (1,0,0), 남은 (0,1,1): DISCONNECTED |
| severedEdgeBranchCannotBeApproved | 남은 (0,1,0),(-1,-1,0) 중 하나만 연결: UNKNOWN |
| unavailableEdgeNeighborCannotAuthorizeMovement | (-1,1,0) 미확인: UNKNOWN |
| repeatedTopologyProbesReadEachBoundedCellOnlyOnce | 미입증 branch 및 반복 탐색: UNKNOWN, 고유 읽기 125 이하, 각 1회 |
| onlyExactKnownGlueClassAndRegistryPairQualifies | 알려진 두 쌍 true, 유사 클래스·ID 및 null false |
| retainsMetadataOnlyForProvenSamePlot | 정상 glue+동일 owner APPROVED; 알려진 glue+미확인 UNKNOWN; 비 glue BLOCKED |
| glueRegionMustStayWithinOneAllocationIncludingAnchor | [-32,-16) × [-16,0), chunk(-2,-1)만 owner: 전체/anchor 내부 true, anchor 또는 한쪽 경계 이탈 false |
| malformedGlueRegionIsNotTrusted | 0크기, NaN, Infinity, owner 미확인: false |

JUnit 실제 RED/GREEN, Gradle compileJava/compileTestJava/processResources/jar/build/clean test, Actions와 in-game은 사용자 실행 전 NOT_RUN. Java 21 구문 분석은 타입 검사 또는 의존성 해석이 아니다. 기존 테스트 기대값이나 물성 수치를 낮추지 않았다.

독립 정적 검수: 18방향 근거, glue 클래스/ID, 할당 영역, 기존 게이트·저장 계약 및 신규 테스트 기대값 확인. 검수에서 generic 엔티티 쿼리와 Create typed 쿼리의 소유권 불확실 glue 판정 차이를 발견하여 양쪽 모두 동일 Gate 함수로 통일. World의 BLOCKED 기본 정책 보존. 기존 Sable broad-phase의 보수적 false-positive 및 실제 재조립/접착 내구성은 인게임 확인이 필요하다.

정적 소스 검증: Java 21 구문 분석 211 compilation units, 구문 오류 0. 원본 339개 파일 경로 유지, 8개 수정, 문서 2개 신규, 삭제 0개 (main 루트의 이전 배포 ZIP은 프로젝트 파일이 아님). Gradle/Wrapper/Actions 의존성과 실행 방식 유지; JAR classifier 및 revision만 변경.

## 사용자 검증 (2회)

먼저 전체 월드 및 PDD journal을 백업. 기존 JAR를 제거하고 Apply ZIP을 incoming/에 하나만 업로드한 뒤 기존 Actions에서 빌드. 로컬에서는 `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon`. 기대 JAR: `physical-destroy-deform-0.6.0-deformFix.jar`. 시작 `revision=deformFix`, JUnit 실행 요약 600개 예상.

1. **양성 시험**: 이전과 같은 금 정적 벽 및 접착제로 조립한 금 Plot 벽. 폭/높이는 최소 3×3, 충돌 방향 뒤쪽 최소 2칸 공기 확보, 물리적으로 다른 Plot/정적 블록이 이동 목적지와 겹치지 않게 배치. 동일 충돌체·속도에서 World 및 Plot 모두 파괴 이전 실제 변형이 가능한 충격을 반복. Create Super Glue와 Honey Glue가 각각 있다면 같은 시험에서 각각 별도 벽으로 확인. Plot 회전 90도 포함. 성공 기준: 실제 블록이 1칸 이동, source air, dest gold_block, 동일 Plot UUID 유지, `phase=PLOT_HEATMAP_WAIT` 뒤 `phase=PLOT_POSTSTATE result=CONFIRMED` 및 MoveTransactionExecutor의 `result=APPLIED`/최종 기록(코드상의 실제 결과명 참조), 질량 동일 허용오차 내, Q/손상 이력 1회 이전, glue 영역과 불필요한 드롭 변화 없음. 단순 후보/시도 로그는 성공 아님.
2. **음성·회귀 시험**: 같은 구조에서 변형 목적지를 다른 블록 또는 물리적으로 다른 Plot으로 막고, 플레이어/몹이 실제 목적지에 있는 경우, 또는 이동으로 연결이 끊기는 경우를 분리하여 시험. 차단/UNKNOWN/DEFERRED는 정당한 veto이며 안전하게 처리되어야 한다. 접착제가 없는 Plot·정적 벽의 정상 경로, 이동 후 월드 저장 및 재시작 확인. PDD가 glue 엔티티를 복제/삭제하지 않으며 실제 블록/손상 상태가 중복되지 않는지 확인. 위험 기계/BE/권한은 허용하지 않는다.

제출: 전체 latest.log 및 crash report (있으면), Actions test summary. 특히 `[PDD-DEFORM]`의 PLOT_GATE/PLOT_POSTSTATE/PLOT_MASS_MISMATCH, `[PDD-MOVE]`, `[PDD-DEFORM-Q]`, `[PDD-STRESS-LEDGER]`를 확인한다. 정당한 안전 검증 실패까지 무조건 열어 변형을 강제하지 않는다.
