package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessTest {
    @Test fun `verbose output drains without blocking the command and stays bounded`() {
        val result = RootProcess.run(listOf("sh", "-c", "head -c 1048576 /dev/zero"), 3)
        assertEquals(0, result.code)
        assertEquals(4096, result.output.length)
    }

    @Test fun `commands receive eof on unused stdin`() {
        val result = RootProcess.run(listOf("sh", "-c", "cat >/dev/null; echo finished"), 3)
        assertEquals(0, result.code)
        assertEquals("finished\n", result.output)
    }

    @Test fun `command failure remains distinct from successful launch`() {
        val result = RootProcess.run(listOf("sh", "-c", "echo failed >&2; exit 7"), 3)
        assertEquals(7, result.code)
        assertEquals("failed\n", result.output)
    }

    @Test fun `descendant retaining stdout cannot retain a reader thread or delay completion`() {
        val before = Thread.getAllStackTraces().keys.count { it.name == "hilight-root-output" }
        val started = System.nanoTime()
        val result = RootProcess.run(listOf("sh", "-c", "sleep 3 & echo finished"), 2)
        assertEquals(0, result.code)
        assertEquals("finished\n", result.output)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
        assertEquals(before, Thread.getAllStackTraces().keys.count { it.name == "hilight-root-output" })
    }

    @Test fun `timeout remains bounded and identifies its duration`() {
        val started = System.nanoTime()
        val error = runCatching { RootProcess.run(listOf("sh", "-c", "exec sleep 30"), 1) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("1s"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4000)
    }

    @Test fun `cleanup timeout identifies its last phase without exposing arbitrary output`() {
        val error = runCatching {
            RootProcess.run(listOf("sh", "-c",
                "echo 'HiLight cleanup: scanning remaining renderers'; echo private-data; exec sleep 30"), 1)
        }.exceptionOrNull()
        assertEquals(
            "command timed out after 1s (HiLight cleanup: scanning remaining renderers)",
            error?.message,
        )
    }
}
