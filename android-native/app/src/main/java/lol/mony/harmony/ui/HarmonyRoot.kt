package lol.mony.harmony.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lol.mony.harmony.BuildConfig
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.InstanceInfo
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.components.HarmonyWordmark
import lol.mony.harmony.ui.screens.instance.InstancePickerScreen
import lol.mony.harmony.ui.screens.signin.AuthFormScreen
import lol.mony.harmony.ui.screens.signin.AuthFormMode
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * The app's root: pick an instance, sign in, then the signed-in shell.
 *
 * Appearance loads as soon as there is a profile - the account's copy unless this device keeps
 * its own look, which is the web client's own rule - so the very first screen already wears the
 * person's colours rather than flashing a default.
 */
@Composable
fun HarmonyRoot(state: AppState) {
    val authState by state.auth.state.collectAsState()
    var settings by remember { mutableStateOf(VisualThemeSettings()) }
    var showInstancePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state.auth.restore(
            Endpoint(BuildConfig.DEFAULT_INSTANCE_URL, BuildConfig.DEFAULT_INSTANCE_ANON_KEY)
                .takeIf { BuildConfig.DEFAULT_INSTANCE_ANON_KEY.isNotBlank() },
        )
    }

    val signedIn = authState as? AuthState.SignedIn
    LaunchedEffect(signedIn?.user?.id) {
        if (signedIn == null) {
            // Signed out: fall back to the last look this device used, as the web client does.
            settings = VisualThemeSettings()
            return@LaunchedEffect
        }
        val endpoint = signedIn.endpoint
        val token = state.auth.freshToken() ?: return@LaunchedEffect
        val profile = signedIn.profile ?: return@LaunchedEffect
        state.profileId.value = profile.id
        state.rememberPeople(listOf(profile))
        instanceInfo = state.servers.instanceInfo(endpoint, token)
        state.applyUnread(state.chats.unreadCounts(endpoint, token))
        val deviceOnly = state.appearanceIsDeviceOnly()
        val account = state.settings.appearance(endpoint, token, profile.id)
        val device = state.deviceAppearance.value
        settings = if (deviceOnly && device != null) device else account ?: VisualThemeSettings()
    }

    HarmonyTheme(settings = settings) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AnimatedContent(
                targetState = authState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "auth",
            ) { current ->
                when (current) {
                    is AuthState.Loading -> LoadingSplash()
                    is AuthState.NeedsInstance -> InstancePickerScreen(
                        initialUrl = null,
                        onUse = { endpoint, name ->
                            showInstancePicker = false
                            state.auth.chooseInstance(endpoint, name)
                        },
                        onCancel = null,
                    )
                    is AuthState.SignedOut -> {
                        if (showInstancePicker) {
                            InstancePickerScreen(
                                initialUrl = current.endpoint.baseUrl,
                                onUse = { endpoint, name ->
                                    showInstancePicker = false
                                    state.auth.chooseInstance(endpoint, name)
                                },
                                onCancel = { showInstancePicker = false },
                            )
                        } else {
                            AuthFormScreen(
                                instanceName = instanceInfo?.name,
                                instanceDomain = instanceInfo?.domain ?: current.endpoint.baseUrl,
                                onSignIn = { email, password -> state.auth.signIn(email, password) },
                                onRegister = { email, password, username ->
                                    state.auth.signUp(email, password, username)
                                },
                                onForgotPassword = { email -> state.auth.requestPasswordReset(email) },
                                onChangeInstance = { showInstancePicker = true },
                            )
                        }
                    }
                    is AuthState.SignedIn -> SignedInShell(
                        state = state,
                        appearance = settings,
                        onAppearanceChange = { updated, persist ->
                            settings = updated
                            state.rememberDeviceAppearance(updated)
                            if (persist) state.persistAccountAppearance(updated)
                        },
                        onSignOut = { state.signOut() },
                        onInstanceChange = { showInstancePicker = true },
                    )
                }
            }
        }
    }
}

/** The instance's name and domain, kept for the sign-in header and settings. */
var instanceInfo: InstanceInfo? = null

@Composable
private fun LoadingSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HarmonyWordmark()
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 32.dp)
                    .size(28.dp),
                strokeWidth = 3.dp,
            )
            Text(
                text = "Connecting to your instance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
