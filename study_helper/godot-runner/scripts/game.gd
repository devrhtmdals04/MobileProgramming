class_name JadeRunGame
extends Node3D
## Owns gameplay only; StudyTransport delegates all learning decisions to Kotlin.

enum Phase { READY, RUNNING, PAUSED, FINISHED, LOADING, QUESTION, FEEDBACK, STUDY_ERROR, REVIEW, REWARD, APPROACH, RESOLVE }
const GOAL_DISTANCE: float = 500.0
const BASE_SPEED: float = 14.0
const MAX_SPEED: float = 21.0
const REWARD_SECONDS: float = 1.2
const REWARD_SPEED: float = 17.0
const QUESTION_SECONDS: float = 15.0
const GATE_SPACING: float = 13.0 + 12.0 + REWARD_SECONDS * REWARD_SPEED
var study: StudyTransport
var study_session: Dictionary = {}
var question_index: int = 0
var correct_count: int = 0
var knowledge: int = 0
var reviewed_count: int = 0
var cleared: int = 0
var streak: int = 0
var _question_elapsed: float = 0.0
var _launch_position := Vector3(0, 1.65, -1)
var _impact_distance: float = 2.1
var _cinema_elapsed: float = 0.0
var _cinema_start_distance: float = 0.0
var _retry_approach: bool = false
var _pending_feedback: Dictionary = {}
var _dash_trail: CPUParticles3D
var _impact_ring: MeshInstance3D
var _impact_tween: Tween
var _learning: bool = false
var _resolved: bool = false
var _reward_elapsed: float = 0.0
var _gate_distance: float = 9.0
var _resume_phase: Phase = Phase.RUNNING
var _upcoming_gate: StudyGate
var _upcoming_distance: float = 0.0
var _camera_focus := Vector3(0, 1.5, -7)
var gate: StudyGate
var _backgrounded: bool = false
var phase: Phase = Phase.READY
var distance: float = 0.0
var coins: int = 0
var health: int = 3
var best: int = 0
var muted: bool = false
var testing: bool = false
var study_enabled: bool = true
var ignore_focus_pause: bool = false
var _clock: float = 0.0
var _shake: float = 0.0
var _touch_origin := Vector2.ZERO
var _touch_index: int = -1
var _gesture_used: bool = false
var _sounds: Dictionary[String, AudioStreamPlayer] = {}
var _sparks: Array[CPUParticles3D] = []
var _spark_index: int = 0
@onready var world: RunnerWorld = $World
@onready var course: RunnerCourse3D = $Course
@onready var player: RunnerCharacter = $Runner
@onready var camera: Camera3D = $Camera
@onready var hud: RunnerHUD = $HUD


