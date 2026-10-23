// Screen-space ink lines: every edge is a quad expanded to a fixed pixel width,
// thinner with distance, pulled slightly toward the camera so it draws over the
// faces it outlines.
shader_type spatial;
render_mode unshaded, cull_disabled, skip_vertex_transform, fog_disabled;

uniform vec4 ink : source_color = vec4(0.12, 0.10, 0.16, 1.0);
uniform float width_px = 2.8;

void vertex() {
	vec4 va = MODELVIEW_MATRIX * vec4(VERTEX, 1.0);
	vec4 vb = MODELVIEW_MATRIX * vec4(CUSTOM0.xyz, 1.0);
	// Keep both ends in front of the near plane.
	if (va.z > -0.2) va.xyz = mix(vb.xyz, va.xyz, (vb.z + 0.2) / (vb.z - va.z + 1e-4));
	if (vb.z > -0.2) vb.xyz = mix(va.xyz, vb.xyz, (va.z + 0.2) / (va.z - vb.z + 1e-4));
	float dist = length(va.xyz);
	va.xyz *= 1.0 - min(0.25, 0.25 / max(dist, 0.5)) ; // pull toward the eye a bit
	vec4 a = PROJECTION_MATRIX * va;
	vec4 b = PROJECTION_MATRIX * vb;
	vec2 sa = a.xy / a.w * VIEWPORT_SIZE;
	vec2 sb = b.xy / b.w * VIEWPORT_SIZE;
	vec2 dir = normalize(sb - sa + vec2(1e-5));
	vec2 n = vec2(-dir.y, dir.x);
	// Width scales with the screen so lines look the same on any phone.
	float w = width_px * VIEWPORT_SIZE.x / 720.0 * clamp(40.0 / dist, 0.45, 1.0);
	a.xy += n * CUSTOM0.w * w / VIEWPORT_SIZE * a.w;
	POSITION = a;
}

void fragment() {
	ALBEDO = ink.rgb;
}
