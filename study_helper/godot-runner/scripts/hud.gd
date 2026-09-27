class_name RunnerHUD
extends CanvasLayer
## Native Godot controls over the 3D viewport, sized for a portrait touch screen.

signal start_requested
signal pause_requested
signal resume_requested
signal home_requested
signal action_requested(action: String)
signal sound_toggled
signal answer_selected(index: int)
signal study_continued

const CREAM := Color("f5f1d8")
const GOLD := Color("f7cd75")
const INK := Color("123e3c")
const FONT: FontFile = preload("res://assets/fonts/NotoSansKR.ttf")
var learning_mode: bool = false
var units_label: Label
var brand_label: Label
var question_clock: VBoxContainer
var question_time_label: Label
var question_time_bar: ProgressBar
var _time_fill: StyleBoxFlat
var study_scroll: ScrollContainer
var _reward_tween: Tween
var root: Control
var distance_label: Label
var coin_label: Label
var hearts_label: Label
var progress: ProgressBar
var title_group: VBoxContainer
var ready_card: VBoxContainer
var controls: HBoxContainer
var hint: Label
var veil: ColorRect
var modal: PanelContainer
var modal_title: Label
var modal_description: Label
var modal_stats: Label
var modal_action: Button
var modal_home: Button
var pause_button: Button
var sound_button: Button
var flash: ColorRect
var pickup_label: Label
var _pickup_tween: Tween
var _flash_tween: Tween
var _modal_is_pause: bool = false
var study_panel: PanelContainer
var study_number: Label
var study_prompt: Label
var study_feedback: Label
var study_choices: VBoxContainer
var study_continue: Button
var study_home: Button
var study_caption: Label
var _question_choices: Array = []


func _ready() -> void:
	print("Jade Run: building interface")
	root = Control.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var theme := Theme.new()
	var font := FontVariation.new()
	font.base_font = FONT
	font.variation_opentype = {TextServerManager.get_primary_interface().name_to_tag("wght"): 600.0}
	theme.default_font = font
	theme.set_font("font", "Label", font)
	theme.set_font("font", "Button", font)
	theme.default_font_size = 17
	root.theme = theme
	add_child(root)
	_gradient(true)
	_gradient(false)
	_build_header()
	_build_title()
	_build_controls()
	_build_modal()
	_build_study_panel()
	flash = ColorRect.new()
	flash.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	flash.color = Color(1, 0.32, 0.15, 0)
	flash.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(flash)
	pickup_label = _label("+1", 28, GOLD)
	pickup_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	pickup_label.set_anchors_and_offsets_preset(Control.PRESET_CENTER)
	pickup_label.position += Vector2(-35, 65)
	pickup_label.size = Vector2(70, 50)
	pickup_label.modulate.a = 0.0
	root.add_child(pickup_label)
	show_ready()


func update_stats(distance: float, coins: int, health: int, target: float) -> void:
	distance_label.text = "%03d" % int(distance)
	coin_label.text = "◈  %02d" % coins
	hearts_label.text = "● ".repeat(health) + "○ ".repeat(3-health)
	progress.value = distance / target * 100.0
	if distance < 18:
		hint.text = "좌우로 이동  ·  위로 점프  ·  아래로 슬라이드"
	elif distance < 39:
		hint.text = "청록색 돌은 뛰어넘을 수 있어요  ↑"
	elif distance < 60:
		hint.text = "높은 기둥은 옆으로 피하세요  ← →"
	elif distance < 78:
		hint.text = "붉은 천 아래로 슬라이드하세요  ↓"
	else:
		hint.text = "유물을 따라, 유적 너머로"


func show_ready() -> void:
	question_clock.hide()
	units_label.show()
	brand_label.text = "JADE  /  RUN"
	distance_label.add_theme_font_size_override("font_size", 42)
	study_panel.hide()
	title_group.show()
	ready_card.show()
	controls.hide()
	hint.hide()
	veil.hide()
	modal.hide()
	pause_button.hide()
	progress.hide()


func show_running() -> void:
	question_clock.hide()
	study_panel.hide()
	title_group.hide()
	ready_card.hide()
	controls.show()
	hint.show()
	veil.hide()
	modal.hide()
	pause_button.show()
	progress.show()


func show_pause() -> void:
	question_clock.hide()
	_modal_is_pause = true
	_show_modal("잠깐, 숨 고르기", "복습한 내용과 보상은 그대로예요." if learning_mode else "유적은 여기서 기다리고 있어요.", "", "모험 계속하기  →")


