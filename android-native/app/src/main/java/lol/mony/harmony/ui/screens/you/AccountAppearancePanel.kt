package lol.mony.harmony.ui.screens.you

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.AccountAppearance
import lol.mony.harmony.data.model.AccountProfile
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.LocalSizes
import lol.mony.harmony.ui.theme.Oklch
import lol.mony.harmony.ui.theme.VisualThemeSettings
import kotlin.math.roundToInt

@Composable
internal fun AccountAppearancePanel(
    state: AppState, repository: AccountRepository, profile: AccountProfile,
    appearance: VisualThemeSettings, onAppearanceChange: (VisualThemeSettings, Boolean) -> Unit,
) {
    var baseline by remember(profile.id) { mutableStateOf(VisualThemeSettings.fromJson(profile.appearanceSettings)) }
    var edited by remember(appearance) { mutableStateOf(appearance) }
    var deviceOnly by remember(profile.id) { mutableStateOf(false) }
    var savedDeviceOnly by remember(profile.id) { mutableStateOf(false) }
    var scopeLoaded by remember(profile.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(profile.id) {
        try { deviceOnly = state.appearanceIsDeviceOnly(); savedDeviceOnly = deviceOnly; scopeLoaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Appearance scope could not be loaded." }
    }
    AccountSection("Your look", "Preview changes here, then save to your account or just this device.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "Dark", "light" to "Light", "midnight" to "Midnight", "system" to "System", "custom" to "Custom").forEach { (id, label) ->
                FilterChip(edited.theme == id, onClick = { edited = edited.copy(theme = id) }, enabled = !busy, label = { Text(label) })
            }
        }
        HarmonyTheme(edited) {
            Surface(
                color = LocalHarmonyColors.current.surfaceChat,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(if (edited.compactMode) 3.dp else 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(profile.displayName ?: profile.username ?: "You", style = MaterialTheme.typography.titleSmall)
                        if (edited.showTimestamps) Text(if (edited.use24HourTime) "14:32" else "2:32 PM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Make yourself at home.", fontSize = LocalSizes.current.messageSp.sp)
                    Text("Message preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (edited.theme == "custom") AccountSection("Custom colors", "Use a hex color or OKLCH value. Empty fields use the theme's defaults.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(edited.customThemeMode != "light", { edited = edited.copy(customThemeMode = "dark") }, label = { Text("Dark base") }, enabled = !busy)
            FilterChip(edited.customThemeMode == "light", { edited = edited.copy(customThemeMode = "light") }, label = { Text("Light base") }, enabled = !busy)
        }
        AppearanceColorField("Primary", edited.customPrimaryColor, busy) { edited = edited.copy(customPrimaryColor = it) }
        AppearanceColorField("Accent", edited.customAccentColor, busy) { edited = edited.copy(customAccentColor = it) }
        AppearanceColorField("Background", edited.customBackgroundColor, busy) { edited = edited.copy(customBackgroundColor = it) }
        AppearanceColorField("Sidebar", edited.customSidebarColor, busy) { edited = edited.copy(customSidebarColor = it) }
        AppearanceSlider("Background lightness", edited.customBackgroundLightness, -50..50, "%", !busy) { edited = edited.copy(customBackgroundLightness = it) }
        AppearanceSlider("Background chroma", edited.customBackgroundChroma, -30..30, "%", !busy) { edited = edited.copy(customBackgroundChroma = it) }
    }
    AccountSection("Text and layout") {
        AppearanceSlider("Message text size", edited.fontSize, 10..32, "sp", !busy) { edited = edited.copy(fontSize = it) }
        AppearanceSlider("Zoom", edited.zoomLevel, 50..200, "%", !busy) { edited = edited.copy(zoomLevel = it) }
        Text("Font", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(edited.fontFamily == "system", { edited = edited.copy(fontFamily = "system") }, enabled = !busy, label = { Text("System") })
            FilterChip(edited.fontFamily == "pixel", { edited = edited.copy(fontFamily = "pixel") }, enabled = !busy, label = { Text("Monospace") })
        }
        if (edited.fontFamily !in setOf("system", "pixel")) Text("Account font: ${edited.fontFamily}. This device uses the system font until you choose a native font.", style = MaterialTheme.typography.bodySmall)
        AccountToggle("Show timestamps", checked = edited.showTimestamps, enabled = !busy) { edited = edited.copy(showTimestamps = it) }
        AccountToggle("Use 24-hour time", checked = edited.use24HourTime, enabled = !busy) { edited = edited.copy(use24HourTime = it) }
        AccountToggle("Compact messages", checked = edited.compactMode, enabled = !busy) { edited = edited.copy(compactMode = it) }
    }
    AccountSection("Accessibility") {
        AccountToggle("High contrast", checked = edited.highContrast, enabled = !busy) { edited = edited.copy(highContrast = it) }
        AccountToggle("Reduce motion", checked = edited.reduceMotion, enabled = !busy) { edited = edited.copy(reduceMotion = it) }
        AccountToggle("Screen reader support", "Android accessibility labels are always available.", edited.screenReaderSupport, !busy) { edited = edited.copy(screenReaderSupport = it) }
    }
    AccountSection("Where to save") {
        AccountToggle("Keep this device's own look", "When off, your look follows your account across clients. Saving while on does not change the account copy.", deviceOnly, !busy && scopeLoaded) { deviceOnly = it }
        OutlinedButton(enabled = !busy && scopeLoaded, onClick = {
            scope.launch {
                busy = true; error = null; notice = null
                try {
                    val accountLook = accountRequest(state) { endpoint, token -> repository.appearance(endpoint, token, profile.id).getOrThrow() }
                    state.setAppearanceDeviceOnly(false)
                    baseline = accountLook; edited = accountLook; deviceOnly = false; savedDeviceOnly = false
                    onAppearanceChange(accountLook, false)
                    notice = "Using your account's saved look."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "The account look could not be loaded." }
                finally { busy = false }
            }
        }) { Text("Use account's saved look") }
        Text("Web CSS and skins cannot run in Android. They are preserved in your account, not replaced or presented as a native preview.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AccountStatus(error, notice)
        AccountSaveActions(busy, scopeLoaded && (edited != appearance || deviceOnly != savedDeviceOnly), onSave = {
            val invalidColor = if (edited.theme == "custom") listOf(edited.customPrimaryColor, edited.customAccentColor, edited.customBackgroundColor, edited.customSidebarColor)
                .firstOrNull { !it.isNullOrBlank() && Oklch.parse(it) == null } else null
            error = if (invalidColor != null) "A custom color is invalid. Use a hex color such as #0EA5E9 or an OKLCH value." else null
            if (error == null) scope.launch {
                busy = true; notice = null
                try {
                    val saved = if (deviceOnly) edited else accountRequest(state) { endpoint, token ->
                        repository.saveAppearance(endpoint, token, profile.id, baseline, edited).getOrThrow()
                    }
                    state.setAppearanceDeviceOnly(deviceOnly)
                    savedDeviceOnly = deviceOnly
                    if (!deviceOnly) baseline = saved
                    edited = saved
                    // Account writes above are awaited and checked. false prevents a duplicate
                    // unobservable write; the parent applies and persists this device's copy.
                    onAppearanceChange(saved, false)
                    notice = if (deviceOnly) "Look saved on this device. Your account look is unchanged." else "Account appearance saved."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Appearance was not saved." }
                finally { busy = false }
            }
        }, onReset = { edited = AccountAppearance.reset(edited); notice = "Default appearance ready. Save to apply it."; error = null }, saveLabel = if (deviceOnly) "Save on device" else "Save to account")
        TextButton(onClick = { edited = appearance; deviceOnly = savedDeviceOnly; error = null; notice = null }, enabled = !busy) { Text("Discard edits") }
    }
}

@Composable
private fun AppearanceColorField(label: String, value: String?, busy: Boolean, onChange: (String?) -> Unit) {
    OutlinedTextField(
        value.orEmpty(), { onChange(it.takeIf(String::isNotBlank)) }, label = { Text(label) },
        placeholder = { Text("Default") }, singleLine = true, enabled = !busy,
        isError = !value.isNullOrBlank() && Oklch.parse(value) == null, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AppearanceSlider(label: String, value: Int, range: IntRange, unit: String, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value$unit", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled, modifier = Modifier.semantics { contentDescription = label; stateDescription = "$value$unit" },
        )
    }
}
