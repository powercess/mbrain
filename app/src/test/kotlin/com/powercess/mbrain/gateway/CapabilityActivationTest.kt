package com.powercess.mbrain.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityActivationTest {
    @Test fun `permission must finish before exposing capability and repeated taps are ignored`() = runTest {
        val permission = CompletableDeferred<Unit>()
        val changes = mutableListOf<Boolean>()
        var requests = 0
        val activation = CapabilityActivation(this, { requests++; permission.await() }, changes::add)
        activation.setEnabled(true)
        activation.setEnabled(true)
        runCurrent()
        assertEquals(1, requests)
        assertTrue(changes.isEmpty())
        assertTrue(activation.state.value.checking)
        permission.complete(Unit)
        runCurrent()
        assertEquals(listOf(true), changes)
        assertFalse(activation.state.value.checking)
    }

    @Test fun `denial turns saved preference off and a retry can succeed`() = runTest {
        var denied = true
        val changes = mutableListOf<Boolean>()
        val activation = CapabilityActivation(this, { check(!denied) { "未授权" } }, changes::add)
        activation.setEnabled(true)
        runCurrent()
        assertEquals(listOf(false), changes)
        assertEquals("未授权", activation.state.value.error)
        denied = false
        activation.setEnabled(true)
        runCurrent()
        assertEquals(listOf(false, true), changes)
        assertNull(activation.state.value.error)
    }

    @Test fun `late permission response cannot undo disabling`() = runTest {
        val permission = CompletableDeferred<Unit>()
        val changes = mutableListOf<Boolean>()
        val activation = CapabilityActivation(this, { permission.await() }, changes::add)
        activation.setEnabled(true)
        runCurrent()
        activation.setEnabled(false)
        permission.complete(Unit)
        runCurrent()
        assertEquals(listOf(false), changes)
        assertEquals(ActivationState(), activation.state.value)
    }

    @Test fun `timeout clears busy state and leaves capability disabled`() = runTest {
        val changes = mutableListOf<Boolean>()
        val activation = CapabilityActivation(this, { withTimeout(100) { delay(200) } }, changes::add)
        activation.setEnabled(true)
        advanceUntilIdle()
        assertEquals(listOf(false), changes)
        assertFalse(activation.state.value.checking)
        assertNotNull(activation.state.value.error)
    }
}
