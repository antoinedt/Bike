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

bool inside(vec2 g, vec2 lo, vec2 hi) {
	return g.x > lo.x && g.x < hi.x && g.y > lo.y && g.y < hi.y;
}

// A pane of glass with a diagonal comic sheen.
vec3 pane(vec2 g, float seed) {
	float sheen = step(0.82, fract(g.x * 1.4 + g.y * 1.1 + seed * 0.37));
	return mix(glass, glass_hi, sheen * 0.8);
}

// A window at [lo, hi] of the bay with a frame [f] wide; returns -1 outside.
vec3 framed(vec2 g, vec2 lo, vec2 hi, float f, vec3 wall, vec3 frame, float seed, out bool hit) {
	hit = true;
	if (inside(g, lo, hi)) return pane(g, seed);
	if (inside(g, lo - f, hi + f)) return frame;
	hit = false;
	return wall;
}

// Shopfront on the ground floor: big glazing, dark fascia / awning band on top.
vec3 shopfront(vec2 g, vec3 wall, float bay) {
	if (g.y > 0.80) return mix(vec3(0.10, 0.12, 0.16), wall, step(0.94, g.y));
	if (g.y > 0.72) return wall * 0.35;
	if (inside(g, vec2(0.06, 0.04), vec2(0.94, 0.72))) return pane(g, bay);
	return wall * 0.8;
}

