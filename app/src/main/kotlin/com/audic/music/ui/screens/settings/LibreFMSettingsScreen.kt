/**
 * Audic Music
 * Licensed under GPL-3.0
 */

package com.music.audic.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.audic.music.LocalPlayerAwareWindowInsets
import com.audic.music.R
import com.audic.music.constants.EnableLibreFMScrobblingKey
import com.audic.music.constants.LibreFMSessionKey
import com.audic.music.constants.LibreFMUseNowPlaying
import com.audic.music.constants.LibreFMUseSendLikes
import com.audic.music.constants.LibreFMUsernameKey
import com.audic.music.ui.component.IconButton
import com.audic.music.ui.component.Material3SettingsGroup
import com.audic.music.ui.component.Material3SettingsItem
import com.audic.music.ui.utils.backToMain
import com.audic.music.utils.rememberPreference
import com.audic.music.utils.lastfm.LastFmException
import com.audic.music.utils.lastfm.LibreFM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibreFMSettingsScreen(
    navController: NavController
) {
    val coroutineScope = rememberCoroutineScope()

    var librefmUsername by rememberPreference(LibreFMUsernameKey, "")
    var librefmSession by rememberPreference(LibreFMSessionKey, "")

    val isLoggedIn = remember(librefmSession) { librefmSession != "" }

    val (useNowPlaying, onUseNowPlayingChange) = rememberPreference(
        key = LibreFMUseNowPlaying,
        defaultValue = false
    )

    val (useSendLikes, onUseSendLikes) = rememberPreference(
        key = LibreFMUseSendLikes,
        defaultValue = false
    )

    val (librefmScrobbling, onLibrefmScrobblingChange) = rememberPreference(
        key = EnableLibreFMScrobblingKey,
        defaultValue = false
    )

    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var isLoggingIn by rememberSaveable { mutableStateOf(false) }
    var loginError by rememberSaveable { mutableStateOf<String?>(null) }

    if (showLoginDialog) {
        var tempUsername by rememberSaveable { mutableStateOf("") }
        var tempPassword by rememberSaveable { mutableStateOf("") }

        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = {
                if (!isLoggingIn) {
                    showLoginDialog = false
                    loginError = null
                }
            },
            title = { Text(stringResource(R.string.login)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = tempUsername,
                        onValueChange = {
                            tempUsername = it
                            loginError = null
                        },
                        label = { Text(stringResource(R.string.username)) },
                        singleLine = true,
                        enabled = !isLoggingIn,
                    )
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = {
                            tempPassword = it
                            loginError = null
                        },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !isLoggingIn,
                    )

                    loginError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (isLoggingIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.logging_in),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempUsername.isBlank() || tempPassword.isBlank()) {
                            loginError = "Please enter both username and password"
                            return@TextButton
                        }

                        isLoggingIn = true
                        loginError = null

                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                LibreFM.getMobileSession(tempUsername, tempPassword)
                                    .onSuccess { auth ->
                                        librefmUsername = auth.session.name
                                        librefmSession = auth.session.key
                                        LibreFM.sessionKey = auth.session.key

                                        coroutineScope.launch(Dispatchers.Main) {
                                            isLoggingIn = false
                                            showLoginDialog = false
                                            loginError = null
                                        }
                                    }
                                    .onFailure { exception ->
                                        coroutineScope.launch(Dispatchers.Main) {
                                            isLoggingIn = false
                                            loginError = when (exception) {
                                                is LastFmException -> {
                                                    when (exception.code) {
                                                        4 -> "Invalid username or password"
                                                        9 -> "Invalid session key"
                                                        10 -> "Invalid API key"
                                                        13 -> "Invalid method signature"
                                                        15 -> "Service temporarily unavailable"
                                                        else -> "Login failed: ${exception.message}"
                                                    }
                                                }
                                                else -> "Network error. Please check your connection."
                                            }
                                        }
                                    }
                            } catch (e: Exception) {
                                coroutineScope.launch(Dispatchers.Main) {
                                    isLoggingIn = false
                                    loginError = "Unexpected error occurred"
                                }
                            }
                        }
                    },
                    enabled = !isLoggingIn
                ) {
                    Text(stringResource(R.string.login))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!isLoggingIn) {
                            showLoginDialog = false
                            loginError = null
                        }
                    },
                    enabled = !isLoggingIn
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.account),
            items = listOf(
                Material3SettingsItem(
                    title = {
                        Text(
                            text = if (isLoggedIn) librefmUsername else stringResource(R.string.not_logged_in),
                            modifier = Modifier.alpha(if (isLoggedIn) 1f else 0.5f),
                        )
                    },
                    trailingContent = {
                        if (isLoggedIn) {
                            OutlinedButton(onClick = {
                                librefmSession = ""
                                librefmUsername = ""
                                LibreFM.sessionKey = null
                            }) {
                                Text(stringResource(R.string.action_logout))
                            }
                        } else {
                            OutlinedButton(onClick = { showLoginDialog = true }) {
                                Text(stringResource(R.string.action_login))
                            }
                        }
                    },
                    icon = painterResource(R.drawable.ic_listenbrainz)
                ),
            )
        )

        Spacer(Modifier.size(8.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.options),
            items = listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.enable_scrobbling)) },
                    trailingContent = {
                        Switch(
                            checked = librefmScrobbling,
                            onCheckedChange = onLibrefmScrobblingChange,
                            enabled = isLoggedIn,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (librefmScrobbling) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        )
                    },
                    enabled = isLoggedIn,
                    icon = painterResource(R.drawable.queue_music)
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.librefm_now_playing)) },
                    trailingContent = {
                        Switch(
                            checked = useNowPlaying,
                            onCheckedChange = onUseNowPlayingChange,
                            enabled = isLoggedIn && librefmScrobbling,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (useNowPlaying) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        )
                    },
                    enabled = isLoggedIn && librefmScrobbling,
                    icon = painterResource(R.drawable.play)
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.librefm_send_likes)) },
                    description = { stringResource(R.string.librefm_send_likes_description) },
                    trailingContent = {
                        Switch(
                            checked = useSendLikes,
                            onCheckedChange = onUseSendLikes,
                            enabled = isLoggedIn,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (useSendLikes) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        )
                    },
                    enabled = isLoggedIn,
                    icon = painterResource(R.drawable.thumb_up_like)
                )
            )
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.librefm_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