func _ready() -> void:
	print("Jade Run: scene ready")
	# Apple's simulator uses a software OpenGL renderer. Keep the UI readable
	# while reducing only its 3D preview cost; physical phones retain full quality.
	if OS.get_name() == "iOS" and not OS.get_environment("SIMULATOR_MODEL_IDENTIFIER").is_empty():
		get_viewport().scaling_3d_scale = 0.25
		get_viewport().msaa_3d = Viewport.MSAA_DISABLED
		for light: DirectionalLight3D in world.find_children("*", "DirectionalLight3D", true, false):
			light.shadow_enabled = false
	study = StudyTransport.new()
	add_child(study)
	# iOS retains the engine while the native library is visible. A fresh scene
	# cancels any old awaiting requests when a new question set is selected.
	study.restart_requested.connect(func() -> void: get_tree().reload_current_scene.call_deferred())
	hud.set_study_available(study_enabled and study.is_available())
	hud.answer_selected.connect(submit_answer)
	hud.study_continued.connect(continue_after_question)
	_configure_input()
	_configure_effects()
	gate = StudyGate.new()
	add_child(gate)
	gate.hide()
	_upcoming_gate = StudyGate.new()
	add_child(_upcoming_gate)
	_upcoming_gate.hide()
	hud.start_requested.connect(start_run)
	hud.pause_requested.connect(pause_run)
	hud.resume_requested.connect(resume_run)
	hud.home_requested.connect(go_home)
	hud.action_requested.connect(perform_action)
	hud.sound_toggled.connect(toggle_sound)
	course.relic_collected.connect(_on_relic)
	course.obstacle_hit.connect(_on_hit)
	player.landed.connect(func() -> void: _play_sound("land"))
	hud.update_stats(distance, coins, health, GOAL_DISTANCE)
	camera.look_at(Vector3(0, 1.5, -7.0))
	var config := ConfigFile.new()
	if not testing and config.load("user://records.cfg") == OK:
		best = int(config.get_value("run", "best", 0))
		muted = bool(config.get_value("audio", "muted", false))
	_apply_sound()
	if OS.has_feature("debug"):
		print("Jade Run: study transport ready; user args=", OS.get_cmdline_user_args())
	if study.has_study_home():
		hud.modal_home.text = "학습 홈으로"
		hud.study_home.text = "학습 홈으로"
		get_tree().auto_accept_quit = false
		# Keep the native loading screen until the first GPU frame is drawn;
		# shader compilation can otherwise expose a blank viewport on first launch.
		await RenderingServer.frame_post_draw
		study.game_ready()
		start_run.call_deferred()
	elif OS.get_cmdline_user_args().has("--autostart"):
		start_run()
	if OS.has_feature("debug") and OS.get_cmdline_user_args().has("--study-probe"):
		var probe := load("res://scripts/native_study_probe.gd").new() as Node
		add_child(probe)
		probe.call_deferred("run", self)
	if OS.has_feature("debug") and OS.get_cmdline_user_args().has("--notebook-probe"):
		var probe := load("res://scripts/notebook_probe.gd").new() as Node
		add_child(probe)
		probe.call_deferred("run", self)


func _physics_process(delta: float) -> void:
	if _learning:
		if phase == Phase.PAUSED:
			return
		_upcoming_gate.position.z = distance - _upcoming_distance
		_dash_trail.position = player.position + Vector3(0, .6, .5)
		var reading := phase in [Phase.QUESTION, Phase.REVIEW]
		_dash_trail.speed_scale = .18 if reading else 1.0
		_dash_trail.emitting = reading or (phase == Phase.APPROACH and _cinema_elapsed > .45) or (phase == Phase.RESOLVE and _resolved and _cinema_elapsed < .7)
		if reading:
			_question_elapsed = minf(_question_elapsed + delta, QUESTION_SECONDS)
			var pressure := minf(_question_elapsed / QUESTION_SECONDS, 1.0)
			player.position = Vector3(sin(_question_elapsed * 1.6) * .045, 1.65 + sin(_question_elapsed * 2.4) * .06, -1.0 - .35 * pressure)
			player.visual.rotation = Vector3(-1.2 + sin(_question_elapsed * 1.8) * .035, 0, sin(_question_elapsed * 1.6) * .035)
			hud.update_question_time(maxf(0, QUESTION_SECONDS - _question_elapsed), QUESTION_SECONDS)
			if _question_elapsed >= QUESTION_SECONDS:
				submit_answer(-1, true)
		elif phase == Phase.APPROACH:
			_step_approach(delta)
		elif phase == Phase.RESOLVE:
			_step_resolution(delta)
		elif phase == Phase.REWARD:
			var travel_time := minf(delta, REWARD_SECONDS - _reward_elapsed)
			_reward_elapsed += travel_time
			player.step(delta, REWARD_SPEED)
			distance += REWARD_SPEED * travel_time
			world.set_distance(distance)
			gate.position.z = distance - _gate_distance
			if _reward_elapsed >= REWARD_SECONDS:
				question_index += 1
				if question_index >= study_session["questions"].size():
					_load_next_cycle()
				else:
					_begin_approach()
		return
	if phase != Phase.RUNNING:
		return
	var speed := minf(MAX_SPEED, BASE_SPEED + distance / 85.0)
	player.step(delta, speed)
	distance = minf(GOAL_DISTANCE, distance + speed * delta)
	world.set_distance(distance)
	course.step(distance, delta, player)
	hud.update_stats(distance, coins, health, GOAL_DISTANCE)
	if phase == Phase.RUNNING and distance >= GOAL_DISTANCE:
		finish_run(true)


