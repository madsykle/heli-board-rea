// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.LabsAutoCorrectCounters
import helium314.keyboard.latin.LabsKeyBoost
import helium314.keyboard.latin.LabsShadowEval
import helium314.keyboard.latin.LabsShadowEval.Metric
import helium314.keyboard.latin.LabsShadowEval.ModelStatus
import helium314.keyboard.latin.LabsSpaceTrackpad
import helium314.keyboard.latin.LabsSuggestionOrder
import helium314.keyboard.latin.LabsSuggestionOrder.Metric as OrderMetric
import helium314.keyboard.latin.LabsTouchOffset
import helium314.keyboard.latin.R
import helium314.keyboard.latin.TouchOffsetBucket
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SwitchPreference
import java.util.Locale

/**
 * Labs build only: experimental switches and the persisted counters of the predicted-key boost
 * and the prediction shadow evaluation.
 *
 * The screen is only reachable from Advanced settings when [LabsKeyBoost.isLabsBuild] is true.
 * Counters are aggregate integers; no typed text, word or key code is recorded.
 */
@Composable
fun LabsScreen(
    onClickBack: () -> Unit,
) {
    val stats by LabsKeyBoost.stats.collectAsState()
    val shadowStats by LabsShadowEval.stats.collectAsState()
    val orderStats by LabsSuggestionOrder.stats.collectAsState()
    val touchStats by LabsTouchOffset.stats.collectAsState()
    val autoCorrectStats by LabsAutoCorrectCounters.stats.collectAsState()
    val modelStatus by LabsShadowEval.status.collectAsState()
    val ctx = LocalContext.current
    val orderSetting = remember(ctx) {
        Setting(ctx, LabsSuggestionOrder.PREF_KEY, R.string.labs_model_suggestion_order) { }
    }
    val orderItems = listOf(
        stringResource(R.string.labs_model_suggestion_order_off) to LabsSuggestionOrder.MODE_OFF,
        stringResource(R.string.labs_model_suggestion_order_on) to LabsSuggestionOrder.MODE_ON,
        stringResource(R.string.labs_model_suggestion_order_ab) to LabsSuggestionOrder.MODE_AB,
    )
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) LabsShadowEval.importModel(ctx, uri)
    }
    LaunchedEffect(Unit) {
        LabsKeyBoost.load(ctx)
        LabsShadowEval.load(ctx)
        LabsSuggestionOrder.load(ctx)
        LabsSpaceTrackpad.load(ctx)
        LabsTouchOffset.load(ctx)
        LabsAutoCorrectCounters.load(ctx)
    }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.labs_settings_title),
        settings = emptyList()
    ) {
        LazyColumn {
            item { PreferenceCategory(stringResource(R.string.labs_section_typing)) }
            item {
                SwitchPreference(
                    name = stringResource(R.string.labs_predicted_key_boost),
                    key = LabsKeyBoost.PREF_KEY,
                    default = false,
                    description = stringResource(R.string.labs_predicted_key_boost_summary)
                )
            }
            item {
                SwitchPreference(
                    name = stringResource(R.string.labs_space_trackpad),
                    key = LabsSpaceTrackpad.PREF_KEY,
                    default = false,
                    description = stringResource(R.string.labs_space_trackpad_summary),
                    onCheckedChange = { LabsSpaceTrackpad.load(ctx, force = true) }
                )
            }
            item { PreferenceCategory(stringResource(R.string.labs_section_counters)) }
            item { CounterPreference(R.string.labs_counter_total_taps, stats.totalTaps) }
            item { CounterPreference(R.string.labs_counter_target_present, stats.tapsWithTarget) }
            item { CounterPreference(R.string.labs_counter_boosted_taps, stats.boostedTaps) }
            item { CounterPreference(R.string.labs_counter_unboosted_taps, stats.unboostedTaps) }
            item { CounterPreference(R.string.labs_counter_boosted_deleted, stats.boostedTapsDeleted) }
            item { CounterPreference(R.string.labs_counter_plain_deleted, stats.plainTapsDeleted) }
            item {
                Preference(
                    name = stringResource(R.string.labs_counter_reset),
                    onClick = { LabsKeyBoost.resetStats(ctx) }
                )
            }

            item { PreferenceCategory(stringResource(R.string.labs_section_prediction_shadow)) }
            item {
                SwitchPreference(
                    name = stringResource(R.string.labs_prediction_shadow_mode),
                    key = LabsShadowEval.PREF_KEY,
                    default = false,
                    description = stringResource(R.string.labs_prediction_shadow_summary),
                    onCheckedChange = { LabsShadowEval.onEnabledChanged(ctx, it) }
                )
            }
            item {
                Preference(
                    name = stringResource(R.string.labs_shadow_model),
                    onClick = {}
                ) {
                    Text(text = modelStatusText(modelStatus))
                }
            }
            item {
                Preference(
                    name = stringResource(R.string.labs_shadow_model_import),
                    onClick = { importLauncher.launch(arrayOf("*/*")) }
                )
            }
            item {
                Preference(
                    name = stringResource(R.string.labs_shadow_model_delete),
                    onClick = { LabsShadowEval.deleteModel(ctx) }
                )
            }

            item {
                ShadowStatsGroup(
                    title = R.string.labs_shadow_prefix1_all,
                    words = shadowStats[Metric.P1_ALL_WORDS],
                    modelHits = shadowStats[Metric.P1_ALL_MODEL_HITS],
                    heliHits = shadowStats[Metric.P1_ALL_HELI_HITS]
                )
            }
            item {
                ShadowStatsGroup(
                    title = R.string.labs_shadow_prefix1_typed,
                    words = shadowStats[Metric.P1_TYPED_WORDS],
                    modelHits = shadowStats[Metric.P1_TYPED_MODEL_HITS],
                    heliHits = shadowStats[Metric.P1_TYPED_HELI_HITS]
                )
            }
            item {
                ShadowStatsGroup(
                    title = R.string.labs_shadow_prefix2_all,
                    words = shadowStats[Metric.P2_ALL_WORDS],
                    modelHits = shadowStats[Metric.P2_ALL_MODEL_HITS],
                    heliHits = shadowStats[Metric.P2_ALL_HELI_HITS]
                )
            }
            item {
                ShadowStatsGroup(
                    title = R.string.labs_shadow_prefix2_typed,
                    words = shadowStats[Metric.P2_TYPED_WORDS],
                    modelHits = shadowStats[Metric.P2_TYPED_MODEL_HITS],
                    heliHits = shadowStats[Metric.P2_TYPED_HELI_HITS]
                )
            }
            item {
                Preference(
                    name = stringResource(R.string.labs_shadow_reset),
                    onClick = { LabsShadowEval.resetStats(ctx) }
                )
            }

            item { PreferenceCategory(stringResource(R.string.labs_section_model_order)) }
            item {
                ListPreference(
                    setting = orderSetting,
                    items = orderItems,
                    default = LabsSuggestionOrder.MODE_AB,
                    onDefault = { },
                    onChanged = { LabsSuggestionOrder.onModeChanged(ctx, it) }
                )
            }
            item {
                OrderStatsGroup(
                    title = R.string.labs_order_off_prefix1,
                    words = orderStats[OrderMetric.OFF_P1_WORDS],
                    firstHits = orderStats[OrderMetric.OFF_P1_FIRST],
                    anyHits = orderStats[OrderMetric.OFF_P1_ANY]
                )
            }
            item {
                OrderStatsGroup(
                    title = R.string.labs_order_off_prefix2,
                    words = orderStats[OrderMetric.OFF_P2_WORDS],
                    firstHits = orderStats[OrderMetric.OFF_P2_FIRST],
                    anyHits = orderStats[OrderMetric.OFF_P2_ANY]
                )
            }
            item {
                OrderStatsGroup(
                    title = R.string.labs_order_on_prefix1,
                    words = orderStats[OrderMetric.ON_P1_WORDS],
                    firstHits = orderStats[OrderMetric.ON_P1_FIRST],
                    anyHits = orderStats[OrderMetric.ON_P1_ANY]
                )
            }
            item {
                OrderStatsGroup(
                    title = R.string.labs_order_on_prefix2,
                    words = orderStats[OrderMetric.ON_P2_WORDS],
                    firstHits = orderStats[OrderMetric.ON_P2_FIRST],
                    anyHits = orderStats[OrderMetric.ON_P2_ANY]
                )
            }
            item {
                Preference(
                    name = stringResource(R.string.labs_order_reset),
                    onClick = { LabsSuggestionOrder.resetStats(ctx) }
                )
            }

            item { PreferenceCategory(stringResource(R.string.labs_section_touch_offset)) }
            item {
                Preference(
                    name = stringResource(R.string.labs_touch_offset_hint),
                    onClick = {}
                )
            }
            val touchRows = intArrayOf(
                R.string.labs_touch_row_top,
                R.string.labs_touch_row_middle,
                R.string.labs_touch_row_bottom,
            )
            val touchColumns = intArrayOf(
                R.string.labs_touch_col_left,
                R.string.labs_touch_col_center,
                R.string.labs_touch_col_right,
            )
            for (row in 0..2) {
                for (column in 0..2) {
                    item {
                        TouchBucketPreference(
                            name = stringResource(touchRows[row]) + " \u00b7 " +
                                    stringResource(touchColumns[column]),
                            bucket = touchStats.bucket(row * 3 + column)
                        )
                    }
                }
            }
            item {
                Preference(
                    name = stringResource(R.string.labs_touch_offset_reset),
                    onClick = { LabsTouchOffset.resetStats(ctx) }
                )
            }

            item { PreferenceCategory(stringResource(R.string.labs_section_autocorrect)) }
            item { CounterPreference(R.string.labs_autocorrect_events, autoCorrectStats.events) }
            item { CounterPreference(R.string.labs_autocorrect_reverted, autoCorrectStats.reverted) }
            item { CounterPreference(R.string.labs_autocorrect_kept, autoCorrectStats.kept) }
            item {
                AutoCorrectGroup(
                    title = R.string.labs_autocorrect_reverted_group,
                    total = autoCorrectStats.reverted,
                    match = autoCorrectStats.revertedMatch,
                    differ = autoCorrectStats.revertedDiffer,
                    noOpinion = autoCorrectStats.revertedNoOpinion
                )
            }
            item {
                AutoCorrectGroup(
                    title = R.string.labs_autocorrect_kept_group,
                    total = autoCorrectStats.kept,
                    match = autoCorrectStats.keptMatch,
                    differ = autoCorrectStats.keptDiffer,
                    noOpinion = autoCorrectStats.keptNoOpinion
                )
            }
            item {
                Preference(
                    name = stringResource(R.string.labs_autocorrect_reset),
                    onClick = { LabsAutoCorrectCounters.resetStats(ctx) }
                )
            }
        }
    }
}

