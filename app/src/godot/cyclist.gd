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

const C_TYRE := Color(0.13, 0.13, 0.15)
const C_SKIN := Color(0.96, 0.80, 0.66)

## Rider designs (pick with [style]). Colours per body part, head style, posture
## (0 = racing crouch, 1 = upright city riding) and bike type.
const STYLES := {
	"armour": {
		"frame": Color(0.92, 0.94, 0.97), "rim": Color(0.24, 0.48, 0.90), "bike": "road",
		"jersey": Color(0.24, 0.48, 0.90), "stripe": Color(0.58, 0.80, 0.98), "shorts": Color(0.12, 0.17, 0.32),
		"thigh": Color(0.12, 0.17, 0.32), "shin": Color(0.24, 0.48, 0.90), "arm": Color(0.24, 0.48, 0.90),
		"forearm": Color(0.58, 0.80, 0.98), "hands": Color(0.12, 0.17, 0.32), "shoes": Color(0.12, 0.17, 0.32),
		"head": "armour", "helmet": Color(0.58, 0.80, 0.98), "posture": 0.0, "pads": true,
	},
	"pro": {
		"frame": Color(0.10, 0.11, 0.13), "rim": Color(0.85, 0.20, 0.18), "bike": "road",
		"jersey": Color(0.86, 0.22, 0.20), "stripe": Color(0.97, 0.97, 0.95), "shorts": Color(0.10, 0.10, 0.12),
		"thigh": Color(0.10, 0.10, 0.12), "shin": C_SKIN, "arm": Color(0.86, 0.22, 0.20),
		"forearm": C_SKIN, "hands": Color(0.10, 0.10, 0.12), "shoes": Color(0.97, 0.97, 0.95),
		"head": "aero", "helmet": Color(0.97, 0.97, 0.95), "posture": -0.15, "socks": Color(0.97, 0.97, 0.95),
	},
	"city": {
		"frame": Color(0.38, 0.78, 0.70), "rim": Color(0.80, 0.82, 0.85), "bike": "city",
		"jersey": Color(0.30, 0.70, 0.62), "stripe": Color(0.22, 0.56, 0.50), "shorts": Color(0.13, 0.15, 0.24),
		"thigh": Color(0.13, 0.15, 0.24), "shin": Color(0.13, 0.15, 0.24), "arm": Color(0.30, 0.70, 0.62),
		"forearm": Color(0.30, 0.70, 0.62), "hands": C_SKIN, "shoes": Color(0.97, 0.97, 0.95),
		"head": "hair", "hair": Color(0.88, 0.32, 0.33), "posture": 1.0, "backpack": true,
	},
	"chibi": {
		"frame": Color(0.98, 0.62, 0.16), "rim": Color(0.20, 0.22, 0.28), "bike": "road",
		"jersey": Color(0.99, 0.80, 0.20), "stripe": Color(0.98, 0.62, 0.16), "shorts": Color(0.25, 0.30, 0.55),
		"thigh": Color(0.25, 0.30, 0.55), "shin": Color(0.99, 0.80, 0.20), "arm": Color(0.99, 0.80, 0.20),
		"forearm": Color(0.99, 0.80, 0.20), "hands": Color(0.25, 0.30, 0.55), "shoes": Color(0.98, 0.62, 0.16),
		"head": "big", "helmet": Color(0.98, 0.62, 0.16), "posture": 0.5, "chibi": true,
	},
}

## Which design to build (set before adding to the scene).
var style := "armour"
var S: Dictionary

var crank_angle := 0.0
var front_wheel: Node3D
var rear_wheel: Node3D
var cranks: Node3D
var legs := []   # per side: [thigh, shin, foot, side_x]
var mats := {}


func _ready() -> void:
	S = STYLES.get(style, STYLES["armour"])
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
	_part(rim, S["rim"], w, Vector3.ZERO, Basis(Vector3.FORWARD, PI / 2), 0.0)
	# Three thick spokes so the rotation reads.
	for k in 3:
		var a := k * TAU / 3.0
		var tip := Vector3(0, sin(a), cos(a)) * (WHEEL_R - 0.06)
		_limb(Vector3.ZERO, tip, 0.012, S["rim"].lightened(0.3), w, false, 0.0)
	var hub := CylinderMesh.new(); hub.top_radius = 0.035; hub.bottom_radius = 0.035; hub.height = 0.1; hub.radial_segments = 8
	_part(hub, C_TYRE, w, Vector3.ZERO, Basis(Vector3.FORWARD, PI / 2), 0.0)
	return w


