// Reads data/vectors.csv (plain CSV on stdin), prints "values N" and one line per mismatch: ds|date|lat|lon|zone|field|want
import org.ontimeprayer.UmmAlQura
fun main() {
    val ev = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha", "eid")
    var n = 0L; val out = StringBuilder()
    val r = System.`in`.bufferedReader(); r.readLine()
    while (true) {
        val line = r.readLine() ?: break
        val f = line.split(',')
        val t = UmmAlQura.prayerTimes(f[1], f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5] == "1")
        val got = if (t == null) null else listOf(t.fajr, t.sunrise, t.dhuhr, t.asr, t.maghrib, t.isha, t.eid)
        for (i in 0 until 7) { val v = f[6 + i]; if (v.isEmpty()) continue; n++
            if (got == null || got[i] != v) out.append(listOf(f[0], f[1], f[2], f[3], f[4], ev[i], v).joinToString("|")).append('\n') }
    }
    print(out); println("values $n")
}
