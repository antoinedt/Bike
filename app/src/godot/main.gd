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
