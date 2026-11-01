# PDD 0.3.1fix — Sable 2.0.6 Plot 좌표계·Native 충돌 구조 역분석

- 작성 기준: 2026-11-01
- 대상: Minecraft 1.21.1 / NeoForge 21.1.x / Sable 2.0.6 / PDD 0.3.1fix
- 범위: Sable 2.0.6 릴리스 Rust·Java 원본, 배포 JAR 및 내장 native, 업로드된 충돌 애드온 3종, Sable Optimizer, PDD 소스와 실제 인게임 로그
- **상태: 분석 완료. 소스 교정·공식 Gradle·인게임 재검증은 수행하지 않음.**

## 0. 핵심 판결

**확정적 설계 결함**: PDD의 `SableConsumerTargets.frameResidual()`은 같은 native 레코드의 A/B witness point를 각각 월드 좌표로 변환한 뒤, 두 점의 유클리드 거리가 `0.25` 블록을 넘으면 `WORLD_POINT_RESIDUAL_GT_0.25`로 거절한다. `SableContactRegions.collect()`는 이 판정을 모든 블록/형상 검사의 선행 게이트로 사용한다. 그러나 **Sable은 Rapier의 양측 접촉 증인점 `local_p1`, `local_p2`를 별개의 점으로 그대로 전송한다**. 두 세계 접점이 일치해야 한다는 보장은 없다. 관통·접촉 예측 시 거리가 0.25를 초과해도 유효한 충돌일 수 있다. 따라서 이 조건은 접촉 유효성의 필요조건이 아니며 **실제 타깃을 검사조차 하지 않은 채 누락시키는 잘못된 필터**다.

**별도 확정된 구조적 문제**: Sable은 복수 물리 substep의 접촉을 누적하고 모든 substep이 끝난 뒤 `clearCollisions()`를 호출한다. PDD는 이때 확인한 마지막 `logicalPose`로 이전 substep 접점을 세계좌표에 변환한다. native 레코드에 substep ID와 생성 순간 pose가 없어 **시간 일관성이 보장되지 않는다**. 이것이 로그 좌표 잔차에 얼마만큼 기여했는지는 미확정.

**배제한 가설**: 주력 `LevelCollider`의 별도 상대 Collider pose가 누락된 것이 핵심 원인이라는 가설은 **원본 코드에 비추어 부적절**하다. 동적 LevelCollider는 위치 오프셋 없이 RigidBody의 자식으로 생성된다. 대신 각 블록의 로컬 위치에서 질량중심을 빼고 manifold point에 되돌려 더한다. 원본 native 접점은 결과적으로 해당 LevelCollider/Body의 질량중심 기준 로컬 값이다.

**교정의 중심**: 각 native 레코드의 A측과 B측을 **각자 해당 Body의 로컬 좌표에서 독립 검증**한다. world residual은 진단값으로만 유지한다. 동일 native 레코드의 두 측은 실제 Sable이 만든 접촉 쌍이므로, 임의의 다른 레코드와 재조합하지 않는다. R3의 타측 world projection은 timestamp가 동일하다는 증거가 없는 한 안전성 근거로 사용하지 않는다.

## 1. 사용한 실제 자료와 식별자

| 자료 | 버전 / 식별 | 확보 및 근거 |
|---|---|---|
| Sable 배포 모드 | `sable-neoforge-1.21.1-2.0.6.jar` | 업로드 JAR, SHA-256 `9c199157843bf953a47dff625fc5bb41c01a90538d35ad18e6996bbcb8a617c2` |
| Sable Rapier Java 중첩 JAR | `META-INF/jarjar/dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.6.jar` | 위 JAR 내 7,607,225 bytes |
| Sable 릴리스 태그 | `mc1.21.1-2.0.6-neoforge` | 릴리스 Git ref 직접 조회, 커밋 `ad8b4d377741fbd923f4d863b2cf9e462daff9a6` |
| Sable native Linux x86_64 | `native-sable_rapier_x86_64_linux.so` | 내부 압축에서 추출, SHA-256 `442c1ffb2f13250caab0e9e26d8e0aa507bad7d401474a723670f8f76b26832f` |
| Sable native Linux aarch64 | `native-sable_rapier_aarch64_linux.so` | 내부 압축에서 추출, SHA-256 `d6ddf44ebe1ecfe6d177da1c1a3768b04e06c5b704b3f4025c095cd79509d8c6` |
| Sable Collision Damage | `sablecollisiondamage-1.0.8.jar` | 업로드 Java bytecode (`javap -c -p`) |
| Sable Impact | `sableimpact-alpha-1.1.0.jar` | 업로드 Java bytecode |
| True Impact | `true_impact-0.5.7-delta.jar` | 업로드 Java bytecode |
| Sable Optimizer | `sable_optimizer-1.0(2).jar` | 업로드 Java bytecode, 인게임 파일명은 `sable_optimizer-1.0.jar`로 직접 바이트 동일성은 미확인 |
| PDD | `PDD-0.3.1fix-FULL-SOURCE-UNVERIFIED-APPLY.zip` | 업로드 전체 소스 ZIP, `SableConsumerTargets`, `SableContactRegions`, `FrameMath`, `OwnerResolver`, Mixin 분석 |
| 게임 로그 | `latestlog.txt` | PDD 0.3.1fix, 2,185행; native 접촉·잔차·원장 계측 |

