# PDD 0.1.3 API 및 인수 참조 보고서
## 설계 기준: PDD 개발용 설계 초안 3.3.1

**대상 환경:** Minecraft Java 1.21.1 · NeoForge 21.1.x · Create 6.0.10 · Create Aeronautics 1.3.2 · Sable 2.0.6  
**목적:** 초안 3.3.1의 요구 기능을 구현하기 위해 참조할 외부 API, 메서드 인수, 호출 경로, 반환값, 미확정 경계를 문서화한다.

> **설계 우선순위:** 이 문서는 PDD 0.1.2의 현재 코드 구조를 구현 방향의 기준으로 삼지 않는다. 0.1.2는 과거 코드의 재사용 가능성 및 현재 구현 상태를 확인하는 참고 자료일 뿐이다. 구현 방향은 초안 3.3.1을 따른다. 소스/API가 요구 기능을 제공하지 못하면 설계를 임의로 바꾸는 대신, 어댑터·별도 구현·미해결 과제로 분류한다.

---

## 1. 상태 표기와 결론

- **확정:** 업로드된 대상 버전의 바이너리 시그니처/바이트코드 또는 버전이 일치하는 소스에서 확인됨.
- **확정된 전달 구조, 의미 미확정:** 인수가 어디로 전달되는지는 확인했지만 물리량의 단위·좌표계·측정 시점은 추가 증거가 필요함.
- **미구현:** 대상 모드의 API는 존재하나 PDD가 이를 사용하도록 연결하는 코드는 아직 작성되지 않음.
- **차단:** 정확하지 않은 가정으로 구현하면 충돌 응답, 블록 위치, 물리 에너지 또는 서버 상태를 잘못 처리할 수 있어 해결 전 게임플레이 구현을 진행하지 않음.

### 핵심 판정

1. Sable의 `BlockSubLevelCollisionCallback`은 블록별 충돌 콜백 인터페이스다. 모든 블록을 대상으로 자동 호출되는 전역 충돌 이벤트가 아니다.
2. 콜백 인수는 블록 위치 두 개(두 번째는 nullable), 충돌 위치 벡터, 법선 방향 충돌 속도를 전달하는 구조다. 속도의 정확한 단위·부호·측정 시점과 네이티브 콜백 발생 횟수는 별도 검증 대상이다.
3. 콜백 반환값은 접선 이동과 충돌 제거 여부를 물리 측에 전달한다. 기존 콜백 결과를 무시하거나 바꾸면 Sable/Create의 기존 충돌 동작을 손상시킬 수 있다.
4. Sable 물리 틱 이벤트는 시스템과 `dt`를 제공하지만 충돌 쌍/충돌 에너지 데이터를 제공하는 이벤트는 아니다.
5. Aeronautics의 확인된 `physicsTick` 경로는 `BalloonMap` 갱신용이며 범용 충돌 이벤트가 아니다.
6. `RigidBodyHandle`은 구조물 단위 선속도/각속도 조회와 임펄스 적용에 쓰인다. 블록별 접촉 법선·접촉점 속도·충돌 임펄스를 자동 제공하지 않는다.
7. Minecraft의 블록 상태 변경 API는 월드 상태를 변경하지만, 그 사실만으로 Sable의 물리 collider가 모든 상황에서 올바른 시점에 갱신된다고 가정할 수 없다.
8. 초안 3.3.1의 충돌 손상·응력 전파·변형을 구현하려면, 아래 차단 항목을 해결한 후 API 어댑터를 설계해야 한다.

---

## 2. 초안 3.3.1 요구 기능과 API 대응

