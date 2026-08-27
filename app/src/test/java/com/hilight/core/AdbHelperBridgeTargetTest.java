package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.json.JSONObject;
import org.junit.Test;

public final class AdbHelperBridgeTargetTest {

    @Test
    public void matchingInstanceMayApplyVisibleState() throws Exception {
        JSONObject input = visible().put("bridgeRendererInstanceId", "adb-one");

        JSONObject output = new JSONObject(
                AdbHelper.stateForRenderer(input.toString(), "adb-one", false));

        assertTrue(output.getBoolean("enabled"));
    }

    @Test
    public void competingInstanceCanOnlyApplyBroadcastIdle() throws Exception {
        JSONObject input = visible().put("bridgeRendererInstanceId", "adb-one");

        JSONObject output = new JSONObject(
                AdbHelper.stateForRenderer(input.toString(), "adb-two", false));

        assertFalse(output.getBoolean("enabled"));
        assertFalse(output.getBoolean("privacyOutputEnabled"));
        assertFalse(output.has("alert"));
        assertEquals(44L, output.getLong("stateRevision"));
    }

    @Test
    public void unscopedVisibleStateNeedsExclusiveLaunchProof() throws Exception {
        String input = visible().toString();

        assertFalse(new JSONObject(
                AdbHelper.stateForRenderer(input, "adb-one", false)).getBoolean("enabled"));
        assertTrue(new JSONObject(
                AdbHelper.stateForRenderer(input, "adb-one", true)).getBoolean("enabled"));
    }

    @Test
    public void launchRequiresOneExplicitValidInstance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AdbHelper.parseLaunchConfig(new String[]{"--owner", "adb"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> AdbHelper.parseLaunchConfig(new String[]{"--instance", "bad id"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> AdbHelper.parseLaunchConfig(new String[]{"--instance", "--exclusive"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> AdbHelper.parseLaunchConfig(
                        new String[]{"--instance", "adb-one", "--instance", "adb-two"}));

        AdbHelper.LaunchConfig config = AdbHelper.parseLaunchConfig(new String[]{
                "--owner", "root",
                "--instance", "root-1234.abcd",
                "--exclusive",
                "--dir", "/tmp/bridge"
        });
        assertEquals("root", config.owner);
        assertEquals("root-1234.abcd", config.instanceId);
        assertEquals("/tmp/bridge", config.dir);
        assertTrue(config.exclusive);
        assertTrue(AdbHelper.ownerMatchesUid("adb", 2000));
        assertTrue(AdbHelper.ownerMatchesUid("root", 0));
        assertFalse(AdbHelper.ownerMatchesUid("root", 2000));
        assertFalse(AdbHelper.ownerMatchesUid("adb", 0));
    }

    @Test
    public void singletonClaimRejectsOverlapAndIsKernelReleasedOnClose() throws Exception {
        File lockFile = File.createTempFile("hilight-renderer-", ".lock");
        try {
            AdbHelper.SingletonClaim first = AdbHelper.SingletonClaim.acquire(lockFile);
            assertNotNull(first);
            assertTrue(lockFile.canRead());
            assertTrue(lockFile.canWrite());
            assertThrows(
                    IOException.class,
                    () -> AdbHelper.SingletonClaim.acquire(lockFile));
            first.close();

            try (AdbHelper.SingletonClaim afterExit =
                         AdbHelper.SingletonClaim.acquire(lockFile)) {
                assertNotNull(afterExit);
            }
        } finally {
            // Exact test-owned temporary file only.
            lockFile.delete();
        }
    }

    @Test
    public void singletonClaimRefusesSymbolicLink() throws Exception {
        File target = File.createTempFile("hilight-renderer-target-", ".lock");
        File link = new File(target.getParentFile(), target.getName() + "-link");
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());

            assertThrows(
                    IOException.class,
                    () -> AdbHelper.SingletonClaim.acquire(link));
        } finally {
            // Exact test-owned paths only.
            Files.deleteIfExists(link.toPath());
            target.delete();
        }
    }

    private static JSONObject visible() throws Exception {
        return new JSONObject()
                .put("v", 2)
                .put("stateRevision", 44L)
                .put("enabled", true)
                .put("privacyOutputEnabled", false);
    }
}
