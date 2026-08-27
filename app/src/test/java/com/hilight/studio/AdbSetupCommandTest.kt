package com.hilight.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSetupCommandTest {

    @Test
    fun `setup reset waits past engine stop bound before launch in the same shell`() {
        listOf(
            ADB_COMMAND to "[ -z \"\$live\" ] || exit 1",
            ADB_COMMAND_CMD to "[ \$live = 0 ] || exit 1",
        ).forEach { (command, finalSurvivorGuard) ->
            assertFalse(command.contains("pkill"))
            assertTrue(command.contains("\$i -lt 65"))
            assertTrue(command.contains("kill -TERM \$p"))
            assertTrue(command.contains("|| exit 1"))
            assertTrue(command.indexOf("\$i -lt 65") < command.indexOf("nohup app_process"))
            assertTrue(command.contains(finalSurvivorGuard))
            assertTrue(command.indexOf(finalSurvivorGuard) < command.indexOf("nohup app_process"))
            assertTrue(command.contains("--instance"))
            assertTrue(command.contains("--exclusive"))
        }
    }

    @Test
    fun `standalone reset has no replacement launch`() {
        assertTrue(ADB_RESET.contains("\$i -lt 65"))
        assertFalse(ADB_RESET.contains("nohup app_process"))
    }
}