func show_result(won: bool, distance: float, coins: int, best: int, learning: String = "") -> void:
	_modal_is_pause = false
	_show_modal("유적을 건넜어요!" if won else "다시, 한 걸음 더",
		"첫 번째 탐험을 완주했습니다." if won else "다음 탐험에서는 더 멀리 갈 수 있어요.",
		"%d m      ◈ %d\n최고 기록  %d m" % [int(distance), coins, best] + learning, "다시 달리기  ↗")


func set_sound(muted: bool) -> void:
	sound_button.text = "음소거" if muted else "소리 켬"


func pulse_pickup(combo: int) -> void:
	if _pickup_tween:
		_pickup_tween.kill()
	pickup_label.text = "+1" if combo < 5 else "+1  ✦"
	pickup_label.modulate.a = 1.0
	pickup_label.scale = Vector2(1.2, 1.2)
	_pickup_tween = create_tween().set_parallel()
	_pickup_tween.tween_property(pickup_label, "modulate:a", 0.0, 0.5)
	_pickup_tween.tween_property(pickup_label, "scale", Vector2.ONE, 0.25)


func pulse_hit() -> void:
	if _flash_tween:
		_flash_tween.kill()
	flash.color = Color(1, .24, .1, .26)
	_flash_tween = create_tween()
	_flash_tween.tween_property(flash, "color:a", 0.0, 0.5)


func _build_header() -> void:
	var margin := MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	margin.offset_left = 28
	margin.offset_right = -28
	margin.offset_top = 36
	margin.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(margin)
	var column := VBoxContainer.new()
	column.add_theme_constant_override("separation", 16)
	margin.add_child(column)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	column.add_child(row)
	brand_label = _label("JADE  /  RUN", 17, GOLD)
	brand_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(brand_label)
	sound_button = _button("소리 켬", false, 14)
	sound_button.custom_minimum_size = Vector2(80, 42)
	sound_button.pressed.connect(func() -> void: sound_toggled.emit())
	row.add_child(sound_button)
	pause_button = _button("Ⅱ", false, 21)
	pause_button.custom_minimum_size = Vector2(46, 42)
	pause_button.pressed.connect(func() -> void: pause_requested.emit())
	row.add_child(pause_button)
	var stats := HBoxContainer.new()
	stats.alignment = BoxContainer.ALIGNMENT_BEGIN
	stats.add_theme_constant_override("separation", 12)
	column.add_child(stats)
	distance_label = _label("000", 42, CREAM)
	stats.add_child(distance_label)
	units_label = _label("m", 18, CREAM.darkened(0.12))
	units_label.size_flags_vertical = Control.SIZE_SHRINK_END
	stats.add_child(units_label)
	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	stats.add_child(spacer)
	var right := VBoxContainer.new()
	stats.add_child(right)
	coin_label = _label("◈  00", 25, GOLD)
	coin_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	right.add_child(coin_label)
	hearts_label = _label("● ● ●", 16, CREAM)
	right.add_child(hearts_label)
	progress = ProgressBar.new()
	progress.custom_minimum_size.y = 3
	progress.show_percentage = false
	progress.add_theme_stylebox_override("background", _style(Color(1, 1, 1, .18), 2))
	progress.add_theme_stylebox_override("fill", _style(GOLD, 2))
	column.add_child(progress)


func _build_title() -> void:
	title_group = VBoxContainer.new()
	title_group.set_anchors_and_offsets_preset(Control.PRESET_TOP_LEFT)
	title_group.position = Vector2(30, 196)
	title_group.add_theme_constant_override("separation", 3)
	title_group.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(title_group)
	title_group.add_child(_label("01  /  THE JADE CAUSEWAY", 13, GOLD))
	var title := _label("지식의 유적", 49, CREAM)
	title.add_theme_color_override("font_shadow_color", Color(0.04, 0.2, 0.18, 0.5))
	title.add_theme_constant_override("shadow_offset_y", 3)
	title_group.add_child(title)
	title_group.add_child(_label("잊힌 길 위에서 시작되는 모험", 17, CREAM))
	ready_card = VBoxContainer.new()
	ready_card.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	ready_card.offset_left = 30
	ready_card.offset_right = -30
	ready_card.offset_top = -228
	ready_card.offset_bottom = -28
	ready_card.add_theme_constant_override("separation", 12)
	root.add_child(ready_card)
	ready_card.add_child(_label("첫 번째 탐험   /   500 m", 15, GOLD))
	study_caption = _label("달리고, 피하고, 유물을 모으세요.", 19, CREAM)
	ready_card.add_child(study_caption)
	var start := _button("달리기 시작     →", true, 22)
	start.custom_minimum_size.y = 64
	start.pressed.connect(func() -> void: start_requested.emit())
	ready_card.add_child(start)
	var help := _label("스와이프 또는 방향키  ·  SPACE 점프", 14, CREAM.darkened(.12))
	help.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	ready_card.add_child(help)