func _begin_approach(retry: bool = false) -> void:
	_retry_approach = retry
	_resolved = false
	_cinema_elapsed = 0.0
	_cinema_start_distance = distance
	if question_index == 0 or retry:
		_gate_distance = distance + 18.0
		_upcoming_distance = _gate_distance + GATE_SPACING
		gate.prepare(question_index + 1)
		_upcoming_gate.prepare(question_index + 2)
		if retry:
			player.reset()
	else:
		var passed_gate := gate
		gate = _upcoming_gate
		_gate_distance = _upcoming_distance
		_upcoming_gate = passed_gate
		_upcoming_distance += GATE_SPACING
		_upcoming_gate.prepare(question_index + 2)
	gate.position.z = distance - _gate_distance
	_upcoming_gate.position.z = distance - _upcoming_distance
	_dash_trail.speed_scale = 1.0
	phase = Phase.APPROACH
	hud.show_cinematic("다시 일어나, 관문을 향해!" if retry else "관문 접근 · 점프 준비!")
	hud.update_learning(cleared, knowledge)
	if _backgrounded:
		pause_run()


func _step_approach(delta: float) -> void:
	_cinema_elapsed += delta
	var t := minf(_cinema_elapsed / 1.25, 1.0)
	distance = _cinema_start_distance + 13.0 * (1.0 - pow(1.0 - t, 2.2))
	world.set_distance(distance)
	gate.position.z = distance - _gate_distance
	if t < .36:
		player.step(delta, 18.0)
	else:
		var leap := (t - .36) / .64
		player._play("Jump_Idle", .08)
		player.animation.speed_scale = lerpf(1.2, .08, leap)
		player.position = Vector3(0, 1.65 * sin(leap * PI * .5), -leap)
		player.visual.rotation.x = -1.2 * leap
		if t - delta / 1.25 < .36:
			_play_sound("jump")
	if t >= 1.0:
		_show_question(_retry_approach)


func _step_resolution(delta: float) -> void:
	_cinema_elapsed += delta
	var t := minf(_cinema_elapsed / 1.1, 1.0)
	player.animation.speed_scale = 1.0 if _resolved else .15
	if _resolved:
		distance = _cinema_start_distance + 12.0 * t
		player.position = _launch_position.lerp(Vector3.ZERO, t) + Vector3(0, sin(t * PI) * .4, 0)
		player.visual.rotation.x = -1.2 * (1.0 - smoothstep(.45, 1.0, t))
		var contact := _impact_distance / (12.0 + _launch_position.z)
		if t >= contact and t - delta / 1.1 < contact:
			gate.shatter()
			_burst_impact(true)
			_play_sound("hit")
	else:
		var hit := minf(t / .22, 1.0)
		distance = _cinema_start_distance + _impact_distance * hit
		var fall := clampf((t - .22) / .78, 0.0, 1.0)
		player.position = _launch_position.lerp(Vector3(0, .22, 1.5), fall)
		player.visual.rotation = Vector3(lerpf(-1.2, 1.45, fall), 0, .18 * fall)
		if t >= .22 and t - delta / 1.1 < .22:
			hud.pulse_hit()
			_burst_impact(false)
			_play_sound("hit")
			if not testing and OS.get_name() in ["iOS", "Android"]:
				Input.vibrate_handheld(90)
	world.set_distance(distance)
	gate.position.z = distance - _gate_distance
	if t >= 1.0:
		if _resolved:
			player.land_from_dive()
			_play_sound("land")
		phase = Phase.FEEDBACK
		if _resolved:
			continue_after_question()
		else:
			_freeze_study()
			hud.show_study_feedback(_pending_feedback["verdict"], _pending_feedback["retry"], 0, streak)


