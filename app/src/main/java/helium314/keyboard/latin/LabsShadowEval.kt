// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Labs-only "prediction shadow mode".
 *
 * The feature is gated twice: the build type must be `labs`, and the Labs switch [PREF_KEY] must
 * be on (default off). On every other build type every entry point returns immediately.
 *
 * The user imports a model file through the system document picker. The file is copied into
 * `noBackupFilesDir`, which is excluded from Android backup, and parsed into an immutable
 * [ShadowModel]. Shadow evaluation runs per finished word: it compares the imported model's
 * top-1 prefix prediction with the top real suggestion HeliBoard showed after the first and
 * second letter, and with the word that was finally committed. Scoring is queued to a
 * background dispatcher; the touch path never scores and never writes preferences.
 *
 * Only aggregate integer counters are persisted. Typed letters, words, suggestions and n-grams
 * are held in memory only while a word is being evaluated and are never logged or stored.
 */
object LabsShadowEval {
    const val PREF_KEY = "labs_prediction_shadow_mode"

    private const val PREF_MODEL_LINES = "labs_shadow_model_lines"
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_MODEL_BYTES = 2L * 1024L * 1024L
    private const val MODEL_DIRECTORY = "labs_shadow_model"
    private const val MODEL_FILE = "model.txt"
    private const val MODEL_TEMP = "model.txt.tmp"
    private const val MODEL_BACKUP = "model.txt.old"

    val isLabsBuild: Boolean = BuildConfig.BUILD_TYPE == "labs"

    /** The twelve aggregate counters. The ordinal doubles as the persistence index. */
    enum class Metric {
        P1_ALL_WORDS,
        P1_ALL_MODEL_HITS,
        P1_ALL_HELI_HITS,
        P1_TYPED_WORDS,
        P1_TYPED_MODEL_HITS,
        P1_TYPED_HELI_HITS,
        P2_ALL_WORDS,
        P2_ALL_MODEL_HITS,
        P2_ALL_HELI_HITS,
        P2_TYPED_WORDS,
        P2_TYPED_MODEL_HITS,
        P2_TYPED_HELI_HITS,
    }

    data class Stats internal constructor(internal val values: List<Int>) {
        constructor() : this(List(Metric.values().size) { 0 })

        operator fun get(metric: Metric): Int = values[metric.ordinal]
    }

    sealed interface ModelStatus {
        data object None : ModelStatus
        data class Loaded(val lines: Int) : ModelStatus
    }

    private val PREF_KEYS = arrayOf(
        "labs_shadow_p1_all_words",
        "labs_shadow_p1_all_model_hits",
        "labs_shadow_p1_all_heli_hits",
        "labs_shadow_p1_typed_words",
        "labs_shadow_p1_typed_model_hits",
        "labs_shadow_p1_typed_heli_hits",
        "labs_shadow_p2_all_words",
        "labs_shadow_p2_all_model_hits",
        "labs_shadow_p2_all_heli_hits",
        "labs_shadow_p2_typed_words",
        "labs_shadow_p2_typed_model_hits",
        "labs_shadow_p2_typed_heli_hits",
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.None)
    val status: StateFlow<ModelStatus> = _status

    @Volatile private var enabled = false
    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    @Volatile private var model: ShadowModel? = null
    @Volatile private var modelGeneration = 0
    @Volatile private var resetGeneration = 0
    @Volatile private var modelLoading = false
    @Volatile private var modelLoadAttempted = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelMutex = Mutex()
    private val writeMutex = Mutex()
    private var flushJob: Job? = null

    // UI-thread-only state for the word currently being typed. It is cleared when the word ends.
    private var currentTyped = ""
    private var wordValid = false
    private var suggestionAfterFirst: String? = null
    private var suggestionAfterSecond: String? = null
    private var fieldEligible = false
    private val previousWords = ArrayDeque<String>()

