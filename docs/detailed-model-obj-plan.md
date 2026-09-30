# 侦查无人机 · 精细模型方案（零前置 OBJ 正路）

> 状态：**已实施并交付**（`DroneMeshes` / `DroneRig` / `DroneRenderer` 已上线，旧 `DroneModel` 已删；
> 网格 `tools/generate_drone_mesh.py` 产出 21 部件 / 260 面，`tools/verify_mesh.py` 0 失败 0 警告）。
> 本文回答两个问题：① `E:\minecraft-mod-精细模型方案.md` 能不能直接用到本 mod 上；
> ② 我的方案是什么。
> 所有结论均以**本仓库已下载的 `neoforge-21.1.251-sources.jar` 反编译源码**为准，不是凭印象。
>
> **实施后新增的实测结论**（比本文更细，也纠正了本文两处）：
> - `ClientNeoForgeMod:73/82` 已把 `ObjLoader.INSTANCE` 注册为 `neoforge:obj` 加载器 **和** 客户端 reload listener
>   → 加载器不用自己注册；**但自己烘焙的缓存要自己清**（`DroneMeshes` 实现 `ResourceManagerReloadListener`）
> - `CompositeRenderable.Mesh.render` 调的是 `IVertexConsumerExtension` 的 9 参 `putBulkData`
>   → **我们传入的 lightmap / overlay 覆盖烘焙值**（`applyBakedLighting` 取 `max(传入, 烘入)`）
>   ⇒ 受击红闪成立；但 **`LightTexture.FULL_BRIGHT` 并不能让部件"发光"**（见 §1 ② 的更正框），
>   发光件必须走 `RenderType.eyes()` 这种**加色**类型，且因此**必须拆成独立 `.obj`** —— 这才是拆文件的真正理由
> - `TextureAtlas.LOCATION_BLOCKS` 在 1.21.1 **已 `@Deprecated`** → 用 `InventoryMenu.BLOCK_ATLAS`
> - OBJ **只能用 `g`，绝不能用 `o`**：`o` 会让 `objAboveGroup=true`，之后的 `g` 变成嵌套路径 `父/子`，
>   `CompositeRenderable.Transforms` 的键就对不上了

---

## 0. 结论

**能用一半。**

那份文档的**技术选型判断是对的**（NeoForge 内置 OBJ 加载器 → 零前置 + 自由网格），
但它的**施工图不能照搬**，因为它是为「方块 / 方块实体」写的，而我们的无人机是**实体**。
照搬会踩三个坑，并且**漏掉了一条更短、更安全的官方正路**。

我的方案：**上 OBJ，但不走它给的管线。**

| | E:\ 方案的路线 | 我的路线 |
|---|---|---|
| 模型载体 | `neoforge:obj` → **BakedModel** | `neoforge:obj` → **`CompositeRenderable`** |
| 渲染入口 | `BlockEntityRenderer`（§4.3） | `EntityRenderer`（我们已有的 `DroneRenderer`） |
| 纹理 | 进**方块图集**，必须用 `RenderType.cutout()` | **直连纹理**，用 `RenderType.entityCutoutNoCull()` |
| 顶点格式 | `BLOCK`（**无 overlay 通道**） | `NEW_ENTITY`（**overlay 在，受击红闪原样保留**） |
| 骨骼 | 无（"放弃骨骼动画"） | **有**，`CompositeRenderable.Transforms` |
| 需要的 JSON 模型文件 | 每个部件一个 | **一个都不需要** |
| 新增 Java 代码 | ~3 个类 | ~3 个类（更短） |

核心发现是：NeoForge 早就为非方块载体准备了运行时网格 API，
`ObjModel#bakeRenderable(IGeometryBakingContext)` → `CompositeRenderable`，
它用 `UnitTextureAtlasSprite` 烘焙（UV 是原始 0–1，**不过图集**），
并且自带 `Transforms`（按部件名给 `Matrix4f`）——**这就是内置的轻量骨骼系统**。
那份文档从头到尾没提过这个类。

> ⚠️ **但"能做"不等于"该做"。** 把 OBJ 和原版 `ModelPart` 路线逐条对比之后（见 **§3**），
> 我的**最终推荐已经改变**：
> - 如果目标是「看起来像原版 Minecraft 里的一台高级无人机」——`DroneModel` 的类注释写的
>   就是 `reads as Minecraft first and sci-fi second`——**走原版 `ModelPart` + 多段折面更好**，
>   OBJ 在风格一致性、贴图管线、UV 自动化、失败模式、预览效率、性能上全面落后；
> - 只有当你要的是「脱离 Minecraft 风格的精美 3D 模型」（圆润流线机身、球形云台罩、
>   扭转桨叶）时，才该上 OBJ，并且必须先做 §5 的 P1 管线验证。
>
> §2 的管线设计本身是正确且经过源码核实的，留着备查；但**先读 §3 再决定要不要走**。

---

## 1. 对 E:\ 方案的逐条核验

### 1.1 判断正确、可以直接用的部分 ✅

| 文档结论 | 核验结果 |
|---|---|
| NeoForge 内置 OBJ 加载器，玩家零前置 | ✅ `net.neoforged.neoforge.client.model.obj.ObjLoader`，随 NeoForge 分发 |
| OBJ 给任意三角网格，`elements` 只能轴对齐盒体 | ✅ 正确 |
| 不要缓存 `BakedModel`，F3+T 后要重新取 | ✅ 正确（我们的路线里连 BakedModel 都不涉及） |
| `automatic_culling` 要设 `false` | ✅ 正确（它的剔除判断拿坐标和 `0`/`1` 比，只对方块空间成立） |
| `.obj` 与 `.mtl` 必须同名同目录 | ✅ `ObjModel.parse` 里 `mtllib` 是相对 obj 所在目录解析的 |
| `.mtl` 里的贴图路径要手工改成 `#texture0` | ✅ `map_Kd #texture0`，`resolveDirtyMaterial` 把 `#xxx` 交给 `owner.getMaterial("#xxx")` |
| `assets/` 下路径必须全小写 | ✅ 正确 |
| 面数预算、`draw call` 而非面数才是瓶颈 | ✅ 正确，对我们同样适用 |
| §8 性能工程（LOD / 批次收敛 / 相位由 `gameTime` 推算） | ✅ 全部适用，可直接抄 |

### 1.2 致命遗漏：`CompositeRenderable` ⚠️