@Composable
private fun modelStatusText(status: ModelStatus): String = when (status) {
    ModelStatus.None -> stringResource(R.string.labs_shadow_model_none)
    is ModelStatus.Loaded -> stringResource(R.string.labs_shadow_model_loaded, status.lines)
}

@Composable
private fun ShadowStatsGroup(
    @StringRes title: Int,
    words: Int,
    modelHits: Int,
    heliHits: Int,
) {
    Column {
        PreferenceCategory(stringResource(title))
        CounterPreference(R.string.labs_shadow_words, words)
        HitPreference(R.string.labs_shadow_model_hits, modelHits, words)
        HitPreference(R.string.labs_shadow_heli_hits, heliHits, words)
    }
}

@Composable
private fun OrderStatsGroup(
    @StringRes title: Int,
    words: Int,
    firstHits: Int,
    anyHits: Int,
) {
    Column {
        PreferenceCategory(stringResource(title))
        CounterPreference(R.string.labs_order_words, words)
        HitPreference(R.string.labs_order_first_hits, firstHits, words)
        HitPreference(R.string.labs_order_any_hits, anyHits, words)
    }
}

@Composable
private fun TouchBucketPreference(name: String, bucket: TouchOffsetBucket) {
    Preference(
        name = name,
        onClick = {},
        description = touchBucketText(bucket)
    )
}

