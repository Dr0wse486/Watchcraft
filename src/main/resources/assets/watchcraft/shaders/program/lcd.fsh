#version 150

// 液晶滤镜：让无人机的画面读起来是「隔着一块屏幕在看」，而不是「一个玩家在天上飞」。
//
// <h2>为什么必须是着色器</h2>
//
// 这里要的每一味都是逐像素的图案或色偏：黑矩阵、亚像素条纹、边缘色散。{@code GuiGraphics}
// 只能填矩形，画不出亚像素；而屏幕空间里横着拉几根线，得到的是"屏幕上叠了几根线"，不是
// "这块屏幕本身有黑矩阵" —— 前者不随画面一起被"拍摄"，转个视角就露馅。
//
// <h2>为什么挂在链子最末端</h2>
//
// 它跑在 {@code RenderLevelStageEvent.AFTER_LEVEL}，也就是 {@code doEntityOutline} 之前，
// 所以被滤的只有世界：发光描边、准星、读数、按键条全部叠在它上面，依旧锐利。这和信号劣化
// 是同一条原则 —— 丢的是画面，不是仪器。同时也意味着它天然只作用于无人机镜头，玩家自己
// 走路时完全看不到。
//
// <h2>面板结构是「黑矩阵」而不是「几条扫描线」</h2>
//
// 一格的右边和下边各压暗<b>一个设备像素</b>，横竖都是 —— 于是整幅画面是一张真正的像素
// 网格，和真屏幕放大以后一样。缝宽固定为一个像素是有意的：格子多大只该决定"开口多大"，
// 不该决定"缝多宽"，否则调大间距会得到越来越粗的杠，那就不像屏幕了。
//
// 网格会吃掉亮度（一格 p×p 里有 1-(1-1/p)² 的面积被压暗），所以这里把均值除回去。
// 屏幕该给的是<b>结构</b>，不是"变暗" —— 让整个世界的曝光跟着一起掉，那是另一种效果，
// 而且会让洞里没法看。
//
// <h2>强度为 0 时直通</h2>
//
// 链子常年挂在后处理里（只要连着链路就一直在跑），关掉这个滤镜时不该白跑七次纹理采样，
// 所以第一件事就是单次采样返回 —— 和 radial_blur 的写法一致。

uniform sampler2D DiffuseSampler;

uniform vec2 OutSize;

uniform float LcdStrength;
uniform float LcdPitch;
uniform float LcdPhase;
uniform float LcdTime;

in vec2 texCoord;

out vec4 fragColor;

// ------------------------------------------------------------------ 配方
// 下面这些是 LcdStrength = 1 时的量。整条滤镜的轻重只有 LcdStrength 一个总旋钮，
// 各个成分之间的比例则写在这里 —— 想让某一样更突出就调它自己，不要动别的。

// 黑矩阵的深度。这是最容易被一眼认出来的那一味，所以给得最多。竖直方向没有对应的列
// （见下面 main 里的说明），所以这一味比"整张网格"时给得更深。
const float GRID = 0.62;
// 亚像素条纹的深度。三根条纹的平均增益精确等于 1，所以它只改同一列的颜色倾向，不改亮度。
// 竖直方向的结构全靠它，所以也比一般情况给得深一点；再深就成彩色噪点了，不像屏幕。
const float MASK = 0.40;
// 亚像素的宽度，单位是设备像素。三根一循环，和格子多大无关（原因见 main 里那段）。
const float STRIPE_PERIOD = 3.0;
// 边缘色散的像素数（画面四角处），中心为 0。真实的镜头和面板都是越靠边越散。
const float FRINGE = 1.6;
// 玻璃后面那一点软。0 是逐像素锐利，那太"数字"了，反而像在自己眼睛里看。
const float SOFTEN = 0.40;
// 滚动亮带的宽度（屏幕高度的比例）与亮度。
const float BAND_WIDTH = 0.10;
const float BAND = 0.10;
// 背光不均：四角略暗。
const float VIGNETTE = 0.30;
// 面板边框：最外一圈压暗，宽度是屏幕高度的一个比例。这是"我正对着一块屏幕"最直接的提示，
// 因为边框在任何画面内容下都成立。
const float BEZEL = 0.65;
const float BEZEL_FRACTION = 0.012;
// 面板本身的偏色，以及"黑不下去"的那层底。液晶的黑是发光的黑。
const vec3 TINT = vec3(0.955, 1.000, 1.030);
const float LIFT = 0.028;
// 逐帧亮度抖动，24 Hz。够让人感到"这画面是刷新出来的"，不够让人眼酸。
const float FLICKER = 0.030;

