class_name RunnerCourse3D
extends Node3D
## Deterministic obstacle rows, recycled in place. Every row leaves an open lane.

signal relic_collected(at: Vector3)
signal obstacle_hit(kind: int)

enum Kind { VAULT, SLIDE, PILLAR }
const MODELS: Array[PackedScene] = [
	preload("res://assets/environment/vault.glb"),
	preload("res://assets/environment/slide_gate.glb"),
	preload("res://assets/environment/pillar.glb")]
const RELIC: PackedScene = preload("res://assets/environment/relic.glb")
const ROW_COUNT: int = 10
const ROW_SPACING: float = 19.0
const FIRST_ROW: float = 38.0
const LANE_WIDTH: float = 2.36
var rows: Array[CourseRow] = []
var _clock: float = 0.0

class CourseRow:
	extends RefCounted
	var root: Node3D
	var models: Array[Node3D] = []
	var coins: Array[Node3D] = []
	var coin_taken: Array[bool] = []
	var hazards_done: Array[bool] = [false, false, false]
	var kinds: Array[int] = [-1, -1, -1]
	var index: int = 0
	var distance: float = 0.0


func _ready() -> void:
	for i in ROW_COUNT:
		var row := CourseRow.new()
		row.root = Node3D.new()
		add_child(row.root)
		for lane in 3:
			for kind in 3:
				var model := MODELS[kind].instantiate() as Node3D
				model.position.x = (lane - 1) * LANE_WIDTH
				row.root.add_child(model)
				row.models.append(model)
		for j in 5:
			var coin := RELIC.instantiate() as Node3D
			row.root.add_child(coin)
			row.coins.append(coin)
			row.coin_taken.append(false)
		rows.append(row)
	reset()


func reset() -> void:
	_clock = 0.0
	for i in rows.size():
		_configure(rows[i], i)
		rows[i].root.position.z = -rows[i].distance


static func pattern(index: int) -> Array[int]:
	if index == 0:
		return [-1, Kind.VAULT, -1]
	if index == 1:
		return [-1, -1, Kind.PILLAR]
	if index == 2:
		return [-1, Kind.SLIDE, -1]
	var rng := RandomNumberGenerator.new()
	rng.seed = 137 + index * 7919
	var result: Array[int] = [-1, -1, -1]
	var safe := rng.randi_range(0, 2)
	for lane in 3:
		if lane != safe and (index > 5 or lane == (safe + 1) % 3):
			result[lane] = rng.randi_range(0, 2)
	return result


static func is_hit(kind: int, player_height: float, sliding: bool) -> bool:
	match kind:
		Kind.VAULT: return player_height < 0.92
		Kind.SLIDE: return not sliding or player_height > 0.15
		Kind.PILLAR: return true
	return false


func step(distance: float, delta: float, player: RunnerCharacter, collisions: bool = true) -> void:
	_clock += delta
	for row in rows:
		var previous_z := row.root.position.z
		row.root.position.z = distance - row.distance
		if row.root.position.z > 24.0:
			_configure(row, row.index + ROW_COUNT)
			row.root.position.z = distance - row.distance
			previous_z = row.root.position.z
		for j in row.coins.size():
			var coin := row.coins[j]
			coin.rotation.y = _clock * 2.2 + j * 0.35
			coin.position.y = 1.05 + sin(_clock*2.8+j)*0.08
			if row.coin_taken[j] or not collisions:
				continue
			var coin_z := row.root.position.z + coin.position.z
			var previous_coin_z := previous_z + coin.position.z
			if previous_coin_z <= 0.7 and coin_z >= -0.7 and coin_z < 1.5:
				if absf(player.position.x-coin.position.x) < 0.82 and player.position.y < 1.65:
					row.coin_taken[j] = true
					coin.visible = false
					relic_collected.emit(coin.global_position)
		if not collisions:
			continue
		for lane in 3:
			if row.kinds[lane] < 0 or row.hazards_done[lane]:
				continue
			if previous_z <= 0.85 and row.root.position.z >= -0.85:
				if absf(player.position.x-(lane-1)*LANE_WIDTH) < 0.83:
					if is_hit(row.kinds[lane], player.position.y, player.slide_remaining > 0.0):
						row.hazards_done[lane] = true
						obstacle_hit.emit(row.kinds[lane])
			if row.root.position.z > 0.85:
				row.hazards_done[lane] = true


func _configure(row: CourseRow, index: int) -> void:
	row.index = index
	row.distance = FIRST_ROW + index * ROW_SPACING
	row.kinds = pattern(index)
	row.hazards_done = [false, false, false]
	for lane in 3:
		for kind in 3:
			row.models[lane*3+kind].visible = row.kinds[lane] == kind
	var coin_lane := row.kinds.find(-1)
	if index == 0:
		coin_lane = 1
	for j in row.coins.size():
		row.coin_taken[j] = false
		row.coins[j].visible = true
		row.coins[j].position = Vector3((coin_lane-1)*LANE_WIDTH, 1.05, 6+j*2.35)