@Composable
private fun AutoCorrectGroup(
    @StringRes title: Int,
    total: Int,
    match: Int,
    differ: Int,
    noOpinion: Int,
) {
    Column {
        PreferenceCategory(stringResource(title))
        HitPreference(R.string.labs_autocorrect_model_match, match, total)
        HitPreference(R.string.labs_autocorrect_model_differ, differ, total)
        HitPreference(R.string.labs_autocorrect_model_no_opinion, noOpinion, total)
    }
}

private fun touchBucketText(bucket: TouchOffsetBucket): String {
    if (bucket.count == 0L) return "n = 0"
    return String.format(
        Locale.US,
        "n = %d \u00b7 dx %.1f \u00b1 %.1f px \u00b7 dy %.1f \u00b1 %.1f px",
        bucket.count,
        bucket.meanDx,
        bucket.stdDx,
        bucket.meanDy,
        bucket.stdDy
    )
}

@Composable
private fun CounterPreference(@StringRes title: Int, value: Int) {
    Preference(name = stringResource(title), onClick = {}) {
        Text(text = value.toString())
    }
}

@Composable
private fun HitPreference(@StringRes title: Int, hits: Int, words: Int) {
    Preference(name = stringResource(title), onClick = {}) {
        Text(text = hitText(hits, words))
    }
}

private fun hitText(hits: Int, words: Int): String {
    if (words == 0) return "$hits / $words (\u2014)"
    val percent = String.format(Locale.US, "%.1f%%", hits * 100.0 / words)
    return "$hits / $words ($percent)"
}
