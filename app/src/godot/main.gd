extends Node3D
## VibeBike ride world: loads the offline-built world of a route (world.json +
## chunk_NNN.glb / .json, see SceneBuilder.kt), renders it cel-shaded with ink
## lines and a comic sky, and moves the camera along the route.
##
## Driven by the Android app through the "VibeBike" plugin (signals carry the
## world folder, distance and speed). For desktop look-dev it also accepts
## command-line user args:  -- --world=<dir> --d=<metres> [--chase] [--shot=<png>]

const Props = preload("res://props.gd")
const Cyclist = preload("res://cyclist.gd")
## Streets are drawn this far above the terrain (SceneBuilder's asphalt lift + margin).
const ROAD_LIFT := 0.11

var toon_shader: Shader = preload("res://shaders/toon.gdshader")
var ink_shader: Shader = preload("res://shaders/ink_line.gdshader")
var sky_shader: Shader = preload("res://shaders/sky.gdshader")

var world_dir := ""
var route := PackedVector3Array()
var route_step := 5.0
var chunks_meta := []
var loaded := {}          # chunk index -> Node3D
var chunk_len := 400.0
var chunk_samples := {}   # chunk index -> route points (every 50 m) it was built around
var last_chunk_check := 0

var distance := 0.0       # latest distance from the app
var speed := 0.0          # m/s
var cadence := 0.0        # rpm
var rider: Node3D
var heading := Vector3.FORWARD
var sample_time := 0.0
var render_d := 0.0
var chase := false
var chase_k := 0.0
var cam: Camera3D
var props: Props

var shot_path := ""
var shot_frames := 0
var debug_noshadow := false
var debug_side := false

var mat_ground: ShaderMaterial
var mat_walls: ShaderMaterial
var mat_roofs: ShaderMaterial
var mat_ink: ShaderMaterial
var mat_rails: ShaderMaterial
var mat_roads: ShaderMaterial


## Video-game themed worlds (world.json "theme"): pixel-art tiles, an 8-bit sky
## per stage and a pixelated frame. "" = the real-place world.
var theme := ""
var stages := []
var stage_now := ""
var mat_tiles: ShaderMaterial
var retro_sky: ShaderMaterial
var pixel_quad: MeshInstance3D
const STAGE_COLOURS := {
	# top, horizon, skyline, clouds, stars
	"sky": [Color(0.0, 0.47, 0.97), Color(0.24, 0.74, 0.99), Color(0.0, 0.25, 0.53), 1.0, 0.0],
	"factory": [Color(0.85, 0.30, 0.20), Color(0.99, 0.72, 0.55), Color(0.30, 0.12, 0.10), 1.0, 0.0],
	"forest": [Color(0.0, 0.50, 0.45), Color(0.70, 0.95, 0.65), Color(0.0, 0.25, 0.05), 1.0, 0.0],
	"ice": [Color(0.40, 0.60, 0.99), Color(0.86, 0.94, 1.0), Color(0.36, 0.45, 0.62), 1.0, 0.0],
	"night": [Color(0.0, 0.0, 0.10), Color(0.15, 0.06, 0.30), Color(0.05, 0.04, 0.12), 0.0, 1.0],
	# Kingdom Quest (+ haze start, end)
	"meadow": [Color(0.32, 0.58, 0.97), Color(0.76, 0.89, 1.0), Color(0.30, 0.56, 0.30), 1.0, 0.0, 120.0, 280.0],
	"woods": [Color(0.22, 0.42, 0.34), Color(0.60, 0.74, 0.60), Color(0.12, 0.30, 0.16), 0.0, 0.0, 45.0, 150.0],
	"village": [Color(0.40, 0.62, 0.95), Color(0.96, 0.90, 0.74), Color(0.42, 0.52, 0.34), 1.0, 0.0, 120.0, 280.0],
	"lake": [Color(0.28, 0.55, 0.92), Color(0.80, 0.92, 0.99), Color(0.36, 0.52, 0.62), 1.0, 0.0, 130.0, 300.0],
	"mountain": [Color(0.52, 0.34, 0.46), Color(0.98, 0.74, 0.54), Color(0.46, 0.30, 0.25), 1.0, 0.0, 120.0, 300.0],
	"castle": [Color(0.30, 0.40, 0.80), Color(0.86, 0.86, 0.96), Color(0.46, 0.48, 0.60), 1.0, 0.0, 120.0, 280.0],
}
## Area names shown as a title card when a stage starts.
const STAGE_NAMES := {
	"sky": "SKY CITY", "factory": "IRON FACTORY", "forest": "TIMBER WOODS", "ice": "FROST PEAKS", "night": "MIDNIGHT FORTRESS",
	"meadow": "Meadow Fields", "woods": "Whispering Woods", "village": "Riverside Village", "lake": "Crystal Lake",
	"mountain": "Thunder Peak", "castle": "The Royal Castle",
}
var title_layer: CanvasLayer
var title_label: Label
var title_time := 0.0

