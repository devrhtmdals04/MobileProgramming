extends SceneTree
## Integration checks against the live Godot scene and physics body.

var game: JadeRunGame
var checks: int = 0
var failures: int = 0


func _initialize() -> void:
	call_deferred("run")


func check(value: bool, message: String) -> void:
	checks += 1
	if not value:
		failures += 1
		push_error(message)
	else:
		print("PASS ", message)


func advance(frames: int) -> void:
	for i in frames:
		game._physics_process(1.0 / 60.0)


func run() -> void:
	game = load("res://scenes/main.tscn").instantiate() as JadeRunGame
	game.testing = true
	game.study_enabled = false
	root.add_child(game)
	game.set_physics_process(false)
	game.set_process(false)
	await physics_frame
	await physics_frame
	check(game.phase == JadeRunGame.Phase.READY, "starts ready")
	check(game.player.animation.has_animation("Running_A"), "skinned running animation available")
	check(game.player.animation.has_animation("Jump_Idle"), "jump animation available")
	var all_safe := true
	for i in 1000:
		all_safe = all_safe and RunnerCourse3D.pattern(i).has(-1)
	check(all_safe, "every generated row has a clear lane (1000 rows)")
	check(RunnerCourse3D.pattern(81) == RunnerCourse3D.pattern(81), "course is reproducible")
	check(RunnerCourse3D.is_hit(RunnerCourse3D.Kind.VAULT, 0, false), "low obstacle hits grounded runner")
	check(not RunnerCourse3D.is_hit(RunnerCourse3D.Kind.VAULT, 1.1, false), "jump clears low obstacle")
	check(not RunnerCourse3D.is_hit(RunnerCourse3D.Kind.SLIDE, 0, true), "slide clears overhead obstacle")
	check(RunnerCourse3D.is_hit(RunnerCourse3D.Kind.PILLAR, 2, true), "pillar cannot be jumped or slid through")
	game.start_run()
	game.perform_action("left")
	game.perform_action("left")
	check(game.player.lane == 0, "lane selection clamps at left edge")
	advance(30)
	check(absf(game.player.position.x + 2.36) < .05, "physics body reaches selected lane")
	game.perform_action("jump")
	check(not game.player.jump(), "double jump rejected")
	advance(20)
	check(game.player.position.y > 1.0, "physics jump reaches obstacle clearance")
	check(not game.player.slide(), "midair slide rejected")
	advance(50)
	check(game.player.position.y < .10, "jump lands on bridge collider")
	game.perform_action("slide")
	check(game.player.slide_remaining > 0, "slide starts on ground")
	check(not game.player.jump(), "jump rejected during slide")
	advance(60)
	check(game.player.slide_remaining == 0, "slide expires")
	game.pause_run()
	var held_distance := game.distance
	var held_position := game.player.position
	advance(120)
	game.perform_action("right")
	check(game.distance == held_distance and game.player.position == held_position, "pause freezes distance and player")
	check(game.player.animation.speed_scale == 0, "pause freezes skeletal animation")
	game.resume_run()
	advance(2)
	check(game.distance > held_distance, "resume advances again")
	game.start_run()
	var touch := InputEventScreenTouch.new()
	touch.index = 0
	touch.position = Vector2(270, 430)
	touch.pressed = true
	game._unhandled_input(touch)
	var drag := InputEventScreenDrag.new()
	drag.index = 0
	drag.position = Vector2(200, 430)
	game._unhandled_input(drag)
	game._unhandled_input(drag)
	check(game.player.lane == 0, "one lane change per swipe")
	touch.pressed = false
	game._unhandled_input(touch)
	game.start_run()
	advance(190)
	check(game.coins == 5, "collects first five relics exactly once")
	check(game.health == 2, "first obstacle subtracts exactly one health")
	game._on_hit(0)
	check(game.health == 2, "invulnerability prevents repeat damage")
	game.pause_run()
	game.go_home()
	check(game.phase == JadeRunGame.Phase.READY and game.health == 3 and game.distance == 0, "home clears paused run")
	check(game._sparks.all(func(effect: CPUParticles3D) -> bool: return not effect.visible), "home clears frozen collection particles")
	game.start_run()
	game.player.invulnerability = 999
	advance(2100)
	check(game.phase == JadeRunGame.Phase.FINISHED and game.distance == 500, "full run ends at 500 metres")
	check(game.course.rows.size() == 10, "course pool remains bounded after completion")
	game.start_run()
	check(game.coins == 0 and game.health == 3 and game.distance == 0, "retry resets all session state")
	for i in 3:
		game.player.invulnerability = 0
		game._on_hit(2)
	check(game.phase == JadeRunGame.Phase.FINISHED and game.health == 0, "three hits finish run")
	game.start_run()
	game._notification(Node.NOTIFICATION_APPLICATION_PAUSED)
	check(game.phase == JadeRunGame.Phase.PAUSED, "backgrounding pauses game")
	game.go_home()
	game.queue_free()
	await process_frame
	print("RESULT %d checks, %d failures" % [checks, failures])
	quit(1 if failures else 0)
