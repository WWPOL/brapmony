package lol.mony.harmony.ui.screens.instance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.ui.components.HarmonyWordmark
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import lol.mony.harmony.data.api.Http

/**
 * Choose the instance to connect to, as the native Harmony clients ask on first launch.
 *
 * The app has to learn two things about an instance: where it is and its anon key. Public
 * instances serve both at `/api/federation/instance-info` - the same file the web client's build reads - so a
 * person types a domain and the app discovers the key. A bare host gets `https://`; anything
 * already carrying a scheme is used as given.
 */
@Composable
fun InstancePickerScreen(
    initialUrl: String?,
    onUse: (Endpoint, String?) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var url by remember { mutableStateOf(initialUrl ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discoveredName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun discoverThenUse(target: String) {
        if (busy || target.isBlank()) return
        busy = true
        error = null
        discoveredName = null
        scope.launch {
            when (val found = discoverInstance(target)) {
                is Discovery.Failed -> error = found.message
                is Discovery.Found -> {
                    discoveredName = found.name
                    busy = false
                    onUse(found.endpoint, found.name)
                    return@launch
                }
            }
            busy = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HarmonyWordmark()
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Choose an instance",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Harmony is federated. Enter the address of the instance you use - for " +
                    "example har.mony.lol - and sign in there.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 20.dp),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Instance address") },
                placeholder = { Text("har.mony.lol") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { discoverThenUse(url.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("instance-url"),
            )
            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("instance-error"),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { discoverThenUse(url.trim()) },
                enabled = !busy && url.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("instance-connect"),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (onCancel != null) {
                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
}

/** What an instance's `/config.json` says about itself. */
sealed interface Discovery {
    data class Found(val endpoint: Endpoint, val name: String?) : Discovery
    data class Failed(val message: String) : Discovery
}

/** Native-client discovery uses the same federation endpoint as the official picker. */
@kotlinx.serialization.Serializable
private data class InstanceConfiguration(
    val name: String? = null,
    val supabaseUrl: String,
    val supabaseAnonKey: String,
)

suspend fun discoverInstance(raw: String): Discovery = withContext(Dispatchers.IO) {
    val address = raw.trim()
    val url = (if (address.contains("://")) address else "https://$address").toHttpUrlOrNull() ?: return@withContext Discovery.Failed("Enter a valid instance address.")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
        return@withContext Discovery.Failed("Use an instance address without a password, query or fragment.")
    }
    val loopback = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
    if (!url.isHttps && !(lol.mony.harmony.BuildConfig.DEBUG && loopback)) {
        return@withContext Discovery.Failed("Use HTTPS to protect your account.")
    }
    val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    try {
        Http.client.newCall(Request.Builder().url("$origin/api/federation/instance-info")
            .header("Accept", "application/json").get().build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext Discovery.Failed(
                "The instance answered ${response.code}. Check that this is a Harmony instance.")
            val config = lol.mony.harmony.data.model.HarmonyJson.decodeFromString(
                InstanceConfiguration.serializer(), response.body.string())
            val service = config.supabaseUrl.toHttpUrlOrNull()
                ?: return@withContext Discovery.Failed("The instance returned an invalid service address.")
            if ((!service.isHttps && !(lol.mony.harmony.BuildConfig.DEBUG &&
                    service.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) ||
                config.supabaseAnonKey.isBlank()) {
                return@withContext Discovery.Failed("The instance did not publish a secure usable configuration.")
            }
            Discovery.Found(Endpoint(service.toString().trimEnd('/'), config.supabaseAnonKey, origin), config.name)
        }
    } catch (error: Exception) {
        Discovery.Failed("Could not connect. Check the address and your network connection.")
    }
}
