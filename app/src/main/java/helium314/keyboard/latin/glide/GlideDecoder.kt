/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.glide

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One sample of a glide, in keyboard pixels. [timeMs] is relative to the start of the stroke.
 */
data class GlidePoint(val x: Float, val y: Float, val timeMs: Int)

/** One letter key. [codePoint] is the lowercase letter this key produces. */
data class GlideKey(
    val codePoint: Int,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * Letter-key centers for the keyboard the user is gliding on.
 * Codes are sorted so a code point always maps to the same index.
 */
class GlideKeyGeometry(
    val codes: IntArray,
    val centerX: FloatArray,
    val centerY: FloatArray,
    val keyWidth: Float,
    val keyHeight: Float,
) {
    val keyCount: Int get() = codes.size

    fun indexOf(codePoint: Int): Int {
        var lo = 0
        var hi = codes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val value = codes[mid]
            when {
                value < codePoint -> lo = mid + 1
                value > codePoint -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    companion object {
        fun fromKeys(keys: List<GlideKey>): GlideKeyGeometry {
            if (keys.isEmpty()) return EMPTY
            val unique = LinkedHashMap<Int, GlideKey>()
            for (key in keys) {
                if (key.width <= 0f || key.height <= 0f) continue
                unique.putIfAbsent(key.codePoint, key)
            }
            if (unique.isEmpty()) return EMPTY
            val sorted = unique.values.sortedBy { it.codePoint }
            val codes = IntArray(sorted.size) { sorted[it].codePoint }
            val xs = FloatArray(sorted.size) { sorted[it].centerX }
            val ys = FloatArray(sorted.size) { sorted[it].centerY }
            val widths = FloatArray(sorted.size) { sorted[it].width }
            val heights = FloatArray(sorted.size) { sorted[it].height }
            widths.sort()
            heights.sort()
            return GlideKeyGeometry(codes, xs, ys, widths[widths.size / 2], heights[heights.size / 2])
        }

        val EMPTY = GlideKeyGeometry(IntArray(0), FloatArray(0), FloatArray(0), 1f, 1f)
    }
}

data class GlideResult(val word: String, val score: Int)

/**
 * SHARK2-style glide decoder (Kristensson & Zhai): resample the stroke, keep words whose
 * first and last letters sit near the stroke ends, then score each survivor by shape
 * (translation- and scale-normalized), absolute location, dwells where the finger slowed
 * or turned, and dictionary frequency.
 *
 * The hot path reuses [Scratch] and does not allocate per candidate. Safe to call from
 * the suggestion thread; a lock serializes the reused buffers.
 */
object GlideDecoder {
    const val SAMPLE_COUNT = 64
    private const val MAX_RESULTS = 6
    @Volatile @JvmField var fullLimit = 300
    private const val OUTPUT_SCALE = 1_000_000f
    @Volatile @JvmField var endpointWidths = 1.6f
    private const val LOCATION_TOLERANCE_WIDTHS = 0.5f
    private const val MIN_LENGTH_RATIO = 0.38f
    private const val MAX_LENGTH_RATIO = 2.5f
    private const val DWELL_COVER_WIDTHS = 0.75f

    /** Strength of the word-frequency prior; 0 keeps the legacy weak prior. Public for benchmarks. */
    @Volatile @JvmField var langBeta = 1.6f
    // scoring scales, public so the benchmark can tune them
    @Volatile @JvmField var shapeScale = 0.22f
    @Volatile @JvmField var pointScale = 0.42f
    @Volatile @JvmField var visitScale = 0.33f
    @Volatile @JvmField var shapeWeight = 0.352f
    @Volatile @JvmField var pointMix = 0.95f
    @Volatile @JvmField var lengthScale = 0.35f
    @Volatile @JvmField var lengthMix = 0.50f
    @Volatile @JvmField var dwellMin = 0.55f

    private val scratchLock = Any()
    private val scratch = Scratch()

    fun decode(
        points: List<GlidePoint>,
        geometry: GlideKeyGeometry,
        lexicon: CompiledGlideLexicon,
        previousWord: String? = null,
        blockOffensive: Boolean = true,
    ): List<GlideResult> {
        if (points.size < 2 || geometry.keyCount == 0 || lexicon.wordCount == 0) return emptyList()
        synchronized(scratchLock) {
            return decodeLocked(points, geometry, lexicon, previousWord, blockOffensive)
        }
    }

    private fun decodeLocked(
        points: List<GlidePoint>,
        geometry: GlideKeyGeometry,
        lexicon: CompiledGlideLexicon,
        previousWord: String?,
        blockOffensive: Boolean,
    ): List<GlideResult> {
        val width = geometry.keyWidth.coerceAtLeast(1f)
        val s = scratch
        s.ensure(points.size, lexicon.wordCount, geometry.keyCount)
        val n = points.size
        for (i in 0 until n) {
            s.srcX[i] = points[i].x
            s.srcY[i] = points[i].y
            s.srcT[i] = points[i].timeMs
        }
        val endX = s.srcX[n - 1]
        val endY = s.srcY[n - 1]
        val endT = s.srcT[n - 1]
        var kept = dedup(s.srcX, s.srcY, s.srcT, n, max(2f, width * 0.08f))
        if (kept < 1) return emptyList()
        s.srcX[kept - 1] = endX
        s.srcY[kept - 1] = endY
        s.srcT[kept - 1] = endT
        if (kept < 2) return emptyList()
        smooth(s.srcX, s.srcY, s.tmpX, s.tmpY, kept)
        val userLength = resample(s.srcX, s.srcY, kept, s.userX, s.userY, s.cum)
        if (userLength < width * 0.45f) return emptyList()
        writeNormalized(s.userX, s.userY, s.normX, s.normY)
        val dwells = collectDwells(s, kept, width)

        val limit2 = (endpointWidths * width) * (endpointWidths * width)
        val sx = s.userX[0]
        val sy = s.userY[0]
        val ex = s.userX[SAMPLE_COUNT - 1]
        val ey = s.userY[SAMPLE_COUNT - 1]
        var startCount = 0
        var endCount = 0
        val keyCount = geometry.keyCount
        for (k in 0 until keyCount) {
            if (dist2(sx, sy, geometry.centerX[k], geometry.centerY[k]) <= limit2) s.startKeys[startCount++] = k
            if (dist2(ex, ey, geometry.centerX[k], geometry.centerY[k]) <= limit2) s.endKeys[endCount++] = k
        }
        if (startCount == 0 || endCount == 0) return emptyList()

        var candidates = 0
        for (a in 0 until startCount) {
            val firstKey = s.startKeys[a]
            for (b in 0 until endCount) {
                val bucket = firstKey * keyCount + s.endKeys[b]
                var index = lexicon.bucketStart[bucket]
                val bucketEnd = lexicon.bucketStart[bucket + 1]
                while (index < bucketEnd) {
                    val word = lexicon.bucketWords[index]
                    index++
                    if (blockOffensive && lexicon.offensive[word]) continue
                    val cheap = cheapScore(lexicon, word, geometry, userLength, width, sx, sy, ex, ey)
                    if (cheap < 0f) continue
                    if (candidates >= s.candIds.size) break
                    s.candIds[candidates] = word
                    s.candCheap[candidates] = cheap
                    candidates++
                }
            }
        }
        if (candidates == 0) return emptyList()
        val full = selectLowest(s.candIds, s.candCheap, candidates, fullLimit)

        val bestScore = FloatArray(MAX_RESULTS)
        val bestFreq = IntArray(MAX_RESULTS)
        val bestWord = IntArray(MAX_RESULTS)
        var bestCount = 0
        for (c in 0 until full) {
            val word = s.candIds[c]
            val score = scoreWord(lexicon, word, geometry, s, userLength, width, dwells, previousWord)
            if (score.isNaN()) continue
            val freq = lexicon.frequency[word]
            var pos = bestCount
            for (i in 0 until bestCount) {
                val better = score > bestScore[i] + 1e-5f ||
                    (abs(score - bestScore[i]) <= 1e-5f && freq > bestFreq[i])
                if (better) {
                    pos = i
                    break
                }
            }
            if (pos >= MAX_RESULTS) continue
            val last = if (bestCount < MAX_RESULTS) bestCount else MAX_RESULTS - 1
            for (j in last downTo pos + 1) {
                bestScore[j] = bestScore[j - 1]
                bestFreq[j] = bestFreq[j - 1]
                bestWord[j] = bestWord[j - 1]
            }
            bestScore[pos] = score
            bestFreq[pos] = freq
            bestWord[pos] = word
            if (bestCount < MAX_RESULTS) bestCount++
        }
        if (bestCount == 0 || bestScore[0] < 0.05f * (if (langBeta > 0f) exp(-langBeta) else 1f)) return emptyList()
        val out = ArrayList<GlideResult>(bestCount)
        for (i in 0 until bestCount) {
            val scaled = (bestScore[i] * OUTPUT_SCALE).toInt().coerceAtLeast(1)
            out.add(GlideResult(lexicon.words[bestWord[i]], scaled))
        }
        return out
    }

    private fun cheapScore(
        lexicon: CompiledGlideLexicon,
        word: Int,
        geometry: GlideKeyGeometry,
        userLength: Float,
        width: Float,
        sx: Float,
        sy: Float,
        ex: Float,
        ey: Float,
    ): Float {
        val from = lexicon.indexOffset[word]
        val to = lexicon.indexOffset[word + 1]
        if (to - from < 2) return -1f
        var length = 0f
        var px = geometry.centerX[lexicon.keyIndices[from]]
        var py = geometry.centerY[lexicon.keyIndices[from]]
        for (i in from + 1 until to) {
            val x = geometry.centerX[lexicon.keyIndices[i]]
            val y = geometry.centerY[lexicon.keyIndices[i]]
            length += hypot(x - px, y - py)
            px = x
            py = y
        }
        if (userLength < 1f || length < 1f) return -1f
        val ratio = length / userLength
        if (ratio < MIN_LENGTH_RATIO || ratio > MAX_LENGTH_RATIO) return -1f
        val first = lexicon.keyIndices[from]
        val last = lexicon.keyIndices[to - 1]
        val start = hypot(sx - geometry.centerX[first], sy - geometry.centerY[first])
        val end = hypot(ex - geometry.centerX[last], ey - geometry.centerY[last])
        return start + end + abs(ln(ratio)) * width
    }

    private fun scoreWord(
        lexicon: CompiledGlideLexicon,
        word: Int,
        geometry: GlideKeyGeometry,
        s: Scratch,
        userLength: Float,
        width: Float,
        dwells: Int,
        previousWord: String?,
    ): Float {
        val from = lexicon.indexOffset[word]
        val to = lexicon.indexOffset[word + 1]
        val count = to - from
        if (count < 2 || count > s.polyX.size) return 0f
        var idealLength = 0f
        for (i in 0 until count) {
            val key = lexicon.keyIndices[from + i]
            s.polyX[i] = geometry.centerX[key]
            s.polyY[i] = geometry.centerY[key]
            if (i > 0) idealLength += hypot(s.polyX[i] - s.polyX[i - 1], s.polyY[i] - s.polyY[i - 1])
        }
        resample(s.polyX, s.polyY, count, s.idealX, s.idealY, s.cum)
        val shape = shapeDistance(s.normX, s.normY, s.idealX, s.idealY)
        val location = locationDistance(s.userX, s.userY, s.idealX, s.idealY, LOCATION_TOLERANCE_WIDTHS * width)
        val visit = visitDistance(lexicon, from, to, geometry, s, LOCATION_TOLERANCE_WIDTHS * width)
        val ratio = if (userLength < 1f) 1f else (idealLength / userLength).coerceIn(MIN_LENGTH_RATIO, MAX_LENGTH_RATIO)
        val shapeSim = exp(-shape / shapeScale)
        val pointSim = exp(-location / (width * pointScale))
        val visitSim = exp(-visit / (width * visitScale))
        val locSim = pointMix * pointSim + (1f - pointMix) * visitSim
        val lengthSim = exp(-abs(ln(ratio)) / lengthScale)
        val spatial = shapeWeight * shapeSim + (1f - shapeWeight) * locSim
        val coverage = dwellCoverage(lexicon, from, to, geometry, s, dwells, width)
        val dwellFactor = if (dwells == 0) 1f else dwellMin + (1f - dwellMin) * coverage
        val freqNorm = lexicon.frequency[word].coerceIn(0, 320) / 320f
        val lang = if (langBeta > 0f) exp(langBeta * (freqNorm - 1f)) else 0.84f + 0.16f * freqNorm
        var bigramAdd = 0f
        if (!previousWord.isNullOrEmpty()) {
            val probability = lexicon.bigram(previousWord, lexicon.words[word])
            if (probability > 0) bigramAdd = 0.05f * (min(probability, 255) / 255f)
        }
        return spatial * ((1f - lengthMix) + lengthMix * lengthSim) * dwellFactor * lang + bigramAdd
    }

    private fun dwellCoverage(
        lexicon: CompiledGlideLexicon,
        from: Int,
        to: Int,
        geometry: GlideKeyGeometry,
        s: Scratch,
        dwells: Int,
        width: Float,
    ): Float {
        if (dwells <= 0) return 1f
        val limit2 = (DWELL_COVER_WIDTHS * width) * (DWELL_COVER_WIDTHS * width)
        var covered = 0
        for (d in 0 until dwells) {
            var best = Float.MAX_VALUE
            for (i in from until to) {
                val key = lexicon.keyIndices[i]
                val d2 = dist2(s.dwellX[d], s.dwellY[d], geometry.centerX[key], geometry.centerY[key])
                if (d2 < best) best = d2
            }
            if (best <= limit2) covered++
        }
        return covered.toFloat() / dwells
    }

    private fun visitDistance(
        lexicon: CompiledGlideLexicon,
        from: Int,
        to: Int,
        geometry: GlideKeyGeometry,
        s: Scratch,
        tolerance: Float,
    ): Float {
        var excess = 0f
        val keys = to - from
        for (i in from until to) {
            val key = lexicon.keyIndices[i]
            val cx = geometry.centerX[key]
            val cy = geometry.centerY[key]
            var best = Float.MAX_VALUE
            for (p in 0 until SAMPLE_COUNT) {
                val d2 = dist2(s.userX[p], s.userY[p], cx, cy)
                if (d2 < best) best = d2
            }
            val dist = sqrt(best)
            if (dist > tolerance) excess += dist - tolerance
        }
        return excess / keys
    }

    private fun locationDistance(
        userX: FloatArray,
        userY: FloatArray,
        idealX: FloatArray,
        idealY: FloatArray,
        tolerance: Float,
    ): Float {
        var excess = 0f
        for (i in 0 until SAMPLE_COUNT) {
            val dist = hypot(userX[i] - idealX[i], userY[i] - idealY[i])
            if (dist > tolerance) excess += dist - tolerance
        }
        return excess / SAMPLE_COUNT
    }

    private fun shapeDistance(userNormX: FloatArray, userNormY: FloatArray, idealX: FloatArray, idealY: FloatArray): Float {
        var cx = 0f
        var cy = 0f
        for (i in 0 until SAMPLE_COUNT) {
            cx += idealX[i]
            cy += idealY[i]
        }
        val inv = 1f / SAMPLE_COUNT
        cx *= inv
        cy *= inv
        var acc = 0f
        for (i in 0 until SAMPLE_COUNT) {
            val dx = idealX[i] - cx
            val dy = idealY[i] - cy
            acc += dx * dx + dy * dy
        }
        val invRms = 1f / sqrt(acc * inv).coerceAtLeast(1e-3f)
        var dist = 0f
        for (i in 0 until SAMPLE_COUNT) {
            val dx = userNormX[i] - (idealX[i] - cx) * invRms
            val dy = userNormY[i] - (idealY[i] - cy) * invRms
            dist += hypot(dx, dy)
        }
        return dist / SAMPLE_COUNT
    }

    private fun writeNormalized(srcX: FloatArray, srcY: FloatArray, dstX: FloatArray, dstY: FloatArray) {
        var cx = 0f
        var cy = 0f
        for (i in 0 until SAMPLE_COUNT) {
            cx += srcX[i]
            cy += srcY[i]
        }
        val inv = 1f / SAMPLE_COUNT
        cx *= inv
        cy *= inv
        var acc = 0f
        for (i in 0 until SAMPLE_COUNT) {
            val dx = srcX[i] - cx
            val dy = srcY[i] - cy
            acc += dx * dx + dy * dy
        }
        val invRms = 1f / sqrt(acc * inv).coerceAtLeast(1e-3f)
        for (i in 0 until SAMPLE_COUNT) {
            dstX[i] = (srcX[i] - cx) * invRms
            dstY[i] = (srcY[i] - cy) * invRms
        }
    }

    /** @return polyline length. Writes [SAMPLE_COUNT] equidistant points, including both ends. */
    private fun resample(
        srcX: FloatArray,
        srcY: FloatArray,
        srcN: Int,
        dstX: FloatArray,
        dstY: FloatArray,
        cum: FloatArray,
    ): Float {
        if (srcN <= 0) return 0f
        if (srcN == 1) {
            for (i in 0 until SAMPLE_COUNT) {
                dstX[i] = srcX[0]
                dstY[i] = srcY[0]
            }
            return 0f
        }
        cum[0] = 0f
        var total = 0f
        for (i in 1 until srcN) {
            total += hypot(srcX[i] - srcX[i - 1], srcY[i] - srcY[i - 1])
            cum[i] = total
        }
        if (total < 1f) {
            for (i in 0 until SAMPLE_COUNT) {
                dstX[i] = srcX[0]
                dstY[i] = srcY[0]
            }
            return total
        }
        var seg = 1
        val last = SAMPLE_COUNT - 1
        for (s in 0 until SAMPLE_COUNT) {
            val target = if (s == last) total else total * s / last
            while (seg < srcN - 1 && cum[seg] < target) seg++
            val prev = seg - 1
            val span = (cum[seg] - cum[prev]).coerceAtLeast(1e-3f)
            val t = ((target - cum[prev]) / span).coerceIn(0f, 1f)
            dstX[s] = srcX[prev] + (srcX[seg] - srcX[prev]) * t
            dstY[s] = srcY[prev] + (srcY[seg] - srcY[prev]) * t
        }
        return total
    }

    private fun dedup(x: FloatArray, y: FloatArray, t: IntArray, n: Int, minDist: Float): Int {
        if (n <= 1) return n
        val min2 = minDist * minDist
        var w = 1
        for (i in 1 until n) {
            val dx = x[i] - x[w - 1]
            val dy = y[i] - y[w - 1]
            if (dx * dx + dy * dy < min2) continue
            x[w] = x[i]
            y[w] = y[i]
            t[w] = t[i]
            w++
        }
        return w
    }

    private fun smooth(x: FloatArray, y: FloatArray, tmpX: FloatArray, tmpY: FloatArray, n: Int) {
        if (n < 3) return
        tmpX[0] = x[0]
        tmpY[0] = y[0]
        for (i in 1 until n - 1) {
            tmpX[i] = 0.25f * x[i - 1] + 0.5f * x[i] + 0.25f * x[i + 1]
            tmpY[i] = 0.25f * y[i - 1] + 0.5f * y[i] + 0.25f * y[i + 1]
        }
        tmpX[n - 1] = x[n - 1]
        tmpY[n - 1] = y[n - 1]
        for (i in 0 until n) {
            x[i] = tmpX[i]
            y[i] = tmpY[i]
        }
    }

    private fun collectDwells(s: Scratch, n: Int, width: Float): Int {
        for (i in s.markUsed.indices) s.markUsed[i] = 0
        if (n < 4) return 0
        var speedCount = 0
        for (i in 0 until n - 1) {
            val dist = hypot(s.srcX[i + 1] - s.srcX[i], s.srcY[i + 1] - s.srcY[i])
            val dt = s.srcT[i + 1] - s.srcT[i]
            s.speeds[speedCount++] = dist / (if (dt <= 0) 1 else dt)
        }
        System.arraycopy(s.speeds, 0, s.sortedSpeeds, 0, speedCount)
        Arrays.sort(s.sortedSpeeds, 0, speedCount)
        val median = s.sortedSpeeds[speedCount / 2].coerceAtLeast(1e-3f)
        var marked = 0
        val startX = s.srcX[0]
        val startY = s.srcY[0]
        val endX = s.srcX[n - 1]
        val endY = s.srcY[n - 1]
        val endZone = width * 0.45f
        for (i in 1 until n - 1) {
            val turn = turnAngle(s.srcX, s.srcY, i)
            val speed = min(s.speeds[i - 1], s.speeds[i])
            val slow = speed < median * 0.40f
            val corner = turn > 0.95f || (turn > 0.55f && slow)
            val pause = speed < median * 0.25f
            if (!corner && !pause) continue
            if (hypot(s.srcX[i] - startX, s.srcY[i] - startY) < endZone) continue
            if (hypot(s.srcX[i] - endX, s.srcY[i] - endY) < endZone) continue
            s.markX[marked] = s.srcX[i]
            s.markY[marked] = s.srcY[i]
            s.markS[marked] = turn + if (slow) 0.45f else 0f
            marked++
        }
        val sep2 = (width * 0.60f) * (width * 0.60f)
        var chosen = 0
        var guard = 0
        while (chosen < s.dwellX.size && guard++ < marked) {
            var best = -1
            var bestS = 0f
            for (i in 0 until marked) {
                if (s.markUsed[i] != 0) continue
                if (s.markS[i] > bestS) {
                    bestS = s.markS[i]
                    best = i
                }
            }
            if (best < 0) break
            s.markUsed[best] = 1
            var near = false
            for (d in 0 until chosen) {
                if (dist2(s.markX[best], s.markY[best], s.dwellX[d], s.dwellY[d]) < sep2) {
                    near = true
                    break
                }
            }
            if (near) continue
            s.dwellX[chosen] = s.markX[best]
            s.dwellY[chosen] = s.markY[best]
            chosen++
        }
        return chosen
    }

    private fun turnAngle(x: FloatArray, y: FloatArray, i: Int): Float {
        val v1x = x[i] - x[i - 1]
        val v1y = y[i] - y[i - 1]
        val v2x = x[i + 1] - x[i]
        val v2y = y[i + 1] - y[i]
        val n1 = hypot(v1x, v1y)
        val n2 = hypot(v2x, v2y)
        if (n1 < 1f || n2 < 1f) return 0f
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
        return acos(cos)
    }

    private fun selectLowest(ids: IntArray, cheap: FloatArray, count: Int, limit: Int): Int {
        if (count <= limit) return count
        for (i in 0 until limit) {
            var best = i
            var bestValue = cheap[i]
            for (j in i + 1 until count) {
                val value = cheap[j]
                if (value < bestValue) {
                    bestValue = value
                    best = j
                }
            }
            if (best != i) {
                val id = ids[i]
                ids[i] = ids[best]
                ids[best] = id
                val cost = cheap[i]
                cheap[i] = cheap[best]
                cheap[best] = cost
            }
        }
        return limit
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private class Scratch {
        var srcX = FloatArray(128)
        var srcY = FloatArray(128)
        var srcT = IntArray(128)
        var tmpX = FloatArray(128)
        var tmpY = FloatArray(128)
        var cum = FloatArray(128)
        var speeds = FloatArray(128)
        var sortedSpeeds = FloatArray(128)
        var markX = FloatArray(128)
        var markY = FloatArray(128)
        var markS = FloatArray(128)
        var markUsed = IntArray(128)
        val userX = FloatArray(SAMPLE_COUNT)
        val userY = FloatArray(SAMPLE_COUNT)
        val normX = FloatArray(SAMPLE_COUNT)
        val normY = FloatArray(SAMPLE_COUNT)
        val idealX = FloatArray(SAMPLE_COUNT)
        val idealY = FloatArray(SAMPLE_COUNT)
        var polyX = FloatArray(64)
        var polyY = FloatArray(64)
        val dwellX = FloatArray(12)
        val dwellY = FloatArray(12)
        var startKeys = IntArray(64)
        var endKeys = IntArray(64)
        var candIds = IntArray(256)
        var candCheap = FloatArray(256)

        fun ensure(src: Int, words: Int, keys: Int) {
            if (srcX.size < src) {
                val n = nextPow(src)
                srcX = FloatArray(n)
                srcY = FloatArray(n)
                srcT = IntArray(n)
                tmpX = FloatArray(n)
                tmpY = FloatArray(n)
                cum = FloatArray(n)
                speeds = FloatArray(n)
                sortedSpeeds = FloatArray(n)
                markX = FloatArray(n)
                markY = FloatArray(n)
                markS = FloatArray(n)
                markUsed = IntArray(n)
            }
            if (startKeys.size < keys) {
                val n = nextPow(keys)
                startKeys = IntArray(n)
                endKeys = IntArray(n)
            }
            if (candIds.size < words) {
                val n = nextPow(words)
                candIds = IntArray(n)
                candCheap = FloatArray(n)
            }
        }

        private fun nextPow(n: Int): Int {
            var size = 16
            while (size < n) size *= 2
            return size
        }
    }
}