文档 §4.1 / §4.2 给的方案是：`ModelEvent.RegisterAdditional` 注册 standalone 模型 →
渲染时从 `ModelManager` 取 `BakedModel` → 手工遍历 `getQuads` 提交。这条路**能跑**，
但它是「方块模型管线」的东西，用在实体上要付代价（见 §1.3）。而 NeoForge 有一条专用路：

```java
// ObjModel.java（NeoForge 21.1.251 源码）
public CompositeRenderable bakeRenderable(IGeometryBakingContext configuration) {
    var builder = CompositeRenderable.builder();
    for (var entry : parts.entries()) {
        var name = entry.getKey();
        var part = entry.getValue();
        part.bake(builder.child(name), configuration);   // ← OBJ 的 g/o 组名 = 部件名
    }
    return builder.get();
}

// ModelMesh.bake(...) 里：
var pair = makeQuad(face, tintIndex, colorTint, mat.ambientColor,
        UnitTextureAtlasSprite.INSTANCE,          // ← UV 恒等映射，不走图集
        Transformation.identity());
quads.add(pair.getLeft());
ResourceLocation texturePath = ResourceLocation.fromNamespaceAndPath(
        textureLocation.getNamespace(), "textures/" + textureLocation.getPath() + ".png");
builder.addMesh(texturePath, quads);              // ← 直接绑纹理文件
```

而 `CompositeRenderable.Mesh.render` 是这样提交的：

```java
var consumer = bufferSource.getBuffer(textureRenderTypeLookup.get(texture));
for (var quad : quads) {
    consumer.putBulkData(poseStack.last(), quad, 1, 1, 1, 1, lightmap, overlay, true);
}
```

三个关键点，全是我们要的：

1. **`UnitTextureAtlasSprite.INSTANCE`** —— 它的 `getU/getV` 是恒等函数，
   所以顶点 UV 就是原始 0–1，**纹理不经过方块图集**，可以直接用 `textures/entity/xxx.png`；
2. **`textureRenderTypeLookup.get(texture)`** —— `RenderType` 由**我们**给
   （`ITextureRenderTypeLookup` 是个函数式接口），可以自由选 `entityCutoutNoCull`；
3. **`putBulkData(..., lightmap, overlay, true)`** —— **overlay 通道在**。
   我们现在的受击红闪就是靠 `OverlayTexture` 传参实现的，走这条路**一行都不用改**。

外加 `CompositeRenderable.Transforms`：

```java
public static Transforms of(ImmutableMap<String, Matrix4f> parts);
@Nullable public Matrix4f getTransform(String part);
```

`Component.render` 会 `poseStack.pushPose(); poseStack.mulPose(matrix)`，
而且**子组件的矩阵是在父组件矩阵之内相乘的**（`Component.children` 递归）——
所以 OBJ 里的 `g` 嵌套 = 骨骼父子关系，组件全名用 `/` 连接（`arm0/pod/rotor`）。

> 文档 §6.5 那句「放弃骨骼动画后，失去的是动画的制作效率」——**前提就错了**。
> 零前置路线一样有骨架，只是骨架由 `Matrix4f` 拼而不是由时间轴编辑器拼。

### 1.3 三处会直接踩坑的错误 ❌

#### ① 坐标单位写错了（这条最贵）

文档 §5 第 3 条：「**单位与朝向**：1 方块 = 16 单位」。

**这条是原版 `elements` JSON 的规矩，对 OBJ 不成立。**
OBJ 加载器不做 `/16`，`makeQuad` 直接把 `v` 行的浮点数当坐标用；
`CompositeRenderable` 也不做任何缩放，`putBulkData` 原样提交。
**在实体渲染器里，OBJ 的 1.0 = 1 格。**

按文档的写法建模 → 进游戏是个 **16 格大的怪物**。

#### ② `emissive_ambient` 默认值是 `true`，不是 `false`

`ObjLoader.read` 里写的是 `GsonHelper.getAsBoolean(jsonObject, "emissive_ambient", true)`。
而 `ObjMaterialLibrary.Material.ambientColor` 默认 `new Vector4f()` = (0,0,0,0)，
`.mtl` 里不写 `Ka` 的话：

```java
if (emissiveAmbient) {
    int fakeLight = (int) ((ambientColor.x() + ambientColor.y() + ambientColor.z()) * 15 / 3.0f); // = 0
    uv2 = LightTexture.pack(fakeLight, fakeLight);   // = 全黑
}
```

→ 烘焙出的 quad 自带「零亮度」。走 BakedModel + `putBulkData` 的 8 参重载时，
8 参重载内部会把它**当作 `lightmap` 传下去**，模型在游戏里**全黑**。
（走我的 `CompositeRenderable` 路线反而绕过了这个坑：它显式传 `lightmap`，
而 `IVertexConsumerExtension#applyBakedLighting` 的默认实现是
`Math.max(传入的 light, quad 里烘的 light)`。）

**正确做法**：JSON 里显式写 `"emissive_ambient": false`，不要靠 `Ka`。

> ⚠️ **本文原先在这里写的「需要自发光时传 `LightTexture.FULL_BRIGHT` 即可」是错的**，
> 实测被用户驳回（"发光镜头/尾灯不常亮"）。`FULL_BRIGHT` 的含义是
> **「和一个满光照的方块一样亮」，是天花板而不是绕过**：
> 它选中的光照图 texel 就是纯白（`getBrightness(15)=1.0`，block 那一项 `f9 = 1.0 × 1.5 = 1.5`
> 自己就 clamp 到 1），所以 `color *= lightMapColor` 是**空操作**，
> 部件渲染出来**恰好等于贴图原色** —— 而白天旁边那个满光照的机身也正好是这个值。
> 于是"发光件"和"普通亮色件"像素级无法区分。
> **要超过光照上限只能靠加色混合**，用 `RenderType.eyes(texture)`：
> 它不设 `lightmapState`（默认 `NO_LIGHTMAP`，fsh 里根本没有 `lightMapColor` 这一项），
> 混合是 `ADDITIVE_TRANSPARENCY` = `blendFunc(ONE, ONE)`，cull 保持默认的 `CULL`（每像素只叠一次）。
> 代价：`EYES` 没设 overlay，所以发光件不吃受击红闪（机身照常闪）。
> `RenderType.breezeEyes()` = `entityTranslucentEmissive` 是另一条路，
> 它同样不采样光照图、但是 alpha 混合不是加法 —— 渲染结果等于贴图原色，**和 `FULL_BRIGHT` 一样看不出发光**，
> 只适合"要恒定颜色"而不是"要发光"的场合。

