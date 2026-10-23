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
uniform vec3 tint : source_color = vec3(1.0); // flat colour for untextured parts (e.g. the cyclist)

varying vec2 facade;
varying vec2 info;

void vertex() {
	facade = UV;
	info = UV2;
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
	if (style == 4) { // Montreal plex: brick, stone lintels, dark frames, shops below
		if (fl < 1.0 && shops) return shopfront(g, wall, bay);
		vec3 stone = vec3(0.78, 0.74, 0.66);
		vec3 c = framed(g, vec2(0.28, 0.20), vec2(0.72, 0.76), 0.035, wall, vec3(0.08, 0.09, 0.11), bay + fl, hit);
		if (hit) return c;
		if (inside(g, vec2(0.22, 0.79), vec2(0.78, 0.88))) return stone;
		// Brick courses.
		return wall * (1.0 - step(0.85, fract(facade.y * 12.0)) * 0.12);
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
	// Vertex / instance colours are authored in sRGB.
	vec3 c = pow(COLOR.rgb, vec3(2.2)) * tint;
	if (mode == 1) c = texture(albedo_tex, UV).rgb;
	if (mode == 2) c = windows(c, COLOR.a < 0.75);
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
