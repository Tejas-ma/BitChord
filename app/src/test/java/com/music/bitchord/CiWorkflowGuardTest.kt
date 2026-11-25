package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two jobs that ship an installer must not cancel their own in-flight pushes.
 *
 * `cancel-in-progress: true` keyed on the ref looks harmless, but it makes the
 * newest push kill whatever is already building. That is how the Android
 * prodRelease job died in `:shared:lintVitalAnalyzeRelease`, and how the
 * Windows job — native build, JVM tests, jpackage image and Inno Setup, close to
 * an hour of work — was cancelled 85 seconds in on Devlopment, publishing
 * nothing. Both now cancel only superseded pull-request runs: duplicate PR
 * feedback is worthless, a half-built release payload is worse than a queued one.
 *
 * The fast checks (`check.yml`, `tv.yml`, `web.yml`) are deliberately left out —
 * a superseded lint or bundle run should be cancelled.
 *
 * This reads the files as text because a workflow is not compiled or
 * type-checked anywhere: without this the flag can go back to `true` and no
 * test in the repository notices.
 */
class CiWorkflowGuardTest {

    private val installerWorkflows = listOf("android.yml", "windows-exe.yml")

    private val workflowsDir: File by lazy {
        val projectRoot = File(".").canonicalFile
        listOf(File(projectRoot, ".github/workflows"), File(projectRoot, "../.github/workflows"))
            .firstOrNull { it.isDirectory }
            ?: error("no .github/workflows directory found from $projectRoot")
    }

    @Test
    fun installerWorkflowsQueuePushesInsteadOfCancelling() {
        val problems = installerWorkflows.mapNotNull { name ->
            val file = File(workflowsDir, name)
            if (!file.isFile) return@mapNotNull "$name: missing from $workflowsDir"

            val text = file.readText()
            val cancelsPush = Regex("""(?m)^\s*cancel-in-progress:\s*true\s*$""").containsMatchIn(text)
            val cancelsOnlyPrs = Regex(
                """(?m)^\s*cancel-in-progress:\s*\$\{\{\s*github\.event_name\s*==\s*'pull_request'\s*\}\}\s*$""",
            ).containsMatchIn(text)

            when {
                cancelsPush -> "$name: cancel-in-progress is true, so a new push cancels the build already running"
                !cancelsOnlyPrs -> "$name: expected cancel-in-progress tied to github.event_name == 'pull_request'"
                else -> null
            }
        }

        assertTrue(
            "an installer workflow cancels in-flight pushes instead of queueing them:\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }
}
