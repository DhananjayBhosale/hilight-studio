package com.hilight.studio

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Drain available output while waiting, without a reader that descendants can keep blocked. */
internal object RootProcess {
    class CommandTimeout(message: String) : IllegalStateException(message)

    data class Result(val code: Int, val output: String)

    fun run(args: List<String>, timeoutSeconds: Long): Result {
        require(timeoutSeconds > 0)
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val stream = process.inputStream
        val buffer = ByteArray(4_096)
        val started = System.nanoTime()
        val timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds)

        fun drainAvailable() {
            // Limit each pass so a continuously writing child cannot starve the timeout check.
            repeat(16) {
                val available = stream.available()
                if (available <= 0) return
                // This is the only reader. Reading at most available bytes never waits for EOF.
                val count = stream.read(buffer, 0, minOf(buffer.size, available))
                if (count < 0) return
                val keep = minOf(count, 4_096 - output.size())
                if (keep > 0) output.write(buffer, 0, keep)
            }
        }

        try {
            // su -c never consumes interactive input. A detached renderer must not inherit it.
            process.outputStream.close()
            while (true) {
                drainAvailable()
                if (process.waitFor(5, TimeUnit.MILLISECONDS)) {
                    drainAvailable()
                    return Result(process.exitValue(), output.toString("UTF-8"))
                }
                if (System.nanoTime() - started >= timeoutNanos) {
                    // Report only our fixed progress markers, never arbitrary command output.
                    val phase = output.toString("UTF-8").lineSequence().lastOrNull {
                        it == "HiLight cleanup: checking source identity" ||
                            it == "HiLight cleanup: waiting for renderer exit" ||
                            it == "HiLight cleanup: scanning remaining renderers"
                    }
                    throw CommandTimeout("command timed out after ${timeoutSeconds}s" +
                        (phase?.let { " ($it)" } ?: ""))
                }
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            // No concurrent blocking read owns the stream lock, even if a descendant holds stdout.
            stream.close()
            process.errorStream.close()
        }
    }
}
