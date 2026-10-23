// Cel shading for the world: flat albedo (vertex colour or texture), two light
// bands with a cool shadow tint and comic halftone dots in the shade, and — for
// building walls — procedural windows / shopfronts from the façade UVs.
shader_type spatial;
render_mode cull_disabled, specular_disabled;

uniform int mode = 0; // 0 = vertex colour, 1 = texture (ground), 2 = walls with windows
uniform sampler2D albedo_tex : source_color, filter_linear_mipmap;
uniform vec3 shadow_tint : source_color = vec3(0.62, 0.66, 0.90);
uniform vec3 glass : source_color = vec3(0.27, 0.37, 0.52);
uniform vec3 glass_hi : source_color = vec3(0.58, 0.72, 0.86);
uniform float halftone = 1.0;

varying vec2 facade;
varying vec2 info;

void vertex() {
	facade = UV;
	info = UV2;
}

vec3 windows(vec3 wall) {
	float style = info.x;
	if (style < 0.5 || info.y < 0.6) return wall;
	vec2 g = fract(facade);
	float floor_i = floor(facade.y);
	bool shop = style > 1.5 && floor_i < 1.0;
	vec2 lo = shop ? vec2(0.06, 0.06) : vec2(0.30, 0.30);
	vec2 hi = shop ? vec2(0.94, 0.80) : vec2(0.70, 0.80);
	if (facade.y < 0.25) return wall;
	bool in_win = g.x > lo.x && g.x < hi.x && g.y > lo.y && g.y < hi.y;
	bool in_frame = g.x > lo.x - 0.04 && g.x < hi.x + 0.04 && g.y > lo.y - 0.04 && g.y < hi.y + 0.04;
	if (in_win) {
		// A diagonal sheen on the glass, like comic reflections.
		float sheen = step(0.82, fract((g.x - lo.x) * 1.4 + (g.y - lo.y) * 1.1 + floor_i * 0.37));
		return mix(glass, glass_hi, sheen * 0.8);
	}
	if (in_frame) return wall * 0.72;
	return wall;
}

void fragment() {
	// Vertex / instance colours are authored in sRGB.
	vec3 c = pow(COLOR.rgb, vec3(2.2));
	if (mode == 1) c = texture(albedo_tex, UV).rgb;
	if (mode == 2) c = windows(c);
	ALBEDO = c;
}

void light() {
	float ndl = dot(NORMAL, LIGHT);
	float lit = smoothstep(0.0, 0.05, ndl) * step(0.5, ATTENUATION);
	vec3 shade = shadow_tint;
	// Halftone dots in the shade.
	vec2 cell = fract(FRAGCOORD.xy / 5.0) - 0.5;
	float dot_ = step(length(cell), 0.22) * halftone;
	shade *= 1.0 - dot_ * 0.12;
	// LIGHT_COLOR already carries energy * PI.
	DIFFUSE_LIGHT += mix(shade * 0.68, vec3(1.0), lit) * LIGHT_COLOR / PI;
}
