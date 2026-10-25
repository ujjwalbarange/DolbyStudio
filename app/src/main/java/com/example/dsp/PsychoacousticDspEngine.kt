package com.example.dsp

import com.example.model.AudioMode
import com.example.model.DolbyConstants
import com.example.model.EqualiserPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Cytomic Zero-Delay Feedback (ZDF) State Variable Filter (SVF) Bell/Peaking EQ stage.
 */
class CytomicSvfBand(
    val centerFreqHz: Double,
    private val sampleRate: Double = DolbyConstants.SAMPLE_RATE
) {
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0
    private var m1 = 0.0

    private var ic1L = 0.0
    private var ic2L = 0.0
    private var ic1R = 0.0
    private var ic2R = 0.0

    var currentGainDb: Double = 0.0
        private set

    init {
        updateCoefficients(0.0)
    }

    fun updateCoefficients(gainDb: Double, qFactor: Double = 1.41421356) {
        currentGainDb = gainDb.coerceIn(-12.0, 12.0)
        val A = 10.0.pow(currentGainDb / 40.0)
        val g = tan(PI * centerFreqHz / sampleRate)
        val k = 1.0 / (qFactor * A)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        m1 = k * (A * A - 1.0)
    }

    fun processLeft(x: Double): Double {
        val v3 = x - ic2L
        val v1 = a1 * ic1L + a2 * v3
        val v2 = ic2L + a2 * ic1L + a3 * v3
        ic1L = 2.0 * v1 - ic1L
        ic2L = 2.0 * v2 - ic2L
        return x + m1 * v1
    }

    fun processRight(x: Double): Double {
        val v3 = x - ic2R
        val v1 = a1 * ic1R + a2 * v3
        val v2 = ic2R + a2 * ic1R + a3 * v3
        ic1R = 2.0 * v1 - ic1R
        ic2R = 2.0 * v2 - ic2R
        return x + m1 * v1
    }

    fun reset() {
        ic1L = 0.0; ic2L = 0.0
        ic1R = 0.0; ic2R = 0.0
    }
}

/**
 * Robert Bristow-Johnson (RBJ) Audio EQ Cookbook Biquad IIR Filter (Direct Form II Transposed).
 */
class RbjBiquadFilter(private val sampleRate: Double = DolbyConstants.SAMPLE_RATE) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var z1L = 0.0
    private var z2L = 0.0
    private var z1R = 0.0
    private var z2R = 0.0

    fun setHighPass(freqHz: Double, q: Double = 0.70710678) {
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosW0) * 0.5) / a0
        b1 = -(1.0 + cosW0) / a0
        b2 = ((1.0 + cosW0) * 0.5) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setLowPass(freqHz: Double, q: Double = 0.70710678) {
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosW0) * 0.5) / a0
        b1 = (1.0 - cosW0) / a0
        b2 = ((1.0 - cosW0) * 0.5) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setBandPass(freqHz: Double, q: Double = 1.0) {
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b1 = 0.0
        b2 = -alpha / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setPeakingEq(freqHz: Double, q: Double, gainDb: Double) {
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / A
        b0 = (1.0 + alpha * A) / a0
        b1 = (-2.0 * cosW0) / a0
        b2 = (1.0 - alpha * A) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha / A) / a0
    }

    fun setLowShelf(freqHz: Double, q: Double, gainDb: Double) {
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha
        val a0 = (A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha
        b0 = (A * ((A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
        b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)) / a0
        b2 = (A * ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
        a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosW0)) / a0
        a2 = ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha) / a0
    }

    fun setHighShelf(freqHz: Double, q: Double, gainDb: Double) {
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha
        val a0 = (A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha
        b0 = (A * ((A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
        b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)) / a0
        b2 = (A * ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
        a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosW0)) / a0
        a2 = ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha) / a0
    }

    fun setAllPass(freqHz: Double, q: Double = 0.70710678) {
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (1.0 - alpha) / a0
        b1 = (-2.0 * cosW0) / a0
        b2 = 1.0
        a1 = b1
        a2 = b0
    }

    fun processLeft(x: Double): Double {
        val y = b0 * x + z1L
        z1L = b1 * x - a1 * y + z2L
        z2L = b2 * x - a2 * y
        return y
    }

    fun processRight(x: Double): Double {
        val y = b0 * x + z1R
        z1R = b1 * x - a1 * y + z2R
        z2R = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1L = 0.0; z2L = 0.0
        z1R = 0.0; z2R = 0.0
    }
}