func _build_controls() -> void:
	hint = _label("", 15, CREAM)
	hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	hint.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	hint.offset_top = -152
	hint.offset_bottom = -125
	root.add_child(hint)
	controls = HBoxContainer.new()
	controls.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	controls.offset_left = 26
	controls.offset_right = -26
	controls.offset_top = -112
	controls.offset_bottom = -32
	controls.add_theme_constant_override("separation", 10)
	root.add_child(controls)
	var actions: Array[String] = ["left", "jump", "slide", "right"]
	var labels: Array[String] = ["←\n이동", "↑\n점프", "↓\n슬라이드", "→\n이동"]
	for i in actions.size():
		var button := _button(labels[i], false, 16)
		button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		button.pressed.connect(func() -> void: action_requested.emit(actions[i]))
		controls.add_child(button)


func _build_modal() -> void:
	veil = ColorRect.new()
	veil.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	veil.color = Color(0.015, .09, .085, .66)
	root.add_child(veil)
	modal = PanelContainer.new()
	modal.set_anchors_and_offsets_preset(Control.PRESET_CENTER)
	modal.offset_left = -230
	modal.offset_right = 230
	modal.offset_top = -190
	modal.offset_bottom = 190
	var panel_style := _style(Color("123a37"), 24)
	panel_style.border_color = Color("698876")
	panel_style.set_border_width_all(1)
	panel_style.content_margin_left = 28
	panel_style.content_margin_right = 28
	panel_style.content_margin_top = 30
	panel_style.content_margin_bottom = 28
	modal.add_theme_stylebox_override("panel", panel_style)
	root.add_child(modal)
	var column := VBoxContainer.new()
	column.add_theme_constant_override("separation", 17)
	modal.add_child(column)
	column.add_child(_label("THE JADE CAUSEWAY", 13, GOLD))
	modal_title = _label("", 29, CREAM)
	column.add_child(modal_title)
	modal_description = _label("", 15, CREAM)
	column.add_child(modal_description)
	modal_stats = _label("", 22, GOLD)
	column.add_child(modal_stats)
	modal_action = _button("", true, 21)
	modal_action.custom_minimum_size.y = 60
	modal_action.pressed.connect(func() -> void:
		if _modal_is_pause:
			resume_requested.emit()
		else:
			start_requested.emit())
	column.add_child(modal_action)
	modal_home = _button("처음 화면으로", false, 16)
	modal_home.custom_minimum_size.y = 45
	modal_home.pressed.connect(func() -> void: home_requested.emit())
	column.add_child(modal_home)


func _show_modal(title: String, description: String, stats: String, action: String) -> void:
	study_panel.hide()
	veil.show()
	modal.show()
	controls.hide()
	hint.hide()
	pause_button.hide()
	modal_title.text = title
	modal_description.text = description
	modal_stats.text = stats
	modal_stats.visible = not stats.is_empty()
	modal_action.text = action


func _label(text: String, font_size: int, color: Color) -> Label:
	var label := Label.new()
	label.text = text
	label.add_theme_font_size_override("font_size", font_size)
	label.add_theme_color_override("font_color", color)
	label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return label


func _button(text: String, primary: bool, font_size: int) -> Button:
	var button := Button.new()
	button.text = text
	button.focus_mode = Control.FOCUS_NONE
	button.add_theme_font_size_override("font_size", font_size)
	var color := GOLD if primary else Color(0.025, 0.16, 0.15, 0.83)
	button.add_theme_stylebox_override("normal", _style(color, 15))
	button.add_theme_stylebox_override("hover", _style(color.lightened(.12), 15))
	button.add_theme_stylebox_override("pressed", _style(color.darkened(.16), 15))
	for state in ["font_color", "font_hover_color", "font_pressed_color"]:
		button.add_theme_color_override(state, INK if primary else CREAM)
	return button


