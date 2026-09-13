package com.hilight.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class RootCommandTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun runStop(processes: Map<Int, List<String>>): Pair<Int, String> {
        val proc = temporary.newFolder()
        for ((pid, args) in processes) {
            val dir = java.io.File(proc, pid.toString()).apply { mkdir() }
            java.io.File(dir, "cmdline").writeBytes(
                if (args.isEmpty()) byteArrayOf() else
                    (args.joinToString("\u0000") + "\u0000").toByteArray(),
            )
        }
        val signals = temporary.newFile()
        // Exercise the actual phone-shell command against a private /proc fixture. The shell
        // function records TERM and removes only the fixture; no host process can be signalled.
        val script = "procRoot='${proc.absolutePath}'; signals='${signals.absolutePath}'; " +
            "kill() { printf '%s\\n' \"\$*\" >> \"\$signals\"; " +
            "rm -r \"\$procRoot/\$2\"; }; " +
            RootCommand.stop(4321, "root", "root-1").replace("/proc/", "\"\$procRoot\"/")
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        assertTrue("stop command must finish", process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to signals.readText()
    }

    private fun helper(instance: String) = listOf(
        "app_process", "/", "com.hilight.core.AdbHelper", "--owner", "root", "--instance", instance,
    )

    @Test
    fun `expired renderer pid reused by an unrelated process is not killed or a permanent blocker`() {
        val (code, signals) = runStop(mapOf(4321 to listOf("other-application")))
        assertEquals(0, code)
        assertEquals("", signals)
    }

    @Test
    fun `reused pid does not authorize stopping a different helper instance`() {
        val (code, signals) = runStop(mapOf(4321 to helper("root-10")))
        assertEquals(1, code)
        assertEquals("", signals)
    }

    @Test
    fun `a different surviving helper still blocks recovery after pid reuse`() {
        val (code, signals) = runStop(mapOf(
            4321 to listOf("other-application"), 9876 to helper("root-2"),
        ))
        assertEquals(1, code)
        assertEquals("", signals)
    }

    @Test
    fun `an unreadable or empty identity cannot prove exit`() {
        val (code, signals) = runStop(mapOf(4321 to emptyList()))
        assertEquals(1, code)
        assertEquals("", signals)
    }

    @Test
    fun `the exact renderer receives term before recovery succeeds`() {
        val (code, signals) = runStop(mapOf(4321 to helper("root-1")))
        assertEquals(0, code)
        assertEquals("-TERM 4321\n", signals)
    }

    @Test
    fun `root launch is detached and explicitly owned`() {
        val start = RootCommand.start(
            "/storage/emulated/0/Android/data/com.hilight.studio/files/hilight",
            "root-instance-1",
        )

        assertTrue(start.contains("nohup app_process"))
        assertTrue(start.contains("--owner root"))
        assertTrue(start.contains("--instance 'root-instance-1'"))
        assertTrue(start.contains("& echo \$!"))
        assertFalse(start.contains("pkill"))
    }

    @Test
    fun `bridge path is safely single quoted for the phone shell`() {
        val start = RootCommand.start("/data/a user's/light", "root-instance-2")

        assertTrue(start.contains("'/data/a user'\\''s/light'"))
    }

    @Test
    fun `root stop validates pid and owner before cooperative term`() {
        val stop = RootCommand.stop(4321, "root", "root-instance-1")

        assertTrue(stop.contains("/proc/4321/cmdline"))
        assertTrue(stop.contains("' --owner root '"))
        assertTrue(stop.contains("kill -TERM 4321"))
        assertFalse(stop.contains("kill -TERM \$p"))
        assertTrue(stop.contains("[ \"\$arg\" = \"root-instance-1\" ]"))
        assertTrue(stop.contains("[ \"\$3\" = com.hilight.core.AdbHelper ]"))
        assertTrue(stop.contains("\$i -lt 65"))
        assertTrue(stop.contains("then exit 1"))
        assertFalse(stop.contains("pkill"))
    }

    @Test
    fun `renderer instance identity is an exact argv token not a prefix`() {
        val stop = RootCommand.stop(4321, "root", "root-1")

        assertTrue(stop.contains("while IFS= read -r arg"))
        assertTrue(stop.contains("[ \"\$prev\" = --instance ]"))
        assertTrue(stop.contains("[ \"\$arg\" = \"root-1\" ]"))
        assertFalse(stop.contains("grep -Fq -- '--instance root-1'"))
    }

    @Test
    fun `adb stop rejects a root-owned helper with the same entry point`() {
        val stop = RootCommand.stop(4321, "adb")

        assertTrue(stop.contains("com.hilight.core.AdbHelper"))
        assertTrue(stop.contains("! printf"))
        assertTrue(stop.contains("--owner root"))
        assertTrue(stop.contains("kill -TERM 4321"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `root launch rejects shell metacharacters in renderer identity`() {
        RootCommand.start("/data/local/tmp/hilight", "bad; kill 1")
    }
}
