class_name StudyGate
extends Node3D
## Reusable numbered gate; the wall breaks only at confirmed impact.

var broken: bool = false
var number: int = 1
var _pieces: Array[MeshInstance3D] = []
var _origins: Array[Vector3] = []
var _tween: Tween
var _number_label: Label3D

func _ready() -> void:
	var stone := StandardMaterial3D.new()
	stone.albedo_color = Color("194f48")
	stone.roughness = .8
	var gold := StandardMaterial3D.new()
	gold.albedo_color = Color("f7cd75")
	gold.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	for side in [-1, 1]:
		_box(Vector3(.8, 5.4, 1), Vector3(side * 3.8, 2.7, 0), stone)
	for row in 3:
		for column in 4:
			var origin := Vector3((column - 1.5) * 1.63, .74 + row * 1.47, 0)
			var piece := _box(Vector3(1.60, 1.44, .4), origin, stone)
			_pieces.append(piece)
			_origins.append(origin)
			var rune := _box(Vector3(.08, 1.05, .05), Vector3(0, 0, .23), gold)
			remove_child(rune)
			piece.add_child(rune)
	_box(Vector3(8.4, .65, 1.1), Vector3(0, 5.2, 0), stone)
	_box(Vector3(7.8, .08, 1.2), Vector3(0, 5.45, .05), gold)
	_number_label = Label3D.new()
	_number_label.position = Vector3(0, 5.05, .65)
	_number_label.font_size = 64
	_number_label.pixel_size = .012
	_number_label.modulate = Color("f7cd75")
	add_child(_number_label)
	prepare(1)

func prepare(index: int) -> void:
	number = index
	_number_label.text = "%02d" % index
	reset_gate()

func reset_gate() -> void:
	if _tween and _tween.is_valid():
		_tween.kill()
	broken = false
	for i in _pieces.size():
		_pieces[i].position = _origins[i]
		_pieces[i].rotation = Vector3.ZERO
		_pieces[i].scale = Vector3.ONE

func shatter() -> void:
	if broken:
		return
	broken = true
	_tween = create_tween().set_parallel()
	for i in _pieces.size():
		var origin := _origins[i]
		var destination := origin + Vector3(signf(origin.x) * (2.0 + i % 3), .5 + i % 2, -3.0 - i % 4)
		_tween.tween_property(_pieces[i], "position", destination, .32).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)
		_tween.tween_property(_pieces[i], "rotation", Vector3(.8 + i * .13, i * .27, origin.x * .5), .75)
		_tween.tween_property(_pieces[i], "position:y", -.8, .5).set_delay(.32)
		_tween.tween_property(_pieces[i], "scale", Vector3.ONE * .02, .35).set_delay(.5)

func set_paused(paused: bool) -> void:
	if _tween and _tween.is_valid():
		if paused:
			_tween.pause()
		else:
			_tween.play()

func _box(size: Vector3, at: Vector3, material: Material) -> MeshInstance3D:
	var mesh := BoxMesh.new()
	mesh.size = size
	var instance := MeshInstance3D.new()
	instance.mesh = mesh
	instance.material_override = material
	instance.position = at
	add_child(instance)
	return instance