func _load_next_cycle() -> void:
	phase = Phase.LOADING
	_freeze_study()
	hud.show_study_wait("다음 복습 문제를 준비하고 있어요…")
	var response: Dictionary = await study.exchange("next", {
		"sessionId": study_session["sessionId"],
		"afterQuestionId": study_session["questions"][-1]["id"]})
	var body: Dictionary = response.get("body", {})
	if response.get("type") != "questions" or not StudyTransport.valid_session(body) or body.get("sessionId") != study_session["sessionId"]:
		_study_error(response)
		return
	for question: Dictionary in body["questions"]:
		for previous: Dictionary in study_session["questions"]:
			if previous["id"] == question["id"]:
				_study_error({})
				return
	study_session["questions"].append_array(body["questions"])
	_begin_approach()


func _process(delta: float) -> void:
	if _learning and phase in [Phase.APPROACH, Phase.QUESTION, Phase.REVIEW, Phase.RESOLVE, Phase.FEEDBACK, Phase.REWARD]:
		_clock += delta
		var close_up := phase in [Phase.QUESTION, Phase.REVIEW, Phase.RESOLVE, Phase.FEEDBACK] or (phase == Phase.APPROACH and _cinema_elapsed > .45)
		var desired := Vector3(1.9, 3.6, 8.2) if close_up else Vector3(0, 4.6, 8.8)
		var reading := phase in [Phase.QUESTION, Phase.REVIEW]
		var focused := reading or phase == Phase.RESOLVE
		var pressure := minf(_question_elapsed / QUESTION_SECONDS, 1.0) if focused else 0.0
		if focused:
			desired += Vector3(.45 * pressure + sin(_question_elapsed * .7) * .035, -.12 * pressure, -.7 * pressure)
		var impact := phase == Phase.RESOLVE and not _resolved and _cinema_elapsed > .2 and _cinema_elapsed < .65
		if impact:
			desired += Vector3(sin(_clock * 91), cos(_clock * 77), 0) * .16
		camera.position = camera.position.lerp(desired, 1.0 - exp(-8.0 * delta))
		var focus_y := -1.0 if phase == Phase.FEEDBACK and not _resolved else .55
		var focus := Vector3(0, focus_y, player.position.z) if close_up else Vector3(0, 1.5, -7)
		_camera_focus = _camera_focus.lerp(focus, 1.0 - exp(-5.0 * delta))
		camera.look_at(_camera_focus)
		camera.fov = lerpf(camera.fov, 58.0 - 4.0 * pressure if close_up else 68.0, 1.0 - exp(-7.0 * delta))
		return
	if phase in [Phase.PAUSED, Phase.LOADING, Phase.QUESTION, Phase.REVIEW, Phase.FEEDBACK, Phase.STUDY_ERROR]:
		return
	_clock += delta
	_shake = move_toward(_shake, 0.0, delta * 1.7)
	var running := phase in [Phase.RUNNING, Phase.REWARD]
	var desired_x := player.position.x * 0.45 if running else sin(_clock * .22) * .20
	var desired := Vector3(desired_x, 4.6 + player.position.y * .12, 8.8)
	if running:
		desired.y += sin(_clock*16.0)*.018
		if _shake > 0:
			desired += Vector3(sin(_clock*83), cos(_clock*71), 0)*_shake*.16
	camera.position = camera.position.lerp(desired, 1.0-exp(-5.0*delta))
	camera.look_at(Vector3(player.position.x*.35, 1.5, -7.0))
	camera.fov = lerpf(camera.fov, 64.0 + minf(distance / GOAL_DISTANCE, 1.0)*5.0 if running else 62.0, delta*2.0)


