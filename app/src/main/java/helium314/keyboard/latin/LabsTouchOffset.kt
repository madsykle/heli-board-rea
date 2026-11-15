// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyDetector
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.common.Constants
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
import kotlin.math.sqrt

internal const val TOUCH_BUCKET_COUNT = 9
internal const val TOUCH_BUCKET_FIELDS = 5

/**
 * Returns the horizontal third of the keyboard that contains [centerX]: 0 left, 1 centre, 2 right.
 * A missing width falls back to the centre third.
 */
internal fun touchHorizontalThird(centerX: Int, occupiedWidth: Int): Int {
    if (occupiedWidth <= 0) return 1
    return (centerX.toLong() * 3L / occupiedWidth).toInt().coerceIn(0, 2)
}

/**
 * Returns the letter-row index for [keyY]. [rowTops] holds the distinct top edges of the letter
 * rows, ascending. Values beyond the third row clamp to the bottom bucket.
 */
internal fun touchRowIndex(rowTops: IntArray, keyY: Int): Int {
    if (rowTops.isEmpty()) return 0
    var index = 0
    for (candidate in rowTops.indices) {
        if (rowTops[candidate] <= keyY) {
            index = candidate
        } else {
            break
        }
    }
    return index.coerceIn(0, 2)
}

/** Row-major bucket index: top/middle/bottom row x left/centre/right third. */
internal fun touchBucketIndex(
    rowTops: IntArray,
    keyY: Int,
    centerX: Int,
    occupiedWidth: Int,
): Int = touchRowIndex(rowTops, keyY) * 3 + touchHorizontalThird(centerX, occupiedWidth)

/**
 * Aggregate geometry for one bucket. Only sums are stored; means and standard deviations are
 * computed when the Labs screen reads them.
 */
internal data class TouchOffsetBucket(
    val count: Long = 0L,
    val sumDx: Long = 0L,
    val sumDy: Long = 0L,
    val sumDx2: Long = 0L,
    val sumDy2: Long = 0L,
) {
    val meanDx: Double get() = if (count == 0L) 0.0 else sumDx.toDouble() / count
    val meanDy: Double get() = if (count == 0L) 0.0 else sumDy.toDouble() / count
    val stdDx: Double get() = standardDeviation(sumDx2, sumDx, count)
    val stdDy: Double get() = standardDeviation(sumDy2, sumDy, count)

    private fun standardDeviation(sumSquares: Long, sum: Long, n: Long): Double {
        if (n <= 0L) return 0.0
        val mean = sum.toDouble() / n
        val variance = sumSquares.toDouble() / n - mean * mean
        return if (variance <= 0.0) 0.0 else sqrt(variance)
    }
}

/**
 * Labs-only, observe-only touch-offset measurement.
 *
 * The build type must be `labs`; every entry point returns immediately on other build types, so
 * release behaviour is unchanged. There is no switch: the counters are collected whenever the
 * current field is eligible.
 *
 * For every main-keyboard letter tap (not a drag, glide or trackpad gesture) the offset between
 * the touch-down point and the centre of the key that was actually dispatched is accumulated in
 * nine buckets: top/middle/bottom letter row x left/centre/right third of the keyboard. Only the
 * sums are kept; which key was touched, and any typed text, is never stored or logged.
 *
 * The touch path only changes in-memory longs. SharedPreferences are written in batches off the
 * input path, at most once per [FLUSH_INTERVAL_MS] and once when the keyboard hides. Field
 * eligibility is loaded at field start, so no preference is read on the touch path.
 */
object LabsTouchOffset {
    val isLabsBuild: Boolean = BuildConfig.BUILD_TYPE == "labs"

    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val TOTAL_FIELDS = TOUCH_BUCKET_COUNT * TOUCH_BUCKET_FIELDS

    private val PREF_KEYS = Array(TOTAL_FIELDS) { "labs_touch_offset_$it" }