var look := "comic"      # "comic" or "soft"
var time_of_day := "afternoon"
var env: Environment
var sun: DirectionalLight3D
var sky_mat: ShaderMaterial


func _ready() -> void:
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--look="): look = a.substr(7)
		elif a.begins_with("--time="): time_of_day = a.substr(7)
	_build_environment()
	mat_walls = _toon(2)
	mat_roofs = _toon(0)
	mat_rails = _toon(3)
	mat_roads = _toon(0)
	set_look(look, time_of_day)
	mat_ink = ShaderMaterial.new()
	mat_ink.shader = ink_shader
	props = Props.new()
	add_child(props)
	rider = Cyclist.new()
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--style="): rider.style = a.substr(8)
	add_child(rider)

	for a in OS.get_cmdline_user_args():
		if a.begins_with("--world="): world_dir = a.substr(8)
		elif a.begins_with("--d="): distance = float(a.substr(4)); render_d = distance
		elif a == "--chase": chase = true; chase_k = 1.0
		elif a.begins_with("--v="): speed = float(a.substr(4))
		elif a.begins_with("--cad="): cadence = float(a.substr(6))
		elif a.begins_with("--shot="): shot_path = a.substr(7)
		elif a == "--noshadow": debug_noshadow = true
		elif a == "--side": debug_side = true

	if debug_noshadow:
		for c in get_children():
			if c is DirectionalLight3D: c.shadow_enabled = false
	var plugin = Engine.get_singleton("VibeBike") if Engine.has_singleton("VibeBike") else null
	if plugin:
		plugin.connect("open_world", open_world)
		plugin.connect("ride_update", ride_update)
		plugin.connect("set_chase", func(c): chase = c)
		plugin.connect("set_style", set_rider_style)
		plugin.connect("set_look", set_look)
		plugin.call("worldReady")
	if world_dir != "":
		open_world(world_dir)


func _toon(mode: int) -> ShaderMaterial:
	var m := ShaderMaterial.new()
	m.shader = toon_shader
	m.set_shader_parameter("mode", mode)
	return m


func _build_environment() -> void:
	env = Environment.new()
	var sky := Sky.new()
	var sm := ShaderMaterial.new()
	sky_mat = sm
	sm.shader = sky_shader
	var noise := FastNoiseLite.new()
	noise.frequency = 0.012
	noise.fractal_octaves = 4
	var nt := NoiseTexture2D.new()
	nt.width = 512; nt.height = 512; nt.seamless = true; nt.noise = noise
	sm.set_shader_parameter("noise", nt)
	sky.sky_material = sm
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.62, 0.68, 0.82)
	env.ambient_light_energy = 0.35
	env.tonemap_mode = Environment.TONE_MAPPER_LINEAR
	env.fog_enabled = true
	env.fog_light_color = Color(0.70, 0.84, 0.96)
	env.fog_density = 0.0016
	env.fog_sky_affect = 0.0
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-48, -35, 0)
	sun.light_energy = 1.05
	sun.light_color = Color(1.0, 0.97, 0.90)
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 90.0
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_2_SPLITS
	sun.shadow_blur = 0.0
	sun.shadow_bias = 0.08
	sun.shadow_normal_bias = 1.5
	add_child(sun)

	cam = Camera3D.new()
	cam.fov = 62
	cam.near = 0.4
	cam.far = 2500
	add_child(cam)
	cam.current = true