#### ③ 版本坐标

| 文档写的 | 实际 |
|---|---|
| `neo_version=21.1.252` | 本仓库锁的是 **21.1.251**（`gradle.properties`）。不是大问题，但要和 `build.gradle` 对齐 |
| GeckoLib `4.9.3` | ✅ **正确**。我拉了 cloudsmith 的 maven-metadata，`geckolib-neoforge-1.21.1` 最新就是 `4.9.3`（`lastUpdated 20260916`）。我们 9/27 记的 `4.7.7` 已经过期 |

`flip_v` 文档说「Blender 导出通常需要 `true`」——这个**必须实测**，不同导出器约定不同，
不能照抄。默认值是 `false`。

---

## 2. 我的方案

### 2.0 为什么这条路更短

| 步骤 | E:\ 方案 | 我的方案 |
|---|---|---|
| 资源文件 | 每个部件一份 `.json`（`loader: neoforge:obj`）+ `.obj` + `.mtl` | **只要 `.obj` + `.mtl`**，不要 JSON |
| 注册 | `ModelEvent.RegisterAdditional` 逐个注册 standalone 模型 | **不注册**，`ObjLoader.INSTANCE.loadModel(...)` 直接读 |
| 取模型 | `ModelManager#getModel(standalone(id))`（每次渲染重取） | 自己持有 `CompositeRenderable`，**资源重载时重建一次** |
| 渲染 | 手写 `getQuads` 双循环 + `putBulkData` | `renderable.render(poseStack, buffer, lookup, light, overlay, partialTick, transforms)` |
| 纹理 | 搬进 `textures/block/`，进方块图集 | 留在 `textures/entity/`，直连 |
| 受击红闪 | 丢失（BLOCK 格式无 overlay），要改成颜色 tint | **原样保留** |

### 2.1 渲染管线（P1，先做，先验证）

**目录**（`assets/watchcraft/`）：

```
models/entity/
  drone.obj          # 机体 / 机臂 / 电机 / 桨叶 / 起落架 / 云台支架 / 天线
  drone.mtl          # newmtl drone_mat + map_Kd #texture0
  drone_glow.obj     # 只放发光件：镜头 + 尾灯
  drone_glow.mtl
textures/entity/
  drone.png          # 128×128，所有部件共用
  drone_glow.png     # 发光件专用，不发光的地方 alpha = 0
```

> 为什么发光件要单独一个文件：`CompositeRenderable` 一次 `render` 只接受**一个** `lightmap`，
> 没法只让其中几个组件走满亮。拆成两个 `CompositeRenderable` 就解决了，代价是一次额外 draw call。
> （`StandaloneGeometryBakingContext.withVisibleComponents` 过滤不了 `bakeRenderable`——
> `ObjModel.bakeRenderable` / `ModelGroup.bake` 都没有做可见性判断，所以只能拆文件。）

**新增 `client/DroneMeshes.java`**（约 60 行）：

```java
package dev.watchcraft.watchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.watchcraft.watchcraft.Watchcraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.model.geometry.StandaloneGeometryBakingContext;
import net.neoforged.neoforge.client.model.obj.ObjLoader;
import net.neoforged.neoforge.client.model.obj.ObjModel;
import net.neoforged.neoforge.client.model.renderable.CompositeRenderable;
import net.neoforged.neoforge.client.model.renderable.ITextureRenderTypeLookup;

import java.util.Map;

/**
 * 运行时烘焙的 OBJ 网格。资源重载时重建；绝不缓存 BakedModel。
 */
public final class DroneMeshes implements ResourceManagerReloadListener {

    private static final ResourceLocation BODY_OBJ =
            ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "models/entity/drone.obj");
    private static final ResourceLocation GLOW_OBJ =
            ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "models/entity/drone_glow.obj");

    private static final ResourceLocation BODY_TEX =
            ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "textures/entity/drone.png");
    private static final ResourceLocation GLOW_TEX =
            ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "textures/entity/drone_glow.png");

    private static CompositeRenderable body;
    private static CompositeRenderable glow;

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        // ObjLoader 自己也是重载监听器，执行顺序不确定；显式清缓存保证读到新文件。
        ObjLoader.INSTANCE.onResourceManagerReload(manager);
        body = bake(BODY_OBJ, BODY_TEX);
        glow = bake(GLOW_OBJ, GLOW_TEX);
    }

    private static CompositeRenderable bake(ResourceLocation obj, ResourceLocation texture) {
        ObjModel model = ObjLoader.INSTANCE.loadModel(new ObjModel.ModelSettings(
                obj,
                false,   // automatic_culling —— 我们的坐标不是 0/1，必须关
                true,    // shade_quads —— 要方向光照
                false,   // flip_v —— 按导出器实测，先 false
                false,   // emissive_ambient —— 一律关，发光靠显式 lightmap
                null));  // mtl 与 obj 同名同目录，不需要 override

        // 1.21.1 起 TextureAtlas.LOCATION_BLOCKS 已 @Deprecated，用 InventoryMenu.BLOCK_ATLAS。
        // 这个 atlas 其实永远不会被读：ObjModel 烘 quad 时固定用 UnitTextureAtlasSprite。
        Material material = new Material(InventoryMenu.BLOCK_ATLAS, texture);
        StandaloneGeometryBakingContext context = StandaloneGeometryBakingContext.builder()
                .withMaterials(Map.of("#texture0", material), material)   // key 必须带 '#'
                .withUseBlockLight(false)
                .withUseAmbientOcclusion(false)
                .build(obj);

        return model.bakeRenderable(context);
    }

    public static CompositeRenderable body() { return body; }
    public static CompositeRenderable glow() { return glow; }

    public static void render(PoseStack pose, MultiBufferSource buffers,
                              CompositeRenderable mesh, ITextureRenderTypeLookup lookup,
                              int light, int overlay, CompositeRenderable.Transforms rig) {
        if (mesh != null) {
            mesh.render(pose, buffers, lookup, light, overlay, 0.0F, rig);
        }
    }
}
```

**`WatchcraftClient` 里注册**（`@EventBusSubscriber` 已经是 MOD 总线 + CLIENT）：

```java
@SubscribeEvent
public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
    event.registerReloadListener(new DroneMeshes());
}
```

**`DroneRenderer` 改造**：

