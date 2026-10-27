extends Node3D
## Street props from the world chunks (mapped trees, lamps, benches, bus stops …)
## as instanced low-poly meshes, cel-shaded with ink outlines.

var toon_shader: Shader = preload("res://shaders/toon.gdshader")
var hull_shader: Shader = preload("res://shaders/outline_hull.gdshader")

## type -> list of parts: [mesh, colour, outline grow (0 = none), local transform,
## optional palette (instance colour picked by the prop's variant)]
var kinds := {}
var mats := {}


func _init() -> void:
	var crown := SphereMesh.new()
	crown.radius = 2.6; crown.height = 4.6; crown.radial_segments = 7; crown.rings = 4
	var trunk := CylinderMesh.new()
	trunk.top_radius = 0.18; trunk.bottom_radius = 0.26; trunk.height = 3.6; trunk.radial_segments = 6
	var cone := CylinderMesh.new()
	cone.top_radius = 0.0; cone.bottom_radius = 2.2; cone.height = 6.5; cone.radial_segments = 7
	var cone2 := CylinderMesh.new()
	cone2.top_radius = 0.0; cone2.bottom_radius = 1.6; cone2.height = 4.5; cone2.radial_segments = 7
	var pole := CylinderMesh.new()
	pole.top_radius = 0.07; pole.bottom_radius = 0.1; pole.height = 5.5; pole.radial_segments = 6
	var lamp_head := BoxMesh.new(); lamp_head.size = Vector3(0.5, 0.22, 0.9)
	var seat := BoxMesh.new(); seat.size = Vector3(1.8, 0.08, 0.45)
	var back := BoxMesh.new(); back.size = Vector3(1.8, 0.45, 0.06)
	var leg := BoxMesh.new(); leg.size = Vector3(1.6, 0.42, 0.06)
	var shelter_roof := BoxMesh.new(); shelter_roof.size = Vector3(3.6, 0.12, 1.6)
	var shelter_wall := BoxMesh.new(); shelter_wall.size = Vector3(3.4, 2.1, 0.05)
	var short_pole := CylinderMesh.new()
	short_pole.top_radius = 0.06; short_pole.bottom_radius = 0.06; short_pole.height = 3.2; short_pole.radial_segments = 6
	var signal_box := BoxMesh.new(); signal_box.size = Vector3(0.32, 0.9, 0.3)
	var bollard := CylinderMesh.new()
	bollard.top_radius = 0.09; bollard.bottom_radius = 0.1; bollard.height = 0.9; bollard.radial_segments = 6
	var hoop := TorusMesh.new(); hoop.inner_radius = 0.38; hoop.outer_radius = 0.44; hoop.rings = 12; hoop.ring_segments = 4
	var bin := CylinderMesh.new(); bin.top_radius = 0.28; bin.bottom_radius = 0.24; bin.height = 0.9; bin.radial_segments = 7
	var hydrant := CylinderMesh.new(); hydrant.top_radius = 0.12; hydrant.bottom_radius = 0.16; hydrant.height = 0.8; hydrant.radial_segments = 6

	var T := func(x: float, y: float, z: float) -> Transform3D: return Transform3D(Basis(), Vector3(x, y, z))
	kinds["tree"] = [[trunk, Color(0.45, 0.32, 0.22), 0.04, T.call(0, 1.8, 0)], [crown, Color(0.40, 0.66, 0.27), 0.10, T.call(0, 5.2, 0)]]
	kinds["conifer"] = [[trunk, Color(0.40, 0.29, 0.20), 0.04, T.call(0, 1.2, 0)], [cone, Color(0.20, 0.45, 0.25), 0.10, T.call(0, 4.6, 0)], [cone2, Color(0.24, 0.50, 0.27), 0.08, T.call(0, 7.0, 0)]]
	kinds["lamp"] = [[pole, Color(0.30, 0.33, 0.38), 0.03, T.call(0, 2.75, 0)], [lamp_head, Color(0.95, 0.88, 0.55), 0.04, T.call(0, 5.5, 0.3)]]
	kinds["bench"] = [[seat, Color(0.62, 0.42, 0.26), 0.03, T.call(0, 0.45, 0)], [back, Color(0.62, 0.42, 0.26), 0.03, T.call(0, 0.75, -0.2)], [leg, Color(0.25, 0.27, 0.30), 0.02, T.call(0, 0.22, 0)]]
	kinds["bus_stop"] = [[shelter_roof, Color(0.30, 0.36, 0.44), 0.04, T.call(0, 2.3, 0)], [shelter_wall, Color(0.70, 0.85, 0.92), 0.03, T.call(0, 1.15, -0.7)]]
	kinds["signal"] = [[short_pole, Color(0.28, 0.30, 0.34), 0.03, T.call(0, 1.6, 0)], [signal_box, Color(0.18, 0.19, 0.22), 0.03, T.call(0, 3.0, 0.15)]]
	kinds["bollard"] = [[bollard, Color(0.30, 0.32, 0.36), 0.03, T.call(0, 0.45, 0)]]
	kinds["bike_rack"] = [[hoop, Color(0.55, 0.58, 0.62), 0.02, Transform3D(Basis(Vector3.RIGHT, PI / 2), Vector3(0, 0.45, 0))]]
	kinds["bin"] = [[bin, Color(0.25, 0.45, 0.32), 0.03, T.call(0, 0.45, 0)]]
	kinds["hydrant"] = [[hydrant, Color(0.80, 0.22, 0.18), 0.03, T.call(0, 0.4, 0)]]
	_trees()
	_car()
	_robot_theme()


