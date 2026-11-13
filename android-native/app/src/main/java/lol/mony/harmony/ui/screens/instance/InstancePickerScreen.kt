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
import lol.mony.harmony.data.api.Http

/**
 * Choose the instance to connect to, as the native Harmony clients ask on first launch.
 *
 * The app has to learn two things about an instance: where it is and its anon key. Public
 * instances serve both at `/config.json` - the same file the web client's build reads - so a
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

/**
 * Reads `/config.json` from an instance.
 *
 * A native client cannot know an instance's anon key in advance. Every Harmony instance serves
 * it in that file next to its Supabase URL, which is exactly the pair the web client is built
 * with - so the picker asks the instance instead of the person.
 */
suspend fun discoverInstance(raw: String): Discovery = withContext(Dispatchers.IO) {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return@withContext Discovery.Failed("Enter the instance's address.")
    val base = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("localhost") || trimmed.startsWith("10.0.2.2") || trimmed.startsWith("127.0.0.1") ->
            "http://$trimmed"
        else -> "https://$trimmed"
    }
    val request = Request.Builder().url("$base/config.json").get().build()
    try {
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext Discovery.Failed(
                    "$base answered ${response.code}. Check the address, then try again.",
                )
            }
            val text = response.body.string()
            val json = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: return@withContext Discovery.Failed("$base did not answer with JSON.")

            @kotlinx.serialization.Serializable
            data class Config(
                val supabaseUrl: String? = null,
                val supabaseAnonKey: String? = null,
                val instanceName: String? = null,
                val name: String? = null,
                val domain: String? = null,
            }

            val config = runCatching {
                lol.mony.harmony.data.model.HarmonyJson.decodeFromString(Config.serializer(), text)
            }.getOrNull() ?: Config()

            val supabaseUrl = config.supabaseUrl?.takeIf { it.isNotBlank() } ?: base
            val anonKey = config.supabaseAnonKey?.takeIf { it.isNotBlank() }
                ?: return@withContext Discovery.Failed(
                    "$base does not publish an anon key in config.json, so the app cannot sign in.",
                )
            Discovery.Found(
                Endpoint(supabaseUrl, anonKey),
                config.instanceName?.takeIf { it.isNotBlank() } ?: config.name ?: config.domain,
            )
        }
    } catch (e: Exception) {
        Discovery.Failed("Could not reach $base: ${e.message ?: "unknown error"}")
    }
}

/** A local development instance, offered as a shortcut so a developer need not type it. */
const val LOOPBACK_INSTANCE = "http://10.0.2.2:54480"