/**
 * Radix-2 In-Place Cooley-Tukey Fast Fourier Transform (FFT) for spectral analysis & GCC-PHAT.
 */
object FastFourierTransform {
    fun fft(real: DoubleArray, imag: DoubleArray, invert: Boolean) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * if (invert) 1.0 else -1.0
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until (len / 2)) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * wRe - imag[i + k + len / 2] * wIm
                    val vIm = real[i + k + len / 2] * wIm + imag[i + k + len / 2] * wRe
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nextWRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
        if (invert) {
            for (i in 0 until n) {
                real[i] /= n.toDouble()
                imag[i] /= n.toDouble()
            }
        }
    }
}

/**
 * Complete 6-Stage Psychoacoustic Audio Processing Engine at 48 kHz.
 * Stage 1: Input Headroom Pre-Gain (-6.0 dB) + DC Blocker (20 Hz High-Pass).
 * Stage 2: Macro-to-Micro Equalizer (10-Band Cytomic SVF) + Pultec Low-End Phase Shaper +
 *          2nd/3rd Order Chebyshev Sub-Bass Harmonic Exciter + FFT-based Dynamic Intelligent EQ (IEQ).
 * Stage 3: Dialog Enhancer (1.5 kHz - 3.5 kHz VAD-gated boost) + Dynamic De-Esser (6.5 kHz).
 * Stage 4: Surround Virtualiser (Mid/Side + Binaural HRTF ITD/ILD/Pinna/Haas OR Loudspeaker Transaural XTC).
 * Stage 5: Volume Leveller (ITU-R BS.1770-4 K-Weighted LUFS + -50 dBFS Silence-Gated AGC + Fletcher-Munson).
 * Stage 6: True-Peak Lookahead Brickwall Limiter (-0.1 dBFS ceiling).
 */
