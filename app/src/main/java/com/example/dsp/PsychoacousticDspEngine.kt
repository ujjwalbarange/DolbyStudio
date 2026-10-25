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
 * Complete 7-Stage DAX3 Psychoacoustic Audio Processing Engine at 48 kHz.
 * Stage 1: Input Conditioning (-6.0 dB Digital Pre-Gain + 20 Hz 2nd-Order Butterworth DC-Blocker).
 * Stage 2: 10-Band Cascaded Graphic EQ + Pultec Low-End Phase Shaper + Dynamic IEQ Spectral Balancing.
 * Stage 3: Sub-Bass Harmonic Wave-Shaper (Missing Fundamental: 4th-order LR 120 Hz LPF,
 *          Chebyshev Polynomials T2(x), T3(x), T4(x), and 120 Hz Fundamental Rejection HPF).
 * Stage 4: Dialog Enhancer (Mid/Side Matrix + 1.2 kHz - 3.8 kHz Speech Formant VAD Ratio +
 *          Dynamic Mid-Channel Peaking Boost at 2.4 kHz + 6.5 kHz De-Esser).
 * Stage 5: Surround Virtualiser (Binaural Headphone Woodworth ITD/ILD/Pinna/Haas OR
 *          Loudspeaker Transaural Crosstalk Cancellation C(z) with 250 Hz - 6000 Hz Band-Pass).
 * Stage 6: Volume Leveller (ITU-R BS.1770-4 K-Weighted LUFS + -50 dBFS Silence-Gated AGC + Fletcher-Munson).
 * Stage 7: Lookahead True-Peak Brickwall Limiter (2.5 ms lookahead, -0.1 dBFS ceiling).
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

    // Stage 1: DC Blocker (20 Hz Butterworth HPF) & Loudspeaker 85 Hz Excursion Protection HPF
    private val dcBlockerFilter = RbjBiquadFilter(sampleRate).apply { setHighPass(20.0, 0.70710678) }
    private val speakerExcursionHpf = RbjBiquadFilter(sampleRate).apply { setHighPass(85.0, 0.70710678) }

    // Stage 2: 10-Band Cytomic ZDF SVF Filter Bank + Pultec Low-End Phase Shaper
    private val svfBands = Array(10) { idx ->
        CytomicSvfBand(DolbyConstants.ISO_10_BAND_FREQUENCIES[idx].toDouble(), sampleRate)
    }
    private val pultecLowShelfBoost = RbjBiquadFilter(sampleRate).apply { setLowShelf(60.0, 0.65, 0.0) }
    private val pultecMidBassDip = RbjBiquadFilter(sampleRate).apply { setPeakingEq(105.0, 1.8, 0.0) }

    // Stage 3: Sub-Bass Harmonic Wave-Shaper (Missing Fundamental)
    // 4th-order Linkwitz-Riley Low-Pass at 120 Hz (two cascaded 2nd-order Butterworth stages)
    private val subBassLrLp1 = RbjBiquadFilter(sampleRate).apply { setLowPass(120.0, 0.70710678) }
    private val subBassLrLp2 = RbjBiquadFilter(sampleRate).apply { setLowPass(120.0, 0.70710678) }
    // Fundamental rejection High-Pass at 120 Hz + Upper Harmonic roll-off at 250 Hz
    private val subHarmonicRejectFundamentalHp = RbjBiquadFilter(sampleRate).apply { setHighPass(120.0, 0.70710678) }
    private val subHarmonicUpperLp = RbjBiquadFilter(sampleRate).apply { setLowPass(250.0, 0.70710678) }

    // Stage 4: Dialog Enhancer (Mid-channel 2.4 kHz presence boost + 6.5 kHz Sibilance De-Esser)
    private val dialogMidPresenceFilter = RbjBiquadFilter(sampleRate).apply { setPeakingEq(2400.0, 0.85, 0.0) }
    private val sibilanceSidechainBp = RbjBiquadFilter(sampleRate).apply { setBandPass(6500.0, 2.2) }
    private val deEsserCutFilter = RbjBiquadFilter(sampleRate).apply { setPeakingEq(6500.0, 2.0, 0.0) }
    private var sibilanceEnv = 0.0

    // Stage 5: Surround Virtualiser (Binaural Woodworth ITD + ILD + Pinna Notch vs Transaural XTC)
    private val haasDelaySamples = (0.012 * sampleRate).toInt()
    private val haasRingBuffer = DoubleArray(1024)
    private var haasWriteIdx = 0
    private val haasAllPass = RbjBiquadFilter(sampleRate).apply { setAllPass(950.0, 0.70710678) }
    private val pinnaNotchFilter = RbjBiquadFilter(sampleRate).apply { setPeakingEq(7500.0, 3.5, -4.5) }
    private val ildContralateralShelf = RbjBiquadFilter(sampleRate).apply { setHighShelf(1800.0, 0.70710678, -3.2) }
    private val earCanalPresenceFilter = RbjBiquadFilter(sampleRate).apply { setPeakingEq(2800.0, 1.4, 2.2) }

    // Woodworth Spherical-Head ITD delay (~0.30 ms = 14 samples at 48 kHz for theta = 35 deg, r = 0.0875 m)
    private val woodworthItdSamples = 14
    private val itdRingL = DoubleArray(64)
    private val itdRingR = DoubleArray(64)
    private var itdWriteIdx = 0

    // Loudspeaker Transaural Crosstalk Cancellation (XTC): delta_tau = 0.22 ms (11 samples at 48 kHz)
    // Band-limited to [250 Hz, 6000 Hz] to preserve sub-bass phase and prevent high-frequency comb filtering
    private val xtcDelaySamples = 11
    private val xtcRingL = DoubleArray(64)
    private val xtcRingR = DoubleArray(64)
    private var xtcWriteIdx = 0
    private val xtcBandHighPass = RbjBiquadFilter(sampleRate).apply { setHighPass(250.0, 0.70710678) }
    private val xtcBandLowPass = RbjBiquadFilter(sampleRate).apply { setLowPass(6000.0, 0.70710678) }

    // Stage 6: Volume Leveller (ITU-R BS.1770-4 K-Weighting + Silence-Gated AGC + Fletcher-Munson)
    private val kWeightStage1Shelf = RbjBiquadFilter(sampleRate).apply {
        setHighShelf(1681.974450955533, 0.7071752369554196, 3.999843853973347)
    }
    private val kWeightStage2RlbHp = RbjBiquadFilter(sampleRate).apply {
        setHighPass(38.13547087602444, 0.5003270373238773)
    }
    private val fletcherMunsonLowShelf = RbjBiquadFilter(sampleRate).apply { setLowShelf(65.0, 0.70710678, 0.0) }
    private val fletcherMunsonHighShelf = RbjBiquadFilter(sampleRate).apply { setHighShelf(11500.0, 0.70710678, 0.0) }

    private var shortTermMeanSquareSum = 1e-5
    private var currentInputLufs = -24.0
    private var currentAgcGainLinear = 1.0
    private val agcAttackCoeff = exp(-1.0 / (0.015 * sampleRate))
    private val agcReleaseCoeff = exp(-1.0 / (0.400 * sampleRate))

    // Stage 7: True-Peak Lookahead Brickwall Limiter (2.5 ms = 120 samples at 48 kHz, -0.1 dBFS ceiling)
    private val limiterCeiling = 10.0.pow(-0.1 / 20.0)
    private val lookaheadSamples = (0.0025 * sampleRate).toInt().coerceIn(16, 200)
    private val lookaheadBufL = DoubleArray(256)
    private val lookaheadBufR = DoubleArray(256)
    private var lookaheadIdx = 0
    private var limiterEnvGain = 1.0
    private val limiterAttackCoeff = exp(-1.0 / (0.0005 * sampleRate))
    private val limiterReleaseCoeff = exp(-1.0 / (0.050 * sampleRate))

    // Sidechain FFT & Formant VAD Classifier state (1.2 kHz - 3.8 kHz Speech Formants)
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
     * Executes the complete 7-stage DAX3 psychoacoustic DSP pipeline in-place on stereo PCM float blocks.
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
        // Stage 1: -6.0 dB Digital Pre-Gain Headroom
        val preGainDb = -6.0 - (maxPositiveGainDb * 0.22)
        val preGainLinear = 10.0.pow(preGainDb / 20.0)

        val bassDrive = when {
            activePreset == EqualiserPreset.BASS_BOOST -> 0.25
            activeMode == AudioMode.MUSIC || activeMode == AudioMode.FILM || activeMode == AudioMode.GAME -> 0.15
            else -> 0.06
        } * (1.0 + max(0.0, effectiveBandGainsDb[0] + effectiveBandGainsDb[1]) / 16.0)

        // Chebyshev harmonic weights k2, k3, k4
        val k2 = 0.52 * bassDrive
        val k3 = 0.34 * bassDrive
        val k4 = 0.14 * bassDrive
        val invSqrt2 = 0.7071067811865475

        for (i in 0 until numSamples) {
            // =========================================================================
            // STAGE 1: Input Conditioning (-6.0 dB Digital Pre-Gain + 20 Hz DC Blocker)
            // =========================================================================
            var sL = dcBlockerFilter.processLeft(left[i].toDouble() * preGainLinear)
            var sR = dcBlockerFilter.processRight(right[i].toDouble() * preGainLinear)

            if (!isHeadphoneRoute) {
                sL = speakerExcursionHpf.processLeft(sL)
                sR = speakerExcursionHpf.processRight(sR)
            }

            // =========================================================================
            // STAGE 2: 10-Band Cascaded Graphic EQ + Dynamic IEQ + Pultec Low-End Shaper
            // =========================================================================
            if (activePreset != EqualiserPreset.MANUAL) {
                sL = pultecMidBassDip.processLeft(pultecLowShelfBoost.processLeft(sL))
                sR = pultecMidBassDip.processRight(pultecLowShelfBoost.processRight(sR))
            }

            for (b in 0 until 10) {
                sL = svfBands[b].processLeft(sL)
                sR = svfBands[b].processRight(sR)
            }

            // =========================================================================
            // STAGE 3: Sub-Bass Harmonic Wave-Shaper (Missing Fundamental)
            // 4th-Order Linkwitz-Riley LPF at 120 Hz -> Chebyshev T2, T3, T4 -> 120 Hz HPF
            // =========================================================================
            val subMono = 0.5 * (sL + sR)
            val xLp = subBassLrLp2.processLeft(subBassLrLp1.processLeft(subMono))
            val xNorm = (xLp * 2.2).coerceIn(-1.0, 1.0)
            val x2 = xNorm * xNorm
            // Zero-DC offset Chebyshev polynomials: T2(x)-T2(0), T3(x), T4(x)-T4(0)
            val t2 = 2.0 * x2
            val t3 = 4.0 * x2 * xNorm - 3.0 * xNorm
            val t4 = 8.0 * x2 * x2 - 8.0 * x2
            val rawHarmonics = k2 * t2 + k3 * t3 + k4 * t4
            val harmonicInject = subHarmonicUpperLp.processLeft(
                subHarmonicRejectFundamentalHp.processLeft(rawHarmonics)
            )

            sL += harmonicInject
            sR += harmonicInject

            // =========================================================================
            // STAGE 4: Dialog Enhancer (Mid/Side Decomposition + 2.4 kHz Formant Boost)
            // =========================================================================
            val sibL = sibilanceSidechainBp.processLeft(sL)
            val sibR = sibilanceSidechainBp.processRight(sR)
            sibilanceEnv = 0.992 * sibilanceEnv + 0.008 * max(abs(sibL), abs(sibR))

            val midDialog = (sL + sR) * invSqrt2
            val sideDialog = (sL - sR) * invSqrt2
            val midEnhanced = dialogMidPresenceFilter.processLeft(midDialog)
            sL = (midEnhanced + sideDialog) * invSqrt2
            sR = (midEnhanced - sideDialog) * invSqrt2

            if (sibilanceEnv > 0.08) {
                sL = deEsserCutFilter.processLeft(sL)
                sR = deEsserCutFilter.processRight(sR)
            }

            // =========================================================================
            // STAGE 5: Surround Virtualiser (Headphone HRTF vs Loudspeaker Transaural XTC)
            // =========================================================================
            if (surroundVirtualiserEnabled || activeMode == AudioMode.SPATIAL_AUDIO) {
                val mid = (sL + sR) * invSqrt2
                val side = (sL - sR) * invSqrt2

                if (isHeadphoneRoute) {
                    // Binaural Headphone Virtualization:
                    // Haas early reflections + 7.5 kHz Pinna Notch + Woodworth Spherical-Head ITD & ILD
                    haasRingBuffer[haasWriteIdx] = side
                    val readIdx = (haasWriteIdx - haasDelaySamples + haasRingBuffer.size) % haasRingBuffer.size
                    val delayedSide = haasAllPass.processLeft(haasRingBuffer[readIdx])
                    haasWriteIdx = (haasWriteIdx + 1) % haasRingBuffer.size

                    val wideSide = side * 1.28 + delayedSide * 0.42
                    val binL = earCanalPresenceFilter.processLeft(
                        pinnaNotchFilter.processLeft((mid + wideSide) * invSqrt2)
                    )
                    val binR = earCanalPresenceFilter.processRight(
                        pinnaNotchFilter.processRight((mid - wideSide) * invSqrt2)
                    )

                    itdRingL[itdWriteIdx] = binL
                    itdRingR[itdWriteIdx] = binR
                    val itdRead = (itdWriteIdx - woodworthItdSamples + itdRingL.size) % itdRingL.size
                    val contraR = ildContralateralShelf.processLeft(itdRingR[itdRead])
                    val contraL = ildContralateralShelf.processRight(itdRingL[itdRead])
                    sL = binL + 0.18 * contraR
                    sR = binR + 0.18 * contraL
                    itdWriteIdx = (itdWriteIdx + 1) % itdRingL.size
                } else {
                    // Loudspeaker Transaural Crosstalk Cancellation (XTC):
                    // C(z) = (H^T H + beta I)^-1 H^T with 250 Hz - 6000 Hz Band-Pass & 0.22 ms delay
                    val wideL = (mid + side * 1.38) * invSqrt2
                    val wideR = (mid - side * 1.38) * invSqrt2
                    val bandL = xtcBandLowPass.processLeft(xtcBandHighPass.processLeft(wideL))
                    val bandR = xtcBandLowPass.processRight(xtcBandHighPass.processRight(wideR))

                    val xtcRead = (xtcWriteIdx - xtcDelaySamples + xtcRingL.size) % xtcRingL.size
                    val delayedCancelFromR = xtcRingR[xtcRead]
                    val delayedCancelFromL = xtcRingL[xtcRead]

                    val gXtc = if (activeMode == AudioMode.SPATIAL_AUDIO) 0.38 else 0.32
                    val outXtcL = wideL - gXtc * delayedCancelFromR
                    val outXtcR = wideR - gXtc * delayedCancelFromL

                    xtcRingL[xtcWriteIdx] = bandL - gXtc * 0.35 * delayedCancelFromR
                    xtcRingR[xtcWriteIdx] = bandR - gXtc * 0.35 * delayedCancelFromL
                    xtcWriteIdx = (xtcWriteIdx + 1) % xtcRingL.size

                    sL = outXtcL * 1.12
                    sR = outXtcR * 1.12
                }
            }

            // =========================================================================
            // STAGE 6: Volume Leveller (ITU-R BS.1770-4 K-Weighting + -50 dBFS Gate AGC)
            // =========================================================================
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

                sL = fletcherMunsonHighShelf.processLeft(
                    fletcherMunsonLowShelf.processLeft(sL * currentAgcGainLinear)
                )
                sR = fletcherMunsonHighShelf.processRight(
                    fletcherMunsonLowShelf.processRight(sR * currentAgcGainLinear)
                )
            } else {
                currentAgcGainLinear = 1.0
            }

            // =========================================================================
            // STAGE 7: Lookahead True-Peak Brickwall Limiter (2.5 ms, -0.1 dBFS Ceiling)
            // =========================================================================
            lookaheadBufL[lookaheadIdx] = sL
            lookaheadBufR[lookaheadIdx] = sR
            val peakAbs = max(abs(sL), abs(sR))
            val requiredGain = if (peakAbs > limiterCeiling) limiterCeiling / peakAbs else 1.0
            limiterEnvGain = if (requiredGain < limiterEnvGain) {
                limiterAttackCoeff * limiterEnvGain + (1.0 - limiterAttackCoeff) * requiredGain
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

        var formantSpeechEnergy = 1e-9
        var totalSpectralEnergy = 1e-9

        for (bin in 1 until (fftSize / 2)) {
            val freq = bin * binHz
            val magSq = fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]
            val mag = sqrt(magSq)
            weightedFreqSum += freq * mag
            magSum += mag
            totalSpectralEnergy += magSq
            if (freq in 1200.0..3800.0) {
                formantSpeechEnergy += magSq
            }

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

        for (b in 0 until 10) {
            val avgPower = bandEnergyAccum[b] / max(1, bandBinCounts[b])
            val db = (10.0 * log10(avgPower) - 6.0).coerceIn(-45.0, 6.0)
            measuredBandEnergiesDb[b] = 0.82 * measuredBandEnergiesDb[b] + 0.18 * db
        }

        // Speech formant ratio rho_speech[n] = E_speech[n] / (E_total[n] + eps)
        val rhoSpeech = (formantSpeechEnergy / totalSpectralEnergy).coerceIn(0.0, 1.0)
        val zcrFactor = if (zeroCrossingRate in 0.02..0.15) 1.0 else 0.55
        vadSpeechProb = (0.85 * vadSpeechProb + 0.15 * (rhoSpeech * zcrFactor * 1.45)).coerceIn(0.05, 0.98)
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

            // Dynamic IEQ Spectral Deviation D_b[n] = T_b - 10*log10(E_b[n])
            val relativeBandEnergy = measuredBandEnergiesDb[b] - avgEnergyDb
            val ieqDelta = if (activePreset == EqualiserPreset.MANUAL && activeMode == AudioMode.CUSTOM) {
                0.0
            } else {
                0.35 * (baseTargetDb - relativeBandEnergy).coerceIn(-6.0, 6.0)
            }
            ieqOffsetsDb[b] = 0.85 * ieqOffsetsDb[b] + 0.15 * ieqDelta
            effectiveBandGainsDb[b] = (baseTargetDb + ieqOffsetsDb[b]).coerceIn(-10.0, 10.0)
            svfBands[b].updateCoefficients(effectiveBandGainsDb[b])
        }

        val bassTarget = max(0.0, effectiveBandGainsDb[0] * 0.45)
        pultecLowShelfBoost.setLowShelf(60.0, 0.68, bassTarget)
        pultecMidBassDip.setPeakingEq(105.0, 1.9, -bassTarget * 0.42)

        val dialogMaxDb = when (activeMode) {
            AudioMode.PODCAST -> 6.0
            AudioMode.FILM, AudioMode.GAME -> 4.5
            AudioMode.SMART_AUDIO -> 4.0
            else -> 2.0
        }
        dialogMidPresenceFilter.setPeakingEq(2400.0, 0.85, dialogMaxDb * vadSpeechProb)

        val deEssCutDb = if (sibilanceEnv > 0.08) {
            -((sibilanceEnv - 0.08) * 35.0).coerceIn(0.0, 6.0)
        } else {
            0.0
        }
        deEsserCutFilter.setPeakingEq(6500.0, 2.0, deEssCutDb)

        if (volumeLevellerEnabled && currentInputLufs < -22.0) {
            val loudnessDeficit = ((-22.0 - currentInputLufs) * 0.25).coerceIn(0.0, 4.5)
            fletcherMunsonLowShelf.setLowShelf(65.0, 0.70710678, loudnessDeficit)
            fletcherMunsonHighShelf.setHighShelf(11500.0, 0.70710678, loudnessDeficit * 0.65)
        } else {
            fletcherMunsonLowShelf.setLowShelf(65.0, 0.70710678, 0.0)
            fletcherMunsonHighShelf.setHighShelf(11500.0, 0.70710678, 0.0)
        }
    }
}
