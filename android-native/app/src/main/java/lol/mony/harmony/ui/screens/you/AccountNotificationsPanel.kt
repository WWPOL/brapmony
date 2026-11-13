package lol.mony.harmony.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.AccountNotifications
import lol.mony.harmony.data.model.AccountQuietHours
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.ui.AppState
import java.time.ZoneId

@Composable
internal fun AccountNotificationsPanel(state: AppState, repository: AccountRepository, profileId: String) {
    var original by remember(profileId) { mutableStateOf<AccountNotifications?>(null) }
    var edited by remember(profileId) { mutableStateOf(AccountNotifications()) }
    var quietStart by remember(profileId) { mutableStateOf("") }
    var quietEnd by remember(profileId) { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    LaunchedEffect(profileId, reload) {
        loading = true; error = null
        try {
            val loaded = accountRequest(state) { endpoint, token -> repository.notifications(endpoint, token, profileId).getOrThrow() }
            original = loaded; edited = loaded
            quietStart = AccountQuietHours.local(loaded.quietStart, zone)
            quietEnd = AccountQuietHours.local(loaded.quietEnd, zone)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Notification preferences could not be loaded." }
        finally { loading = false }
    }
    if (original == null) AccountStatus(error, null)
    if (loading) CircularProgressIndicator()
    if (original == null) {
        if (!loading) TextButton(onClick = { reload++ }) { Text("Retry notification preferences") }
        return
    }
    AccountSection("Notifications", "These are your account's notification policies, shared across clients. Saving a push preference does not register this Android device for background push.") {
        AccountToggle("Chat alerts", checked = edited.desktop, enabled = !busy) { edited = edited.copy(desktop = it) }
        AccountToggle("Mentions", checked = edited.mentions, enabled = !busy) { edited = edited.copy(mentions = it) }
        AccountToggle("Direct messages", checked = edited.dms, enabled = !busy) { edited = edited.copy(dms = it) }
        AccountToggle("Replies", checked = edited.replies, enabled = !busy) { edited = edited.copy(replies = it) }
        AccountToggle("Reactions", checked = edited.reactions, enabled = !busy) { edited = edited.copy(reactions = it) }
        AccountToggle("Federated activity", checked = edited.activityPub, enabled = !busy) { edited = edited.copy(activityPub = it) }
    }
    AccountSection("Sound") {
        AccountToggle("Notification sounds", checked = edited.sound, enabled = !busy) { edited = edited.copy(sound = it) }
        AccountToggle("Mention sounds", checked = edited.soundMentions, enabled = !busy) { edited = edited.copy(soundMentions = it) }
        AccountToggle("Direct message sounds", checked = edited.soundDms, enabled = !busy) { edited = edited.copy(soundDms = it) }
        AccountToggle("Reply sounds", checked = edited.soundReplies, enabled = !busy) { edited = edited.copy(soundReplies = it) }
        AccountToggle("Reaction sounds", checked = edited.soundReactions, enabled = !busy) { edited = edited.copy(soundReactions = it) }
        AccountToggle("Voice activity sounds", checked = edited.soundVoice, enabled = !busy) { edited = edited.copy(soundVoice = it) }
    }
    AccountSection("Push to subscribed devices", "These preferences affect devices already subscribed through a supported push transport.") {
        AccountToggle("Push notifications", checked = edited.push, enabled = !busy) { edited = edited.copy(push = it) }
        AccountToggle("Only push when away", checked = edited.pushOfflineOnly, enabled = !busy) { edited = edited.copy(pushOfflineOnly = it) }
        AccountToggle("Push mentions", checked = edited.pushMentions, enabled = !busy) { edited = edited.copy(pushMentions = it) }
        AccountToggle("Push direct messages", checked = edited.pushDms, enabled = !busy) { edited = edited.copy(pushDms = it) }
    }
    AccountSection("Quiet hours", "Quiet hours silence alerts; notifications still arrive in your inbox. Times are displayed in ${zone.id} and saved in UTC, like the web client.") {
        AccountToggle("Enable quiet hours", checked = edited.dnd, enabled = !busy) { edited = edited.copy(dnd = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(quietStart, { quietStart = it }, label = { Text("Start (HH:mm)") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
            OutlinedTextField(quietEnd, { quietEnd = it }, label = { Text("End (HH:mm)") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
        }
    }
    val before = original ?: return
    val startChanged = quietStart != runCatching { AccountQuietHours.local(before.quietStart, zone) }.getOrNull()
    val endChanged = quietEnd != runCatching { AccountQuietHours.local(before.quietEnd, zone) }.getOrNull()
    val timeChanged = startChanged || endChanged
    AccountStatus(error, notice)
    AccountSaveActions(busy, edited != before || timeChanged, onSave = {
        error = null; notice = null
        val withTime = runCatching {
            require(Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(quietStart) && Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(quietEnd)) {
                "Enter quiet hours as HH:mm, using a 24-hour clock."
            }
            edited.copy(
                quietStart = if (startChanged) AccountQuietHours.utc(quietStart, zone) else before.quietStart,
                quietEnd = if (endChanged) AccountQuietHours.utc(quietEnd, zone) else before.quietEnd,
            )
        }.getOrElse { error = it.message; null }
        if (withTime != null) scope.launch {
            busy = true
            try {
                val saved = accountRequest(state) { endpoint, token -> repository.saveNotifications(endpoint, token, profileId, before, withTime).getOrThrow() }
                original = saved; edited = saved
                quietStart = AccountQuietHours.local(saved.quietStart, zone); quietEnd = AccountQuietHours.local(saved.quietEnd, zone)
                notice = "Notification preferences saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Notification preferences were not saved." }
            finally { busy = false }
        }
    }, onReset = {
        edited = before; quietStart = AccountQuietHours.local(before.quietStart, zone); quietEnd = AccountQuietHours.local(before.quietEnd, zone)
        error = null; notice = null
    })
}
