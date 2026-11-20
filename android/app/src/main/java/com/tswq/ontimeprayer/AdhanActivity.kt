package com.tswq.ontimeprayer

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * The adhan screen: opened by the adhan notification when the phone is locked or its screen is off,
 * like an incoming call. The prayer's name and time, with "Stop", "I prayed" and "Remind me before it ends".
 * The volume buttons stop the adhan here too (if that option is on); it closes when the adhan ends.
 */
class AdhanActivity : Activity() {
    private var ev: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#11594A"); window.navigationBarColor = Color.parseColor("#0A382C")
        current = WeakReference(this)
        show(intent?.getStringExtra("ev"))
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); show(intent.getStringExtra("ev")) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun show(json: String?) {
        val e = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        // nothing playing any more (opened late from the notification): nothing to show
        if (e == null || AdhanService.playingPk() == null) { finish(); return }
        ev = e
        val ar = Exits.ar(e); val f = WFonts.get(assets); val pk = e.optString("pk")
        val root = FrameLayout(this); root.addView(Backdrop(this))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = if (ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        fun tv(s: String, size: Float, tf: Typeface, color: Int) = TextView(this).apply { text = s; textSize = size; typeface = tf; setTextColor(color); gravity = Gravity.CENTER
            setShadowLayer(dp(6f), 0f, dp(1f), 0x66000000) }
        val cream = Color.parseColor("#F4ECDC"); val gold = Color.parseColor("#E2C27A")
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1.15f))
        col.addView(tv(if (ar) "حان الآن وقت صلاة" else "It is time for", 17f, f.plex, Color.argb(200, 244, 236, 220)))
        col.addView(tv(e.optString("title"), 64f, f.name("kufi"), cream))
        col.addView(tv(Exits.clock(e.optLong("at"), e), 26f, f.plex, gold))
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        fun btn(s: String, primary: Boolean, click: (Button) -> Unit) = Button(this).apply {
            text = s; isAllCaps = false; textSize = if (primary) 17f else 14f; typeface = f.plexB; stateListAnimator = null; setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
            setTextColor(if (primary) Color.parseColor("#0A2E24") else cream)
            background = GradientDrawable().apply { cornerRadius = dp(20f); if (primary) setColor(gold) else { setColor(Color.argb(30, 255, 255, 255)); setStroke(dp(1f).toInt(), Color.argb(70, 226, 194, 122)) } }
            setOnClickListener { click(this) }
        }
        val lp = { h: Float -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h).toInt()).apply { setMargins(dp(28f).toInt(), dp(6f).toInt(), dp(28f).toInt(), dp(6f).toInt()) } }
        col.addView(btn(if (ar) "إيقاف الأذان" else "Stop the adhan", true) { stopAdhan() }, lp(64f))
        // the user's buttons, in their order (as in the notification); "Remind me in…" opens its durations in place
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val half = { LinearLayout.LayoutParams(0, dp(56f).toInt(), 1f).apply { setMargins(dp(5f).toInt(), 0, dp(5f).toInt(), 0) } }
        fun fill() {
            row.removeAllViews()
            if (!Exits.hasEx(e)) return
            val btns = Alerts.buttonsOf(e)
            // the user's buttons, as in the notification; each "remind me" opens its durations in place and picking one replaces the other
            if ("pray" in btns || Reminders.find(this, pk) != null || Reminders.find(this, pk, "later") != null)
                row.addView(btn(if (ar) "صلّيت" else "I prayed", false) { Alerts.prayed(this, pk); finish() }, half())
            for (bt in btns) when (bt) {
                "exit" -> if (Alerts.beforeChoices(e, System.currentTimeMillis()).isNotEmpty()) row.addView(btn(if (ar) "ذكّرني قبل خروج الوقت" else "Before it ends…", false) {
                    row.removeAllViews()
                    for (m in Alerts.beforeChoices(e, System.currentTimeMillis())) row.addView(btn((if (ar) "قبل " else "") + Exits.mins(m, e), false) { Alerts.before(this, e, m); stopAdhan() }, half())
                }, half())
                "later" -> if (Alerts.laterChoices(e, System.currentTimeMillis()).isNotEmpty()) row.addView(btn(if (ar) "ذكّرني بعد…" else "Remind me in…", false) {
                    row.removeAllViews()
                    for (m in Alerts.laterChoices(e, System.currentTimeMillis())) row.addView(btn(Exits.mins(m, e), false) { Alerts.later(this, e, m); stopAdhan() }, half())
                }, half())
            }
        }
        fill()
        col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(22f).toInt(), dp(4f).toInt(), dp(22f).toInt(), 0) })
        // when the time ends, under the buttons
        Exits.next(e)?.let { x -> col.addView(tv((if (ar) "يخرج ${x.name} ${x.clock}" else "${x.name} ends at ${x.clock}"), 13f, f.plex, Color.argb(170, 244, 236, 220)),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(24f).toInt(), dp(12f).toInt(), dp(24f).toInt(), 0) }) }
        val ways = e.optString("stop", "pvf"); val hints = ArrayList<String>()
        if ('p' in ways) hints += if (ar) "زر القفل" else "the power button"
        if ('v' in ways) hints += if (ar) "زر الصوت" else "a volume button"
        if ('f' in ways) hints += if (ar) "قلب الجوال" else "turning the phone over"
        if (hints.isNotEmpty()) col.addView(tv((if (ar) "يوقفه أيضاً: " else "Also stops it: ") + hints.joinToString(if (ar) "، " else ", "), 13f, f.plex, Color.argb(150, 244, 236, 220)),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(24f).toInt(), dp(10f).toInt(), dp(24f).toInt(), dp(30f).toInt()) })
        // the screen runs under the system bars: keep the text and buttons clear of them
        @Suppress("DEPRECATION") col.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun stopAdhan() { AdhanService.stop(this); finish() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && 'v' in (ev?.optString("stop", "pvf") ?: "")) { stopAdhan(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { if (current?.get() === this) current = null; super.onDestroy() }

    /** The app's green, a soft glow and the gold arch, behind the text. */
    private class Backdrop(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, w * .4f, h, Color.parseColor("#11594A"), Color.parseColor("#0A382C"), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p)
            val cx = w / 2; val cy = h * .36f; val r = w * .62f
            p.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(70, 226, 194, 122), 0), null, Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r, p)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = w * .008f; p.color = Color.argb(150, 226, 194, 122)
            val aw = w * .36f; val top = h * .10f; val bot = h * .56f
            val arch = Path().apply { moveTo(cx - aw, bot); lineTo(cx - aw, top + aw * 1.05f); cubicTo(cx - aw, top + aw * .45f, cx - aw * .45f, top + aw * .12f, cx, top)
                cubicTo(cx + aw * .45f, top + aw * .12f, cx + aw, top + aw * .45f, cx + aw, top + aw * 1.05f); lineTo(cx + aw, bot) }
            c.drawPath(arch, p); p.style = Paint.Style.FILL
        }
    }

    companion object {
        private var current: WeakReference<AdhanActivity>? = null
        /** The adhan ended or was stopped elsewhere: close the screen. */
        fun close() { current?.get()?.let { a -> a.runOnUiThread { a.finish() } } }
    }
}
