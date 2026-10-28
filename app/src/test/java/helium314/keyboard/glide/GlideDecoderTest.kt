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
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Synthetic QWERTY: 10 columns, 3 rows, 100×150 px keys. No Android classes.
 */
class GlideDecoderTest {
    @Test fun cleanPathRanksTargetFirst() {
        val geometry = qwerty()
        val lexicon = lexicon(geometry)
        assertTrue(lexicon.wordCount >= 200, "lexicon has ${lexicon.wordCount} words")
        for (word in TARGETS) {
            val ranked = GlideDecoder.decode(path(word, geometry, Random(1), 0.0), geometry, lexicon)
            val top = ranked.take(4).joinToString { it.word }
            assertTrue(ranked.firstOrNull()?.word == word, "$word ranked as $top")
        }
    }

    @Test fun noisyPathRanksTargetInTopThree() {
        val geometry = qwerty()
        val lexicon = lexicon(geometry)
        val hits = IntArray(TARGETS.size)
        val misses = ArrayList<String>()
        for (trial in 0 until TRIALS) {
            for (index in TARGETS.indices) {
                val word = TARGETS[index]
                val random = Random(20261029L + trial * 100L + index)
                val ranked = GlideDecoder.decode(path(word, geometry, random, 8.0), geometry, lexicon)
                val place = ranked.indexOfFirst { it.word == word }
                if (place in 0..2) {
                    hits[index]++
                } else if (misses.size < 12) {
                    misses.add("$word#$trial:${ranked.take(3).joinToString { it.word }}")
                }
            }
        }
        val total = hits.sum()
        val attempts = TRIALS * TARGETS.size
        val detail = TARGETS.indices.joinToString { "${TARGETS[it]}=${hits[it]}/$TRIALS" }
        assertTrue(total * 10 >= attempts * 9, "hits=$total/$attempts $detail e.g. $misses")
    }

    private fun lexicon(geometry: GlideKeyGeometry) =
        GlideLexicon.fromEntries(WORDS.map { (word, freq) -> GlideLexiconEntry(word, freq) }).compile(geometry, "en")

    private fun qwerty(): GlideKeyGeometry {
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val keys = ArrayList<GlideKey>()
        for (row in rows.indices) {
            val letters = rows[row]
            for (col in letters.indices) {
                keys.add(GlideKey(
                    letters[col].code,
                    col * KEY_WIDTH + KEY_WIDTH / 2f,
                    row * KEY_HEIGHT + KEY_HEIGHT / 2f,
                    KEY_WIDTH,
                    KEY_HEIGHT,
                ))
            }
        }
        return GlideKeyGeometry.fromKeys(keys)
    }

    private fun path(word: String, geometry: GlideKeyGeometry, random: Random, jitter: Double): List<GlidePoint> {
        val centers = ArrayList<Pair<Float, Float>>()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            val index = geometry.indexOf(cp)
            check(index >= 0) { word }
            centers.add(geometry.centerX[index] to geometry.centerY[index])
        }
        val out = ArrayList<GlidePoint>(centers.size * 8)
        var time = 0
        fun sample(x: Float, y: Float) {
            val jx = if (jitter == 0.0) 0f else (gauss(random) * jitter).coerceIn(-18.0, 18.0).toFloat()
            val jy = if (jitter == 0.0) 0f else (gauss(random) * jitter).coerceIn(-18.0, 18.0).toFloat()
            out.add(GlidePoint(x + jx, y + jy, time))
        }
        sample(centers[0].first, centers[0].second)
        for (s in 1 until centers.size) {
            val x0 = centers[s - 1].first
            val y0 = centers[s - 1].second
            val x1 = centers[s].first
            val y1 = centers[s].second
            val steps = max(1, (hypot(x1 - x0, y1 - y0) / 14f).toInt())
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                time += 12
                sample(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
            if (s != centers.lastIndex) {
                repeat(3) {
                    time += 36
                    sample(x1, y1)
                }
            }
        }
        return out
    }

