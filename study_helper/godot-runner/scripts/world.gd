class_name RunnerWorld
extends Node3D
## A bounded set of reusable bridge / canyon sections. Distances are in metres.

const BRIDGE: PackedScene = preload("res://assets/environment/causeway.glb")
const CANYON: PackedScene = preload("res://assets/environment/canyon.glb")
const GATE: PackedScene = preload("res://assets/environment/sun_gate.glb")
var bridges: Array[Node3D] = []
var canyons: Array[Node3D] = []
var gates: Array[Node3D] = []


func _ready() -> void:
	print("Jade Run: building environment")
	_make_lighting()
	_make_water()
	for i in 10:
		var bridge := BRIDGE.instantiate() as Node3D
		add_child(bridge)
		bridges.append(bridge)
	for i in 5:
		var canyon := CANYON.instantiate() as Node3D
		add_child(canyon)
		canyons.append(canyon)
	for i in 3:
		var gate := GATE.instantiate() as Node3D
		add_child(gate)
		gates.append(gate)
	var floor_body := StaticBody3D.new()
	floor_body.collision_layer = 1
	floor_body.collision_mask = 2
	var collision := CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = Vector3(8, 1, 24)
	collision.shape = shape
	collision.position.y = -0.5
	floor_body.add_child(collision)
	add_child(floor_body)
	set_distance(0.0)


func set_distance(distance: float) -> void:
	for i in bridges.size():
		bridges[i].position.z = fposmod(distance, 24.0) + 24.0 - i * 24.0
	for i in canyons.size():
		canyons[i].position.z = fposmod(distance, 48.0) + 48.0 - i * 48.0
	for i in gates.size():
		gates[i].position.z = fposmod(distance + 40.0, 80.0) - 8.0 - i * 80.0


func _make_lighting() -> void:
	var environment := Environment.new()
	environment.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	var sky_material := ProceduralSkyMaterial.new()
	sky_material.sky_top_color = Color("528d94")
	sky_material.sky_horizon_color = Color("e5e5b8")
	sky_material.ground_bottom_color = Color("204943")
	sky_material.ground_horizon_color = Color("c9dab4")
	sky_material.sky_curve = 0.18
	sky_material.sun_angle_max = 8.0
	sky.sky_material = sky_material
	environment.sky = sky
	environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.ambient_light_color = Color("9ac8bd")
	environment.ambient_light_energy = 0.30
	environment.ambient_light_sky_contribution = 0.0
	environment.tonemap_mode = Environment.TONE_MAPPER_LINEAR
	environment.fog_enabled = true
	environment.fog_light_color = Color("b9d7b7")
	environment.fog_density = 0.006
	environment.fog_sky_affect = 0.22
	var world_environment := WorldEnvironment.new()
	world_environment.environment = environment
	add_child(world_environment)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-38, -32, 0)
	sun.light_color = Color("fff0c4")
	sun.light_energy = 0.65
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 75.0
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_2_SPLITS
	sun.shadow_bias = 0.20
	sun.shadow_normal_bias = 2.0
	add_child(sun)


func _make_water() -> void:
	var water := MeshInstance3D.new()
	var plane := PlaneMesh.new()
	plane.size = Vector2(220, 300)
	plane.subdivide_width = 40
	plane.subdivide_depth = 40
	water.mesh = plane
	water.position = Vector3(0, -3.8, -95)
	var shader := Shader.new()
	shader.code = """
shader_type spatial;
render_mode cull_disabled;
varying vec3 world;
void vertex() {
  world = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
  VERTEX.y += sin(world.x * 0.6 + TIME * 0.5) * 0.09;
}
void fragment() {
  float waves = sin(world.x * 1.7 + world.z * 0.6 + TIME * 0.8);
  float crest = smoothstep(0.92, 1.0, waves * sin(world.z * 1.4 - TIME * 0.6));
  ALBEDO = mix(vec3(0.025, 0.34, 0.31), vec3(0.34, 0.64, 0.49), crest * 0.52);
  ROUGHNESS = 0.32;
  METALLIC = 0.2;
}
"""
	var mat := ShaderMaterial.new()
	mat.shader = shader
	water.material_override = mat
	add_child(water)