| 설계 기능 | 필요한 입력/행위 | 우선 참조 API | 판정 |
|---|---|---|---|
| 물리 구조물 충돌 감지 | 충돌한 블록 위치, 충돌 위치, 충돌 법선 방향 속도 | `BlockSubLevelCollisionCallback` | 블록 콜백 경로는 확인. 전체 구조물 범위·콜백 발생 규칙은 추가 검증 |
| 정적 월드 블록과 물리 구조물의 충돌 | 월드/서브레벨 좌표 변환, 충돌 블록 식별 | Sable voxel collider 생성 경로 및 Minecraft `BlockPos`/`Level` | 좌표 변환과 대상 범위 검증 전 차단 |
| 충돌 에너지 산출 | 유효 질량, 법선 상대 속도 | Sable 콜백 속도 + `RigidBodyHandle`/`PhysicsPipelineBody`의 질량 관련 API | 콜백 속도 단위·시점 및 질량 정의가 완전히 확인되지 않아 차단 |
| 경도 기반 손상 | 블록별 경도와 검증된 충돌 에너지 | Minecraft 블록 속성/블록 정의 + PDD 물성 등록 계층 | Minecraft의 파괴 저항은 PDD 경도 점수와 다른 값이므로 변환 규칙 필요 |
| 연성 기반 영구 변형 | 충돌 손상량, 연성 백분율 | PDD 자체 계산기 | 외부 모드가 제공하는 값이 아님. 초안의 수식/규칙에 따라 구현 |
| 탄성에 따른 응력 분배 | 이웃 블록, 탄성 백분율, 방문 기록/깊이 제한 | Minecraft `BlockPos`/`Level` 조회 + PDD 응력 전파기 | 외부 API가 제공하는 기능이 아님. PDD가 구현 |
| 블록 파괴/상태 변경 | 서버 권위의 블록 변경 | `ServerLevel.destroyBlock`, `setBlock`, `setBlockAndUpdate` | 기본 변경 API 확인. Sable collider 갱신 경로를 함께 검증해야 함 |
| 물리 틱 안전 경계 | 이벤트 단계, `dt`, 처리 큐 | `SablePrePhysicsTickEvent`, `SablePostPhysicsTickEvent` | 틱 이벤트는 확인. 안전한 월드 변경 시점은 실행 스레드/콜백 타이밍 검증 필요 |
| 물성 자동 등록 | 블록 정의 시점의 기본값 등록 및 캐시 | Minecraft `Block`/`BlockState`, Create 블록 클래스·레시피 정의 | 설계에 맞춰 초기화/블록 정의 계층에서 처리. 모든 블록별 수동 등록 방식은 기준으로 삼지 않음 |
| 상태 메모리 최적화 | 변경된 블록만 좌표별 상태 저장 | Minecraft `BlockPos` + PDD 희소 상태 저장소 | 외부 API 기능이 아니라 PDD 내부 저장 설계 |
| Create/Aeronautics 호환 | 기존 contraption/블록 충돌 응답 보존 | Aeronautics Mixin 경로 및 Sable 콜백 선택 경로 | 중복 감지와 응답 보존 테스트가 필요 |

---

## 3. Sable: 충돌 콜백 API

### 3.1 `BlockSubLevelCollisionCallback`

패키지: `dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback`

대상 JAR에서 확인된 시그니처:

```java
default double[] onCollision(
    int x, int y, int z,
    int otherX, int otherY, int otherZ,
    double impactX, double impactY, double impactZ,
    double impactVelocity,
    boolean hasOtherBlock
);

CollisionResult sable$onCollision(
    BlockPos hitBlockPos,
    BlockPos otherHitBlockPos,
    Vector3d impactPosition,
    double impactVelocity
);
```

`otherHitBlockPos`는 `hasOtherBlock == false`일 때 `null`로 전달된다.

| 인수 | 역할 | 확정 상태/주의 |
|---|---|---|
| `x, y, z` | 충돌 콜백이 붙은 블록의 위치를 구성 | 좌표 정수 3개가 `BlockPos`로 전달됨. 로컬/월드 좌표 구분은 네이티브 호출부까지 추적 필요 |
| `otherX, otherY, otherZ` | 상대 블록 위치를 구성 | `hasOtherBlock`이 참일 때만 두 번째 위치를 생성 |
| `impactX, impactY, impactZ` | `Vector3d impactPosition` 구성 | 벡터 구성은 확정. 좌표계/원점/단위는 호출 경로 확인 필요 |
| `impactVelocity` | `sable$onCollision`의 마지막 `double` 인수 | Sable 소스 주석은 충돌 법선 방향 속도로 설명. 에너지/임펄스가 아님. 단위·부호·측정 시점은 네이티브 구현 검증 필요 |
| `hasOtherBlock` | 두 번째 `BlockPos`를 만들지 결정 | false는 “상대 블록 위치가 제공되지 않음”을 뜻함. 다른 충돌 유형을 모두 특정하는 플래그로 확대 해석하지 말 것 |

