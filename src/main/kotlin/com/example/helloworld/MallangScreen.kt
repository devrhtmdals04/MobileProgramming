package com.example.helloworld

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.*

internal enum class Material(val title: String, val hint: String, val light: Int, val dark: Int,
                            val stiffness: Float, val damping: Float, val stretch: Float) {
    JELLY("젤리", "탱글탱글 · 길게 늘어나요", 0xffffc8c2.toInt(), 0xffe7819a.toInt(), 135f, .27f, 1f),
    PUDDING("푸딩", "몽글몽글 · 오래 출렁여요", 0xffffe5a3.toInt(), 0xffdca74e.toInt(), 85f, .20f, .72f),
    MOCHI("모찌", "쫀득쫀득 · 천천히 돌아와요", 0xffe7ebd4.toInt(), 0xffa5b894.toInt(), 190f, .65f, .46f)
}

class MallangScreen(context: Context) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("mallang", Context.MODE_PRIVATE)
    private val toy = MallangView(context)
    private val ink = 0xff4d5149.toInt()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        setBackgroundColor(0xfff9f6f0.toInt())
        setPadding(dp(24), dp(12), dp(24), dp(16))
        setOnApplyWindowInsetsListener { _, insets ->
            setPadding(dp(24), dp(12) + insets.systemWindowInsetTop, dp(24), dp(16) + insets.systemWindowInsetBottom)
            insets
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(context).apply {
            text = "mallang ii"; textSize = 28f; setTextColor(ink)
            typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        val sound = Button(context).apply {
            isAllCaps = false; textSize = 12f; minWidth = 0
            background = pill(0xffeeeae2.toInt()); setTextColor(ink)
        }
        toy.soundEnabled = prefs.getBoolean("sound", true)
        fun soundLabel() { sound.text = if (toy.soundEnabled) "소리 켜짐" else "소리 꺼짐" }
        soundLabel()
        sound.setOnClickListener {
            toy.soundEnabled = !toy.soundEnabled; soundLabel()
            prefs.edit().putBoolean("sound", toy.soundEnabled).apply()
        }
        heading.addView(sound, LinearLayout.LayoutParams(dp(88), dp(44)))
        addView(heading)
        addView(TextView(context).apply {
            text = "아무것도 안 해도 괜찮아. 그냥 조물조물."; textSize = 13f; setTextColor(0xff929187.toInt())
            setPadding(0, dp(8), 0, 0)
        })
        addView(toy, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val hint = TextView(context).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(ink) }
        addView(hint, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        val choices = LinearLayout(context)
        val buttons = Material.entries.map { material ->
            Button(context).apply {
                text = material.title; isAllCaps = false; textSize = 14f; setTextColor(ink)
                choices.addView(this, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }
        fun select(index: Int) {
            toy.material = Material.entries[index]
            hint.text = toy.material.hint
            buttons.forEachIndexed { i, button ->
                button.isSelected = i == index
                button.background = pill(if (i == index) toy.material.light else 0xffeeeae2.toInt())
                button.contentDescription = "${Material.entries[i].title}${if (i == index) ", 선택됨" else ""}"
            }
            prefs.edit().putInt("material", index).apply()
        }
        buttons.forEachIndexed { index, button -> button.setOnClickListener { select(index) } }
        select(prefs.getInt("material", 0).coerceIn(0, 2))
        addView(choices)
        addView(TextView(context).apply {
            text = "톡 누르고 · 꾹 누르고 · 쭉 늘려보세요"; textSize = 12f
            gravity = Gravity.CENTER; setTextColor(0xff929187.toInt()); setPadding(0, dp(20), 0, dp(4))
        })
    }
    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }
    fun resume() = toy.resume()
    fun pause() = toy.pause()
    fun release() = toy.release()
}

internal class MallangView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path()
    private val surface = SoftSurface()
    private val mapped = FloatArray(2)
    private var releaseTime = 0L
    private var releaseEnergy = 0f
    private val points = Array(64) { PointF() }
    private val density = resources.displayMetrics.density
    private var active = false
    private var pointer = -1
    private var downTime = 0L
    private var dragged = false
    private var heldFeedback = false
    private var gripX = 0f
    private var gripY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lookX = 0f
    private var lookY = 0f
    private var running = true
    private var status = "만나서 말랑!"
    private enum class Mood(val label: String) {
        HAPPY("기분 좋음"), SURPRISED("깜짝"), SAD("우울"), COY("새침"), SULKY("삐짐"), SQUEEZED("울상"), DIZZY("어질어질")
    }
    private var mood = Mood.HAPPY
    private var moodUntil = 0L
    private var lastTap = 0L
    private var tapStreak = 0
    private var peakStretch = 0f
    private val expressionPath = Path()
    private fun emote(next: Mood, caption: String, duration: Long = 2500L) {
        mood = next; status = caption; moodUntil = SystemClock.uptimeMillis() + duration
        contentDescription = "말랑이, ${next.label}. 톡 누르거나 길게 누르고 늘려보세요."
    }
    private fun recover(time: Long) {
        if (active || time < moodUntil) return
        when (mood) {
            Mood.SULKY, Mood.SQUEEZED, Mood.DIZZY -> emote(Mood.SAD, "나 쪼끔 속상해…", 2200)
            Mood.SAD -> emote(Mood.COY, "흥… 이번만 봐준다.", 2200)
            Mood.COY, Mood.SURPRISED -> emote(Mood.HAPPY, "그래도 네가 좋아.", 4000)
            Mood.HAPPY -> status = "조물조물, 같이 놀자."
        }
    }
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private var loaded = false
    private val pop: Int
    var soundEnabled = true
    var material = Material.JELLY
        set(value) { field = value; channels.forEach { it.tune() }; invalidate() }
    private inner class Channel {
        val holder = FloatValueHolder(0f)
        val animation = SpringAnimation(holder).apply {
            spring = SpringForce(0f)
            minimumVisibleChange = .001f
            addUpdateListener { _, _, _ -> invalidate() }
        }
        val value get() = holder.value
        fun tune() { animation.spring!!.stiffness = material.stiffness; animation.spring!!.dampingRatio = material.damping }
        fun target(value: Float) { tune(); animation.animateToFinalPosition(value) }
        fun reset() { animation.cancel(); holder.value = 0f }
    }
    private val squash = Channel()
    private val pullX = Channel()
    private val pullY = Channel()
    private val channels = listOf(squash, pullX, pullY)
    private val cx get() = width * .5f
    private val cy get() = height * .48f
    private val radius get() = min(width * .29f, height * .27f).coerceAtLeast(1f)
    init {
        pool.setOnLoadCompleteListener { _, _, result -> loaded = result == 0 }
        pop = pool.load(context, R.raw.pop, 1)
        isClickable = true; isFocusable = true
        contentDescription = "말랑이. 두 번 탭하면 움찔해요. 길게 누르면 납작해지고, 끌면 늘어나요."
    }
    private fun feedback(rate: Float = 1f) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (soundEnabled && loaded) pool.play(pop, .32f, .32f, 1, 0, rate)
    }
    override fun performClick(): Boolean {
        super.performClick()
        squash.target(0f)
        squash.animation.setStartVelocity(4f)
        feedback(when(material) { Material.JELLY -> 1.2f; Material.PUDDING -> .85f; Material.MOCHI -> .65f })
        val now = SystemClock.uptimeMillis()
        tapStreak = if (now-lastTap < 1400) tapStreak+1 else 1
        lastTap = now
        when {
            tapStreak >= 4 -> emote(Mood.SULKY, "흥! 나 삐졌어.", 4500)
            tapStreak == 3 -> emote(Mood.COY, "자꾸 찌를 거야?", 3000)
            tapStreak == 2 -> emote(Mood.SURPRISED, "앗! 또 너야?", 1800)
            else -> emote(Mood.HAPPY, "헤헤, 간지러워!", 2200)
        }
        invalidate(); return true
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot((e.x-cx)/radius, (e.y-cy)/radius) > 1.18f) return false
                pointer = e.getPointerId(0); active = true; dragged = false; heldFeedback = false
                downTime = SystemClock.uptimeMillis(); lastX = e.x; lastY = e.y; peakStretch = 0f
                gripX = (e.x-cx)/radius; gripY = (e.y-cy)/radius
                squash.target(.13f)
                if (mood == Mood.HAPPY) emote(Mood.SURPRISED, "응? 나 불렀어?")
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i < 0) return true
                lastX = e.getX(i); lastY = e.getY(i)
                val dx = (lastX-cx)/radius-gripX; val dy = (lastY-cy)/radius-gripY
                if (hypot(dx,dy) > .09f) dragged = true
                if (dragged) {
                    pullX.target(dx.coerceIn(-1.6f,1.6f) * material.stretch)
                    pullY.target(dy.coerceIn(-1.6f,1.6f) * material.stretch)
                    squash.target(.07f)
                    peakStretch = max(peakStretch, hypot(dx,dy))
                    if (peakStretch > .85f) emote(Mood.DIZZY, "으아아, 늘어난다아…")
                    else emote(Mood.COY, "볼은 왜 당겨…", 3000)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pointer) finish(false)
            MotionEvent.ACTION_UP -> finish(true)
            MotionEvent.ACTION_CANCEL -> finish(false)
        }
        invalidate(); return true
    }
    private fun finish(click: Boolean) {
        if (!active) return
        val tap = !dragged && SystemClock.uptimeMillis()-downTime < 250
        releaseTime = SystemClock.uptimeMillis()
        releaseEnergy = (hypot(pullX.value,pullY.value)*.055f + abs(squash.value)*.08f).coerceAtMost(.12f)
        active = false; pointer = -1
        lookX = ((lastX-cx)/radius).coerceIn(-1f,1f)
        lookY = ((lastY-cy)/radius).coerceIn(-1f,1f)
        channels.forEach { it.target(0f) }
        if (click && tap) performClick() else if (click) {
            tapStreak = 0
            feedback(.8f)
            when {
                dragged && peakStretch > .85f -> emote(Mood.DIZZY, "세상이 출렁출렁…", 2800)
                dragged -> emote(Mood.COY, "흥, 내 볼 돌려줘.", 3000)
                SystemClock.uptimeMillis()-downTime > 1300 -> emote(Mood.SAD, "나… 납작해졌어…", 3800)
                else -> emote(Mood.SULKY, "방금 꾹 눌렀지?", 3200)
            }
        }
        parent.requestDisallowInterceptTouchEvent(false)
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val time = SystemClock.uptimeMillis()
        recover(time)
        if (active && !dragged && time-downTime > 250) {
            squash.target(.72f)
            if (!heldFeedback) { heldFeedback = true; feedback(.7f) }
            if (time-downTime > 1300) emote(Mood.SQUEEZED, "우우… 나 찌부됐어…")
            else emote(Mood.SURPRISED, "어어… 납작해져…")
        }
        val s = squash.value.coerceIn(-.4f,.9f)
        val breathe = if (active) 0f else sin(time/1100.0).toFloat()*.014f
        val sy = 1f-s*.53f-breathe
        // Keep approximate 3D volume: flattened height pushes mass out sideways and in depth.
        val sx = 1f / sqrt(sy)
        val px = pullX.value; val py = pullY.value
        val length = hypot(px,py)
        // Bound the deformation gradient so a hard diagonal pull cannot fold the mesh over itself.
        val pullScale = 1f / (1f + length*.65f)
        val age = (time-releaseTime)/1000f
        val ripple = if(active) 0f else releaseEnergy*exp(-age*4.5f)
        fun map(nx: Float, ny: Float, out: FloatArray, index: Int) {
            val angle = atan2(ny,nx)
            val rr = hypot(nx,ny)
            val organic = 1f+.014f*sin(angle*3+time/1800.0).toFloat()*rr
            val influence = exp(-((nx-gripX).pow(2)+(ny-gripY).pow(2))*.85f)
            val wave = ripple*sin(angle*4-age*19).toFloat()*rr
            // Stretch along the pull while narrowing its cross section, like elastic silicone.
            val alongX = if(length > .001f) px/length else 0f
            val alongY = if(length > .001f) py/length else 0f
            val transverse = nx*(-alongY)+ny*alongX
            val neck = min(.25f,length*.17f)*influence
            val gripDistance = (nx-gripX).pow(2)+(ny-gripY).pow(2)
            val dent = max(0f,s)*.15f*exp(-gripDistance*9f)
            out[index] = cx+radius*(nx*sx*(organic+wave)+px*pullScale*influence+alongY*transverse*neck-nx*dent)
            out[index+1] = cy+radius*(ny*sy*(organic+wave)+py*pullScale*influence-alongX*transverse*neck+s*.43f-ny*dent)
        }
        val shadowX = cx+px*radius*.17f
        val floorY = cy+radius*.94f
        paint.shader = RadialGradient(shadowX,floorY,radius*1.35f*sx,
            intArrayOf(0x344e3c35,0x164e3c35,0x004e3c35),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
        c.save(); c.scale(1f,.23f,shadowX,floorY)
        c.drawCircle(shadowX,floorY,radius*1.35f*sx,paint); c.restore()
        // A tighter contact shadow gives the toy weight on the surface.
        paint.shader = RadialGradient(shadowX,floorY,radius*.78f*sx,intArrayOf(0x384e3c35,0x004e3c35),null,Shader.TileMode.CLAMP)
        c.save(); c.scale(1f,.10f,shadowX,floorY)
        c.drawCircle(shadowX,floorY,radius*.78f*sx,paint); c.restore(); paint.shader = null
        for (i in points.indices) {
            val angle = i*2.0*PI/points.size
            map(cos(angle).toFloat(),sin(angle).toFloat(),mapped,0)
            points[i].set(mapped[0],mapped[1])
        }
        body.reset()
        val first = points.first(); val last = points.last()
        body.moveTo((last.x+first.x)/2,(last.y+first.y)/2)
        points.forEachIndexed { i,p -> val next = points[(i+1)%points.size]; body.quadTo(p.x,p.y,(p.x+next.x)/2,(p.y+next.y)/2) }
        body.close()
        surface.draw(c, material, ::map)
        c.save(); c.clipPath(body)
        // The fingertip makes a soft concavity: shaded center, light catching the lower lip.
        val pressure = max(0f,s)
        if (pressure > .02f && length < .25f) {
            map(gripX*.72f,gripY*.72f,mapped,0)
            val dentX = mapped[0]; val dentY = mapped[1]
            val dentRadius = radius*.32f
            paint.shader = RadialGradient(dentX,dentY,dentRadius,
                intArrayOf(Color.argb((pressure*55).toInt(),95,44,58),0x005f2c3a),null,Shader.TileMode.CLAMP)
            c.drawCircle(dentX,dentY,dentRadius,paint)
            paint.shader = RadialGradient(dentX,dentY+dentRadius*.60f,dentRadius*.85f,
                intArrayOf(Color.argb((pressure*100).toInt(),255,255,255),0x00ffffff),null,Shader.TileMode.CLAMP)
            c.save(); c.scale(1f,.36f,dentX,dentY+dentRadius*.60f)
            c.drawCircle(dentX,dentY+dentRadius*.60f,dentRadius*.85f,paint); c.restore()
            paint.shader = null
        }
        map(0f,.10f,mapped,0)
        val faceX = mapped[0]
        val faceY = mapped[1]
        val eyeGap = radius*(.23f+s*.25f)
        val gazeX = (if(active) ((lastX-cx)/radius).coerceIn(-1f,1f) else lookX)*.045f
        val gazeY = (if(active) ((lastY-cy)/radius).coerceIn(-1f,1f) else lookY)*.035f
        drawExpression(c, faceX, faceY, eyeGap/radius, gazeX, gazeY, time)
        c.restore()
        paint.color = 0xffa29c90.toInt(); paint.textSize = 13*density; paint.textAlign = Paint.Align.CENTER
        c.drawText(status,cx,(cy+radius*1.8f).coerceAtMost(height-16*density),paint)
        if (running) postInvalidateDelayed(if(active || channels.any { it.animation.isRunning }) 16 else 33)
    }
    /** Draw in body-radius units so every expression follows stretching and screen size. */
    private fun drawExpression(c: Canvas, x: Float, y: Float, gap: Float, gx: Float, gy: Float, time: Long) {
        c.save()
        c.translate(x, y)
        c.scale(radius, radius)
        if (mood == Mood.COY || mood == Mood.SULKY) c.rotate(if (lookX >= 0) -7f else 7f)
        val blink = !active && time % 4600 < 140
        val faceInk = 0xff51434b.toInt()
        fun stroke() {
            paint.color = faceInk; paint.style = Paint.Style.STROKE
            paint.strokeWidth = .024f; paint.strokeCap = Paint.Cap.ROUND
        }
        fun curve(x1: Float, y1: Float, mx: Float, my: Float, x2: Float, y2: Float) {
            stroke(); expressionPath.reset(); expressionPath.moveTo(x1,y1)
            expressionPath.quadTo(mx,my,x2,y2); c.drawPath(expressionPath,paint)
            paint.style = Paint.Style.FILL
        }
        for (side in intArrayOf(-1,1)) {
            val ex = side * gap
            paint.style = Paint.Style.FILL
            paint.color = if (mood == Mood.SULKY) 0x66db7183 else 0x40cc6b78
            val cheek = if (mood == Mood.SULKY) .16f else .12f
            c.drawOval(ex-cheek,.10f,ex+cheek,.20f,paint)
            val gaze = if (mood == Mood.COY || mood == Mood.SULKY) -.035f else gx
            when {
                mood == Mood.DIZZY -> {
                    stroke(); expressionPath.reset()
                    for (i in 0..48) {
                        val t = i / 48f; val a = t * PI * 4 + time / 260.0
                        val px = ex + cos(a).toFloat()*.082f*t
                        val py = sin(a).toFloat()*.082f*t
                        if (i==0) expressionPath.moveTo(px,py) else expressionPath.lineTo(px,py)
                    }
                    c.drawPath(expressionPath,paint)
                }
                mood == Mood.SQUEEZED -> {
                    stroke(); expressionPath.reset()
                    expressionPath.moveTo(ex+side*.065f,-.075f)
                    expressionPath.lineTo(ex-side*.025f,0f)
                    expressionPath.lineTo(ex+side*.065f,.055f)
                    c.drawPath(expressionPath,paint)
                }
                blink -> { stroke(); c.drawLine(ex-.045f,0f,ex+.045f,0f,paint) }
                else -> {
                    paint.color = faceInk
                    val h = when(mood) { Mood.SURPRISED -> .105f; Mood.SAD -> .083f; Mood.COY -> .036f; Mood.SULKY -> .045f; else -> .068f }
                    c.drawOval(ex-.044f+gaze,-h+gy,ex+.044f+gaze,h+gy,paint)
                    paint.color = 0xddffffff.toInt()
                    c.drawCircle(ex+gaze-.014f,gy-h*.35f,.014f,paint)
                }
            }
            paint.style = Paint.Style.FILL
            when (mood) {
                Mood.SAD, Mood.SQUEEZED -> {
                    // Inner brows lift; translucent tears sit under the outer eye corners.
                    curve(ex-side*.065f,-.17f,ex,-.12f,ex+side*.07f,-.12f)
                    paint.color = 0x99a8ddef.toInt()
                    val dropY = if(mood == Mood.SQUEEZED) .10f+(time%850)/850f*.12f else .095f
                    c.drawOval(ex+side*.06f-.026f,dropY,ex+side*.06f+.026f,dropY+.075f,paint)
                }
                Mood.SULKY -> curve(ex-side*.07f,-.105f,ex,-.13f,ex+side*.07f,-.18f)
                Mood.COY -> {
                    stroke(); c.drawLine(ex-.07f,-.03f,ex+.065f,-.03f,paint)
                    if(side == -1) curve(ex-.07f,-.16f,ex,-.19f,ex+.06f,-.15f)
                }
                Mood.SURPRISED -> curve(ex-.06f,-.18f,ex,-.23f,ex+.06f,-.18f)
                else -> Unit
            }
            paint.style = Paint.Style.FILL
        }
        when (mood) {
            Mood.HAPPY -> curve(-.075f,.06f,0f,.18f,.075f,.06f)
            Mood.SURPRISED -> { stroke(); c.drawOval(-.045f,.07f,.045f,.18f,paint) }
            Mood.SAD -> curve(-.07f,.16f,0f,.07f,.07f,.16f)
            Mood.COY -> curve(-.05f,.11f,.015f,.14f,.075f,.08f)
            Mood.SULKY -> {
                curve(-.06f,.105f,.02f,.07f,.06f,.11f)
                curve(-.06f,.14f,.02f,.18f,.06f,.14f)
            }
            Mood.SQUEEZED, Mood.DIZZY -> {
                stroke(); expressionPath.reset(); expressionPath.moveTo(-.10f,.13f)
                repeat(4) { i -> expressionPath.quadTo(-.075f+i*.05f,if(i%2==0) .08f else .18f,-.05f+i*.05f,.13f) }
                c.drawPath(expressionPath,paint)
            }
        }
        paint.style = Paint.Style.FILL
        c.restore()
    }
    fun resume() { running = true; invalidate() }
    fun pause() { running = false; active = false; pointer = -1; channels.forEach { it.reset() }; releaseEnergy = 0f; pool.autoPause() }
    fun release() { pause(); pool.release() }
}
