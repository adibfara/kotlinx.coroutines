package kotlinx.coroutines.internal

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic

/**
 * Essentially, this segment queue is an infinite array of segments, which is represented as
 * a Michael-Scott queue of them. All segments are instances of [Segment] interface and
 * follow in natural order (see [Segment.id]) in the queue.
 *
 * In some data structures, like `Semaphore`, this queue is used for storing suspended continuations
 * and is always empty in uncontended scenarios. Therefore, there is no need in creating
 * the first segment in advance in this case. A special `createFirstSegmentLazily` is introduced
 * to create segments lazily, on the first [getSegment] invocation; it is set to `false` by default.
 */
internal abstract class SegmentQueue<S: Segment<S>>(createFirstSegmentLazily: Boolean = false) {
    private val _head: AtomicRef<S?>
    /**
     * Returns the first segment in the queue. All segments with lower [id]
     */
    protected val first: S? get() = _head.value

    private val _tail: AtomicRef<S?>
    protected val last: S? get() = _tail.value

    init {
        val initialSegment = if (createFirstSegmentLazily) null else newSegment(0)
        _head = atomic(initialSegment)
        _tail = atomic(initialSegment)
    }

    /**
     * The implementation should create an instance of segment [S] with the specified id
     * and initial reference to the previous one.
     */
    abstract fun newSegment(id: Long, prev: S? = null): S

    /**
     * Finds a segment with the specified [id] following by next references from the
     * [startFrom] segment. The typical use-case is reading [last] or [first], doing some
     * synchronization, and invoking [getSegment] or [getSegmentAndMoveFirst] correspondingly
     * to find the required segment.
     */
    protected fun getSegment(startFrom: S?, id: Long): S? {
        // Try to create the first segment if [startFrom] is null.
        // This occurs if `createFirstSegmentLazily` was set to `true`.
        var startFrom = startFrom
        if (startFrom === null) {
            val firstSegment = newSegment(0)
            if (_head.compareAndSet(null, firstSegment))
                startFrom = firstSegment
            else {
                startFrom = first!!
            }
        }
        if (startFrom.id > id) return null
        // Go through `next` references and add new segments if needed,
        // similarly to the `push` in the Michael-Scott queue algorithm.
        // The only difference is that `CAS failure` means that the
        // required segment has already been added, so the algorithm just
        // uses it. This way, only one segment with each id can be in the queue.
        var cur: S = startFrom
        while (cur.id < id) {
            var curNext = cur.next.value
            if (curNext == null) {
                // Add a new segment.
                val newTail = newSegment(cur.id + 1, cur)
                curNext = if (cur.next.compareAndSet(null, newTail)) {
                    if (cur.removed) {
                        cur.remove()
                    }
                    moveTailForward(newTail)
                    newTail
                } else {
                    cur.next.value!!
                }
            }
            cur = curNext
        }
        if (cur.id != id) return null
        return cur
    }

    /**
     * Invokes [getSegment] and replaces [first] with the result if its [id] is greater.
     */
    protected fun getSegmentAndMoveFirst(startFrom: S?, id: Long): S? {
        if (startFrom !== null && startFrom.id == id) return startFrom
        val s = getSegment(startFrom, id) ?: return null
        moveHeadForward(s)
        return s
    }

    /**
     * Updates [_head] to the specified segment
     * if its `id` is greater.
     */
    private fun moveHeadForward(new: S) {
        while (true) {
            val cur = first!!
            if (cur.id > new.id) return
            if (_head.compareAndSet(cur, new)) {
                new.prev.value = null
                return
            }
        }
    }

    /**
     * Updates [_tail] to the specified segment
     * if its `id` is greater.
     */
    private fun moveTailForward(new: S) {
        while (true) {
            val cur = last
            if (cur !== null && cur.id > new.id) return
            if (_tail.compareAndSet(cur, new)) return
        }
    }
}

/**
 * Each segment in [SegmentQueue] has a unique id and is created by [SegmentQueue.newSegment].
 * Essentially, this is a node in the Michael-Scott queue algorithm, but with
 * maintaining [prev] pointer for efficient [remove] implementation.
 */
internal abstract class Segment<S: Segment<S>>(val id: Long, prev: S?) {
    // Pointer to the next segment, updates similarly to the Michael-Scott queue algorithm.
    val next = atomic<S?>(null)
    // Pointer to the previous segment, updates in [remove] function.
    val prev = atomic<S?>(null)

    /**
     * Returns `true` if this segment is logically removed from the queue.
     * The [remove] function should be called right after it becomes logically removed.
     */
    abstract val removed: Boolean

    init {
        this.prev.value = prev
    }

    /**
     * Removes this segment physically from the segment queue. The segment should be
     * logically removed (so [removed] returns `true`) at the point of invocation.
     */
    fun remove() {
        check(removed) { " The segment should be logically removed at first "}
        // Read `next` and `prev` pointers.
        val next = this.next.value ?: return // tail cannot be removed
        val prev = prev.value ?: return // head cannot be removed
        // Link `next` and `prev`.
        next.movePrevToLeft(prev)
        prev.movePrevNextToRight(next)
        // Check whether `prev` and `next` are still in the queue
        // and help with removing them if needed.
        if (prev.removed)
            prev.remove()
        if (next.removed)
            next.remove()
    }

    /**
     * Updates [next] pointer to the specified segment if
     * the [id] of the specified segment is greater.
     */
    private fun movePrevNextToRight(next: S) {
        while (true) {
            val curNext = this.next.value as S
            if (next.id <= curNext.id) return
            if (this.next.compareAndSet(curNext, next)) return
        }
    }

    /**
     * Updates [prev] pointer to the specified segment if
     * the [id] of the specified segment is lower.
     */
    private fun movePrevToLeft(prev: S) {
        while (true) {
            val curPrev = this.prev.value ?: return
            if (curPrev.id <= prev.id) return
            if (this.prev.compareAndSet(curPrev, prev)) return
        }
    }
}