class PsychoacousticDspEngine(
    private val sampleRate: Double = DolbyConstants.SAMPLE_RATE
) {
    @Volatile var masterEnabled: Boolean = true
    @Volatile var activeMode: AudioMode = AudioMode.MUSIC
    @Volatile var activePreset: EqualiserPreset = EqualiserPreset.BRILLIANT_TREBLE
    @Volatile var surroundVirtualiserEnabled: Boolean = true
    @Volatile var volumeLevellerEnabled: Boolean = true
    @Volatile var isHeadphoneRoute: Boolean = false
    @Volatile var macroBassTiltDb: Float = 0f
    @Volatile var macroTrebleTiltDb: Float = 0f

    private val userBandGainsDb = DoubleArray(10) {
        EqualiserPreset.BRILLIANT_TREBLE.targetBandGainsDb[it].toDouble()
    }
    private val ieqOffsetsDb = DoubleArray(10) { 0.0 }
    private val effectiveBandGainsDb = DoubleArray(10) { 0.0 }
    private val measuredBandEnergiesDb = DoubleArray(10) { -24.0 }

    // Stage 1: DC Blocker (20 Hz HPF) & Loudspeaker 85 Hz Excursion Protection HPF
    private val dcBlockerFilter = RbjBiquadFilter(sampleRate).apply { setHighPass(20.0, 0.7071) }
    private val speakerExcursionHpf = RbjBiquadFilter(sampleRate).apply { setHighPass(85.0, 0.7071) }

    // Stage 2: 10-Band Cytomic ZDF SVF Filter Bank
    private val svfBands = Array(10) { idx ->
        CytomicSvfBand(DolbyConstants.ISO_10_BAND_FREQUENCIES[idx].toDouble(), sampleRate)
    }

    // Stage 2B: Pultec EQP-1A Style Resonant Low-Shelf Pair (60 Hz boost + 105 Hz attenuation dip)
    private val pultecLowShelfBoost = RbjBiquadFilter(sampleRate).apply { setLowShelf(60.0, 0.65, 0.0) }
    private val pultecMidBassDip = RbjBiquadFilter(sampleRate).apply { setPeakingEq(105.0, 1.8, 0.0) }

    // Stage 2C: Sub-Bass Chebyshev Harmonic Exciter (25-85 Hz extract -> T2/T3 Chebyshev -> 90-210 Hz BP)
    private val subBassExtractLp = RbjBiquadFilter(sampleRate).apply { setLowPass(85.0, 0.7071) }
    private val subBassExtractHp = RbjBiquadFilter(sampleRate).apply { setHighPass(25.0, 0.7071) }
    private val harmonicBandpass = RbjBiquadFilter(sampleRate).apply { setBandPass(140.0, 1.15) }

    // Stage 3: Dialog Enhancer (2.4 kHz presence bell covering 1.5-3.5 kHz) + 6.5 kHz Sibilance De-Esser
    private val dialogPresenceFilter = RbjBiquadFilter(sampleRate).apply { setPeakingEq(2400.0, 0.85, 0.0) }
    private val sibilanceSidechainBp = RbjBiquadFilter(sampleRate).apply { setBandPass(6500.0, 2.2) }
    private val deEsserCutFilter = RbjBiquadFilter(sampleRate).apply { setPeakingEq(6500.0, 2.0, 0.0) }
    private var sibilanceEnv = 0.0

    // Stage 4: Surround Virtualiser (HRTF Binaural vs Loudspeaker Transaural XTC)
    private val haasDelaySamples = (0.012 * sampleRate).toInt()
    private val haasRingBuffer = DoubleArray(1024)
    private var haasWriteIdx = 0
    private val haasAllPass = RbjBiquadFilter(sampleRate).apply { setAllPass(950.0, 0.7071) }
    private val pinnaNotchFilter = RbjBiquadFilter(sampleRate).apply { setPeakingEq(7500.0, 3.2, -4.0) }
    private val earCanalPresenceFilter = RbjBiquadFilter(sampleRate).apply { setPeakingEq(2800.0, 1.4, 2.5) }

    private val itdRingL = DoubleArray(64)
    private val itdRingR = DoubleArray(64)
    private var itdWriteIdx = 0

    private val xtcDelaySamples = 11
    private val xtcRingL = DoubleArray(32)
    private val xtcRingR = DoubleArray(32)
    private var xtcWriteIdx = 0
    private val xtcCrossLowPass = RbjBiquadFilter(sampleRate).apply { setLowPass(4800.0, 0.7071) }

    // Stage 5: Volume Leveller (ITU-R BS.1770-4 K-Weighting + Silence-Gated AGC + Fletcher-Munson)
    private val kWeightStage1Shelf = RbjBiquadFilter(sampleRate).apply {
        setHighShelf(1681.974450955533, 0.7071752369554196, 3.999843853973347)
    }
    private val kWeightStage2RlbHp = RbjBiquadFilter(sampleRate).apply {
        setHighPass(38.13547087602444, 0.5003270373238773)
    }
    private val fletcherMunsonLowShelf = RbjBiquadFilter(sampleRate).apply { setLowShelf(65.0, 0.7071, 0.0) }
    private val fletcherMunsonHighShelf = RbjBiquadFilter(sampleRate).apply { setHighShelf(11500.0, 0.7071, 0.0) }

    private var shortTermMeanSquareSum = 1e-5
    private var currentInputLufs = -24.0
    private var currentAgcGainLinear = 1.0
    private val agcAttackCoeff = exp(-1.0 / (0.020 * sampleRate))
    private val agcReleaseCoeff = exp(-1.0 / (0.600 * sampleRate))

    // Stage 6: True-Peak Lookahead Brickwall Limiter (-0.1 dBFS ceiling)
    private val limiterCeiling = 10.0.pow(-0.1 / 20.0)
    private val lookaheadSamples = 72
    private val lookaheadBufL = DoubleArray(128)
    private val lookaheadBufR = DoubleArray(128)
    private var lookaheadIdx = 0
    private var limiterEnvGain = 1.0
    private val limiterReleaseCoeff = exp(-1.0 / (0.080 * sampleRate))

    // Sidechain FFT & VAD Classifier state
    private val fftSize = 256
    private val fftRe = DoubleArray(fftSize)
    private val fftIm = DoubleArray(fftSize)
    private var vadSpeechProb = 0.20
    private var spectralCentroidHz = 2200.0
    private var zeroCrossingRate = 0.07

    fun setBandGains(gainsDb: FloatArray) {
        for (i in 0 until min(10, gainsDb.size)) {
            userBandGainsDb[i] = gainsDb[i].toDouble().coerceIn(-10.0, 10.0)
        }
    }

    fun getEffectiveBandGainsDb(): List<Float> = effectiveBandGainsDb.map { it.toFloat() }

    /**
     * Executes the complete 6-stage psychoacoustic DSP pipeline in-place on stereo PCM float blocks.
     */
    fun processAudioBlock(left: FloatArray, right: FloatArray, numSamples: Int) {
        if (!masterEnabled) return

        analyzeSidechainBlock(left, right, numSamples)
        updateDynamicFilterChain()

        var maxPositiveGainDb = 0.0
        for (b in 0 until 10) {
            if (effectiveBandGainsDb[b] > maxPositiveGainDb) {
                maxPositiveGainDb = effectiveBandGainsDb[b]
            }
        }
        val preGainDb = -6.0 - (maxPositiveGainDb * 0.25)
        val preGainLinear = 10.0.pow(preGainDb / 20.0)

        val bassDrive = when {
            activePreset == EqualiserPreset.BASS_BOOST -> 0.24
            activeMode == AudioMode.MUSIC || activeMode == AudioMode.FILM || activeMode == AudioMode.GAME -> 0.14
            else -> 0.05
        } * (1.0 + max(0.0, effectiveBandGainsDb[0] + effectiveBandGainsDb[1]) / 16.0)

        val alpha2 = 0.55 * bassDrive
        val alpha3 = 0.35 * bassDrive

        for (i in 0 until numSamples) {
            // Stage 1: Input Headroom Pre-Gain (-6.0 dB) + 20 Hz High-Pass DC Blocker
            var sL = dcBlockerFilter.processLeft(left[i].toDouble() * preGainLinear)
            var sR = dcBlockerFilter.processRight(right[i].toDouble() * preGainLinear)

            if (!isHeadphoneRoute) {
                sL = speakerExcursionHpf.processLeft(sL)
                sR = speakerExcursionHpf.processRight(sR)
            }

            // Stage 2: 10-Band Cytomic SVF + Pultec Low-End Shaper + Chebyshev Sub-Bass Exciter
            val subMono = 0.5 * (sL + sR)
            val subExtracted = subBassExtractHp.processLeft(subBassExtractLp.processLeft(subMono))
            val xSub = (subExtracted * 2.2).coerceIn(-1.0, 1.0)
            val chebyshev2 = 2.0 * xSub * xSub - (2.0 * xSub * xSub * 0.35)
            val chebyshev3 = 4.0 * xSub * xSub * xSub - 3.0 * xSub
            val virtualBassHarmonic = harmonicBandpass.processLeft(alpha2 * chebyshev2 + alpha3 * chebyshev3)

            if (activePreset != EqualiserPreset.MANUAL) {
                sL = pultecMidBassDip.processLeft(pultecLowShelfBoost.processLeft(sL))
                sR = pultecMidBassDip.processRight(pultecLowShelfBoost.processRight(sR))
            }

            for (b in 0 until 10) {
                sL = svfBands[b].processLeft(sL)
                sR = svfBands[b].processRight(sR)
            }

            sL += virtualBassHarmonic
            sR += virtualBassHarmonic

            // Stage 3: Dialog Enhancer (1.5 - 3.5 kHz VAD-Gated) + 6.5 kHz De-Esser
            val sibL = sibilanceSidechainBp.processLeft(sL)
            val sibR = sibilanceSidechainBp.processRight(sR)
            sibilanceEnv = 0.992 * sibilanceEnv + 0.008 * max(abs(sibL), abs(sibR))

            sL = dialogPresenceFilter.processLeft(sL)
            sR = dialogPresenceFilter.processRight(sR)
            if (sibilanceEnv > 0.08) {
                sL = deEsserCutFilter.processLeft(sL)
                sR = deEsserCutFilter.processRight(sR)
            }

            // Stage 4: Surround Virtualiser (Headphone HRTF vs Loudspeaker Transaural XTC)
            if (surroundVirtualiserEnabled || activeMode == AudioMode.SPATIAL_AUDIO) {
                val invSqrt2 = 0.70710678118
                val mid = (sL + sR) * invSqrt2
                val side = (sL - sR) * invSqrt2

                if (isHeadphoneRoute) {
                    haasRingBuffer[haasWriteIdx] = side
                    val readIdx = (haasWriteIdx - haasDelaySamples + haasRingBuffer.size) % haasRingBuffer.size
                    val delayedSide = haasAllPass.processLeft(haasRingBuffer[readIdx])
                    haasWriteIdx = (haasWriteIdx + 1) % haasRingBuffer.size

                    val wideSide = side * 1.28 + delayedSide * 0.42
                    val binL = earCanalPresenceFilter.processLeft(pinnaNotchFilter.processLeft((mid + wideSide) * invSqrt2))
                    val binR = earCanalPresenceFilter.processRight(pinnaNotchFilter.processRight((mid - wideSide) * invSqrt2))

                    itdRingL[itdWriteIdx] = binL
                    itdRingR[itdWriteIdx] = binR
                    val itdRead = (itdWriteIdx - 14 + itdRingL.size) % itdRingL.size
                    sL = binL + 0.16 * itdRingR[itdRead]
                    sR = binR + 0.16 * itdRingL[itdRead]
                    itdWriteIdx = (itdWriteIdx + 1) % itdRingL.size
                } else {
                    val wideL = (mid + side * 1.35) * invSqrt2
                    val wideR = (mid - side * 1.35) * invSqrt2
                    xtcRingL[xtcWriteIdx] = wideL
                    xtcRingR[xtcWriteIdx] = wideR
                    val xtcRead = (xtcWriteIdx - xtcDelaySamples + xtcRingL.size) % xtcRingL.size
                    val crossL = xtcCrossLowPass.processLeft(xtcRingR[xtcRead])
                    val crossR = xtcCrossLowPass.processRight(xtcRingL[xtcRead])
                    val gamma = if (activeMode == AudioMode.SPATIAL_AUDIO) 0.40 else 0.35
                    sL = (wideL - gamma * crossL) * 1.12
                    sR = (wideR - gamma * crossR) * 1.12
                    xtcWriteIdx = (xtcWriteIdx + 1) % xtcRingL.size
                }
            }

            // Stage 5: Volume Leveller (ITU-R BS.1770 K-Weighted LUFS + Fletcher-Munson)
            val kwL = kWeightStage2RlbHp.processLeft(kWeightStage1Shelf.processLeft(sL))
            val kwR = kWeightStage2RlbHp.processRight(kWeightStage1Shelf.processRight(sR))
            val instPower = 0.5 * (kwL * kwL + kwR * kwR)
            shortTermMeanSquareSum = 0.9995 * shortTermMeanSquareSum + 0.0005 * max(instPower, 1e-10)
            currentInputLufs = -0.691 + 10.0 * log10(shortTermMeanSquareSum)

            if (volumeLevellerEnabled) {
                val targetGainLinear = if (currentInputLufs > -50.0) {
                    val deltaDb = (-16.0 - currentInputLufs).coerceIn(-9.0, 9.0)
                    10.0.pow(deltaDb / 20.0)
                } else {
                    currentAgcGainLinear
                }
                val coeff = if (targetGainLinear < currentAgcGainLinear) agcAttackCoeff else agcReleaseCoeff
                currentAgcGainLinear = coeff * currentAgcGainLinear + (1.0 - coeff) * targetGainLinear

                sL = fletcherMunsonHighShelf.processLeft(fletcherMunsonLowShelf.processLeft(sL * currentAgcGainLinear))
                sR = fletcherMunsonHighShelf.processRight(fletcherMunsonLowShelf.processRight(sR * currentAgcGainLinear))
            } else {
                currentAgcGainLinear = 1.0
            }

            // Stage 6: True-Peak Lookahead Brickwall Limiter (-0.1 dBFS Ceiling)
            lookaheadBufL[lookaheadIdx] = sL
            lookaheadBufR[lookaheadIdx] = sR
            val peakAbs = max(abs(sL), abs(sR))
            val requiredGain = if (peakAbs > limiterCeiling) limiterCeiling / peakAbs else 1.0
            limiterEnvGain = if (requiredGain < limiterEnvGain) {
                requiredGain
            } else {
                limiterReleaseCoeff * limiterEnvGain + (1.0 - limiterReleaseCoeff) * requiredGain
            }

            val outReadIdx = (lookaheadIdx - lookaheadSamples + lookaheadBufL.size) % lookaheadBufL.size
            val limitedL = (lookaheadBufL[outReadIdx] * limiterEnvGain).coerceIn(-limiterCeiling, limiterCeiling)
            val limitedR = (lookaheadBufR[outReadIdx] * limiterEnvGain).coerceIn(-limiterCeiling, limiterCeiling)
            lookaheadIdx = (lookaheadIdx + 1) % lookaheadBufL.size

            left[i] = limitedL.toFloat()
            right[i] = limitedR.toFloat()
        }
    }

    private fun analyzeSidechainBlock(left: FloatArray, right: FloatArray, numSamples: Int) {
        val n = min(numSamples, fftSize)
        var zeroCrossings = 0
        var prevSample = 0.0
        for (i in 0 until fftSize) {
            if (i < n) {
                val mono = 0.5 * (left[i] + right[i])
                val w = 0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
                fftRe[i] = mono * w
                fftIm[i] = 0.0
                if (i > 0 && ((mono >= 0.0 && prevSample < 0.0) || (mono < 0.0 && prevSample >= 0.0))) {
                    zeroCrossings++
                }
                prevSample = mono
            } else {
                fftRe[i] = 0.0
                fftIm[i] = 0.0
            }
        }
        zeroCrossingRate = zeroCrossings.toDouble() / max(1, n).toDouble()

        FastFourierTransform.fft(fftRe, fftIm, invert = false)

        val binHz = sampleRate / fftSize.toDouble()
        var weightedFreqSum = 0.0
        var magSum = 1e-9
        val bandEnergyAccum = DoubleArray(10) { 1e-9 }
        val bandBinCounts = IntArray(10) { 0 }

        for (bin in 1 until (fftSize / 2)) {
            val freq = bin * binHz
            val magSq = fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]
            val mag = sqrt(magSq)
            weightedFreqSum += freq * mag
            magSum += mag

            var closestBand = 0
            var minRatio = Double.MAX_VALUE
            for (b in 0 until 10) {
                val fc = DolbyConstants.ISO_10_BAND_FREQUENCIES[b].toDouble()
                val ratio = abs(ln(max(freq, 20.0) / fc))
                if (ratio < minRatio) {
                    minRatio = ratio
                    closestBand = b
                }
            }
            bandEnergyAccum[closestBand] += magSq
            bandBinCounts[closestBand]++
        }

        spectralCentroidHz = (weightedFreqSum / magSum).coerceIn(120.0, 12000.0)

        var midSpeechPower = 1e-9
        var totalPower = 1e-9
        for (b in 0 until 10) {
            val avgPower = bandEnergyAccum[b] / max(1, bandBinCounts[b])
            val db = (10.0 * log10(avgPower) - 6.0).coerceIn(-45.0, 6.0)
            measuredBandEnergiesDb[b] = 0.82 * measuredBandEnergiesDb[b] + 0.18 * db
            totalPower += avgPower
            if (b in 3..6) {
                midSpeechPower += avgPower
            }
        }

        val speechRatio = (midSpeechPower / totalPower).coerceIn(0.0, 1.0)
        val zcrFactor = if (zeroCrossingRate in 0.02..0.15) 1.0 else 0.5
        vadSpeechProb = (0.85 * vadSpeechProb + 0.15 * (speechRatio * zcrFactor * 1.35)).coerceIn(0.05, 0.98)
    }

    private fun updateDynamicFilterChain() {
        val avgEnergyDb = measuredBandEnergiesDb.average()

        for (b in 0 until 10) {
            val normPos = (b.toDouble() / 9.0) * 2.0 - 1.0
            val tiltOffset = if (normPos < 0.0) {
                -normPos * macroBassTiltDb.toDouble()
            } else {
                normPos * macroTrebleTiltDb.toDouble()
            }

            val baseTargetDb = when (activeMode) {
                AudioMode.SMART_AUDIO -> {
                    val musicCurve = EqualiserPreset.BRILLIANT_TREBLE.targetBandGainsDb[b] * 0.6 +
                        EqualiserPreset.BASS_BOOST.targetBandGainsDb[b] * 0.5
                    val voiceCurve = EqualiserPreset.VOCAL_BOOST.targetBandGainsDb[b].toDouble()
                    vadSpeechProb * voiceCurve + (1.0 - vadSpeechProb) * musicCurve
                }
                AudioMode.SPATIAL_AUDIO -> {
                    val spatialTarget = doubleArrayOf(4.5, 3.8, 1.5, 0.0, -0.5, 1.8, 3.5, 4.8, 5.5, 6.5)
                    spatialTarget[b]
                }
                else -> userBandGainsDb[b] + tiltOffset
            }

            val relativeBandEnergy = measuredBandEnergiesDb[b] - avgEnergyDb
            val ieqDelta = if (activePreset == EqualiserPreset.MANUAL && activeMode == AudioMode.CUSTOM) {
                0.0
            } else {
                0.35 * (baseTargetDb - relativeBandEnergy).coerceIn(-5.0, 5.0)
            }
            ieqOffsetsDb[b] = 0.85 * ieqOffsetsDb[b] + 0.15 * ieqDelta
            effectiveBandGainsDb[b] = (baseTargetDb + ieqOffsetsDb[b]).coerceIn(-10.0, 10.0)
            svfBands[b].updateCoefficients(effectiveBandGainsDb[b])
        }

        val bassTarget = max(0.0, effectiveBandGainsDb[0] * 0.45)
        pultecLowShelfBoost.setLowShelf(60.0, 0.68, bassTarget)
        pultecMidBassDip.setPeakingEq(105.0, 1.9, -bassTarget * 0.42)

        val dialogModeMultiplier = when (activeMode) {
            AudioMode.PODCAST -> 5.5
            AudioMode.FILM, AudioMode.GAME -> 4.0
            AudioMode.SMART_AUDIO -> 3.5 * vadSpeechProb
            else -> 1.2 * vadSpeechProb
        }
        dialogPresenceFilter.setPeakingEq(2400.0, 0.85, dialogModeMultiplier * vadSpeechProb)

        val deEssCutDb = if (sibilanceEnv > 0.08) {
            -((sibilanceEnv - 0.08) * 35.0).coerceIn(0.0, 6.0)
        } else {
            0.0
        }
        deEsserCutFilter.setPeakingEq(6500.0, 2.0, deEssCutDb)

        if (volumeLevellerEnabled && currentInputLufs < -22.0) {
            val loudnessDeficit = ((-22.0 - currentInputLufs) * 0.25).coerceIn(0.0, 4.5)
            fletcherMunsonLowShelf.setLowShelf(65.0, 0.7071, loudnessDeficit)
            fletcherMunsonHighShelf.setHighShelf(11500.0, 0.7071, loudnessDeficit * 0.65)
        } else {
            fletcherMunsonLowShelf.setLowShelf(65.0, 0.7071, 0.0)
            fletcherMunsonHighShelf.setHighShelf(11500.0, 0.7071, 0.0)
        }
    }
}
