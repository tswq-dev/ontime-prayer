package com.tswq.ontimeprayer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject

object Alerts {
    const val CH_ADHAN = "adhan"
    /** Only opens the adhan screen when the phone is locked (some phones ignore the full-screen intent of a service's notification). */
    const val CH_SCREEN = "adhan_screen"
    const val SCREEN_ID = 0x0ADA5C
    const val CH_VIBRATE = "vibrate"
    const val CH_REMIND = "remind"
    const val CH_EXIT = "exit"
    const val CH_AFTER = "after"
    const val CH_SILENT = "quiet"
    const val CH_LATER = "later"

    /** The prayer whose notification is showing the "remind me in" choices. */
    @Volatile var menuPk: String? = null
    /** Which durations the open menu shows: "later" (remind me in…) or "before" (before the time ends). */
    @Volatile var menuKind: String = "later"
    private const val RESTORE_CODE = 900
    private const val STALE_CODE = 901

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ar = ctx.resources.configuration.locales[0].language == "ar"
        fun t(a: String, e: String) = if (ar) a else e
        nm.deleteNotificationChannel("tone") // the soft tone option is gone (0.4)
        // The adhan itself is played by AdhanService, so its channel stays silent.
        nm.createNotificationChannel(NotificationChannel(CH_ADHAN, t("الأذان", "Adhan"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_SCREEN, t("شاشة الأذان", "Adhan screen"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_VIBRATE, t("اهتزاز فقط", "Vibrate only"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 400, 250, 400)
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, t("التذكير والإقامة", "Reminders and iqama"), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_EXIT, t("خروج وقت الصلاة", "End of prayer time"), NotificationManager.IMPORTANCE_HIGH))
        // "Notification only", and reminders whose sound is spoken or off: no sound, no vibration.
        nm.createNotificationChannel(NotificationChannel(CH_SILENT, t("إشعار بدون صوت", "Silent notification"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_LATER, t("ذكّرني بعد", "Remind me in"), NotificationManager.IMPORTANCE_HIGH))
        // What stays in the shade after the adhan (with "remind me" and "I prayed"): no sound of its own.
        nm.createNotificationChannel(NotificationChannel(CH_AFTER, t("بعد الأذان", "After the adhan"), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
    }

    /** Events saved before 0.4 carry only "voice". */
    fun soundOf(ev: JSONObject): String = ev.optString("sound").ifEmpty {
        when (ev.optString("voice")) {
            "makkah", "madinah", "adhan" -> "afasy"; "takbir" -> "takbir_afasy"
            "vibrate" -> "vibrate"; "none" -> "none"; else -> "notify"
        }
    }

    /** [done] is called when nothing more is playing (the receiver keeps the process alive until then). */
    fun fire(ctx: Context, ev: JSONObject, done: () -> Unit = {}) {
        channels(ctx)
        val title = ev.optString("title"); val text = ev.optString("text"); val id = ev.optString("id").hashCode()
        val pk = ev.optString("pk")
        val spoken = ev.optString("rs", "ding") == "speak"; val loud = loud(ev)
        val remCh = when { ev.optString("voice") == "vibrate" -> CH_VIBRATE; ev.optString("rs", "ding") == "ding" && !loud -> CH_REMIND; else -> CH_SILENT }
        var speaking = false
        fun say(name: String, alarm: Boolean = false, then: () -> Unit = {}) { speaking = true; Speaker.play(ctx, name, alarm) { then(); done() } }
        fun quietNote() = post(ctx, if (Exits.hasEx(ev)) prayerBuilder(ctx, ev, CH_SILENT, null) else builder(ctx, CH_SILENT, title, text), noteId(ev))
        when (ev.optString("kind")) {
            "adhan" -> when (val s = soundOf(ev).let { if (it != "none" && WidgetUpdater.takeMute(ctx, ev)) "notify" else it }) {
                "none" -> {}
                "vibrate" -> post(ctx, prayerBuilder(ctx, ev, CH_VIBRATE, null), noteId(ev))
                "notify" -> quietNote()
                // an adhan set to keep to silent mode (ring 0, 0.18): a quiet note, or only vibrate on vibrate mode
                else -> if (s.startsWith("spk_")) {
                    val loudS = ev.optInt("ring", 0) == 1
                    if (!loudS && hush(ctx) == 1) post(ctx, if (Exits.hasEx(ev)) prayerBuilder(ctx, ev, CH_VIBRATE, null) else builder(ctx, CH_VIBRATE, title, text), noteId(ev)) else quietNote()
                    say(s, loudS)
                } else if (ev.optInt("ring", 1) == 0 && hush(ctx) > 0) {
                    post(ctx, prayerBuilder(ctx, ev, if (hush(ctx) == 1) CH_VIBRATE else CH_SILENT, null), noteId(ev))
                } else {
                    val i = Intent(ctx, AdhanService::class.java).putExtra("ev", ev.toString())
                    try {
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                    } catch (e: Exception) {
                        post(ctx, prayerBuilder(ctx, ev, CH_REMIND, null), noteId(ev))
                    }
                }
            }
            "pre", "iqama" -> {
                if (ev.optString("kind") == "pre") preNote(ctx, ev, false) else post(ctx, builder(ctx, remCh, title, text).also { untilNow(it, ev.optLong("until")) }, id)
                if (remCh != CH_VIBRATE) remSound(ev, ev.optString("spk"))?.let { say(it, loud) }
            }
            // the spoken line first, then vibrate mode (vibrate mode would cut it off)
            "silence" -> if (spoken && ringerNormal(ctx)) say(ev.optString("spk")) { silence(ctx, ev.optInt("min", 20), ev) }
                else silence(ctx, ev.optInt("min", 20), ev)
            "exit" -> if (!Prayed.has(ctx, pk)) { exitNote(ctx, ev); remSound(ev, ev.optString("xspk"))?.let { say(it, loud) } }
            // a plain reminder from the page (the last third of the night, fasting tomorrow)
            "note" -> if (ev.optString("key") == "third") thirdNote(ctx, ev, false) else post(ctx, builder(ctx, CH_REMIND, title, text).also { untilNow(it, ev.optLong("until")) }, id)
        }
        if (!speaking) done()
    }

    private fun ringerNormal(ctx: Context) = ctx.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    /** 0: sound on, 1: vibrate, 2: silent or do-not-disturb (what an adhan that keeps to silent mode goes by). */
    private fun hush(ctx: Context): Int {
        val f = ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        if (f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return 2
        return when (ctx.getSystemService(AudioManager::class.java)?.ringerMode) { AudioManager.RINGER_MODE_VIBRATE -> 1; AudioManager.RINGER_MODE_SILENT -> 2; else -> 0 }
    }

    /** Reminders that ring even on silent (0.18): the notification goes out quiet and the app plays the sound on the alarm stream. */
    fun loud(r: JSONObject) = r.optInt("rring") == 1 && r.optString("voice") != "vibrate"

    /** What a reminder plays itself: the spoken line, the tone the user picked, or (ringing on silent) the phone's notification tone. */
    fun remSound(r: JSONObject, spk: String): String? = when (r.optString("rs", "ding")) {
        "speak" -> spk.ifEmpty { null }; "tone" -> "tone_rem"; "ding" -> if (loud(r)) "tone_ding" else null; else -> null
    }

    fun noteId(ev: JSONObject) = ev.optString("pk").ifEmpty { ev.optString("id") }.let { (it.hashCode() and 0x0FFFFFFF) or 0x10000000 }
    fun noteIdOf(pk: String) = (pk.hashCode() and 0x0FFFFFFF) or 0x10000000
    fun exitId(pk: String) = noteIdOf(pk) xor 0x20000000

    fun builder(ctx: Context, channel: String, title: String, text: String): Notification.Builder =
        Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))

    /** A broadcast for a notification button; the data URI keeps each prayer's buttons apart. */
    fun action(ctx: Context, act: String, pk: String, label: String, key: String, value: String): Notification.Action {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(act).setData(Uri.parse("ontime://$act/${Uri.encode(pk)}")).putExtra(key, value)
        val pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, label, pi).build()
    }

    /** The adhan notification: the prayer, then the buttons "Stop" (while it plays), "Remind me before it ends", "I prayed". */
    fun prayerBuilder(ctx: Context, ev: JSONObject, channel: String, stop: PendingIntent?): Notification.Builder {
        val pk = ev.optString("pk"); val ar = Exits.ar(ev); val now = System.currentTimeMillis()
        val withEx = pk.isNotEmpty() && Exits.hasEx(ev)
        if (withEx && menuPk == pk) return if (menuKind == "before") beforeMenu(ctx, ev, channel) else laterMenu(ctx, ev, channel)
        // one reminder per prayer: "before the time ends" or "remind me in…", whichever was picked last
        val rem = if (withEx) Reminders.find(ctx, pk) else null
        val lat = if (withEx) Reminders.find(ctx, pk, "later") else null
        val set = listOfNotNull(rem, lat).minByOrNull { it.optLong("at") }
        val text = if (set != null) setText(set) else ev.optString("text")
        val b = builder(ctx, channel, ev.optString("title"), text)
        val acts = ArrayList<Notification.Action>()
        if (stop != null) acts += Notification.Action.Builder(null, if (ar) "إيقاف" else "Stop", stop).build()
        val btns = buttonsOf(ev)
        // with "Stop" there are three buttons: the long label is shortened so Android does not cut it
        val beforeBtn = { action(ctx, AlarmReceiver.ACTION_BEFORE_MENU, pk, if (ar) "ذكّرني قبل الخروج" else "Before it ends", "ev", ev.toString()) }
        val laterBtn = { action(ctx, AlarmReceiver.ACTION_LATER_MENU, pk, if (ar) "ذكّرني بعد" else "Remind me later", "ev", ev.toString()) }
        val prayBtn = { action(ctx, AlarmReceiver.ACTION_PRAYED, pk, if (ar) "صلّيت" else "I prayed", "pk", pk) }
        val canBefore = withEx && beforeChoices(ev, now).isNotEmpty(); val canLater = withEx && laterChoices(ev, now).isNotEmpty()
        if (withEx && (set != null || ev.optInt("remNote") == 1)) {
            // a reminder is set (or this is the reminder itself): «صلّيت» (praying before it rings cancels it), and the other kind to change it
            acts += prayBtn()
            if (ev.optInt("remNote") == 1) { if (canLater) acts += laterBtn() }
            else if (rem != null) { if ("later" in btns && canLater) acts += laterBtn() }
            else if ("exit" in btns && canBefore) acts += beforeBtn()
        } else for (bt in btns) when (bt) {
            // the user's buttons, in their order
            "exit" -> if (canBefore) acts += beforeBtn()
            "pray" -> if (withEx) acts += prayBtn()
            "later" -> if (canLater) acts += laterBtn()
        }
        acts.take(3).forEach { b.addAction(it) }
        untilNow(b, untilOf(ev))
        return b
    }

    /** The user's buttons, in order: "exit" (before the time ends), "pray", "later" (remind me in…). */
    fun buttonsOf(ev: JSONObject): List<String> = ev.optString("btns", "exit,later").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** The user's "before the time ends" durations whose reminder is still ahead (a minute at least). */
    fun beforeChoices(ev: JSONObject, now: Long): List<Long> {
        val a = ev.optJSONArray("before"); val l = if (a == null) listOf(10L, 20L, 30L) else (0 until a.length()).map { a.optLong(it) }.filter { it > 0 }
        val end = Exits.next(ev, now)?.at ?: return emptyList()
        return l.distinct().sorted().filter { end - it * 60_000L > now + 60_000L }.take(3)
    }

    /** The user's "remind me in" durations that still fall before the time ends. */
    fun laterChoices(ev: JSONObject, now: Long): List<Long> {
        val a = ev.optJSONArray("later"); val l = if (a == null) listOf(15L, 25L, 35L) else (0 until a.length()).map { a.optLong(it) }.filter { it > 0 }
        val end = Exits.next(ev, now)?.at ?: Long.MAX_VALUE
        return l.distinct().sorted().filter { now + it * 60_000L <= end }.take(3)
    }

    /** The notification goes away by itself once its time is over. */
    private fun untilNow(b: Notification.Builder, until: Long) { val left = until - System.currentTimeMillis(); if (until > 0 && left > 0) b.setTimeoutAfter(left) }
    private fun untilOf(ev: JSONObject): Long = ev.optLong("until").takeIf { it > 0 }
        ?: ev.optJSONArray("ex")?.let { a -> if (a.length() > 0) a.optJSONObject(a.length() - 1)?.optLong("at") ?: 0L else 0L } ?: 0L

    /** "Remind me in…": the same notification, its buttons now the durations, with when the time ends. */
    private fun laterMenu(ctx: Context, ev: JSONObject, channel: String): Notification.Builder {
        val pk = ev.optString("pk"); val ar = Exits.ar(ev); val now = System.currentTimeMillis()
        val ex = Exits.next(ev, now)
        val text = (if (ar) "متى أذكّرك؟" else "When should I remind you?") + (if (ex != null) (if (ar) " يخرج ${ex.name} ${ex.clock}" else " ${ex.name} ends at ${ex.clock}") else "")
        val b = builder(ctx, channel, ev.optString("title"), text)
        for (m in laterChoices(ev, now)) {
            val i = Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_LATER).setData(Uri.parse("ontime://later/${Uri.encode(pk)}/$m"))
                .putExtra("ev", ev.toString()).putExtra("mins", m)
            b.addAction(Notification.Action.Builder(null, Exits.short(m, ev), PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        untilNow(b, untilOf(ev))
        return b
    }

    /** "بنذكّرك 5:56 ص · يخرج وقت الفجر 6:16 ص": when the reminder rings, and when the time ends. */
    private fun setText(r: JSONObject): String {
        val at = Exits.clock(r.optLong("at"), r); val ex = Exits.next(r)
        return if (Exits.ar(r)) "بنذكّرك $at" + (if (ex != null) " · يخرج ${ex.name} ${ex.clock}" else "")
               else "Reminder at $at" + (if (ex != null) " · ${ex.name} ends at ${ex.clock}" else "")
    }

    /** "Before the time ends" pressed: the same notification shows how long before (the user's durations). */
    private fun beforeMenu(ctx: Context, ev: JSONObject, channel: String): Notification.Builder {
        val pk = ev.optString("pk"); val ar = Exits.ar(ev); val now = System.currentTimeMillis()
        val ex = Exits.next(ev, now)
        val text = (if (ar) "قبل الخروج بكم أذكّرك؟" else "How long before it ends?") + (if (ex != null) (if (ar) " يخرج ${ex.name} ${ex.clock}" else " ${ex.name} ends at ${ex.clock}") else "")
        val b = builder(ctx, channel, ev.optString("title"), text)
        for (m in beforeChoices(ev, now)) {
            val i = Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_BEFORE).setData(Uri.parse("ontime://before/${Uri.encode(pk)}/$m"))
                .putExtra("ev", ev.toString()).putExtra("mins", m)
            b.addAction(Notification.Action.Builder(null, Exits.short(m, ev), PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        untilNow(b, untilOf(ev))
        return b
    }

    fun showBefore(ctx: Context, ev: JSONObject) {
        val pk = ev.optString("pk"); menuPk = pk; menuKind = "before"
        if (AdhanService.playingPk() == pk) AdhanService.stop(ctx)
        else post(ctx, prayerBuilder(ctx, ev, CH_AFTER, null), noteIdOf(pk))
    }

    /** A "before" duration picked: it replaces a "remind me in" reminder, if there was one. */
    fun before(ctx: Context, ev: JSONObject, mins: Long) {
        val pk = ev.optString("pk"); menuPk = null
        remind(ctx, JSONObject(ev.toString()).put("mins", mins))
        if (AdhanService.playingPk() != pk) post(ctx, prayerBuilder(ctx, ev, CH_AFTER, null), noteIdOf(pk))
    }

    /** "Remind me in…" pressed: the adhan stops (if it plays) and the notification shows the durations. */
    fun showLater(ctx: Context, ev: JSONObject) {
        val pk = ev.optString("pk"); menuPk = pk; menuKind = "later"
        if (AdhanService.playingPk() == pk) AdhanService.stop(ctx)   // what it leaves behind is the notification with the choices
        else post(ctx, prayerBuilder(ctx, ev, CH_AFTER, null), noteIdOf(pk))
    }

    /** A duration picked: a reminder then; it replaces a "before the time ends" one (the last choice is the one kept). */
    fun later(ctx: Context, ev: JSONObject, mins: Long) {
        val pk = ev.optString("pk"); menuPk = null
        val now = System.currentTimeMillis(); val at = now + mins * 60_000L
        val ex = Exits.next(ev, now)
        if (ex == null || at <= ex.at) {
            Reminders.remove(ctx, pk, "x")
            Reminders.put(ctx, JSONObject(ev.toString()).put("at", at).put("kind", "later"))
            Scheduler.reschedule(ctx)
        }
        if (AdhanService.playingPk() == pk) AdhanService.refresh()
        else post(ctx, prayerBuilder(ctx, ev, CH_AFTER, null), noteIdOf(pk))
        WidgetUpdater.refresh(ctx, "actions")
    }

    /** The "remind me in" reminder itself: the prayer, when its time ends, and the user's buttons again. */
    /** Returns the sound the caller should play (it keeps the receiver alive while it plays). */
    fun laterNote(ctx: Context, r: JSONObject, quiet: Boolean = false): String? {
        val ar = Exits.ar(r); val now = System.currentTimeMillis(); val ex = Exits.next(r, now)
        val e = JSONObject(r.toString()).put("remNote", 1).put("title", if (ar) "تذكير: صلاة ${r.optString("title")}" else "Reminder: ${r.optString("title")}")
            .put("text", if (ex != null) (if (ar) "باقي ${leftTxt(leftMin(ex.at, now), r)} على خروج ${ex.name}، ينتهي ${ex.clock}" else "${ex.name} ends at ${ex.clock}, in ${leftTxt(leftMin(ex.at, now), r)}") else r.optString("text"))
        // there is no spoken line for "remind me in", so ringing on silent plays the phone's tone
        val snd = when { r.optString("rs") == "tone" -> "tone_rem"; r.optString("rs", "ding") in setOf("ding", "speak") && loud(r) -> "tone_ding"; else -> null }
        val b = prayerBuilder(ctx, e, if (r.optString("rs", "ding") == "quiet" || snd != null) CH_SILENT else CH_LATER, null)
        if (quiet) b.setOnlyAlertOnce(true)
        post(ctx, b, noteIdOf(r.optString("pk")))
        if (ex != null) tickLater(ctx, noteIdOf(r.optString("pk")), "later", r, ex.at)
        return if (quiet) null else snd
    }

    /** "20 min left until the chosen time for Asr ends", with "I prayed" and "In 10 min". */
    fun exitNote(ctx: Context, r: JSONObject, quiet: Boolean = false) {
        val now = System.currentTimeMillis()
        val ex = Exits.next(r, now) ?: return
        val pk = r.optString("pk"); val ar = Exits.ar(r)
        val left = leftMin(ex.at, now)
        val title = if (ar) "باقي ${Exits.mins(left, r)} على خروج ${ex.name}" else "$left min until ${ex.name} ends"
        val b = builder(ctx, if (r.optString("rs", "ding") == "ding" && !loud(r)) CH_EXIT else CH_SILENT, title, if (ar) "ينتهي ${ex.clock}" else "Ends at ${ex.clock}")
        b.addAction(action(ctx, AlarmReceiver.ACTION_PRAYED, pk, if (ar) "صلّيت" else "I prayed", "pk", pk))
        if (ex.at - now > 11 * 60_000L)
            b.addAction(action(ctx, AlarmReceiver.ACTION_SNOOZE, pk, if (ar) "بعد ${Exits.short(10, r)}" else "In 10 min", "rem", r.toString()))
        untilNow(b, ex.at)
        if (quiet) b.setOnlyAlertOnce(true)
        post(ctx, b, exitId(pk))
        tickLater(ctx, exitId(pk), "exit", r, ex.at)
    }

    // ── notifications that say how long is left: re-posted quietly each time the minutes change, while they are showing ──
    /** Minutes left, rounded up (what the text says). */
    private fun leftMin(at: Long, now: Long) = maxOf(1L, (at - now + 59_999) / 60_000)
    /** "4 س 20 د" for long waits, else "9 دقائق". */
    private fun leftTxt(min: Long, r: JSONObject): String = if (min < 60) Exits.mins(min, r)
        else if (Exits.ar(r)) Exits.digits("${min / 60} س ${min % 60} د", r) else "${min / 60} h ${min % 60} min"

    /** Wakes at the moment the shown minutes drop by one, to show the new count. */
    fun tickLater(ctx: Context, id: Int, kind: String, r: JSONObject, target: Long) {
        val now = System.currentTimeMillis(); if (target - now <= 60_000L) return
        val next = target - (leftMin(target, now) - 1) * 60_000L
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TICK).setData(Uri.parse("ontime://tick/$id"))
            .putExtra("kind", kind).putExtra("ev", r.toString()).putExtra("id", id)
        Scheduler.setExact(am, maxOf(next, now + 1_000L), PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
    /** A minute passed: if the notification is still there (not dismissed, not prayed), show the new count without sound. */
    fun tick(ctx: Context, kind: String, r: JSONObject, id: Int) {
        if (!activeNote(ctx, id)) return
        channels(ctx)
        when (kind) {
            "exit" -> if (!Prayed.has(ctx, r.optString("pk"))) exitNote(ctx, r, true)
            "pre" -> preNote(ctx, r, true)
            "later" -> laterNote(ctx, r, true)
            "silence" -> silenceNote(ctx, r, true)
            "third" -> thirdNote(ctx, r, true)
        }
    }
    private fun remChOf(ev: JSONObject) = when { ev.optString("voice") == "vibrate" -> CH_VIBRATE; ev.optString("rs", "ding") == "ding" && !loud(ev) -> CH_REMIND; else -> CH_SILENT }
    /** "In 9 min: the Asr adhan", counting down to the adhan. */
    private fun preNote(ctx: Context, ev: JSONObject, quiet: Boolean) {
        val until = ev.optLong("until"); val now = System.currentTimeMillis(); val ar = Exits.ar(ev); val id = ev.optString("id").hashCode()
        val isP = ev.optString("key") in setOf("fajr", "dhuhr", "asr", "maghrib", "isha"); val nm = ev.optString("title")
        val text = if (until <= now) ev.optString("text") else { val m = leftMin(until, now); if (ar) "بعد ${Exits.mins(m, ev)}: " + (if (isP) "أذان " else "") + nm else "In $m min: $nm" }
        val b = builder(ctx, remChOf(ev), nm, text).also { untilNow(it, until) }
        if (quiet) b.setOnlyAlertOnce(true)
        post(ctx, b, id)
        tickLater(ctx, id, "pre", ev, until)
    }
    /** Phone on vibrate after the iqama: when the sound comes back. */
    private fun silenceNote(ctx: Context, r: JSONObject, quiet: Boolean) {
        val until = r.optLong("until"); val now = System.currentTimeMillis(); val ar = Exits.ar(r); val m = leftMin(until, now)
        val text = if (ar) "يرجع الصوت بعد ${Exits.mins(m, r)}" else "Sound back in $m min"
        val b = builder(ctx, CH_REMIND, if (ar) "الجوال على الاهتزاز" else "Phone on vibrate", text)
        if (quiet) b.setOnlyAlertOnce(true)
        post(ctx, b, RESTORE_CODE)
        tickLater(ctx, RESTORE_CODE, "silence", r, until)
    }
    /** The last third of the night: a silent notification (always), with how long until Fajr. */
    private fun thirdNote(ctx: Context, ev: JSONObject, quiet: Boolean) {
        val until = ev.optLong("until"); val now = System.currentTimeMillis(); val ar = Exits.ar(ev); val id = ev.optString("id").hashCode()
        val text = if (until <= now) ev.optString("text") else { val m = leftMin(until, now); if (ar) "باقي ${leftTxt(m, ev)} على الفجر" else "${leftTxt(m, ev)} until Fajr" }
        val b = builder(ctx, CH_SILENT, ev.optString("title"), text).also { untilNow(it, until) }
        if (quiet) b.setOnlyAlertOnce(true)
        post(ctx, b, id)
        tickLater(ctx, id, "third", ev, until)
    }

    /** "Remind me before it ends": from the adhan notification or from the app. */
    fun remind(ctx: Context, ev: JSONObject) {
        val now = System.currentTimeMillis()
        val ex = Exits.next(ev, now) ?: return
        val pk = ev.optString("pk")
        val at = ex.at - ev.optInt("mins", 20) * 60_000L
        if (at <= now + 5_000) { exitNote(ctx, ev); return }
        Reminders.remove(ctx, pk, "later")   // one reminder per prayer: this one replaces "remind me in…"
        Reminders.put(ctx, JSONObject(ev.toString()).put("at", at).put("kind", "x"))
        Scheduler.reschedule(ctx)
        // swap the button for a confirmation
        if (AdhanService.playingPk() == pk) AdhanService.refresh()
        else if (activeNote(ctx, noteIdOf(pk))) post(ctx, prayerBuilder(ctx, ev, CH_AFTER, null), noteIdOf(pk))
        WidgetUpdater.refresh(ctx, "actions")
    }

    /** Cancel a reminder from the app: the adhan notification gets its "Remind me" button back. */
    fun unremind(ctx: Context, pk: String) {
        val r = Reminders.find(ctx, pk)
        Reminders.remove(ctx, pk)
        Scheduler.reschedule(ctx)
        if (AdhanService.playingPk() == pk) AdhanService.refresh()
        else if (r != null && activeNote(ctx, noteIdOf(pk))) post(ctx, prayerBuilder(ctx, r, CH_AFTER, null), noteIdOf(pk))
        WidgetUpdater.refresh(ctx, "actions")
    }

    fun snooze(ctx: Context, r: JSONObject) {
        val now = System.currentTimeMillis()
        val ex = Exits.next(r, now) ?: return
        Reminders.put(ctx, JSONObject(r.toString()).put("at", minOf(now + 10 * 60_000L, ex.at - 60_000L)).put("kind", "x"))
        cancel(ctx, exitId(r.optString("pk")))
        Scheduler.reschedule(ctx)
    }

    fun prayed(ctx: Context, pk: String) {
        if (menuPk == pk) menuPk = null
        Prayed.add(ctx, pk)
        Reminders.remove(ctx, pk)
        if (AdhanService.playingPk() == pk) AdhanService.dismiss(ctx)
        cancel(ctx, noteIdOf(pk)); cancel(ctx, exitId(pk))
        Scheduler.reschedule(ctx)
        WidgetUpdater.refresh(ctx, "actions"); WidgetUpdater.refresh(ctx, "prayed")
    }

    private fun activeNote(ctx: Context, id: Int): Boolean =
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == id } ?: false }.getOrDefault(false)

    fun post(ctx: Context, b: Notification.Builder, id: Int) {
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(id, b.build()) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context, id: Int) { ctx.getSystemService(NotificationManager::class.java)?.cancel(id) }

    fun notify(ctx: Context, channel: String, title: String, text: String, id: Int) = post(ctx, builder(ctx, channel, title, text), id)

    /** A silent alarm notification whose only job is to open the adhan screen on a locked phone. */
    fun screenNote(ctx: Context, ev: JSONObject) {
        channels(ctx)
        val pi = PendingIntent.getActivity(ctx, 4, Intent(ctx, AdhanActivity::class.java).putExtra("ev", ev.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CH_SCREEN).setSmallIcon(R.drawable.ic_stat).setContentTitle(ev.optString("title")).setContentText(ev.optString("text"))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC).setFullScreenIntent(pi, true).setContentIntent(pi)
            .setOngoing(true).setTimeoutAfter(90_000L)
        post(ctx, b, SCREEN_ID)
    }

    /** The page sends a month of alerts and only the page renews them: when they are about to run out, ask once a day to open the app (0.18.1). */
    fun staleCheck(ctx: Context, last: Long, now: Long, ar: Boolean) {
        val p = Scheduler.prefs(ctx)
        if (last <= 0L || last - now > 3 * 86_400_000L) { if (p.getLong("staleAt", 0L) != 0L) { p.edit().putLong("staleAt", 0L).apply(); cancel(ctx, STALE_CODE) }; return }
        if (now - p.getLong("staleAt", 0L) < 86_400_000L) return
        p.edit().putLong("staleAt", now).apply(); channels(ctx)
        notify(ctx, CH_REMIND, if (ar) "افتح «على وقتها» مرة" else "Open On Time once",
            if (ar) "مواقيت الأذان المحفوظة تخلص قريب. افتح التطبيق يحدّثها لشهر جديد." else "The saved adhan times run out soon. Open the app to renew them for another month.", STALE_CODE)
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("quiet", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Vibrate mode needs no special permission; we only undo it if we set it and it is still on. */
    private fun silence(ctx: Context, minutes: Int, ev: JSONObject) {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try { audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) { return }
        Scheduler.prefs(ctx).edit().putBoolean("weSilenced", true).apply()
        silenceNote(ctx, JSONObject().put("until", System.currentTimeMillis() + minutes * 60_000L).put("lang", ev.optString("lang", "ar")).put("dg", ev.optInt("dg")), false)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, RESTORE_CODE, Intent(ctx, AlarmReceiver::class.java).setAction(Scheduler.ACTION_RESTORE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        Scheduler.setExact(am, System.currentTimeMillis() + minutes * 60_000L, pi)
    }

    fun restoreRinger(ctx: Context) {
        val p = Scheduler.prefs(ctx)
        if (!p.getBoolean("weSilenced", false)) return
        p.edit().putBoolean("weSilenced", false).apply()
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            try { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
        }
        cancel(ctx, RESTORE_CODE)
    }

    /** A bundled recording by name: the adhan voices, or the spoken lines (spk_…). 0 if there is none. */
    fun rawId(ctx: Context, name: String): Int = raw(name).takeIf { it != 0 }
        ?: when {
            name.startsWith("spk_") -> ctx.resources.getIdentifier(name, "raw", ctx.packageName)
            // the other voices (0.14): adhan_<voice>, adhan_<voice>_fajr, adhan_tk1_<voice> (one takbir), adhan_takbir_<voice> (four)
            name.matches(Regex("[a-z0-9_]+")) -> ctx.resources.getIdentifier("adhan_$name", "raw", ctx.packageName)
            else -> 0
        }

    /** Bundled recordings (sources in android/SOUNDS.md). */
    private fun raw(name: String): Int = when (name) {
        "afasy" -> R.raw.adhan_afasy
        "afasy_fajr" -> R.raw.adhan_afasy_fajr
        "qatami" -> R.raw.adhan_qatami
        "takbir_afasy" -> R.raw.adhan_takbir_afasy
        "takbir_qatami" -> R.raw.adhan_takbir_qatami
        else -> 0
    }

    /** What to play: the user's file, a bundled recording, or the phone's notification sound. Second: is it a full recording. */
    fun soundUri(ctx: Context, sound: String, userFile: Boolean = true): Pair<Uri?, Boolean> {
        // the user's own files (file, file2 …) and tones picked from the phone (tone_fajr …, tone_rem for reminders)
        if (Regex("file\\d*").matches(sound) && userFile) Scheduler.prefs(ctx).getString("soundUri_$sound", null)?.let { return Uri.parse(it) to true }
        if (sound.startsWith("tone_")) return (Scheduler.prefs(ctx).getString("soundUri_$sound", null)?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)) to true
        if (sound == "notify") return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) to false
        val id = rawId(ctx, sound).takeIf { it != 0 } ?: R.raw.adhan_afasy
        return Uri.parse("android.resource://${ctx.packageName}/$id") to true
    }

    private var preview: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    fun preview(ctx: Context, sound: String) {
        stopPreview()
        if (sound == "none") return
        if (sound == "vibrate") {
            val v = ctx.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1))
            return
        }
        val uri = soundUri(ctx, sound).first ?: return
        try {
            preview = MediaPlayer().apply {
                setOnErrorListener { _, _, _ -> stopPreview(); true }
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                setOnCompletionListener { stopPreview() }
                prepare(); start()
            }
            main.postDelayed({ stopPreview() }, 12_000)
        } catch (_: Exception) { stopPreview() }
    }

    fun stopPreview() {
        main.removeCallbacksAndMessages(null)
        preview?.let { runCatching { it.stop() }; it.release() }
        preview = null
    }
}
