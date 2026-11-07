// Plant / tree impostor cards (crossed quads cut out of a render of the model):
// alpha-tested, lit softly as foliage (normals bent upwards).
shader_type spatial;
render_mode cull_disabled;

uniform sampler2D tex : source_color, filter_linear_mipmap;

void fragment() {
	vec4 t = texture(tex, UV);
	if (t.a < 0.5) discard;
	ALBEDO = t.rgb * COLOR.rgb * 0.8;
	NORMAL = normalize(mix(NORMAL, (VIEW_MATRIX * vec4(0.0, 1.0, 0.0, 0.0)).xyz, 0.4));
	ROUGHNESS = 0.95;
	SPECULAR = 0.1;
}