```java
@Override
public void render(ReconDroneEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                   MultiBufferSource buffer, int packedLight) {
    poseStack.pushPose();

    float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
    float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
    poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
    poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
    poseStack.mulPose(Axis.ZP.rotationDegrees(BANK_SIGN * entity.getBank(partialTick)));
    poseStack.translate(0.0F, HOVER_Y, 0.0F);   // 把原点抬到 AABB 中心（0.45 / 2）

    int overlay = entity.getHurtTime() > 0 ? HURT_OVERLAY : OverlayTexture.NO_OVERLAY;
    CompositeRenderable.Transforms rig = DroneRig.build(entity, partialTick);

    DroneMeshes.render(poseStack, buffer, DroneMeshes.body(),
            RenderType::entityCutoutNoCull, packedLight, overlay, rig);
    DroneMeshes.render(poseStack, buffer, DroneMeshes.glow(),
            RenderType::entityTranslucentEmissive, LightTexture.FULL_BRIGHT, overlay, rig);

    poseStack.popPose();
    super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
}
```

**变换链的一处清理（有意为之）**：现在的代码是
`scale(-1,-1,1)` + `translate(0,-1.501,0)`，模型空间是原版那套「y 向下、原点在脚上方 1.501 格」。
换成 OBJ 后建议改成**正常的 y 向上、原点在机体几何中心**——建模时直观得多，
也省掉 X 轴镜像带来的左右手混乱。代价是 **3 个符号要重新实机校准**：
`BANK_SIGN`、pitch 的符号、yaw 的 180° 偏移。无人机前后不对称（有机头），校准时要留意。

#### P1 必须验证的 5 件事（做完再动模型）

1. 模型出现在正确位置、大小是 **0.8 × 0.45 格**（不是 16 倍）；
2. 纹理贴对、**没有上下颠倒**（`flip_v` 调对）；
3. **方向光照正常**（不是平板一片亮，也不是全黑）；
4. **受击红闪还在**（打一下无人机看）；
5. `draw call` 与帧率正常（F3 看，或直接目测多架同屏）。

> 建议用一个 10 行的 Python 脚本先导一个「单色测试立方体」的 OBJ 来跑 P1，
> 不要一上来就做精细模型。管线不对的话，精细模型的返工成本是它的几十倍。

### 2.2 模型生产：程序化生成 OBJ（推荐）

这是我方案里**最重要的增量**。

E:\ 方案假设你要用 **Blender / Blockbench** 建模。但我们这个项目的现有工作流是
**纯代码 + Python 生成资产**（`tools/generate_textures.py` 已经在跑）。
引入外部建模工具会带来三个问题：不可复现、UV 与贴图手工对齐（最容易错）、
4 个机臂的对称性靠手调。

而**无人机本来就是工业几何体**——机身是拉伸体/旋转体，机臂是锥台，
桨叶是扭转薄板，云台是球。程序化生成完全够用，而且更好：

```
tools/
  mesh_kit.py           # 极小的几何库：extrude / lathe / bevel / box / quad_strip
  generate_mesh.py      # 用 mesh_kit 拼出 drone.obj + drone_glow.obj + drone.mtl
  uv_layout.py          # ★ 唯一的 UV 布局真值源
  generate_textures.py  # 改成 from uv_layout import ...，不再手工写死 UV 坐标
```

**收益**：

- **UV 与贴图永远对齐**——两个脚本读同一份 `uv_layout.py`，现在最容易出错的地方直接消失；
- 改一个参数（机臂长度、机身宽度）就能重新生成，可以快速试造型；
- 4 个机臂 / 4 个电机 / 4 个桨叶**几何完全一致**，对称性由构造保证；
- 模型进版本库是纯文本，diff 可读；
- 不需要装 Blender，不需要在另一个工具里对齐坐标系。

**代价**：做不出有机曲面。但「倒角 + 多段拉伸 + 旋转体」对无人机这种造型足够，
而且比现在的方块堆精细一个数量级。

**备选**：确实需要手工雕造型时，用 Blender 或 Blockbench（Blockbench 的
Generic Model 支持导出 OBJ）。但**坐标单位必须按 §1.3① 改成「1.0 = 1 格」**。

### 2.3 资源规范

| 项 | 规定 | 理由 |
|---|---|---|
| 坐标单位 | **1.0 = 1 格**，y 向上，机头朝 **-Z** | 见 §1.3①。**这是本文档对 E:\ 方案最重要的纠正** |
| 部件原点 | 放在**自己的旋转关节**上 | 矩阵只做「绕关节旋转」，不用算 `T(p)·R·T(-p)` |
| 面数预算 | 整机 **≤ 4000 三角形**（主力件 ≤ 800/件） | 0.8 格的无人机，同屏可能多架；第三人称才看得到，面数收益递减 |
| 贴图 | 128×128 起步，全部件共用一张；发光件单独 128×128 | 一次纹理绑定 |
| 命名 | 全小写；`.obj` 与 `.mtl` 同名同目录 | `ObjModel.parse` 的相对路径解析 |
| `.mtl` | `newmtl drone_mat` + `map_Kd #texture0` | `resolveDirtyMaterial` 认 `#` 前缀 |
| `.obj` | 每个部件必须有 `usemtl`，且有 `g <部件名>` | **`mat == null` 的面会被静默丢弃**；组名是 `Transforms` 的 key |
| 发光件 | 不发光处 alpha = 0，用 `RenderType.entityTranslucentEmissive` | 不需要单独的发光贴图机制 |

> ⚠️ `ObjModel.parse` 里 `ModelMesh.addQuads` / `bake` 开头都是 `if (mat == null) return;`
> ——`.obj` 里漏写 `usemtl` 会导致**整个部件静默消失**，不报错。这是最容易白掉半天的坑。

### 2.4 部件清单（延续路线 B 的骨架，但原点在关节）

| 部件组名 | 数量 | 关节原点 | 面数预算 |
|---|---|---|---|
| `frame` | 1 | 机体几何中心 | 800 |
| `hull_lower` / `hull_upper` / `canopy` / `nose` / `spine` | 各 1 | 同上（静止件） | 600 / 500 / 400 / 300 / 200 |
| `gimbal_yoke` | 1 | 云台俯仰轴 | 200 |
| `gimbal_lens` *(glow)* | 1 | 同上 | 100 |
| `tail_light` *(glow)* | 1 | 尾灯位置 | 50 |
| `antenna` | 1 | 天线根部 | 120 |
| `arm0..arm3` | 4 | **机身四角** | 150 × 4 |
| `motor0..motor3` | 4 | 机臂末端 | 200 × 4 |
| `rotor0..rotor3` | 4 | 电机轴心 | 300 × 4 |
| `skid_l` / `skid_r` | 2 | 起落架铰点 | 200 × 2 |