    data class Stats internal constructor(internal val values: List<Long>) {
        constructor() : this(List(TOUCH_BUCKET_COUNT * TOUCH_BUCKET_FIELDS) { 0L })

        internal fun bucket(index: Int): TouchOffsetBucket {
            val base = index * TOUCH_BUCKET_FIELDS
            return TouchOffsetBucket(
                count = values[base],
                sumDx = values[base + 1],
                sumDy = values[base + 2],
                sumDx2 = values[base + 3],
                sumDy2 = values[base + 4],
            )
        }
    }

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    @Volatile private var fieldEligible = false
    @Volatile private var rowTops: IntArray = IntArray(0)
    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var flushJob: Job? = null

    /** Loads the persisted sums. Safe to call from the Labs screen. */
    fun load(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val preferences = app.prefs()
            _stats.value = Stats(List(TOTAL_FIELDS) { preferences.getLong(PREF_KEYS[it], 0L) })
            dirty = false
            loaded = true
        }
        startFlushLoop()
    }

    /** Called from MainKeyboardView.setKeyboard: the row edges belong to the new layout. */
    fun onKeyboardChanged(keyboard: Keyboard?) {
        if (!isLabsBuild) return
        val tops = sortedSetOf<Int>()
        if (keyboard != null) {
            for (key in keyboard.sortedKeys) {
                if (key.isSpacer) continue
                val code = key.code
                if (code < Constants.CODE_SPACE || !Character.isLetter(code)) continue
                tops.add(key.y)
            }
        }
        rowTops = tops.toIntArray()
    }

    /** Called when a new editor or text field starts. No preference is read on the touch path. */
    fun onFieldChanged(context: Context, settingsValues: SettingsValues) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        fieldEligible = isEligible(settingsValues)
        startFlushLoop()
    }

    /** Called when the input view is hidden: write the pending batch now. */
    fun onKeyboardHidden() {
        if (!isLabsBuild) return
        fieldEligible = false
        flushJob?.cancel()
        flushJob = null
        ioScope.launch { flushLocked() }
    }

    /**
     * Called once for a dispatched letter tap. This runs on the touch path and only touches
     * memory: no I/O, no model lookup and no preference read.
     */
    fun onLetterTap(key: Key?, downX: Int, downY: Int, detector: KeyDetector) {
        if (!isLabsBuild || !fieldEligible || key == null) return
        val code = key.code
        if (code < Constants.CODE_SPACE || !Character.isLetter(code)) return
        val keyboard = detector.keyboard ?: return
        val centerX = key.x + key.width / 2
        val centerY = key.y + key.height / 2
        // The detector's coordinates are the ones used for hit testing. On the main keyboard the
        // vertical correction is 0, so this is the raw view position minus the view padding.
        val dx = detector.getTouchX(downX) - centerX
        val dy = detector.getTouchY(downY) - centerY
        val bucket = touchBucketIndex(rowTops, key.y, centerX, keyboard.mOccupiedWidth)
        val base = bucket * TOUCH_BUCKET_FIELDS
        synchronized(this) {
            val values = _stats.value.values.toMutableList()
            values[base] = values[base] + 1L
            values[base + 1] = values[base + 1] + dx
            values[base + 2] = values[base + 2] + dy
            values[base + 3] = values[base + 3] + dx.toLong() * dx
            values[base + 4] = values[base + 4] + dy.toLong() * dy
            _stats.value = Stats(values)
            dirty = true
        }
    }

    /** Clears the nine buckets and writes zeros. */
    fun resetStats(context: Context) {
        if (!isLabsBuild) return
        val app = context.applicationContext
        appContext = app
        load(app)
        synchronized(this) {
            _stats.value = Stats()
            dirty = false
        }
        ioScope.launch { flushLocked(force = true) }
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
            for (index in PREF_KEYS.indices) {
                putLong(PREF_KEYS[index], stats.values[index])
            }
        }
    }
}