## A lumpy crown: overlapping low-poly spheres [x, y, z, radius] in two greens.
func _crown(lumps: Array, greens: Array, grow := 0.08, squash := 0.9) -> Array:
	var parts := []
	for i in lumps.size():
		var l: Array = lumps[i]
		var sp := SphereMesh.new()
		sp.radius = l[3]; sp.height = l[3] * 2.0 * squash; sp.radial_segments = 6; sp.rings = 4
		parts.append([sp, greens[i % greens.size()], grow, Transform3D(Basis(), Vector3(l[0], l[1], l[2]))])
	return parts


func _trunk(h: float, r: float, c: Color, limbs := 0, limb_top := 0.0, spread := 1.0) -> Array:
	var t := CylinderMesh.new()
	t.top_radius = r * 0.7; t.bottom_radius = r; t.height = h; t.radial_segments = 6
	var parts := [[t, c, 0.03, Transform3D(Basis(), Vector3(0, h / 2, 0))]]
	for k in limbs:
		var a := k * TAU / limbs + 0.4
		var from := Vector3(0, h - 0.2, 0)
		var to := Vector3(cos(a) * spread, limb_top, sin(a) * spread)
		var len := from.distance_to(to)
		var lm := CylinderMesh.new()
		lm.top_radius = r * 0.35; lm.bottom_radius = r * 0.6; lm.height = len; lm.radial_segments = 5
		var up := (to - from).normalized()
		var side := up.cross(Vector3.FORWARD).normalized()
		var b := Basis(side, up, side.cross(up))
		parts.append([lm, c, 0.02, Transform3D(b, (from + to) / 2)])
	return parts


