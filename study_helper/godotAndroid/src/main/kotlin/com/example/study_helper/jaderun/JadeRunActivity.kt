package com.example.study_helper.jaderun

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsControllerCompat
import com.example.study_helper.core.StudyService
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotActivity
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.UsedByGodot
import org.json.JSONObject

class JadeRunActivity : GodotActivity() {
    private var bridge: StudyBridgePlugin? = null
    private var loading: LinearLayout? = null
    private var returning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(246, 247, 242))
            addView(ProgressBar(this@JadeRunActivity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(36, 92, 79))
            })
            addView(TextView(this@JadeRunActivity).apply {
                text = "복습 공간을 준비하고 있어요"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(32, 58, 50))
                setPadding(24, 40, 24, 0)
            })
        }
        addContentView(loading, ViewGroup.LayoutParams(-1, -1))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnToStudy()
        })
    }

    override fun getHostPlugins(engine: Godot): Set<GodotPlugin> {
        val plugin = bridge ?: StudyBridgePlugin(engine, this,
            intent.getStringExtra(StudyGameContract.QUESTION_SET) ?: "").also { bridge = it }
        return setOf(plugin)
    }

    fun gameReady() = runOnUiThread {
        loading?.let { (it.parent as? ViewGroup)?.removeView(it) }
        loading = null
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
    }

    fun returnToStudy() = runOnUiThread {
        if (!returning) {
            returning = true
            val summary = bridge?.closeSession()
            if (summary != null) {
                val record = JSONObject().put("title", intent.getStringExtra(StudyGameContract.TITLE) ?: "학습 노트")
                    .put("summary", summary).toString()
                setResult(Activity.RESULT_OK, Intent().putExtra(StudyGameContract.RESULT, record))
            }
            finish()
        }
    }

    override fun onGodotForceQuit(instance: Godot) = returnToStudy()

    override fun onDestroy() {
        super.onDestroy()
        // Godot native state cannot be reused after teardown. Only this activity's
        // :learning_game process exits; the Kotlin home and its result stay alive.
        if (isFinishing) Process.killProcess(Process.myPid())
    }
}

/** Platform adapter only. Never generates questions or grades an answer itself. */
class StudyBridgePlugin(godot: Godot, private val host: JadeRunActivity, private val questionSet: String) : GodotPlugin(godot) {
    private val study = StudyService()
    private var sessionId: String? = null
    private var summary: JSONObject? = null
    override fun getPluginName(): String = "StudyBridge"

    @UsedByGodot
    @Synchronized
    fun exchange(request: String): String {
        val envelope = runCatching { JSONObject(request) }.getOrNull() ?: return study.exchange(request)
        if (envelope.optString("type") == "begin") {
            // Parsing and validation happen in StudyService; never fall back to generated sample questions.
            val payload = runCatching { JSONObject(questionSet) }.getOrElse { JSONObject() }
            envelope.put("body", JSONObject().put("questionSet", payload)
                .put("continuous", envelope.optJSONObject("body")?.optBoolean("continuous", false) == true))
        }
        val response = study.exchange(envelope.toString())
        val result = JSONObject(response)
        when (result.optString("type")) {
            "session" -> { sessionId = result.getJSONObject("body").getString("sessionId"); summary = null }
            "summary" -> { summary = result.getJSONObject("body"); sessionId = null }
        }
        return response
    }

    @Synchronized
    fun closeSession(): JSONObject? {
        sessionId?.let { id ->
            exchange(JSONObject().put("version", 1).put("requestId", "native-close-$id")
                .put("type", "end").put("body", JSONObject().put("sessionId", id)).toString())
        }
        return summary
    }

    @UsedByGodot
    fun returnToStudy() = host.returnToStudy()

    @UsedByGodot
    fun gameReady() = host.gameReady()
}