func start_run() -> void:
	if phase == Phase.LOADING:
		return
	study_session = {}
	question_index = 0
	correct_count = 0
	knowledge = 0
	reviewed_count = 0
	cleared = 0
	streak = 0
	_resolved = false
	if study_enabled and study.is_available():
		phase = Phase.LOADING
		_freeze_study()
		hud.show_study_wait("복습할 문제를 준비하고 있어요…")
		var response: Dictionary = await study.exchange("begin", {"continuous": true})
		if response.get("type") != "session" or not StudyTransport.valid_session(response.get("body", {})):
			_study_error(response)
			return
		study_session = response["body"]
	distance = 0.0
	coins = 0
	health = 3
	_shake = 0.0
	_reset_gesture()
	player.reset()
	world.set_distance(0.0)
	course.reset()
	_learning = not study_session.is_empty()
	course.visible = not _learning
	gate.visible = _learning
	_upcoming_gate.visible = _learning
	for decoration in world.gates:
		decoration.visible = not _learning
	hud.learning_mode = _learning
	phase = Phase.RUNNING
	hud.show_running()
	hud.update_stats(distance, coins, health, GOAL_DISTANCE)
	for effect in _sparks:
		effect.speed_scale = 1.0
		effect.emitting = false
		effect.visible = false
	_play_sound("start")
	if _learning:
		_begin_approach()
		return
	if _backgrounded:
		pause_run()


func pause_run() -> void:
	if phase not in [Phase.RUNNING, Phase.REWARD, Phase.APPROACH, Phase.RESOLVE, Phase.QUESTION, Phase.REVIEW]:
		return
	_resume_phase = phase
	phase = Phase.PAUSED
	player.set_frozen(true)
	gate.set_paused(true)
	_dash_trail.speed_scale = 0.0
	if _impact_tween and _impact_tween.is_valid():
		_impact_tween.pause()
	for effect in _sparks:
		effect.speed_scale = 0.0
	_reset_gesture()
	hud.show_pause()


func resume_run() -> void:
	if phase != Phase.PAUSED:
		return
	phase = _resume_phase
	gate.set_paused(false)
	player.set_frozen(false)
	_dash_trail.speed_scale = 1.0
	if _impact_tween and _impact_tween.is_valid():
		_impact_tween.play()
	for effect in _sparks:
		effect.speed_scale = 1.0
	_reset_gesture()
	if _learning and phase in [Phase.QUESTION, Phase.REVIEW]:
		player.animation.speed_scale = .12
		hud.show_question(study_session["questions"][question_index], question_index, 3)
		hud.update_question_time(maxf(0, QUESTION_SECONDS - _question_elapsed), QUESTION_SECONDS)
	elif _learning:
		hud.show_cinematic("문을 향해 도약!" if phase == Phase.APPROACH else "관문 돌파!" if _resolved else "충돌!")
	else:
		hud.show_running()


func go_home() -> void:
	if phase == Phase.LOADING:
		return
	if not study_session.is_empty():
		phase = Phase.LOADING
		_freeze_study()
		hud.show_study_wait("학습을 마무리하고 있어요…")
		await study.exchange("end", {"sessionId": study_session["sessionId"]})
	if study.has_study_home():
		study.return_to_study()
		return
	study_session = {}
	question_index = 0
	correct_count = 0
	_learning = false
	hud.learning_mode = false
	gate.hide()
	_upcoming_gate.hide()
	for decoration in world.gates:
		decoration.show()
	course.show()
	phase = Phase.READY
	distance = 0
	coins = 0
	health = 3
	player.reset()
	world.set_distance(0)
	course.reset()
	for effect in _sparks:
		effect.speed_scale = 1.0
		effect.emitting = false
		effect.visible = false
	_reset_gesture()
	hud.show_ready()
	hud.update_stats(distance, coins, health, GOAL_DISTANCE)


