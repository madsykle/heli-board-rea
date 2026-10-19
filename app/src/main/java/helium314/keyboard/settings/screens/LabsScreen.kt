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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.LabsKeyBoost
import helium314.keyboard.latin.LabsShadowEval
import helium314.keyboard.latin.LabsShadowEval.Metric
import helium314.keyboard.latin.LabsShadowEval.ModelStatus
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
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
    val modelStatus by LabsShadowEval.status.collectAsState()
    val ctx = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) LabsShadowEval.importModel(ctx, uri)
    }
    LaunchedEffect(Unit) {
        LabsKeyBoost.load(ctx)
        LabsShadowEval.load(ctx)
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