`onCollision(...)`은 네이티브/pipeline 쪽에서 호출되는 브리지 메서드이며, 콜백 구현자가 실제 처리할 메서드는 `sable$onCollision(...)`이다. `onCollision`의 기본 구현은 위치 인수들을 객체로 변환하고 결과를 `double[4]`로 반환한다.

### 3.2 `CollisionResult`

중첩 타입: `BlockSubLevelCollisionCallback.CollisionResult`

```java
record CollisionResult(
    Vector3dc tangentMotion,
    boolean removeCollision
)
```

| 항목 | 역할 | PDD 주의 |
|---|---|---|
| `tangentMotion()` | 충돌 처리에서 사용할 접선 방향 이동 벡터 | 충돌 위치나 상대 속도와 다른 값. 손상 입력으로 직접 사용하지 않음 |
| `removeCollision()` | 해당 충돌을 제거할지 결정하는 boolean | `true`를 임의로 반환하면 기존 물리 충돌이 무시될 수 있음 |
| `NONE` | 영벡터와 `false`를 가진 기본 결과 | 원래 콜백 결과를 대체해도 된다는 뜻은 아님 |

기본 브리지 메서드는 결과를 다음 배열로 변환한다.

- `[0] = tangentMotion.x()`
- `[1] = tangentMotion.y()`
- `[2] = tangentMotion.z()`
- `[3] = removeCollision ? 1.0 : 0.0`

**초안 3.3.1에 따른 구현 원칙:** PDD의 손상 계산은 충돌 응답을 변경하는 것과 분리한다. 기존 콜백의 파괴/충돌 응답을 보존하면서 샘플을 관측할 수 있는 방식인지 먼저 확인한다. 단순히 `NONE`을 반환하는 래퍼는 기존 동작을 바꿀 수 있으므로 사용을 승인하지 않는다.

출처:
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback.java

---

## 4. Sable: 블록별 콜백 선택 API

패키지: `dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback`

```java
static BlockSubLevelCollisionCallback sable$getCallback(BlockState state);
static boolean hasCallback(BlockState state);
BlockSubLevelCollisionCallback sable$getCallback();
```

| API | 입력 | 반환/역할 | 설계상 의미 |
|---|---|---|---|
| `sable$getCallback(BlockState)` | 현재 블록 상태 | 해당 상태의 콜백 또는 `null` | collider 생성 경로가 사용할 콜백을 선택 |
| `hasCallback(BlockState)` | 현재 블록 상태 | 콜백 존재 여부 | collider 경로/테스트에서 콜백 지원 여부 확인 |
| 인스턴스 `sable$getCallback()` | 없음 | 블록 구현이 제공하는 콜백 | 해당 인터페이스를 구현한 블록이 콜백을 직접 지정 |

확인된 선택 순서는 다음과 같다.

1. `state.getBlock()`이 `BlockWithSubLevelCollisionCallback`을 구현하면 해당 블록의 콜백을 반환한다.
2. 그렇지 않으면 Sable의 `FRAGILE` 블록 상태 속성을 확인한다.
3. `FRAGILE`이면 Sable 기본 `FragileBlockCallback`을 반환한다.
4. 그 외에는 `null`을 반환한다.

따라서 이 API는 전역 이벤트 구독이 아니다. **일반 블록 전체에 PDD 콜백이 자동 등록된다고 가정할 수 없다.** 초안 3.3.1의 전체 블록 대상 요구사항을 충족하려면 콜백이 없는 블록을 대상으로 한 확장 경로를 별도로 확정해야 한다.

Sable의 `VoxelNeighborhoodState`는 콜백이 있는 블록을 collider 형상 최적화에서 특별 취급한다. 그러므로 모든 블록에 콜백을 붙이는 방식은 단순한 이벤트 구독보다 물리 형상 생성·성능에 더 큰 영향을 줄 수 있다.

출처:
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/block/BlockWithSubLevelCollisionCallback.java
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/physics/chunk/VoxelNeighborhoodState.java

---

## 5. Sable: 취약 블록 콜백 및 기존 동작

Sable 2.0.6에는 `FragileBlockCallback`, `ExplosiveBlockCallback`, `BeehiveBlockCallback` 등 특정 블록의 충돌 응답을 구현하는 콜백이 있다.