## Open a world folder (built on the phone by SceneBuilder).
func open_world(dir: String) -> void:
	world_dir = dir
	for c in loaded.values(): c.queue_free()
	loaded.clear()
	var text := FileAccess.get_file_as_string(dir.path_join("world.json"))
	var data = JSON.parse_string(text)
	if data == null:
		push_error("No world at " + dir)
		return
	route_step = float(data.get("routeStep", 5.0))
	theme = String(data.get("theme", ""))
	stages = data.get("stages", [])
	stage_now = ""
	_apply_theme()
	chunk_len = float(data.get("chunkLength", 400.0))
	chunks_meta = data["chunks"]
	var r: Array = data["route"]
	route = PackedVector3Array()
	for i in range(0, r.size(), 3):
		route.append(Vector3(r[i], r[i + 1], r[i + 2]))
	chunk_samples.clear()
	for meta in chunks_meta:
		var pts := PackedVector3Array()
		var d := float(meta["d0"])
		while d <= float(meta["d1"]):
			pts.append(_route_at(d))
			d += 50.0
		chunk_samples[int(meta["i"])] = pts
	_update_chunks(true)


## Swap the rider for another design (see cyclist.gd STYLES).
func set_rider_style(name: String) -> void:
	if rider and rider.style == name: return
	if rider: rider.queue_free()
	rider = Cyclist.new()
	rider.style = name
	add_child(rider)


func ride_update(d: float, v: float, cad: float) -> void:
	distance = d
	speed = v
	cadence = cad
	sample_time = Time.get_ticks_msec() / 1000.0


func _route_at(d: float) -> Vector3:
	if route.size() == 0: return Vector3.ZERO
	var f := clampf(d / route_step, 0.0, route.size() - 1.001)
	var i := int(f)
	return route[i].lerp(route[min(i + 1, route.size() - 1)], f - i)


## Chunks are loaded by distance to the rider, not by order along the route: a
## street is stored with the first pass of the route near it, so loops and
## out-and-back routes need the chunk of that earlier pass when they come back.
func _update_chunks(force := false) -> void:
	var now := Time.get_ticks_msec()
	if not force and now - last_chunk_check < 500: return
	last_chunk_check = now
	var here := _route_at(render_d)
	for meta in chunks_meta:
		var i := int(meta["i"])
		var dist := INF
		for q in chunk_samples.get(i, PackedVector3Array()):
			dist = minf(dist, Vector2(q.x - here.x, q.z - here.z).length())
		var want := dist < (650.0 if loaded.has(i) else 500.0)
		if want and not loaded.has(i):
			var node := _load_chunk(i)
			if node: loaded[i] = node
		elif not want and loaded.has(i):
			loaded[i].queue_free()
			loaded.erase(i)


func _load_chunk(i: int) -> Node3D:
	var base := world_dir.path_join("chunk_%03d" % i)
	var root := Node3D.new()
	add_child(root)
	if FileAccess.file_exists(base + ".glb"):
		var doc := GLTFDocument.new()
		var state := GLTFState.new()
		if doc.append_from_file(base + ".glb", state) == OK:
			var scene := doc.generate_scene(state)
			root.add_child(scene)
			_style(scene)
	var info = JSON.parse_string(FileAccess.get_file_as_string(base + ".json"))
	if info != null:
		_ink(root, info.get("edges", []))
		props.add_chunk(root, info.get("props", []))
	return root


