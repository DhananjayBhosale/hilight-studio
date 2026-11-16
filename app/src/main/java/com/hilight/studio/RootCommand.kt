package com.hilight.studio

/** Phone-shell commands used by the direct-root backend. */
object RootCommand {
    fun start(bridgeDir: String, rendererInstanceId: String, apkPath: String): String {
        require(validInstanceId(rendererInstanceId)) { "invalid renderer instance id" }
        require(apkPath.startsWith("/")) { "APK path must be absolute" }
        return "[ -r ${quote(apkPath)} ] || { echo 'installed APK is not readable'; exit 1; }; " +
            "CLASSPATH=${quote(apkPath)} " +
            "nohup app_process / com.hilight.core.AdbHelper --owner root " +
            "--instance ${quote(rendererInstanceId)} --exclusive --dir ${quote(bridgeDir)} " +
            "> /data/local/tmp/hilight-root.log 2>&1 < /dev/null & echo ${'$'}!"
    }

    /**
     * Validates and stops only the exact acknowledged source. A final read-only scan fails if a
     * different helper remains, closing the shared-file duplicate hole without signaling it.
     */
    fun stop(pid: Int, owner: String = "root", rendererInstanceId: String = ""): String =
        platformShell(stopBody(pid, owner, rendererInstanceId))

    // su implementations may select a different shell. The NUL reader below requires Android mksh.
    internal fun platformShell(command: String): String = "/system/bin/sh -c ${quote(command)}"