확인된 기본 `FragileBlockCallback` 경로:
1. 임계 속도(기본값 `4.0`)와 전달된 `impactVelocity`를 비교한다.
2. 임계값 미만이면 `CollisionResult.NONE`을 반환한다.
3. 임계값을 넘으면 현재 스테핑 중인 `SubLevelPhysicsSystem`에서 서버 레벨을 가져온다.
4. `hitBlockPos`의 현재 상태와 `FRAGILE` 속성을 확인한다.
5. 조건에 따라 `ServerLevel.destroyBlock(pos, true)`를 호출하고 충돌 제거 응답을 반환한다.

`ExplosiveBlockCallback`은 별도 임계값 `5.0`으로 TNT를 점화/교체하는 특수 동작을 수행한다. `BeehiveBlockCallback` 역시 별도 임계값과 블록 엔티티 처리를 가진다.

**의미:** Sable 콜백은 관측 알림만이 아니라 블록 파괴와 충돌 응답을 직접 바꿀 수 있다. PDD는 기존 콜백과의 우선순위, 중복 파괴 방지, 같은 물리 단계에서의 월드 변경 안전성을 검증해야 한다.

---

## 6. Sable: 물리 틱 이벤트

패키지: `dev.ryanhcode.sable.api.event`

```java
void prePhysicsTick(SubLevelPhysicsSystem system, double dt);
void postPhysicsTick(SubLevelPhysicsSystem system, double dt);
```

| 인수 | 역할 | 주의 |
|---|---|---|
| `system` | 해당 서브레벨 물리 시스템 | 이 객체가 충돌 쌍 자체를 전달하는 것은 아님 |
| `dt` | 물리 하위 단계의 시간 간격 | 속도/에너지/충돌 강도가 아님 |

Sable의 Java 측 물리 시스템은 `prePhysicsTick` → 물리 파이프라인 스텝 → 포즈 갱신 → `postPhysicsTick` 순서로 이벤트를 호출한다. NeoForge 구현은 이벤트 버스에 해당 이벤트를 게시한다.

이 이벤트의 역할은 **물리 단계 경계 알림**이다. 초안 3.3.1의 충돌 데이터는 콜백 또는 검증된 별도 경로에서 수집해야 한다. 틱 이벤트만 구독해서 충돌 에너지를 직접 얻을 수는 없다.

출처:
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/event/SablePrePhysicsTickEvent.java
- https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/event/SablePostPhysicsTickEvent.java

---

## 7. Sable: 강체 핸들 및 질량 API

### 7.1 `RigidBodyHandle`

패키지: `dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle`

주요 시그니처:

```java
static RigidBodyHandle of(ServerLevel level, PhysicsPipelineBody body);
static RigidBodyHandle of(ServerSubLevel subLevel);

void applyImpulseAtPoint(Vector3dc position, Vector3dc impulse);
void applyImpulseAtPoint(Vec3 position, Vec3 impulse);
void applyLinearAndAngularImpulse(Vector3dc impulse, Vector3dc torque);
void applyLinearAndAngularImpulse(Vector3dc impulse, Vector3dc torque, boolean wakeUp);
void applyLinearImpulse(Vector3dc impulse);
void applyAngularImpulse(Vector3dc impulse);
void applyTorqueImpulse(Vector3dc torque);

Vector3d getLinearVelocity(Vector3d dest);
Vector3d getAngularVelocity(Vector3d dest);
void addLinearAndAngularVelocity(Vector3dc linearVelocity, Vector3dc angularVelocity);
void teleport(Vector3dc position, Quaterniondc orientation);
boolean isValid();
```

| API/인수 | 역할 | 초안 3.3.1에서의 용도/제한 |
|---|---|---|
| `of(level, body)` | 레벨과 물리 바디에서 핸들 생성 | 대상 바디를 알고 있을 때 조회. 유효성/널 가능성 검사 필요 |
| `of(subLevel)` | 서브레벨의 핸들 생성 | 구조물 전체의 강체 속도 조회 후보 |
| `position` | 임펄스를 적용할 위치 | 접촉점 위치라고 임의 가정하지 말 것. 좌표계 확인 필요 |
| `impulse` | 선형 임펄스 벡터 | 충돌에서 실제 적용된 임펄스의 기록을 반환하는 API가 아님 |
| `torque` | 각운동 관련 벡터 | 호출 API의 실제 단위 체계를 확인해야 함 |
| `wakeUp` | 잠든 바디를 깨울지 여부 | 충돌 응답에 영향을 줄 수 있으므로 단순 관측용으로 호출하지 않음 |
| `getLinearVelocity(dest)` | 선속도 벡터를 `dest`에 기록 | 구조물 속도 후보. 블록 접촉점의 상대 속도와 같지 않음 |
| `getAngularVelocity(dest)` | 각속도 벡터를 `dest`에 기록 | 접촉점 속도 계산에 추가로 쓰일 수 있으나 자세/접촉점 변환 필요 |
| `teleport(position, orientation)` | 물리 바디 자세를 강제로 변경 | 손상/변형 계산용으로 사용하지 않음 |
| `isValid()` | 내부 바디가 제거됐는지 확인 | 지연 처리 큐의 오래된 참조 검사 |

