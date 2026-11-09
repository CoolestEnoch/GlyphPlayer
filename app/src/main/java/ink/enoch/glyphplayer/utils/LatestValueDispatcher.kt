package ink.enoch.glyphplayer.utils

/**
 * A capacity-one, latest-wins mailbox. post must enqueue asynchronously on a serial executor.
 * While 1 is computing, offers 2..9 replace the pending value; the next computation receives 9.
 * It never enqueues one executor task per request and never calls consume recursively.
 */
class LatestValueDispatcher<T : Any>(
    private val post: (Runnable) -> Unit,
    private val consume: (T) -> Unit
) {
    private val lock = Any()
    private var latest: T? = null
    private var queued = false
    private var closed = false

    fun offer(value: T) = synchronized(lock) {
        if (closed) return@synchronized
        latest = value
        enqueueLocked()
    }

    private fun enqueueLocked() {
        if (closed || queued) return
        queued = true
        post(Runnable { drain() })
    }

    private fun drain() {
        val value = synchronized(lock) {
            if (closed) null else latest.also { latest = null }
        }
        try {
            if (value != null) consume(value)
        } finally {
            synchronized(lock) {
                queued = false
                if (latest != null) enqueueLocked()
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        latest = null
    }
}