func _style(color: Color, radius: int) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = color
	style.set_corner_radius_all(radius)
	style.content_margin_left = 12
	style.content_margin_right = 12
	return style


func _gradient(top: bool) -> void:
	var texture := GradientTexture2D.new()
	var gradient := Gradient.new()
	gradient.colors = PackedColorArray([Color(.01,.10,.09,.80), Color(.01,.10,.09,0)])
	texture.gradient = gradient
	texture.fill_from = Vector2(.5, 0 if top else 1)
	texture.fill_to = Vector2(.5, 1 if top else 0)
	var rect := TextureRect.new()
	rect.texture = texture
	rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	rect.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE if top else Control.PRESET_BOTTOM_WIDE)
	if top:
		rect.offset_bottom = 310
	else:
		rect.offset_top = -310
	root.add_child(rect)


func set_study_available(available: bool) -> void:
	study_caption.text = "문제를 풀어 다음 관문을 계속 여세요" if available else "자유 달리기 · 학습 연결 없음"


func show_study_wait(message: String) -> void:
	_open_study_panel()
	study_number.text = "지식의 관문"
	study_prompt.text = message
	study_choices.hide()
	study_feedback.hide()
	study_continue.hide()
	study_home.disabled = true


func show_question(question: Dictionary, index: int, _total: int) -> void:
	_open_study_panel()
	study_scroll.scroll_vertical = 0
	question_clock.show()
	pause_button.show()
	_question_choices = question["choices"].duplicate()
	study_number.text = "지식의 관문   %d번째" % (index + 1)
	study_prompt.text = question["prompt"]
	for child in study_choices.get_children():
		study_choices.remove_child(child)
		child.queue_free()
	for i in question["choices"].size():
		var button := _button("%d   %s" % [i + 1, question["choices"][i]], false, 19)
		button.alignment = HORIZONTAL_ALIGNMENT_LEFT
		button.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		button.custom_minimum_size.y = 64
		button.pressed.connect(func() -> void: answer_selected.emit(i))
		study_choices.add_child(button)
	study_choices.show()
	study_feedback.hide()
	study_continue.hide()
	study_home.disabled = false


func show_study_feedback(verdict: Dictionary, retry: bool = false, reward: int = 0, streak: int = 0) -> void:
	study_scroll.scroll_vertical = 0
	question_clock.hide()
	var correct: bool = verdict["correct"]
	study_number.text = ("다시 확인했어요!" if retry else "정답! 관문이 열렸어요") if correct else "잠깐, 원리를 확인해 봐요"
	var chosen: String = "시간 초과 · 미응답" if verdict.get("timedOut", false) else str(_question_choices[int(verdict["selectedChoice"])])
	if verdict.get("timedOut", false):
		study_number.text = "시간이 다 됐어요 · 원리를 확인해 봐요"
	var answer: String = str(_question_choices[int(verdict["correctChoice"])])
	study_feedback.text = "선택: %s\n정답: %s\n\n%s" % [chosen, answer, verdict["explanation"]]
	if correct:
		study_feedback.text += "\n\n지식 에너지 +%d" % reward
		if streak >= 2:
			study_feedback.text += " · %d연속 정답!" % streak
	study_feedback.show()
	study_choices.hide()
	study_continue.text = "다음 관문으로  →" if correct else "다시 일어나 도전하기  →"
	study_continue.show()
	study_home.disabled = false


func update_learning(cleared: int, knowledge: int) -> void:
	units_label.hide()
	brand_label.text = "지식의 유적 · 문제로 여는 길"
	distance_label.add_theme_font_size_override("font_size", 28)
	distance_label.text = "관문  %d개 완료" % cleared
	coin_label.text = "✦  %d" % knowledge
	hearts_label.text = "지식 에너지"
	progress.hide()


func show_cinematic(message: String) -> void:
	show_running()
	controls.hide()
	hint.text = message


func show_reward(cleared: int, knowledge: int) -> void:
	show_running()
	controls.hide()
	hint.text = "문제로 열린 길을 건너는 중 · 자동 이동"
	update_learning(cleared, knowledge)


func show_learning_result(summary: String, knowledge: int) -> void:
	_modal_is_pause = false
	_show_modal("오늘의 복습 기록", "문제를 풀어 길을 열었어요.",
		summary + "\n지식 에너지  %d" % knowledge, "새 문제로 복습하기  ↗")


