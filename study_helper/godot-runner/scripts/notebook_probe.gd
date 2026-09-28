extends Node
## Opt-in integration probe, using the native host's isolated test library and real Kotlin grader.
var output_directory: String = "user://"

func capture(game: JadeRunGame, name: String) -> void:
	await RenderingServer.frame_post_draw
	game.get_viewport().get_texture().get_image().save_png(output_directory + name + ".png")

func animate(game: JadeRunGame, seconds: float) -> void:
	for frame in int(ceil(seconds * 60)):
		game._physics_process(1.0 / 60)


func run(game: JadeRunGame) -> void:
	game.testing = true
	game.ignore_focus_pause = true
	game.set_physics_process(false)
	if OS.get_name() not in ["iOS", "Android"]:
		output_directory = game.study.directory + "/"
	var stage: Dictionary = JSON.parse_string(FileAccess.get_file_as_string(game.study.directory + "/probe-stage.json"))
	var round_id := int(stage.get("round", 0))
	if round_id == 3:
		return # UIKit exercises its native close button.
	var failures: Array[String] = []
	var session: Dictionary = {}
	var deadline := Time.get_ticks_msec() + 60000
	while game.study_session.is_empty() and Time.get_ticks_msec() < deadline:
		await get_tree().create_timer(.05).timeout
	if game.study_session.is_empty():
		failures.append("No Kotlin session: " + game.hud.study_prompt.text)
	else:
		session = game.study_session.duplicate(true)
		if game.phase != JadeRunGame.Phase.APPROACH:
			failures.append("Learning must start with cinematic approach")
		animate(game, 1.3)
		for index in 7:
			if game.phase != JadeRunGame.Phase.QUESTION:
				failures.append("Question %d was not shown" % index)
				break
			if index == 0:
				await get_tree().create_timer(.5).timeout
				await capture(game, "notebook-game-%d" % round_id)
			var choices: Array = game.study_session["questions"][index]["choices"]
			var right := choices.find("기밀성") # Known answer in the host's QA question file only.
			if right < 0:
				failures.append("Unexpected QA fixture choices")
				break
			var guided := round_id == 1 and index == 0
			if guided:
				animate(game, 12.0)
				await get_tree().create_timer(.5).timeout
				await capture(game, "notebook-pressure")
				animate(game, 3.0)
				deadline = Time.get_ticks_msec() + 10000
				while game.phase == JadeRunGame.Phase.LOADING and Time.get_ticks_msec() < deadline:
					await get_tree().create_timer(.05).timeout
			else:
				await game.submit_answer(right)
			animate(game, .65)
			if index == 0:
				await get_tree().create_timer(.25).timeout
				await capture(game, "notebook-action-%d" % round_id)
			animate(game, .5)
			if game.phase != (JadeRunGame.Phase.FEEDBACK if guided else JadeRunGame.Phase.REWARD):
				failures.append("Answer %d was not graded" % index)
				break
			if guided:
				if not game.hud.study_feedback.is_visible_in_tree() or not game.hud.study_continue.is_visible_in_tree():
					failures.append("Collision explanation or retry button is hidden")
				await get_tree().create_timer(.2).timeout
				await capture(game, "notebook-wrong-explanation")
				if game.knowledge != 0:
					failures.append("Incorrect first answer awarded energy")
				game.continue_after_question()
				animate(game, 1.3)
				if game.phase != JadeRunGame.Phase.REVIEW:
					failures.append("Missing guided retry")
				await game.submit_answer(right)
				animate(game, 1.15)
			if not game._resolved:
				failures.append("Correct answer did not open gate")
				break
			if index == 0:
				await get_tree().create_timer(.2).timeout
				await capture(game, "notebook-feedback-%d" % round_id)
			game.continue_after_question()
			if game.phase != JadeRunGame.Phase.REWARD:
				failures.append("Missing automatic reward journey")
			if index == 0:
				await get_tree().create_timer(.2).timeout
				await capture(game, "notebook-reward-%d" % round_id)
			for frame in 211:
				game._physics_process(1.0 / 60)
			deadline = Time.get_ticks_msec() + 10000
			while game.phase == JadeRunGame.Phase.LOADING and Time.get_ticks_msec() < deadline:
				await get_tree().create_timer(.05).timeout
			if game.phase == JadeRunGame.Phase.APPROACH:
				animate(game, 1.3)
		if game.phase != JadeRunGame.Phase.QUESTION or game.question_index != 7:
			failures.append("Continuous session did not reach the eighth gate")
		if game.correct_count != (6 if round_id == 1 else 7) or game.knowledge != (65 if round_id == 1 else 70):
			failures.append("Cumulative first score or reward count is incorrect")
		await get_tree().create_timer(.9).timeout
		await capture(game, "notebook-continuous-%d" % round_id)
	var report := {"round": round_id, "session": session, "failures": failures, "knowledge": game.knowledge, "firstCorrect": game.correct_count, "reviewed": game.reviewed_count}
	var file := FileAccess.open(output_directory + "notebook-engine-%d.json" % round_id, FileAccess.WRITE)
	file.store_string(JSON.stringify(report))
	file.close()
	await game.go_home()
