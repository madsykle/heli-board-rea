// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.random.Random

/**
 * Labs-only "model suggestion order".
 *
 * The feature is gated twice: the build type must be `labs`, and the Labs setting [PREF_KEY] must
 * not be off. Every entry point returns immediately on other build types, so release behaviour is
 * unchanged. The setting is Off / On / A-B test (default A-B). In A-B test the arm is chosen when
 * a field starts and kept for that field; a restart of the same field keeps the arm.
 *
 * The reorder itself happens on the suggestion worker thread, immediately before [SuggestedWords]
 * is constructed. It never runs on the touch path and never runs on the UI thread. The original
 * list is carried on [SuggestedWords] so the existing shadow counters can keep measuring
 * HeliBoard's own order before the model touched it.
 *
 * Counters are aggregate integers only. Typed words, suggestions, previous words and n-grams are
 * held in memory only and are never logged or persisted. Counter writes are batched off the input
 * path; the touch path only changes in-memory integers.
 */
object LabsSuggestionOrder {
    const val PREF_KEY = "labs_model_suggestion_order"
    const val MODE_OFF = "off"
    const val MODE_ON = "on"
    const val MODE_AB = "ab"

    val isLabsBuild: Boolean = BuildConfig.BUILD_TYPE == "labs"

    private const val FLUSH_INTERVAL_MS = 10_000L

    /** Twelve aggregate counters: arm (off/on) x prefix length (1/2) x three measures. */
    enum class Metric {
        OFF_P1_WORDS,
        OFF_P1_FIRST,
        OFF_P1_ANY,
        OFF_P2_WORDS,
        OFF_P2_FIRST,
        OFF_P2_ANY,
        ON_P1_WORDS,
        ON_P1_FIRST,
        ON_P1_ANY,
        ON_P2_WORDS,
        ON_P2_FIRST,
        ON_P2_ANY,
    }

    data class Stats internal constructor(internal val values: List<Int>) {
        constructor() : this(List(Metric.values().size) { 0 })

        operator fun get(metric: Metric): Int = values[metric.ordinal]
    }

    private val PREF_KEYS = arrayOf(
        "labs_order_off_p1_words",
        "labs_order_off_p1_first",
        "labs_order_off_p1_any",
        "labs_order_off_p2_words",
        "labs_order_off_p2_first",
        "labs_order_off_p2_any",
        "labs_order_on_p1_words",
        "labs_order_on_p1_first",
        "labs_order_on_p1_any",
        "labs_order_on_p2_words",
        "labs_order_on_p2_first",
        "labs_order_on_p2_any",
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    @Volatile private var mode = MODE_AB
    @Volatile private var fieldActive = false
    @Volatile private var fieldArmOn = false
    @Volatile private var currentTyped = ""
    @Volatile private var wordValid = false
    @Volatile private var contextWords: List<String> = emptyList()
    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    // UI-thread-only state for the word currently being typed, discarded when the word ends.
    private var lastModeForField: String? = null
    private var firstSeen = false
    private var firstReal: String? = null
    private var firstVisible: List<String> = emptyList()
    private var secondSeen = false
    private var secondReal: String? = null
    private var secondVisible: List<String> = emptyList()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var flushJob: Job? = null

    /** Loads the persisted counters. Safe to call from the Labs screen. */
    fun load(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val preferences = app.prefs()
            _stats.value = Stats(PREF_KEYS.map { preferences.getInt(it, 0) })
            mode = normalizeMode(preferences.getString(PREF_KEY, MODE_AB))
            dirty = false
            loaded = true
        }
        startFlushLoop()
    }

