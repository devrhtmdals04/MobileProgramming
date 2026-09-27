extends SceneTree
## Captures the real viewport, never a mockup. Run without --headless.

var game: JadeRunGame


func _initialize() -> void:
	call_deferred("capture")


func capture() -> void:
	game = load("res://scenes/main.tscn").instantiate() as JadeRunGame
	game.testing = true
	game.ignore_focus_pause = true
	root.add_child(game)
	game.set_process(false)
	game.camera.position = Vector3(0, 4.6, 8.8)
	game.camera.look_at(Vector3(0, 1.5, -7))
	for i in 25:
		await process_frame
	await _save("ready")
	game.start_run()
	game.set_physics_process(false)
	game.player.invulnerability = 999
	for i in 80:
		game._physics_process(1.0/60.0)
		await physics_frame
	await _save("running")
	game.perform_action("jump")
	for i in 20:
		game._physics_process(1.0/60.0)
		await physics_frame
	await _save("jump")
	for i in 90:
		game._physics_process(1.0/60.0)
		await physics_frame
	game.perform_action("slide")
	for i in 16:
		game._physics_process(1.0/60.0)
		await physics_frame
	await _save("slide")
	game.pause_run()
	await _save("paused")
	print("CAPTURE_OK; FPS=", Engine.get_frames_per_second(), "; draw_calls=", Performance.get_monitor(Performance.RENDER_TOTAL_DRAW_CALLS_IN_FRAME))
	quit(0)


func _save(filename: String) -> void:
	await RenderingServer.frame_post_draw
	var viewport_image := root.get_texture().get_image()
	var error := viewport_image.save_png("res://artifacts/%s.png" % filename)
	assert(error == OK)
	print("CAPTURED ", filename)
