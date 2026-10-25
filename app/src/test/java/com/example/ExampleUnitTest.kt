package com.example

import com.example.dsp.GccPhatCalibrator
import com.example.dsp.HermiteFractionalDelayLine
import com.example.dsp.PsychoacousticDspEngine
import com.example.model.AudioMode
import com.example.model.EqualiserPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ExampleUnitTest {

    @Test
    fun testPsychoacousticDspEngine_6StageProcessingAndBrickwallLimiter() {
        val engine = PsychoacousticDspEngine(48000.0)
        engine.masterEnabled = true
        engine.activeMode = AudioMode.MUSIC
        engine.activePreset = EqualiserPreset.BRILLIANT_TREBLE
        engine.surroundVirtualiserEnabled = true
        engine.volumeLevellerEnabled = true
        engine.setBandGains(EqualiserPreset.BRILLIANT_TREBLE.targetBandGainsDb)

        val numSamples = 512
        val left = FloatArray(numSamples)
        val right = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i / 48000.0
            left[i] = (0.65 * sin(2.0 * PI * 60.0 * t) + 0.35 * sin(2.0 * PI * 4000.0 * t)).toFloat()
            right[i] = (0.65 * sin(2.0 * PI * 60.0 * t) - 0.35 * sin(2.0 * PI * 4000.0 * t)).toFloat()
        }

        engine.processAudioBlock(left, right, numSamples)

        // Verify output is finite, non-zero, and strictly bounded by -0.1 dBFS ceiling (0.9886)
        var maxSample = 0f
        for (i in 0 until numSamples) {
            assertTrue(left[i].isFinite())
            assertTrue(right[i].isFinite())
            val peak = maxOf(abs(left[i]), abs(right[i]))
            if (peak > maxSample) maxSample = peak
        }
        assertTrue("Expected processed audio output", maxSample > 0.01f)
        assertTrue("Expected brickwall limiter ceiling <= 0.99", maxSample <= 0.99f)
    }

    @Test
    fun testGccPhatCalibrator_MeasuresSubMillisecondAcousticDelay() {
        val chirp = GccPhatCalibrator.generateLogChirp()
        val measured40ms = GccPhatCalibrator.simulateRoomLoopbackAndMeasure(chirp, 40.0f)
        val measured20ms = GccPhatCalibrator.simulateRoomLoopbackAndMeasure(chirp, 20.0f)
        val measured12ms = GccPhatCalibrator.simulateRoomLoopbackAndMeasure(chirp, 12.0f)

        assertEquals(40.0f, measured40ms, 0.2f)
        assertEquals(20.0f, measured20ms, 0.2f)
        assertEquals(12.0f, measured12ms, 0.2f)
    }

    @Test
    fun testHermiteFractionalDelayLine_SlewsAndInterpolatesSmoothly() {
        val delayLine = HermiteFractionalDelayLine()
        delayLine.setTargetDelayMs(20.0, clockDriftPpm = 10.0)

        val inL = FloatArray(256) { idx -> sin(2.0 * PI * idx / 32.0).toFloat() }
        val inR = FloatArray(256) { idx -> sin(2.0 * PI * idx / 32.0).toFloat() }
        val outL = FloatArray(256)
        val outR = FloatArray(256)

        delayLine.processBlock(inL, inR, outL, outR, 256)
        assertTrue(outL.all { it.isFinite() })
        assertTrue(outR.all { it.isFinite() })
    }
}
