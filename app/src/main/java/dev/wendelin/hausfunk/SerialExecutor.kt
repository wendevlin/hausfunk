package dev.wendelin.hausfunk

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * One dedicated thread that runs tasks in order. After [shutdown] new tasks are dropped instead of
 * throwing, because socket readers and system callbacks may still deliver late events and an
 * exception on their threads would crash the app.
 */
class SerialExecutor(name: String) : Executor {
    private val delegate = Executors.newSingleThreadExecutor { Thread(it, name) }

    override fun execute(command: Runnable) {
        try {
            delegate.execute(command)
        } catch (e: RejectedExecutionException) {
            // Shut down: late events are no longer of interest.
        }
    }

    /** Runs the tasks already queued, then ends the thread. */
    fun shutdown() = delegate.shutdown()
}