void main() {
    float s = clamp(LcdStrength, 0.0, 1.0);
    if (s <= 0.001) {
        fragColor = texture(DiffuseSampler, texCoord);
        return;
    }

    // 设备像素坐标。用 OutSize 而不是 gl_FragCoord：面板图案的物理尺寸只该由分辨率决定，
    // 不该跟着 GUI 缩放走。
    vec2 pixel = texCoord * OutSize;

    // 一格多大。0 表示跟随分辨率：一格占屏幕高度的固定比例，这样 1080p 和 4K 上看到的
    // 面板结构一样粗。设备像素是"物理量"，可这效果要的是"看起来像块屏"，两者在 4K 上
    // 差得很远 —— 同一个 4 像素格子在高分屏上会细到完全看不见。
    // 下限 4：再小一格就是"缝比开口多"，那读起来是百叶窗不是屏幕。
    float pitch = LcdPitch > 0.5 ? LcdPitch : clamp(OutSize.y / 270.0, 4.0, 8.0);

    // ------------------------------------------------------------------ 采样
    // 先色散，再软化。软化只做半像素的十字四点、权重不高：目的不是"糊"，是把锐利的数字
    // 边缘磨掉一层，让它像被一块玻璃隔开。
    vec2 centred = texCoord - 0.5;
    // 归一化半径：中心 0，四角 1。乘 2 是必须的 —— dot(centred, centred) 在四角只有 0.5，
    // 不归一化的话 FRINGE 说的"四角偏移多少像素"实际只能兑现一半。
    float radial = dot(centred, centred) * 2.0;
    vec2 fringed = centred * radial * FRINGE * s / OutSize;
    vec2 halfTexel = 0.5 / OutSize;

    vec3 colour;
    colour.r = texture(DiffuseSampler, texCoord + fringed).r;
    colour.g = texture(DiffuseSampler, texCoord).g;
    colour.b = texture(DiffuseSampler, texCoord - fringed).b;

    vec3 soft = (
        texture(DiffuseSampler, texCoord + vec2(halfTexel.x, 0.0)).rgb
        + texture(DiffuseSampler, texCoord - vec2(halfTexel.x, 0.0)).rgb
        + texture(DiffuseSampler, texCoord + vec2(0.0, halfTexel.y)).rgb
        + texture(DiffuseSampler, texCoord - vec2(0.0, halfTexel.y)).rgb
    ) * 0.25;
    colour = mix(colour, soft, SOFTEN * s);

    // ------------------------------------------------------------------ 面板图案
    // 黑矩阵：每一格的下边压暗一个设备像素。缝宽按格子的比例给，所以不管 pitch 多大，
    // 落到屏幕上都是一像素宽的缝 —— 这正是"格子大小决定开口、不决定缝宽"那句话的实现。
    //
    // <b>竖直方向没有对应的黑矩阵列，这是有意的。</b>一个像素宽的列无论放在一格的哪里，
    // 都会整根落进某一根亚像素条纹里（一格的最后一列必然落在 B 那一根上），那个通道就会被
    // 多压暗一次 —— 实测偏黄绿，pitch 越小越明显。竖直方向的结构交给下面的条纹去做：
    // 它本来就是彩色的，而且是精确平衡的。
    float gapEdge = 1.0 - 1.0 / pitch;
    float gapSpan = min(0.14, 1.0 - gapEdge);
    float gapRow = smoothstep(gapEdge, gapEdge + gapSpan, fract(pixel.y / pitch));
    // 把网格吃掉的亮度除回去。覆盖面积就是每一格被压暗的那一行。
    float grid = (1.0 - GRID * s * gapRow) / (1.0 - GRID * s / pitch);

    // 亚像素条纹：<b>一个设备像素一根，三像素一循环</b>，和格子多大无关 —— 真实面板的
    // 亚像素就是一个像素宽。
    //
    // ⚠️ 曾经把它绑在格子上（`fract(pixel.x / pitch)` 切成三份），那是错的：三根条纹要
    // 均分一格，只有 pitch 是 3 的倍数时才分得匀。pitch 4 会得到 R,G,G,B —— 绿通道占了一半
    // 的列，整幅画面明显偏绿；pitch 5 反过来偏品红。周期固定成 3 之后，每根条纹恒定占
    // 三分之一的列，均值精确等于 1，除以这个均值就够了，不需要知道 pitch。
    vec3 subpixel = vec3(1.0 - MASK * s);
    float stripe = fract(pixel.x / STRIPE_PERIOD);
    if (stripe < 1.0 / 3.0) {
        subpixel.r = 1.0;
    } else if (stripe < 2.0 / 3.0) {
        subpixel.g = 1.0;
    } else {
        subpixel.b = 1.0;
    }
    subpixel /= 1.0 - MASK * s * 2.0 / 3.0;

    colour *= subpixel * grid;

    // ------------------------------------------------------------------ 背光与边框
    // 等比空间里算，否则 16:9 下的暗角会摊成横着的椭圆。
    vec2 aspect = centred * vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    colour *= 1.0 - VIGNETTE * s * dot(aspect, aspect);

    // 边框：屏幕最外一圈压暗。宽度按屏幕高度取比例，所以高分屏上不会变细。
    float bezelWidth = max(OutSize.y * BEZEL_FRACTION, 4.0);
    float toEdge = min(min(pixel.x, OutSize.x - pixel.x), min(pixel.y, OutSize.y - pixel.y));
    colour *= 1.0 - BEZEL * s * (1.0 - smoothstep(0.0, bezelWidth, toEdge));

    // 滚动亮带。LcdPhase 由 Java 侧累加并保持在 0..1，所以这里 fract 出来的接缝是连续的，
    // 不会每秒跳一下。
    float bandPos = fract(texCoord.y - LcdPhase + 0.5) - 0.5;
    colour += vec3(0.95, 1.0, 1.05) * BAND * s
            * exp(-(bandPos * bandPos) / (BAND_WIDTH * BAND_WIDTH));

    // 面板色偏与底。白色保持白色，黑被抬到 LIFT。
    colour = colour * TINT * (1.0 - LIFT) + vec3(LIFT);

    // 刷新抖动。按 24 分之一秒取一个随机数，所以它是"每帧一个亮度"而不是噪声。
    float tick = floor(LcdTime * 24.0);
    float jitter = fract(sin(tick * 12.9898) * 43758.5453);
    colour *= 1.0 + (jitter - 0.5) * FLICKER * s;

    fragColor = vec4(clamp(colour, 0.0, 1.0), 1.0);
}