**주의:** Git 태그와 JAR에 표시된 버전은 일치한다. 다만 **stripped native 바이너리와 Git Rust 코드가 bit-for-bit 동일 빌드임을 증명하는 공급망 정보는 확보하지 않았다**. 엔드포인트와 구조는 동일 계열로 확인했고, 소스 수준 의미는 공식 2.0.6 릴리스 커밋을 기준으로 확인했다. 릴리스 구현을 직접 대조했지만 네이티브 바이너리의 소스와 완전한 빌드 동일성을 증명한 것은 아니다.

## 2. 실제 Sable의 4가지 좌표공간

| 공간 | 표시 | 원점과 예 |
|---|---|---|
| A. Plot 저장 그리드 절대 좌표 | `P_storage` | 멀리 배치된 가상 chunk 저장 좌표, 실로그 X/Z 약 `20,481,032`; 실제 월드 좌표 아님 |
| B. Plot embedded 상대 블록 좌표 | `P_embedded` | `P_storage - plot.getCenterBlock()`; 예 PDD `localBlock=(1,3,-1)` |
| C. Rapier LevelCollider / RigidBody 로컬 좌표 | `P_body` | **질량중심(COM)** 기준, native `local_p1/2` |
| D. 실제 Overworld 월드 좌표 | `P_world` | 실제 움직이는 구조물의 점, 예 `(79, 67, -50)` |
| E. 단일 복셀 Cuboid 로컬 좌표 | `P_box` | 각 블록 collision box 중심 기준의 ±half extents; native `dispatcher`에서 Body-local로 전환한 다음 이벤트 발생 |

> 공간 A, B, C, D, E를 같은 `BlockPos` 또는 같은 원점으로 취급하면 오차가 난다.

각 변수:
- `C = rotationPoint = centerOfMass`(Sable Rapier가 등록하는 Plot 질량중심; 질량변화 시 확인 필요)
- `G = plot.getCenterBlock()`(저장 그리드의 Plot 중심; C와 일반적으로 다름)
- `T = logicalPose.position()`(물리 RigidBody 월드 위치)
- `R = logicalPose.orientation()`(quaternion으로 나타낸 물리 회전)
- `S = pose.scale()`(실로그는 모두 `(1,1,1)`)

**스케일 1의 대상 native LevelCollider 경로**:

```
P_body      = P_storage - C
P_storage   = P_body + C
P_embedded  = P_storage - G
P_world     = T + R(P_body)
            = T + R(P_storage - C)
P_body      = R^-1(P_world - T)
P_storage   = C + R^-1(P_world - T)
```

일반 `Pose3dc` 스케일 수식은 `P_world = T + R( (P_storage-C)*S )`. Native collider와 PDD 현재 스케일1 사례는 위와 같이 환원된다. **벡터/법선에는 평행이동을 더하지 않는다.** 법선 변환은 회전(`R`) 및 비등방 스케일 시 역전치 정규화 문제를 별도로 다룬다.

**정적 World의 Body ID `-1`**: Native static `LevelCollider(None,true)`는 별도 부모 없이 원점 기본 pose로 생성된다. Native 보고 접점은 이 정적 Collider의 로컬 좌표, 즉 현재 구현에서 **절대 월드 좌표**다. Plot CoM이나 회전을 적용하면 안 된다.

