package dev.wendelin.hausfunk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerialExecutorTest {
    @Test
    fun runsTasksInOrderAndDropsTasksAfterShutdown() {
        val executor = SerialExecutor("test")
        val seen = mutableListOf<Int>()
        val done = CountDownLatch(1)
        repeat(100) { i -> executor.execute { seen += i } }
        executor.execute { done.countDown() }
        executor.shutdown()
        executor.execute { seen += -1 } // must neither throw nor run
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals((0 until 100).toList(), seen)
    }

    @Test
    fun schedulesAndRefusesAfterShutdown() {
        val executor = SerialExecutor("test")
        val ran = CountDownLatch(1)
        var cancelledRan = false
        executor.schedule(10) { ran.countDown() }
        executor.schedule(10) { cancelledRan = true }?.cancel(false)
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertTrue(executor.tryExecute {})
        executor.shutdown()
        assertEquals(false, executor.tryExecute {})
        assertEquals(null, executor.schedule(10) {})
        assertEquals(false, cancelledRan)
    }
}