`RigidBodyHandle`은 실제 파이프라인 호출을 감싸는 핸들이다. 이 API를 호출한다고 해서 블록 단위 충돌 정보가 생기는 것은 아니다.

### 7.2 `PhysicsPipelineBody`

```java
int getRuntimeId();
MassData getMassTracker();
boolean isRemoved();
```

| API | 역할 | 한계 |
|---|---|---|
| `getRuntimeId()` | 물리 객체 런타임 식별자 | 영구 저장 ID로 사용하지 않음 |
| `getMassTracker()` | 질량 데이터 객체 접근 | `MassData` 내부 필드·유효 질량 정의·단위는 별도 검증 필요 |
| `isRemoved()` | 물리 객체 제거 여부 | 객체가 유효한 질량/속도를 제공하는지 보장하지 않음 |

### 7.3 `PhysicsPipeline`

PDD와 직접 관련 있는 시그니처:

```java
void prePhysicsTicks();
void physicsTick(double dt);
void postPhysicsTicks();
void handleBlockChange(
    SectionPos sectionPos,
    LevelChunkSection chunkSection,
    int x, int y, int z,
    BlockState oldState,
    BlockState newState
);
Vector3d getLinearVelocity(PhysicsPipelineBody body, Vector3d dest);
Vector3d getAngularVelocity(PhysicsPipelineBody body, Vector3d dest);
void wakeUp(PhysicsPipelineBody body);
```

`handleBlockChange(...)`는 물리 파이프라인에 블록 상태 변경을 알리는 API다. 인수의 좌표가 어떤 기준(섹션 상대/청크 상대/기타)인지 호출부의 좌표 계산과 함께 확인해야 한다. Minecraft 월드의 블록을 변경하는 API가 아니며, 물리 collider 자료를 갱신하는 측의 알림이다.

---

## 8. Sable의 실제 콜백 연결 경로

Java 측에서 확인된 경로:

```text
블록 상태를 물리 collider 데이터로 변환
  └─ RapierVoxelColliderBakery.buildPhysicsDataForBlock(BlockState)
       └─ BlockWithSubLevelCollisionCallback.sable$getCallback(state)
            └─ 콜백 또는 null 반환
       └─ Rapier3D.createVoxelColliderEntry(..., callback)
            └─ native newVoxelCollider(..., callback)
                 └─ [네이티브 Rapier 충돌 처리]
                      └─ BlockSubLevelCollisionCallback.onCollision(...)
                           └─ sable$onCollision(...)
```

여기서 Java 측 `RapierVoxelColliderBakery`가 블록별 콜백을 선택해 네이티브 voxel collider 생성 경로로 넘기는 사실은 확인됐다. 그러나 `Rapier3D.newVoxelCollider(...)` 및 `Rapier3D.step(...)`은 native 경계다.

따라서 현재 확인된 것은 Java 쪽의 콜백 연결 경로다. 네이티브 구현의 소스/디버그 증거 없이 아래 항목을 확정했다고 말할 수 없다.
- 동일 충돌에 대한 콜백 호출 횟수
- 충돌이 접촉을 시작할 때만 호출되는지, 지속 접촉 중에도 반복 호출되는지
- `impactVelocity`의 단위·부호·충돌 전후 측정 시점
- 모든 종류의 충돌 쌍에서 `otherHitBlockPos`가 제공되는지

이 네 항목은 충돌 손상 로직의 구현 승인 전에 해결해야 한다.

---

## 9. Create Aeronautics 1.3.2

JAR은 번들 형태이며 내부에 `aeronautics-neoforge-1.21.1-1.3.2.jar`가 포함되어 있다.

