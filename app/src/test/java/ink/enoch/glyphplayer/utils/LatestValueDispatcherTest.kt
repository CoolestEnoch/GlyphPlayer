package ink.enoch.glyphplayer.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestValueDispatcherTest {
    @Test fun arrivalsDuringCalculationReplaceEverythingExceptTheLatest() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val results = mutableListOf<Int>()
        val mailbox = LatestValueDispatcher<Int>({ worker.execute(it) }) { value ->
            if (value == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            synchronized(results) { results.add(value) }
            finished.countDown()
        }
        try {
            mailbox.offer(1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            (2..9).forEach(mailbox::offer)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 9), synchronized(results) { results.toList() })
        } finally {
            release.countDown()
            mailbox.close()
            worker.shutdownNow()
        }
    }

    @Test fun aLargeBurstUsesOneQueueSlotAndOnePendingValue() {
        val tasks = ArrayDeque<Runnable>()
        val results = mutableListOf<Int>()
        val mailbox = LatestValueDispatcher<Int>({ tasks.add(it) }, results::add)
        repeat(100_000) { mailbox.offer(it) }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(listOf(99_999), results)
        assertTrue(tasks.isEmpty())
    }

    @Test fun consumerCanOfferAgainWithoutRecursiveCalls() {
        val tasks = ArrayDeque<Runnable>()
        var depth = 0
        val results = mutableListOf<Int>()
        lateinit var mailbox: LatestValueDispatcher<Int>
        mailbox = LatestValueDispatcher({ tasks.add(it) }) { value ->
            depth++
            assertEquals(1, depth)
            results.add(value)
            if (value < 10_000) mailbox.offer(value + 1)
            depth--
        }
        mailbox.offer(1)
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(10_000, results.size)
    }

    @Test fun stopDropsPendingFramesAndLateCallbacks() {
        val tasks = ArrayDeque<Runnable>()
        val results = mutableListOf<Int>()
        val mailbox = LatestValueDispatcher<Int>({ tasks.add(it) }, results::add)
        mailbox.offer(1)
        mailbox.close()
        mailbox.offer(2)
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertTrue(results.isEmpty())
    }
}
