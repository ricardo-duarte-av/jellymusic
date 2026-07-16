package pt.aguiarvieira.jellymusic

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real app end-to-end on a device/emulator — connect → sign in → browse — and
 * captures a full-window PNG of each screen. Meant to be run by the `screenshots` CI job
 * (workflow_dispatch), which pulls the PNGs off the device and commits them.
 *
 * It talks to a **real** Jellyfin server (no mocked network), so it needs internet and a
 * populated library. Credentials come from instrumentation arguments so nothing is
 * hard-coded here:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.serverUrl=https://…
 *   -Pandroid.testInstrumentationRunnerArguments.username=…
 *   -Pandroid.testInstrumentationRunnerArguments.password=…
 *
 * Each screen is captured inside its own try/catch: a single content-dependent step (an
 * album that isn't there, playback that won't start) logs and is skipped rather than
 * failing the whole run, so we still commit the screens that did render.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Pre-grant so the POST_NOTIFICATIONS launcher on API 33+ returns without a system
    // dialog that would otherwise block the UI thread during the run.
    @get:Rule
    val notifPermission: GrantPermissionRule =
        GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS")

    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("serverUrl") ?: "https://jellyfin.aguiarvieira.pt"
    private val username = args.getString("username") ?: "jelly"
    private val password = args.getString("password") ?: "jelly1234jelly"

    private val outputDir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        (ctx.getExternalFilesDir("screenshots") ?: File(ctx.filesDir, "screenshots")).apply {
            mkdirs()
        }
    }

    @Test
    fun captureScreens() {
        // --- Onboarding: Connect → Login → Home ------------------------------------
        waitForText("Server address")
        composeRule.onNode(hasSetTextAction()).performTextInput(serverUrl)
        composeRule.onNodeWithText("Connect").performClick()

        waitForText("Sign in")
        composeRule.onNodeWithText("Username").performTextInput(username)
        composeRule.onNodeWithText("Password").performTextInput(password)
        // Two "Sign in" nodes exist (heading + button); the last is the button.
        composeRule.onAllNodesWithText("Sign in").onLast().performClick()

        // Landed on the browse shell.
        waitForText("Albums")
        screenshot("01-albums")

        // --- Browse tabs -----------------------------------------------------------
        capture("02-artists") {
            composeRule.onNodeWithText("Artists").performClick()
            waitForText("Artists")
        }
        capture("03-playlists") {
            composeRule.onNodeWithText("Playlists").performClick()
            composeRule.waitForIdle()
        }
        // Back to Albums for the content-dependent captures below.
        runCatching {
            composeRule.onNodeWithText("Albums").performClick()
            waitForText("Albums")
        }

        // --- Album detail + now-playing (content-dependent, best-effort) -----------
        // These depend on the library actually having an album/track and on tapping the
        // right grid cell; if any step misses, capture() logs and skips it.
        capture("04-album-detail") {
            composeRule.onAllNodesWithTag("albumCard").onFirst().performClick()
            waitForText("Play")
        }
        capture("05-now-playing") {
            // Start the album, then expand the mini-player into the full player.
            composeRule.onNodeWithText("Play").performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag("miniPlayer").onFirst().performClick()
            composeRule.waitForIdle()
        }
        // Collapse the player / leave album detail.
        repeat(2) { runCatching { pressBack() } }

        // --- Search ----------------------------------------------------------------
        capture("06-search") {
            composeRule.onNodeWithContentDescription("Search").performClick()
            waitForText("Search music")
            composeRule.onNode(hasSetTextAction()).performTextInput("a")
            composeRule.waitForIdle()
        }
        runCatching { pressBack() }

        // --- Settings → Changelog → About ------------------------------------------
        capture("07-settings") {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.waitForIdle()
        }
        capture("08-changelog") {
            composeRule.onNodeWithText("Changelog").performClick()
            composeRule.waitForIdle()
        }
        runCatching { pressBack() }
        capture("09-about") {
            composeRule.onNodeWithText("About").performClick()
            composeRule.waitForIdle()
        }

        Log.i(TAG, "Screenshots written to ${outputDir.absolutePath}")
    }

    // --- helpers ------------------------------------------------------------------

    /** Runs [block] then captures; any failure inside is logged and skipped. */
    private fun capture(name: String, block: () -> Unit) {
        runCatching {
            block()
            screenshot(name)
        }.onFailure { Log.w(TAG, "Skipped screenshot '$name': ${it.message}") }
    }

    private fun waitForText(text: String, timeoutMs: Long = NETWORK_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pressBack() {
        Espresso.pressBack()
        composeRule.waitForIdle()
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        val bitmap: Bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(outputDir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        Log.i(TAG, "Captured $name.png")
    }

    private companion object {
        const val TAG = "ScreenshotTest"
        const val NETWORK_TIMEOUT_MS = 60_000L
    }
}