## 3. source-to-sink 호출·데이터 추적

### 3.1 Collider 및 RigidBody 생성

`lib.rs` `createSubLevel` (대략 650–713행):

1. 입력 pose `(position, quaternion)`으로 동적 RigidBody 생성.
2. `ColliderBuilder::new(LevelCollider(Some(id), false))`, 별도 `.position` 호출 없음.
3. `collider_set.insert_with_parent(collider, handle, rigid_body_set)`로 부모에 결합. 이 LevelCollider의 상대 pose는 기본 identity.
4. `setCenterOfMass(...)`는 Sable collider info에 저장.
5. `collider.rs`의 `compute_local_aabb_from_bounds(min,max,com)`는 각 bounds에서 `com`을 차감하여 Body-local bounds를 구성.

정적 World에서는 `lib.rs`의 scene 초기화 394–452행이 `LevelCollider(None,true)`를 원점 ColliderSet에 직접 삽입한다. ID `None`은 Java에서 `-1`로 직렬화된다.

### 3.1.1 질량중심 변경 시 좌표 epoch — 추가 확인

`common/src/main/java/dev/ryanhcode/sable/api/physics/mass/MergedMassTracker.java` 105–145행의 `uploadData()`는 질량·질량중심·관성텐서 변화를 검사한다. 값이 바뀌면 다음 순서로 처리한다.

1. `physicsSystem.updatePose(subLevel)`로 현재 물리 pose 갱신.
2. CoM 변화량 `movement = newCenterOfMass - lastCenterOfMass` 계산.
3. `pipeline.teleport(..., pose.position + R(movement), pose.orientation)` 실행.
4. `pose.rotationPoint().set(newCenterOfMass)` 실행.
5. `pipeline.onStatsChanged(subLevel)`로 native Collider COM과 질량 특성 변경.

따라서 **COM과 `rotationPoint`는 갱신 경로에서 함께 변경된다**. 충돌 원본이 생성될 때의 `C_t`와 PDD가 나중에 읽은 `C_last`가 다르면 `raw + C_last`가 실제 접점 생성 당시의 Plot 저장 좌표와 다를 수 있다. 일반적인 고정 구성 테스트에서 실제 CoM 변화가 있었다는 증거는 없으며, *가능한 위험 경로*로만 분류한다. 향후 R2가 world pose를 배제하더라도 **COM epoch 일치**는 별도 확인해야 한다.

