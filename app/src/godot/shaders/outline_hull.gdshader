// Inverted-hull outline for rounded props (tree crowns, characters).
shader_type spatial;
render_mode unshaded, cull_front, fog_disabled;

uniform vec4 ink : source_color = vec4(0.12, 0.10, 0.16, 1.0);
uniform float grow = 0.06;
global uniform float soft_look;

void vertex() {
	VERTEX += NORMAL * grow;
}

void fragment() {
	if (soft_look > 0.5) discard; // no outlines in the soft look
	ALBEDO = ink.rgb;
}
