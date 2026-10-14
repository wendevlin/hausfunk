package dev.wendelin.hausfunk.pairing

import dev.wendelin.hausfunk.signaling.PairAbort
import dev.wendelin.hausfunk.signaling.PairCommit
import dev.wendelin.hausfunk.signaling.PairConfirm
import dev.wendelin.hausfunk.signaling.PairReveal
import dev.wendelin.hausfunk.signaling.SignalingMessage
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingProtocolTest {
    private val parentFp = ByteArray(32) { 1 }
    private val babyFp = ByteArray(32) { 2 }
    private val parentId = "01".repeat(32)
    private val babyId = "02".repeat(32)
    private val parentNonce = ByteArray(32) { 3 }
    private val babyNonce = ByteArray(32) { 4 }

    private fun parent() = PairingProtocol(true, parentNonce, parentId, "Papa", parentFp, babyFp)
    private fun baby() = PairingProtocol(false, babyNonce, babyId, "Kinderzimmer", babyFp, parentFp)

    private fun List<PairingStep>.sent() = filterIsInstance<PairingStep.Send>().map { it.message }
    private fun List<PairingStep>.aborted() = any { it is PairingStep.Abort }
    private fun List<PairingStep>.paired() = any { it is PairingStep.Paired }

    /** Delivers every message until both sides are quiet; returns all steps per side. */
    private fun exchange(p: PairingProtocol, b: PairingProtocol): Pair<List<PairingStep>, List<PairingStep>> {
        val pSteps = mutableListOf<PairingStep>()
        val bSteps = mutableListOf<PairingStep>()
        var toBaby = p.start().also { pSteps += it }.sent()
        var toParent = emptyList<SignalingMessage>()
        while (toBaby.isNotEmpty() || toParent.isNotEmpty()) {
            val nextToParent = toBaby.flatMap { b.onMessage(it).also { s -> bSteps += s }.sent() }
            val nextToBaby = toParent.flatMap { p.onMessage(it).also { s -> pSteps += s }.sent() }
            toParent = nextToParent
            toBaby = nextToBaby
        }
        return pSteps to bSteps
    }

    @Test
    fun honestRunShowsTheSameEmojisAndPairsAfterBothConfirm() {
        val p = parent()
        val b = baby()
        val (pSteps, bSteps) = exchange(p, b)
        val pEmojis = pSteps.filterIsInstance<PairingStep.Compare>().single()
        val bEmojis = bSteps.filterIsInstance<PairingStep.Compare>().single()
        assertEquals(pEmojis.emojis, bEmojis.emojis)
        assertEquals(7, pEmojis.emojis.size)
        assertEquals("Kinderzimmer", pEmojis.peerName)
        assertEquals("Papa", bEmojis.peerName)

        // Parent confirms first: nothing stored until the baby's confirmation arrives.
        val pConfirm = p.confirm()
        assertEquals(listOf<SignalingMessage>(PairConfirm), pConfirm.sent())
        assertTrue(!pConfirm.paired())
        val bOnConfirm = b.onMessage(PairConfirm)
        assertTrue(!bOnConfirm.paired(), "baby must not store before its own confirmation")
        val bConfirm = b.confirm()
        assertTrue(bConfirm.paired())
        assertTrue(p.onMessage(PairConfirm).paired())
    }

    @Test
    fun peerConfirmationAloneStoresNothing() {
        val p = parent()
        val b = baby()
        exchange(p, b)
        assertTrue(!b.onMessage(PairConfirm).paired())
        assertTrue(b.reject("Don't match").aborted())
        assertTrue(b.confirm().isEmpty(), "nothing after an abort")
    }

    @Test
    fun revealBeforeCommitmentAborts() {
        val b = baby()
        val steps = b.onMessage(PairReveal(b64(parentNonce), parentId, "Papa"))
        assertTrue(steps.aborted())
        assertTrue(PairAbort in steps.sent())
    }

    @Test
    fun parentAbortsOnRevealBeforeTheBabysCommitment() {
        val p = parent()
        p.start()
        assertTrue(p.onMessage(PairReveal(b64(babyNonce), babyId, "Baby")).aborted())
    }

    @Test
    fun nonceThatDoesNotMatchTheCommitmentAborts() {
        val b = baby()
        b.onMessage(PairCommit(b64(Sas.commitment(parentNonce))))
        assertTrue(b.onMessage(PairReveal(b64(ByteArray(32) { 9 }), parentId, "Papa")).aborted())
    }

    @Test
    fun deviceIdThatDoesNotMatchTheCertificateAborts() {
        val b = baby()
        b.onMessage(PairCommit(b64(Sas.commitment(parentNonce))))
        assertTrue(b.onMessage(PairReveal(b64(parentNonce), "ff".repeat(32), "Papa")).aborted())
    }

    @Test
    fun confirmationBeforeTheEmojisAborts() {
        val b = baby()
        b.onMessage(PairCommit(b64(Sas.commitment(parentNonce))))
        assertTrue(b.onMessage(PairConfirm).aborted())
    }

    @Test
    fun peerAbortEndsWithoutReply() {
        val p = parent()
        p.start()
        val steps = p.onMessage(PairAbort)
        assertTrue(steps.aborted())
        assertTrue(steps.sent().isEmpty())
    }

    @Test
    fun malformedCommitmentAborts() {
        assertTrue(baby().onMessage(PairCommit("not base64!")).aborted())
        assertTrue(baby().onMessage(PairCommit(b64(ByteArray(5)))).aborted())
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
