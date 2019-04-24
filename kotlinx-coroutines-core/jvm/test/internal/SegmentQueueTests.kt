package kotlinx.coroutines.internal

import com.devexperts.dxlab.lincheck.LinChecker
import com.devexperts.dxlab.lincheck.annotations.Operation
import com.devexperts.dxlab.lincheck.annotations.Param
import com.devexperts.dxlab.lincheck.paramgen.IntGen
import com.devexperts.dxlab.lincheck.strategy.stress.StressCTest
import kotlinx.atomicfu.atomic
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * This queue implementation is based on [SegmentQueue] for testing purposes and is organized as follows. Essentially,
 * the [SegmentBasedQueue] is represented as an infinite array of segments, each stores one element (see [OneElementSegment]).
 * Both [enqueue] and [dequeue] operations increment the corresponding global index ([enqIdx] for [enqueue] and
 * [deqIdx] for [dequeue]) and work with the indexed by this counter cell. Since both operations increment the indices
 * at first, there could be a race: [enqueue] increments [enqIdx], then [dequeue] checks that the queue is not empty
 * (that's true) and increments [deqIdx], looking into the corresponding cell after that; however, the cell is empty
 * because the [enqIdx] operation has not been put its element yet. To make the queue non-blocking, [dequeue] can mark
 * the cell with [BROKEN] token and retry the operation, [enqueue] at the same time should restart as well; this way,
 * the queue is obstruction-free.
 */
private class SegmentBasedQueue<T>(createFirstSegmentLazily: Boolean) : SegmentQueue<OneElementSegment<T>>(createFirstSegmentLazily) {
    override fun newSegment(id: Long, prev: OneElementSegment<T>?): OneElementSegment<T> = OneElementSegment(id, prev)

    private val enqIdx = atomic(0L)
    private val deqIdx = atomic(0L)

    // Returns the segments associated with the enqueued element.
    fun enqueue(element: T): OneElementSegment<T> {
        while (true) {
            var tail = this.last
            val enqIdx = this.enqIdx.getAndIncrement()
            tail = getSegment(tail, enqIdx) ?: continue
            if (tail.element.value === BROKEN) continue
            if (tail.element.compareAndSet(null, element)) return tail
        }
    }

    fun dequeue(): T? {
        while (true) {
            if (this.deqIdx.value >= this.enqIdx.value) return null
            var firstSegment = this.first
            val deqIdx = this.deqIdx.getAndIncrement()
            firstSegment = getSegmentAndMoveFirst(firstSegment, deqIdx) ?: continue
            var el = firstSegment.element.value
            if (el === null) {
                if (firstSegment.element.compareAndSet(null, BROKEN)) continue
                else el = firstSegment.element.value
            }
            if (el === REMOVED) continue
            return el as T
        }
    }

    val numberOfSegments: Int get() {
        var s: OneElementSegment<T>? = first
        var i = 0
        while (s != null) {
            s = s.next.value
            i++
        }
        return i
    }
}

private class OneElementSegment<T>(id: Long, prev: OneElementSegment<T>?) : Segment<OneElementSegment<T>>(id, prev) {
    val element = atomic<Any?>(null)

    override val removed get() = element.value === REMOVED

    fun removeSegment() {
        element.value = REMOVED
        remove()
    }
}

private val BROKEN = Symbol("BROKEN")
private val REMOVED = Symbol("REMOVED")

@RunWith(Parameterized::class)
class SegmentQueueTest(private val createFirstSegmentLazily: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "createFirstSegmentLazily={0}")
        fun testArguments() = listOf(true, false)
    }

    @Test
    fun simpleTest() {
        val q = SegmentBasedQueue<Int>(createFirstSegmentLazily)
        assertEquals(if (createFirstSegmentLazily) 0 else 1, q.numberOfSegments)
        assertEquals(null, q.dequeue())
        q.enqueue(1)
        assertEquals(1, q.numberOfSegments)
        q.enqueue(2)
        assertEquals(2, q.numberOfSegments)
        assertEquals(1, q.dequeue())
        assertEquals(2, q.numberOfSegments)
        assertEquals(2, q.dequeue())
        assertEquals(1, q.numberOfSegments)
        assertEquals(null, q.dequeue())

    }

    @Test
    fun testSegmentRemoving() {
        val q = SegmentBasedQueue<Int>(createFirstSegmentLazily)
        q.enqueue(1)
        val s = q.enqueue(2)
        q.enqueue(3)
        assertEquals(3, q.numberOfSegments)
        s.removeSegment()
        assertEquals(2, q.numberOfSegments)
        assertEquals(1, q.dequeue())
        assertEquals(3, q.dequeue())
        assertEquals(null, q.dequeue())
    }

    @Test
    fun testRemoveHeadSegment() {
        val q = SegmentBasedQueue<Int>(createFirstSegmentLazily)
        q.enqueue(1)
        val s = q.enqueue(2)
        assertEquals(1, q.dequeue())
        q.enqueue(3)
        s.removeSegment()
        assertEquals(3, q.dequeue())
        assertEquals(null, q.dequeue())
    }

    @Test
    fun stressTest() {
        val q = SegmentBasedQueue<Int>(createFirstSegmentLazily)
        val expectedQueue = ArrayDeque<Int>()
        val r = Random(0)
        repeat(1_000_000) {
            if (r.nextBoolean()) { // add
                val el = r.nextInt()
                q.enqueue(el)
                expectedQueue.add(el)
            } else { // remove
                assertEquals(expectedQueue.poll(), q.dequeue())
            }
        }
    }

    @Test
    fun stressTestRemoveSegmentsSerial() = stressTestRemoveSegments(false)

    @Test
    fun stressTestRemoveSegmentsRandom() = stressTestRemoveSegments(true)

    private fun stressTestRemoveSegments(random: Boolean) {
        val N = 1000_000
        val T = 1
        val q = SegmentBasedQueue<Int>(createFirstSegmentLazily)
        val segments = (1..N).map { q.enqueue(it) }.toMutableList()
        if (random) segments.shuffle()
        assertEquals(N, q.numberOfSegments)
        val nextSegmentIndex = AtomicInteger()
        val barrier = CyclicBarrier(T)
        (1..T).map {
            thread {
                while (true) {
                    barrier.await()
                    val i = nextSegmentIndex.getAndIncrement()
                    if (i >= N) break
                    segments[i].removeSegment()
                }
            }
        }.forEach { it.join() }
        assertEquals(2, q.numberOfSegments)
    }
}

@StressCTest
class SegmentQueueLFTest {
    private companion object {
        var createFirstSegmentLazily: Boolean = false
    }

    private val q = SegmentBasedQueue<Int>(createFirstSegmentLazily)

    @Operation
    fun add(@Param(gen = IntGen::class) x: Int) {
        q.enqueue(x)
    }

    @Operation
    fun poll(): Int? = q.dequeue()

    @Test
    fun test() {
        createFirstSegmentLazily = false
        LinChecker.check(SegmentQueueLFTest::class.java)
    }

    @Test
    fun testWithLazyFirstSegment() {
        createFirstSegmentLazily = true
        LinChecker.check(SegmentQueueLFTest::class.java)
    }
}