func _build_bike() -> void:
	var city: bool = S["bike"] == "city"
	var fc: Color = S["frame"]
	rear_wheel = _wheel(0.50)
	front_wheel = _wheel(-0.52)
	var seat_top := Vector3(0, 0.90, 0.20)
	var head_top := Vector3(0, 0.90 if city else 0.86, -0.38)
	var head_bot := Vector3(0, 0.72, -0.42)
	var rear := Vector3(0, WHEEL_R, 0.50)
	var front := Vector3(0, WHEEL_R, -0.52)
	_limb(BB, seat_top, 0.022, fc, self, false)                                     # seat tube
	if city:
		_limb(BB + Vector3(0, 0.02, -0.06), head_bot + Vector3(0, 0.08, 0), 0.03, fc, self, false)  # step-through
	else:
		_limb(seat_top, head_top, 0.022, fc, self, false)                             # top tube
	_limb(BB, head_bot, 0.026, fc, self, false)                                       # down tube
	_limb(head_bot, head_top, 0.026, fc, self, false)                                 # head tube
	for sx in [-0.05, 0.05]:
		_limb(BB + Vector3(sx, 0, 0), rear + Vector3(sx, 0, 0), 0.014, fc, self, false)        # chain stays
		_limb(seat_top + Vector3(sx, 0, 0), rear + Vector3(sx, 0, 0), 0.013, fc, self, false)  # seat stays
		_limb(head_bot + Vector3(sx, 0, 0), front + Vector3(sx, 0, 0), 0.014, fc, self, false) # fork
	var bars := _bar_pos()
	_limb(head_top, Vector3(0, bars.y, bars.z + 0.02), 0.018, C_TYRE, self, false)                        # stem
	if city:
		# Swept-back city bars + mudguards.
		_limb(Vector3(0, bars.y, bars.z + 0.02), Vector3(-0.27, bars.y, bars.z + 0.14), 0.016, C_TYRE, self, false)
		_limb(Vector3(0, bars.y, bars.z + 0.02), Vector3(0.27, bars.y, bars.z + 0.14), 0.016, C_TYRE, self, false)
		for z in [0.50, -0.52]:
			var guard := TorusMesh.new(); guard.inner_radius = WHEEL_R + 0.01; guard.outer_radius = WHEEL_R + 0.05; guard.rings = 16; guard.ring_segments = 4
			var gm := _part(guard, fc, self, Vector3(0, WHEEL_R, z), Basis(Vector3.FORWARD, PI / 2), 0.0)
			gm.scale = Vector3(1, 1, 1)
		var basket := BoxMesh.new(); basket.size = Vector3(0.3, 0.2, 0.25)
		_part(basket, Color(0.72, 0.52, 0.32), self, Vector3(0, bars.y - 0.05, bars.z - 0.2), Basis(), 0.02)
	else:
		_limb(Vector3(-0.21, bars.y, bars.z), Vector3(0.21, bars.y, bars.z), 0.017, C_TYRE, self, false)    # handlebar
	var saddle := BoxMesh.new(); saddle.size = Vector3(0.14 if city else 0.12, 0.05, 0.24)
	_part(saddle, C_TYRE, self, Vector3(0, 0.95, 0.22))
	_limb(seat_top, Vector3(0, 0.93, 0.22), 0.014, C_TYRE, self, false)                              # seat post
	cranks = Node3D.new()
	cranks.position = BB
	add_child(cranks)
	var ring := CylinderMesh.new(); ring.top_radius = 0.09; ring.bottom_radius = 0.09; ring.height = 0.015; ring.radial_segments = 12
	_part(ring, C_TYRE, cranks, Vector3(0.06, 0, 0), Basis(Vector3.FORWARD, PI / 2), 0.0)


## Where the hands go: low and forward when racing, high and back when upright.
func _bar_pos() -> Vector3:
	var u: float = S["posture"]
	return Vector3(0, lerpf(0.98, 1.10, u), lerpf(-0.42, -0.34, u))


func _ball(r: float, c: Color, pos: Vector3, scale := Vector3.ONE, outline := 0.02) -> MeshInstance3D:
	var m := SphereMesh.new(); m.radius = r; m.height = 2.0 * r; m.radial_segments = 10; m.rings = 6
	var mi := _part(m, c, self, pos, Basis(), outline)
	mi.scale = scale
	return mi


