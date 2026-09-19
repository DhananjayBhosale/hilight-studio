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
    fun stop(pid: Int, owner: String = "root", rendererInstanceId: String = ""): String {
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
        val rejectOtherHelper =
            "echo 'HiLight cleanup: scanning remaining renderers'; " +
            "for d in /proc/[0-9]*; do a=''; b=''; c=''; " +
                "{ IFS= read -r -d '' a; case \"${'$'}{a##*/}\" in " +
                "app_process|app_process32|app_process64) " +
                "IFS= read -r -d '' b; IFS= read -r -d '' c;; esac; } " +
                "2>/dev/null < \"${'$'}d/cmdline\"; if [ -z \"${'$'}a\" ]; then " +
                "e=${'$'}(readlink ${'$'}d/exe 2>/dev/null) || e=''; " +
                "x=${'$'}{e##*/}; if [ \"${'$'}x\" = app_process ] || " +
                "[ \"${'$'}x\" = app_process32 ] || " +
                "[ \"${'$'}x\" = app_process64 ]; then exit 1; fi; continue; fi; " +
                "x=${'$'}{a##*/}; " +
                "if { [ \"${'$'}x\" = app_process ] || " +
                "[ \"${'$'}x\" = app_process32 ] || [ \"${'$'}x\" = app_process64 ]; } && " +
                "[ \"${'$'}b\" = / ] && " +
                "[ \"${'$'}c\" = com.hilight.core.AdbHelper ]; then exit 1; fi; done"
        return "echo 'HiLight cleanup: checking source identity'; " +
            "original=''; if [ -d /proc/$pid ]; then " +
            "original=${'$'}($readCmdline) || exit 1; [ -n \"${'$'}original\" ] || exit 1; " +
            // A PID may be reused after the helper dies. Never signal its new owner. The final
            // scan still rejects every surviving helper, including a different instance at this PID.
            "if ( $identity ); then kill -TERM $pid 2>/dev/null || exit 1; " +
            "else original=''; fi; fi; " +
            "if [ -n \"${'$'}original\" ]; then " +
            "echo 'HiLight cleanup: waiting for renderer exit'; i=0; " +
            "while [ ${'$'}i -lt 65 ] && [ -d /proc/$pid ]; do " +
            "current=${'$'}($readCmdline) || current=''; " +
            "if [ -z \"${'$'}current\" ]; then sleep 0.1; " +
            "i=${'$'}((i + 1)); continue; fi; " +
            "[ \"${'$'}current\" != \"${'$'}original\" ] && break; " +
            "sleep 0.1; i=${'$'}((i + 1)); done; " +
            "if [ -d /proc/$pid ]; then current=${'$'}($readCmdline) || exit 1; " +
            "[ -n \"${'$'}current\" ] || exit 1; " +
            "[ \"${'$'}current\" = \"${'$'}original\" ] && exit 1; fi; fi; " +
            "$rejectOtherHelper; exit 0"
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun validInstanceId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9._:-]{1,96}"))

}
