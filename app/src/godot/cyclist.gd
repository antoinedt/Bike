extends Node3D
## The rider: a low-poly, cel-shaded cyclist (blue armoured rider, as in the 2D
## version) on a road bike, with ink outlines. Legs pedal at the real cadence (feet
## stay on the pedals via two-bone IK), wheels turn with the real speed.
## Local frame: X = right, Y = up, -Z = forward. Origin on the ground between the wheels.

var toon_shader: Shader = preload("res://shaders/toon.gdshader")
var hull_shader: Shader = preload("res://shaders/outline_hull.gdshader")

const WHEEL_R := 0.34
const CRANK := 0.17
const THIGH := 0.44
const SHIN := 0.45
const BB := Vector3(0, 0.30, 0.04)          # bottom bracket
const HIP := Vector3(0, 0.99, 0.23)          # hip centre (saddle)

const C_ARMOUR := Color(0.24, 0.48, 0.90)
const C_LIGHT := Color(0.58, 0.80, 0.98)
const C_DARK := Color(0.12, 0.17, 0.32)
const C_FRAME := Color(0.92, 0.94, 0.97)
const C_TYRE := Color(0.13, 0.13, 0.15)
const C_SKIN := Color(0.96, 0.80, 0.66)

var crank_angle := 0.0
var front_wheel: Node3D
var rear_wheel: Node3D
var cranks: Node3D
var legs := []   # per side: [thigh, shin, foot, side_x]
var mats := {}


func _ready() -> void:
	_build_bike()
	_build_rider()
	_update_legs()


func _mat(c: Color, outline := 0.03) -> ShaderMaterial:
	var key := c.to_html() + str(outline)
	if mats.has(key): return mats[key]
	var m := ShaderMaterial.new()
	m.shader = toon_shader
	m.set_shader_parameter("mode", 0)
	m.set_shader_parameter("tint", c)
	if outline > 0.0:
		var o := ShaderMaterial.new()
		o.shader = hull_shader
		o.set_shader_parameter("grow", outline)
		m.next_pass = o
	mats[key] = m
	return m


func _part(mesh: Mesh, c: Color, parent: Node3D, pos := Vector3.ZERO, basis := Basis(), outline := 0.03) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = _mat(c, outline)
	mi.transform = Transform3D(basis, pos)
	parent.add_child(mi)
	return mi


## A cylinder/capsule from a to b (radius r), parented to [parent].
func _limb(a: Vector3, b: Vector3, r: float, c: Color, parent: Node3D, capsule := true, outline := 0.025) -> MeshInstance3D:
	var mesh: PrimitiveMesh
	var len := a.distance_to(b)
	if capsule:
		var cm := CapsuleMesh.new(); cm.radius = r; cm.height = len + 2.0 * r; cm.radial_segments = 8; cm.rings = 2
		mesh = cm
	else:
		var cy := CylinderMesh.new(); cy.top_radius = r; cy.bottom_radius = r; cy.height = len; cy.radial_segments = 6
		mesh = cy
	var mi := _part(mesh, c, parent, Vector3.ZERO, Basis(), outline)
	_place(mi, a, b)
	return mi


func _place(node: Node3D, a: Vector3, b: Vector3) -> void:
	var d := b - a
	if d.length() < 1e-4: return
	node.transform = Transform3D(Basis(Quaternion(Vector3.UP, d.normalized())), (a + b) * 0.5)


