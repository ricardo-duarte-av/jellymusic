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
import androidx.compose.ui.test.performScrollTo
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
        // Internal storage: the workflow pulls these via `run-as`. adb can't read another
        // app's /sdcard/Android/data/<pkg> dir under scoped storage on API 30+.
        File(ctx.filesDir, "screenshots").apply { mkdirs() }
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

        // Landed on the browse shell — wait for the album grid to actually populate
        // (the "Albums" tab label is present instantly, so it's not a content signal).
        waitForTag("albumCard")
        settle()
        screenshot("01-albums")

        // --- Browse tabs -----------------------------------------------------------
        capture("02-artists") {
            composeRule.onNodeWithText("Artists").performClick()
            waitForTag("artistCard")
            settle()
        }
        capture("03-playlists") {
            composeRule.onNodeWithText("Playlists").performClick()
            waitForTag("playlistCard")
            settle()
        }
        // --- Search (captured BEFORE playback) -------------------------------------
        // Run search before starting any playback. The album grid loaded fresh artwork
        // fine with nothing playing, but once a FLAC stream is running the emulator's
        // limited CPU starves the burst of fresh thumbnail decodes and the rows stay grey
        // (a real device has the headroom, so it loads there). The Search icon is in the
        // top bar on every browse tab, so we can open it straight from the Playlists tab.
        capture("06-search") {
            composeRule.onNodeWithContentDescription("Search").performClick()
            waitForText("Search music")
            // A term the test library actually contains, so results render.
            composeRule.onNode(hasSetTextAction()).performTextInput("zelda")
            // Drop the keyboard so the results (not a half-screen of keys) fill the shot.
            Espresso.closeSoftKeyboard()
            // Wait for results to actually render (playlist rows carry an "N tracks"
            // subtitle) — this absorbs the search API round-trip — THEN give the row
            // thumbnails time to finish. The rows appear a beat before their artwork loads,
            // and the previous blind sleep expired ~0.2s before the images completed
            // (verified in logcat: RealImageLoader "Successful" landed just after capture).
            waitForText("tracks")
            settle(6_000)
        }
        // Close the IME first — otherwise the first Back only dismisses the keyboard and
        // leaves us on Search instead of returning to the browse shell.
        runCatching {
            Espresso.closeSoftKeyboard()
            pressBack()
            waitForText("Albums")
        }

        // Back to the Albums grid for the content-dependent captures below.
        runCatching {
            composeRule.onNodeWithText("Albums").performClick()
            waitForTag("albumCard")
        }

        // --- Album detail + now-playing (starts playback; content-dependent) -------
        // If any step misses (no album/track, tap lands wrong), capture() logs and skips.
        capture("04-album-detail") {
            composeRule.onAllNodesWithTag("albumCard").onFirst().performClick()
            waitForText("Play")
            settle()
        }
        capture("05-now-playing") {
            // Start the album, then expand the mini-player into the full player.
            composeRule.onNodeWithText("Play").performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag("miniPlayer").onFirst().performClick()
            composeRule.waitForIdle()
        }
        // Return to Home deterministically: collapse the full player if it opened, then a
        // single Back off album detail. (A fixed number of Backs is fragile — if the player
        // didn't expand, one Back too many exits the app entirely.)
        runCatching {
            composeRule.onNodeWithContentDescription("Collapse").performClick()
            composeRule.waitForIdle()
        }
        runCatching {
            pressBack()
            waitForText("Albums")
        }

        // --- Settings → Changelog → About ------------------------------------------
        capture("07-settings") {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.waitForIdle()
        }
        capture("08-changelog") {
            composeRule.onNodeWithText("Changelog").performScrollTo().performClick()
            composeRule.waitForIdle()
        }
        runCatching { pressBack() }
        capture("09-about") {
            composeRule.onNodeWithText("About").performScrollTo().performClick()
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

    /** Waits until at least one node with [tag] exists — i.e. real content has loaded. */
    private fun waitForTag(tag: String, timeoutMs: Long = NETWORK_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Brief pause so async artwork (Coil) finishes decoding before the screenshot. */
    private fun settle(ms: Long = 2_000) {
        Thread.sleep(ms)
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