## Street tree species: the silhouettes tell a maple street from a linden or locust one.
func _trees() -> void:
	var bark := Color(0.36, 0.28, 0.22)
	var g1 := Color(0.30, 0.52, 0.20); var g2 := Color(0.36, 0.58, 0.23); var g3 := Color(0.27, 0.47, 0.19)
	# Maple: short trunk forking into limbs, broad rounded crown of big lumps.
	kinds["maple"] = _trunk(2.6, 0.24, bark, 3, 4.3, 1.1) + _crown([
		[0, 6.0, 0, 1.8], [1.7, 5.3, 0.4, 1.6], [-1.5, 5.4, 0.8, 1.7], [0.3, 5.2, -1.7, 1.6],
		[-0.6, 7.0, -0.5, 1.5], [0.9, 6.8, 1.1, 1.5], [-1.2, 4.7, -1.0, 1.4], [1.2, 4.6, -0.9, 1.4]], [g1, g2, g3])
	# Linden: one straight trunk, dense egg-shaped crown.
	kinds["linden"] = _trunk(3.4, 0.22, bark) + _crown([
		[0, 4.6, 0, 1.9], [0, 6.4, 0, 1.9], [0, 8.1, 0, 1.45], [0, 9.3, 0, 0.9],
		[1.1, 5.4, 0.5, 1.4], [-1.0, 5.6, -0.6, 1.4], [0.5, 7.2, -0.9, 1.3]], [g3, g1], 0.08, 0.95)
	# Ash: tall, airy crown of small clumps, lighter green.
	var ga := Color(0.42, 0.62, 0.26); var gb := Color(0.36, 0.56, 0.22)
	kinds["ash"] = _trunk(3.6, 0.2, bark, 3, 5.6, 1.4) + _crown([
		[0, 7.6, 0, 1.3], [2.0, 6.4, 0.6, 1.1], [-1.8, 6.6, 1.0, 1.15], [0.6, 6.2, -2.0, 1.1],
		[-1.0, 8.2, -1.0, 1.0], [1.2, 8.0, 1.3, 1.0], [-2.2, 5.6, -0.8, 0.9]], [ga, gb], 0.06)
	# Honey locust: wide, flat, see-through, yellow-green.
	var gl := Color(0.60, 0.70, 0.30); var gm := Color(0.52, 0.66, 0.28)
	kinds["locust"] = _trunk(3.2, 0.2, bark, 4, 5.2, 2.0) + _crown([
		[0, 6.2, 0, 1.2], [2.4, 5.8, 0.3, 1.0], [-2.3, 5.9, 0.5, 1.05], [0.4, 5.7, -2.4, 1.0],
		[-0.3, 5.8, 2.4, 1.0], [1.6, 6.6, -1.5, 0.9], [-1.6, 6.5, -1.4, 0.9], [1.5, 6.4, 1.7, 0.9]], [gl, gm], 0.05, 0.7)
	# Elm / hackberry: vase shape, wide top.
	kinds["elm"] = _trunk(3.0, 0.26, bark, 4, 7.0, 2.2) + _crown([
		[0, 9.0, 0, 1.9], [2.6, 8.4, 0.4, 1.7], [-2.5, 8.5, 0.6, 1.7], [0.3, 8.3, -2.6, 1.6],
		[-0.4, 8.2, 2.6, 1.6], [1.8, 7.4, -1.8, 1.3], [-1.8, 7.3, -1.6, 1.3]], [g1, g3], 0.08, 0.8)
	# Oak: big, dark, irregular.
	var go := Color(0.24, 0.42, 0.17)
	kinds["oak"] = _trunk(2.8, 0.32, bark, 4, 4.6, 1.6) + _crown([
		[0, 6.4, 0, 2.2], [2.2, 5.6, 0.6, 1.8], [-2.0, 5.8, 1.0, 1.9], [0.5, 5.4, -2.2, 1.8],
		[-0.8, 7.6, -0.8, 1.6], [1.3, 7.3, 1.4, 1.5], [-1.9, 5.0, -1.5, 1.5]], [go, g3], 0.09)
	# Poplar: columnar.
	kinds["poplar"] = _trunk(2.0, 0.22, bark) + _crown([
		[0, 3.6, 0, 1.2], [0, 5.4, 0, 1.35], [0, 7.4, 0, 1.3], [0, 9.3, 0, 1.05], [0, 10.8, 0, 0.7]], [g2, g1], 0.07, 1.25)
	# Birch: slender white trunk, small loose crown.
	var birch := Color(0.92, 0.91, 0.86)
	kinds["birch"] = _trunk(4.5, 0.15, birch, 2, 6.0, 0.9) + _crown([
		[0, 7.2, 0, 1.2], [1.0, 6.2, 0.4, 1.0], [-0.9, 6.4, -0.3, 1.0], [0.2, 8.2, 0.3, 0.9], [-0.3, 5.4, 0.9, 0.85]], [ga, g2], 0.06)
	# Small ornamental (crab apple, cherry, lilac).
	kinds["small_tree"] = _trunk(1.6, 0.14, bark, 3, 2.4, 0.6) + _crown([
		[0, 3.3, 0, 1.1], [0.8, 2.9, 0.3, 0.9], [-0.7, 3.0, -0.4, 0.9], [0.1, 3.9, -0.3, 0.8]], [g2, g1], 0.05)


## A parked car (body colour from the variant).
func _car() -> void:
	var body := BoxMesh.new(); body.size = Vector3(1.8, 0.75, 4.4)
	var cabin := BoxMesh.new(); cabin.size = Vector3(1.56, 0.5, 2.1)
	var glass := BoxMesh.new(); glass.size = Vector3(1.6, 0.32, 2.16)
	var wheel := CylinderMesh.new(); wheel.top_radius = 0.34; wheel.bottom_radius = 0.34; wheel.height = 0.24; wheel.radial_segments = 10
	var paint := [Color(0.90, 0.91, 0.92), Color(0.70, 0.72, 0.75), Color(0.30, 0.32, 0.36), Color(0.72, 0.18, 0.15),
		Color(0.28, 0.44, 0.70), Color(0.96, 0.96, 0.97), Color(0.80, 0.74, 0.60), Color(0.30, 0.58, 0.60)]
	var rb := Basis(Vector3.BACK, PI / 2)
	kinds["car"] = [
		[body, Color.WHITE, 0.04, T3(0, 0.77, 0), paint],
		[cabin, Color.WHITE, 0.04, T3(0, 1.39, -0.1), paint],
		[glass, Color(0.22, 0.30, 0.40), 0.0, T3(0, 1.42, -0.1)],
		[wheel, Color(0.10, 0.10, 0.11), 0.02, Transform3D(rb, Vector3(-0.86, 0.43, 1.4))],
		[wheel, Color(0.10, 0.10, 0.11), 0.02, Transform3D(rb, Vector3(0.86, 0.43, 1.4))],
		[wheel, Color(0.10, 0.10, 0.11), 0.02, Transform3D(rb, Vector3(-0.86, 0.43, -1.4))],
		[wheel, Color(0.10, 0.10, 0.11), 0.02, Transform3D(rb, Vector3(0.86, 0.43, -1.4))],
	]