合计约 **4000 面**，7 个 `g` 层级，符合 §2.3 预算。

> **机臂 pivot 必须挪到机身四角**（这是上一份方案 §3.1 的结论，仍然成立）。
> 现在的 `PartPose.offsetAndRotation(0, ROOT_Y, 0, 0, angle, 0)` 是绕机身中心转的，
> 做收拢动画时机臂会整条扫过机身。挪到角点后，收拢就是 `arm.zRot` 抬到 -70°。

### 2.5 动画：矩阵即骨架

新增 `client/DroneRig.java`，把上一份方案 §4 的 7 段动作翻译成矩阵：

```java
public final class DroneRig {

    private static final float ARM_ANGLE = 45.0F;      // 机臂相对机身轴的方位角
    private static final float ARM_LEN   = 0.19F;      // 格，机身角点到电机
    private static final float FOLD_UP   = -70.0F;     // 收拢时的抬臂角

    public static CompositeRenderable.Transforms build(ReconDroneEntity e, float partialTick) {
        float age = e.tickCount + partialTick;
        ImmutableMap.Builder<String, Matrix4f> rig = ImmutableMap.builder();

        // 动作 1 悬停怠速：机体上下浮动 + 微滚转
        float bob = Mth.sin(age * 0.12F) * 0.04F;      // 格
        float roll = Mth.sin(age * 0.09F) * 2.0F;      // 度
        rig.put("frame", new Matrix4f().translate(0.0F, bob, 0.0F).rotateZ((float) Math.toRadians(roll)));

        float fold = Mth.lerp(foldAmount(e, partialTick), 0.0F, 1.0F) * FOLD_UP;  // 动作 4/6
        float spin = rotorAngle(e, age);                                          // 动作 2

        for (int i = 0; i < 4; i++) {
            float bearing = (float) Math.toRadians(45.0D + i * 90.0D);
            float ax = Mth.cos(bearing) * 0.14F;       // 机身角点
            float az = Mth.sin(bearing) * 0.14F;
            Matrix4f arm = new Matrix4f()
                    .translate(ax, 0.0F, az)
                    .rotateY(bearing)
                    .rotateZ((float) Math.toRadians(fold));
            rig.put("arm" + i, arm);
            rig.put("motor" + i, new Matrix4f(arm).translate(ARM_LEN, 0.0F, 0.0F));
            rig.put("rotor" + i, new Matrix4f(arm)
                    .translate(ARM_LEN, 0.02F, 0.0F)
                    .rotateY((float) Math.toRadians(i % 2 == 0 ? spin : -spin)));
        }

        // 动作 5 云台锁定
        rig.put("gimbal_yoke", new Matrix4f().translate(0.0F, -0.08F, -0.16F)
                .rotateX((float) Math.toRadians(Mth.clamp(e.getXRot(), -60.0F, 60.0F))));
        rig.put("gimbal_lens", new Matrix4f(rig.build().get("gimbal_yoke")));   // 镜头跟着云台
        ...
        return CompositeRenderable.Transforms.of(rig.build());
    }
}
```

> 注意：这里所有长度单位都是**格**。上一份方案里写的 `frame.y = sin(...) * 0.6` 是
> **模型单位**（1/16 格），换算过来是 `* 0.6 / 16` 格——这是从 `ModelPart` 迁到矩阵时
> 最容易搞错的一处。

**7 段动作的映射**（与上一份方案 §4 一致，只是实现层换了）：

| # | 动作 | 触发 | 矩阵实现 |
|---|---|---|---|
| 1 | 悬停怠速 | 默认 | `frame` 的 y 平移 + Z 轴微旋 |
| 2 | 旋翼加速 | `isThrown()` 上升沿 | `rotorN` 的 Y 轴转速，`clampedLerp(0→满)` 缓动 |
| 3 | 投掷弹道 | `isThrown()` | 机体俯仰**已由服务端 `tickThrown` 算好**，客户端只叠加 ±3° 滚转 |
| 4 | 机臂展开 | 落地 / 接管 | `armN` 的 Z 轴角 `-70° → 0°` |
| 5 | 云台锁定 | 接管 | `gimbal_yoke` 的 X 轴角 = `clamp(entity.getXRot(), ±60)` |
| 6 | 回收折叠 | 回收键 | 机臂收 + 起落架收 + `frame` 缩放 `1 → 0.6` |
| 7 | 受击抖动 | `hurtTime > 0` | `frame` 随机平移 + 发光层闪红（红闪由 overlay 负责） |

### 2.6 前置阻塞：同步缺口（P0，与 OBJ 无关，先做）

**上一份方案 §1 的判断是对的，这一条必须先做，否则投掷动画无论如何做不出来。**

`thrown` / `throwTicks` 现在**没有同步**，客户端拿不到弹道状态：

```java
private static final EntityDataAccessor<Boolean> DATA_THROWN =
        SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.BOOLEAN);
private static final EntityDataAccessor<Integer> DATA_THROW_START =
        SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

// defineSynchedData
builder.define(DATA_THROWN, false);
builder.define(DATA_THROW_START, 0);

public boolean isThrown()  { return this.entityData.get(DATA_THROWN); }
public int getThrowStart() { return this.entityData.get(DATA_THROW_START); }

public void markThrown() {
    this.thrown = true;
    this.throwTicks = 0;
    this.entityData.set(DATA_THROWN, true);
    this.entityData.set(DATA_THROW_START, this.tickCount);   // 只同步"事件"，进度客户端自己算
}
```

`tickThrown()` 结尾的 `this.thrown = false` 也要补一个 `set(DATA_THROWN, false)`。

> **不要**逐 tick 同步 `float throwProgress`：那是 20 包/秒/实体。
> 同步起始 tick，客户端用 `ageInTicks - getThrowStart()` 推算，丢包也不会漂移。

### 2.7 性能