    private fun gauss(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    private companion object {
        const val KEY_WIDTH = 100f
        const val KEY_HEIGHT = 150f
        const val TRIALS = 50
        val TARGETS = arrayOf("hello", "world", "keyboard", "thanks", "good", "morning", "kumusta", "salamat")

        val WORDS = listOf(
            "the" to 240, "of" to 220, "and" to 230, "to" to 225, "in" to 210,
            "is" to 200, "you" to 205, "that" to 190, "it" to 185, "for" to 195,
            "on" to 180, "are" to 175, "with" to 188, "as" to 160, "his" to 140,
            "they" to 170, "be" to 165, "at" to 172, "one" to 150, "have" to 168,
            "this" to 155, "from" to 148, "or" to 140, "had" to 130, "by" to 125,
            "not" to 160, "but" to 145, "what" to 135, "all" to 150, "were" to 128,
            "we" to 120, "when" to 110, "your" to 140, "can" to 135, "said" to 90,
            "there" to 115, "use" to 80, "each" to 70, "which" to 125, "she" to 100,
            "do" to 160, "how" to 95, "their" to 110, "if" to 150, "will" to 145,
            "up" to 100, "other" to 80, "about" to 120, "out" to 110, "many" to 75,
            "then" to 90, "them" to 100, "these" to 85, "so" to 140, "some" to 95,
            "her" to 90, "would" to 165, "make" to 100, "like" to 130, "time" to 120,
            "has" to 110, "look" to 70, "two" to 60, "more" to 175, "write" to 50,
            "go" to 190, "see" to 100, "number" to 40, "no" to 150, "way" to 90,
            "could" to 150, "people" to 100, "my" to 140, "than" to 90, "first" to 70,
            "water" to 40, "been" to 90, "call" to 50, "who" to 80, "its" to 100,
            "now" to 120, "find" to 60, "long" to 50, "down" to 70, "day" to 80,
            "did" to 90, "get" to 130, "come" to 110, "made" to 60, "may" to 70,
            "part" to 40, "over" to 50, "new" to 100, "sound" to 20, "take" to 90,
            "only" to 70, "little" to 50, "work" to 80, "know" to 140, "place" to 40,
            "year" to 60, "live" to 50, "me" to 120, "back" to 70, "give" to 60,
            "most" to 50, "very" to 80, "after" to 70, "thing" to 60, "our" to 90,
            "just" to 100, "name" to 50, "good" to 180, "sentence" to 10, "man" to 40,
            "think" to 110, "say" to 70, "great" to 40, "help" to 120, "through" to 30,
            "much" to 60, "before" to 40, "line" to 20, "right" to 50, "too" to 80,
            "mean" to 40, "old" to 50, "any" to 90, "same" to 40, "tell" to 30,
            "boy" to 20, "follow" to 10, "came" to 40, "want" to 90, "show" to 50,
            "also" to 60, "around" to 30, "form" to 20, "three" to 25, "small" to 40,
            "set" to 30, "put" to 70, "end" to 40, "does" to 50, "another" to 30,
            "well" to 90, "large" to 20, "must" to 40, "big" to 30, "even" to 50,
            "such" to 30, "because" to 40, "turn" to 20, "here" to 70, "why" to 60,
            "ask" to 30, "went" to 40, "men" to 20, "read" to 50, "need" to 80,
            "land" to 20, "different" to 15, "home" to 70, "us" to 90, "move" to 30,
            "try" to 60, "kind" to 40, "hand" to 30, "picture" to 15, "again" to 50,
            "change" to 20, "off" to 40, "play" to 50, "spell" to 10, "air" to 20,
            "away" to 40, "animal" to 10, "house" to 40, "point" to 30, "page" to 15,
            "letter" to 20, "mother" to 25, "answer" to 20, "found" to 40, "study" to 15,
            "still" to 60, "learn" to 20, "should" to 70, "world" to 150, "high" to 30,
            "every" to 40, "near" to 20, "add" to 30, "food" to 100, "between" to 15,
            "own" to 40, "below" to 10, "country" to 20, "plant" to 10, "last" to 40,
            "school" to 30, "father" to 25, "keep" to 40, "tree" to 15, "never" to 50,
            "start" to 45, "city" to 35, "earth" to 25, "eye" to 20, "light" to 30,
            "thought" to 35, "head" to 25, "under" to 30, "story" to 20, "saw" to 25,
            "left" to 40, "few" to 35, "while" to 30, "along" to 15, "might" to 45,
            "close" to 20, "night" to 30, "real" to 25, "life" to 40, "always" to 35,
            "those" to 30, "both" to 25, "paper" to 10, "together" to 15, "got" to 40,
            "group" to 15, "often" to 20, "run" to 35, "important" to 10, "until" to 20,
            "children" to 15, "side" to 25, "feet" to 15, "car" to 30, "mile" to 10,
            "hello" to 160, "keyboard" to 140, "thanks" to 155, "morning" to 145,
            "kumusta" to 130, "salamat" to 130, "hell" to 100, "held" to 40,
            "yellow" to 70, "word" to 130, "wound" to 30, "thank" to 140,
            "thinks" to 40, "god" to 60, "gold" to 80, "gone" to 70,
            "moon" to 50, "coming" to 60, "key" to 70, "board" to 50,
        )
    }
}