### 9.1 `AeronauticsCommonEvents.physicsTick`

확인된 시그니처:

```java
static void physicsTick(SubLevelPhysicsSystem system, double dt);
```

확인된 동작:
```java
ServerLevel level = system.getLevel();
BalloonMap.physicsTick(level, dt);
```

| 인수 | 역할 | PDD 판단 |
|---|---|---|
| `system` | Sable 서브레벨 물리 시스템 | 물리 틱 시스템 접근 |
| `dt` | Sable 물리 하위 단계의 시간 간격 | 충돌 속도나 에너지가 아님 |

이 경로는 풍선 맵 갱신이다. 범용 충돌 보고 이벤트로 사용하지 않는다.

### 9.2 `SableCommonEventsMixin.onBlockChange`

```java
private static void onBlockChange(
    ServerLevel level,
    LevelChunk chunk,
    int x, int y, int z,
    BlockState oldState,
    BlockState newState,
    CallbackInfo ci
);
```

이 Mixin은 블록 변경 시 Aeronautics의 `onBlockModifiedEvent(...)`를 호출한다. 확인된 Aeronautics 동작은 `BalloonMap.updateNearbyBalloons(pos, oldState, newState)`로 주변 풍선 상태를 갱신하는 것이다.

이는 블록 변경 알림이지 충돌 이벤트가 아니다. PDD의 블록 파괴가 이 경로를 통과하는지와 Sable 물리 collider 갱신이 따로 보장되는지는 각각 검증해야 한다.

### 9.3 `ContraptionColliderMixin`

확인된 관련 메서드:

```java
static Vec3 collide(Vec3 movement, Entity entity);

private static Vec3 sable$overrideCollisionStrength(
    Vec3 movement,
    Entity entity,
    AbstractContraptionEntity contraption,
    LocalRef<Vec3> initialMovement
);
```

이 Mixin은 Create의 `AbstractContraptionEntity`와 엔티티 이동/충돌 응답을 조정하는 구현 지점이다. `movement`는 이동 벡터이며, 이 인수만으로 질량·접촉 법선·충돌 에너지를 알 수 없다. 일반적인 Sable 블록-블록 충돌 콜백과 동일한 경로로 취급하지 않는다.

**호환성 규칙:** 이 Mixin을 PDD에서 직접 호출하지 않는다. 실제 충돌을 이중으로 감지할 수 있는지 호출 경로를 추적하고, PDD는 동일 충돌을 한 번만 손상 입력으로 처리해야 한다.

---

## 10. Minecraft 1.21.1 기본 블록 및 월드 API

### 10.1 조회 및 변경

| API | 인수 | 역할 | PDD 적용 시 주의 |
|---|---|---|---|
| `Level.getBlockState(BlockPos)` | 블록 좌표 | 현재 블록 상태 조회 | 서버/클라이언트 레벨 구분 |
| `ServerLevel.destroyBlock(BlockPos, boolean)` | 위치, 드롭 처리 여부 | 월드의 블록 파괴 경로 | `true`는 드롭 처리. 같은 블록 중복 파괴 방지 필요 |
| `Level.setBlock(BlockPos, BlockState, int)` | 위치, 새 상태, 업데이트 플래그 | 블록 상태 변경 | 업데이트 플래그를 목적에 맞게 선택 |
| `Level.setBlockAndUpdate(BlockPos, BlockState)` | 위치, 새 상태 | 블록 상태 변경 및 갱신 경로 | 물리 collider 갱신을 별도로 확인 |
| `BlockState.getBlock()` | 없음 | 상태에 해당하는 블록 정의 반환 | 물성 기본값 캐시 키 후보 |
| 블록 기본 파괴 저항 관련 속성 | 블록 정의/상태 | Minecraft 기본 파괴 저항 제공 | PDD의 0..255 경도 점수와 동일하지 않음 |

참조:
- https://mappings.dev/1.21.1/net/minecraft/world/level/Level.html
- https://mappings.dev/1.21.1/net/minecraft/server/level/ServerLevel.html
- https://mappings.dev/1.21.1/net/minecraft/world/level/block/state/BlockBehaviour%24Properties.html

### 10.2 물리 collider 갱신 경계

