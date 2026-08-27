package com.hilight.studio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public final class HiLightUserServiceIdentityTest {

    @Test
    public void acceptsOnlyExactAdbHelperAndExpectedInstance() {
        byte[] adb = cmdline(
                "app_process", "/", "com.hilight.core.AdbHelper",
                "--owner", "adb", "--instance", "adb-one");

        assertTrue(HiLightUserService.isExactAdbHelperCmdline(adb, "adb-one"));
        assertTrue(HiLightUserService.isExactAdbHelperCmdline(adb, null));
        assertFalse(HiLightUserService.isExactAdbHelperCmdline(adb, "adb-two"));
        assertFalse(HiLightUserService.isExactAdbHelperCmdline(adb, ""));

        byte[] legacy = cmdline("/system/bin/app_process", "/",
                "com.hilight.core.AdbHelper");
        assertTrue(HiLightUserService.isExactAdbHelperCmdline(legacy, ""));
        assertTrue(HiLightUserService.isExactAdbHelperCmdline(legacy, null));
    }

    @Test
    public void rejectsRootUnrelatedAndShellTextThatMerelyMentionsHelper() {
        assertFalse(HiLightUserService.isExactAdbHelperCmdline(cmdline(
                "app_process", "/", "com.hilight.core.AdbHelper",
                "--owner", "root", "--instance", "root-one"), null));
        assertFalse(HiLightUserService.isExactAdbHelperCmdline(cmdline(
                "sh", "-c", "kill com.hilight.core.AdbHelper"), null));
        assertFalse(HiLightUserService.isExactAdbHelperCmdline(cmdline(
                "app_process", "/", "com.example.Other"), null));
        assertFalse(HiLightUserService.isExactAdbHelperCmdline(cmdline(
                "sh", "/", "com.hilight.core.AdbHelper"), null));
        assertFalse(HiLightUserService.isExactAdbHelperCmdline(cmdline(
                "app_process", "/", "com.example.Other", "com.hilight.core.AdbHelper"), null));
        assertFalse(HiLightUserService.isExactAdbHelperCmdline(cmdline(
                "app_process", "/", "com.hilight.core.AdbHelper", "--unknown"), null));
    }

    @Test
    public void terminationSelectionNeverTargetsDifferentHelperPidOrInstance() {
        byte[] expected = cmdline(
                "app_process", "/", "com.hilight.core.AdbHelper",
                "--owner", "adb", "--instance", "adb-one");
        byte[] other = cmdline(
                "app_process", "/", "com.hilight.core.AdbHelper",
                "--owner", "adb", "--instance", "adb-two");

        assertTrue(HiLightUserService.shouldSignalAdbHelper(
                4321, expected, 4321, "adb-one"));
        assertFalse(HiLightUserService.shouldSignalAdbHelper(
                4322, other, 4321, "adb-one"));
        assertFalse(HiLightUserService.shouldSignalAdbHelper(
                4321, other, 4321, "adb-one"));
    }

    @Test
    public void presentUnreadableExpectedProcessRemainsUnresolved() {
        byte[] original = cmdline(
                "app_process", "/", "com.hilight.core.AdbHelper",
                "--owner", "adb", "--instance", "adb-one");
        byte[] reused = cmdline("app_process", "/", "com.example.Other");

        assertTrue(HiLightUserService.originalProcessUnresolved(true, original, null));
        assertTrue(HiLightUserService.originalProcessUnresolved(true, original, original));
        assertFalse(HiLightUserService.originalProcessUnresolved(false, original, null));
        assertFalse(HiLightUserService.originalProcessUnresolved(true, original, reused));
    }

    @Test
    public void destroyExitActionRunsEvenWhenStopThrows() {
        AtomicBoolean exited = new AtomicBoolean(false);
        try {
            HiLightUserService.stopThenExit(
                    () -> { throw new IllegalStateException("cleanup failed"); },
                    () -> exited.set(true));
            fail("stop failure should remain visible to the caller");
        } catch (IllegalStateException expected) {
            assertTrue(exited.get());
        }
    }

    private static byte[] cmdline(String... args) {
        return (String.join("\0", args) + "\0").getBytes(StandardCharsets.UTF_8);
    }
}