func finish_run(won: bool) -> void:
	if phase != Phase.RUNNING:
		return
	phase = Phase.FINISHED
	if not _learning:
		best = maxi(best, int(distance))
	_save_preferences()
	player.visual.visible = true
	if won:
		player.celebrate()
		_play_sound("start")
	else:
		player.set_frozen(true)
	var learning := ""
	if not study_session.is_empty():
		phase = Phase.LOADING
		hud.show_study_wait("학습 결과를 확인하고 있어요…")
		var response: Dictionary = await study.exchange("end", {"sessionId": study_session["sessionId"]})
		if response.get("type") == "summary":
			var summary: Dictionary = response["body"]
			learning = "첫 답 정답  %d / %d\n다시 확인  %d개 · 제출 %d개" % [summary["correctCount"], summary["questionCount"], summary.get("reviewedCount", 0), summary["answeredCount"]]
		else:
			learning = "\n학습 결과를 불러오지 못했어요."
		study_session = {}
	phase = Phase.FINISHED
	if _learning:
		player.set_frozen(true)
		hud.show_learning_result(learning, knowledge)
	else:
		hud.show_result(won, distance, coins, best, learning)


func perform_action(action: String) -> void:
	if _learning or phase != Phase.RUNNING:
		return
	match action:
		"left": player.move_lane(-1)
		"right": player.move_lane(1)
		"jump":
			if player.jump():
				_play_sound("jump")
		"slide":
			if player.slide():
				_play_sound("slide")


func toggle_sound() -> void:
	muted = not muted
	_apply_sound()
	_save_preferences()


func _unhandled_input(event: InputEvent) -> void:
	if event.is_action_pressed("pause_run"):
		if phase == Phase.PAUSED:
			resume_run()
		else:
			pause_run()
		get_viewport().set_input_as_handled()
		return
	if event.is_action_pressed("start_run") and phase in [Phase.READY, Phase.FINISHED]:
		start_run()
		get_viewport().set_input_as_handled()
		return
	if phase != Phase.RUNNING:
		return
	for action in ["left", "right", "jump", "slide"]:
		if event.is_action_pressed(action):
			perform_action(action)
			get_viewport().set_input_as_handled()
			return
	if event is InputEventScreenTouch:
		if event.pressed and _touch_index == -1:
			_touch_index = event.index
			_touch_origin = event.position
			_gesture_used = false
		elif not event.pressed and event.index == _touch_index:
			_reset_gesture()
	elif event is InputEventScreenDrag and event.index == _touch_index and not _gesture_used:
		var difference: Vector2 = event.position - _touch_origin
		if difference.length() >= 32:
			_gesture_used = true
			if absf(difference.x) > absf(difference.y):
				perform_action("right" if difference.x > 0 else "left")
			else:
				perform_action("slide" if difference.y > 0 else "jump")


func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_GO_BACK_REQUEST and is_node_ready() and study.has_study_home():
		study.return_to_study()
		return
	if what in [NOTIFICATION_APPLICATION_RESUMED, NOTIFICATION_APPLICATION_FOCUS_IN]:
		_backgrounded = false
	if what in [NOTIFICATION_APPLICATION_PAUSED, NOTIFICATION_APPLICATION_FOCUS_OUT]:
		_backgrounded = not ignore_focus_pause
		if is_node_ready() and not ignore_focus_pause:
			pause_run()


func _on_relic(at: Vector3) -> void:
	if _learning or phase != Phase.RUNNING:
		return
	coins += 1
	hud.pulse_pickup(coins)
	_sounds["coin"].pitch_scale = 1.0 + (coins % 5) * .07
	_play_sound("coin")
	var effect := _sparks[_spark_index]
	_spark_index = (_spark_index + 1) % _sparks.size()
	effect.position = at
	effect.visible = true
	effect.restart()
	effect.emitting = true


func _on_hit(_kind: int) -> void:
	if _learning or phase != Phase.RUNNING or player.invulnerability > 0.0:
		return
	health -= 1
	player.invulnerability = 1.6
	_shake = 1.0
	hud.pulse_hit()
	_play_sound("hit")
	if health <= 0:
		finish_run(false)


func _reset_gesture() -> void:
	_touch_index = -1
	_gesture_used = false


func _apply_sound() -> void:
	AudioServer.set_bus_mute(0, muted)
	hud.set_sound(muted)


func _save_preferences() -> void:
	if testing:
		return
	var config := ConfigFile.new()
	config.set_value("run", "best", best)
	config.set_value("audio", "muted", muted)
	config.save("user://records.cfg")