Sable의 `PhysicsPipeline.handleBlockChange(...)`는 기존 collider 자료에 블록 변경을 반영하기 위한 물리 측 API다. `ServerLevel.setBlock(...)`을 호출했다는 사실만으로 정적 월드와 서브레벨 내부의 collider가 동일하게 갱신된다고 간주하지 않는다.

PDD가 파괴/변형으로 블록 상태를 바꿀 경우 확인할 것:
1. 일반 Minecraft 블록 변경 이벤트가 Sable의 블록 변경 경로를 통과하는가?
2. 서브레벨 내부 블록도 같은 경로로 갱신되는가?
3. 물리 스텝 중 변경이 큐잉되는가, 즉시 반영되는가?
4. collider 갱신 완료 전 재충돌이 발생할 수 있는가?

이 검증은 유령 충돌과 파괴된 블록의 collider 잔존을 방지하기 위한 필수 항목이다.

---

## 11. 물성 자동 등록 API 경계

초안 3.3.1의 방향에 맞춰 물성 자동 등록은 모든 블록을 런타임에 개별 순회하며 수동 등록하는 방식으로 설계하지 않는다. 블록 정의/초기화 단계에서 기본 물성을 추론하고, 블록 ID 단위 캐시에 등록하며, 명시적 사용자 설정을 최우선으로 한다.

| 데이터 | 후보 출처 | 주의 |
|---|---|---|
| Minecraft 파괴 저항 | 블록 정의의 기본 속성 | PDD 경도 점수로 변환하는 별도 매핑이 필요 |
| 폭발 저항 | 블록 정의/폭발 처리 관련 기본 속성 또는 별도 테이블 | 모든 블록에서 동일 API로 안정적으로 읽을 수 있는지 대상 버전 확인 |
| 제작 재료 | Create/Minecraft 레시피 및 블록 정의 | 레시피는 블록의 완전한 재료 구성/물성을 보장하지 않음 |
| 연성 | PDD 기본값/재료 추론/사용자 오버라이드 | 외부 모드가 범용 연성 값을 제공한다고 가정하지 않음 |
| 탄성 | PDD 기본값/재료 추론/사용자 오버라이드 | 외부 모드가 범용 탄성 값을 제공한다고 가정하지 않음 |

**참고:** Create 레시피만으로 모든 블록의 물성을 완벽히 결정할 수는 없다. 레시피가 없는 블록, 여러 입력으로 제작되는 블록, 기능성 블록, 액체/특수 블록에는 기본값 또는 명시적 예외가 필요하다. 이는 자동 등록 설계를 폐기해야 한다는 뜻이 아니라, 추론 규칙과 예외 규칙을 분리해야 한다는 뜻이다.

---

## 12. PDD 자체가 구현해야 하는 기능

다음 기능은 Sable/Aeronautics/Minecraft API가 완성품으로 제공하는 기능이 아니다. 초안 3.3.1에 따라 PDD가 구현해야 한다.

- 검증된 충돌 입력을 PDD의 충돌 샘플로 변환하는 어댑터
- 유효 질량과 법선 상대 속도를 이용한 충돌 에너지 계산
- 동일 충돌 중복 제거/충돌 샘플 병합
- 블록 ID별 기본 물성 추론 및 캐시
- 사용자 설정이 자동 추론값보다 우선하는 규칙
- 경도 기반 손상 계산
- 연성 기반 영구 변형 계산
- 탄성 기반 주변 응력 분배
- 응력 전파의 방문 기록, 깊이/블록 수/최소 임계값 제한
- 변경된 블록만 상태를 보유하는 희소 손상 상태 저장
- 서버 권위의 블록 파괴·변형 확정
- 클라이언트 균열 시각화/동기화
- Sable collider 갱신 확인 및 기존 Aeronautics/Create 동작과의 호환 처리

이 목록은 0.1.2의 현재 클래스 구성을 따라야 한다는 의미가 아니다. 초안 3.3.1의 요구 기능을 외부 API와 PDD 자체 구현으로 분리한 것이다.

---

## 13. 개발 차단 목록

