package lol.mony.harmony.ui.screens.you

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.mony.harmony.data.model.AccountProfile
import lol.mony.harmony.data.model.AccountProfileField
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.Avatar
import lol.mony.harmony.ui.components.AvatarUrl

@Composable
internal fun AccountProfilePanel(state: AppState, repository: AccountRepository, profile: AccountProfile, onSaved: (AccountProfile) -> Unit) {
    val original = remember(profile) { profile.edit() }
    var edited by remember(profile) { mutableStateOf(original) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AccountSection("Your profile", "Your name, pronouns and bio are shared with your instance and federated profile.") {
        OutlinedTextField(
            edited.displayName, { edited = edited.copy(displayName = it); notice = null },
            label = { Text("Display name") }, supportingText = { Text("${edited.displayName.length}/50") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(profile.username.orEmpty(), {}, label = { Text("Username") }, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.pronouns, { edited = edited.copy(pronouns = it) }, label = { Text("Pronouns") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.bio, { edited = edited.copy(bio = it) }, label = { Text("Bio") }, minLines = 3, supportingText = { Text("${edited.bio.length}/500") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.color, { edited = edited.copy(color = it) }, label = { Text("Display color") }, placeholder = { Text("#0EA5E9") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
    }
    PicturePicker(state, repository, profile, onSaved)
    AccountSection("Profile fields", "Up to four labelled links or short details.") {
        edited.fields.forEachIndexed { index, field ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(field.name, { value ->
                    edited = edited.copy(fields = edited.fields.mapIndexed { i, item -> if (i == index) item.copy(name = value) else item })
                }, label = { Text("Field ${index + 1} name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(field.value, { value ->
                    edited = edited.copy(fields = edited.fields.mapIndexed { i, item -> if (i == index) item.copy(value = value) else item })
                }, label = { Text("Field ${index + 1} value") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { edited = edited.copy(fields = edited.fields.filterIndexed { i, _ -> i != index }) }, enabled = !busy) {
                    Text("Remove")
                }
            }
        }
        OutlinedButton(onClick = { edited = edited.copy(fields = edited.fields + AccountProfileField("", "")) }, enabled = !busy && edited.fields.size < 4) { Text("Add field") }
    }
    AccountStatus(error, notice)
    AccountSaveActions(busy, edited != original, onSave = {
        error = edited.validate()
        if (error == null) scope.launch {
            busy = true; notice = null
            try {
                val saved = accountRequest(state) { endpoint, token -> repository.updateProfile(endpoint, token, profile.id, original, edited).getOrThrow() }
                onSaved(saved)
                notice = "Profile saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Your profile was not saved." }
            finally { busy = false }
        }
    }, onReset = { edited = original; error = null; notice = null })
    profile.createdAt?.let { Text("Account created ${it.substringBefore('T')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/**
 * The account's own picture: what it is now, how to change it, and what the change is doing.
 *
 * The picker is Android's own photo picker, which gives this app one picture and no access to
 * the rest of the gallery. The chosen picture is shown before it is uploaded, so a person can
 * see what they are about to publish and cancel; the upload then reports its own progress and
 * failure instead of leaving a spinner that never ends. A picture is validated here -- type and
 * a sane size limit -- so the instance is not sent something it will reject.
 */
@Composable
private fun PicturePicker(state: AppState, repository: AccountRepository, profile: AccountProfile, onSaved: (AccountProfile) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Uri?>(null) }
    var previewBytes by remember { mutableStateOf<ByteArray?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val baseUrl = (state.auth.state.value as? lol.mony.harmony.data.session.AuthState.SignedIn)?.endpoint?.baseUrl

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        failure = null; notice = null
        scope.launch {
            try {
                val (bytes, extension, contentType) = withContext(Dispatchers.IO) { readPicture(context, uri) }
                previewBytes = bytes
                preview = uri
                notice = "Ready to upload (${bytes.size / 1024} KB)."
                extension.let { }
                contentType.let { }
            } catch (error: Exception) { failure = error.message ?: "That picture could not be read." }
        }
    }

    AccountSection("Profile picture", "Your picture is shown beside your messages and on your profile.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shown = preview?.let { it.toString() }
            if (shown != null) {
                AsyncImage(
                    model = shown, contentDescription = "New profile picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).testTag("avatar-preview"),
                )
            } else {
                Avatar(profile.displayName ?: profile.username ?: "You",
                    AvatarUrl.resolve(baseUrl, profile.avatarUrl), size = 72.dp,
                    modifier = Modifier.testTag("avatar-current"))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !uploading,
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.testTag("avatar-choose"),
                    ) {
                        Icon(Icons.Default.PhotoCamera, null, Modifier.size(16.dp))
                        Text("Choose", Modifier.padding(start = 6.dp))
                    }
                    if (preview != null) {
                        TextButton(enabled = !uploading, onClick = {
                            preview = null; previewBytes = null; notice = null; failure = null
                        }) { Text("Cancel") }
                    }
                }
                if (uploading) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("avatar-progress"))
                    Text("Uploading…", style = MaterialTheme.typography.labelSmall)
                } else if (preview != null) {
                    TextButton(
                        onClick = {
                            val bytes = previewBytes ?: return@TextButton
                            uploading = true; progress = 0.2f; failure = null
                            scope.launch {
                                try {
                                    val (extension, contentType) = withContext(Dispatchers.IO) { pictureKind(bytes) }
                                    val saved = accountRequest(state) { endpoint, token ->
                                        progress = 0.6f
                                        val url = repository.uploadImage(endpoint, token, "avatars", profile.id, "avatar", bytes, extension, contentType).getOrThrow()
                                        repository.setAvatar(endpoint, token, profile.id, url).getOrThrow()
                                    }
                                    progress = 1f
                                    onSaved(saved)
                                    preview = null; previewBytes = null
                                    notice = "Profile picture updated."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) { failure = error.message ?: "The picture was not uploaded." }
                                finally { uploading = false }
                            }
                        },
                        modifier = Modifier.testTag("avatar-upload"),
                    ) { Text("Upload") }
                }
            }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The largest picture this client uploads, before the instance's own limit is considered. */
private const val MAX_PICTURE_BYTES = 8 * 1024 * 1024

/** Reads a picked picture, refusing a type or size the instance would reject anyway. */
private fun readPicture(context: android.content.Context, uri: Uri): Triple<ByteArray, String, String> {
    val type = context.contentResolver.getType(uri)
        ?: throw IllegalArgumentException("That file is not a picture.")
    if (!type.startsWith("image/")) throw IllegalArgumentException("That file is not a picture.")
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("That picture could not be opened.")
    if (bytes.isEmpty()) throw IllegalArgumentException("That picture is empty.")
    if (bytes.size > MAX_PICTURE_BYTES) throw IllegalArgumentException("That picture is larger than 8 MB. Choose a smaller one.")
    // Confirm it decodes, so a mislabelled file is refused here rather than stored broken.
    if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
        throw IllegalArgumentException("That picture could not be read.")
    }
    val extension = when (type) {
        "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg"
    }
    return Triple(bytes, extension, type)
}

/** The extension and content type of bytes already read, for the upload call. */
private fun pictureKind(bytes: ByteArray): Pair<String, String> = when {
    bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png" to "image/png"
    bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() -> "webp" to "image/webp"
    bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif" to "image/gif"
    else -> "jpg" to "image/jpeg"
}
