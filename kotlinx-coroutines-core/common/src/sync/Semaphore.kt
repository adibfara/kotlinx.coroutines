package kotlinx.coroutines.sync

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import kotlin.coroutines.resume
import kotlin.jvm.JvmField
import kotlin.math.max

/**
 * A counting semaphore for coroutines. It maintains a number of available permits.
 * Each [acquire] suspends if necessary until a permit is available, and then takes it.
 * Each [release] adds a permit, potentially releasing a suspended acquirer.
 *
 * Semaphore with `maxPermits = 1` is essentially a [Mutex].
 **/
public interface Semaphore {
    /**
     * Returns the current number of available permits available in this semaphore.
     */
    public val availablePermits: Int

    /**
     * Acquires a permit from this semaphore, suspending until one is available.
     * All suspending acquirers are processed in first-in-first-out (FIFO) order.
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     *
     * *Cancellation of suspended lock invocation is atomic* -- when this function
     * throws [CancellationException] it means that the mutex was not locked.
     *
     * Note, that this function does not check for cancellation when it is not suspended.
     * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
     *
     * Use [tryAcquire] to try acquire a permit of this semaphore without suspension.
     */
    public suspend fun acquire()

    /**
     * Tries to acquire a permit from this semaphore without suspension.
     *
     * @return `true` if a permit was acquired, `false` otherwise.
     */
    public fun tryAcquire(): Boolean

    /**
     * Releases a permit, returning it into this semaphore. Resumes the first
     * suspending acquirer if there is one at the point of invocation.
     */
    public fun release()
}

/**
 * Creates new [Semaphore] instance.
 */
@Suppress("FunctionName")
public fun Semaphore(maxPermits: Int, acquiredPermits: Int = 0): Semaphore = SemaphoreImpl(maxPermits, acquiredPermits)

/**
 * Executes the given [action] with acquiring a permit from this semaphore at the beginning
 * and releasing it after the [action] is completed.
 *
 * @return the return value of the [action].
 */
public suspend inline fun <T> Semaphore.withSemaphore(action: () -> T): T {
    acquire()
    try {
        return action()
    } finally {
        release()
    }
}

private class SemaphoreImpl(@JvmField val maxPermits: Int, acquiredPermits: Int)
    : Semaphore, SegmentQueue<SemaphoreSegment>(createFirstSegmentLazily = true)
{
    init {
        require(maxPermits > 0) { "Semaphore should have at least 1 permit"}
        require(acquiredPermits in 0..maxPermits) { "The number of acquired permits should be ≥ 0 and ≤ maxPermits" }
    }

    override fun newSegment(id: Long, prev: SemaphoreSegment?)= SemaphoreSegment(id, prev)

    /**
     * This counter indicates a number of available permits if it is non-negative,
     * or the size with minus sign otherwise. Note, that 32-bit counter is enough here
     * since the maximal number of available permits is [maxPermits] which is [Int],
     * and the maximum number of waiting acquirers cannot be greater than 2^31 in any
     * real application.
     */
    private val _availablePermits = atomic(maxPermits)
    override val availablePermits: Int get() = max(_availablePermits.value, 0)

    // The queue of waiting acquirers is essentially an infinite array based on `SegmentQueue`;
    // each segment contains a fixed number of slots. To determine a slot for each enqueue
    // and dequeue operation, we increment the corresponding counter at the beginning of the operation
    // and use the value before the increment as a slot number. This way, each enqueue-dequeue pair
    // works with an individual cell.
    private val enqIdx = atomic(0L)
    private val deqIdx = atomic(0L)

    override fun tryAcquire(): Boolean {
        _availablePermits.loop { p ->
            if (p <= 0) return false
            if (_availablePermits.compareAndSet(p, p - 1)) return true
        }
    }

    override suspend fun acquire() {
        val p = _availablePermits.getAndDecrement()
        if (p > 0) return // permit acquired
        addToQueueAndSuspend()
    }

    override fun release() {
        val p = _availablePermits.getAndUpdate { cur ->
            check(cur < maxPermits) { "The number of acquired permits cannot be greater than maxPermits" }
            cur + 1
        }
        if (p >= 0) return // no waiters
        resumeNextFromQueue()
    }

    private suspend fun addToQueueAndSuspend() = suspendAtomicCancellableCoroutine<Unit> sc@ { cont ->
        val last = this.last
        val enqIdx = enqIdx.getAndIncrement()
        val segment = getSegment(last, enqIdx / SEGMENT_SIZE)
        val i = (enqIdx % SEGMENT_SIZE).toInt()
        if (segment === null || segment[i].value === RESUMED || !segment[i].compareAndSet(null, cont)) {
            // already resumed
            cont.resume(Unit)
            return@sc
        }
        cont.invokeOnCancellation(handler = object : CancelHandler() {
            override fun invoke(cause: Throwable?) {
                segment.cancel(i)
                release()
            }
        }.asHandler)
    }

    private fun resumeNextFromQueue() {
        val first = this.first
        val deqIdx = deqIdx.getAndIncrement()
        val segment = getSegmentAndMoveFirst(first, deqIdx / SEGMENT_SIZE) ?: return
        val i = (deqIdx % SEGMENT_SIZE).toInt()
        val cont = segment[i].getAndUpdate {
            if (it === CANCELLED) CANCELLED else RESUMED
        }
        if (cont === null) return // just resumed
        if (cont === CANCELLED) return // Cancelled continuation invokes `release`
                                       // and resumes next suspended acquirer if needed.
        cont as CancellableContinuation<Unit>
        cont.resume(Unit)
    }
}

private class SemaphoreSegment(id: Long, prev: SemaphoreSegment?): Segment<SemaphoreSegment>(id, prev) {
    private val acquirers = atomicArrayOfNulls<Any?>(SEGMENT_SIZE)

    operator fun get(index: Int): AtomicRef<Any?> = acquirers[index]

    private val cancelledSlots = atomic(0)
    override val removed get() = cancelledSlots.value == SEGMENT_SIZE

    // Cleans the acquirer slot located by the specified index
    // and removes this segment physically if all slots are cleaned.
    fun cancel(index: Int) {
        // Clean the specified waiter
        acquirers[index].value = CANCELLED
        // Remove this segment if needed
        if (cancelledSlots.incrementAndGet() == SEGMENT_SIZE)
            remove()
    }
}

@SharedImmutable
private val RESUMED = Symbol("RESUMED")
@SharedImmutable
private val CANCELLED = Symbol("CANCELLED")
@SharedImmutable
private val SEGMENT_SIZE = systemProp("kotlinx.coroutines.semaphore.segmentSize", 32)