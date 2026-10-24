// Sky. Comic look: flat blue gradient, white clouds with a thin blue outline.
// Soft look: hazy gradient to a warm horizon, soft painted clouds and a sun glow.
shader_type sky;

global uniform float soft_look;
uniform vec3 soft_top : source_color = vec3(0.46, 0.64, 0.84);
uniform vec3 soft_horizon : source_color = vec3(0.86, 0.88, 0.86);
uniform vec3 soft_ground : source_color = vec3(0.70, 0.74, 0.72);
uniform vec3 sun_glow : source_color = vec3(1.0, 0.92, 0.78);

uniform vec3 top : source_color = vec3(0.23, 0.52, 0.86);
uniform vec3 horizon : source_color = vec3(0.66, 0.83, 0.96);
uniform vec3 cloud : source_color = vec3(0.99, 0.99, 1.0);
uniform vec3 cloud_shade : source_color = vec3(0.82, 0.88, 0.97);
uniform vec3 cloud_ink : source_color = vec3(0.36, 0.52, 0.78);
uniform sampler2D noise : repeat_enable, filter_linear;

vec3 soft_sky(vec3 d) {
	float h = clamp(d.y, 0.0, 1.0);
	vec3 c = mix(soft_horizon, soft_top, pow(h, 0.45));
	if (d.y < 0.0) c = mix(soft_horizon, soft_ground, clamp(-d.y * 4.0, 0.0, 1.0));
	// Sun disc and glow.
	float s = max(dot(d, LIGHT0_DIRECTION), 0.0);
	c += sun_glow * (pow(s, 12.0) * 0.35 + pow(s, 400.0) * 1.5);
	if (d.y > 0.0) {
		vec2 uv = d.xz / (d.y + 0.12) * 0.07 + vec2(0.31, 0.17);
		float n = texture(noise, uv).r;
		float n2 = texture(noise, uv * 2.3 + vec2(0.4, 0.1)).r;
		float body = smoothstep(0.56, 0.68, n * 0.85 + n2 * 0.15) * smoothstep(0.0, 0.2, d.y);
		vec3 cl = mix(vec3(0.80, 0.82, 0.86), vec3(1.0, 0.98, 0.95), smoothstep(0.6, 0.75, n2));
		cl = mix(cl, sun_glow, pow(s, 6.0) * 0.5);
		c = mix(c, cl, body * 0.9);
	}
	return c;
}

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
	COLOR = soft_look > 0.5 ? soft_sky(d) : c;
}
