package com.audic.music.brain.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.navigation.NavController
import com.audic.music.LocalPlayerAwareWindowInsets
import com.audic.music.R
import com.audic.music.ui.component.Material3SettingsGroup
import com.audic.music.ui.component.Material3SettingsItem
import com.audic.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainSettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    highlightKey: String? = null
) {
    val scrollState = rememberScrollState()
    var brainEnabled by rememberPreference(
        booleanPreferencesKey("audic_brain_enabled"), false
    )
    var showWhyDialog by rememberPreference(
        booleanPreferencesKey("audic_brain_show_why"), true
    )

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top))
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Audic Brain learns from your listening behavior to intelligently " +
                   "recommend songs that match your current vibe — all on-device and private.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Material3SettingsGroup(
            title = "Brain Engine",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.sparks),
                    title = { Text("Audic Brain (Beta)") },
                    description = {
                        Text(if (brainEnabled)
                            "Analyzing listening patterns and generating recommendations"
                        else "Enable on-device personalized recommendations")
                    },
                    trailingContent = {
                        Switch(checked = brainEnabled,
                            onCheckedChange = { brainEnabled = it })
                    },
                    onClick = { brainEnabled = !brainEnabled }
                )
            )
        )

        if (brainEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            Material3SettingsGroup(
                title = "Preferences",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.info),
                        title = { Text("Show \"Why this song?\"") },
                        description = { Text("Display transparency dialog for AI-suggested tracks") },
                        trailingContent = {
                            Switch(checked = showWhyDialog,
                                onCheckedChange = { showWhyDialog = it })
                        },
                        onClick = { showWhyDialog = !showWhyDialog }
                    )
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("How it works", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "• Tracks your listening sessions to learn your preferences\n" +
                   "• Builds a private interest profile from your library\n" +
                   "• Scores candidates using multi-signal ranking\n" +
                   "• Injects up to 3 recommendations into your queue\n" +
                   "• All data stays on-device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Spacer(Modifier.windowInsetsPadding(
            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)))
    }

    TopAppBar(
        title = { Text("Audic Brain") },
        navigationIcon = {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        }
    )
}