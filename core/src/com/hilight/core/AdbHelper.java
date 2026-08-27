package com.hilight.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.json.JSONObject;

/**
 * ADB host: `app_process` entry point, launched under the shell UID.
 *
 * This class ships inside the APK, so it can be started straight out of the installed app with no
 * file to push:
 *
 *   adb shell "CLASSPATH=$(pm path com.hilight.studio | head -1 | cut -d: -f2) \
 *              app_process / com.hilight.core.AdbHelper \
 *              --instance adb-$(cat /proc/sys/kernel/random/uuid)"
 *
 * The app cannot bind a cross-UID binder to us (a shell-UID process is killed by ActivityManager as
 * soon as it touches a ContentProvider), so state arrives as a JSON file that we poll.
 *
 * File ownership matters: on external storage a file keeps its creator's UID, and a file created
 * here would be unreadable by the app. The app creates both files; we only overwrite in place.
 */
public final class AdbHelper {

    private static final long POLL_MS = 100;
    private static final long STATUS_MS = 1000;
    private static final int ROOT_UID = 0;
    private static final int SHELL_UID = 2000;
    private static final String DEFAULT_DIR =
            "/storage/emulated/0/Android/data/com.hilight.studio/files/hilight";
    static final String SINGLETON_LOCK_PATH = "/data/local/tmp/hilight-renderer.lock";

    private final File stateFile;
    private final File statusFile;
    private final String owner;
    private final String instanceId;
    private final boolean allowInitialUnscopedVisible;
    private final Engine engine = new Engine();
    private final Object lifecycleLock = new Object();

    private long stamp = -1, size = -1, lastStatusWarn;
    private boolean engineStopped;
    private boolean rendererTargetEstablished;

