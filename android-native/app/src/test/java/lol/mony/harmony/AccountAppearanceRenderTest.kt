package lol.mony.harmony

import androidx.compose.ui.test.junit4.v2.createComposeRule
import lol.mony.harmony.RenderHarness.shotThenRetheme
import lol.mony.harmony.RenderHarness.signedIn
import lol.mony.harmony.ui.screens.chat.ChatWorkspace
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app must wear the look the **account** stores, not one of its own.
 *
 * Harmony keeps appearance in `profiles.appearance_settings`, the same JSON the web
 * client reads and writes. A person who chose a theme there has to find it here, and
 * the app must not quietly substitute its default. This renders Chats twice -- once
 * with the account's stored settings, once with the defaults -- and requires the
 * pixels to differ, so a screen that ignored the setting could not pass.
 *
 * A separate class from ScreenRenderHarnessTest because a Compose rule may only call
 * `setContent` once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class AccountAppearanceRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chatsFollowTheAccountAppearance() {
        val state = signedIn()
        val stored = RenderHarness.accountAppearance(state)
        // An account still holding the default look is a legitimate configuration: the
        // two captures then match and there is nothing to compare.
        assumeTrue("the account stores the default look, so there is nothing to compare",
            stored != VisualThemeSettings())
        // One composition, two looks: a Compose rule may only call setContent once, and
        // repainting in place is also what the app does when the account's look arrives
        // after the first frame.
        val (rendered, defaultLook) = compose.shotThenRetheme(
            "chats-account-appearance", "chats-account-default", state,
            first = stored, second = VisualThemeSettings(),
            settle = "Direct message",
        ) { theme -> ChatWorkspace(state, theme) }
        val differs = RenderHarness.differing(rendered, defaultLook)
        assertTrue("the account's look changed the screen (%d of %d pixels)"
            .format(differs, rendered.width * rendered.height), differs > rendered.width)
    }
}
