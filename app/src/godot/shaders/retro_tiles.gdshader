// Themed (video-game) worlds: pixel-art tiles from an 8 x 8 atlas, nearest
// filtered. UV repeats the tile, UV2.x picks it. Flat 8-bit shading: tops bright,
// walls darker by facing (no smooth light), lava glows.
shader_type spatial;
render_mode unshaded, cull_disabled, fog_disabled;

uniform sampler2D atlas : source_color, filter_nearest;
uniform vec3 fog_color : source_color = vec3(0.24, 0.74, 0.99);
uniform float fog_start = 110.0;
uniform float fog_end = 260.0;

varying float tile;
varying vec3 wnormal;

void vertex() {
	tile = UV2.x;
	wnormal = normalize((MODEL_MATRIX * vec4(NORMAL, 0.0)).xyz);
}

void fragment() {
	float t = floor(tile + 0.5);
	vec2 cell = vec2(mod(t, 8.0), floor(t / 8.0));
	vec2 f = clamp(fract(UV), 0.5 / 16.0, 1.0 - 0.5 / 16.0);
	vec3 c = texture(atlas, (cell + f) / 8.0).rgb * COLOR.rgb;
	vec3 n = abs(wnormal);
	float shade = n.y > 0.6 ? 1.0 : (n.x > n.z ? 0.78 : 0.62);
	if (t == 23.0) shade = 1.15; // lava
	c *= shade;
	// Distance haze into the stage's sky colour (hides where the world ends).
	float dist = length(VERTEX);
	c = mix(c, fog_color, smoothstep(fog_start, fog_end, dist));
	ALBEDO = c;
}