    public static void main(String[] args) {
        try {
            LaunchConfig config = parseLaunchConfig(args);
            int actualUid = android.os.Process.myUid();
            if (!ownerMatchesUid(config.owner, actualUid)) {
                throw new IllegalArgumentException(
                        "--owner " + config.owner + " does not match process uid " + actualUid);
            }
            // This fixed path deliberately does not depend on owner or bridge directory: ADB and
            // root transports must contend for the same kernel-held claim before either Engine is
            // constructed, let alone started.
            try (SingletonClaim ignored = SingletonClaim.acquire(
                    new File(SINGLETON_LOCK_PATH))) {
                new AdbHelper(
                        new File(config.dir),
                        config.owner,
                        config.instanceId,
                        config.exclusive).run();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    /** Strict parsing is part of the safety boundary; an anonymous helper may never own LEDs. */
    static LaunchConfig parseLaunchConfig(String[] args) {
        String dir = DEFAULT_DIR;
        String owner = "adb";
        String instanceId = null;
        boolean exclusive = false;
        boolean sawDir = false, sawOwner = false, sawInstance = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--exclusive".equals(arg)) {
                if (exclusive) throw new IllegalArgumentException("duplicate --exclusive");
                exclusive = true;
            } else if ("--dir".equals(arg)) {
                if (sawDir) throw new IllegalArgumentException("duplicate --dir");
                dir = requiredValue(args, ++i, "--dir");
                sawDir = true;
            } else if ("--owner".equals(arg)) {
                if (sawOwner) throw new IllegalArgumentException("duplicate --owner");
                owner = requiredValue(args, ++i, "--owner");
                sawOwner = true;
            } else if ("--instance".equals(arg)) {
                if (sawInstance) throw new IllegalArgumentException("duplicate --instance");
                instanceId = requiredValue(args, ++i, "--instance");
                sawInstance = true;
            } else {
                throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }
        if (!sawInstance || !validInstanceId(instanceId)) {
            throw new IllegalArgumentException("a valid explicit --instance is required");
        }
        if (!("adb".equals(owner) || "root".equals(owner))) {
            throw new IllegalArgumentException("--owner must be adb or root");
        }
        return new LaunchConfig(dir, owner, instanceId, exclusive);
    }

    private static String requiredValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return args[index];
    }

    static final class LaunchConfig {
        final String dir;
        final String owner;
        final String instanceId;
        final boolean exclusive;

        LaunchConfig(String dir, String owner, String instanceId, boolean exclusive) {
            this.dir = dir;
            this.owner = owner;
            this.instanceId = instanceId;
            this.exclusive = exclusive;
        }
    }

    /**
     * Cross-UID process-lifetime renderer claim.
     *
     * Linux releases the advisory lock when app_process exits, including a crash or SIGKILL. The
     * inode is intentionally retained and made world-readable/writable so a shell-created file is
     * usable by root and a root-created file remains usable by the shell UID on the next launch.
     */
    static final class SingletonClaim implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private SingletonClaim(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static SingletonClaim acquire(File lockFile) throws IOException {
            FileChannel channel = null;
            FileLock lock = null;
            try {
                Path path = lockFile.toPath();
                // NOFOLLOW_LINKS prevents a root launch from opening or chmodding a shell-planted
                // symlink in /data/local/tmp. The regular-file check rejects devices and FIFOs.
                channel = FileChannel.open(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("renderer lock is not a regular file");
                }
                makeCrossUidWritable(path);
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException alreadyHeldInProcess) {
                    lock = null;
                }
                if (lock == null) throw new IOException("another AdbHelper owns the renderer lock");
                return new SingletonClaim(channel, lock);
            } catch (IOException | RuntimeException failure) {
                closeQuietly(lock);
                closeQuietly(channel);
                if (failure instanceof IOException) throw (IOException) failure;
                throw (RuntimeException) failure;
            }
        }

        private static void makeCrossUidWritable(Path path) throws IOException {
            Set<PosixFilePermission> crossUid =
                    PosixFilePermissions.fromString("rw-rw-rw-");
            PosixFileAttributeView view = Files.getFileAttributeView(
                    path,
                    PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("renderer lock has no POSIX permission view");
            try {
                view.setPermissions(crossUid);
                return;
            } catch (IOException | RuntimeException chmodFailure) {
                // A shell process cannot chmod an already-correct root-owned 0666 inode, although
                // it can safely open and lock it. Verify the actual mode before accepting that
                // expected root-to-ADB transition; never accept merely because this UID has access.
                Set<PosixFilePermission> actual;
                try {
                    actual = view.readAttributes().permissions();
                } catch (IOException | RuntimeException readFailure) {
                    chmodFailure.addSuppressed(readFailure);
                    if (chmodFailure instanceof IOException) throw (IOException) chmodFailure;
                    throw (RuntimeException) chmodFailure;
                }
                if (!actual.contains(PosixFilePermission.OTHERS_READ)
                        || !actual.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new IOException(
                            "cannot make renderer lock cross-UID writable",
                            chmodFailure);
                }
            }
        }

        @Override public void close() {
            closeQuietly(lock);
            closeQuietly(channel);
        }

        private static void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) return;
            try {
                closeable.close();
            } catch (Exception ignored) {
                // The kernel still releases a process's locks on exit.
            }
        }
    }

    private AdbHelper(
            File dir,
            String owner,
            String requestedInstanceId,
            boolean allowInitialUnscopedVisible) {
        if (!dir.isDirectory()) {
            Log.w("no " + dir + " yet — open HiLight Studio once so it can create the bridge files");
        }
        stateFile = new File(dir, "state.json");
        statusFile = new File(dir, "helper_status.json");
        this.owner = owner;
        if (!validInstanceId(requestedInstanceId)) {
            throw new IllegalArgumentException("a valid explicit --instance is required");
        }
        instanceId = requestedInstanceId;
        this.allowInitialUnscopedVisible = allowInitialUnscopedVisible;
    }

    private void run() throws Exception {
        Thread shutdownHook = new Thread(this::stopEngineOnce, "hilight-shutdown");
        Runtime runtime = Runtime.getRuntime();
        runtime.addShutdownHook(shutdownHook);
        try {
            engine.start();
            Log.i("watching bridge as " + owner);
            long lastStatus = 0;
            while (true) {
                long now = System.currentTimeMillis();
                reloadIfChanged();
                if (now - lastStatus >= STATUS_MS) {
                    lastStatus = now;
                    writeStatus(now);
                }
                Thread.sleep(POLL_MS);
            }
        } finally {
            // SIGTERM runs the hook; unexpected loop failures come through finally. Either path must
            // blank and close exactly once before app_process exits.
            stopEngineOnce();
            try {
                runtime.removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The VM is already shutting down and the hook owns cleanup.
            }
        }
    }

    private void stopEngineOnce() {
        synchronized (lifecycleLock) {
            if (engineStopped) return;
            engineStopped = true;
        }
        try {
            engine.stop();
        } catch (Throwable t) {
            Log.w("renderer shutdown cleanup failed: " + t);
        }
    }

    /**
     * Applies the state file whenever it has been rewritten.
     *
     * Any rewrite counts, even one whose bytes are unchanged: the document carries commands as well
     * as state — "arm" restarts the auto-off window — so an identical re-push is a fresh instruction,
     * not a no-op, and comparing contents would swallow it.
     *
     * The markers are recorded only once the read has produced something, so a read that loses the
     * race with the writer and comes back empty is retried on the next poll instead of being
     * discarded. (The old order recorded the file as seen first, which lost that update for good.)
     */
    private void reloadIfChanged() {
        long m = stateFile.lastModified(), s = stateFile.length();
        if (m == stamp && s == size) return;
        String raw = read(stateFile);
        if (raw == null || raw.isEmpty()) return;       // leave the markers; try again next poll
        stamp = m;
        size = s;
        JSONObject state = parse(raw);
        String target = state == null ? "" : state.optString("bridgeRendererInstanceId", "");
        if (instanceId.equals(target)) rendererTargetEstablished = true;
        engine.setState(stateForRenderer(
                raw,
                instanceId,
                allowInitialUnscopedVisible && !rendererTargetEstablished));
    }

    /**
     * A bridge state may light LEDs only when it names this exact helper instance. Disabled legacy
     * documents remain broadcast so every leftover helper can release during a handoff.
     */
    static String stateForRenderer(String raw, String instanceId, boolean allowUnscopedVisible) {
        try {
            JSONObject state = new JSONObject(raw);
            boolean potentiallyVisible = state.optBoolean("enabled", false)
                    || state.has("alert")
                    || state.optBoolean("privacyOutputEnabled", false);
            String target = state.optString("bridgeRendererInstanceId", "");
            if ((!target.isEmpty() && !instanceId.equals(target))
                    || (target.isEmpty() && potentiallyVisible && !allowUnscopedVisible)) {
                JSONObject idle = new JSONObject();
                idle.put("v", state.optInt("v", 2));
                idle.put("stateRevision", state.optLong("stateRevision", 0));
                idle.put("enabled", false);
                idle.put("arm", false);
                idle.put("privacyObserverEnabled", false);
                idle.put("privacyOutputEnabled", false);
                return idle.toString();
            }
            return raw;
        } catch (Throwable ignored) {
            // Engine owns parse error handling and retains its last valid state.
            return raw;
        }
    }

    private static JSONObject parse(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeStatus(long now) {
        if (!statusFile.exists()) {
            if (now - lastStatusWarn > 10_000) {
                lastStatusWarn = now;
                Log.w("no " + statusFile.getName() + " — open HiLight Studio once");
            }
            return;
        }
        try {
            // in-place truncating write, so the app stays the file's owner
            JSONObject status = new JSONObject(engine.status());
            status.put("owner", owner);
            status.put("rendererInstanceId", instanceId);
            byte[] data = status.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream f = new FileOutputStream(statusFile, false)) {
                f.write(data);
            }
        } catch (Exception e) {
            Log.w("status write failed: " + e);
        }
    }

    private static String read(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean validInstanceId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,96}");
    }

    static boolean ownerMatchesUid(String owner, int uid) {
        return ("adb".equals(owner) && uid == SHELL_UID)
                || ("root".equals(owner) && uid == ROOT_UID);
    }
}
