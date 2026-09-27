extends Node
## Opt-in debug export check, enabled only by -- --study-probe.
## Uses the real platform Kotlin service, never a fixture or a replacement grader.

var checks: Array[String] = []
var failures: Array[String] = []

func verify(condition: bool, description: String) -> void:
	checks.append(description)
	if not condition:
		failures.append(description)
	print("NATIVE STUDY ", "PASS " if condition else "FAIL ", description)

func capture(game: JadeRunGame, name: String) -> void:
	await RenderingServer.frame_post_draw
	game.get_viewport().get_texture().get_image().save_png("user://" + name + ".png")

func animate(game: JadeRunGame, seconds: float) -> void:
	for frame in int(ceil(seconds * 60)):
		game._physics_process(1.0 / 60)


func run(game: JadeRunGame) -> void:
	game.testing = true
	game.ignore_focus_pause = true
	game.set_physics_process(false)
	await get_tree().create_timer(0.5).timeout
	await game.start_run()
	animate(game, 1.3)
	verify(game.phase == JadeRunGame.Phase.QUESTION and game.study_session.size() > 0, "Kotlin generated a session")
	if game.study_session.is_empty():
		failures.append("phase=%d: %s" % [game.phase, game.hud.study_prompt.text])
	if not game.study_session.is_empty():
		for index in 7:
			verify(game.phase == JadeRunGame.Phase.QUESTION, "checkpoint %d shows question" % index)
			if index == 0:
				await capture(game, "native-study-question")
			game.hud.study_choices.get_child(0).pressed.emit()
			var deadline := Time.get_ticks_msec() + 10000
			while game.phase == JadeRunGame.Phase.LOADING and Time.get_ticks_msec() < deadline:
				await get_tree().create_timer(.05).timeout
			animate(game, 1.15)
			verify(game.phase in [JadeRunGame.Phase.FEEDBACK, JadeRunGame.Phase.REWARD], "Kotlin graded answer %d" % index)
			if game.phase not in [JadeRunGame.Phase.FEEDBACK, JadeRunGame.Phase.REWARD]:
				break
			if index == 0:
				await capture(game, "native-study-feedback")
			for choice in 3:
				if game._resolved:
					break
				game.continue_after_question()
				animate(game, 1.3)
				await game.submit_answer(choice)
				animate(game, 1.15)
			game.continue_after_question()
			verify(game.phase == JadeRunGame.Phase.REWARD, "solved question opens journey %d" % index)
			for frame in 211:
				game._physics_process(1.0 / 60)
			while game.phase == JadeRunGame.Phase.LOADING:
				await get_tree().create_timer(.05).timeout
			if game.phase == JadeRunGame.Phase.APPROACH:
				animate(game, 1.3)
		verify(game.phase == JadeRunGame.Phase.QUESTION and game.question_index == 7, "continues beyond three gates")
		await capture(game, "native-study-continuous")

	await game.go_home()
	await capture(game, "native-study-ready")
	var report := {"platform": OS.get_name(), "checks": checks, "failures": failures}
	var file := FileAccess.open("user://native-study-report.json", FileAccess.WRITE)
	file.store_string(JSON.stringify(report, "  "))
	file.close()
	print("NATIVE STUDY COMPLETE ", JSON.stringify(report))
	game.set_physics_process(true)
