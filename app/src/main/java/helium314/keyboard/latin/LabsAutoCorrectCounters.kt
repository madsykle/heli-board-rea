// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
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

/** Whether the shadow model agreed with a chosen auto-correction. */
internal enum class AutoCorrectOpinion {
    UNKNOWN,
    MATCH,
    DIFFER,
    NO_OPINION,
}

/**
 * True for a typed word that a different suggestion replaced on space or punctuation. Manual
 * picks, gesture commits and text-input commits are not auto-corrections.
 */
internal fun isAutoCorrectEvent(
    commitType: Int,
    typedWord: String,
    committedWord: String,
    separatorCommit: Boolean,
): Boolean = commitType == LastComposedWord.COMMIT_TYPE_DECIDED_WORD &&
        typedWord.isNotEmpty() &&
        typedWord != committedWord &&
        separatorCommit

/** Classifies the model's top candidate against the word that was actually committed. */
internal fun classifyOpinion(prediction: String?, committedWord: String): AutoCorrectOpinion =
    when {
        prediction == null -> AutoCorrectOpinion.NO_OPINION
        prediction.equals(committedWord, ignoreCase = true) -> AutoCorrectOpinion.MATCH
        else -> AutoCorrectOpinion.DIFFER
    }

/**
 * Labs-only, observe-only auto-correction counters.
 *
 * The build type must be `labs`; every entry point returns immediately on other build types, so
 * release behaviour is unchanged. There is no switch: the counters are collected whenever the
 * current field is eligible.
 *
 * An event is a typed word that a different suggestion replaced on space or punctuation. It is
 * counted once it is final: either the user reverted it with backspace within
 * [REVERT_WINDOW_MS], or the window elapsed without a revert. Each final event is split by
 * whether the imported shadow model's top candidate for the typed prefix and the previous two
 * words equals the committed correction, differs, or does not exist.
 *
 * Only aggregate integers are persisted. Typed words, corrections, suggestions and n-grams are
 * held in memory only while an event is pending and are never logged or stored. The model lookup
 * runs on a background dispatcher, never on the touch path; the touch path only records the
 * pending event and its two-word context.
 */
object LabsAutoCorrectCounters {
    val isLabsBuild: Boolean = BuildConfig.BUILD_TYPE == "labs"

    const val REVERT_WINDOW_MS = 3_000L

    private const val FLUSH_INTERVAL_MS = 10_000L

    private const val PREF_EVENTS = "labs_ac_events"
    private const val PREF_REVERTED = "labs_ac_reverted"
    private const val PREF_KEPT = "labs_ac_kept"
    private const val PREF_REVERTED_MATCH = "labs_ac_reverted_match"
    private const val PREF_REVERTED_DIFFER = "labs_ac_reverted_differ"
    private const val PREF_REVERTED_NONE = "labs_ac_reverted_none"
    private const val PREF_KEPT_MATCH = "labs_ac_kept_match"
    private const val PREF_KEPT_DIFFER = "labs_ac_kept_differ"
    private const val PREF_KEPT_NONE = "labs_ac_kept_none"

    data class Stats(
        val events: Int = 0,
        val reverted: Int = 0,
        val kept: Int = 0,
        val revertedMatch: Int = 0,
        val revertedDiffer: Int = 0,
        val revertedNoOpinion: Int = 0,
        val keptMatch: Int = 0,
        val keptDiffer: Int = 0,
        val keptNoOpinion: Int = 0,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    @Volatile private var fieldEligible = false
    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opinionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeMutex = Mutex()
    private var flushJob: Job? = null
    private var timeoutJob: Job? = null

    private var pending: Pending? = null
    private val previousWords = ArrayDeque<String>()

    /** Loads the persisted counters. Safe to call from the Labs screen. */
    fun load(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val preferences = app.prefs()
            _stats.value = Stats(
                events = preferences.getInt(PREF_EVENTS, 0),
                reverted = preferences.getInt(PREF_REVERTED, 0),
                kept = preferences.getInt(PREF_KEPT, 0),
                revertedMatch = preferences.getInt(PREF_REVERTED_MATCH, 0),
                revertedDiffer = preferences.getInt(PREF_REVERTED_DIFFER, 0),
                revertedNoOpinion = preferences.getInt(PREF_REVERTED_NONE, 0),
                keptMatch = preferences.getInt(PREF_KEPT_MATCH, 0),
                keptDiffer = preferences.getInt(PREF_KEPT_DIFFER, 0),
                keptNoOpinion = preferences.getInt(PREF_KEPT_NONE, 0),
            )
            dirty = false
            loaded = true
        }
        startFlushLoop()
    }

