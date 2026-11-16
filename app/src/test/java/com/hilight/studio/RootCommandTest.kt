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
        states: Map<Int, Char> = emptyMap(),
        instance: String = "root-1",
        outerShell: String? = null,
        killFunction: String = "kill() { printf '%s\\n' \"\$*\" >> \"\$signals\"; " +
            "rm -r \"\$procRoot/\$2\"; }; ",
    ): Pair<Int, String> {
        val proc = temporary.newFolder()
        for ((pid, args) in processes) {
            val dir = java.io.File(proc, pid.toString()).apply { mkdir() }
            java.io.File(dir, "cmdline").writeBytes(
                if (args.isEmpty()) byteArrayOf() else
                    (args.joinToString("\u0000") + "\u0000").toByteArray(),
            )
            states[pid]?.let { state ->
                java.io.File(dir, "stat").writeText("$pid (app_process) $state 1 0 0\n")
            }
            executables[pid]?.let { executable ->
                // A real target with the same basename, as /proc/<pid>/exe resolves on a phone.
                val target = java.io.File(temporary.newFolder(), executable.substringAfterLast('/'))
                    .apply { createNewFile() }
                java.nio.file.Files.createSymbolicLink(
                    java.io.File(dir, "exe").toPath(), target.toPath(),
                )
            }
        }
        val signals = temporary.newFile()
        // Exercise the actual phone-shell command against a private /proc fixture. The shell
        // function records TERM and removes only the fixture; no host process can be signalled.
        val script = "procRoot='${proc.absolutePath}'; signals='${signals.absolutePath}'; " +
            killFunction +
            RootCommand.stopBody(4321, "root", instance).replace("/proc/", "\"\$procRoot\"/")
        // Wrap the WHOLE fixture, including signal overrides, so no host PID can be signalled.
        val command = if (outerShell == null) script else
            RootCommand.platformShell(script).replaceFirst("/system/bin/sh", "/bin/bash")
        val process = ProcessBuilder(outerShell ?: "bash", "-c", command).redirectErrorStream(true).start()
        assertTrue("stop command must finish", process.waitFor(15, TimeUnit.SECONDS))
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
    fun `kernel threads with no executable do not fork readlink during the scan`() {
        // Issue #19: a Pixel has hundreds of kernel threads, each with an empty cmdline. One
        // readlink fork per thread exceeded the cleanup deadline under KernelSU after reboot.
        val proc = temporary.newFolder()
        repeat(500) { index ->
            val dir = java.io.File(proc, (20_000 + index).toString()).apply { mkdir() }
            java.io.File(dir, "cmdline").writeBytes(byteArrayOf())
        }
        val calls = temporary.newFile()
        val script = "calls='${calls.absolutePath}'; " +
            "readlink() { echo readlink >> \"\$calls\"; command readlink \"\$@\"; }; " +
            "kill() { echo unexpected-kill >> \"\$calls\"; return 1; }; " +
            RootCommand.stopBody(4321, "root", "root-1").replace("/proc/", "'${proc.absolutePath}'/")
        val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("", calls.readText())
    }

    @Test
    fun `a renderer that ignores term is killed only after the grace period`() {
        // TERM is recorded but the fixture process survives; only KILL removes it.
        val ignoresTerm = "kill() { printf '%s\\n' \"\$*\" >> \"\$signals\"; " +
            "[ \"\$1\" = -KILL ] && rm -r \"\$procRoot/\$2\"; return 0; }; "
        val started = System.nanoTime()
        val (code, signals) = runStop(mapOf(4321 to helper("root-1")), killFunction = ignoresTerm)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertEquals(0, code)
        assertEquals("-TERM 4321\n-KILL 4321\n", signals)
        assertTrue("TERM must get its grace period, took $seconds s", seconds >= 3.5)
    }

    @Test
    fun `a renderer that survives kill is reported as stuck within the deadline`() {
        val survives = "kill() { printf '%s\\n' \"\$*\" >> \"\$signals\"; return 0; }; "
        val (code, signals) = runStop(mapOf(4321 to helper("root-1")), killFunction = survives)
        assertEquals(RootCommand.STOP_RENDERER_STUCK, code)
        assertEquals("-TERM 4321\n-KILL 4321\n", signals)
    }

    @Test
    fun `an exited renderer left as a zombie counts as stopped`() {
        // A zombie keeps /proc/<pid> with an empty cmdline until its parent reaps it.
        val becomesZombie = "kill() { printf '%s\\n' \"\$*\" >> \"\$signals\"; " +
            ": > \"\$procRoot/\$2/cmdline\"; " +
            "printf '%s\\n' \"\$2 (app_process) Z 1 0 0\" > \"\$procRoot/\$2/stat\"; }; "
        val (code, signals) = runStop(mapOf(4321 to helper("root-1")), killFunction = becomesZombie)
        assertEquals(0, code)
        assertEquals("-TERM 4321\n", signals)
    }

    @Test
    fun `an exiting renderer with no argv and no executable left is not reported as stuck`() {
        // After TERM the kernel has released its memory: argv reads empty and exe is gone, but
        // /proc/<pid> remains until the exit completes. KILL must not be wasted on an empty read.
        val exiting = "kill() { printf '%s\\n' \"\$*\" >> \"\$signals\"; " +
            ": > \"\$procRoot/\$2/cmdline\"; return 0; }; "
        val (code, signals) = runStop(mapOf(4321 to helper("root-1")), killFunction = exiting)
        assertEquals(0, code)
        assertEquals("-TERM 4321\n", signals)
    }

    @Test
    fun `stop failures map to fixed diagnostics`() {
        assertEquals(RootFailureCode.RENDERER_STUCK, rootStopFailureCode(RootCommand.STOP_RENDERER_STUCK))
        assertEquals(RootFailureCode.OTHER_RENDERER_RUNNING, rootStopFailureCode(RootCommand.STOP_OTHER_RENDERER))
        assertEquals(RootFailureCode.COMMAND_FAILED, rootStopFailureCode(RootCommand.STOP_IDENTITY_UNREADABLE))
        assertEquals(RootFailureCode.COMMAND_FAILED, rootStopFailureCode(127))
        assertTrue(
            RootStopFailed(RootCommand.STOP_OTHER_RENDERER, "expired renderer").message!!
                .contains("another HiLight renderer"),
        )
        // A denied su or a shell syntax error exits 1 or 2; it must not read as a renderer problem.
        listOf(1, 2).forEach {
            assertEquals(RootFailureCode.COMMAND_FAILED, rootStopFailureCode(it))
            assertTrue(RootStopFailed(it, "expired renderer").message!!.contains("stop command failed"))
        }
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
        assertEquals(RootCommand.STOP_OTHER_RENDERER, blocked.first)
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
            assertEquals(executable, RootCommand.STOP_OTHER_RENDERER, code)
            assertEquals("", signals)
        }
    }

    @Test
    fun `empty cmdline for app process still blocks recovery`() {
        val (code, signals) = runStop(
            mapOf(9876 to emptyList()), mapOf(9876 to "/system/bin/app_process64"),
        )
        assertEquals(RootCommand.STOP_OTHER_RENDERER, code)
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
        assertEquals(RootCommand.STOP_OTHER_RENDERER, code)
        assertEquals("", signals)
    }

    @Test
    fun `a different surviving helper still blocks recovery after pid reuse`() {
        val (code, signals) = runStop(mapOf(
            4321 to listOf("other-application"), 9876 to helper("root-2"),
        ))
        assertEquals(RootCommand.STOP_OTHER_RENDERER, code)
        assertEquals("", signals)
    }

    @Test
    fun `an unreadable or empty identity cannot prove exit`() {
        // A live user process with an executable but no readable argv could still be the helper.
        val (code, signals) = runStop(mapOf(4321 to emptyList()), mapOf(4321 to "/system/bin/app_process64"))
        assertEquals(RootCommand.STOP_IDENTITY_UNREADABLE, code)
        assertEquals("", signals)
    }

    @Test
    fun `a renderer that already exited as a zombie does not block every retry`() {
        // Seen as an empty cmdline before any signal. It holds no binder session.
        val (code, signals) = runStop(
            mapOf(4321 to emptyList()), mapOf(4321 to "/system/bin/app_process64"), mapOf(4321 to 'Z'),
        )
        assertEquals(0, code)
        assertEquals("", signals)
    }

    @Test
    fun `a stale pid reused by a kernel thread is neither signalled nor a blocker`() {
        val (code, signals) = runStop(mapOf(4321 to emptyList()), states = mapOf(4321 to 'S'))
        assertEquals(0, code)
        assertEquals("", signals)
    }

    @Test
    fun `a renderer that exits between identity check and term still counts as stopped`() {
        val exitsFirst = "kill() { printf '%s\\n' \"\$*\" >> \"\$signals\"; " +
            "rm -r \"\$procRoot/\$2\"; return 1; }; "
        val (code, signals) = runStop(mapOf(4321 to helper("root-1")), killFunction = exitsFirst)
        assertEquals(0, code)
        assertEquals("-TERM 4321\n", signals)
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
        assertTrue(stop.contains("SECONDS + 5"))
        assertTrue(stop.contains("kill -KILL 4321"))
        assertFalse(stop.contains("kill -KILL \$p"))
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
