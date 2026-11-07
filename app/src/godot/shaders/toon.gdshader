// World shading in two looks (global soft_look):
//  - comic (0): flat albedo, two light bands with a cool shadow tint and halftone
//    dots in the shade;
//  - soft (1): low-poly "painted" look: faceted normals, wrapped warm sun with a
//    soft shadow, muted colours and a subtle surface grain.
// Building walls also get procedural windows / shopfronts from the façade UVs.
shader_type spatial;
render_mode cull_disabled, specular_disabled;

uniform int mode = 0; // 0 = vertex colour, 1 = texture (ground), 2 = walls with windows, 3 = iron railings
uniform sampler2D albedo_tex : source_color, filter_linear_mipmap;
uniform vec3 shadow_tint : source_color = vec3(0.62, 0.66, 0.90);
uniform vec3 glass : source_color = vec3(0.27, 0.37, 0.52);
uniform vec3 glass_hi : source_color = vec3(0.58, 0.72, 0.86);
uniform float halftone = 1.0;
uniform vec3 tint : source_color = vec3(1.0); // flat colour for untextured parts (e.g. the cyclist)
uniform float facet = 1.0; // soft look: flat-shade each triangle (low-poly facets)
global uniform float soft_look;

varying vec2 facade;
varying vec2 info;
varying vec3 wpos;

void vertex() {
	facade = UV;
	info = UV2;
	wpos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
}

float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
	vec2 i = floor(p); vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash2(i), hash2(i + vec2(1, 0)), u.x), mix(hash2(i + vec2(0, 1)), hash2(i + vec2(1, 1)), u.x), u.y);
}

#include "res://shaders/facade.gdshaderinc"

void fragment() {
	// Iron railings: one quad per run, the bars cut out of it (UV.x counts bars).
	if (mode == 3 && UV.y > 0.07 && UV.y < 0.92 && fract(UV.x) > 0.28) discard;
	// Vertex / instance colours are authored in sRGB.
	vec3 c = pow(COLOR.rgb, vec3(2.2)) * tint;
	if (mode == 1) c = texture(albedo_tex, UV).rgb;
	if (mode == 2) c = windows(c, COLOR.a < 0.75);
	if (soft_look > 0.5) {
		// Low-poly facets: one normal per triangle (facing the camera).
		if (facet > 0.5) {
			vec3 n = normalize(cross(dFdx(VERTEX), dFdy(VERTEX)));
			if (dot(n, VERTEX) > 0.0) n = -n;
			NORMAL = n;
		}
		// Muted, earthy colours and a subtle grain (patches on the ground, wear on walls).
		float l = dot(c, vec3(0.299, 0.587, 0.114));
		c = mix(vec3(l), c, 0.8) * vec3(1.02, 1.0, 0.95);
		vec2 q = (abs(wpos.y) < 1e4 ? wpos.xz : vec2(0.0));
		float g = vnoise(q * 0.9) * 0.6 + vnoise(q * 0.13) * 0.4;
		c *= mode == 1 ? 0.86 + 0.24 * g : 0.94 + 0.10 * vnoise(q * 3.0 + wpos.y * 2.0);
	}
	ALBEDO = c;
}

void light() {
	float ndl = dot(NORMAL, LIGHT);
	if (soft_look > 0.5) {
		// Wrapped warm sun, soft-edged shadow; the sky fill comes from the ambient light.
		float wrap = clamp((ndl + 0.25) / 1.25, 0.0, 1.0);
		float sh = mix(0.32, 1.0, smoothstep(0.2, 0.8, ATTENUATION));
		DIFFUSE_LIGHT += wrap * sh * LIGHT_COLOR / PI;
	} else {
	float lit = smoothstep(0.0, 0.05, ndl) * step(0.5, ATTENUATION);
	vec3 shade = shadow_tint;
	// Halftone dots in the shade.
	vec2 cell = fract(FRAGCOORD.xy / 5.0) - 0.5;
	float dot_ = step(length(cell), 0.22) * halftone;
	shade *= 1.0 - dot_ * 0.12;
	// LIGHT_COLOR already carries energy * PI.
	DIFFUSE_LIGHT += mix(shade * 0.68, vec3(1.0), lit) * LIGHT_COLOR / PI;
	}
}
