// Minimal SkSL stand-in for offline previews: compile a shader as C++ and render it to PPM.
// Vector-scalar arithmetic is deliberately NOT provided (Bitwig's shaders avoid it), so using
// it is a compile error here too.
#pragma once
#include <cmath>
#include <cstdio>
#include <algorithm>
struct float2 { float x, y; float2(float a, float b) : x(a), y(b) {} float2() : x(0), y(0) {} };
struct float3 { float x, y, z; float3(float a, float b, float c) : x(a), y(b), z(c) {} float3() : x(0), y(0), z(0) {} };
struct float4 { float x, y, z, w; float4(float a, float b, float c, float d) : x(a), y(b), z(c), w(d) {} float4() : x(0), y(0), z(0), w(0) {}
    float2 xy() const { return float2(x, y); } };
typedef float3 half3;
struct half4 { float r, g, b, a; half4(half3 c, float al) : r(c.x), g(c.y), b(c.z), a(al) {} };
inline float2 operator+(float2 a, float2 b) { return float2(a.x + b.x, a.y + b.y); }
inline float2 operator-(float2 a, float2 b) { return float2(a.x - b.x, a.y - b.y); }
inline float2 operator*(float2 a, float2 b) { return float2(a.x * b.x, a.y * b.y); }
inline float2 operator/(float2 a, float2 b) { return float2(a.x / b.x, a.y / b.y); }
inline float3 operator+(float3 a, float3 b) { return float3(a.x + b.x, a.y + b.y, a.z + b.z); }
inline float3 operator*(float3 a, float3 b) { return float3(a.x * b.x, a.y * b.y, a.z * b.z); }
inline float dot(float2 a, float2 b) { return a.x * b.x + a.y * b.y; }
inline float length(float2 a) { return std::sqrt(dot(a, a)); }
inline float clamp(float v, float a, float b) { return std::min(std::max(v, a), b); }
inline float step(float e, float x) { return x < e ? 0.f : 1.f; }
inline float smoothstep(float a, float b, float x) { float t = clamp((x - a) / (b - a), 0.f, 1.f); return t * t * (3 - 2 * t); }
inline float mix(float a, float b, float t) { return a + (b - a) * t; }
inline float3 mix(float3 a, float3 b, float t) { return float3(mix(a.x, b.x, t), mix(a.y, b.y, t), mix(a.z, b.z, t)); }
using std::floor; using std::abs; using std::min; using std::max;
inline float min(float a, double b) { return std::min(a, (float)b); }
inline float max(float a, double b) { return std::max(a, (float)b); }
inline float min(double a, float b) { return std::min((float)a, b); }
inline float max(double a, float b) { return std::max((float)a, b); }
inline float2 abs(float2 a) { return float2(std::fabs(a.x), std::fabs(a.y)); }
inline float2 max(float2 a, float2 b) { return float2(std::max(a.x, b.x), std::max(a.y, b.y)); }
inline float2 min(float2 a, float2 b) { return float2(std::min(a.x, b.x), std::min(a.y, b.y)); }
inline float atan(float y, float x) { return std::atan2(y, x); }
inline float atan(double y, double x) { return std::atan2(y, x); }
struct Res { float2 xy; };
static Res iResolution;
#define uniform static
