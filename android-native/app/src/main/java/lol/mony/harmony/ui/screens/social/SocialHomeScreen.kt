package lol.mony.harmony.ui.screens.social

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.SocialRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.Avatar

@Composable
fun SocialHomeScreen(state: AppState) = SocialFeature(state, SocialRoute.Feed)

/** Notification routes can embed the same detail UI without constructing a separate repository. */
@Composable
fun SocialPostDetailScreen(state: AppState, postId: String, onClose: () -> Unit) =
    SocialFeature(state, SocialRoute.Post(postId), onClose)

@Composable
fun SocialProfileScreen(state: AppState, profileId: String, onClose: () -> Unit) =
    SocialFeature(state, SocialRoute.Profile(profileId), onClose)

private sealed interface SocialRoute {
    data object Feed : SocialRoute
    data object Search : SocialRoute
    data object Lists : SocialRoute
    data class Post(val id: String) : SocialRoute
    data class Profile(val id: String) : SocialRoute
    data class Tag(val name: String) : SocialRoute
    data class ListFeed(val list: SocialList) : SocialRoute
    data class Members(val list: SocialList) : SocialRoute
    data class People(val id: String, val followers: Boolean, val pending: Boolean = false) : SocialRoute
}
private data class SocialAccess(val endpoint: Endpoint, val token: String, val profileId: String)
private data class SocialDraft(val reply: SocialPost? = null, val edit: SocialPost? = null)

