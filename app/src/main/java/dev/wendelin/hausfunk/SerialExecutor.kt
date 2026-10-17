package dev.wendelin.hausfunk

import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One dedicated thread that runs tasks in order. After [shutdown] new tasks are dropped instead of
 * throwing, because socket readers and system callbacks may still deliver late events and an
 * exception on their threads would crash the app.
 */
class SerialExecutor(name: String) : Executor {
    private val delegate = ScheduledThreadPoolExecutor(1) { Thread(it, name) }.apply {
        // Cancelled timeouts must not pile up, and shutdown must not wait for them.
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    override fun execute(command: Runnable) {
        try {
            delegate.execute(command)
        } catch (e: RejectedExecutionException) {
            // Shut down: late events are no longer of interest.
        }
    }

    /** Like [execute], but says whether the task was taken (false after shutdown). */
    fun tryExecute(command: Runnable): Boolean = try {
        delegate.execute(command)
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    /** Runs [command] on this thread after [delayMillis]; null after shutdown. Cancel with the result. */
    fun schedule(delayMillis: Long, command: Runnable): Future<*>? = try {
        delegate.schedule(command, delayMillis, TimeUnit.MILLISECONDS)
    } catch (e: RejectedExecutionException) {
        null
    }

    /** Runs the tasks already queued, then ends the thread. */
    fun shutdown() = delegate.shutdown()
}