    /** Called when a new editor or text field starts. */
    fun onFieldChanged(context: Context, settingsValues: SettingsValues, restarting: Boolean) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        val newMode = readMode(app)
        val modeChanged = newMode != mode
        mode = newMode
        val eligible = newMode != MODE_OFF && isEligible(settingsValues)
        val keepField = restarting && fieldActive && !modeChanged && lastModeForField == newMode
        clearWord(clearContext = !keepField)
        if (!eligible) {
            fieldActive = false
            lastModeForField = null
            clearWord(clearContext = true)
            return
        }
        fieldActive = true
        if (!keepField) {
            fieldArmOn = if (newMode == MODE_ON) true else Random.nextBoolean()
            lastModeForField = newMode
        }
        LabsShadowEval.requestModelLoad(app)
        startFlushLoop()
    }

    /** Called when the Labs setting changes while the settings screen is open. */
    fun onModeChanged(context: Context, selectedMode: String) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        val newMode = normalizeMode(selectedMode)
        // The shared ListPreference does not write the default value itself, so make sure the
        // "Default" action also persists A-B before applying it.
        if (readMode(app) != newMode) {
            app.prefs().edit { putString(PREF_KEY, newMode) }
        }
        val modeChanged = newMode != mode
        mode = newMode
        if (newMode == MODE_OFF) {
            fieldActive = false
            lastModeForField = null
            clearWord(clearContext = true)
            return
        }
        if (fieldActive && modeChanged) {
            fieldArmOn = if (newMode == MODE_ON) true else Random.nextBoolean()
            lastModeForField = newMode
            clearWord(clearContext = false)
        }
        LabsShadowEval.requestModelLoad(app)
        startFlushLoop()
    }

    /**
     * Called after every suggestion-strip update, with the list exactly as it will be displayed.
     * Captures the first real slot and all visible slots after the first and second letter.
     * No I/O and no model lookup.
     */
    fun onSuggestions(
        settingsValues: SettingsValues,
        words: SuggestedWords?,
        typedWord: String,
        composing: Boolean,
    ) {
        if (!isLabsBuild || !fieldActive) return
        if (!isEligible(settingsValues)) {
            fieldActive = false
            clearWord(clearContext = true)
            return
        }
        if (!composing || typedWord.isEmpty()) return
        if (typedWord != currentTyped || !wordValid || !isOrderingWord(typedWord)) return
        val prefixLength = typedWord.length
        if (prefixLength != 1 && prefixLength != 2) return
        if (words == null || words.mIsObsoleteSuggestions) return
        if (words.mInputStyle != SuggestedWords.INPUT_STYLE_TYPING) return
        val suggestionTypedWord = words.getTypedWordInfo()?.getWord() ?: return
        if (!suggestionTypedWord.equals(currentTyped, ignoreCase = true)) return
        // The counters compare against the same scope in which ordering could run: a previous
        // committed word and a loaded model. An off-arm field is still measured this way.
        if (contextWords.isEmpty() || !LabsShadowEval.isModelLoaded()) return
        val (first, visible) = realSuggestions(words)
        if (first == null) return
        if (prefixLength == 1) {
            firstSeen = true
            firstReal = first
            firstVisible = visible
        } else {
            secondSeen = true
            secondReal = first
            secondVisible = visible
        }
    }

    /** Called after a letter was appended to the composing word. */
    fun onLetterTyped(
        settingsValues: SettingsValues,
        codePoint: Int,
        typedWord: String,
        batchMode: Boolean,
    ) {
        if (!isLabsBuild || !fieldActive) return
        if (!isEligible(settingsValues)) {
            fieldActive = false
            clearWord(clearContext = true)
            return
        }
        if (batchMode || !isWordCodePoint(codePoint)) {
            invalidate(typedWord)
            return
        }
        val character = String(Character.toChars(codePoint))
        if (currentTyped.isEmpty()) {
            currentTyped = typedWord
            wordValid = typedWord.equals(character, ignoreCase = true)
            resetCaptures()
            return
        }
        val expected = currentTyped + character
        if (wordValid && typedWord.equals(expected, ignoreCase = true)) {
            currentTyped = typedWord
        } else {
            invalidate(typedWord)
        }
    }

    /** A backspace means the word is no longer strictly left to right. */
    fun onBackspace() {
        if (!isLabsBuild || !fieldActive) return
        wordValid = false
    }

    /** A cursor reset or a fully deleted word abandons the composing word. */
    fun onWordInvalidated() {
        if (!isLabsBuild) return
        clearWord(clearContext = false)
    }

    /** Called once for every committed word, before the composer is reused. */
    fun onWordCommitted(
        settingsValues: SettingsValues,
        typedWord: String,
        committedWord: String,
    ) {
        if (!isLabsBuild || !fieldActive) return
        if (!isEligible(settingsValues)) {
            fieldActive = false
            clearWord(clearContext = true)
            return
        }
        val validWord = wordValid &&
                currentTyped == typedWord &&
                isOrderingWord(typedWord) &&
                isOrderingWord(committedWord)
        if (validWord) {
            countPrefix(1, firstSeen, firstReal, firstVisible, committedWord)
            countPrefix(2, secondSeen, secondReal, secondVisible, committedWord)
        }
        // Context keeps the real previous words even when they are capitalized or the word was
        // not typed strictly left to right. The model stores lowercase vocabulary.
        val contextWord = committedWord.lowercase(Locale.ROOT)
        if (isContextWord(contextWord)) {
            contextWords = if (contextWords.size < 2) {
                contextWords + contextWord
            } else {
                listOf(contextWords[1], contextWord)
            }
        }
        clearWord(clearContext = false)
    }

    /** Called when the input view is hidden. */
    fun onKeyboardHidden() {
        if (!isLabsBuild) return
        fieldActive = false
        lastModeForField = null
        clearWord(clearContext = true)
        flushJob?.cancel()
        flushJob = null
        ioScope.launch { flushLocked() }
    }

    /**
     * Called on the suggestion worker thread immediately before [SuggestedWords] is built.
     * Returns the pre-reorder list when the displayed list changed, otherwise null.
     *
     * Hard rule: [willAutoCorrect] must already be false. This method never makes a model word the
     * auto-correction target; it only moves a completion into the first real suggestion slot.
     */
    internal fun reorderForTyping(
        typedWord: String,
        suggestions: ArrayList<SuggestedWordInfo>,
        willAutoCorrect: Boolean,
        inputStyle: Int,
        composing: Boolean,
        settingsValues: SettingsValues,
        transformModelWord: (String) -> SuggestedWordInfo,
    ): ArrayList<SuggestedWordInfo>? {
        if (!isLabsBuild || !fieldActive || !fieldArmOn) return null
        if (willAutoCorrect || !composing || inputStyle != SuggestedWords.INPUT_STYLE_TYPING) return null
        if (typedWord.isEmpty() || typedWord != currentTyped || !wordValid || !isOrderingWord(typedWord)) {
            return null
        }
        if (contextWords.isEmpty() || !isEligible(settingsValues) || !LabsShadowEval.isModelLoaded()) {
            return null
        }

        val realIndices = ArrayList<Int>()
        var index = 1 // index 0 is always the typed-word slot and is never touched
        while (index < suggestions.size) {
            if (isRealSuggestion(suggestions[index])) {
                realIndices.add(index)
            }
            index++
        }
        if (realIndices.isEmpty()) return null

        val modelWord = LabsShadowEval.predictForOrdering(typedWord, contextWords) ?: return null
        if (modelWord.equals(typedWord, ignoreCase = true)) return null
        val modelInfo = transformModelWord(modelWord)
        val displayWord = modelInfo.getWord()
        if (displayWord.isEmpty() || displayWord.equals(typedWord, ignoreCase = true)) return null

        val firstReal = realIndices.first()
        val existingAnywhere = suggestions.indexOfFirst { it.getWord() == displayWord }
        if (existingAnywhere == firstReal) return null
        // A matching app-defined, hardcoded or prediction slot is not a real suggestion slot;
        // leave it alone instead of creating a duplicate.
        if (existingAnywhere >= 0 && !realIndices.contains(existingAnywhere)) return null

        val original = ArrayList(suggestions)
        if (existingAnywhere >= 0) {
            val existingInfo = suggestions.removeAt(existingAnywhere)
            suggestions.add(firstReal, existingInfo)
        } else {
            // Keep the slot count: the model word replaces the last real slot.
            suggestions.removeAt(realIndices.last())
            suggestions.add(firstReal, modelInfo)
        }
        return original
    }

    /** Clears the twelve counters and writes zeros. The shadow counters are untouched. */
    fun resetStats(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        synchronized(this) {
            _stats.value = Stats()
            dirty = false
        }
        // Do not let captures taken before the reset be counted afterwards.
        clearWord(clearContext = false)
        ioScope.launch { flushLocked(force = true) }
    }

    private fun countPrefix(
        prefixLength: Int,
        seen: Boolean,
        first: String?,
        visible: List<String>,
        committedWord: String,
    ) {
        if (!seen) return
        val base = if (fieldArmOn) {
            if (prefixLength == 1) Metric.ON_P1_WORDS.ordinal else Metric.ON_P2_WORDS.ordinal
        } else {
            if (prefixLength == 1) Metric.OFF_P1_WORDS.ordinal else Metric.OFF_P2_WORDS.ordinal
        }
        synchronized(this) {
            val values = _stats.value.values.toMutableList()
            values[base]++
            if (first == committedWord) values[base + 1]++
            if (visible.contains(committedWord)) values[base + 2]++
            _stats.value = Stats(values)
            dirty = true
        }
    }

    private fun realSuggestions(words: SuggestedWords): Pair<String?, List<String>> {
        var first: String? = null
        val visible = ArrayList<String>(words.size())
        var index = 1 // skip the typed-word slot at index 0
        while (index < words.size()) {
            val info = words.getInfo(index)
            val word = info.getWord()
            if (info.getKind() != SuggestedWordInfo.KIND_TYPED && word.isNotEmpty()) {
                // "Any visible slot" is broader than "first real slot": app-defined, hardcoded
                // and prediction slots are still on screen, so they count here.
                visible.add(word)
                if (first == null && isRealSuggestion(info)) first = word
            }
            index++
        }
        return first to visible
    }

    /** The shadow evaluation's definition of a real suggestion slot. */
    private fun isRealSuggestion(info: SuggestedWordInfo): Boolean =
        when (info.getKind()) {
            SuggestedWordInfo.KIND_TYPED,
            SuggestedWordInfo.KIND_APP_DEFINED,
            SuggestedWordInfo.KIND_HARDCODED,
            SuggestedWordInfo.KIND_PREDICTION -> false
            else -> true
        }

    private fun isEligible(settingsValues: SettingsValues): Boolean {
        val attributes = settingsValues.mInputAttributes
        return !settingsValues.mIncognitoModeEnabled &&
                !attributes.mIsPasswordField &&
                !attributes.mNoLearning &&
                settingsValues.needsToLookupSuggestions()
    }

    private fun isOrderingWord(word: String): Boolean {
        if (word.isEmpty()) return false
        var hasLetter = false
        for (character in word) {
            if (character == '\'' || character == '\u2019') continue
            if (!character.isLetter()) return false
            hasLetter = true
        }
        return hasLetter
    }

    private fun isContextWord(word: String): Boolean {
        if (word.isEmpty()) return false
        for (character in word) {
            if (character == '\'' || character == '\u2019') continue
            if (!character.isLetter()) return false
        }
        return true
    }

    private fun isWordCodePoint(codePoint: Int): Boolean {
        if (codePoint == '\''.code || codePoint == '\u2019'.code) return true
        return Character.isLetter(codePoint)
    }

    private fun invalidate(typedWord: String) {
        currentTyped = typedWord
        wordValid = false
        resetCaptures()
    }

    private fun resetCaptures() {
        firstSeen = false
        firstReal = null
        firstVisible = emptyList()
        secondSeen = false
        secondReal = null
        secondVisible = emptyList()
    }

    private fun clearWord(clearContext: Boolean) {
        currentTyped = ""
        wordValid = false
        resetCaptures()
        if (clearContext) contextWords = emptyList()
    }

    private fun readMode(context: Context): String =
        normalizeMode(context.prefs().getString(PREF_KEY, MODE_AB))

    private fun normalizeMode(value: String?): String {
        val candidate = value ?: MODE_AB
        return if (candidate == MODE_OFF || candidate == MODE_ON || candidate == MODE_AB) {
            candidate
        } else {
            MODE_AB
        }
    }

    private fun startFlushLoop() {
        if (flushJob?.isActive == true) return
        flushJob = ioScope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushLocked()
            }
        }
    }

    private suspend fun flushLocked(force: Boolean = false) {
        writeMutex.withLock {
            val context = appContext ?: return
            val snapshot = synchronized(this) {
                if (!dirty && !force) {
                    null
                } else {
                    dirty = false
                    _stats.value
                }
            } ?: return
            persist(context.prefs(), snapshot)
        }
    }

    private fun persist(preferences: SharedPreferences, stats: Stats) {
        preferences.edit {
            for (index in PREF_KEYS.indices) {
                putInt(PREF_KEYS[index], stats.values[index])
            }
        }
    }
}