// Styles (UV2.x, see world/Architecture.kt): 0 blank, 1 historic, 2 slab, 3 glass,
// 4 plex, 5 house, 6 industrial, 7 church. UV = (bays, floors); COLOR.a < 1 = shops.
vec3 windows(vec3 wall, bool shops) {
	int style = int(info.x + 0.5);
	if (style == 0 || info.y < 0.6 || facade.y < 0.25) return wall;
	vec2 g = fract(facade);
	float fl = floor(facade.y);
	float bay = floor(facade.x);
	vec3 light_trim = mix(wall, vec3(1.0), 0.45);
	bool hit;
	if (style == 1) { // historic: tall framed windows, sills, lintels, rusticated / shop base
		if (fl < 1.0) {
			if (shops) return shopfront(g, wall, bay);
			float joint = step(0.92, fract(facade.y * 3.0));
			vec3 c = framed(g, vec2(0.36, 0.22), vec2(0.64, 0.80), 0.04, wall, light_trim, bay, hit);
			return hit ? c : wall * (1.0 - joint * 0.18);
		}
		vec3 c = framed(g, vec2(0.33, 0.16), vec2(0.67, 0.78), 0.035, wall, light_trim, bay + fl, hit);
		if (hit) return c;
		if (inside(g, vec2(0.28, 0.83), vec2(0.72, 0.90))) return light_trim;   // lintel
		if (inside(g, vec2(0.30, 0.09), vec2(0.70, 0.125))) return light_trim;  // sill
		return wall;
	}
	if (style == 2) { // post-war slab: wide ribbon windows, panel joints
		if (shops && fl < 1.0) return shopfront(g, wall, bay);
		if (g.x < 0.02 || g.y < 0.025) return wall * 0.78;
		vec3 c = framed(g, vec2(0.10, 0.36), vec2(0.90, 0.76), 0.025, wall, wall * 0.6, bay + fl, hit);
		if (hit && g.x > 0.49 && g.x < 0.51) return wall * 0.6; // mullion
		return c;
	}
	if (style == 3) { // curtain wall: glass everywhere, mullions and spandrels
		if (shops && fl < 1.0) return shopfront(g, wall, bay);
		if (g.x < 0.05 || g.y < 0.06) return mix(wall, vec3(0.2), 0.35);
		if (g.y < 0.24) return wall * 0.85; // spandrel
		return mix(pane(vec2(facade.x * 0.12, facade.y * 0.08), 0.0), wall, 0.25);
	}
	if (style == 4 || style == 8) { // Montreal plex: brick (4) or greystone (8), paired windows
		if (fl < 1.0 && shops) return shopfront(g, wall, bay);
		vec3 trim = vec3(0.80, 0.77, 0.70);
		vec3 frame = vec3(0.93, 0.93, 0.90);
		// Two tall windows per bay with a post between them; dark glass.
		vec3 c = framed(g, vec2(0.2, 0.18), vec2(0.8, 0.80), 0.035, wall, frame, bay + fl, hit);
		if (hit) {
			if (g.x > 0.47 && g.x < 0.53) return frame;
			if (c != frame) return mix(vec3(0.07, 0.10, 0.15), glass_hi, step(0.88, fract(g.x * 1.4 + g.y * 1.1 + fl * 0.37)) * 0.5);
			return c;
		}
		if (inside(g, vec2(0.10, 0.83), vec2(0.90, 0.93))) return style == 8 ? mix(wall, vec3(1.0), 0.25) : trim; // lintel
		if (inside(g, vec2(0.12, 0.10), vec2(0.88, 0.145))) return style == 8 ? mix(wall, vec3(1.0), 0.25) : trim; // sill
		if (style == 8) { // greystone: big cut blocks
			vec2 b = vec2(facade.x * 1.6 + step(0.5, fract(facade.y * 2.0)) * 0.5, facade.y * 4.0);
			float joint = max(step(0.95, fract(b.x)), step(0.92, fract(b.y)));
			return wall * (1.0 - joint * 0.16) * (0.96 + 0.06 * fract(sin(dot(floor(b), vec2(12.9, 78.2))) * 4375.5));
		}
		// Brick courses with pale mortar.
		vec2 k = vec2(facade.x * 5.0 + step(0.5, fract(facade.y * 6.0)) * 0.5, facade.y * 12.0);
		float mortar = max(step(0.88, fract(k.y)), step(0.94, fract(k.x)));
		return mix(wall, vec3(0.86, 0.84, 0.80), mortar * 0.35);
	}
	if (style == 5) { // house: small white-framed windows, a door
		if (fl < 1.0 && int(mod(bay, 3.0)) == 1) {
			if (inside(g, vec2(0.32, 0.0), vec2(0.68, 0.78))) return vec3(0.22, 0.13, 0.09);
			if (inside(g, vec2(0.28, 0.0), vec2(0.72, 0.82))) return vec3(0.95);
			return wall;
		}
		return framed(g, vec2(0.32, 0.32), vec2(0.68, 0.74), 0.045, wall, vec3(0.95), bay + fl, hit);
	}
	if (style == 6) { // industrial: corrugated cladding, a strip of windows high up
		float rib = step(0.5, fract(facade.x * 10.0));
		vec3 c = wall * (1.0 - rib * 0.08);
		if (g.y > 0.58 && g.y < 0.80 && mod(fl, 2.0) < 1.0) {
			if (g.x < 0.04) return wall * 0.5;
			return pane(g, fl);
		}
		if (fl < 1.0 && int(mod(bay, 5.0)) == 2 && g.y < 0.85 && g.x > 0.1 && g.x < 0.9) {
			// A roller door.
			return wall * (0.7 - step(0.8, fract(g.y * 10.0)) * 0.15);
		}
		return c;
	}
	if (style == 7) { // church: tall arched windows spanning two "floors", dark stained glass
		float y = facade.y * 0.5;
		vec2 h = vec2(g.x, fract(y));
		if (y < 0.35) return wall * (1.0 - step(0.9, fract(facade.y * 2.0)) * 0.15);
		vec2 c = vec2(0.5, 0.70);
		bool in_arch = (h.y < c.y && abs(h.x - 0.5) < 0.13 && h.y > 0.12) || (h.y >= c.y && length((h - c) * vec2(1.0, 1.4)) < 0.13);
		bool in_frame = (h.y < c.y && abs(h.x - 0.5) < 0.17 && h.y > 0.09) || (h.y >= c.y && length((h - c) * vec2(1.0, 1.4)) < 0.17);
		if (in_arch) return mix(vec3(0.10, 0.12, 0.28), vec3(0.55, 0.22, 0.18), step(0.5, fract(h.y * 6.0 + h.x * 3.0)));
		if (in_frame) return light_trim;
		return wall;
	}
	return wall;
}

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