func _play_sound(key: String) -> void:
	if DisplayServer.get_name() == "headless":
		return
	_sounds[key].play()


func _configure_input() -> void:
	var bindings := {"left": [KEY_LEFT, KEY_A], "right": [KEY_RIGHT, KEY_D],
		"jump": [KEY_UP, KEY_W, KEY_SPACE], "slide": [KEY_DOWN, KEY_S],
		"pause_run": [KEY_ESCAPE, KEY_P], "start_run": [KEY_ENTER, KEY_R]}
	for action: String in bindings:
		if not InputMap.has_action(action):
			InputMap.add_action(action)
		for key: int in bindings[action]:
			var event := InputEventKey.new()
			event.physical_keycode = key
			InputMap.action_add_event(action, event)


func _configure_effects() -> void:
	for key in ["coin", "jump", "slide", "hit", "land", "start"]:
		var audio := AudioStreamPlayer.new()
		audio.stream = load("res://assets/audio/%s.wav" % key) as AudioStream
		audio.volume_db = -14.0 if key != "land" else -23.0
		add_child(audio)
		_sounds[key] = audio
	for i in 8:
		var particles := CPUParticles3D.new()
		particles.emitting = false
		particles.one_shot = true
		particles.explosiveness = 1.0
		particles.amount = 10
		particles.lifetime = .4
		particles.direction = Vector3.UP
		particles.spread = 80.0
		particles.initial_velocity_min = 1.3
		particles.initial_velocity_max = 2.8
		particles.gravity = Vector3(0, -5, 1)
		particles.scale_amount_min = .045
		particles.scale_amount_max = .10
		var mesh := SphereMesh.new()
		mesh.radial_segments = 6
		mesh.rings = 3
		var material := StandardMaterial3D.new()
		material.albedo_color = Color.WHITE
		material.vertex_color_use_as_albedo = true
		material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		mesh.material = material
		particles.mesh = mesh
		particles.color = Color("ffda7e")
		add_child(particles)
		_sparks.append(particles)

	_dash_trail = CPUParticles3D.new()
	_dash_trail.emitting = false
	_dash_trail.local_coords = false
	_dash_trail.amount = 24
	_dash_trail.lifetime = .22
	_dash_trail.direction = Vector3(0, .1, 1)
	_dash_trail.spread = 12.0
	_dash_trail.initial_velocity_min = 8.0
	_dash_trail.initial_velocity_max = 14.0
	_dash_trail.gravity = Vector3.ZERO
	_dash_trail.emission_shape = CPUParticles3D.EMISSION_SHAPE_SPHERE
	_dash_trail.emission_sphere_radius = .6
	var streak_mesh := BoxMesh.new()
	streak_mesh.size = Vector3(.025, .025, .8)
	var trail_material := StandardMaterial3D.new()
	trail_material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	trail_material.albedo_color = Color("ffe3a1")
	streak_mesh.material = trail_material
	_dash_trail.mesh = streak_mesh
	add_child(_dash_trail)
	_impact_ring = MeshInstance3D.new()
	var torus := TorusMesh.new()
	torus.inner_radius = .88
	torus.outer_radius = 1.0
	_impact_ring.mesh = torus
	var ring_material := StandardMaterial3D.new()
	ring_material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	ring_material.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_impact_ring.material_override = ring_material
	_impact_ring.rotation.x = PI / 2
	_impact_ring.hide()
	add_child(_impact_ring)


func _burst_impact(correct: bool) -> void:
	var tint := Color("ffe297") if correct else Color("ff8a55")
	var effect := _sparks[_spark_index]
	_spark_index = (_spark_index + 1) % _sparks.size()
	effect.position = player.position + Vector3(0, .7, -1.4)
	effect.color = tint
	effect.speed_scale = 1.0
	effect.visible = true
	effect.restart()
	effect.emitting = true
	if _impact_tween and _impact_tween.is_valid():
		_impact_tween.kill()
	_impact_ring.position = effect.position
	_impact_ring.scale = Vector3.ONE * .15
	_impact_ring.show()
	var material := _impact_ring.material_override as StandardMaterial3D
	material.albedo_color = tint
	_impact_tween = create_tween().set_parallel()
	_impact_tween.tween_property(_impact_ring, "scale", Vector3.ONE * 2.2, .4).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)
	_impact_tween.tween_property(material, "albedo_color:a", 0.0, .4)


