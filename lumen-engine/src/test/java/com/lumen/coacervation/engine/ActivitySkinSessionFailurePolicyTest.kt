package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.runtime.LiquidFailureNotificationPolicy
import com.lumen.coacervation.engine.runtime.SkinPreferenceState
import com.lumen.coacervation.engine.runtime.SkinProcessStateHolder
import com.lumen.coacervation.engine.runtime.SkinRecoveryGuard
import com.lumen.coacervation.engine.runtime.SkinRecoveryReason
import com.lumen.coacervation.engine.runtime.SkinRenderSessionRegistry
import com.lumen.coacervation.engine.runtime.SkinStateMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivitySkinSessionFailurePolicyTest {
    private val pending = SkinPreferenceState.pendingLiquid(9, "attempt-current")
    private val confirmed = SkinPreferenceState.confirmedLiquid(9, "attempt-current")

    @Test
    fun `initialization failure cannot notify when pending or confirmed fallback is not saved`() {
        for (initial in listOf(pending, confirmed)) {
            val holder = SkinProcessStateHolder()
            val registry = SkinRenderSessionRegistry()
            val owner = requireNotNull(registry.claim(holder.current { initial }, "owner-failed"))
            val result = fallback(holder, initial, persisted = false)
            registry.release(owner)

            assertFalse(LiquidFailureNotificationPolicy.canNotify(result))
            assertFalse(result.persisted)
            assertEquals(initial, result.state)
            assertEquals(initial, holder.current { error("must not reload after bindRoot") })
            assertFalse(registry.isActiveFor(owner, initial))
        }
    }

    @Test
    fun `failed pending health confirmation followed by failed fallback cannot notify`() {
        val holder = SkinProcessStateHolder()
        val registry = SkinRenderSessionRegistry()
        val owner = requireNotNull(registry.claim(holder.current { pending }, "owner-failed"))
        val otherOwner = requireNotNull(registry.claim(pending, "owner-other-activity"))
        var writes = 0
        val healthy = holder.mutate(
            loader = { pending },
            decide = { SkinRecoveryGuard.confirmLiquidHealthy(it, 9, "attempt-current") },
            persist = { writes++; false }
        )
        val failed = holder.mutate(
            loader = { error("must retain the loaded pending state") },
            decide = { SkinRecoveryGuard.onLiquidValidationFailed(it, 9, "attempt-current") },
            persist = { writes++; false }
        )
        registry.release(owner)

        assertEquals(2, writes)
        assertFalse(healthy.persisted)
        assertFalse(failed.persisted)
        assertFalse(LiquidFailureNotificationPolicy.canNotify(failed))
        assertEquals(pending, holder.current { error("must not publish the unsaved fallback") })
        assertTrue(registry.isActiveFor(otherOwner, pending))
    }

    @Test
    fun `successful fallback from pending or confirmed liquid permits host notification`() {
        for (initial in listOf(pending, confirmed)) {
            val holder = SkinProcessStateHolder()
            val result = fallback(holder, initial, persisted = true)

            assertTrue(result.persisted)
            assertEquals(SkinRecoveryReason.LIQUID_VALIDATION_FAILED, result.reason)
            assertTrue(LiquidFailureNotificationPolicy.canNotify(result))
            assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, holder.current { error("unused") })
        }
    }

    @Test
    fun `successful pending health confirmation remains a separate activation phase`() {
        val holder = SkinProcessStateHolder()
        val result = holder.mutate(
            loader = { pending },
            decide = { SkinRecoveryGuard.confirmLiquidHealthy(it, 9, "attempt-current") },
            persist = { true }
        )

        assertTrue(result.persisted)
        assertEquals(confirmed, result.state)
        assertFalse(LiquidFailureNotificationPolicy.canNotify(result))
    }

    @Test
    fun `confirmed healthy renderer does not write or request host notification`() {
        val holder = SkinProcessStateHolder()
        val result = holder.mutate(
            loader = { confirmed },
            decide = { SkinRecoveryGuard.confirmLiquidHealthy(it, 9, "attempt-current") },
            persist = { error("already confirmed health must not write") }
        )

        assertTrue(result.persisted)
        assertEquals(SkinRecoveryReason.READY, result.reason)
        assertEquals(confirmed, result.state)
        assertFalse(LiquidFailureNotificationPolicy.canNotify(result))
    }

    @Test
    fun `stale failure cannot notify even when the unchanged state is considered persisted`() {
        for (current in listOf(pending, confirmed, SkinPreferenceState.MATERIAL_DEFAULT)) {
            val holder = SkinProcessStateHolder()
            val result = holder.mutate(
                loader = { current },
                decide = { SkinRecoveryGuard.onLiquidValidationFailed(it, 9, "attempt-old") },
                persist = { error("stale failure must not write") }
            )

            assertTrue(result.persisted)
            assertEquals(SkinRecoveryReason.STALE_VALIDATION_FAILURE_IGNORED, result.reason)
            assertEquals(current, result.state)
            assertFalse(LiquidFailureNotificationPolicy.canNotify(result))
        }
    }

    @Test
    fun `missing fallback and persisted liquid states cannot authorize a notification`() {
        assertFalse(LiquidFailureNotificationPolicy.canNotify(null))
        assertFalse(LiquidFailureNotificationPolicy.canNotify(SkinStateMutationResult(
            state = confirmed,
            persisted = true,
            reason = SkinRecoveryReason.LIQUID_VALIDATION_FAILED
        )))
    }

    @Test
    fun `initialization runtime failure and repeated binds all use the persisted notification gate`() {
        val source = SourceContract.read("runtime/ActivitySkinSession.kt")
        val create = source.after("fun create(").before("private fun Configuration.toSkinSnapshot")
        assertTrue(create.contains("val initialFailureResult = if (initializationFailed && owner != null)"))
        assertTrue(create.contains("SkinRepository.reportLiquidValidationFailure(activity, owner).also"))
        assertTrue(create.contains("initialLiquidFailureResult = initialFailureResult"))
        assertTrue(source.contains("LiquidFailureNotificationPolicy.canNotify(initialLiquidFailureResult)"))

        val failure = source.after("private fun handleRendererFailure()").before("private fun retireStaleSession()")
        assertTrue(failure.contains("failureFallbackPersisted = LiquidFailureNotificationPolicy.canNotify(result)"))
        assertTrue(failure.contains("if (initialLiquidFailure) scheduleFailureNotification()"))
        val binding = source.after("fun bindRoot(").before("fun surfaceBackground(")
        assertTrue(binding.contains("scheduleFailureNotification()"))

        val schedule = source.after("private fun scheduleFailureNotification()").before("override fun close()")
        val gate = "if (!failureFallbackPersisted || failureNotified || failureNotificationPosted) return"
        assertTrue(schedule.contains(gate))
        assertTrue(schedule.indexOf(gate) < schedule.indexOf("root.post"))
        assertTrue(schedule.contains("if (isClosed || failureNotified) return@post"))
    }

    private fun fallback(
        holder: SkinProcessStateHolder,
        initial: SkinPreferenceState,
        persisted: Boolean
    ): SkinStateMutationResult = holder.mutate(
        loader = { initial },
        decide = { SkinRecoveryGuard.onLiquidValidationFailed(it, 9, "attempt-current") },
        persist = { persisted }
    )
}