근거: [MergedMassTracker.uploadData](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/common/src/main/java/dev/ryanhcode/sable/api/physics/mass/MergedMassTracker.java#L105-L145).

### 3.2 Contact manifold 생성 — native에서 정확한 블록 쌍을 아는 위치

`dispatcher.rs`의 `world_vs_world` (약 440–690행):

1. `find_collision_pairs(...)`로 두 객체의 복셀 후보 쌍 탐색. 전달되는 연산 상한 인수 `256`.
2. `chunk_access_1/2.get_chunk`, `get_block`으로 **충돌한 실제 블록 좌표 A/B** 확보.
3. `boxCenter + blockCoord - centerOfMass` 계산. native 시점에서 전체 Plot 저장 좌표와 COM 차이가 명시됨.
4. 개별 block Cuboid에 `contact_manifold_cuboid_cuboid_shapes` 적용.
5. `SableManifoldInfo{pos_a,pos_b,col_a,col_b}`에 **정확한 복셀 블록 쌍**을 저장. `manifold.data.user_data`에 인덱스 기록.
6. 충돌점 `point.local_p1 += center; point.local_p2 += other_center`로 **Cuboid-local 점을 전체 LevelCollider-local 점으로 변환**.
7. `swap`된 경우 point와 local normal 순서를 원래 Body 정렬에 맞게 뒤집는다.

`static_world_vs_collider`에서도 block center 및 COM 보정 후 point의 로컬 좌표를 해당 LevelCollider 공간에 맞게 조정한다.

**매우 중요한 증거**: Sable의 네이티브 충돌 계산은 각 접촉이 어느 복셀 블록에서 발생했는지 이미 안다. PDD가 나중에 역산하는 이유는 **현행 JNI 직렬화가 그 블록 좌표를 버리기 때문**이다.

### 3.3 충돌 이벤트 → Java payload

`event_handler.rs` `handle_contact_force_event` (대략 25–70행):

```
for manifold in contact_pair.manifolds:
    for point in manifold.points:
        local_p1 = point.local_p1
        local_p2 = point.local_p2
        force_amount = total_force_magnitude
        body_a = LevelCollider.id
        body_b = LevelCollider.id
        reported_collisions.extend(...)
```

- `total_force_magnitude < 0.1`이면 기록하지 않음.
- `local_p1`과 `local_p2`는 **각 Collider별로 별도의 witness point**.
- `TrackedContact.dist`, `manifold.user_data`, `SableManifoldInfo.pos_a/pos_b`, substep index, native pose는 **전달하지 않음**.
- `total_force_magnitude`는 각 점에 동일한 값으로 들어간다. 각 점의 고유 충격량으로 합산하면 과대계산 위험.

`lib.rs` `clearCollisions` 1243–1304행:

```
[ body_a, body_b, force_amount,
  local_normal_a(3), local_normal_b(3),
  local_point_a(3),  local_point_b(3) ]
```

Java에 고정 15개 `double` 스트라이드로 전달. `reported.truncate(100)` 후 직렬화하고 `reported.clear()`로 큐 비움. 초과분 통계는 노출하지 않음.

### 3.4 Java 소비자의 실제 처리

`RapierPhysicsPipeline.java` `processCollisionEffects()` (634행 이후):

```
raw point A/B = collision[9..11] / collision[12..14]
if Plot A: block pos = floor-ish(rawA + logicalPose.rotationPoint())
if Plot B: block pos = floor-ish(rawB + logicalPose.rotationPoint())
if World:  block pos = raw World point
world Plot point = rotate(raw, logicalPose.orientation()) + logicalPose.position()
```

Java는 이 입자/사운드 경로에서 A/B 세계 접점 사이 거리≤0.25를 검사하지 않는다. Sable 소비자의 `BlockPos.MutableBlockPos#set(double,double,double)`가 변환 좌표를 **어떤 방식으로 정수화하는지**는 해당 MC 함수 구현과 비교해야 한다. PDD의 주변 8셀 탐색을 Sable의 `BlockPos` 정수화 결과와 일치한다고 단정하면 안 된다.

### 3.5 PDD 0.3.1fix의 잘못된 필터

`src/main/java/com/physicaldestroydeform/collision/SableConsumerTargets.java`:

- `candidate`: Plot은 `raw + frame.pose().rotationPoint()`로 Plot 저장 좌표를 구함. World(-1)는 raw 그대로. **방향·기준 원점은 Sable와 일치한다.**
- `worldPoint`: `pose.toWorldPoint(raw + C)` ⇒ `T+R(raw)`; 스케일1에서 수학적으로 맞는다. 다만 **접촉 생성과 동일 substep pose를 썼다는 전제**가 미보장.
- `frameResidual`: 두 점의 거리 `distance<=0.25`만 `MATCH` 승인.
- `corroboratedContact`: 위 값이 `MATCH`일 때만 true.
- `scan`: R2뿐 아니라 R3에도 선행 `corroboratedContact` 적용.

`src/main/java/com/physicaldestroydeform/collision/SableContactRegions.java`: native 레코드 처리 시작 시 `if(record==null || !corroboratedContact(record)) ... continue;`; **OwnerResolver·VoxelShape 감사까지 실행하지 않고 해당 레코드 제거**. 나머지에 대해 각 측 대상 독립 검증, 32 검증 검사 상한 및 4 타깃 상한은 유효한 안전장치.

**판결**: PDD의 현재 Plot 좌표 공식 자체가 완전히 틀렸다는 증거는 없다. 접점 유효성 판정식의 전제가 틀렸으며, 마지막 서브스텝 pose를 사용한 world projection의 시점도 일치하지 않을 수 있다. 이 둘을 원인으로 분리해야 한다.

## 4. 두 witness point의 세계 좌표는 왜 다를 수 있나?

Rapier의 contact manifold는 양쪽 형상에서 선택한 접촉 증인점 `local_p1`, `local_p2`를 보유한다. 접촉점이 반드시 공간상의 동일점이라고 정의하지 않는다. 관통(negative `dist`) 혹은 예측 접촉의 경우 두 접점은 떨어질 수 있다.

독립 검증용 간단한 1D 기하 예: 두 단위 큐브가 X방향 0.30블록 중첩. A측 접촉면은 X=1.00, B측 접촉면은 X=0.70. 양측에 물리적으로 일관된 witness point가 있어도 거리=0.30 > PDD 상한 0.25, PDD는 레코드를 검사하기도 전에 버린다. 이는 **전제 반례**이며 사용자의 실제 사건이 반드시 0.30 깊이였다는 주장은 아니다.

Sable `collider.rs`의 `ccd_thickness()`가 `0.25`를 반환하는 것은 CCD 관련 모양 매개변수다. PDD `frameResidual<=0.25`를 물리 접촉 유효성으로 정당화하는 계약이 아니다.

## 5. Multi-substep 좌표 시점 불일치

`common/.../SubLevelPhysicsSystem.java` 250–298행:

1. `pipeline.prePhysicsTicks()` 한번.
2. `substepsPerTick`만큼 `pipeline.physicsTick(dt)` (즉 Rapier `step`) 실행.
3. **매 substep 후** `updateAllPoses(container)` 실행. `logicalPose` 최신 값으로 갱신.
4. 모든 substep 완료 후 `pipeline.postPhysicsTicks()` 실행.
5. 그 내부에서 `RapierPhysicsPipeline.processCollisionEffects()` → `Rapier3D.clearCollisions()`.

`event_handler.rs`는 각 substep에서 발생한 접촉을 **같은 reported_collisions 버퍼에 누적**한다. `lib.rs`의 `step`은 `manifold_info_map.clear()`를 매 substep 실행하지만 `reported_collisions`는 지우지 않는다. `clearCollisions()`는 각 레코드 생성 substep ID를 내보내지 않는다.

**결론**: T=후반 substep pose로 이전의 `local_p`를 world 변환할 수 있다. 물리적으로 불일치된 시점이므로 `worldA-worldB` 잔차는 실제 관통 거리와 시간 오차가 합쳐진 값일 수 있다. 특히 상대 속도/회전이 크거나 Sable Optimizer가 pose 갱신을 생략하면 차이가 늘 수 있다. **오차가 각 원인에 얼마나 기인하는지는 현재 자료만으로 분해 불가**.

이 문제는 R2를 **각 측 native Body-local에서 독립 조회**하면 피할 수 있다. 같은 좌표 공간 안에서 raw witness와 Shape를 비교하면 world projection 및 substep 재투영이 필요 없다. 단, 질량 중심이 substep 중 이동했다면 COM epoch 차이도 별도 검증해야 한다.

## 6. 세 유사 애드온의 실제 설계 비교

### 6.1 Sable Collision Damage 1.0.8

`com.sable_collision_damage.SablePreSolverDamage` (`javap -c -p`)의 `toPlotPosition`:

```
worldPoint - logicalPose.position()
logicalPose.orientation().transformInverse(...)
+ logicalPose.rotationPoint()
```

이는 World→Plot 저장좌표 변환의 정확한 역식이다. `Sable.HELPER.getAllIntersecting()`을 이용해 월드 접점 주변 Plot 후보를 찾고 실제 블록 상태를 확인한다. 인접·겹침 구조물에선 근접성만으로 native 정답 쌍을 단정할 수 없다. `RapierVoxelColliderBakeryMixin`으로 callback을 가로채는 구조는 PDD의 원본 콜백 보존 조건과 충돌할 수 있다.

### 6.2 Sable Impact alpha 1.1.0

`com.mrdead2020.sableimpact.physics.KineticImpactCallback`은 `toPlotPosition`에 같은 `R^-1(P_world-T)+C`를 사용한다. `Sable.HELPER.getContaining()`으로 주변 Plot을 찾는 추정 경로가 있다. 그러나 구현 callback은 `sable$onCollision(BlockPos,Vector3d,double)` **3인수**, Sable 2.0.6의 callback은 `sable$onCollision(BlockPos,BlockPos,Vector3d,double)` **4인수**다. JAR간 바이너리 API 호환성 위험이 있으므로 그대로 이식 금지. 바이트코드에 기존 콜백 대체 Mixin 경로도 있어 원본 보존 정책과 충돌 가능.

### 6.3 True Impact 0.5.7-delta

`io.github.omegau371.trueimpact.observation.BodySnapshot`, `SableBodyReader`, `SableImpactCapture`, `SableEventBridge`, `DiagnosticContactCaptureMixin` 분석.

- BodySnapshot에 `substepIndex`, `SnapshotPhase`, Body pose, CoM, plotCenter 등이 분리 저장된다.
- Plot 저장 중심과 질량 중심 차이를 따로 반영한다.
- 물리 시점별 snapshot 수집을 시도하며, PDD의 마지막 pose 재투영 오류를 계측하는 데 유용.
- 하지만 Sable `clearCollisions` 원본에 substep ID가 없으므로 **snapshot만 많다고 각 raw 레코드와 생성 substep을 정확히 매핑한 것으로는 볼 수 없다**.
- 주변 블록 근접성은 후보 탐색 근거일 뿐, native에 실재하는 `manifold_info_map.pos_a/pos_b`를 대체하는 확증은 아니다.

### 6.4 Sable Optimizer 1.0 (보조 분석)

`net.mc_orion.sable_optimizer.mixin.SubLevelPhysicsSystemMixin`은 `updatePose(ServerSubLevel)`을 `ActivityTracker.shouldUpdatePose(UUID,currentSubstep)` 정책에 따라 취소할 수 있다. **비활성 Plot pose의 업데이트 주기가 달라질 가능성**이 있다. 게임에는 `sable_optimizer-1.0.jar`가 로딩되었으나 업로드 분석 JAR 파일명은 `sable_optimizer-1.0(2).jar`이므로 실제 런타임 바이트 동일성은 아직 증명되지 않는다. 기존 접점 거리 게이트가 틀렸다는 결론은 Optimizer와 무관하다.

## 7. 실제 0.3.1fix 로그의 교차 검증

로그 `latestlog.txt` 총 2,185행, 0.3.1fix가 로드됨. 자동 문자열 집계(기본 소스 레코드 수와 진단 레코드 수는 다른 측정 단위):

| 항목 | 값 |
|---|---:|
| `[PDD-EPISODE]` | 268행 |
| 승인 `APPROVED` | 43행 |
| 중복 `DUPLICATE_EPISODE` | 200행 |
| `TARGET_UNVERIFIED` | 22행 |
| `INITIAL_CONTACT_UNKNOWN` | 3행 |
| `[PDD-LEDGER]` ISSUED | 43행 |
| `[PDD-VERF-REGION]` 진단 | 75행 |
| `NO_VERIFIED_REGION / UNCORROBORATED` | 24행 |
| `[PDD-FIX-FRAME]` | 72행 |
| FRAME 첫 불일치 거리 | 최소 0.25357, 중위 0.48216, 최대 1.85165 블록 |
| native `rawRecords=100` | **7회 연속**(nativeTick 91376~91382) |
| 최종 summary `regionTooBroad` | 10 |
| 최종 summary `nativeDropped` | 0 (PDD 유입 이후 관련 지표) |
| 최종 summary `worldMutation` | OFF |

**진단의 해석을 바로잡아야 한다**: `NO_VERIFIED_REGION / UNCORROBORATED` 24행은 블록 형상 검사가 24회 실패했다는 뜻이 아니다. 원점-세계 witness 거리 게이트 때문에 해당 레코드의 **독립 블록 검사가 실행되지 않았다는 사실**이다. 얼마나 많은 유효 타깃을 배제했는지는 필터 제거 후 동일 상황 검증 없이는 숫자로 확정할 수 없다.

로그 대표 사례:
- tick90312, Plot/Plot 첫 불일치 거리 0.71925, 14 native 레코드 중 12개 uncorroborated. 나머지에서 2/2 타깃 승인.
- tick90656, Plot/World 첫 불일치 거리 0.32587, 11레코드 중 7개 uncorroborated. 승인 여부는 FSM 상태도 작용.
- tick90780, Plot/Plot 첫 불일치 거리 0.27913, `0.25`보다 0.02913 초과. 나머지 타깃은 검증됨.
- tick91795, Plot/World 첫 불일치 거리 1.85165. 이것만으로 관통 깊이인지 시간 차이인지 분리할 수 없다.

### Native `100` 제한의 의미

`lib.rs`의 `reported.truncate(100)`로 인해 100개 이상의 기록이 생성됐더라도 **최초 100개 이외는 Java에서 관측할 수 없다**. 이번 로그에 100이 연속 7번 나타났다는 것은 상한에 닿았다는 뜻이며 **초과 기록이 실제 있었는지**는 원본 버퍼 길이를 로깅하지 않아 확정할 수 없다. `nativeDropped=0`은 `clearCollisions()` **이전**의 native 내부 잘림을 검증하지 못한다. 접점과 충격 정보 정확성 문제와 별개로 기록 완전성 요구사항을 위협한다.

## 8. 확정·기각·미검증 구분

| 질문 | 판정 | 결정적 근거 |
|---|---|---|
| native LevelCollider local 접점의 기준 | **확정** | `dispatcher.rs` blockCenter-COM 보정 및 point offset; `event_handler.rs` 그대로 전달 |
| 동적 LevelCollider와 Body 상대 pose | **identity 확인** | `lib.rs createSubLevel` ColliderBuilder 기본 pose, insert_with_parent |
| `-1` 정적 접점 의미 | **world absolute 확인** | native static collider identity, CoM=0, `-1` 직렬화 |
| Plot embedded center와 COM 관계 | **서로 다른 기준점 확인** | `LevelPlot.getCenterBlock` vs `MassTracker.getCenterOfMass` |
| Sable가 두 witness point의 일치를 요구함 | **아니오: 원본 불일치 허용** | Rust `event_handler` 직접 복사; Rapier contact point 구조 |
| PDD 0.25 거리 게이트의 필요성 | **잘못된 필요조건** | PDD 선행 필터, 위 source 계약과 충돌 |
| 마지막 pose 재사용의 시점 일치 | **일치 보장 없음** | substep별 step/pose 갱신, 종합 clearCollisions |
| 이번 실제 오차의 관통·시점별 비율 | 미확정 | native 레코드에 dist/substep/pose 없음 |
| JNI 반환값의 정확한 block pair | 미제공 | manifold_info_map에만 존재, 15-double ABI에서 삭제 |
| native에서 100초과 손실이 실제 발생했는가 | 미확정 | 7회 `rawRecords=100`이나 overflow count 없음 |
| 실제 Gradle/JUnit/인게임 수정 검증 | 미실행 | 본 문서는 분석 전용 |
| Git 소스와 배포 native 바이너리의 재현 빌드 동일성 | 미확정 | 공급망/빌드 provenance 없음 |

## 9. PDD 다음 개발 버전 권장 정책 (별도 코드 수정 전 설계)

**최소·정확·fail-closed 수정**:

1. `SableConsumerTargets.frameResidual`은 **diagnostic-only**로 변경한다. A/B witness 거리 >0.25는 `PENETRATION_OR_EPOCH_DIFFERENCE` 관측값이며 단독 거절 사유가 아님.
2. `SableContactRegions.collect`의 `!corroboratedContact` 선행 `continue` 제거. 대신 각 record의 ID·owner·finite raw·frame validity·블록 상태/shape와 접점 상대 위치를 독립 검증. `SableConsumerTargets.scan`의 동일한 선행 거절도 수정.
3. Plot 측 native point `raw + C`로 **Plot 저장 좌표의 해당 블록/충돌 형상** 직접 검사한다. static world 측 native point `raw`로 정적 월드 블록 검사. **R2에서는 월드 재투영 자체가 필요 없음.**
4. **동일 native record 내의 A/B만 접촉 쌍으로 연결**; 물리 Body ID 및 UUID 확인; 다른 레코드의 점끼리 임의 교차 결합 금지. 두 점이 정확히 일치할 필요 없이 같은 native solver manifold record에서 왔다는 출처가 핵심.
5. 점 근처 이웃 블록 검사에서는 `OwnerResolver`의 shape distance를 각각 독립 실시. 기존 캐시는 block state/shape 자체만 재사용하고 접촉점별 근접성 다시 평가. 32검사/측, 4타깃/측, Q<=64, 중복 FSM/원장, 미사용 Q 정책 유지.
6. **R3 world-to-other-side projection을 같은 substep/pose 증거 없이 확정 경로로 사용하지 않는다.** 이미 같은 record에 정확한 타측 native witness가 있으므로 R2 성공하면 R3 불필요. R2에서 소유자나 형상이 확인되지 않으면 fail-closed.
7. 시간 영향 진단: 최종 pose 기준 `d_world` 외 두 점 차이의 법선/접선 성분을 별도로 기록한다. 구간별 native substep 시점 매칭이 없다면 이 값만으로 신뢰도 확대 금지.
8. 절대 정확한 native 블록 쌍이 꼭 필요한 나중 단계는 Rust `SableManifoldInfo`를 `ReportedCollision`에 포함하고 `dist,substep,pose,overflowCount`까지 ABI를 확장하는 방안 검토. **외부 Sable 바이너리와 호환되어야 하므로 PDD가 임의로 JNI ABI 변경하지 말 것.** 별도 호환 모드/승인/검증 필요.
9. 원인별 회귀 테스트: 관통 witness 거리 0.3/0.7/1.2, World-Plot, Plot-Plot, 90° 회전, 다른 COM/plotCenter, native record A/B swap, 2-substep pose 차이, 잘못된 frame/ID, 빈 블록, 중복 포인트, 5타깃 편측 격리, 100레코드 batch 경계, FSM 이벤트 순서.
10. 사용자는 현재 반복 테스트할 필요 없음. 수정본 ZIP을 Apply/Actions로 빌드 후 기존 두 차례 인게임 테스트. 그때 주요 성공 지표는 `NO_VERIFIED_REGION/UNCORROBORATED` 감소, 독립 shape PASS, Q 중복 0, 오소유자 0, native 한도 포착 여부.

**경고**: world residual 게이트를 제거한다는 것과 무조건 Q를 승인한다는 것은 완전히 다르다. 각 측이 자기 물리 소유자·블록·형상으로 검증되지 않으면 Q는 발행하지 않는다.

## 10. 검증 가능한 원천 링크 및 코드 위치

GitHub 릴리스 커밋 전체: `ad8b4d377741fbd923f4d863b2cf9e462daff9a6`, 태그 `mc1.21.1-2.0.6-neoforge`.

- [Native event handler — contact local values](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/sable_rapier/src/main/rust/rapier/src/event_handler.rs#L24-L72)
- [Native dispatcher — voxel pair, CoM and witness transform](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/sable_rapier/src/main/rust/rapier/src/dispatcher.rs#L440-L690)
- [Native static/dynamic Collider / CCD shape](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/sable_rapier/src/main/rust/rapier/src/collider.rs)
- [Native step and clearing per-substep manifold info](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/sable_rapier/src/main/rust/rapier/src/lib.rs#L509-L550)
- [Native clearCollisions 15-double payload, hard cap 100](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/sable_rapier/src/main/rust/rapier/src/lib.rs#L1243-L1304)
- [Java Sable consumer — record interpretation / Plot rotationPoint](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/sable_rapier/src/main/java/dev/ryanhcode/sable/physics/impl/rapier/RapierPhysicsPipeline.java#L634-L710)
- [Java substep ordering](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/common/src/main/java/dev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem.java#L250-L298)
- [Plot embedded center](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/common/src/main/java/dev/ryanhcode/sable/sublevel/plot/LevelPlot.java#L120-L135)
- [Sable pre-solver exact block evidence via manifold_info_map](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/sable_rapier/src/main/rust/rapier/src/hooks.rs)
- Rapier 공식 contact / witness point / penetration 문서: https://docs.rs/rapier3d/latest/rapier3d/geometry/struct.TrackedContact.html

PDD 비교 소스는 사용자 제공 `PDD-0.3.1fix-FULL-SOURCE-UNVERIFIED-APPLY.zip`의 `src/main/java/com/physicaldestroydeform/collision/SableConsumerTargets.java`, `SableContactRegions.java`, `FrameMath.java`, `OwnerResolver.java`, `src/main/java/com/physicaldestroydeform/mixin/RapierCollisionTelemetryMixin.java`; 로그 `latestlog.txt`.

## 11. 결과 상태

- **원인 식별:** 완료. 잘못된 witness point 일치 게이트와 잠재적인 pose 시간 혼합 확인.
- **Plot 좌표계 해석:** Sable 대상 버전의 native LevelCollider·Java 소비자 경로 기준 완료.
- **외부 애드온 비교:** 업로드된 3개 JAR bytecode 수준 완료.
- **불확실성:** 특정 게임 사건의 관통 깊이와 substep 기여율, 릴리스 바이너리의 완전한 재현 빌드, native 한도 초과 실제 유실량.
- **코드 수정·빌드·테스트:** 요청 범위 밖으로 미실행. 다음 개발 버전에서 사용자 PDD 규약에 따라 전체 프로젝트 소스 ZIP 제공 필요.
