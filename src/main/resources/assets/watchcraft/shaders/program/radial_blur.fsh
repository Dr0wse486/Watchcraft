#version 150

// 引爆瞬间的径向模糊。
//
// 采样点沿「屏幕中心 -> 当前像素」这条射线往外推：中心纹丝不动，越靠边拉得越开，
// 于是整幅画面像被爆炸从中心推了出去。冲击点就在镜头脚下，所以中心恒为屏幕中心，
// 由后处理链在 Center uniform 里写死，这里不必再算。
//
// 这不是"糊"，是"拽"：均匀采样会把画面揉成一团，而沿射线取样留下的是方向感 ——
// 每一条从中心射出的线都被拉成一道拖影，眼睛读到的是速度而不是失焦。
//
// Strength 为 0 时直接单次采样返回。链子常年挂在后处理里，不这样写的话哪怕不爆炸
// 也在全屏白跑十个纹理采样。

uniform sampler2D DiffuseSampler;

uniform vec2 InSize;
uniform vec2 Center;
uniform float Strength;

in vec2 texCoord;

out vec4 fragColor;

// 采样数。十档已经糊成连续的一片，再加只是拿帧率换看不见的细节。
const int SAMPLES = 10;
// Strength 为 1 时，最外侧的采样点离中心是原来的 1.18 倍。链子里跑两遍，合起来约 1.4 倍。
const float MAX_SPREAD = 0.18;

void main() {
    if (Strength <= 0.001) {
        fragColor = texture(DiffuseSampler, texCoord);
        return;
    }

    // 在等比空间里推，再换回来。直接在 uv 空间里乘同一个系数的话，16:9 下的拖影
    // 会变成横着的椭圆 —— 因为一个 uv 单位的横向长度本来就比纵向长。
    float aspect = InSize.x / InSize.y;
    vec2 delta = texCoord - Center;
    delta.x *= aspect;

    vec4 sum = vec4(0.0);
    for (int i = 0; i < SAMPLES; i++) {
        float t = float(i) / float(SAMPLES - 1);
        vec2 pushed = delta * (1.0 + Strength * MAX_SPREAD * t);
        vec2 uv = Center + vec2(pushed.x / aspect, pushed.y);
        sum += texture(DiffuseSampler, clamp(uv, vec2(0.0), vec2(1.0)));
    }
    fragColor = sum / float(SAMPLES);
}
