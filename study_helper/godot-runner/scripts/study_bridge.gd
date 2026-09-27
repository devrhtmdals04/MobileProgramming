class_name StudyTransport
extends Node
signal restart_requested
## Transport only. Question generation and grading are exclusively in studyCore.
## Android: engine plugin. iOS/desktop: native host's atomic JSON mailbox.

const DIRECTORY: String = "user://study-bridge"
var _sequence: int = 0
var _busy: bool = false
var _instance_id: String = str(Time.get_ticks_usec())
var timeout_seconds: float = 8.0
var _ios_home: bool = false


func _ready() -> void:
	_ios_home = OS.get_name() == "iOS" and FileAccess.file_exists(DIRECTORY + "/host.json")
	if _ios_home:
		var timer := Timer.new()
		timer.wait_time = 0.1
		timer.timeout.connect(_check_restart)
		add_child(timer)
		timer.start()


func _check_restart() -> void:
	if FileAccess.file_exists(DIRECTORY + "/restart.json"):
		DirAccess.remove_absolute(DIRECTORY + "/restart.json")
		restart_requested.emit()


func _signal_host(name: String) -> void:
	var file := FileAccess.open(DIRECTORY + "/" + name + ".tmp", FileAccess.WRITE)
	if file == null:
		push_error("Could not notify the study host: " + name)
		return
	file.store_string("{\"version\":1}")
	file.close()
	DirAccess.rename_absolute(DIRECTORY + "/" + name + ".tmp", DIRECTORY + "/" + name + ".json")


func has_study_home() -> bool:
	# Android plugins expose JNI methods separately from Godot Object methods.
	return _ios_home or (Engine.has_singleton("StudyBridge") and Engine.get_singleton("StudyBridge").has_java_method("returnToStudy"))


func return_to_study() -> void:
	if _ios_home:
		_signal_host("return")
	elif has_study_home():
		Engine.get_singleton("StudyBridge").returnToStudy()


func game_ready() -> void:
	if _ios_home:
		_signal_host("game-ready")
	elif has_study_home():
		Engine.get_singleton("StudyBridge").gameReady()


func is_available() -> bool:
	return Engine.has_singleton("StudyBridge") or FileAccess.file_exists(DIRECTORY + "/ready.json") or OS.get_name() in ["iOS", "Android"]


func exchange(type: String, body: Dictionary = {}) -> Dictionary:
	if _busy:
		return _error("이전 요청을 처리하고 있습니다.")
	_busy = true
	_sequence += 1
	var request_id := "%s-%d" % [_instance_id, _sequence]
	var request := JSON.stringify({"version": 1, "requestId": request_id, "type": type, "body": body})
	var response_text: String = ""
	if Engine.has_singleton("StudyBridge"):
		response_text = Engine.get_singleton("StudyBridge").exchange(request)
	else:
		DirAccess.make_dir_recursive_absolute(DIRECTORY)
		var file := FileAccess.open(DIRECTORY + "/request.tmp", FileAccess.WRITE)
		if file == null:
			_busy = false
			return _error("학습 데이터를 전달하지 못했습니다.")
		file.store_string(request)
		file.close()
		var renamed := DirAccess.rename_absolute(DIRECTORY + "/request.tmp", DIRECTORY + "/request.json")
		if renamed != OK:
			_busy = false
			return _error("학습 요청을 전달하지 못했습니다.")
		var deadline := Time.get_ticks_msec() + int(timeout_seconds * 1000)
		while true:
			if FileAccess.file_exists(DIRECTORY + "/response.json"):
				var candidate := FileAccess.get_file_as_string(DIRECTORY + "/response.json")
				var parsed: Variant = _parse(candidate)
				if parsed is Dictionary and parsed.get("requestId") == request_id:
					response_text = candidate
					break
			# Rendering may stall a frame during first shader compilation. Consume an
			# already-written response before deciding that the request timed out.
			if Time.get_ticks_msec() >= deadline:
				break
			await get_tree().create_timer(0.05).timeout
	_busy = false
	if response_text.is_empty():
		return _error("학습 연결에 응답이 없습니다. 처음 화면에서 다시 시작해 주세요.")
	return decode_response(response_text, request_id)


static func decode_response(text: String, request_id: String) -> Dictionary:
	var response: Variant = _parse(text)
	if not response is Dictionary or response.get("version") != 1 or response.get("requestId") != request_id or not response.get("body") is Dictionary:
		return _error("학습 데이터 형식이 올바르지 않습니다.")
	return response


static func valid_session(body: Dictionary) -> bool:
	if not body.get("sessionId") is String or body["sessionId"].is_empty():
		return false
	var questions: Variant = body.get("questions")
	if not questions is Array or questions.size() < 3 or questions.size() > 20:
		return false
	var ids: Array[String] = []
	for question: Variant in questions:
		if not question is Dictionary or not question.get("id") is String or not question.get("prompt") is String:
			return false
		if question["id"].is_empty() or question["prompt"].is_empty() or ids.has(question["id"]):
			return false
		ids.append(question["id"])
		var choices: Variant = question.get("choices")
		if not choices is Array or choices.size() != 3:
			return false
		for choice: Variant in choices:
			if not choice is String or choice.is_empty():
				return false
	return true


static func _error(message: String) -> Dictionary:
	return {"type": "error", "body": {"message": message}}


static func valid_verdict(body: Dictionary, session_id: String, question_id: String, selected: int, answered: int) -> bool:
	return body.get("sessionId") == session_id and body.get("questionId") == question_id \
		and body.get("correct") is bool and body.get("explanation") is String \
		and _integer_in_range(body.get("correctChoice"), 0, 2) \
		and _integer_in_range(body.get("selectedChoice"), selected, selected) \
		and _integer_in_range(body.get("answeredCount"), answered, answered) \
		and _integer_in_range(body.get("correctCount"), 0, answered)


static func _integer_in_range(value: Variant, minimum: int, maximum: int) -> bool:
	return (value is float or value is int) and value == int(value) and value >= minimum and value <= maximum


static func _parse(text: String) -> Variant:
	var parser := JSON.new()
	return parser.data if parser.parse(text) == OK else null