    /** Loads the persisted counters and model status. Safe to call from the Labs screen. */
    fun load(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val preferences = app.prefs()
            val values = IntArray(Metric.values().size) { index ->
                preferences.getInt(PREF_KEYS[index], 0)
            }
            _stats.value = Stats(values.toList())
            enabled = preferences.getBoolean(PREF_KEY, false)
            dirty = false
            loaded = true
        }
        startFlushLoop()
        ensureModelLoaded()
    }

    /** Called when a new editor or text field starts. */
    fun onFieldChanged(context: Context, settingsValues: SettingsValues) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        enabled = app.prefs().getBoolean(PREF_KEY, false)
        fieldEligible = isEligible(settingsValues)
        clearWord(clearContext = true)
        if (enabled) ensureModelLoaded()
        startFlushLoop()
    }

    /** Called when the Labs switch changes while the settings screen is open. */
    fun onEnabledChanged(context: Context, isEnabled: Boolean) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        enabled = isEnabled
        if (!isEnabled) {
            fieldEligible = false
            clearWord(clearContext = true)
        } else {
            ensureModelLoaded()
        }
    }

    /** Called after every suggestion-strip update. */
    fun onSuggestions(
        context: Context,
        settingsValues: SettingsValues,
        words: SuggestedWords?,
        typedWord: String,
        composing: Boolean,
    ) {
        if (!isLabsBuild) return
        refresh(context, settingsValues)
        if (!enabled || !fieldEligible) return
        if (typedWord.isEmpty()) {
            if (currentTyped.isNotEmpty()) clearWord(clearContext = false)
            return
        }
        if (!composing) return
        // Stale suggestion batches are ignored. The batch carries the typed word it was built
        // for; only a batch for the word currently being tracked can supply a fair comparison.
        if (typedWord != currentTyped || !wordValid) return
        val prefixLength = typedWord.length
        if (prefixLength != 1 && prefixLength != 2) return
        if (words == null || words.mIsObsoleteSuggestions) return
        if (words.mInputStyle != SuggestedWords.INPUT_STYLE_TYPING) return
        val suggestionTypedWord = words.getTypedWordInfo()?.getWord() ?: return
        if (!suggestionTypedWord.equals(currentTyped, ignoreCase = true)) return
        val top = topRealSuggestion(words) ?: return
        if (prefixLength == 1) {
            if (suggestionAfterFirst == null) suggestionAfterFirst = top
        } else {
            if (suggestionAfterSecond == null) suggestionAfterSecond = top
        }
    }

    /** Called after a letter was appended to the composing word. */
    fun onLetterTyped(
        settingsValues: SettingsValues,
        codePoint: Int,
        typedWord: String,
        batchMode: Boolean,
    ) {
        if (!isLabsBuild || !enabled) return
        if (!fieldEligible || !isEligible(settingsValues)) {
            fieldEligible = false
            clearWord(clearContext = true)
            return
        }
        if (batchMode) {
            wordValid = false
            currentTyped = typedWord
            suggestionAfterFirst = null
            suggestionAfterSecond = null
            return
        }
        if (!isLowerWordCodePoint(codePoint)) {
            wordValid = false
            currentTyped = typedWord
            suggestionAfterFirst = null
            suggestionAfterSecond = null
            return
        }
        val character = String(Character.toChars(codePoint))
        if (currentTyped.isEmpty()) {
            currentTyped = typedWord
            wordValid = typedWord == character
            suggestionAfterFirst = null
            suggestionAfterSecond = null
            return
        }
        val expected = currentTyped + character
        if (wordValid && typedWord == expected) {
            currentTyped = typedWord
        } else {
            wordValid = false
            currentTyped = typedWord
            suggestionAfterFirst = null
            suggestionAfterSecond = null
        }
    }

    /** A backspace means the word is no longer strictly left to right. */
    fun onBackspace() {
        if (!isLabsBuild || currentTyped.isEmpty()) return
        wordValid = false
    }

    /** A cursor reset abandons the composing word without committing it. */
    fun onWordInvalidated() {
        if (!isLabsBuild) return
        clearWord(clearContext = false)
    }

    /** Called once for every committed word, before the composer is reused. */
    fun onWordCommitted(
        settingsValues: SettingsValues,
        typedWord: String,
        committedWord: String,
        commitType: Int,
    ) {
        if (!isLabsBuild) return
        val app = appContext ?: return
        if (!loaded) load(app)
        enabled = app.prefs().getBoolean(PREF_KEY, false)
        if (!enabled || !fieldEligible || !isEligible(settingsValues)) {
            fieldEligible = false
            clearWord(clearContext = true)
            return
        }
        val validWord = wordValid &&
                currentTyped == typedWord &&
                isEvaluableWord(typedWord) &&
                isEvaluableWord(committedWord)
        if (validWord && previousWords.isNotEmpty()) {
            val typedFully = typedWord == committedWord &&
                    commitType != LastComposedWord.COMMIT_TYPE_MANUAL_PICK &&
                    commitType != LastComposedWord.COMMIT_TYPE_CANCEL_AUTO_CORRECT
            submit(
                Observation(
                    typedWord = typedWord,
                    committedWord = committedWord,
                    context = previousWords.toList(),
                    suggestionAfterFirst = suggestionAfterFirst,
                    suggestionAfterSecond = suggestionAfterSecond,
                    typedFully = typedFully,
                )
            )
        }
        val contextWord = committedWord.lowercase(Locale.ROOT)
        if (isEvaluableWord(contextWord)) {
            previousWords.addLast(contextWord)
            while (previousWords.size > 2) previousWords.removeFirst()
        }
        clearWord(clearContext = false)
    }

    /** Called when the input view is hidden. */
    fun onKeyboardHidden() {
        if (!isLabsBuild) return
        fieldEligible = false
        clearWord(clearContext = true)
        flushJob?.cancel()
        flushJob = null
        ioScope.launch { flushLocked() }
    }

    /** Imports the chosen document into private, no-backup storage and parses it. */
    fun importModel(context: Context, uri: Uri) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        ioScope.launch {
            modelMutex.withLock {
                val directory = File(app.noBackupFilesDir, MODEL_DIRECTORY)
                if (!directory.isDirectory && !directory.mkdirs()) return@withLock
                val target = File(directory, MODEL_FILE)
                val temporary = File(directory, MODEL_TEMP)
                try {
                    val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().use { output ->
                            copyLimited(input, output)
                        }
                    } ?: false
                    if (!copied) return@withLock
                    val parsed = temporary.inputStream().buffered().use {
                        ShadowModelParser.parse(it)
                    } ?: return@withLock
                    if (!replaceFile(temporary, target)) return@withLock
                    model = parsed.model
                    modelGeneration++
                    modelLoadAttempted = true
                    _status.value = ModelStatus.Loaded(parsed.lineCount)
                    persistModelLines(app, parsed.lineCount)
                } catch (_: Exception) {
                    // A bad picker URI, a read error or an oversized file leaves the old model
                    // in place. Nothing about the file is logged.
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
        }
    }

    /** Deletes the imported model. Aggregate counters are left untouched. */
    fun deleteModel(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        clearWord(clearContext = true)
        // Retire the in-memory model immediately so no queued evaluation can count against it.
        model = null
        modelGeneration++
        modelLoadAttempted = true
        _status.value = ModelStatus.None
        clearModelLines(app)
        ioScope.launch {
            modelMutex.withLock {
                // Repeat the retirement while serialized with import/load, so a concurrent load
                // cannot leave an in-memory model behind after the delete.
                model = null
                modelGeneration++
                _status.value = ModelStatus.None
                val directory = File(app.noBackupFilesDir, MODEL_DIRECTORY)
                File(directory, MODEL_FILE).delete()
                File(directory, MODEL_TEMP).delete()
                File(directory, MODEL_BACKUP).delete()
            }
        }
    }

    /** Clears all twelve shadow counters, including any queued evaluation results. */
    fun resetStats(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        synchronized(this) {
            _stats.value = Stats()
            resetGeneration++
            dirty = false
        }
        ioScope.launch { flushLocked(force = true) }
    }

    private fun refresh(context: Context, settingsValues: SettingsValues) {
        val app = context.applicationContext
        appContext = app
        load(app)
        enabled = app.prefs().getBoolean(PREF_KEY, false)
        val eligible = isEligible(settingsValues)
        if (!enabled || !eligible) {
            fieldEligible = false
            clearWord(clearContext = true)
        } else {
            fieldEligible = true
            ensureModelLoaded()
        }
        startFlushLoop()
    }

    private fun isEligible(settingsValues: SettingsValues): Boolean {
        val attributes = settingsValues.mInputAttributes
        return !settingsValues.mIncognitoModeEnabled &&
                !attributes.mIsPasswordField &&
                !attributes.mNoLearning &&
                settingsValues.needsToLookupSuggestions()
    }

    private fun clearWord(clearContext: Boolean) {
        currentTyped = ""
        wordValid = false
        suggestionAfterFirst = null
        suggestionAfterSecond = null
        if (clearContext) previousWords.clear()
    }

    private fun topRealSuggestion(words: SuggestedWords): String? {
        var index = SuggestedWords.INDEX_OF_AUTO_CORRECTION
        while (index < words.size()) {
            val info = words.getInfo(index)
            when (info.getKind()) {
                SuggestedWords.SuggestedWordInfo.KIND_TYPED,
                SuggestedWords.SuggestedWordInfo.KIND_APP_DEFINED,
                SuggestedWords.SuggestedWordInfo.KIND_HARDCODED,
                SuggestedWords.SuggestedWordInfo.KIND_PREDICTION -> index++
                else -> return info.getWord()
            }
        }
        return null
    }

    private fun submit(observation: Observation) {
        val currentModel = model ?: return
        val generation = modelGeneration
        val reset = resetGeneration
        scoreScope.launch {
            val delta = try {
                evaluate(currentModel, observation)
            } catch (_: RuntimeException) {
                return@launch
            }
            synchronized(this@LabsShadowEval) {
                if (!enabled ||
                    generation != modelGeneration ||
                    reset != resetGeneration
                ) {
                    return@launch
                }
                val values = _stats.value.values.toMutableList()
                for (index in delta.indices) values[index] += delta[index]
                _stats.value = Stats(values)
                dirty = true
            }
        }
    }

    private fun evaluate(model: ShadowModel, observation: Observation): IntArray {
        val delta = IntArray(Metric.values().size)
        for (prefixLength in 1..2) {
            if (observation.typedWord.length <= prefixLength) continue
            val prefix = observation.typedWord.substring(0, prefixLength)
            val prediction = model.predict(prefix, observation.context)
            val modelHit = prediction == observation.committedWord
            val heliSuggestion = if (prefixLength == 1) {
                observation.suggestionAfterFirst
            } else {
                observation.suggestionAfterSecond
            }
            val heliHit = heliSuggestion == observation.committedWord
            val allBase = if (prefixLength == 1) {
                Metric.P1_ALL_WORDS.ordinal
            } else {
                Metric.P2_ALL_WORDS.ordinal
            }
            delta[allBase]++
            if (modelHit) delta[allBase + 1]++
            if (heliHit) delta[allBase + 2]++
            if (observation.typedFully) {
                val typedBase = if (prefixLength == 1) {
                    Metric.P1_TYPED_WORDS.ordinal
                } else {
                    Metric.P2_TYPED_WORDS.ordinal
                }
                delta[typedBase]++
                if (modelHit) delta[typedBase + 1]++
                if (heliHit) delta[typedBase + 2]++
            }
        }
        return delta
    }

    private fun isEvaluableWord(word: String): Boolean {
        if (word.isEmpty()) return false
        for (character in word) {
            if (character == '\'') continue
            if (!character.isLetter() || !character.isLowerCase()) return false
        }
        return true
    }

    private fun isLowerWordCodePoint(codePoint: Int): Boolean {
        if (codePoint == '\''.code) return true
        return Character.isLetter(codePoint) && Character.isLowerCase(codePoint)
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

    private fun ensureModelLoaded() {
        if (!isLabsBuild || model != null || modelLoading || modelLoadAttempted) return
        val context = appContext ?: return
        val generationAtStart = modelGeneration
        modelLoading = true
        ioScope.launch {
            try {
                modelMutex.withLock {
                    if (model != null || generationAtStart != modelGeneration) return@withLock
                    modelLoadAttempted = true
                    val file = File(File(context.noBackupFilesDir, MODEL_DIRECTORY), MODEL_FILE)
                    if (!file.isFile) {
                        _status.value = ModelStatus.None
                        clearModelLines(context)
                        return@withLock
                    }
                    val parsed = try {
                        file.inputStream().buffered().use { ShadowModelParser.parse(it) }
                    } catch (_: Exception) {
                        null
                    }
                    if (parsed == null) {
                        _status.value = ModelStatus.None
                        clearModelLines(context)
                        return@withLock
                    }
                    model = parsed.model
                    modelGeneration++
                    _status.value = ModelStatus.Loaded(parsed.lineCount)
                    persistModelLines(context, parsed.lineCount)
                }
            } finally {
                modelLoading = false
            }
        }
    }

    private fun persistModelLines(context: Context, lines: Int) {
        context.prefs().edit { putInt(PREF_MODEL_LINES, lines) }
    }

    private fun clearModelLines(context: Context) {
        context.prefs().edit { remove(PREF_MODEL_LINES) }
    }

    private fun copyLimited(input: InputStream, output: OutputStream): Boolean {
        var total = 0L
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_MODEL_BYTES) return false
            output.write(buffer, 0, read)
        }
        return total > 0L
    }

    /**
     * Replaces [target] with [temporary]. The old file is moved aside first, so a failed rename
     * restores it instead of silently discarding the previously loaded model.
     */
    private fun replaceFile(temporary: File, target: File): Boolean {
        val backup = File(target.parentFile, MODEL_BACKUP)
        backup.delete()
        if (target.exists() && !target.renameTo(backup)) return false
        if (temporary.renameTo(target)) {
            backup.delete()
            return true
        }
        if (backup.exists()) backup.renameTo(target)
        return false
    }

    private data class Observation(
        val typedWord: String,
        val committedWord: String,
        val context: List<String>,
        val suggestionAfterFirst: String?,
        val suggestionAfterSecond: String?,
        val typedFully: Boolean,
    )
}