| 项 | 目标 | 手段 |
|---|---|---|
| 整机面数 | ≤ 4000 | §2.3 预算表 |
| 纹理绑定 | **1 次**（+1 次发光） | 全部件共用一张 128×128 |
| `draw call` | **2**（机体 + 发光） | 同一 RenderType + 同一纹理 = 同一个 `BufferBuilder` |
| 顶点格式 | `NEW_ENTITY`（36 B/顶点） | 4000 面 ≈ 16000 顶点 ≈ 0.6 MB，显存无压力 |
| 每帧 CPU | 每部件一次 `pushPose/mulPose/popPose`，约 20 次 | 可忽略；**不要**在 `DroneRig` 里 `new` 一堆临时对象（用 `ImmutableMap.Builder`，矩阵复用） |
| 视锥剔除 | 重写 `shouldRender`，用贴紧的包围盒 | 现在的默认盒对 0.8×0.45 的实体已经够紧 |
| LOD | 同屏 > 6 架时切低模 | 后期再说，先不做 |

> `RenderType.entityCutoutNoCull` 是**不做背面剔除**的（等于当前行为）。
> 等模型封闭成实体后可以换成 `RenderType.entityCutout`，省掉约一半的片元——但桨叶如果是
> 单面薄片就会消失，要么给桨叶做双面几何，要么保持 NoCull。**先 NoCull 保证正确，再优化。**

---

## 3. 相比原版 `ModelPart` 路线，这样做有哪些缺陷（诚实清单）

先把**不是**缺陷的澄清掉，避免误判：

| 常被误认为缺陷 | 实际情况 |
|---|---|
| 丢受击红闪 | ❌ 不会。`CompositeRenderable.Mesh.render` 走 `putBulkData(..., lightmap, overlay, true)`，**overlay 通道在**，红闪原样保留 |
| 丢实体发光描边（`isCurrentlyGlowing` 的白边） | ❌ 不会。`OutlineBufferSource` 是包在传进来的 `MultiBufferSource` 上的，我们仍走 `super.render`，描边照旧 |
| 丢阴影 | ❌ 不会。阴影由 `shadowRadius` + AABB 决定，与模型无关 |
| 动画能力变弱 | ❌ 相反。`Matrix4f` 是 `PartPose` 的**超集**（支持 shear、任意 pivot、任意相乘顺序） |
| 资源包不能覆盖 | ❌ 一样能覆盖（把同名 `.obj` 放进资源包即可） |
| 「零前置」承诺被破坏 | ❌ 没有。OBJ 加载器随 NeoForge 分发 |

**真正的缺陷，按严重度排序：**

### 3.1 「原版感」直接冲突 —— 最严重的一条

`DroneModel` 的类注释写的就是：

> *built from vanilla style cubes so it **reads as Minecraft first and sci-fi second***

OBJ 会从根本上破坏这个既定目标：

- 原版实体模型全是**整数像素对齐的盒体**，斜边只出现在 22.5° / 45° 这类规整角度；
  OBJ 带来平滑曲面和任意角度斜边，观感立刻变成「装了模型资源包的 mod」，与周围的原版生物和方块割裂；
- **更要命的是贴图**。我们现在的 64×64 是**程序化生成的像素画**，它是为「每个面一个矩形 UV」
  设计的。贴到 UV 岛 + 曲面上会出现**拉伸、接缝、亚像素噪点（纹理闪烁）**。
  要上 OBJ，贴图必须重做成 128/256 并按 UV 岛绘制——等于美术管线重来一遍。

### 3.2 原版立方体的 UV 是自动展开的，OBJ 不是

`CubeListBuilder.addBox(..., texOffs(u, v))` 配合 `LayerDefinition.create(mesh, texW, texH)`，
**6 个面的 UV 由 w/h/d 自动算出**——`tools/generate_textures.py` 里的
`faces(u, v, w, h, d)` 就是这个规则的镜像。改一个尺寸，UV 自动跟着走。

OBJ 要重新摊 UV：约 40 个部件 × 6 面 = 240 个面要手工排布，而且每次改形状都要重排。

> **但这条有解**：写一个 shelf packing 自动装箱器（约 60 行 Python），按部件顺序分配 UV 区域，
> 同时输出一份布局表供 Java 的 `texOffs` 使用。这样 ModelPart 路线的最大痛点就消失了。

### 3.3 fail-fast 变成 fail-silent —— 可靠性退化

| 出错情形 | 原版 `ModelPart` | OBJ |
|---|---|---|
| 部件名拼错 | `root.getChild("pod0")` **抛异常** | `Transforms` 的 key 对不上 → **静默不动** |
| `.obj` 里漏写 `usemtl` | 不适用 | **整个部件静默消失**，无任何日志 |
| 贴图路径写错 | 紫黑格（一眼可见） | 可能整块不显示 |
| `g` 组名与代码字符串不一致 | 不适用 | **静默不动** |

开发期最贵的成本就是「静默失败」。这一条是实打实的损失。

### 3.4 模型不可视化 —— 工作流退化

按 §2.2 的推荐，模型是 Python 程序化生成的，意味着**写代码 → 进游戏才知道长什么样**；
而且 Blockbench **也看不到最终效果**（游戏里还有我们的矩阵变换）。
原版 `elements` / `ModelPart` 可以在 Blockbench 里直接预览、直接改、直接导出。

> 缓解：写一个 OBJ 正交三视图预览脚本（Python → PNG），或导出 glTF 丢在线查看器。
> 但这本身是额外工作量。

### 3.5 不能直接当物品模型

原版模型可以直接被 `item/xxx.json` 引用，物品栏、手持、展示框全部白送。
OBJ + `CompositeRenderable` 不行——物品栏里要么另做 2D 图标（我们现在就是这么做的），
要么写 `IClientItemExtensions#getCustomRenderer` 再走一遍 `CompositeRenderable`。**多一份代码。**

### 3.6 没有数据生成

原版模型有 `ModelProvider`（datagen）。OBJ 没有对应的 provider。

### 3.7 `CubeDeformation` 的「胖瘦调节」没了

原版 `new CubeDeformation(0.25F)` 一个数字就能让部件胖一圈 / 缩一圈，
用来错开共面、做受击抖动、做展开收起的过渡。OBJ 只能重新导出模型。

### 3.8 性能与内存（数值真实，绝对值仍小）

| | 原版 `ModelPart` | OBJ（4000 面） |
|---|---|---|
| 顶点数 / 架 | 约 40 cube × 24 ≈ **960** | 4000 quad × 4 ≈ **16000** |
| 每帧 CPU 顶点变换 | ~960 次 | ~16000 次（`putBulkData` 里 `transformPosition`） |
| 常驻内存 | 几十 KB | 4000 × ~200 B ≈ **0.8 MB** |
| `draw call` | 1（+ 发光层 1） | 1（+ 发光层 1）—— **平手** |

