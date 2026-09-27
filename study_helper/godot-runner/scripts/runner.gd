class_name RunnerCharacter
extends CharacterBody3D
## Physics drives jumping; a skinned model supplies the running animation.

signal landed

const LANE_WIDTH: float = 2.36
const GRAVITY: float = 25.0
const JUMP_SPEED: float = 9.8
const SLIDE_SECONDS: float = 0.85
var lane: int = 1
var slide_remaining: float = 0.0
var invulnerability: float = 0.0
var animation: AnimationPlayer
var _was_airborne: bool = false
@onready var visual: Node3D = $Visual


func _ready() -> void:
	animation = _find_animation($Visual/Model)
	_hide_equipment($Visual/Model)
	for key in ["Idle", "Running_A", "Jump_Idle", "Sit_Floor_Pose"]:
		if animation.has_animation(key):
			animation.get_animation(key).loop_mode = Animation.LOOP_LINEAR
	reset()


func reset() -> void:
	lane = 1
	position = Vector3(0, 0.02, 0)
	velocity = Vector3.ZERO
	slide_remaining = 0.0
	invulnerability = 0.0
	visual.visible = true
	visual.scale = Vector3.ONE
	visual.rotation = Vector3.ZERO
	_was_airborne = false
	animation.speed_scale = 1.0
	_play("Idle")


func land_from_dive() -> void:
	position.y = .02
	velocity = Vector3.ZERO
	visual.rotation = Vector3.ZERO
	_was_airborne = false
	animation.speed_scale = 1.0
	_play("Running_A", .12)


func move_lane(direction: int) -> void:
	lane = clampi(lane + direction, 0, 2)


func jump() -> bool:
	if _was_airborne or position.y > 0.10 or slide_remaining > 0.0:
		return false
	velocity.y = JUMP_SPEED
	_was_airborne = true
	_play("Jump_Idle", 0.09)
	return true


func slide() -> bool:
	if _was_airborne or position.y > 0.10 or slide_remaining > 0.0:
		return false
	slide_remaining = SLIDE_SECONDS
	_play("Sit_Floor_Pose", 0.10)
	return true


func step(delta: float, speed: float) -> void:
	slide_remaining = maxf(0.0, slide_remaining - delta)
	invulnerability = maxf(0.0, invulnerability - delta)
	var difference := (lane - 1) * LANE_WIDTH - position.x
	velocity.x = clampf(difference * 18.0, -14.0, 14.0)
	velocity.z = 0.0
	velocity.y -= GRAVITY * delta
	move_and_slide()
	if is_on_floor() and _was_airborne:
		_was_airborne = false
		landed.emit()
	visual.rotation.z = lerpf(visual.rotation.z, -velocity.x * 0.024, 1.0-exp(-12.0*delta))
	visual.rotation.x = lerpf(visual.rotation.x, -0.15 if slide_remaining > 0 else 0.0, 0.25)
	visual.scale.y = lerpf(visual.scale.y, 0.70 if slide_remaining > 0 else 1.0, 0.30)
	visual.visible = invulnerability <= 0.0 or int(invulnerability * 12.0) % 2 == 0
	if slide_remaining > 0.0:
		_play("Sit_Floor_Pose", 0.12)
	elif position.y > 0.12:
		_play("Jump_Idle", 0.12)
	else:
		_play("Running_A", 0.14)
	animation.speed_scale = clampf(speed / 14.0, 0.95, 1.45)


func set_frozen(frozen: bool) -> void:
	animation.speed_scale = 0.0 if frozen else 1.0


func celebrate() -> void:
	visual.visible = true
	animation.speed_scale = 1.0
	_play("Cheer")


func _play(key: String, blend: float = 0.18) -> void:
	if animation.current_animation != key:
		animation.play(key, blend)


func _find_animation(node: Node) -> AnimationPlayer:
	if node is AnimationPlayer:
		return node as AnimationPlayer
	for child in node.get_children():
		var found := _find_animation(child)
		if found:
			return found
	return null


func _hide_equipment(node: Node) -> void:
	if node is MeshInstance3D and not str(node.name).begins_with("Rogue_"):
		(node as MeshInstance3D).visible = false
	for child in node.get_children():
		_hide_equipment(child)