private suspend fun AppState.socialAccess(): SocialAccess {
    val signedIn = auth.state.value as? AuthState.SignedIn ?: error("Sign in to use social features.")
    val id = profileId.value ?: signedIn.profile?.id ?: error("Your account profile is not available. Sign in again.")
    val token = auth.freshToken() ?: error("Your session expired. Sign in again.")
    val active = auth.state.value as? AuthState.SignedIn ?: error("Your session ended. Sign in again.")
    check(active.endpoint == signedIn.endpoint && (profileId.value ?: active.profile?.id) == id) {
        "The signed-in account changed. Reopen social features."
    }
    return SocialAccess(signedIn.endpoint, token, id)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialFeature(state: AppState, initial: SocialRoute, onClose: (() -> Unit)? = null) {
    val session by state.auth.state.collectAsState()
    val id by state.profileId.collectAsState()
    val signedIn = session as? AuthState.SignedIn
    val profileId = id ?: signedIn?.profile?.id
    if (signedIn == null || profileId == null) {
        SocialMessage("Your account profile is not available. Sign in again to load social features.")
        return
    }
    val repo = remember(state) { SocialRepository(state.container.rest) }
    val scope = rememberCoroutineScope()
    val routes = remember(profileId, initial) { mutableStateListOf(initial) }
    val route = routes.last()
    val navigate: (SocialRoute) -> Unit = { if (routes.last() != it) routes.add(it) }
    val back: () -> Unit = { if (routes.size > 1) routes.removeAt(routes.lastIndex) else onClose?.invoke() }
    BackHandler(enabled = routes.size > 1 || onClose != null, onBack = back)
    var timelineName by rememberSaveable(profileId) { mutableStateOf(SocialTimeline.Home.name) }
    val timeline = SocialTimeline.valueOf(timelineName)
    var revision by remember(profileId) { mutableIntStateOf(0) }
    val updates = remember(profileId) { mutableStateMapOf<String, SocialPost>() }
    val busy = remember(profileId) { mutableStateMapOf<String, Boolean>() }
    var actionError by remember(profileId) { mutableStateOf<String?>(null) }
    var draft by remember(profileId) { mutableStateOf<SocialDraft?>(null) }
    var deleting by remember(profileId) { mutableStateOf<SocialPost?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var listEditor by remember { mutableStateOf<SocialList?>(null) }
    var creatingList by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    fun mutate(post: SocialPost, reload: Boolean = false, operation: suspend (SocialAccess) -> SocialPost) {
        if (busy[post.id] == true) return
        busy[post.id] = true
        scope.launch {
            try {
                val updated = operation(state.socialAccess())
                updates[updated.id] = updated
                if (reload) revision++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { actionError = e.message ?: "The instance could not complete the change. Refresh before trying again." }
            finally { busy.remove(post.id) }
        }
    }
    val postContent: @Composable (SocialPost) -> Unit = { raw ->
        val canonical = updates[raw.actionPost.id]
        val row = if (raw.isBoost && canonical != null) raw.copy(reblog = canonical) else updates[raw.id] ?: raw
        SocialPostCard(row, profileId, signedIn.endpoint.baseUrl, busy[row.actionPost.id] == true,
            onOpen = { navigate(SocialRoute.Post(it)) }, onProfile = { navigate(SocialRoute.Profile(it)) }, onHashtag = { navigate(SocialRoute.Tag(it)) },
            onReply = { draft = SocialDraft(reply = it) }, onEdit = { draft = SocialDraft(edit = it) }, onDelete = { deleting = it },
            onFavorite = { post -> mutate(post) { a -> repo.setInteraction(a.endpoint, a.token, a.profileId, post.id, "favorite", !post.isFavorited) } },
            onBookmark = { post -> mutate(post, reload = route == SocialRoute.Feed && timeline == SocialTimeline.Bookmarks) { a -> repo.setInteraction(a.endpoint, a.token, a.profileId, post.id, "bookmark", !post.isBookmarked) } },
            onBoost = { post -> mutate(post, reload = true) { a -> repo.setBoost(a.endpoint, a.token, a.profileId, post.id, !post.isReblogged) } })
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(when (route) {
                SocialRoute.Feed -> "Social"
                SocialRoute.Search -> "Find accounts and tags"
                SocialRoute.Lists -> "Your lists"
                is SocialRoute.Post -> "Conversation"
                is SocialRoute.Profile -> "Profile"
                is SocialRoute.Tag -> "#${route.name.removePrefix("#")}"
                is SocialRoute.ListFeed -> route.list.title
                is SocialRoute.Members -> "List members"
                is SocialRoute.People -> if (route.pending) "Follow requests" else if (route.followers) "Followers" else "Following"
            }) }, navigationIcon = {
                if (routes.size > 1 || onClose != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            }, actions = {
                IconButton(onClick = { updates.clear(); revision++ }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                if (route == SocialRoute.Feed) {
                    IconButton(onClick = { navigate(SocialRoute.Search) }) { Icon(Icons.Outlined.Search, "Find accounts or hashtags") }
                    IconButton(onClick = { navigate(SocialRoute.Lists) }) { Icon(Icons.Outlined.ListAlt, "Lists") }
                    IconButton(onClick = { navigate(SocialRoute.Profile(profileId)) }) { Icon(Icons.Outlined.AccountCircle, "Your profile") }
                }
                if (route == SocialRoute.Lists) IconButton(onClick = { creatingList = true }) { Icon(Icons.Outlined.Add, "Create list") }
                if (route is SocialRoute.ListFeed) {
                    IconButton(onClick = { navigate(SocialRoute.Members(route.list)) }) { Icon(Icons.Outlined.Group, "Manage list members") }
                    IconButton(onClick = { listEditor = route.list }) { Icon(Icons.Outlined.Edit, "Edit list") }
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (route !is SocialRoute.Members && route != SocialRoute.Lists && route != SocialRoute.Search) {
                ExtendedFloatingActionButton(onClick = { draft = SocialDraft() }, icon = { Icon(Icons.Outlined.Edit, null) }, text = { Text("New post") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            actionError?.let { shown -> SocialError(shown, onRetry = { updates.clear(); revision++; actionError = null }, onDismiss = { actionError = null }) }
            when (route) {
                SocialRoute.Feed -> {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SocialTimeline.entries.forEach { tab -> FilterChip(selected = tab == timeline, onClick = { timelineName = tab.name }, label = { Text(tab.label) }) }
                    }
                    SocialPostFeed("timeline:$timeline:$profileId", revision, postContent,
                        emptyText = when (timeline) {
                            SocialTimeline.Home -> "Your following feed is quiet. Find accounts to follow, or write your first post."
                            SocialTimeline.Local -> "No public posts from this instance yet."
                            SocialTimeline.Federated -> "No public posts from other instances are stored here yet."
                            SocialTimeline.Explore -> "No trending posts in the last 24 hours."
                            SocialTimeline.Bookmarks -> "Save a post with its bookmark button to find it here."
                        }, header = { if (timeline == SocialTimeline.Explore) SocialTrendingTags(state, repo, revision) { navigate(SocialRoute.Tag(it)) } },
                        load = { cursor -> val a = state.socialAccess(); repo.timeline(a.endpoint, a.token, a.profileId, timeline, cursor) })
                }
                is SocialRoute.Post -> SocialDetail(state, repo, route.id, revision, postContent)
                is SocialRoute.Profile -> SocialProfileContent(state, repo, route.id, revision, postContent, navigate)
                SocialRoute.Search -> SocialSearch(state, repo, onProfile = { navigate(SocialRoute.Profile(it)) }, onTag = { navigate(SocialRoute.Tag(it)) })
                SocialRoute.Lists -> SocialLists(state, repo, revision, onOpen = { navigate(SocialRoute.ListFeed(it)) }, onEdit = { listEditor = it }, onChanged = { revision++ })
                is SocialRoute.ListFeed -> SocialPostFeed("list:${route.list.id}", revision, postContent, "Add followed accounts to this list to see their public posts.",
                    header = { if (!route.list.description.isNullOrBlank()) Text(route.list.description, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) },
                    load = { cursor -> val a = state.socialAccess(); repo.listTimeline(a.endpoint, a.token, a.profileId, route.list.id, cursor) })
                is SocialRoute.Members -> SocialMembers(state, repo, route.list, revision) { revision++ }
                is SocialRoute.Tag -> SocialPostFeed("tag:${route.name}", revision, postContent, "No public posts carry this hashtag yet.",
                    load = { cursor -> val a = state.socialAccess(); repo.hashtagPosts(a.endpoint, a.token, a.profileId, route.name, cursor) })
                is SocialRoute.People -> SocialPeople(state, repo, route, revision, onProfile = { navigate(SocialRoute.Profile(it)) })
            }
        }
    }
    draft?.let { current ->
        SocialComposer(state, repo, current, onDismiss = { draft = null }, onPublished = { post ->
            draft = null; updates.clear(); revision++
            scope.launch { snackbar.showSnackbar(if (current.edit != null) "Post updated" else "Post published") }
            if (current.reply != null) navigate(SocialRoute.Post(current.reply.id))
        })
    }
    if (deleting != null) AlertDialog(onDismissRequest = { if (!deleteBusy) deleting = null }, title = { Text("Delete this post?") },
        text = { Text("The instance will mark this post as deleted and queue its removal for federation. This cannot be undone.") },
        confirmButton = { TextButton(enabled = !deleteBusy, onClick = {
            val post = deleting ?: return@TextButton
            deleteBusy = true
            scope.launch {
                try {
                    val a = state.socialAccess(); repo.deletePost(a.endpoint, a.token, a.profileId, post.id)
                    deleting = null; updates.clear(); revision++
                    if ((route as? SocialRoute.Post)?.id == post.id) back()
                    snackbar.showSnackbar("Post deleted")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { actionError = e.message ?: "Post could not be deleted."; deleting = null }
                finally { deleteBusy = false }
            }
        }) { Text(if (deleteBusy) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !deleteBusy) { Text("Keep post") } })
    if (creatingList || listEditor != null) SocialListEditor(state, repo, listEditor, onDismiss = { creatingList = false; listEditor = null }, onSaved = { saved ->
        creatingList = false; listEditor = null; revision++
        if (route is SocialRoute.ListFeed && route.list.id == saved.id) routes[routes.lastIndex] = SocialRoute.ListFeed(saved)
    })
}

@Composable
private fun SocialPostFeed(
    feedKey: String, revision: Int, postContent: @Composable (SocialPost) -> Unit, emptyText: String,
    header: @Composable () -> Unit = {}, load: suspend (SocialCursor?) -> SocialPage,
) {
    var posts by remember(feedKey) { mutableStateOf<List<SocialPost>>(emptyList()) }
    var cursor by remember(feedKey) { mutableStateOf<SocialCursor?>(null) }
    var loading by remember(feedKey) { mutableStateOf(true) }
    var loaded by remember(feedKey) { mutableStateOf(false) }
    var retryReset by remember(feedKey) { mutableStateOf(true) }
    var error by remember(feedKey) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val currentLoad by rememberUpdatedState(load)
    suspend fun fetch(reset: Boolean) {
        loading = true; error = null; retryReset = reset
        try {
            val result = currentLoad(if (reset) null else cursor)
            posts = if (reset) result.posts else (posts + result.posts).distinctBy { it.id }
            cursor = result.next; loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "The instance could not load these posts." }
        finally { loading = false }
    }
    LaunchedEffect(feedKey, revision) { fetch(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item { header() }
        if (loading && !loaded) item { SocialLoading() }
        error?.let { shown -> item { SocialError(shown, onRetry = { scope.launch { fetch(retryReset) } }) } }
        if (loaded && posts.isEmpty() && !loading && error == null) item { SocialMessage(emptyText) }
        items(posts, key = { it.id }) { postContent(it) }
        if (loaded && cursor != null) item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                OutlinedButton(onClick = { scope.launch { fetch(false) } }, enabled = !loading) { Text(if (loading) "Loading…" else "Load more") }
            }
        }
        if (loading && loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SocialDetail(state: AppState, repo: SocialRepository, postId: String, revision: Int, postContent: @Composable (SocialPost) -> Unit) {
    var thread by remember(postId) { mutableStateOf<SocialThread?>(null) }
    SocialPostFeed("post:$postId", revision, postContent, "No replies yet. Start a conversation with Reply.",
        header = {
            thread?.let { detail ->
                for (ancestor in detail.ancestors) postContent(ancestor)
                postContent(detail.post)
                Text("Replies", Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
            }
        }, load = { cursor ->
            val a = state.socialAccess()
            if (cursor == null) repo.thread(a.endpoint, a.token, a.profileId, postId).also { thread = it }.replies
            else repo.replies(a.endpoint, a.token, a.profileId, postId, cursor)
        })
}

@Composable
private fun SocialProfileContent(
    state: AppState, repo: SocialRepository, targetId: String, revision: Int,
    postContent: @Composable (SocialPost) -> Unit, navigate: (SocialRoute) -> Unit,
) {
    var profile by remember(targetId) { mutableStateOf<SocialProfile?>(null) }
    var relationship by remember(targetId) { mutableStateOf<SocialRelationship?>(null) }
    var followBusy by remember(targetId) { mutableStateOf(false) }
    var error by remember(targetId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ownId by state.profileId.collectAsState()
    val authState by state.auth.state.collectAsState()
    val own = targetId == (ownId ?: (authState as? AuthState.SignedIn)?.profile?.id)
    SocialPostFeed("profile:$targetId", revision, postContent, "No visible posts on this profile yet.", header = {
        profile?.let { person ->
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(person.shownName, socialMediaUrl((authState as? AuthState.SignedIn)?.endpoint?.baseUrl.orEmpty(), person.avatarUrl), size = 64.dp)
                    Column(Modifier.weight(1f)) {
                        Text(person.shownName, style = MaterialTheme.typography.headlineSmall)
                        Text(person.handle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!person.bio.isNullOrBlank()) SelectionContainer { Text(person.bio, style = MaterialTheme.typography.bodyMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { navigate(SocialRoute.People(targetId, true)) }) { Text("${person.followersCount} followers") }
                    TextButton(onClick = { navigate(SocialRoute.People(targetId, false)) }) { Text("${person.followingCount} following") }
                    Text("${person.postsCount} posts", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                }
                if (!own) {
                    val held = relationship
                    Button(enabled = !followBusy && held != null, onClick = {
                        followBusy = true; error = null
                        scope.launch {
                            try {
                                val a = state.socialAccess()
                                relationship = repo.setFollowing(a.endpoint, a.token, a.profileId, targetId, !(held?.following == true || held?.pending == true))
                                profile = repo.profile(a.endpoint, a.token, targetId)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "The instance could not change this follow." }
                            finally { followBusy = false }
                        }
                    }) { Text(when { followBusy -> "Saving…"; held?.pending == true -> "Withdraw request"; held?.following == true -> "Unfollow"; person.manuallyApprovesFollowers -> "Request to follow"; else -> "Follow" }) }
                } else TextButton(onClick = { navigate(SocialRoute.People(targetId, true, true)) }) { Text("Manage follow requests") }
                error?.let { shown -> SocialError(shown, onRetry = {
                    scope.launch {
                        try { val a = state.socialAccess(); relationship = repo.relationship(a.endpoint, a.token, a.profileId, targetId); error = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not refresh follow state." }
                    }
                }) }
            }
            HorizontalDivider()
        }
    }, load = { cursor ->
        val a = state.socialAccess()
        if (cursor == null) { profile = repo.profile(a.endpoint, a.token, targetId); relationship = if (own) SocialRelationship() else repo.relationship(a.endpoint, a.token, a.profileId, targetId) }
        repo.profilePosts(a.endpoint, a.token, a.profileId, targetId, cursor)
    })
}

@Composable
private fun SocialTrendingTags(state: AppState, repo: SocialRepository, revision: Int, onOpen: (String) -> Unit) {
    var tags by remember { mutableStateOf<List<SocialHashtag>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        try { val a = state.socialAccess(); tags = repo.trendingHashtags(a.endpoint, a.token); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Trending tags could not be loaded." }
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Trending posts today", style = MaterialTheme.typography.titleMedium)
        if (tags.isNotEmpty()) Text("Tags from the past week", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag -> SuggestionChip(onClick = { onOpen(tag.tag) }, label = { Text("#${tag.tag}") }) }
        }
        error?.let { shown -> Text(shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SocialSearch(state: AppState, repo: SocialRepository, onProfile: (String) -> Unit, onTag: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SocialProfile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun search() {
        if (query.isBlank() || busy) return
        if (query.startsWith("#")) { onTag(query.removePrefix("#")); return }
        busy = true; error = null
        scope.launch {
            try { val a = state.socialAccess(); results = repo.searchPeople(a.endpoint, a.token, query) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Account search failed." }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Account name or #hashtag") }, singleLine = true)
            FilledIconButton(onClick = ::search, enabled = query.isNotBlank() && !busy) { Icon(Icons.Outlined.Search, "Search") }
        }
        Text("Search accounts already known to your instance. Enter #tag to browse public posts.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { shown -> SocialError(shown, ::search) }
        LazyColumn {
            if (results?.isEmpty() == true && !busy) item { SocialMessage("No accounts matched this name on your instance.") }
            items(results.orEmpty(), key = { it.id }) { person -> SocialPersonRow(state, person, { onProfile(person.id) }) }
        }
    }
}

@Composable
private fun SocialPeople(state: AppState, repo: SocialRepository, route: SocialRoute.People, revision: Int, onProfile: (String) -> Unit) {
    var rows by remember(route) { mutableStateOf<List<SocialProfile>>(emptyList()) }
    var next by remember(route) { mutableStateOf<SocialCursor?>(null) }
    var busy by remember(route) { mutableStateOf(true) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    var loaded by remember(route) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load(reset: Boolean) {
        busy = true; error = null
        try {
            val a = state.socialAccess()
            val page = repo.people(a.endpoint, a.token, route.id, route.followers, route.pending, if (reset) null else next)
            rows = if (reset) page.people else (rows + page.people).distinctBy { it.id }; next = page.next; loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Accounts could not be loaded." }
        finally { busy = false }
    }
    LaunchedEffect(route, revision) { load(true) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { shown -> item { SocialError(shown, { scope.launch { load(!loaded) } }) } }
        if (loaded && rows.isEmpty() && error == null) item { SocialMessage(if (route.pending) "No pending follow requests." else "No accounts to show yet.") }
        items(rows, key = { it.id }) { person ->
            SocialPersonRow(state, person, { onProfile(person.id) })
            if (route.pending) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(true, false).forEach { accept -> OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try { val a = state.socialAccess(); repo.respondToFollowRequest(a.endpoint, a.token, a.profileId, person.id, accept); load(true) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Follow request could not be changed."; busy = false }
                    }
                }) { Text(if (accept) "Accept" else "Decline") } }
            }
        }
        if (next != null) item { TextButton(enabled = !busy, onClick = { scope.launch { load(false) } }, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
    }
}

@Composable
private fun SocialPersonRow(state: AppState, person: SocialProfile, onOpen: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val authState by state.auth.state.collectAsState()
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(person.shownName, socialMediaUrl((authState as? AuthState.SignedIn)?.endpoint?.baseUrl.orEmpty(), person.avatarUrl))
        Column(Modifier.weight(1f)) { Text(person.shownName, style = MaterialTheme.typography.titleSmall); Text(person.handle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        trailing()
    }
}

@Composable
private fun SocialLists(state: AppState, repo: SocialRepository, revision: Int, onOpen: (SocialList) -> Unit, onEdit: (SocialList) -> Unit, onChanged: () -> Unit) {
    var lists by remember { mutableStateOf<List<SocialList>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<SocialList?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision, retry) {
        try { val a = state.socialAccess(); lists = repo.lists(a.endpoint, a.token, a.profileId); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Your lists could not be loaded." }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (lists == null && error == null) item { SocialLoading() }
        error?.let { shown -> item { SocialError(shown, { retry++ }) } }
        if (lists?.isEmpty() == true) item { SocialMessage("Create a list to give a group of followed accounts its own timeline.") }
        items(lists.orEmpty(), key = { it.id }) { list ->
            ListItem(headlineContent = { Text(list.title) }, supportingContent = { Text(list.description ?: if (list.isPublic) "Public list" else "Private list") },
                leadingContent = { Icon(Icons.Outlined.ListAlt, null) }, modifier = Modifier.clickable { onOpen(list) }, trailingContent = {
                    Row { IconButton(onClick = { onEdit(list) }) { Icon(Icons.Outlined.Edit, "Edit ${list.title}") }; IconButton(onClick = { deleting = list }) { Icon(Icons.Outlined.DeleteOutline, "Delete ${list.title}") } }
                })
            HorizontalDivider()
        }
    }
    deleting?.let { list -> AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("Delete ${list.title}?") }, text = { Text("This removes the list, not its members' posts.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
            try { val a = state.socialAccess(); repo.deleteList(a.endpoint, a.token, a.profileId, list.id); deleting = null; onChanged() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "List could not be deleted."; deleting = null }
            finally { busy = false }
        } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun SocialMembers(state: AppState, repo: SocialRepository, list: SocialList, revision: Int, onChanged: () -> Unit) {
    var members by remember(list.id) { mutableStateOf<List<SocialListMember>?>(null) }
    var query by rememberSaveable(list.id) { mutableStateOf("") }
    var results by remember(list.id) { mutableStateOf<List<SocialProfile>>(emptyList()) }
    var error by remember(list.id) { mutableStateOf<String?>(null) }
    var busy by remember(list.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(list.id, revision, retry) {
        try { val a = state.socialAccess(); members = repo.listMembers(a.endpoint, a.token, list.id); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "List members could not be loaded." }
    }
    fun change(personId: String, enabled: Boolean) {
        busy = true; error = null
        scope.launch {
            try { val a = state.socialAccess(); repo.setListMember(a.endpoint, a.token, a.profileId, list.id, personId, enabled); onChanged() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "List membership could not be saved." }
            finally { busy = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("${list.title} only includes accounts you follow.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Find an account to add") }, singleLine = true)
                FilledIconButton(enabled = !busy && query.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { val a = state.socialAccess(); results = repo.searchPeople(a.endpoint, a.token, query) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Account search failed." }
                        finally { busy = false }
                    }
                }) { Icon(Icons.Outlined.Search, "Search accounts") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { shown -> SocialError(shown, { retry++ }) }
        }
        items(results.filter { p -> members.orEmpty().none { it.accountId == p.id } }, key = { "search:${it.id}" }) { person ->
            SocialPersonRow(state, person, {}) { TextButton(enabled = !busy, onClick = { change(person.id, true) }) { Text("Add") } }
        }
        item { Text("Members", Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium) }
        if (members == null && error == null) item { SocialLoading() }
        if (members?.isEmpty() == true) item { SocialMessage("This list has no members yet.") }
        items(members.orEmpty(), key = { it.accountId }) { member ->
            SocialPersonRow(state, member.account ?: SocialProfile(member.accountId), {}) { TextButton(enabled = !busy, onClick = { change(member.accountId, false) }) { Text("Remove") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialComposer(state: AppState, repo: SocialRepository, draft: SocialDraft, onDismiss: () -> Unit, onPublished: (SocialPost) -> Unit) {
    var text by rememberSaveable(draft.edit?.id, draft.reply?.id) { mutableStateOf(draft.edit?.plainText ?: draft.reply?.author?.handle?.let { "$it " }.orEmpty()) }
    var warning by rememberSaveable(draft.edit?.id) { mutableStateOf(draft.edit?.contentWarning.orEmpty()) }
    var sensitive by rememberSaveable(draft.edit?.id) { mutableStateOf(draft.edit?.isSensitive ?: false) }
    var visibility by rememberSaveable(draft.edit?.id, draft.reply?.id) { mutableStateOf(draft.edit?.visibility ?: draft.reply?.visibility ?: "public") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { if (!busy) { if (text.isNotBlank() && text != draft.edit?.plainText) discard = true else onDismiss() } }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (draft.edit != null) "Edit post" else if (draft.reply != null) "Write a reply" else "New post", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = close, enabled = !busy) { Text("Cancel") }
            }
            if (draft.reply != null) Text("Replying to ${draft.reply.author?.shownName ?: "a post"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), enabled = !busy, label = { Text("What's happening?") }, supportingText = { Text("${text.length} characters · instance limits apply") })
            if (draft.edit == null) {
                Text("Who can see this?", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("public", "unlisted", "followers", "direct").forEach { value -> FilterChip(selected = value == visibility, enabled = !busy, onClick = { visibility = value }, label = { Text(value.replaceFirstChar { it.uppercase() }) }) }
                }
                if (visibility == "direct") Text("Only mentioned accounts can read a direct post. This is not an encrypted direct message.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(warning, { warning = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Content warning (optional)") })
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(sensitive, { sensitive = it }, enabled = !busy); Spacer(Modifier.width(12.dp)); Text("Mark content as sensitive", style = MaterialTheme.typography.bodyMedium) }
            error?.let { shown -> SocialError(shown, onRetry = { error = null }) }
            Button(enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val a = state.socialAccess()
                        val post = if (draft.edit != null) repo.edit(a.endpoint, a.token, a.profileId, draft.edit, text, warning, sensitive)
                        else repo.publish(a.endpoint, a.token, a.profileId, text, visibility, warning, sensitive, draft.reply?.id)
                        onPublished(post)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "The instance could not save your post. Your draft is still here." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else if (draft.edit != null) "Save changes" else "Publish") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard this draft?") }, text = { Text("Your unpublished changes will be lost.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep writing") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialListEditor(state: AppState, repo: SocialRepository, list: SocialList?, onDismiss: () -> Unit, onSaved: (SocialList) -> Unit) {
    var title by rememberSaveable(list?.id) { mutableStateOf(list?.title.orEmpty()) }
    var description by rememberSaveable(list?.id) { mutableStateOf(list?.description.orEmpty()) }
    var policy by rememberSaveable(list?.id) { mutableStateOf(list?.repliesPolicy ?: "list") }
    var exclusive by rememberSaveable(list?.id) { mutableStateOf(list?.isExclusive ?: false) }
    var public by rememberSaveable(list?.id) { mutableStateOf(list?.isPublic ?: false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (list == null) "Create list" else "Edit list", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("List name") })
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Description") })
            Text("Replies policy", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("list", "followed", "none").forEach { value -> FilterChip(selected = value == policy, onClick = { policy = value }, enabled = !busy, label = { Text(value.replaceFirstChar { it.uppercase() }) }) } }
            Text("These options are stored with your list. Its timeline currently shows members' public and unlisted posts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(exclusive, { exclusive = it }, enabled = !busy); Spacer(Modifier.width(12.dp)); Text("Exclusive list") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(public, { public = it }, enabled = !busy); Spacer(Modifier.width(12.dp)); Text("Public list") }
            error?.let { shown -> SocialError(shown, { error = null }) }
            Button(enabled = !busy && title.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = null
                scope.launch {
                    try { val a = state.socialAccess(); onSaved(repo.saveList(a.endpoint, a.token, a.profileId, title, description, list?.id, policy, exclusive, public)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "List could not be saved." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Save list") }
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun SocialLoading() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun SocialMessage(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SocialError(message: String, onRetry: () -> Unit, onDismiss: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onRetry) { Text("Retry") }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
