package io.quarantaine.gfx

/** Shaders GLSL ES 1.00 (compatibles OpenGL ES 2.0 sur tous les téléphones Android). */
object Shaders {
    private const val PREC = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    const val WORLD_VS = """
uniform mat4 u_mvp;
attribute vec3 a_pos;
attribute vec2 a_uv;
attribute vec3 a_col;
varying vec2 v_uv;
varying vec3 v_col;
varying vec3 v_wpos;
void main() {
    v_uv = a_uv;
    v_col = a_col;
    v_wpos = a_pos;
    gl_Position = u_mvp * vec4(a_pos, 1.0);
}
"""

    const val WORLD_FS = PREC + """
uniform sampler2D u_tex;
uniform vec3 u_cam;
uniform vec3 u_fog;
uniform float u_fogd;
uniform vec2 u_scroll;
uniform vec4 u_lp[4];
uniform vec3 u_lc[4];
varying vec2 v_uv;
varying vec3 v_col;
varying vec3 v_wpos;
void main() {
    vec4 t = texture2D(u_tex, v_uv + u_scroll);
    vec3 light = v_col;
    for (int i = 0; i < 4; i++) {
        vec3 d = u_lp[i].xyz - v_wpos;
        float r = u_lp[i].w;
        float a = max(0.0, 1.0 - dot(d, d) / (r * r));
        light += u_lc[i] * (a * a);
    }
    vec3 c = t.rgb * (light + (1.0 - t.a) * 2.2);
    float f = exp(-length(v_wpos - u_cam) * u_fogd);
    gl_FragColor = vec4(mix(u_fog, c, f), 1.0);
}
"""

    const val FX_VS = """
uniform mat4 u_mvp;
uniform vec3 u_cam;
uniform float u_fogd;
attribute vec3 a_pos;
attribute vec2 a_uv;
attribute vec4 a_col;
varying vec2 v_uv;
varying vec4 v_col;
void main() {
    v_uv = a_uv;
    float f = exp(-length(a_pos - u_cam) * u_fogd);
    v_col = vec4(a_col.rgb * f, a_col.a);
    gl_Position = u_mvp * vec4(a_pos, 1.0);
}
"""

    const val FX_FS = PREC + """
uniform sampler2D u_tex;
varying vec2 v_uv;
varying vec4 v_col;
void main() {
    vec4 t = texture2D(u_tex, v_uv);
    gl_FragColor = vec4(t.rgb * v_col.rgb, t.a * v_col.a);
}
"""

    const val SKY_VS = """
attribute vec2 a_pos;
varying vec2 v_ndc;
void main() {
    v_ndc = a_pos;
    gl_Position = vec4(a_pos, 0.9999, 1.0);
}
"""

    /** Ciel d'Io : espace étoilé, lueur sulfureuse à l'horizon et Jupiter géante avec ses bandes. */
    const val SKY_FS = PREC + """
uniform vec3 u_fwd;
uniform vec3 u_right;
uniform vec3 u_up;
uniform vec2 u_tan;
uniform float u_time;
varying vec2 v_ndc;
float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
void main() {
    vec3 dir = normalize(u_fwd + v_ndc.x * u_tan.x * u_right + v_ndc.y * u_tan.y * u_up);
    float h = max(dir.z, 0.0);
    vec3 col = vec3(0.012, 0.010, 0.025);
    col += vec3(0.22, 0.12, 0.04) * pow(1.0 - h, 8.0);
    vec3 sp = floor(dir * 220.0);
    float s = hash(sp);
    if (s > 0.996) col += vec3(0.8, 0.85, 1.0) * (s - 0.996) * 220.0 * (0.6 + 0.4 * sin(u_time * 3.0 + s * 100.0));
    vec3 jd = normalize(vec3(0.55, 0.62, 0.45));
    float cr = 0.26;
    float ca = dot(dir, jd);
    if (ca > cos(cr)) {
        vec3 jx = normalize(cross(jd, vec3(0.0, 0.0, 1.0)));
        vec3 jy = cross(jx, jd);
        vec2 q = vec2(dot(dir, jx), dot(dir, jy)) / sin(cr);
        float r2 = dot(q, q);
        if (r2 < 1.0) {
            float lat = q.y + 0.08 * sin(q.x * 4.0 + q.y * 9.0 + u_time * 0.05);
            float band = sin(lat * 22.0) * 0.5 + 0.5;
            float band2 = sin(lat * 47.0 + 1.3) * 0.5 + 0.5;
            vec3 jc = mix(vec3(0.62, 0.43, 0.30), vec3(0.93, 0.85, 0.70), band);
            jc = mix(jc, vec3(0.55, 0.32, 0.22), band2 * 0.35);
            vec2 spot = (q - vec2(0.32, -0.38)) * vec2(1.0, 2.2);
            jc = mix(jc, vec3(0.75, 0.30, 0.18), smoothstep(0.17, 0.10, length(spot)));
            float limb = sqrt(1.0 - r2);
            vec3 n = vec3(q, limb);
            float sun = clamp(dot(n, normalize(vec3(-0.6, 0.3, 0.75))) * 1.2 + 0.08, 0.0, 1.0);
            jc *= (0.25 + 0.75 * limb) * sun;
            float edge = smoothstep(1.0, 0.96, r2);
            col = mix(col, jc, edge);
        }
    }
    gl_FragColor = vec4(col, 1.0);
}
"""

    const val HUD_VS = """
uniform vec2 u_screen;
attribute vec2 a_pos;
attribute vec2 a_uv;
attribute vec4 a_col;
varying vec2 v_uv;
varying vec4 v_col;
void main() {
    v_uv = a_uv;
    v_col = a_col;
    gl_Position = vec4(a_pos.x / u_screen.x * 2.0 - 1.0, 1.0 - a_pos.y / u_screen.y * 2.0, 0.0, 1.0);
}
"""

    const val HUD_FS = PREC + """
uniform sampler2D u_tex;
varying vec2 v_uv;
varying vec4 v_col;
void main() {
    gl_FragColor = texture2D(u_tex, v_uv) * v_col;
}
"""
}
