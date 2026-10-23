// Comic sky: flat blue gradient, white clouds with a thin blue outline.
shader_type sky;

uniform vec3 top : source_color = vec3(0.23, 0.52, 0.86);
uniform vec3 horizon : source_color = vec3(0.66, 0.83, 0.96);
uniform vec3 cloud : source_color = vec3(0.99, 0.99, 1.0);
uniform vec3 cloud_shade : source_color = vec3(0.82, 0.88, 0.97);
uniform vec3 cloud_ink : source_color = vec3(0.36, 0.52, 0.78);
uniform sampler2D noise : repeat_enable, filter_linear;

void sky() {
	vec3 d = EYEDIR;
	float h = clamp(d.y, 0.0, 1.0);
	vec3 c = mix(horizon, top, pow(h, 0.55));
	if (d.y > 0.0) {
		vec2 uv = d.xz / (d.y + 0.12) * 0.09 + vec2(0.31, 0.17);
		float n = texture(noise, uv).r;
		float n2 = texture(noise, uv + vec2(0.013, 0.02)).r;
		float fade = smoothstep(0.0, 0.12, d.y);
		float body = step(0.60, n) * fade;
		float rim = (step(0.585, n) - step(0.60, n)) * fade;
		vec3 cl = mix(cloud_shade, cloud, step(0.66, n2));
		c = mix(c, cl, body);
		c = mix(c, cloud_ink, rim);
	} else {
		c = horizon * 0.95;
	}
	COLOR = c;
}
