/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.glide

import helium314.keyboard.latin.glide.GlideDecoder
import helium314.keyboard.latin.glide.GlideKey
import helium314.keyboard.latin.glide.GlideKeyGeometry
import helium314.keyboard.latin.glide.GlideLexicon
import helium314.keyboard.latin.glide.GlideLexiconEntry
import helium314.keyboard.latin.glide.GlidePoint
import java.io.File
import java.util.Random
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * Accuracy benchmark with human-like swipes on a phone-sized QWERTY and a real frequency list.
 * Skipped unless .agent/bench/en_50k.txt (word<space>count lines) exists; writes .agent/bench/result.txt.
 */
class GlideBenchmarkTest {
    private val wordList: File? = listOf("../.agent/bench/en_50k.txt", ".agent/bench/en_50k.txt")
        .map { File(it) }.firstOrNull { it.isFile }

    @Test fun benchmark() {
        val file = wordList ?: return
        val entries = ArrayList<Pair<String, Long>>()
        file.forEachLine { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 2) {
                val word = parts[0].lowercase()
                if (word.all { it in 'a'..'z' } && word.length >= 2) entries.add(word to parts[1].toLong())
            }
        }
        val geometry = qwerty()
        val maxLn = ln(entries.first().second.toDouble())
        val minLn = ln(entries.last().second.toDouble())
        val lexicon = GlideLexicon.fromEntries(entries.map { (w, c) ->
            GlideLexiconEntry(w, (255 * (ln(c.toDouble()) - minLn) / (maxLn - minLn)).toInt().coerceIn(1, 255))
        }).compile(geometry, "en")