注意：**两条路线的顶点都是 CPU 侧变换**（`ModelPart.render` 和 `putBulkData` 都是），
所以不存在「OBJ 省 CPU」这回事。同屏 6 架时，16000 × 6 = 9.6 万次/帧的顶点变换，
虽然仍不算多，但比现在重约 16 倍。

### 3.9 兼容性风险（主要是光影）

- `RenderType.entityCutoutNoCull` 是标准实体 RenderType，Iris / Oculus 能正确路由到 gbuffer；
- `RenderType.entityTranslucentEmissive` 的批次归属**需要实测**（光影下自发光层可能表现异常）；
- 优化类 mod（Sodium / Embeddium）主要动方块和区块，实体渲染影响小；
- 原版 `ModelPart` 路线则完全在标准路径上，**零兼容性风险**。

### 3.10 更正：我上一轮把「部件可复用」算成了 OBJ 的优势，这是错的

「4 个机臂 = 1 份网格 × 4 个矩阵」这条**只在**「每个部件一个 `.obj` 文件 + 手写矩阵循环」
的变体下成立。而我推荐的「单文件 + `g arm0..arm3` + `Transforms`」变体里，
4 个组就是 **4 份顶点数据**，和 ModelPart 里循环 `addOrReplaceChild` 4 次**完全一样**。

所以「网格复用」不是 OBJ 的免费优势，它和「声明式 `Transforms`」二选一。

### 3.11 那 OBJ 真正多出来的是什么？

只剩一条：**形状不必由轴对齐盒体拼出。**

- 真圆柱、真球、贝塞尔曲面——ModelPart 要靠大量折面 Part 近似；
- 逐顶点颜色（`.obj` 的 `vc` 行）、任意多边形面、顶点级独立 UV——我们用不到。

而这条能力在「Minecraft 风格」的目标下**基本用不上**：
四轴无人机在现实里本来就是棱角分明的工业机体，Minecraft 本来也不该有平滑曲面。

### 3.12 结论：我改变推荐

| | **ModelPart 多段折面** | OBJ |
|---|---|---|
| 风格一致性 | ✅ 完全一致 | ❌ 割裂 |
| 贴图管线 | ✅ 现有 64×64 像素画可直接扩展 | ❌ 要重做成 UV 岛贴图 |
| UV | ✅ 自动展开（+ 可写装箱器进一步自动化） | ⚠️ 手工 / 程序化摊 |
| 失败模式 | ✅ fail-fast | ❌ fail-silent |
| 预览 | ✅ Blockbench 直接看 | ❌ 要进游戏 |
| 物品模型 | ✅ 白送 | ❌ 要另写 |
| 兼容性 | ✅ 标准路径 | ⚠️ 光影需实测 |
| 性能 | ✅ ~960 顶点/架 | ⚠️ ~16000 顶点/架 |
| 形状自由度 | ⚠️ 只能折面近似曲面 | ✅ 任意网格 |

**「多段折面」能做到什么程度**：一块 45° 斜板 = 一个 `Part`；一个「圆润」机壳 =
8–12 段折面 = 8–12 个 `Part`。整机约 **35–40 个 `Part`**，其中 4 套机臂 / 电机 / 桨叶
用循环 `addOrReplaceChild` 生成，代码上不重复。这个量级对 `ModelPart` 毫无压力。

**所以：默认走 `ModelPart` 多段折面（上一份方案的路线 B 加强版 + 自动 UV 装箱器）。**
OBJ 路线的唯一触发条件是：你确实要**脱离 Minecraft 风格**——圆润流线机身、球形云台罩、
带扭转的桨叶。那种情况下再回来做 §5 的 P1 验证。

---

## 4. 为什么仍然不上 GeckoLib

E:\ 方案 §7.1 的论证（「没有第三方动画库 = 没有第三方版本节奏牵制」）**是对的**，
而且现在有了 `CompositeRenderable.Transforms`，「为了骨骼动画」这个理由也不成立了。

| | 我的方案（ModelPart 折面 / OBJ，两条都比它强） | GeckoLib |
|---|---|---|
| 玩家前置 | **无** | 装 GeckoLib（Fabric 还要 Fabric API） |
| 模型格式 | `ModelPart` 代码构建 / `.obj` + `.mtl`（通用） | `.geo.json`（Bedrock 专用） |
| 动画编辑 | 手写 `PartPose` / `Matrix4f` | 时间轴 + 缓动曲线 |
| 依赖冲突风险 | 0 | 「两个 Mod 内嵌不同版本」是常见事故 |
| 兼容性 | 原版标准路径（或已核实的官方 OBJ API） | 替换整条渲染管线 |
| 对我们的收益 | — | **只有「动画好写」**，而我们有 7 段动作、20 个部件 |

**结论不变：不上 GeckoLib。** 但如果将来要加 3–5 个新实体、每个都要 20+ 段关键帧动画，
那就该重新评估——那时候是「格式迁移决策」，不是「要不要前置」。

（版本坐标已核实：`software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3`，
仓库 `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/`，
需用 `exclusiveContent` 限定 group。5.x 大版本换了 groupId 为 `com.geckolib`，抄旧教程会失败。）

---

## 5. 分期与验收

**两条路线共享 P0 和 P3 的动作设计，只在 P1/P2 分岔。**

### 5.A 推荐路线：`ModelPart` 多段折面（默认走这条）

| 阶段 | 内容 | 验收标准 | 风险 |
|---|---|---|---|
| **P0** | 补 `DATA_THROWN` / `DATA_THROW_START` 同步 | 客户端能读到 `isThrown()`；`gradle build` 通过 | 低 |
| **P1** | `tools/uv_pack.py`：shelf packing 自动 UV 装箱器，输出布局表 | `generate_textures.py` 与 `DroneModel` 的 `texOffs` 从同一份布局读取，不再手写数字 | 低 |
| **P2** | 重构 `DroneModel` 部件树：机壳 8–12 段折面、机臂 pivot 挪到机身四角、起落架、云台 | 整机 35–40 个 `Part`；贴图 128×128 或 64×128 装得下 | 中 |
| **P3** | `setupAnim` 状态机：7 段动作 | 逐段动作在游戏里正确，无穿模 | 中 |
| **P4** | 发光层（`LightTexture.FULL_BRIGHT` 单独 pass）+ 贴图精修 | 镜头 / 尾灯自发光；雪花屏与 HUD 不受影响 | 低 |
| **P5** | 性能压测 | 同屏 6 架掉帧 < 10% | 低 |

