# 侦查无人机 — 模型精细化与动作系统方案

> 状态：**方案阶段，未动代码**。选定路线后再实施。

---

## 0. 现状盘点

| 位置 | 现状 | 问题 |
|------|------|------|
| `client/DroneModel.java` | `body` / `arm0..3` / `pod0..3 → rotor0..3`，全部平铺在 `root` 下 | 无层级骨架，无法整体收拢机臂、无法独立俯仰云台 |
| `client/DroneModel#setupAnim` | 只做 `rotor.yRot = ageInTicks * 1.7F` | 单一动画，无状态概念 |
| `entity/ReconDroneEntity` | `defineSynchedData` 只有 `DATA_PILOT_ID`、`DATA_MARKS` | `thrown` / `throwTicks` **未同步**，客户端拿不到弹道状态 |
| `client/DroneRenderer` | `renderToBuffer(..., -1)` | 无发光层，镜头/尾灯不会自发光 |
| 贴图 | `textures/entity/recon_drone.png`，64×64 | 部件变多后 UV 空间不够 |

**核心结论：投掷动画现在做不出来，不是模型的问题，是数据没同步。**

---

## 1. 必须先补的同步缺口

### 1.1 不要逐 tick 同步进度，只同步「事件」

`SynchedEntityData` 每次 `set()` 都会进 dirty 集合，tick 末打包发包。如果每 tick 同步一个 `float throwProgress`，就是 20 包/秒/实体，浪费且没必要。

**推荐做法：只同步事件的起始 tick，客户端自己算进度。**

```java
// 新增两个 accessor
private static final EntityDataAccessor<Boolean> DATA_THROWN =
        SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.BOOLEAN);
private static final EntityDataAccessor<Integer> DATA_THROW_START =
        SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

// defineSynchedData 里
builder.define(DATA_THROWN, false);
builder.define(DATA_THROW_START, 0);

// markThrown() 改成写 entityData
public void markThrown() {
    this.thrown = true;
    this.throwTicks = 0;
    this.entityData.set(DATA_THROWN, true);
    this.entityData.set(DATA_THROW_START, this.tickCount);
}
```

客户端侧：

```java
public boolean isThrown()     { return this.entityData.get(DATA_THROWN); }
public int getThrowStart()    { return this.entityData.get(DATA_THROW_START); }
```

渲染时进度天然平滑，因为 `ageInTicks` 本身就是 `tickCount + partialTick`：

```java
float throwAge = ageInTicks - entity.getThrowStart();   // 0.0 → 60.0，逐帧连续
```

> 关键点：`DroneRenderer` 已经把 `entity.tickCount + partialTick` 当 `ageInTicks` 传进来了，
> 所以 `setupAnim` 内部**能反推出 partialTick**（`ageInTicks - Mth.floor(ageInTicks)`），
> 不需要额外改 `EntityModel` 的签名。

### 1.2 朝向与位置本来就在同步

`ServerEntity#sendChanges` 每 tick 比较 `yRot` / `xRot`，变化就发 `ClientboundMoveEntityPacket.Rot`；
`Registries` 里已设 `updateInterval(1)`。所以 `tickThrown` 算出来的弹道朝向**客户端已经能收到**，
不需要额外同步。缺的只有 `thrown` 这个布尔状态。

### 1.3 其余可选 accessor

| 字段 | 类型 | 用途 | 必要性 |
|------|------|------|--------|
| `DATA_THROWN` | BOOLEAN | 是否在弹道中 | **必须** |
| `DATA_THROW_START` | INT | 弹道起始 tick | **必须** |
| `DATA_ROTOR_POWER` | FLOAT | 旋翼目标转速 0..1 | 可选（也可由状态推导） |
| `DATA_ARM_DEPLOY` | FLOAT | 机臂展开度 0..1 | 可选（同上） |
| `DATA_GIMBAL_PITCH` | FLOAT | 云台俯仰 | 可选（接管时直接用 `entity.getXRot()`） |

---

## 2. 三条实现路线

### 路线 A · 动作优先
保留现有几何体，只把 Part 拆出层级，补 `thrown` 同步，加 5 段动作。
- **优点**：改动面最小，风险低
- **缺点**：「精细模型」这个诉求没被满足，模型本身还是粗糙

### 路线 B · 重塑骨架（推荐）
重新设计部件树（云台 / 机臂 pivot / 起落架 / 发光层），贴图扩到 128×64，再做 A 的全部动作。
- **优点**：零依赖，用原版 `ModelPart` 就能做到 90% 效果；模型和动作一起交付
- **缺点**：需要重画贴图，工作量大一些

### 路线 C · GeckoLib
引入骨骼动画库，用关键帧 + 缓动曲线写动画。
- **优点**：动画与代码解耦，后续加动作最省事
- **缺点**：**多一个前置模组**；需重写整个渲染层；对这个体量的模型属于杀鸡用牛刀

---

## 3. 模型部件树改造（路线 B）

```
root
├─ frame                      整体，承载悬停浮动 / 回收缩放
│  ├─ hull_lower              下半壳
│  ├─ hull_upper              上半壳
│  ├─ nose                    机头
│  ├─ spine                   顶部脊线
│  ├─ antenna                 天线（细杆 + 顶端小球）
│  ├─ gimbal_yoke             云台支架（独立 pitch）
│  │  └─ gimbal_lens          镜头（发光层）
│  └─ tail_light              尾灯（发光层）
├─ arm[4]                     机臂 pivot 落在机身四角
│  └─ pod
│     ├─ motor
│     └─ rotor                双叶交叉
└─ skid_l / skid_r            起落架（可收放）
```

### 3.1 机臂 pivot 必须挪位置

现在是绕**机身中心**旋转：

