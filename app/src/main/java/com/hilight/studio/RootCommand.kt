package com.hilight.studio

/** Phone-shell commands used by the direct-root backend. */
object RootCommand {
    fun start(bridgeDir: String, rendererInstanceId: String): String {
        require(validInstanceId(rendererInstanceId)) { "invalid renderer instance id" }
        return "CLASSPATH=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2) " +
            "nohup app_process / com.hilight.core.AdbHelper --owner root " +
            "--instance ${quote(rendererInstanceId)} --exclusive --dir ${quote(bridgeDir)} " +
            "> /data/local/tmp/hilight-root.log 2>&1 & echo ${'$'}!"
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
        val rejectOtherHelper =
            "for d in /proc/[0-9]*; do c=${'$'}(tr '\\000' ' ' < ${'$'}d/cmdline " +
                "2>/dev/null) || c=''; if [ -z \"${'$'}c\" ]; then " +
                "e=${'$'}(readlink ${'$'}d/exe 2>/dev/null) || e=''; " +
                "x=${'$'}{e##*/}; if [ \"${'$'}x\" = app_process ] || " +
                "[ \"${'$'}x\" = app_process32 ] || " +
                "[ \"${'$'}x\" = app_process64 ]; then exit 1; fi; continue; fi; " +
                "set -- ${'$'}c; x=${'$'}{1##*/}; " +
                "if { [ \"${'$'}x\" = app_process ] || " +
                "[ \"${'$'}x\" = app_process32 ] || [ \"${'$'}x\" = app_process64 ]; } && " +
                "[ \"${'$'}2\" = / ] && " +
                "[ \"${'$'}3\" = com.hilight.core.AdbHelper ]; then exit 1; fi; done"
        return "original=''; if [ -d /proc/$pid ]; then " +
            "original=${'$'}($readCmdline) || exit 1; [ -n \"${'$'}original\" ] || exit 1; " +
            "( $identity ) || exit 1; kill -TERM $pid 2>/dev/null || exit 1; fi; " +
            "if [ -n \"${'$'}original\" ]; then i=0; " +
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