func pulse_correct(reward: int) -> void:
	if _flash_tween:
		_flash_tween.kill()
	flash.color = Color(1.0, .8, .35, .15)
	_flash_tween = create_tween()
	_flash_tween.tween_property(flash, "color:a", 0.0, .45)
	if _reward_tween:
		_reward_tween.kill()
	pickup_label.text = "+%d ✦" % reward
	pickup_label.position = Vector2(root.size.x * .5 - 55, 170)
	pickup_label.size = Vector2(110, 55)
	pickup_label.modulate.a = 1.0
	pickup_label.scale = Vector2(1.3, 1.3)
	_reward_tween = create_tween().set_parallel()
	_reward_tween.tween_property(pickup_label, "position", Vector2(root.size.x - 140, 95), .8).set_trans(Tween.TRANS_CUBIC)
	_reward_tween.tween_property(pickup_label, "scale", Vector2(.5, .5), .8)
	_reward_tween.tween_property(pickup_label, "modulate:a", 0.0, .3).set_delay(.5)


func show_study_error(message: String) -> void:
	_open_study_panel()
	study_number.text = "학습 연결을 확인해 주세요"
	study_prompt.text = message
	study_choices.hide()
	study_feedback.hide()
	study_continue.hide()
	study_home.disabled = false


func _open_study_panel() -> void:
	if learning_mode:
		veil.hide()
	else:
		veil.show()
	modal.hide()
	title_group.hide()
	ready_card.hide()
	controls.hide()
	hint.hide()
	pause_button.hide()
	study_panel.show()
	question_clock.hide()


func _build_study_panel() -> void:
	question_clock = VBoxContainer.new()
	question_clock.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	question_clock.offset_left = 32
	question_clock.offset_right = -32
	question_clock.offset_top = 172
	question_clock.mouse_filter = Control.MOUSE_FILTER_IGNORE
	question_clock.add_theme_constant_override("separation", 4)
	root.add_child(question_clock)
	question_time_label = _label("", 15, CREAM)
	question_clock.add_child(question_time_label)
	question_time_bar = ProgressBar.new()
	question_time_bar.custom_minimum_size.y = 8
	question_time_bar.show_percentage = false
	question_time_bar.add_theme_stylebox_override("background", _style(INK, 4))
	_time_fill = _style(GOLD, 4)
	question_time_bar.add_theme_stylebox_override("fill", _time_fill)
	question_clock.add_child(question_time_bar)
	question_clock.hide()
	study_panel = PanelContainer.new()
	study_panel.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	study_panel.offset_left = 24
	study_panel.offset_right = -24
	study_panel.anchor_top = .48
	study_panel.offset_top = 0
	study_panel.offset_bottom = -24
	var style := _style(Color("123a37"), 24)
	style.set_content_margin_all(24)
	style.border_color = GOLD.darkened(.5)
	style.set_border_width_all(1)
	study_panel.add_theme_stylebox_override("panel", style)
	root.add_child(study_panel)
	study_scroll = ScrollContainer.new()
	study_scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	study_panel.add_child(study_scroll)
	var column := VBoxContainer.new()
	column.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	column.add_theme_constant_override("separation", 16)
	study_scroll.add_child(column)
	study_number = _label("", 17, GOLD)
	column.add_child(study_number)
	study_prompt = _label("", 22, CREAM)
	study_prompt.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	column.add_child(study_prompt)
	study_choices = VBoxContainer.new()
	study_choices.add_theme_constant_override("separation", 10)
	column.add_child(study_choices)
	study_feedback = _label("", 18, CREAM)
	study_feedback.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	column.add_child(study_feedback)
	study_continue = _button("계속 달리기  →", true, 21)
	study_continue.custom_minimum_size.y = 60
	study_continue.pressed.connect(func() -> void: study_continued.emit())
	column.add_child(study_continue)
	study_home = _button("탐험 마치기", false, 16)
	study_home.custom_minimum_size.y = 45
	study_home.pressed.connect(func() -> void: home_requested.emit())
	column.add_child(study_home)


func update_question_time(remaining: float, total: float) -> void:
	question_time_label.text = "남은 시간  %.1f초" % remaining
	question_time_bar.value = 100.0 * remaining / total
	_time_fill.bg_color = Color("ff946b") if remaining <= 5.0 else GOLD