### 5.B 备选路线：OBJ（仅当你确定要脱离 Minecraft 风格）

| 阶段 | 内容 | 验收标准 | 风险 |
|---|---|---|---|
| **P0** | 同上 | 同上 | 低 |
| **P1** | 管线打通：`DroneMeshes` + `DroneRenderer` 改造 + **测试立方体 OBJ** | §2.1 的 5 条全过 | **中**（唯一的未知项） |
| **P2** | `tools/mesh_kit.py` + `generate_mesh.py` + `uv_layout.py`，出整机 OBJ | 整机面数 ≤ 4000；OBJ 能被加载器解析无警告 | 中 |
| **P3** | `DroneRig` 装配矩阵 + 7 段动作 | 逐段动作在游戏里正确，无穿模 | 中 |
| **P4** | 发光层（独立 `drone_glow.obj`）+ 贴图重做成 UV 岛贴图 | 镜头 / 尾灯自发光；**像素画风格不再撕裂** | **高**（贴图要重做） |
| **P5** | 性能压测 | 同屏 6 架掉帧 < 10% | 低 |

**P1（OBJ 路线）是唯一有真实未知的环节**（`CompositeRenderable` 用在 `EntityRenderer` 里
没有官方文档示例，我是从源码推出来的）。所以走 5.B 时**先 P1，用一个测试立方体验证，
再投入 P2 的建模工作量**。走 5.A 则没有这个未知项。

---

## 6. 风险与回退

| 风险 | 概率 | 应对 |
|---|---|---|
| （5.A）折面段数不够，机壳看着还是方的 | 中 | 加到 16 段；或局部用 `CubeDeformation` 微调轮廓 |
| （5.A）40 个 `Part` 的 UV 排布太挤 | 低 | 贴图扩到 128×128；`uv_pack.py` 会自动重排 |
| `CompositeRenderable` 在实体批次里行为异常 | 低 | 回退到 E:\ 方案的 BakedModel 路线（`RenderType.cutout()` + 颜色 tint 做红闪），已确认可行 |
| `flip_v` / 朝向不对 | 中 | 改一个布尔值，F3+T 热重载，不用重启 |
| OBJ 部件静默消失 | 中 | 检查 `usemtl`（§2.3 的坑）；或临时把 `mtl` 里 `Kd` 设成红色定位 |
| 程序化建模做不出想要的造型 | 中 | 局部换 Blender 出的件，两种来源可以混（同一 `.obj` 内） |
| 贴图 128×128 不够用 | 低 | 提到 256×256（直连纹理，不受方块图集尺寸约束） |
| 需要从 OBJ 回退到原版 `ModelPart` | 低 | **已放弃这条退路**：`DroneModel.java` 与其 `ModelLayerLocation` 注册均已删除。回退不再需要"保留开关"，因为 `tools/preview_mesh.py` 已能离线出四视图 + 清单驱动贴图，造型问题在启动游戏之前就能看出来，不值得为它养一份双实现 |
| 旋翼顶视时和机臂糊成一条 | 中 | 桨叶方向必须是**机臂方向 ±45°**。共线时两片桨的十字有一臂与对角机臂完全重合，臂看起来像"长出了桨"。45° 转对角恰好落在坐标轴上，顶点仍是整 U，`snap()` 零误差 |
| 换资源包后模型不更新 | 低 | 自烘焙的 `CompositeRenderable` 缓存要挂在 `RegisterClientReloadListenersEvent` 上清掉。`ObjLoader` 自己的缓存由 `ClientNeoForgeMod` 清，不包含我们的 |

---

## 附录 A：源码证据索引（NeoForge 21.1.251）

| 结论 | 出处 |
|---|---|
| `bakeRenderable` → `CompositeRenderable` | `net/neoforged/neoforge/client/model/obj/ObjModel.java:436` |
| 用 `UnitTextureAtlasSprite` 烘焙（UV 恒等） | `ObjModel.java:569` + `client/textures/UnitTextureAtlasSprite.java` |
| 纹理直连 `textures/<path>.png` | `ObjModel.java:573-576` |
| `putBulkData(..., lightmap, overlay, true)` | `client/model/renderable/CompositeRenderable.java:78` |
| `Transforms.of(Map<String, Matrix4f>)` | `CompositeRenderable.java:138` |
| 子组件矩阵在父矩阵内相乘 | `CompositeRenderable.java:49-64` |
| `emissive_ambient` 默认 `true` | `client/model/obj/ObjLoader.java:52` |
| `ambientColor` 默认全 0 → uv2 全黑 | `client/model/obj/ObjMaterialLibrary.java` + `ObjModel.java:340-346` |
| `#texture0` → `owner.getMaterial("#texture0")` | `client/model/geometry/UnbakedGeometryHelper.java:78-79` |
| `ObjLoader.INSTANCE` / `loadModel` / `onResourceManagerReload` 均 public | `client/model/obj/ObjLoader.java:29,58,37` |
| `Material(ResourceLocation atlasLocation, ResourceLocation texture)` | `net/minecraft/client/resources/model/Material.java` |
| `withMaterials(Map<String, Material>, Material)` | `client/model/geometry/StandaloneGeometryBakingContext.java:168` |
| `applyBakedLighting` 默认 `return packedLight`（所以显式 lightmap 生效） | `IVertexConsumerExtension.java` + `com/mojang/blaze3d/vertex/VertexConsumer.java` |
| `RenderType.entityCutoutNoCull` / `entityTranslucentEmissive` 存在 | `net/minecraft/client/renderer/RenderType.java` |
| `RegisterClientReloadListenersEvent#registerReloadListener` | `client/event/RegisterClientReloadListenersEvent.java` |
| `mat == null` 的面被静默丢弃 | `ObjModel.java:542, 560` |
| GeckoLib 1.21.1 最新 = 4.9.3 | `dl.cloudsmith.io` maven-metadata，`lastUpdated 20260916` |

## 附录 B：参考

- NeoForge OBJ 加载器 JSON 字段：<https://docs.neoforged.net/docs/1.21.4/resources/client/models/modelloaders/>
  （注意：文档**没有**覆盖运行时渲染，`CompositeRenderable` 只能从源码读）
- `CompositeRenderable` / `IRenderable` / `ITextureRenderTypeLookup`：
  `net.neoforged.neoforge.client.model.renderable` 包
- 本仓库反编译源码：`build/moddev/artifacts/neoforge-21.1.251-sources.jar`
  （抽取单个文件用 Python `zipfile`，比 `unzip` 稳）