## The art style: "comic" (ink lines, toon bands, bright) or "soft" (low-poly
## painted look: no outlines, warm wrapped light, haze), and the time of day.
func set_look(name: String, tod := "afternoon") -> void:
	look = name
	time_of_day = tod
	var soft := look == "soft"
	RenderingServer.global_shader_parameter_set("soft_look", 1.0 if soft else 0.0)
	# Soft look: warmer, lighter asphalt (sun-bleached, like a painted road).
	if mat_roads: mat_roads.set_shader_parameter("tint", Color(1.18, 1.10, 1.0) if soft else Color.WHITE)
	for c in loaded.values():
		for n in c.get_children():
			if n.name == "ink": n.visible = not soft
	if not soft:
		env.ambient_light_color = Color(0.62, 0.68, 0.82); env.ambient_light_energy = 0.35
		env.tonemap_mode = Environment.TONE_MAPPER_LINEAR
		env.fog_light_color = Color(0.70, 0.84, 0.96); env.fog_density = 0.0016
		sun.rotation_degrees = Vector3(-48, -35, 0); sun.light_energy = 1.05; sun.light_color = Color(1.0, 0.97, 0.90)
		sun.shadow_blur = 0.0
		RenderingServer.directional_soft_shadow_filter_set_quality(RenderingServer.SHADOW_QUALITY_HARD)
		return
	RenderingServer.directional_soft_shadow_filter_set_quality(RenderingServer.SHADOW_QUALITY_SOFT_LOW)
	sun.shadow_blur = 1.5
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.tonemap_white = 6.0
	# (sun pitch, yaw, colour, energy, sky fill, haze, sky top, horizon)
	var p = {
		"morning": [-22.0, 60.0, Color(1.0, 0.90, 0.78), 1.15, Color(0.62, 0.70, 0.82), Color(0.80, 0.84, 0.88), Color(0.50, 0.66, 0.84), Color(0.88, 0.88, 0.86)],
		"afternoon": [-50.0, -35.0, Color(1.0, 0.96, 0.88), 1.25, Color(0.60, 0.68, 0.80), Color(0.78, 0.84, 0.90), Color(0.42, 0.62, 0.86), Color(0.84, 0.88, 0.90)],
		"sunset": [-16.0, -120.0, Color(1.0, 0.74, 0.50), 1.6, Color(0.86, 0.72, 0.70), Color(0.96, 0.82, 0.68), Color(0.62, 0.64, 0.78), Color(1.0, 0.84, 0.66)],
	}.get(time_of_day, null)
	if p == null: p = [-50.0, -35.0, Color(1.0, 0.96, 0.88), 1.25, Color(0.60, 0.68, 0.80), Color(0.78, 0.84, 0.90), Color(0.42, 0.62, 0.86), Color(0.84, 0.88, 0.90)]
	sun.rotation_degrees = Vector3(p[0], p[1], 0)
	sun.light_color = p[2]; sun.light_energy = p[3]
	env.ambient_light_color = p[4]; env.ambient_light_energy = 0.95 if time_of_day == "sunset" else 0.75
	env.fog_light_color = p[5]; env.fog_density = 0.0035
	sky_mat.set_shader_parameter("soft_top", p[6])
	sky_mat.set_shader_parameter("soft_horizon", p[7])
	sky_mat.set_shader_parameter("soft_ground", (p[5] as Color).darkened(0.1))
	sky_mat.set_shader_parameter("sun_glow", p[2])


## Switch between the real-place look and a themed (video-game) one.
func _apply_theme() -> void:
	if pixel_quad == null:
		pixel_quad = MeshInstance3D.new()
		var q := QuadMesh.new(); q.size = Vector2(1, 1)
		pixel_quad.mesh = q
		var pm := ShaderMaterial.new(); pm.shader = load("res://shaders/pixelate.gdshader")
		pm.render_priority = 127
		pixel_quad.material_override = pm
		pixel_quad.extra_cull_margin = 16384.0
		pixel_quad.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		cam.add_child(pixel_quad)
		pixel_quad.position = Vector3(0, 0, -1)
	if theme == "":
		pixel_quad.visible = false
		env.sky.sky_material = sky_mat
		env.fog_enabled = true
		set_look(look, time_of_day)
		return
	set_look("comic")
	# Props (lit toon materials) fade with the scene's fog; the tiles do their own haze.
	env.fog_enabled = true
	env.fog_sky_affect = 0.0
	if mat_tiles == null:
		mat_tiles = ShaderMaterial.new()
		mat_tiles.shader = load("res://shaders/retro_tiles.gdshader")
		retro_sky = ShaderMaterial.new()
		retro_sky.shader = load("res://shaders/retro_sky.gdshader")
	var img := Image.load_from_file("res://themes/%s_atlas.png" % theme)
	if img: mat_tiles.set_shader_parameter("atlas", ImageTexture.create_from_image(img))
	env.sky.sky_material = retro_sky
	pixel_quad.visible = true
	# 8-bit: a coarse frame and few colours; 16-bit: finer and richer.
	var pm := pixel_quad.material_override as ShaderMaterial
	pm.set_shader_parameter("lines", 320.0 if theme == "quest" else 240.0)
	pm.set_shader_parameter("levels", 24.0 if theme == "quest" else 10.0)
	retro_sky.set_shader_parameter("hills", 1.0 if theme == "quest" else 0.0)
	# 8-bit light: flat and bright.
	sun.light_energy = 1.1; sun.light_color = Color(1, 1, 1)
	env.ambient_light_color = Color(0.75, 0.78, 0.85); env.ambient_light_energy = 0.55


