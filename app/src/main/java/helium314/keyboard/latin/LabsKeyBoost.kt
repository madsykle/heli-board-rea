// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Labs-only "predicted key boost".
 *
 * Behaviour is gated twice: the build type must be `labs`, and the Labs-only preference
 * [PREF_KEY] must be on (default off). On any other build type every entry point returns
 * immediately, so release behaviour is unchanged.
 *
 * The prediction is a single letter, computed from the suggestion that is currently on screen
 * (see [onSuggestions]). [boostedKey] turns it into a precedence rule that [KeyDetector] applies
 * at touch-down only: if the touch is inside the target letter's drawn rectangle grown by a small
 * margin, the target wins even when the touch is inside a neighbouring key.
 *
 * Counters are aggregate integers kept in memory only. No typed character, word or key code is
 * ever stored or logged; the target letter is transient state and is cleared on keyboard change,
 * word commit and cursor move.
 */
object LabsKeyBoost {
    const val PREF_KEY = "labs_predicted_key_boost"

    // Tuning constants. Margins are fractions of the key's drawn size, capped in dp so they stay
    // modest on very large keys (tablets, split layouts).
    const val MARGIN_WIDTH_RATIO = 0.12f
    const val MARGIN_HEIGHT_RATIO = 0.12f
    const val MARGIN_MAX_DP = 8f
    const val DELETE_WINDOW_MS = 1_500L

    val isLabsBuild: Boolean = BuildConfig.BUILD_TYPE == "labs"

    /** Aggregate counters, shown on the Labs debug screen. Never contains typed text. */
    data class Stats(
        val totalTaps: Int = 0,
        val boostedTaps: Int = 0,
        val boostedTapsDeleted: Int = 0,
        val plainTapsDeleted: Int = 0,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    @Volatile private var enabled = false
    /** Lower-case code point of the predicted key, 0 when there is none. */
    @Volatile private var targetLetter = 0
    @Volatile private var density = 3f

    // UI-thread-only bookkeeping for the "followed by delete" counter.
    private var lastTapTime = 0L
    private var lastTapBoosted = false
    private var lastTapCounted = false

    /** True when the labs build has the setting on and a predicted key is available. */
    fun isActive(): Boolean = isLabsBuild && enabled && targetLetter != 0

    /** Called from MainKeyboardView.setKeyboard: the target belongs to the old layout. */
    fun onKeyboardChanged(context: Context) {
        if (!isLabsBuild) return
        refresh(context)
        targetLetter = 0
    }

    /** Called after every suggestion-strip update. */
    fun onSuggestions(
        context: Context,
        words: SuggestedWords?,
        typedWord: String,
        composing: Boolean,
    ) {
        if (!isLabsBuild) return
        refresh(context)
        val next = if (enabled) nextLetter(words, typedWord, composing) else 0
        // Letter keys only: punctuation or emoji predictions never become a target.
        targetLetter = if (next != 0 && Character.isLetter(next)) Character.toLowerCase(next) else 0
    }

    /** Called on word commit and cursor move. */
    fun clearTarget() {
        if (!isLabsBuild) return
        targetLetter = 0
    }

    /**
     * Precedence rule used by [helium314.keyboard.keyboard.KeyDetector.detectHitKey] for taps:
     * returns the predicted letter key when [x]/[y] is inside its drawn rectangle grown by the
     * capped margin, otherwise null.
     */
    fun boostedKey(keyboard: Keyboard?, x: Int, y: Int): Key? {
        if (!isLabsBuild || !enabled) return null
        val letter = targetLetter
        if (letter == 0 || keyboard == null) return null
        val key = findLetterKey(keyboard, letter) ?: return null
        val maxMargin = (MARGIN_MAX_DP * density).toInt()
        val marginX = minOf((key.width * MARGIN_WIDTH_RATIO).toInt(), maxMargin)
        val marginY = minOf((key.height * MARGIN_HEIGHT_RATIO).toInt(), maxMargin)
        val inside = x >= key.x - marginX && x < key.x + key.width + marginX &&
                y >= key.y - marginY && y < key.y + key.height + marginY
        return if (inside) key else null
    }

    /** Called once per dispatched tap (not for key repeats or cancelled gestures). */
    fun onKeyDispatched(code: Int, wasBoosted: Boolean, eventTime: Long) {
        if (!isLabsBuild) return
        synchronized(this) {
            var s = _stats.value
            s = s.copy(totalTaps = s.totalTaps + 1)
            if (code == KeyCode.DELETE) {
                val t = lastTapTime
                if (t != 0L && !lastTapCounted && eventTime >= t
                    && eventTime - t <= DELETE_WINDOW_MS
                ) {
                    lastTapCounted = true
                    s = if (lastTapBoosted) {
                        s.copy(boostedTapsDeleted = s.boostedTapsDeleted + 1)
                    } else {
                        s.copy(plainTapsDeleted = s.plainTapsDeleted + 1)
                    }
                }
            } else if (Character.isLetter(code)) {
                if (wasBoosted) s = s.copy(boostedTaps = s.boostedTaps + 1)
                lastTapTime = eventTime
                lastTapBoosted = wasBoosted
                lastTapCounted = false
            } else if (code >= Constants.CODE_SPACE) {
                // Space, digits or punctuation: a delete now is no longer undoing the last letter.
                lastTapTime = 0L
            }
            _stats.value = s
        }
    }

    fun resetStats() {
        if (!isLabsBuild) return
        synchronized(this) {
            _stats.value = Stats()
            lastTapTime = 0L
        }
    }

    private fun refresh(context: Context) {
        density = context.resources.displayMetrics.density
        enabled = context.prefs().getBoolean(PREF_KEY, false)
    }

    /**
     * One letter, or 0. While composing, the typed word must be a prefix of the top suggestion
     * and the suggestion must be longer; otherwise the first letter of the top next-word
     * prediction is used. Non-typing suggestion styles (gesture, recorrection) never produce a
     * target.
     */
    private fun nextLetter(
        words: SuggestedWords?,
        typedWord: String,
        composing: Boolean,
    ): Int {
        if (words == null || words.isEmpty()) return 0
        if (composing) {
            if (typedWord.isEmpty()) return 0
            if (words.mInputStyle != SuggestedWords.INPUT_STYLE_TYPING) return 0
            // Index 0 is the typed word itself. The top suggestion is the first non-typed entry;
            // depending on the "center typed word" setting it can be at index 1 or 2.
            var index = SuggestedWords.INDEX_OF_AUTO_CORRECTION
            var word: String? = null
            while (index < words.size()) {
                val info = words.getInfo(index)
                if (info.getKind() != SuggestedWords.SuggestedWordInfo.KIND_TYPED) {
                    word = info.mWord
                    break
                }
                index++
            }
            if (word == null || word.length <= typedWord.length) return 0
            if (!word.regionMatches(0, typedWord, 0, typedWord.length, ignoreCase = true)) return 0
            return word[typedWord.length].code
        }
        if (!words.isPrediction()) return 0
        val word = words.getWord(0)
        return if (word.isNotEmpty()) word[0].code else 0
    }

    private fun findLetterKey(keyboard: Keyboard, lowerCaseLetter: Int): Key? {
        for (key in keyboard.sortedKeys) {
            if (key.isSpacer) continue
            val code = key.code
            // Letter keys only: never shift, delete, space or action keys.
            if (code < Constants.CODE_SPACE || !Character.isLetter(code)) continue
            if (Character.toLowerCase(code) == lowerCaseLetter) return key
        }
        return null
    }
}
