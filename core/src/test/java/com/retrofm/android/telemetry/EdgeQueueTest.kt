package com.retrofm.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the queue does with each answer. Items are plain ints and the "marshaled body" is their
 * decimal list, so what was sent — and in which order — is readable straight off [sent].
 */
class EdgeQueueTest {
    private var clock = 1_000_000L
    private val sent = mutableListOf<List<Int>>()
    private val answers = ArrayDeque<EdgeResult>()

    private fun queue(maxItems: Int = 100, maxBatch: Int = 10, maxBytes: Int = 10_000) = EdgeQueue<Int>(
        send = { body ->
            sent += String(body).split(",").filter { it.isNotEmpty() }.map { it.toInt() }
            answers.removeFirstOrNull() ?: EdgeResult.Ok
        },
        marshal = { items -> items.joinToString(",").toByteArray() },
        maxItems = maxItems,
        maxBatchItems = maxBatch,
        maxBatchBytes = maxBytes,
        baseBackoffMs = 30_000,
        maxBackoffMs = 300_000,
        now = { clock },
    )

    @Test
    fun `everything ships in order, in batches of the cap`() {
        val q = queue(maxBatch = 3)
        q.add((1..7).toList())
        assertTrue(q.drain())
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7)), sent)
        assertTrue(q.isEmpty())
    }

    @Test
    fun `413 halves the batch and never resends the same bytes`() {
        val q = queue(maxBatch = 8)
        q.add((1..8).toList())
        answers += EdgeResult.TooLarge
        assertTrue(q.drain())
        assertEquals(listOf((1..8).toList(), (1..4).toList(), (5..8).toList()), sent)
    }

    @Test
    fun `a single record that is still too large is dropped and counted, not retried forever`() {
        val q = queue(maxBatch = 1)
        q.add(listOf(1, 2))
        answers += EdgeResult.TooLarge
        assertTrue(q.drain())
        assertEquals(listOf(listOf(1), listOf(2)), sent)
        assertEquals(1, q.takeDropped())
    }

    @Test
    fun `a batch over the byte budget is split before anyone is asked`() {
        val q = queue(maxBatch = 10, maxBytes = 8)
        q.add(listOf(1111, 2222, 3333))      // "1111,2222,3333" = 14 bytes
        assertTrue(q.drain())
        assertEquals(listOf(listOf(1111), listOf(2222), listOf(3333)), sent)
    }

    @Test
    fun `429 keeps the batch at the front and waits out Retry-After exactly`() {
        val q = queue(maxBatch = 2)
        q.add(listOf(1, 2, 3))
        answers += EdgeResult.Retry(7_000, "rate limited")
        assertFalse(q.drain())
        assertTrue(q.backingOff())

        clock += 6_999
        assertFalse(q.drain())
        assertEquals(1, sent.size)            // nothing sent while backing off

        clock += 1
        assertTrue(q.drain())
        assertEquals(listOf(listOf(1, 2), listOf(1, 2), listOf(3)), sent)
    }

    @Test
    fun `5xx backs off exponentially up to the cap, and success resets it`() {
        val q = queue()
        q.add(listOf(1))
        val waits = mutableListOf<Long>()
        repeat(6) {
            answers += EdgeResult.Retry(null, "edge or store trouble (503)")
            val start = clock
            q.drain()
            while (q.backingOff()) clock += 1_000
            waits += clock - start
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), waits)

        assertTrue(q.drain())                 // Ok
        q.add(listOf(2))
        answers += EdgeResult.Retry(null, "edge or store trouble (502)")
        val start = clock
        q.drain()
        while (q.backingOff()) clock += 1_000
        assertEquals(30_000L, clock - start)
    }

    @Test
    fun `ignoring the backoff sends at once — the validated-network flush`() {
        val q = queue()
        q.add(listOf(1))
        answers += EdgeResult.Retry(null, "transport: UnknownHostException")
        q.drain()
        assertTrue(q.backingOff())
        assertTrue(q.drain(ignoreBackoff = true))
        assertEquals(listOf(listOf(1), listOf(1)), sent)
    }

    @Test
    fun `an auth refusal drops the batch and backs off at the cap`() {
        val q = queue(maxBatch = 1)
        q.add(listOf(1, 2))
        answers += EdgeResult.Drop(401, "unknown source key", auth = true)
        assertFalse(q.drain())
        assertEquals(listOf(listOf(1)), sent)
        assertEquals(1, q.size)               // 1 dropped, 2 still waiting
        clock += 299_999
        assertTrue(q.backingOff())
        clock += 1
        assertFalse(q.backingOff())
    }

    @Test
    fun `a non-auth refusal drops that batch only and carries on`() {
        val q = queue(maxBatch = 1)
        q.add(listOf(1, 2))
        answers += EdgeResult.Drop(422, "refused", auth = false)
        assertTrue(q.drain())
        assertEquals(listOf(listOf(1), listOf(2)), sent)
    }

    @Test
    fun `the buffer is bounded drop-oldest and the loss is counted`() {
        val q = queue(maxItems = 3)
        q.add((1..5).toList())
        assertEquals(2, q.takeDropped())
        assertEquals(0, q.takeDropped())
        q.drain()
        assertEquals(listOf(listOf(3, 4, 5)), sent)
    }

    @Test
    fun `the send-time filter discards what it refuses`() {
        val q = queue()
        q.add((1..6).toList())
        assertTrue(q.drain(admit = { it % 2 == 0 }))
        assertEquals(listOf(listOf(2, 4, 6)), sent)
        assertTrue(q.isEmpty())
    }

    @Test
    fun `a spool write carries only what no earlier write carried`() {
        val q = queue()
        q.add(listOf(1, 2))
        assertEquals(listOf(1, 2), q.takeUnspooled())
        q.add(listOf(3))
        assertEquals(listOf(3), q.takeUnspooled())
        assertEquals(emptyList<Int>(), q.takeUnspooled())
        q.markUnspooled(listOf(3))            // that write failed
        assertEquals(listOf(3), q.takeUnspooled())
    }

    @Test
    fun `restored records go ahead of the live ones`() {
        val q = queue()
        q.add(listOf(3, 4))
        q.addFirst(listOf(1, 2))
        q.drain()
        assertEquals(listOf(listOf(1, 2, 3, 4)), sent)
    }
}