| ID | 미확정 항목 | 구현 전 완료 기준 |
|---|---|---|
| API-01 | 네이티브 Rapier가 콜백을 호출하는 조건/횟수 | 같은 충돌이 접촉 시작/지속/종료 중 언제, 몇 번 보고되는지 소스 또는 테스트로 확정 |
| API-02 | `impactVelocity`의 단위·부호·측정 시점 | 정확한 물리량과 단위를 확인하고 정적/동적 충돌 테스트로 검증 |
| API-03 | `impactPosition` 및 두 `BlockPos`의 좌표계 | 정적 월드↔서브레벨, 서브레벨↔서브레벨 좌표 변환 테스트 통과 |
| API-04 | 콜백이 없는 일반 블록의 전체 충돌 감지 방법 | 모든 대상 블록에 대해 호환성·성능을 유지하는 등록/후킹 경로 확정 |
| API-05 | 기존 콜백 응답 보존 방식 | Sable 취약 블록, TNT, 벌집, Create 특수 블록의 원래 응답을 보존하는 테스트 통과 |
| API-06 | 질량/유효 질량의 의미와 단위 | `MassData` 필드 및 Rapier 쪽 질량 정의 확인 |
| API-07 | 물리 틱 중 서버 월드 변경의 안전한 시점 | 실행 스레드와 이벤트/콜백 타이밍을 확인하고 지연 큐 처리 시점 확정 |
| API-08 | 파괴 후 collider 갱신 경로 | 정적 블록과 서브레벨 블록 각각에서 파괴 직후 충돌 형상 갱신 확인 |
| API-09 | Create contraption과 Sable sublevel의 충돌 경계 | 동일 충돌이 두 경로에서 중복 집계되지 않음을 테스트 |
| API-10 | 폭발 저항/제작 재료 기반 물성 추론 범위 | 값의 출처, fallback, 사용자 오버라이드 우선순위를 명세 |

**승인 규칙:** API-01~API-08은 충돌 손상/파괴/변형 런타임 구현 전 해결한다. API-09는 Create/Aeronautics 구조물 호환 기능을 활성화하기 전에 해결한다. API-10은 자동 물성 등록을 완성하기 전에 해결한다. 해결되지 않은 항목은 임의의 상수나 추측으로 채우지 않는다.

---

## 14. 버전 및 출처

### 업로드된 바이너리
- `sable-neoforge-1.21.1-2.0.6.jar`
  - `BlockSubLevelCollisionCallback`
  - `BlockWithSubLevelCollisionCallback`
  - `FragileBlockCallback`, `ExplosiveBlockCallback`, `BeehiveBlockCallback`
  - `SubLevelPhysicsSystem`, `PhysicsPipeline`, `PhysicsPipelineBody`
  - `RigidBodyHandle`, `SablePrePhysicsTickEvent`, `SablePostPhysicsTickEvent`
  - `VoxelNeighborhoodState`
- `create-aeronautics-bundled-1.21.1-1.3.2.jar`
  - 내부 `aeronautics-neoforge-1.21.1-1.3.2.jar`
  - `AeronauticsCommonEvents`
  - `SableCommonEventsMixin`
  - `ContraptionColliderMixin`
- `create-1.21.1-6.0.10.jar`
- PDD 0.1.1 및 0.1.2 ZIP은 과거 코드 구조/재사용 가능성 확인용으로만 참고. 설계 기준으로 사용하지 않음.

### 공개 소스 및 매핑
- Sable callback: https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback.java
- Sable callback selector: https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/block/BlockWithSubLevelCollisionCallback.java
- Sable voxel classification: https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/physics/chunk/VoxelNeighborhoodState.java
- Sable pre-tick event: https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/event/SablePrePhysicsTickEvent.java
- Sable post-tick event: https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/event/SablePostPhysicsTickEvent.java
- Minecraft 1.21.1 Level mapping: https://mappings.dev/1.21.1/net/minecraft/world/level/Level.html
- Minecraft 1.21.1 ServerLevel mapping: https://mappings.dev/1.21.1/net/minecraft/server/level/ServerLevel.html
- Minecraft 1.21.1 BlockBehaviour.Properties mapping: https://mappings.dev/1.21.1/net/minecraft/world/level/block/state/BlockBehaviour%24Properties.html

**최종 판정:** 이 문서는 초안 3.3.1의 기능을 기준으로 API와 인수를 매핑한 구현 참조 문서다. Java 측 API 시그니처와 일부 호출 경로는 확인했으나 네이티브 Rapier의 충돌 호출 조건 및 물리량의 정확한 단위/시점은 완전히 확정되지 않았다. 이 미확정 사항을 해결하기 전에는 충돌 손상 기능의 구현을 승인하지 않는다.
