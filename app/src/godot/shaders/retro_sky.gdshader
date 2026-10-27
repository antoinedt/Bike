// Themed sky: flat 8-bit colour bands, blocky clouds, a city skyline (or stars)
// on the horizon. Colours are set per stage by main.gd.
shader_type sky;

uniform vec3 top : source_color = vec3(0.0, 0.47, 0.97);
uniform vec3 horizon : source_color = vec3(0.24, 0.74, 0.99);
uniform vec3 skyline : source_color = vec3(0.0, 0.25, 0.53);
uniform vec3 window_color : source_color = vec3(0.99, 0.85, 0.47);
uniform vec3 cloud : source_color = vec3(0.99, 0.99, 0.99);
uniform float clouds = 1.0;
uniform float stars = 0.0;
uniform float hills = 0.0; // 1 = rolling hills on the horizon instead of a city skyline

float h1(float x) { return fract(sin(x * 127.1) * 43758.5453); }
float h2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

void sky() {
	vec3 d = EYEDIR;
	float az = atan(d.x, d.z);
	float el = asin(clamp(d.y, -1.0, 1.0));
	// Banded gradient (no smooth ramps on an 8-bit palette).
	float band = floor(clamp(el / 0.5, 0.0, 1.0) * 4.0) / 4.0;
	vec3 c = mix(horizon, top, band);
	if (stars > 0.5 && el > 0.05) {
		vec2 g = floor(vec2(az, el) / 0.01);
		if (h2(g) > 0.993) c = vec3(1.0);
	}
	if (clouds > 0.5 && el > 0.06 && el < 0.5) {
		// Blocky clouds: cells of a coarse grid, puffed by a second grid.
		vec2 g = floor(vec2(az * 16.0, el * 40.0));
		float n = h2(floor(g / vec2(4.0, 2.0)));
		float puff = h2(g);
		if (n > 0.72 && puff > 0.25) c = (puff > 0.5) ? cloud : mix(cloud, horizon, 0.35);
	}
	if (hills > 0.5) {
		// Two layers of rolling hills (the far one paler).
		float far_h = 0.05 + 0.035 * sin(az * 5.0 + 1.3) + 0.02 * sin(az * 13.0);
		float near_h = 0.025 + 0.03 * sin(az * 3.0 + 0.4) + 0.015 * sin(az * 17.0 + 2.0);
		if (el < far_h && el > -0.2) c = mix(skyline, horizon, 0.45);
		if (el < near_h && el > -0.2) c = skyline;
		if (el < -0.02) c = skyline * 0.8;
		COLOR = c;
	} else {
	// Skyline silhouettes on the horizon, with lit windows.
	float b = floor(az / 0.035);
	float bh = 0.015 + h1(b) * 0.09 + step(0.9, h1(b + 7.0)) * 0.06;
	if (el < bh && el > -0.2) {
		c = skyline;
		vec2 w = floor(vec2(az / 0.006, el / 0.008));
		if (mod(w.x, 2.0) < 1.0 && mod(w.y, 2.0) < 1.0 && h2(w) > 0.55 && el > 0.004) c = window_color;
	}
	if (el < -0.02) c = skyline * 0.7;
	COLOR = c;
	}
}
