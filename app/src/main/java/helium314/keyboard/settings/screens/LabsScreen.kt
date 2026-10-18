// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.LabsKeyBoost
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SwitchPreference

/**
 * Labs build only: experimental switches and the persisted counters of the predicted-key boost.
 *
 * The screen is only reachable from Advanced settings when [LabsKeyBoost.isLabsBuild] is true.
 * Counters are aggregate integers; no typed text, word or key code is recorded.
 */
@Composable
fun LabsScreen(
    onClickBack: () -> Unit,
) {
    val stats by LabsKeyBoost.stats.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { LabsKeyBoost.load(ctx) }
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
        }
    }
}

@Composable
private fun CounterPreference(@StringRes title: Int, value: Int) {
    Preference(name = stringResource(title), onClick = {}) {
        Text(text = value.toString())
    }
}
