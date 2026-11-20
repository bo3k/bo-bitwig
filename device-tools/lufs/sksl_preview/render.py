"""Compile an SkSL shader as C++ (sksl.h) and render it with given uniforms -> PNG."""
import subprocess, sys, os, numpy as np
from PIL import Image
HERE = os.path.dirname(os.path.abspath(__file__))

def shader_src(name):
    lufs = os.path.dirname(HERE)
    s = open(os.path.join(lufs, name)).read()
    return s.replace("BO_FONT", open(os.path.join(lufs, "bo_lufs_font.sksl")).read())

def render(src, w, h, low, med, high, scale=3):
    cpp = '#include "sksl.h"\n' + src.replace("half4 main(", "half4 shader_main(") + f'''
int main() {{
    iResolution.xy = float2({w}, {h});
    audioLow = float4({",".join(map(str, low))});
    audioMedium = float4({",".join(map(str, med))});
    audioHigh = float4({",".join(map(str, high))});
    printf("P6 {w} {h} 255\\n");
    for (int y = 0; y < {h}; ++y) for (int x = 0; x < {w}; ++x) {{
        half4 c = shader_main(float2(x + .5f, y + .5f));
        unsigned char px[3] = {{(unsigned char)(clamp(c.r,0.f,1.f)*255), (unsigned char)(clamp(c.g,0.f,1.f)*255), (unsigned char)(clamp(c.b,0.f,1.f)*255)}};
        fwrite(px, 1, 3, stdout);
    }}
}}
'''
    open('/tmp/shader.cpp', 'w').write(cpp)
    subprocess.run(['g++', '-O1', '-std=c++17', '-Wall', '-Wno-unused-variable', '-I', HERE, '/tmp/shader.cpp', '-o', '/tmp/shader'], check=True)
    out = subprocess.run(['/tmp/shader'], check=True, capture_output=True).stdout
    hdr_end = out.index(b'\n') + 1
    img = np.frombuffer(out[hdr_end:], np.uint8).reshape(h, w, 3)
    return Image.fromarray(img).resize((w * scale, h * scale), Image.NEAREST)