func _freeze_study() -> void:
	player.set_frozen(true)
	_dash_trail.emitting = false
	_reset_gesture()
	for effect in _sparks:
		effect.speed_scale = 0.0


func _show_question(retry: bool = false) -> void:
	phase = Phase.REVIEW if retry else Phase.QUESTION
	_question_elapsed = 0.0
	_freeze_study()
	player.animation.speed_scale = .12
	hud.update_learning(cleared, knowledge)
	hud.show_question(study_session["questions"][question_index], question_index, 3)
	hud.update_question_time(QUESTION_SECONDS, QUESTION_SECONDS)
	if retry:
		hud.study_number.text = "다시 생각하기 · 첫 답 기록은 유지돼요"


func submit_answer(selected: int, timed_out: bool = false) -> void:
	if phase not in [Phase.QUESTION, Phase.REVIEW] or (not timed_out and (selected < 0 or selected > 2)):
		return
	var retry := phase == Phase.REVIEW
	phase = Phase.LOADING
	hud.question_clock.hide()
	for button in hud.study_choices.get_children():
		button.disabled = true
	hud.study_home.disabled = true
	var question: Dictionary = study_session["questions"][question_index]
	var response: Dictionary = await study.exchange("timeout" if timed_out else ("review" if retry else "answer"), {
		"sessionId": study_session["sessionId"], "questionId": question["id"], "selectedChoice": selected, "review": retry})
	var verdict: Dictionary = response.get("body", {})
	if response.get("type") != ("reviewed" if retry else "graded") or not StudyTransport.valid_verdict(verdict, study_session["sessionId"], question["id"], selected, question_index + 1):
		_study_error(response)
		return
	correct_count = int(verdict["correctCount"])
	_resolved = bool(verdict["correct"])
	var reward := 0
	if _resolved:
		reward = 5 if retry else 10
		knowledge += reward
		cleared += 1
		streak = 0 if retry else streak + 1
		reviewed_count = int(verdict.get("reviewedCount", reviewed_count))
		_sounds["coin"].pitch_scale = minf(1.2 + streak * .1, 1.8)
		_play_sound("coin")
		if not testing and OS.get_name() in ["iOS", "Android"]:
			Input.vibrate_handheld(50)
	else:
		streak = 0
	_pending_feedback = {"verdict": verdict, "retry": retry, "reward": reward}
	_launch_position = player.position
	_impact_distance = maxf(.2, _gate_distance - distance + _launch_position.z - 1.9)
	_cinema_elapsed = 0.0
	_cinema_start_distance = distance
	phase = Phase.RESOLVE
	hud.show_cinematic("정답! 관문 돌파!" if _resolved else "앗! 닫힌 문에 충돌!")
	hud.update_learning(cleared, knowledge)
	if _resolved:
		hud.pulse_correct(reward)
	if _backgrounded:
		pause_run()


func continue_after_question() -> void:
	if phase != Phase.FEEDBACK:
		return
	if not _resolved:
		_begin_approach(true)
		return
	_reward_elapsed = 0.0
	_resume_phase = Phase.REWARD
	phase = Phase.REWARD
	player.set_frozen(false)
	_reset_gesture()
	hud.show_reward(cleared, knowledge)
	if _backgrounded:
		pause_run()


func _study_error(response: Dictionary) -> void:
	push_warning("Study transport: ", response.get("body", {}).get("message", "invalid response"))
	phase = Phase.STUDY_ERROR
	_freeze_study()
	hud.show_study_error(str(response.get("body", {}).get("message", "학습 데이터를 읽지 못했습니다. 다시 시작해 주세요.")))