    /** Unwrapped body so fixture-only process/signal overrides stay in the same shell. */
    internal fun stopBody(pid: Int, owner: String = "root", rendererInstanceId: String = ""): String {
        require(pid > 0) { "pid must be positive" }
        require(owner == "adb" || owner == "root") { "owner must be adb or root" }
        require(rendererInstanceId.isEmpty() || validInstanceId(rendererInstanceId)) {
            "invalid renderer instance id"
        }
        val readCmdline = "tr '\\000' ' ' < /proc/$pid/cmdline 2>/dev/null"
        val original = "printf '%s' \"${'$'}original\""
        val exactEntry = "$original | grep -Eq " +
            "'^([^ ]*/)?app_process(32|64)? / com\\.hilight\\.core\\.AdbHelper( |${'$'})'"
        var identity = "$exactEntry && " + if (owner == "root") {
            "$original | grep -Fq -- ' --owner root '"
        } else {
            "$original | grep -Fq 'com.hilight.core.AdbHelper' && " +
                "! $original | grep -Fq -- ' --owner root '"
        }
        if (rendererInstanceId.isNotEmpty()) {
            // Read the NUL-delimited argv directly and compare the value token following
            // --instance. A flattened substring check would let root-1 match root-10.
            identity += " && tr '\\000' '\\n' < /proc/$pid/cmdline 2>/dev/null | " +
                "{ prev=''; while IFS= read -r arg; do " +
                "if [ \"${'$'}prev\" = --instance ] && " +
                "[ \"${'$'}arg\" = \"$rendererInstanceId\" ]; then exit 0; fi; " +
                "prev=${'$'}arg; done; exit 1; }"
        }
        // Android's mksh reads NUL-delimited argv directly. Forking tr for every process made
        // this scan consume the stop deadline even when the recorded renderer was already gone.
        // Keep the empty/unreadable-cmdline executable check and reject every surviving helper.
        // Kernel threads also have an empty cmdline but no executable. Testing exe with a shell
        // builtin first keeps hundreds of kernel threads from each forking readlink (issue #19:
        // on KernelSU that alone exceeded the cleanup deadline after every reboot).
        val rejectOtherHelper =
            "echo 'HiLight cleanup: scanning remaining renderers'; " +
            "for d in /proc/[0-9]*; do a=''; b=''; c=''; " +
                "{ IFS= read -r -d '' a; case \"${'$'}{a##*/}\" in " +
                "app_process|app_process32|app_process64) " +
                "IFS= read -r -d '' b; IFS= read -r -d '' c;; esac; } " +
                "2>/dev/null < \"${'$'}d/cmdline\"; if [ -z \"${'$'}a\" ]; then " +
                "[ -e \"${'$'}d/exe\" ] || continue; " +
                // An exited helper awaiting reaping holds nothing; never let it block takeover.
                "z=''; { IFS= read -r z; } 2>/dev/null < \"${'$'}d/stat\"; z=${'$'}{z##*\") \"}; " +
                "[ \"${'$'}{z%% *}\" = Z ] && continue; " +
                "e=${'$'}(readlink ${'$'}d/exe 2>/dev/null) || e=''; " +
                "x=${'$'}{e##*/}; if [ \"${'$'}x\" = app_process ] || " +
                "[ \"${'$'}x\" = app_process32 ] || " +
                "[ \"${'$'}x\" = app_process64 ]; then exit $STOP_OTHER_RENDERER; fi; continue; fi; " +
                "x=${'$'}{a##*/}; " +
                "if { [ \"${'$'}x\" = app_process ] || " +
                "[ \"${'$'}x\" = app_process32 ] || [ \"${'$'}x\" = app_process64 ]; } && " +
                "[ \"${'$'}b\" = / ] && " +
                "[ \"${'$'}c\" = com.hilight.core.AdbHelper ]; then exit $STOP_OTHER_RENDERER; fi; done"
        // A zombie has exited and released its binder session; only its parent can reap it.
        val readState = "st=''; { IFS= read -r st; } 2>/dev/null < /proc/$pid/stat; " +
            "s=${'$'}{st##*\") \"}; s=${'$'}{s%% *}"
        return "echo 'HiLight cleanup: checking source identity'; " +
            "original=''; if [ -d /proc/$pid ]; then $readState; " +
            // An exited renderer waiting to be reaped is a zombie with an empty cmdline. It holds
            // no binder session, so skip it instead of failing every retry until its parent reaps it.
            "if [ \"${'$'}s\" != Z ]; then " +
            "original=${'$'}($readCmdline) || original=''; " +
            // An empty cmdline is only safe to pass when the PID vanished, became a zombie, or now
            // belongs to a kernel thread (no executable). Any other live process fails closed.
            "if [ -z \"${'$'}original\" ]; then $readState; " +
            "if [ -d /proc/$pid ] && [ \"${'$'}s\" != Z ] && [ -e /proc/$pid/exe ]; then " +
            "exit $STOP_IDENTITY_UNREADABLE; fi; " +
            // A PID may be reused after the helper dies. Never signal its new owner. The final
            // scan still rejects every surviving helper, including a different instance at this PID.
            "elif ( $identity ); then " +
            "kill -TERM $pid 2>/dev/null || [ ! -d /proc/$pid ] || exit $STOP_IDENTITY_UNREADABLE; " +
            "else original=''; fi; fi; fi; " +
            "if [ -n \"${'$'}original\" ]; then " +
            "echo 'HiLight cleanup: waiting for renderer exit'; " +
            // Wall-clock budgets, not iteration counts: forks are slow on some root managers, and
            // a counted loop plus the scan could outlive the caller's timeout (issue #19).
            "forced=0; end=${'$'}((SECONDS + $TERM_GRACE_SECONDS)); " +
            "hard=${'$'}((SECONDS + $TERM_GRACE_SECONDS + $KILL_GRACE_SECONDS)); " +
            "while [ -d /proc/$pid ]; do $readState; [ \"${'$'}s\" = Z ] && break; " +
            "current=${'$'}($readCmdline) || current=''; " +
            "[ -n \"${'$'}current\" ] && [ \"${'$'}current\" != \"${'$'}original\" ] && break; " +
            "[ ${'$'}SECONDS -ge ${'$'}hard ] && break; " +
            // A renderer hung in its shutdown hook ignores TERM forever. Only the same exact
            // process identity may be killed; the binder session is released by process death and
            // the next renderer's startup cleanup clears any panel state left behind. A momentary
            // empty read doesn't use up the force: KILL goes on the next read that proves identity.
            "if [ ${'$'}forced = 0 ] && [ ${'$'}SECONDS -ge ${'$'}end ] && " +
            "[ \"${'$'}current\" = \"${'$'}original\" ]; then forced=1; " +
            "hard=${'$'}((SECONDS + $KILL_GRACE_SECONDS)); " +
            "echo 'HiLight cleanup: forcing renderer exit'; kill -KILL $pid 2>/dev/null; fi; " +
            "sleep 0.1; done; " +
            // Stuck only if the same renderer provably remains. A process that vanishes during
            // these reads, or is left with no argv and no executable, has exited.
            "if [ -d /proc/$pid ]; then $readState; if [ \"${'$'}s\" != Z ]; then " +
            "current=${'$'}($readCmdline) || current=''; " +
            "if [ -n \"${'$'}current\" ]; then " +
            "[ \"${'$'}current\" = \"${'$'}original\" ] && exit $STOP_RENDERER_STUCK; " +
            "elif [ -d /proc/$pid ] && [ -e /proc/$pid/exe ]; then exit $STOP_RENDERER_STUCK; " +
            "fi; fi; fi; fi; " +
            "$rejectOtherHelper; exit 0"
    }

    /**
     * Exit codes of [stop]. Any other non-zero code is a shell or su failure. Kept clear of 1 and 2,
     * which a denied su or a shell syntax error also return, so diagnostics can't misreport them.
     */
    const val STOP_IDENTITY_UNREADABLE = 41
    const val STOP_RENDERER_STUCK = 42
    const val STOP_OTHER_RENDERER = 43
    private const val TERM_GRACE_SECONDS = 5
    private const val KILL_GRACE_SECONDS = 3

    /** Fixed, user-safe description of a failed [stop]; never includes command output. */
    fun stopFailure(code: Int): String = when (code) {
        STOP_IDENTITY_UNREADABLE -> "the previous renderer's identity could not be read"
        STOP_RENDERER_STUCK -> "the previous renderer did not exit, even after being forced"
        STOP_OTHER_RENDERER -> "another HiLight renderer is still running (for example one started over ADB)"
        else -> "the stop command failed (exit $code)"
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun validInstanceId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9._:-]{1,96}"))

}