func _build_rider() -> void:
	var u: float = S["posture"]
	var chibi: bool = S.get("chibi", false)
	# Torso from the saddle toward the bars (upright riders sit straighter).
	var neck := Vector3(0, lerpf(1.38, 1.55, u), lerpf(-0.14, 0.06, u))
	var torso_r := 0.10 if chibi else 0.12
	_limb(HIP + Vector3(0, 0.06, 0), neck, torso_r, S["jersey"], self)
	_limb(HIP + Vector3(0, 0.02, 0.01), HIP + Vector3(0, 0.12, -0.02), torso_r + 0.015, S["shorts"], self, true, 0.02)
	_limb(HIP + Vector3(0, 0.16, 0.06), neck + Vector3(0, 0.03, 0.06), 0.04, S["stripe"], self, true, 0.0)
	var head := neck + Vector3(0, 0.15, -0.10 + 0.08 * u)
	match S["head"]:
		"armour":
			_ball(0.15, S["helmet"], head, Vector3(1, 0.93, 1), 0.03)
			var visor := BoxMesh.new(); visor.size = Vector3(0.20, 0.06, 0.05)
			_part(visor, S["jersey"], self, head + Vector3(0, -0.02, -0.14), Basis(Vector3.RIGHT, 0.25), 0.015)
			var fin := BoxMesh.new(); fin.size = Vector3(0.03, 0.06, 0.18)
			_part(fin, S["jersey"], self, head + Vector3(0, 0.14, 0.01), Basis(), 0.015)
		"aero":
			_ball(0.105, C_SKIN, head + Vector3(0, -0.03, -0.01), Vector3.ONE, 0.02)
			_ball(0.14, S["helmet"], head + Vector3(0, 0.05, 0.05), Vector3(0.9, 0.7, 1.45), 0.025)
			var shades := BoxMesh.new(); shades.size = Vector3(0.20, 0.045, 0.04)
			_part(shades, C_TYRE, self, head + Vector3(0, -0.01, -0.10), Basis(), 0.01)
		"hair":
			_ball(0.11, C_SKIN, head, Vector3.ONE, 0.02)
			_ball(0.125, S["hair"], head + Vector3(0, 0.035, 0.03), Vector3(1.05, 0.85, 1.0), 0.025)   # bob
			for sx in [-1.0, 1.0]:
				_ball(0.07, S["hair"], head + Vector3(0.08 * sx, -0.04, 0.04), Vector3(0.7, 1.2, 1.0), 0.02)
		"big":
			_ball(0.25, S["helmet"], head + Vector3(0, 0.10, 0), Vector3.ONE, 0.035)
			var v := BoxMesh.new(); v.size = Vector3(0.30, 0.10, 0.06)
			_part(v, Color(0.22, 0.28, 0.42), self, head + Vector3(0, 0.06, -0.22), Basis(Vector3.RIGHT, 0.2), 0.02)
	if S.get("backpack", false):
		var pack := BoxMesh.new(); pack.size = Vector3(0.30, 0.36, 0.16)
		var back := (HIP + neck) * 0.5 + Vector3(0, 0.02, 0.13)
		var dirb := (neck - HIP).normalized()
		_part(pack, Color(0.15, 0.17, 0.26), self, back, Basis(Quaternion(Vector3.UP, dirb)), 0.025)
		var roll := CylinderMesh.new(); roll.top_radius = 0.07; roll.bottom_radius = 0.07; roll.height = 0.40; roll.radial_segments = 8
		_part(roll, Color(0.98, 0.80, 0.25), self, back + dirb * 0.22 + Vector3(0, 0, 0.02), Basis(Vector3.FORWARD, PI / 2), 0.025)
	var bars := _bar_pos()
	for sx in [-1.0, 1.0]:
		var shoulder := neck + Vector3((0.15 if chibi else 0.19) * sx, -0.06, 0.04)
		var hand := Vector3((0.27 if S["bike"] == "city" else 0.20) * sx, bars.y + 0.01, bars.z + (0.12 if S["bike"] == "city" else 0.0))
		var elbow := (shoulder + hand) * 0.5 + Vector3(0.05 * sx, -0.04 * (1.0 - u), 0.02)
		var arm_r := 0.06 if chibi else 0.05
		_limb(shoulder, elbow, arm_r + 0.005, S["arm"], self)
		_limb(elbow, hand, arm_r, S["forearm"], self)
		_ball(0.05, S["hands"], hand, Vector3.ONE, 0.015)
		if S.get("pads", false): _ball(0.08, S["stripe"], shoulder, Vector3.ONE, 0.02)
		# Created at their real lengths: _place() only moves / turns them.
		var thigh := _limb(Vector3.ZERO, Vector3.UP * THIGH, 0.08 if chibi else 0.07, S["thigh"], self)
		var shin := _limb(Vector3.ZERO, Vector3.UP * SHIN, 0.065 if chibi else 0.058, S["shin"], self)
		var boot := BoxMesh.new(); boot.size = Vector3(0.10, 0.09, 0.26)
		var foot := _part(boot, S["shoes"], self, Vector3.ZERO, Basis(), 0.02)
		if S.has("socks"):
			var sock := _limb(Vector3.ZERO, Vector3.UP * 0.12, 0.06, S["socks"], foot, true, 0.0)
			sock.transform = Transform3D(Basis(), Vector3(0, 0.08, 0.03))
		legs.append([thigh, shin, foot, 0.11 * sx])


## Two-bone IK: knee from hip to pedal, bending forward.
func _update_legs() -> void:
	for side in legs.size():
		var leg = legs[side]
		var sx: float = leg[3]
		var a := crank_angle + (PI if side == 1 else 0.0)
		# Forward (-Z) at a = 0, then down: the pedals turn forwards.
		var pedal := BB + Vector3(sx, -sin(a) * CRANK, -cos(a) * CRANK)
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