    /** Called when a new editor or text field starts. */
    fun onFieldChanged(context: Context, settingsValues: SettingsValues) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        val eligible = isEligible(settingsValues)
        synchronized(this) { finalizeKeptLocked() }
        clearContext()
        fieldEligible = eligible
        if (eligible) {
            // The shared shadow model is only used to classify the event. Loading it is I/O on
            // the background dispatcher, never on the touch path.
            LabsShadowEval.requestModelLoad(app)
        }
        startFlushLoop()
    }

    /** Called when the input view is hidden: no revert can follow. */
    fun onKeyboardHidden() {
        if (!isLabsBuild) return
        fieldEligible = false
        synchronized(this) { finalizeKeptLocked() }
        clearContext()
        flushJob?.cancel()
        flushJob = null
        ioScope.launch { flushLocked() }
    }

    /**
     * Called once for every committed word, before the composer is reused. This updates the
     * two-word context and, for an auto-correction on space or punctuation, starts a pending
     * event. It runs on the UI thread and does not look up the model.
     */
    fun onWordCommitted(
        settingsValues: SettingsValues,
        typedWord: String,
        committedWord: String,
        commitType: Int,
        separatorCommit: Boolean,
    ) {
        if (!isLabsBuild) return
        if (!fieldEligible || !isEligible(settingsValues)) {
            synchronized(this) { finalizeKeptLocked() }
            clearContext()
            return
        }
        if (isAutoCorrectEvent(commitType, typedWord, committedWord, separatorCommit)) {
            startPending(typedWord, committedWord)
        }
        appendContext(committedWord)
    }

    /** Called from the backspace revert path with the word that was committed by auto-correct. */
    fun onRevert(committedWord: String) {
        if (!isLabsBuild) return
        val now = SystemClock.uptimeMillis()
        synchronized(this) {
            val event = pending ?: return
            if (event.committed != committedWord) return
            if (now - event.startedAt > REVERT_WINDOW_MS) return
            pending = null
            timeoutJob?.cancel()
            timeoutJob = null
            finalizeLocked(event, reverted = true)
        }
    }

    /** Clears all counters and drops any pending event. */
    fun resetStats(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        synchronized(this) {
            // Suppress an opinion result that is still in flight.
            pending?.counted = true
            pending = null
            timeoutJob?.cancel()
            timeoutJob = null
            _stats.value = Stats()
            dirty = false
        }
        clearContext()
        ioScope.launch { flushLocked(force = true) }
    }

    private fun startPending(typedWord: String, committedWord: String) {
        val context = previousWords.toList()
        val model = LabsShadowEval.modelForOpinion()
        val event = Pending(
            committed = committedWord,
            context = context,
            startedAt = SystemClock.uptimeMillis(),
        )
        synchronized(this) {
            finalizeKeptLocked()
            pending = event
            timeoutJob?.cancel()
            timeoutJob = ioScope.launch {
                delay(REVERT_WINDOW_MS)
                synchronized(this@LabsAutoCorrectCounters) {
                    if (pending === event) {
                        pending = null
                        finalizeLocked(event, reverted = false)
                    }
                }
            }
        }
        if (model == null || context.isEmpty()) {
            event.opinion = AutoCorrectOpinion.NO_OPINION
            countIfReady(event)
        } else {
            val loadedModel = model
            val prefix = typedWord.lowercase(Locale.ROOT)
            opinionScope.launch {
                val prediction = try {
                    loadedModel.predict(prefix, event.context)
                } catch (_: RuntimeException) {
                    null
                }
                event.opinion = classifyOpinion(prediction, committedWord)
                countIfReady(event)
            }
        }
    }

    private fun finalizeKeptLocked() {
        val event = pending ?: return
        pending = null
        timeoutJob?.cancel()
        timeoutJob = null
        finalizeLocked(event, reverted = false)
    }

    private fun finalizeLocked(event: Pending, reverted: Boolean) {
        if (event.finalized) return
        event.finalized = true
        event.reverted = reverted
        countIfReadyLocked(event)
    }

    private fun countIfReady(event: Pending) {
        synchronized(this) { countIfReadyLocked(event) }
    }

    private fun countIfReadyLocked(event: Pending) {
        if (event.counted || !event.finalized || event.opinion == AutoCorrectOpinion.UNKNOWN) return
        event.counted = true
        val stats = _stats.value
        val match = if (event.opinion == AutoCorrectOpinion.MATCH) 1 else 0
        val differ = if (event.opinion == AutoCorrectOpinion.DIFFER) 1 else 0
        val none = if (event.opinion == AutoCorrectOpinion.NO_OPINION) 1 else 0
        _stats.value = if (event.reverted) {
            stats.copy(
                events = stats.events + 1,
                reverted = stats.reverted + 1,
                revertedMatch = stats.revertedMatch + match,
                revertedDiffer = stats.revertedDiffer + differ,
                revertedNoOpinion = stats.revertedNoOpinion + none,
            )
        } else {
            stats.copy(
                events = stats.events + 1,
                kept = stats.kept + 1,
                keptMatch = stats.keptMatch + match,
                keptDiffer = stats.keptDiffer + differ,
                keptNoOpinion = stats.keptNoOpinion + none,
            )
        }
        dirty = true
    }

    private fun appendContext(committedWord: String) {
        val word = committedWord.lowercase(Locale.ROOT)
        if (!isContextWord(word)) return
        previousWords.addLast(word)
        while (previousWords.size > 2) previousWords.removeFirst()
    }

    private fun clearContext() {
        previousWords.clear()
    }

    private fun isContextWord(word: String): Boolean {
        if (word.isEmpty()) return false
        for (character in word) {
            if (character == '\'' || character == '\u2019') continue
            if (!character.isLetter()) return false
        }
        return true
    }

    private fun isEligible(settingsValues: SettingsValues): Boolean {
        val attributes = settingsValues.mInputAttributes
        return !settingsValues.mIncognitoModeEnabled &&
                !attributes.mIsPasswordField &&
                !attributes.mNoLearning
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
            putInt(PREF_EVENTS, stats.events)
            putInt(PREF_REVERTED, stats.reverted)
            putInt(PREF_KEPT, stats.kept)
            putInt(PREF_REVERTED_MATCH, stats.revertedMatch)
            putInt(PREF_REVERTED_DIFFER, stats.revertedDiffer)
            putInt(PREF_REVERTED_NONE, stats.revertedNoOpinion)
            putInt(PREF_KEPT_MATCH, stats.keptMatch)
            putInt(PREF_KEPT_DIFFER, stats.keptDiffer)
            putInt(PREF_KEPT_NONE, stats.keptNoOpinion)
        }
    }

    private class Pending(
        val committed: String,
        val context: List<String>,
        val startedAt: Long,
    ) {
        @Volatile var opinion: AutoCorrectOpinion = AutoCorrectOpinion.UNKNOWN
        @Volatile var finalized: Boolean = false
        @Volatile var reverted: Boolean = false
        @Volatile var counted: Boolean = false
    }
}
