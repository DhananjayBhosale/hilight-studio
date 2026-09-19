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

    private fun runStop(
        processes: Map<Int, List<String>>,
        executables: Map<Int, String> = emptyMap(),
        instance: String = "root-1",
        outerShell: String? = null,
    ): Pair<Int, String> {
        val proc = temporary.newFolder()
        for ((pid, args) in processes) {
            val dir = java.io.File(proc, pid.toString()).apply { mkdir() }
            java.io.File(dir, "cmdline").writeBytes(
                if (args.isEmpty()) byteArrayOf() else
                    (args.joinToString("\u0000") + "\u0000").toByteArray(),
            )
            executables[pid]?.let { executable ->
                java.nio.file.Files.createSymbolicLink(
                    java.io.File(dir, "exe").toPath(), java.io.File(executable).toPath(),
                )
            }
        }
        val signals = temporary.newFile()
        // Exercise the actual phone-shell command against a private /proc fixture. The shell
        // function records TERM and removes only the fixture; no host process can be signalled.
        val script = "procRoot='${proc.absolutePath}'; signals='${signals.absolutePath}'; " +
            "kill() { printf '%s\\n' \"\$*\" >> \"\$signals\"; " +
            "rm -r \"\$procRoot/\$2\"; }; " +
            RootCommand.stopBody(4321, "root", instance).replace("/proc/", "\"\$procRoot\"/")
        // Wrap the WHOLE fixture, including signal overrides, so no host PID can be signalled.
        val command = if (outerShell == null) script else
            RootCommand.platformShell(script).replaceFirst("/system/bin/sh", "/bin/bash")
        val process = ProcessBuilder(outerShell ?: "bash", "-c", command).redirectErrorStream(true).start()
        assertTrue("stop command must finish", process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to signals.readText()
    }

    @Test
    fun `scanning unrelated processes does not fork a command per process`() {
        val proc = temporary.newFolder()
        repeat(500) { index ->
            val dir = java.io.File(proc, (10_000 + index).toString()).apply { mkdir() }
            java.io.File(dir, "cmdline").writeBytes("com.example.worker\u0000--background\u0000".toByteArray())
        }
        val calls = temporary.newFile()
        val script = "calls='${calls.absolutePath}'; " +
            "tr() { echo tr >> \"\$calls\"; command tr \"\$@\"; }; " +
            "kill() { echo unexpected-kill >> \"\$calls\"; return 1; }; " +
            RootCommand.stopBody(4321, "root", "root-1").replace("/proc/", "'${proc.absolutePath}'/")
        // Android's mksh and bash both support NUL-delimited read. CI's /bin/sh may be dash.
        val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("ordinary processes must not consume the stop deadline spawning tr", "", calls.readText())
    }

    @Test
    fun `cleanup explicitly pins Android shell and quotes the complete body`() {
        val body = RootCommand.stopBody(4321, "root", "root-1")
        val quoted = "'" + body.replace("'", "'\\''") + "'"
        assertEquals("/system/bin/sh -c $quoted", RootCommand.stop(4321, "root", "root-1"))
    }

    @Test
    fun `outer POSIX shell cannot change cleanup reader or bypass ownership checks`() {
        org.junit.Assume.assumeTrue(java.io.File("/bin/dash").canExecute())
        val unrelated = mapOf(9876 to listOf("com.example.app"))
        assertEquals(0, runStop(unrelated, mapOf(9876 to "/system/bin/app_process64"), outerShell = "/bin/dash").first)
        val blocked = runStop(mapOf(9876 to helper("root-other")), outerShell = "/bin/dash")
        assertEquals(1, blocked.first)
        assertEquals("", blocked.second)
        val stopped = runStop(mapOf(4321 to helper("root-1")), outerShell = "/bin/dash")
        assertEquals(0, stopped.first)
        assertEquals("-TERM 4321\n", stopped.second)
    }

    private fun helper(instance: String) = listOf(
        "app_process", "/", "com.hilight.core.AdbHelper", "--owner", "root", "--instance", instance,
    )

    @Test
    fun `scan rejects surviving helper for every app process executable spelling`() {
        for (executable in listOf("app_process", "/system/bin/app_process32", "/system/bin/app_process64")) {
            val (code, signals) = runStop(mapOf(9876 to helper("root-2").toMutableList().apply {
                this[0] = executable
            }))
            assertEquals(executable, 1, code)
            assertEquals("", signals)
        }
    }

    @Test
    fun `empty cmdline for app process still blocks recovery`() {
        val (code, signals) = runStop(
            mapOf(9876 to emptyList()), mapOf(9876 to "/system/bin/app_process64"),
        )
        assertEquals(1, code)
        assertEquals("", signals)
    }

    @Test
    fun `other app process classes and helper names inside unrelated arguments are harmless`() {
        val (code, signals) = runStop(mapOf(
            9876 to listOf("/system/bin/app_process64", "/", "another.JavaClass"),
            9877 to listOf("sh", "-c", "app_process / com.hilight.core.AdbHelper"),
            9878 to listOf("app_process / com.hilight.core.AdbHelper"),
        ))
        assertEquals(0, code)
        assertEquals("", signals)
    }

    @Test
    fun `legacy helper without instance is stopped only through the legacy identity path`() {
        val legacy = listOf("app_process", "/", "com.hilight.core.AdbHelper", "--owner", "root", "--dir", "/bridge")
        val (code, signals) = runStop(mapOf(4321 to legacy), instance = "")
        assertEquals(0, code)
        assertEquals("-TERM 4321\n", signals)
    }

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
            "/data/app/play/base.apk",
        )

        assertTrue(start.contains("nohup app_process"))
        assertTrue(start.contains("--owner root"))
        assertTrue(start.contains("--instance 'root-instance-1'"))
        assertTrue(start.contains("& echo \$!"))
        assertFalse(start.contains("pkill"))
        assertTrue(start.contains("CLASSPATH='/data/app/play/base.apk'"))
        assertTrue(start.contains("< /dev/null"))
        assertFalse(start.contains("pm path"))
    }

    @Test
    fun `missing current APK cannot silently launch a renderer from another installed edition`() {
        val missing = java.io.File(temporary.newFolder(), "missing.apk").absolutePath
        val process = ProcessBuilder("sh", "-c", RootCommand.start("/unused", "root-1", missing))
            .redirectErrorStream(true).start()
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        assertEquals(1, process.exitValue())
        assertTrue(process.inputStream.bufferedReader().readText().contains("APK is not readable"))
    }

    @Test
    fun `bridge path is safely single quoted for the phone shell`() {
        val start = RootCommand.start("/data/a user's/light", "root-instance-2", "/data/app/play/base.apk")

        assertTrue(start.contains("'/data/a user'\\''s/light'"))
    }

    @Test
    fun `root stop validates pid and owner before cooperative term`() {
        val stop = RootCommand.stopBody(4321, "root", "root-instance-1")

        assertTrue(stop.contains("/proc/4321/cmdline"))
        assertTrue(stop.contains("' --owner root '"))
        assertTrue(stop.contains("kill -TERM 4321"))
        assertFalse(stop.contains("kill -TERM \$p"))
        assertTrue(stop.contains("[ \"\$arg\" = \"root-instance-1\" ]"))
        assertTrue(stop.contains("\$i -lt 65"))
        assertTrue(stop.contains("then exit 1"))
        assertFalse(stop.contains("pkill"))
    }

    @Test
    fun `renderer instance identity is an exact argv token not a prefix`() {
        val stop = RootCommand.stopBody(4321, "root", "root-1")

        assertTrue(stop.contains("while IFS= read -r arg"))
        assertTrue(stop.contains("[ \"\$prev\" = --instance ]"))
        assertTrue(stop.contains("[ \"\$arg\" = \"root-1\" ]"))
        assertFalse(stop.contains("grep -Fq -- '--instance root-1'"))
    }

    @Test
    fun `adb stop rejects a root-owned helper with the same entry point`() {
        val stop = RootCommand.stopBody(4321, "adb")

        assertTrue(stop.contains("com.hilight.core.AdbHelper"))
        assertTrue(stop.contains("! printf"))
        assertTrue(stop.contains("--owner root"))
        assertTrue(stop.contains("kill -TERM 4321"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `root launch rejects shell metacharacters in renderer identity`() {
        RootCommand.start("/data/local/tmp/hilight", "bad; kill 1", "/data/app/play/base.apk")
    }
}