## Robot City props: a little dome-helmet robot and an energy capsule.
func _robot_theme() -> void:
	var dome := SphereMesh.new(); dome.radius = 0.42; dome.height = 0.42; dome.is_hemisphere = true; dome.radial_segments = 10; dome.rings = 4
	var body := CylinderMesh.new(); body.top_radius = 0.34; body.bottom_radius = 0.38; body.height = 0.35; body.radial_segments = 10
	var eye := SphereMesh.new(); eye.radius = 0.11; eye.height = 0.22; eye.radial_segments = 8; eye.rings = 4
	var pupil := SphereMesh.new(); pupil.radius = 0.05; pupil.height = 0.1; pupil.radial_segments = 6; pupil.rings = 3
	var foot := BoxMesh.new(); foot.size = Vector3(0.18, 0.12, 0.3)
	var helmet_cols := [Color(0.98, 0.78, 0.10), Color(0.95, 0.45, 0.10), Color(0.20, 0.70, 0.95), Color(0.85, 0.20, 0.30)]
	kinds["bot"] = [
		[body, Color(0.35, 0.36, 0.42), 0.03, T3(0, 0.3, 0)],
		[dome, Color.WHITE, 0.04, T3(0, 0.46, 0), helmet_cols],
		[eye, Color(0.98, 0.98, 0.98), 0.02, T3(0, 0.3, 0.33)],
		[pupil, Color(0.05, 0.05, 0.08), 0.0, T3(0, 0.3, 0.43)],
		[foot, Color(0.25, 0.26, 0.32), 0.02, T3(-0.18, 0.06, 0.05)],
		[foot, Color(0.25, 0.26, 0.32), 0.02, T3(0.18, 0.06, 0.05)],
	]
	var tube := CylinderMesh.new(); tube.top_radius = 0.22; tube.bottom_radius = 0.22; tube.height = 0.42; tube.radial_segments = 10
	var cap := CylinderMesh.new(); cap.top_radius = 0.26; cap.bottom_radius = 0.26; cap.height = 0.1; cap.radial_segments = 10
	kinds["capsule"] = [
		[tube, Color.WHITE, 0.03, T3(0, 0, 0), [Color(0.25, 0.85, 0.99), Color(0.99, 0.85, 0.20)]],
		[cap, Color(0.95, 0.95, 0.98), 0.03, T3(0, 0.25, 0)],
		[cap, Color(0.95, 0.95, 0.98), 0.03, T3(0, -0.25, 0)],
	]


func T3(x: float, y: float, z: float) -> Transform3D:
	return Transform3D(Basis(), Vector3(x, y, z))


func _material(grow: float) -> ShaderMaterial:
	var key := "g%.3f" % grow
	if mats.has(key): return mats[key]
	var m := ShaderMaterial.new()
	m.shader = toon_shader
	m.set_shader_parameter("mode", 0)
	if grow > 0.0:
		var o := ShaderMaterial.new()
		o.shader = hull_shader
		o.set_shader_parameter("grow", grow)
		m.next_pass = o
	mats[key] = m
	return m


## Instance a chunk's props under [root].
func add_chunk(root: Node3D, list: Array) -> void:
	var by_type := {}
	for p in list:
		var t: String = p["t"]
		if not kinds.has(t): continue
		if not by_type.has(t): by_type[t] = []
		by_type[t].append(p)
	for t in by_type:
		var items: Array = by_type[t]
		for part in kinds[t]:
			var mm := MultiMesh.new()
			mm.transform_format = MultiMesh.TRANSFORM_3D
			mm.use_colors = true
			mm.mesh = part[0]
			mm.instance_count = items.size()
			for i in items.size():
				var p = items[i]
				var pos := Vector3(p["p"][0], p["p"][1], p["p"][2])
				var s := float(p["s"])
				var b := Basis(Vector3.UP, float(p["r"])).scaled(Vector3(s, s, s))
				var local: Transform3D = part[3]
				mm.set_instance_transform(i, Transform3D(b, pos) * local)
				# A little colour variety per instance (greens, wood).
				var k := int(p.get("k", 0))
				if part.size() > 4:
					var pal: Array = part[4]
					mm.set_instance_color(i, pal[k % pal.size()])
					continue
				var c: Color = part[1]
				mm.set_instance_color(i, c.lightened(0.04 * (k % 4)).darkened(0.03 * (k / 4)))
			var mmi := MultiMeshInstance3D.new()
			mmi.multimesh = mm
			mmi.material_override = _material(part[2])
			root.add_child(mmi)
