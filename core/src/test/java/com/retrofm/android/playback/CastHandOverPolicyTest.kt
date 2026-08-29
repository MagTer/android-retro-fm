package com.retrofm.android.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boolean is trivial; the claim's lifetime is not, and getting it wrong is silent both ways
 * — a leaked claim un-silences the exact hand-over this exists for, and a claim consumed too
 * early mutes the watchdog's rescue, which is the one case where local audio is the point.
 */
class CastHandOverPolicyTest {

    private val policy = CastHandOverPolicy(resumeLocallyOnSessionLoss = false)

    /** Walking out of Wi-Fi range: the receiver plays on, so the phone must not join in. */
    @Test
    fun `an unrequested hand-over is silenced`() {
        assertTrue(policy.silenceOnHandOver(fromRemote = true, toRemote = false))
    }

    /** The stall watchdog ending a dead session — getting the audio back is the whole point. */
    @Test
    fun `a hand-back this app asked for stays audible`() {
        policy.handBackRequested()
        assertFalse(policy.silenceOnHandOver(fromRemote = true, toRemote = false))
    }

    /** One hand-over per claim: the next session loss must not inherit the exemption. */
    @Test
    fun `the claim does not survive the hand-over it was made for`() {
        policy.handBackRequested()
        policy.silenceOnHandOver(fromRemote = true, toRemote = false)

        assertTrue(policy.silenceOnHandOver(fromRemote = true, toRemote = false))
    }

    /** endCurrentSession threw, so no transfer consumes the claim — it must be dropped. */
    @Test
    fun `an abandoned hand-back does not exempt the next hand-over`() {
        policy.handBackRequested()
        policy.handBackAbandoned()

        assertTrue(policy.silenceOnHandOver(fromRemote = true, toRemote = false))
    }

    /**
     * Going out to a receiver is the opposite direction and must neither silence anything nor
     * eat a claim that was made for the hand-over still to come.
     */
    @Test
    fun `a transfer to the receiver silences nothing and keeps the claim`() {
        policy.handBackRequested()

        assertFalse(policy.silenceOnHandOver(fromRemote = false, toRemote = true))
        assertFalse(policy.silenceOnHandOver(fromRemote = true, toRemote = false))
    }

    /** Local to local (no cast involved at all) is not a hand-over. */
    @Test
    fun `a local to local transfer is not a hand-over`() {
        assertFalse(policy.silenceOnHandOver(fromRemote = false, toRemote = false))
    }

    /** The kill switch restores Media3's default behaviour. */
    @Test
    fun `the config can restore resuming locally`() {
        val permissive = CastHandOverPolicy(resumeLocallyOnSessionLoss = true)
        assertFalse(permissive.silenceOnHandOver(fromRemote = true, toRemote = false))
    }
}