func _wheel(z: float) -> Node3D:
	var w := Node3D.new()
	w.position = Vector3(0, WHEEL_R, z)
	add_child(w)
	var tyre := TorusMesh.new(); tyre.inner_radius = WHEEL_R - 0.045; tyre.outer_radius = WHEEL_R; tyre.rings = 20; tyre.ring_segments = 6
	_part(tyre, C_TYRE, w, Vector3.ZERO, Basis(Vector3.FORWARD, PI / 2), 0.02)
	var rim := TorusMesh.new(); rim.inner_radius = WHEEL_R - 0.07; rim.outer_radius = WHEEL_R - 0.045; rim.rings = 20; rim.ring_segments = 4
	_part(rim, C_ARMOUR, w, Vector3.ZERO, Basis(Vector3.FORWARD, PI / 2), 0.0)
	# Three thick spokes so the rotation reads.
	for k in 3:
		var a := k * TAU / 3.0
		var tip := Vector3(0, sin(a), cos(a)) * (WHEEL_R - 0.06)
		_limb(Vector3.ZERO, tip, 0.012, C_LIGHT, w, false, 0.0)
	var hub := CylinderMesh.new(); hub.top_radius = 0.035; hub.bottom_radius = 0.035; hub.height = 0.1; hub.radial_segments = 8
	_part(hub, C_DARK, w, Vector3.ZERO, Basis(Vector3.FORWARD, PI / 2), 0.0)
	return w


func _build_bike() -> void:
	rear_wheel = _wheel(0.50)
	front_wheel = _wheel(-0.52)
	var seat_top := Vector3(0, 0.90, 0.20)
	var head_top := Vector3(0, 0.86, -0.38)
	var head_bot := Vector3(0, 0.72, -0.42)
	var rear := Vector3(0, WHEEL_R, 0.50)
	var front := Vector3(0, WHEEL_R, -0.52)
	_limb(BB, seat_top, 0.022, C_FRAME, self, false)           # seat tube
	_limb(seat_top, head_top, 0.022, C_FRAME, self, false)     # top tube
	_limb(BB, head_bot, 0.026, C_FRAME, self, false)           # down tube
	_limb(head_bot, head_top, 0.026, C_FRAME, self, false)     # head tube
	for sx in [-0.05, 0.05]:
		_limb(BB + Vector3(sx, 0, 0), rear + Vector3(sx, 0, 0), 0.014, C_FRAME, self, false)        # chain stays
		_limb(seat_top + Vector3(sx, 0, 0), rear + Vector3(sx, 0, 0), 0.013, C_FRAME, self, false)  # seat stays
		_limb(head_bot + Vector3(sx, 0, 0), front + Vector3(sx, 0, 0), 0.014, C_FRAME, self, false) # fork
	_limb(head_top, Vector3(0, 0.98, -0.40), 0.018, C_DARK, self, false)                             # stem
	_limb(Vector3(-0.21, 0.98, -0.42), Vector3(0.21, 0.98, -0.42), 0.017, C_DARK, self, false)       # handlebar
	var saddle := BoxMesh.new(); saddle.size = Vector3(0.12, 0.05, 0.26)
	_part(saddle, C_DARK, self, Vector3(0, 0.95, 0.22))
	_limb(seat_top, Vector3(0, 0.93, 0.22), 0.014, C_DARK, self, false)                              # seat post
	cranks = Node3D.new()
	cranks.position = BB
	add_child(cranks)
	var ring := CylinderMesh.new(); ring.top_radius = 0.09; ring.bottom_radius = 0.09; ring.height = 0.015; ring.radial_segments = 12
	_part(ring, C_DARK, cranks, Vector3(0.06, 0, 0), Basis(Vector3.FORWARD, PI / 2), 0.0)