```java
PartPose.offsetAndRotation(0.0F, ROOT_Y, 0.0F, 0.0F, angle, 0.0F)   // 绕中心，臂长 5
```

这样机臂一旦做收拢动画就会整条扫过机身。改成 **pivot 落在机身四角**：

```java
float hx = 3.0F * Mth.cos(angle);   // 角点
float hz = 3.0F * Mth.sin(angle);
PartPose.offsetAndRotation(hx, ROOT_Y, hz, 0.0F, angle, 0.0F)       // 臂长改为 2.0
```

收拢动画就变成 `arm.zRot` 从 `0` 抬到 `-70°`（向上折叠），不会穿模。

### 3.2 发光层

仿原版 `EyesLayer`：

```java
this.model.renderToBuffer(poseStack,
        buffer.getBuffer(this.model.renderType(GLOW_TEXTURE)),
        LightTexture.FULL_BRIGHT,          // 而不是 packedLight
        OverlayTexture.NO_OVERLAY,
        -1);
```

或者用 `RenderType.entityTranslucentEmissive(glowTexture)`。发光贴图只画镜头和尾灯，
其余像素留空（alpha = 0）。

### 3.3 贴图扩容

64×64 → **128×64**。`tools/generate_textures.py` 里 `build_entity_sheet()` 要加对应的 UV 布局，
每个新部件分到独立的 UV 区域。

---

## 4. 动作清单

| # | 动作 | 触发条件 | 时长 | 实现要点 | 难度 |
|---|------|----------|------|----------|------|
| 1 | 悬停怠速 | 默认状态 | 循环 | `frame.y = sin(age * 0.12) * 0.6`，叠加 ±2° 滚转 | 低 |
| 2 | 旋翼加速 | `isThrown()` 上升沿 | 6 tick | 转速 `0 → 满`，`Mth.clampedLerp` 缓动 | 低 |
| 3 | 投掷弹道 | `isThrown() == true` | 60 tick | 后仰 → 前倾 25° → 跟随速度矢量 → 回正 | 中 |
| 4 | 机臂展开 | 落地 / 接管 | 15 tick | `arm.zRot` 从 `-70° → 0°` | 中 |
| 5 | 云台锁定 | 接管 | 即时 | `gimbal_yoke.xRot = clamp(entity.getXRot(), -60, 60)` | 低 |
| 6 | 回收折叠 | 回收键按下 | 10 tick | 机臂收回 + 起落架收 + 整体 scale `0.6 → 0` | 中 |
| 7 | 受击抖动 | `hurtTime > 0` | 10 tick | 随机位移抖动 + 尾灯闪红 | 低 |

---

## 5. 投掷动画时间线（0 → 60 tick）

| 阶段 | 时间 | 机体俯仰 | 旋翼 | 机臂 |
|------|------|----------|------|------|
| 出手 | t = 0 | 后仰 15°（被向上抛） | 怠速起步 | 收拢 |
| 加速 | 0 – 6 | 后仰 → 前倾 25° | 爬升到满速 | 收拢 |
| 弹道 | 6 – 40 | 由速度矢量驱动 + ±3° 滚转正弦 | 满速 | 收拢 |
| 减速 | 40 – 60 | 速度 < 0.15 时姿态回正 | 满速 → 巡航 | 45 起开始外展 |
| 悬停 | t = 60 | 回正 | 巡航 | 完全展开 |

> t = 6–40 段的俯仰/偏航**已经在 `tickThrown()` 里算好了**（`setYRot` / `setXRot` 那两行），
> 客户端通过 `ClientboundMoveEntityPacket.Rot` 收到，动画只需叠加滚转扰动即可。

---

## 6. 关键技术点

1. **进度用 `ageInTicks - throwStartTick` 推算**，不要逐 tick 同步 float。
2. **`partialTick` 从 `ageInTicks` 反推**（`ageInTicks - floor(ageInTicks)`），不改 `EntityModel` 签名。
3. **机臂 pivot 挪到机身四角**，否则收拢动画穿模。
4. **发光层用 `LightTexture.FULL_BRIGHT` 单独 pass**，不要试图在主 pass 里调颜色。
5. **性能**：`setupAnim` 每帧对每个可见无人机调用一次，约 20 个 `ModelPart` 的三角函数计算，可忽略。

---

## 7. 工作量估算

| 路线 | 内容 | 估算 |
|------|------|------|
| A | 层级拆分 2h + 同步 0.5h + 5 段动作 3h + 调参 1h | 约半天 ~ 1 天 |
| B | A 全部 + 部件树重画 3h + 贴图重画 3h + 发光层 2h | 约 1.5 天 |
| C | B 部分 + GeckoLib 接入 2h + 动画 JSON 4h + 渲染层重写 3h | 约 2 天，且有前置依赖 |

---

## 8. 推荐

**路线 B**。

- 路线 A 不解决「模型精细」这个原始诉求，只加动作的话模型还是那个粗糙的方块堆。
- 路线 C 引入前置模组 —— 之前专门确认过「这个 mod 有没有前置」，说明在意这一点；
  而且它会替换整条渲染管线，风险高，收益主要是「动画更好写」，对 20 个部件的模型不划算。
- 路线 B 用原版 `ModelPart` + 手写 `Mth` 缓动，零依赖就能做到 90% 的效果。

---

## 9. 实施顺序（选定后）

1. 补 `DATA_THROWN` / `DATA_THROW_START` 同步 ← 这一步单独就能让投掷动画成为可能
2. 重构 `DroneModel` 部件树 + 挪机臂 pivot
3. 重画贴图（128×64）+ 新增发光贴图
4. `DroneRenderer` 加发光层 pass
5. `setupAnim` 改写为状态机
6. 逐段调参
7. `gradle build` 验证
