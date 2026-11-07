// "Real" look: the world with photographic CC0 textures (asphalt, concrete, grass,
// forest floor, rock, plaster, brick, roof tiles) and ordinary lighting.
// Textures are projected in world space; the builder's colours pick and tint them.
shader_type spatial;
render_mode cull_disabled;

uniform int mode = 0; // 0 = streets, 1 = ground (land-use texture), 2 = walls, 3 = roofs
uniform sampler2D albedo_tex : source_color, filter_linear_mipmap;
uniform sampler2D asphalt : source_color, filter_linear_mipmap, repeat_enable;
uniform sampler2D concrete : source_color, filter_linear_mipmap, repeat_enable;
uniform sampler2D grass : source_color, filter_linear_mipmap, repeat_enable;
uniform sampler2D forest : source_color, filter_linear_mipmap, repeat_enable;
uniform sampler2D rock : source_color, filter_linear_mipmap, repeat_enable;
uniform sampler2D plaster : source_color, filter_linear_mipmap, repeat_enable;
uniform sampler2D brick : source_color, filter_linear_mipmap, repeat_enable;
uniform sampler2D roof : source_color, filter_linear_mipmap, repeat_enable;
uniform vec3 glass : source_color = vec3(0.16, 0.22, 0.30);
uniform vec3 glass_hi : source_color = vec3(0.50, 0.62, 0.74);

varying vec2 facade;
varying vec2 info;
varying vec3 wpos;
varying vec3 wnorm;

#include "res://shaders/facade.gdshaderinc"

void vertex() {
	facade = UV;
	info = UV2;
	wpos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
	wnorm = normalize((MODEL_MATRIX * vec4(NORMAL, 0.0)).xyz);
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// Two scales of the same texture hide the tiling.
vec3 tex2(sampler2D t, vec2 p, float s) {
	return mix(texture(t, p / s).rgb, texture(t, p / (s * 3.7) + 0.37).rgb, 0.35);
}

void fragment() {
	vec3 c = pow(COLOR.rgb, vec3(2.2));
	vec2 p = wpos.xz;
	float rough = 0.9;
	if (mode == 0) {
		float l = luma(c);
		float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
		vec3 a = tex2(asphalt, p, 5.0);
		if (l < 0.3 && sat < 0.08) c = a * 1.05;                                   // asphalt
		else if (l > 0.75) c = c * 0.82 * (0.75 + 0.5 * luma(a));                  // paint
		else if (sat < 0.1) c = tex2(concrete, p, 2.5) * (c / max(0.3, luma(c))) * 0.55; // pavements, kerbs
		else c = c * (0.7 + 0.6 * luma(a));                                       // cycle lanes, tracks
		rough = 0.85;
	} else if (mode == 1) {
		vec3 base = texture(albedo_tex, UV).rgb;
		float green = base.g - max(base.r, base.b);
		float slope = 1.0 - clamp(wnorm.y, 0.0, 1.0);
		vec3 g = tex2(grass, p, 3.0);
		vec3 f = tex2(forest, p, 4.0);
		vec3 r = tex2(rock, p, 6.0);
		vec3 detail = vec3(0.7 + 0.6 * luma(r));
		c = base * detail;
		c = mix(c, g * (0.85 + 0.3 * base.g / max(0.2, luma(base))), smoothstep(0.02, 0.09, green));
		c = mix(c, f, smoothstep(0.05, 0.12, green) * smoothstep(0.32, 0.22, base.g));
		c = mix(c, r, smoothstep(0.32, 0.55, slope));
	} else if (mode == 2) {
		vec3 w = windows(c, COLOR.a < 0.75);
		if (w == c) {
			int style = int(info.x + 0.5);
			vec2 q = facade * vec2(1.0, 1.25);
			if (style == 4) c = mix(c, texture(brick, q * 0.8).rgb, 0.7);
			else c *= 0.6 + 0.8 * luma(texture(plaster, q * 0.5).rgb);
		} else c = w;
	} else {
		float slope = 1.0 - clamp(abs(wnorm.y), 0.0, 1.0);
		vec3 t = slope > 0.08 ? texture(roof, p / 3.0).rgb : tex2(concrete, p, 4.0);
		c = c * (0.55 + 0.9 * luma(t));
	}
	ALBEDO = c;
	ROUGHNESS = rough;
	SPECULAR = 0.2;
}