func _build_rider() -> void:
	# Torso leaning toward the bars, helmet, arms to the hoods.
	var neck := Vector3(0, 1.38, -0.14)
	_limb(HIP + Vector3(0, 0.06, 0), neck, 0.12, C_ARMOUR, self)                        # jersey
	_limb(HIP + Vector3(0, 0.02, 0.01), HIP + Vector3(0, 0.12, -0.02), 0.135, C_DARK, self, true, 0.02) # shorts / belt
	# A light stripe down the back so the torso reads from behind.
	_limb(HIP + Vector3(0, 0.16, 0.06), neck + Vector3(0, 0.03, 0.06), 0.04, C_LIGHT, self, true, 0.0)
	var helmet := SphereMesh.new(); helmet.radius = 0.15; helmet.height = 0.28; helmet.radial_segments = 10; helmet.rings = 6
	_part(helmet, C_LIGHT, self, Vector3(0, 1.53, -0.25), Basis(), 0.03)
	var visor := BoxMesh.new(); visor.size = Vector3(0.20, 0.06, 0.05)
	_part(visor, C_LIGHT, self, Vector3(0, 1.51, -0.39), Basis(Vector3.RIGHT, 0.25), 0.015)
	var fin := BoxMesh.new(); fin.size = Vector3(0.03, 0.06, 0.18)
	_part(fin, C_LIGHT, self, Vector3(0, 1.67, -0.24), Basis(), 0.015)
	for sx in [-1.0, 1.0]:
		var shoulder := Vector3(0.19 * sx, 1.32, -0.10)
		var elbow := Vector3(0.23 * sx, 1.12, -0.28)
		var hand := Vector3(0.20 * sx, 0.99, -0.42)
		_limb(shoulder, elbow, 0.055, C_ARMOUR, self)
		_limb(elbow, hand, 0.048, C_LIGHT, self)
		var glove := SphereMesh.new(); glove.radius = 0.05; glove.height = 0.1; glove.radial_segments = 8; glove.rings = 4
		_part(glove, C_DARK, self, hand, Basis(), 0.015)
		var pad := SphereMesh.new(); pad.radius = 0.08; pad.height = 0.14; pad.radial_segments = 8; pad.rings = 4
		_part(pad, C_LIGHT, self, shoulder, Basis(), 0.02)
		# Created at their real lengths: _place() only moves / turns them.
		var thigh := _limb(Vector3.ZERO, Vector3.UP * THIGH, 0.07, C_DARK, self)
		var shin := _limb(Vector3.ZERO, Vector3.UP * SHIN, 0.058, C_ARMOUR, self)
		var boot := BoxMesh.new(); boot.size = Vector3(0.09, 0.08, 0.24)
		var foot := _part(boot, C_DARK, self, Vector3.ZERO, Basis(), 0.02)
		legs.append([thigh, shin, foot, 0.11 * sx])


## Two-bone IK: knee from hip to pedal, bending forward.
func _update_legs() -> void:
	for side in legs.size():
		var leg = legs[side]
		var sx: float = leg[3]
		var a := crank_angle + (PI if side == 1 else 0.0)
		var pedal := BB + Vector3(sx, sin(a) * CRANK, -cos(a) * CRANK)
		var hip := HIP + Vector3(sx, 0, 0)
		var to := pedal + Vector3(0, 0.05, 0) - hip
		var d := clampf(to.length(), 0.2, THIGH + SHIN - 0.01)
		var dir := to.normalized()
		var x := (THIGH * THIGH - SHIN * SHIN + d * d) / (2.0 * d)
		var h := sqrt(maxf(THIGH * THIGH - x * x, 0.0))
		# Perpendicular in the side plane, pointing forward (-Z) and up.
		var perp := Vector3(0, dir.z, -dir.y).normalized()
		if perp.z > 0: perp = -perp
		var knee := hip + dir * x + perp * h
		var ankle := hip + dir * d
		_place(leg[0], hip, knee)
		_place(leg[1], knee, ankle)
		(leg[2] as Node3D).transform = Transform3D(Basis(), ankle + Vector3(0, -0.03, -0.03))
	cranks.rotation = Vector3(-crank_angle, 0, 0)


## Advance the animation: [speed] in m/s, [cadence] in rpm.
func animate(delta: float, speed: float, cadence: float) -> void:
	crank_angle = fmod(crank_angle + cadence / 60.0 * TAU * delta, TAU)
	var spin := speed / WHEEL_R * delta
	front_wheel.rotate_x(-spin)
	rear_wheel.rotate_x(-spin)
	_update_legs()
