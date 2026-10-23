extends Node3D
## Street props from the world chunks (mapped trees, lamps, benches, bus stops …)
## as instanced low-poly meshes, cel-shaded with ink outlines.

var toon_shader: Shader = preload("res://shaders/toon.gdshader")
var hull_shader: Shader = preload("res://shaders/outline_hull.gdshader")

## type -> list of parts: [mesh, colour, outline grow (0 = none), local transform]
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
				var c: Color = part[1]
				mm.set_instance_color(i, c.lightened(0.04 * (k % 4)).darkened(0.03 * (k / 4)))
			var mmi := MultiMeshInstance3D.new()
			mmi.multimesh = mm
			mmi.material_override = _material(part[2])
			root.add_child(mmi)
