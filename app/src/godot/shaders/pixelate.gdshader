// Post effect for the 8-bit themes: the whole frame drawn as big pixels with a
// reduced number of colour levels. A full-screen quad that reads the frame.
shader_type spatial;
render_mode unshaded, depth_test_disabled, depth_draw_never, cull_disabled, fog_disabled, shadows_disabled;

uniform sampler2D screen : hint_screen_texture, filter_nearest;
uniform float lines = 240.0; // vertical resolution of the "console"
uniform float levels = 10.0; // colour levels per channel

void vertex() {
	POSITION = vec4(VERTEX.xy * 2.0, 0.0, 1.0);
}

void fragment() {
	float px = VIEWPORT_SIZE.y / lines;
	vec2 cell = vec2(px) / VIEWPORT_SIZE;
	vec2 uv = (floor(SCREEN_UV / cell) + 0.5) * cell;
	vec3 c = textureLod(screen, uv, 0.0).rgb;
	// Fewer colour levels, like a console palette.
	c = floor(c * levels + 0.5) / levels;
	ALBEDO = c;
	ALPHA = 1.0;
}
