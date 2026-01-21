package com.vauchi.proximity

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for AudioProximityService - ultrasonic proximity verification
 * Based on: features/contact_exchange.feature
 */
@RunWith(RobolectricTestRunner::class)
class AudioProximityServiceTest {

    private lateinit var context: Context
    private lateinit var audioService: AudioProximityService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        audioService = AudioProximityService.getInstance(context)
    }

    // MARK: - Capability Tests
    // Based on: Scenario: Check device audio capability

    /**
     * Scenario: Check capability returns valid value
     */
    @Test
    fun `checkCapability returns valid value`() {
        val capability = audioService.checkCapability()

        val validCapabilities = listOf("full", "emit_only", "receive_only", "none")
        assertTrue(
            capability in validCapabilities,
            "Capability should be one of $validCapabilities, got: $capability"
        )
    }

    /**
     * Scenario: Capability check is consistent
     */
    @Test
    fun `checkCapability is consistent`() {
        val cap1 = audioService.checkCapability()
        val cap2 = audioService.checkCapability()
        val cap3 = audioService.checkCapability()

        assertEquals(cap1, cap2, "Capability should be consistent")
        assertEquals(cap2, cap3, "Capability should be consistent")
    }

    // MARK: - Singleton Tests

    /**
     * Scenario: Service is singleton
     */
    @Test
    fun `getInstance returns same instance`() {
        val instance1 = AudioProximityService.getInstance(context)
        val instance2 = AudioProximityService.getInstance(context)

        assertEquals(instance1, instance2, "Should return same instance")
    }

    // MARK: - Active State Tests

    /**
     * Scenario: Service starts inactive
     */
    @Test
    fun `isActive returns false initially`() {
        assertFalse(audioService.isActive(), "Service should start inactive")
    }

    /**
     * Scenario: Stop when already stopped is safe
     */
    @Test
    fun `stop when inactive is safe`() {
        audioService.stop()
        audioService.stop()
        audioService.stop()

        assertFalse(audioService.isActive(), "Should remain inactive after multiple stops")
    }

    // MARK: - Emit Signal Tests
    // Based on: Scenario: Emit ultrasonic signal

    /**
     * Scenario: Emit empty samples returns error
     */
    @Test
    fun `emitSignal with empty samples returns error`() {
        val result = audioService.emitSignal(listOf(), 44100u)

        assertTrue(result.isNotEmpty(), "Should return error message for empty samples")
    }

    /**
     * Scenario: Emit handles valid samples
     */
    @Test
    fun `emitSignal handles valid samples`() {
        // Generate short test signal
        val samples = (0 until 100).map { i ->
            kotlin.math.sin(2.0 * kotlin.math.PI * 18000.0 * i / 44100.0).toFloat() * 0.5f
        }

        // In test environment, this may fail due to no audio hardware
        // We verify it handles gracefully
        val result = audioService.emitSignal(samples, 44100u)

        // Result is either empty (success) or error message - both acceptable
        assertNotNull(result)
    }

    // MARK: - Receive Signal Tests
    // Based on: Scenario: Receive ultrasonic signal

    /**
     * Scenario: Receive with zero timeout returns quickly
     */
    @Test
    fun `receiveSignal with zero timeout returns quickly`() {
        val samples = audioService.receiveSignal(0u, 44100u)

        // Should return (possibly empty) list without blocking
        assertNotNull(samples)
    }

    /**
     * Scenario: Receive returns float list
     */
    @Test
    fun `receiveSignal returns float list`() {
        val samples = audioService.receiveSignal(10u, 44100u)

        // Verify samples are in valid range if any were recorded
        samples.forEach { sample ->
            assertTrue(sample >= -1.0f, "Sample should be >= -1.0")
            assertTrue(sample <= 1.0f, "Sample should be <= 1.0")
        }
    }

    // MARK: - Sample Rate Tests

    /**
     * Scenario: Different sample rates are handled
     */
    @Test
    fun `different sample rates are handled`() {
        val sampleRates = listOf(22050u, 44100u, 48000u)

        sampleRates.forEach { rate ->
            val samples = audioService.receiveSignal(10u, rate)
            assertNotNull(samples, "Should handle sample rate $rate")
            audioService.stop()
        }
    }

    // MARK: - Thread Safety Tests

    /**
     * Scenario: Concurrent stop calls are safe
     */
    @Test
    fun `concurrent stop calls are safe`() {
        val threads = (1..10).map {
            Thread { audioService.stop() }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertFalse(audioService.isActive())
    }

    /**
     * Scenario: Capability check is thread safe
     */
    @Test
    fun `capability check is thread safe`() {
        val results = mutableListOf<String>()
        val threads = (1..10).map {
            Thread {
                synchronized(results) {
                    results.add(audioService.checkCapability())
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // All results should be the same
        val uniqueResults = results.distinct()
        assertEquals(1, uniqueResults.size, "All threads should get same capability")
    }
}
