package com.hilight.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandTest {

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