## The stage at [d] colours the sky and the haze.
func _update_stage(d: float) -> void:
	var key := ""
	for st in stages:
		if float(st["d"]) <= d: key = String(st["s"])
	if key == stage_now or not STAGE_COLOURS.has(key): return
	stage_now = key
	var c: Array = STAGE_COLOURS[key]
	retro_sky.set_shader_parameter("top", c[0])
	retro_sky.set_shader_parameter("horizon", c[1])
	retro_sky.set_shader_parameter("skyline", c[2])
	retro_sky.set_shader_parameter("clouds", c[3])
	retro_sky.set_shader_parameter("stars", c[4])
	retro_sky.set_shader_parameter("window_color", Color(0.99, 0.85, 0.47))
	mat_tiles.set_shader_parameter("fog_color", c[1])
	mat_tiles.set_shader_parameter("fog_start", c[5] if c.size() > 5 else 110.0)
	mat_tiles.set_shader_parameter("fog_end", c[6] if c.size() > 6 else 260.0)
	env.fog_light_color = c[1]
	env.fog_light_energy = 1.0
	env.fog_density = 1.2 / (c[6] if c.size() > 6 else 260.0)
	_show_title(STAGE_NAMES.get(key, ""))


## The area's name, faded in and out over the scene.
func _show_title(text: String) -> void:
	if text == "": return
	if title_layer == null:
		title_layer = CanvasLayer.new()
		add_child(title_layer)
		title_label = Label.new()
		title_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		title_label.set_anchors_preset(Control.PRESET_TOP_WIDE)
		title_label.add_theme_font_size_override("font_size", 44)
		title_label.add_theme_color_override("font_color", Color(1, 1, 1))
		title_label.add_theme_color_override("font_outline_color", Color(0.05, 0.05, 0.1))
		title_label.add_theme_constant_override("outline_size", 12)
		title_layer.add_child(title_label)
	title_label.text = text
	title_time = 4.0


## Swap the imported materials for the toon ones by mesh name.
func _style(n: Node) -> void:
	if n is MeshInstance3D:
		var mi := n as MeshInstance3D
		var name := String(mi.name)
		if name.begins_with("ground"):
			var tex: Texture2D = null
			var src := mi.mesh.surface_get_material(0)
			if src is BaseMaterial3D: tex = (src as BaseMaterial3D).albedo_texture
			var m := _toon(1)
			m.set_shader_parameter("albedo_tex", tex)
			mi.material_override = m
			# The ground only receives shadows (self-shadowing the bumpy DEM makes blotches).
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		elif name.begins_with("walls"):
			mi.material_override = mat_walls
		elif name.begins_with("tiles"):
			mi.material_override = mat_tiles
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		elif name.begins_with("rails"):
			mi.material_override = mat_rails
		elif name.begins_with("roads"):
			mi.material_override = mat_roads
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		else:
			mi.material_override = mat_roofs
	for c in n.get_children(): _style(c)