        // test words sampled ~ usage frequency, 3+ letters
        val pool = entries.filter { it.first.length >= 3 }
        val weights = pool.map { it.second.toDouble() }
        val total = weights.sum()
        val trials = 700
        val sweep = StringBuilder()
        fun evaluate(label: String, detail: Boolean = false): Double {
            val rnd = Random(7)
            var top1 = 0; var top3 = 0; var any = 0
            val fails = ArrayList<String>()
            val byLen = HashMap<Int, IntArray>()
            val t0 = System.nanoTime()
            for (t in 0 until trials) {
                var r = rnd.nextDouble() * total
                var idx = 0
                while (idx < pool.size - 1 && r > weights[idx]) { r -= weights[idx]; idx++ }
                val word = pool[idx].first
                val pts = swipe(word, geometry, rnd)
                val ranked = GlideDecoder.decode(pts, geometry, lexicon)
                val place = ranked.indexOfFirst { it.word == word }
                val st = byLen.getOrPut(min(word.length, 9)) { IntArray(2) }
                st[1]++
                if (place == 0) { top1++; st[0]++ }
                if (place in 0..2) top3++
                if (place >= 0) any++
                if (place != 0 && fails.size < 40) fails.add("$word -> ${ranked.take(3).joinToString { it.word }}")
            }
            val ms = (System.nanoTime() - t0) / 1e6 / trials
            sweep.append("$label top1=${pct(top1, trials)} top3=${pct(top3, trials)} inList=${pct(any, trials)} ms=${"%.1f".format(ms)}\n")
            if (detail) {
                sweep.append("byLen: " + byLen.toSortedMap().entries.joinToString { "${it.key}:${pct(it.value[0], it.value[1])}" } + "\n")
                sweep.append("fails:\n" + fails.joinToString("\n") + "\n")
            }
            return top1 * 1.0 + top3 * 0.5
        }
        evaluate("defaults")
        for (ew in listOf(1.5f, 2.2f, 2.8f)) { GlideDecoder.endpointWidths = ew; evaluate("endpointWidths=$ew") }
        GlideDecoder.endpointWidths = 2.2f
        for (fl in listOf(240, 900, 1600)) { GlideDecoder.fullLimit = fl; evaluate("fullLimit=$fl") }
        GlideDecoder.fullLimit = 900
        evaluate("final", detail = true)
        File(wordList.parentFile, "result.txt").writeText(sweep.toString())
    }

    private fun pct(a: Int, b: Int) = "%.1f%%".format(100.0 * a / b)

    private fun qwerty(): GlideKeyGeometry {
        val w = 108f; val h = 150f
        val rows = arrayOf("qwertyuiop" to 0.5f, "asdfghjkl" to 1f, "zxcvbnm" to 2f)
        val keys = ArrayList<GlideKey>()
        for ((row, pair) in rows.withIndex()) {
            val (letters, offset) = pair
            for (col in letters.indices) keys.add(GlideKey(letters[col].code, (col + offset) * w, row * h + h / 2f, w, h))
        }
        return GlideKeyGeometry.fromKeys(keys)
    }

    /** Human-like swipe: aim errors, shared thumb bias, corner cutting (spline), slower at turns, sampling noise. */
    private fun swipe(word: String, g: GlideKeyGeometry, rnd: Random): List<GlidePoint> {
        val letters = ArrayList<Char>()
        for (c in word) if (letters.isEmpty() || letters.last() != c) letters.add(c)
        val w = g.keyWidth; val h = g.keyHeight
        val biasX = rnd.nextGaussian() * 0.10 * w; val biasY = rnd.nextGaussian() * 0.10 * h
        val way = ArrayList<DoubleArray>()
        for ((i, c) in letters.withIndex()) {
            val k = g.indexOf(c.code)
            var x = g.centerX[k] + biasX + rnd.nextGaussian() * 0.20 * w
            var y = g.centerY[k] + biasY + rnd.nextGaussian() * 0.20 * h
            // occasionally a middle letter is only grazed
            if (i in 1 until letters.size - 1 && rnd.nextDouble() < 0.05) {
                val prev = way.last(); x = (x + prev[0]) / 2; y = (y + prev[1]) / 2
            }
            way.add(doubleArrayOf(x, y))
        }
        if (way.size == 1) way.add(doubleArrayOf(way[0][0] + 1, way[0][1]))
        // Catmull-Rom (centripetal-ish uniform) through waypoints
        val dense = ArrayList<DoubleArray>()
        val turn = ArrayList<Double>()
        for (i in 0 until way.size - 1) {
            val p0 = way[max(i - 1, 0)]; val p1 = way[i]; val p2 = way[i + 1]; val p3 = way[min(i + 2, way.size - 1)]
            val steps = max(6, (hypot(p2[0] - p1[0], p2[1] - p1[1]) / 6).toInt())
            for (s in 0 until steps) {
                val t = s / steps.toDouble()
                val x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t * t + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t * t * t)
                val y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t * t + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t * t * t)
                dense.add(doubleArrayOf(x, y))
                turn.add(1.0 - minOf(t, 1 - t) * 2) // 1 near waypoints
            }
        }
        dense.add(way.last()); turn.add(1.0)
        // time sampling: speed ~ 0.9 w per 60ms, slowed to 45% near waypoints
        val out = ArrayList<GlidePoint>()
        var time = 0.0
        out.add(GlidePoint(dense[0][0].toFloat(), dense[0][1].toFloat(), 0))
        var acc = 0.0
        val baseSpeed = (0.9 * w / 60.0) * (0.8 + 0.4 * rnd.nextDouble())
        for (i in 1 until dense.size) {
            val d = hypot(dense[i][0] - dense[i - 1][0], dense[i][1] - dense[i - 1][1])
            val speed = baseSpeed * (1.0 - 0.55 * turn[i])
            val dt = d / max(speed, 1e-3)
            time += dt; acc += dt
            if (acc >= 16.0) {
                acc = 0.0
                out.add(GlidePoint((dense[i][0] + rnd.nextGaussian() * 1.5).toFloat(), (dense[i][1] + rnd.nextGaussian() * 1.5).toFloat(), time.toInt()))
            }
        }
        out.add(GlidePoint(dense.last()[0].toFloat(), dense.last()[1].toFloat(), time.toInt() + 16))
        return out
    }
}
