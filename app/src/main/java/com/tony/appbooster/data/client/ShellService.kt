package com.tony.appbooster.data.client

import android.util.Log
import com.tony.appbooster.IShellService
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Shizuku UserService implementation that runs with elevated (shell UID) privileges.
 *
 * This service is started by Shizuku and runs in a separate process with the same
 * privileges as ADB shell. It can execute any shell command.
 *
 * Note: This class runs in Shizuku's process, not the app's process.
 */
class ShellService : IShellService.Stub() {

    companion object {
        private const val TAG = "ShellService"
    }

    /** Drains process stdout/stderr in parallel; threads are daemons so they never hold the process open. */
    private val streamReaders = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "shell-stream-reader").apply { isDaemon = true }
    }

    /**
     * Runs [command] through `sh -c` and returns `[exitCode, stdout, stderr]`.
     *
     * stdout and stderr are drained concurrently. Reading them one after the other
     * deadlocks whenever the child fills the pipe buffer of the stream that is not
     * being read yet — a real risk here, since commands such as
     * `dumpsys package dexopt` produce output far larger than the buffer.
     */
    override fun executeCommand(command: String): Array<String> {
        Log.d(TAG, "Executing: $command")

        var process: Process? = null
        return try {
            val started = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process = started

            val stdout = drainAsync(started.inputStream)
            val stderr = drainAsync(started.errorStream)

            val output = stdout.get()
            val error = stderr.get()
            val exitCode = started.waitFor()

            Log.d(TAG, "Command completed: exitCode=$exitCode")

            arrayOf(exitCode.toString(), output.trim(), error.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Command failed", e)
            arrayOf("-1", "", e.message ?: "Unknown error")
        } finally {
            process?.destroy()
        }
    }

    /**
     * Reads [stream] to EOF on a worker thread, closing it when done.
     *
     * @return Future carrying the full text, or an empty string when reading fails.
     */
    private fun drainAsync(stream: InputStream): Future<String> =
        streamReaders.submit(Callable {
            try {
                stream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to drain process stream", e)
                ""
            }
        })

    override fun destroy() {
        Log.d(TAG, "ShellService destroyed")
        streamReaders.shutdownNow()
    }
}