## Build the screen-space ink line mesh for a chunk's edges.
func _ink(root: Node3D, e: Array) -> void:
	var count := e.size() / 6
	if count == 0: return
	var verts := PackedVector3Array(); verts.resize(count * 4)
	var custom := PackedFloat32Array(); custom.resize(count * 16)
	var idx := PackedInt32Array(); idx.resize(count * 6)
	for k in count:
		var a := Vector3(e[6 * k], e[6 * k + 1], e[6 * k + 2])
		var b := Vector3(e[6 * k + 3], e[6 * k + 4], e[6 * k + 5])
		var vi := 4 * k
		verts[vi] = a; verts[vi + 1] = a; verts[vi + 2] = b; verts[vi + 3] = b
		var ends := [b, b, a, a]
		var sides := [1.0, -1.0, -1.0, 1.0]
		for q in 4:
			custom[(vi + q) * 4] = ends[q].x
			custom[(vi + q) * 4 + 1] = ends[q].y
			custom[(vi + q) * 4 + 2] = ends[q].z
			custom[(vi + q) * 4 + 3] = sides[q]
		var ii := 6 * k
		idx[ii] = vi; idx[ii + 1] = vi + 1; idx[ii + 2] = vi + 2
		idx[ii + 3] = vi; idx[ii + 4] = vi + 2; idx[ii + 5] = vi + 3
	var arrays := []
	arrays.resize(Mesh.ARRAY_MAX)
	arrays[Mesh.ARRAY_VERTEX] = verts
	arrays[Mesh.ARRAY_CUSTOM0] = custom
	arrays[Mesh.ARRAY_INDEX] = idx
	var mesh := ArrayMesh.new()
	var fmt := Mesh.ARRAY_CUSTOM_RGBA_FLOAT << Mesh.ARRAY_FORMAT_CUSTOM0_SHIFT
	mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arrays, [], {}, fmt)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = mat_ink
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	mi.extra_cull_margin = 16384
	mi.name = "ink"
	mi.visible = look != "soft"
	root.add_child(mi)


func _process(delta: float) -> void:
	if route.size() < 2: return
	if theme != "": _update_stage(render_d)
	if title_label:
		title_time = maxf(0.0, title_time - delta)
		title_label.modulate.a = clampf(title_time, 0.0, 1.0) * clampf((4.0 - title_time) * 2.0, 0.0, 1.0)
		title_label.position.y = get_viewport().get_visible_rect().size.y * 0.22
	# Extrapolate between app ticks, then ease toward it (snap on big jumps).
	var now := Time.get_ticks_msec() / 1000.0
	var target := distance + speed * clampf(now - sample_time, 0.0, 0.6)
	if absf(target - render_d) > 150.0 or shot_path != "": render_d = target
	else: render_d += (target - render_d) * clampf(delta * 6.0, 0.0, 1.0)
	chase_k += ((1.0 if chase else 0.0) - chase_k) * clampf(delta * 3.0, 0.0, 1.0)

	# The rider on the road, facing along the (smoothed) route direction.
	var p := _route_at(render_d)
	var ahead := _route_at(render_d + 6.0) - _route_at(render_d - 2.0)
	ahead.y = 0.0
	if ahead.length() > 0.1:
		heading = heading.slerp(ahead.normalized(), clampf(delta * 4.0, 0.0, 1.0)) if shot_path == "" else ahead.normalized()
	rider.transform = Transform3D(Basis.looking_at(heading, Vector3.UP), p + Vector3(0, ROAD_LIFT, 0))
	rider.animate(delta if shot_path == "" else 0.3, speed, cadence)

	# Camera behind the rider: close (rider view) or higher and further (chase).
	# A slight three-quarter view from behind so bike, legs and arms all read.
	var side := heading.cross(Vector3.UP).normalized()
	var eye := p - heading * lerpf(3.4, 7.5, chase_k) + side * lerpf(0.7, 1.4, chase_k) + Vector3.UP * lerpf(2.0, 3.8, chase_k)
	var look := p + heading * lerpf(6.0, 9.0, chase_k) + Vector3.UP * lerpf(0.9, 0.6, chase_k)
	if debug_side:
		eye = p + side * 3.0 + Vector3.UP * 1.0
		look = p + Vector3.UP * 0.8
	cam.look_at_from_position(eye, look, Vector3.UP)
	_update_chunks()

	if shot_path != "":
		shot_frames += 1
		if shot_frames == 12:
			var img := get_viewport().get_texture().get_image()
			img.save_png(shot_path)
			get_tree().quit()
