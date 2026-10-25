package com.example.dsp

import com.example.model.AudioMode
import com.example.model.DolbyConstants
import com.example.model.EqualiserPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Direct Form II Transposed Second-Order Robert Bristow-Johnson (RBJ) Biquad IIR Filter.
 *
 * Implements the discrete-time difference equation:
 *   y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
 * via Direct Form II Transposed state variables:
 *   y[n]  = b0 * x[n] + z1
 *   z1[n] = b1 * x[n] - a1 * y[n] + z2
 *   z2[n] = b2 * x[n] - a2 * y[n]
 */
class RbjBiquadFilter(private var sampleRate: Double = DolbyConstants.SAMPLE_RATE) {
    var b0 = 1.0
        private set
    var b1 = 0.0
        private set
    var b2 = 0.0
        private set
    var a1 = 0.0
        private set
    var a2 = 0.0
        private set

    private var z1L = 0.0
    private var z2L = 0.0
    private var z1R = 0.0
    private var z2R = 0.0

    fun setSampleRate(fs: Double) {
        if (fs > 8000.0) {
            sampleRate = fs
        }
    }

    /**
     * Exact normalized coefficients (e.g. for ITU-R BS.1770 K-Weighting stages).
     */
    fun setNormalizedCoefficients(
        b0Coeff: Double,
        b1Coeff: Double,
        b2Coeff: Double,
        a1Coeff: Double,
        a2Coeff: Double
    ) {
        b0 = b0Coeff
        b1 = b1Coeff
        b2 = b2Coeff
        a1 = a1Coeff
        a2 = a2Coeff
    }

    /**
     * Section 1 & 6: RBJ Peaking Equalizer Filter:
     *   omega_0 = 2 * pi * f_0 / f_s
     *   alpha   = sin(omega_0) / (2 * Q)
     *   A       = 10^(G_dB / 40)
     *   b_0 = 1 + alpha * A,  b_1 = -2 * cos(omega_0),  b_2 = 1 - alpha * A
     *   a_0 = 1 + alpha / A,  a_1 = -2 * cos(omega_0),  a_2 = 1 - alpha / A
     */
    fun setPeakingEq(freqHz: Double, q: Double = 1.414, gainDb: Double) {
        val omega0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(omega0)
        val sinW0 = sin(omega0)
        val alpha = sinW0 / (2.0 * max(0.1, q))
        val A = 10.0.pow(gainDb / 40.0)

        val rawB0 = 1.0 + alpha * A
        val rawB1 = -2.0 * cosW0
        val rawB2 = 1.0 - alpha * A
        val a0 = 1.0 + alpha / A
        val rawA1 = -2.0 * cosW0
        val rawA2 = 1.0 - alpha / A

        b0 = rawB0 / a0
        b1 = rawB1 / a0
        b2 = rawB2 / a0
        a1 = rawA1 / a0
        a2 = rawA2 / a0
    }

    fun setHighPass(freqHz: Double, q: Double = 0.70710678) {
        val omega0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(omega0)
        val alpha = sin(omega0) / (2.0 * max(0.1, q))
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosW0) * 0.5) / a0
        b1 = -(1.0 + cosW0) / a0
        b2 = ((1.0 + cosW0) * 0.5) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setLowPass(freqHz: Double, q: Double = 0.70710678) {
        val omega0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(omega0)
        val alpha = sin(omega0) / (2.0 * max(0.1, q))
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosW0) * 0.5) / a0
        b1 = (1.0 - cosW0) / a0
        b2 = ((1.0 - cosW0) * 0.5) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setBandPass(freqHz: Double, q: Double = 1.0) {
        val omega0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(omega0)
        val alpha = sin(omega0) / (2.0 * max(0.1, q))
        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b1 = 0.0
        b2 = -alpha / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setLowShelf(freqHz: Double, q: Double, gainDb: Double) {
        val A = 10.0.pow(gainDb / 40.0)
        val omega0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(omega0)
        val alpha = sin(omega0) / (2.0 * max(0.1, q))
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
        val omega0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(omega0)
        val alpha = sin(omega0) / (2.0 * max(0.1, q))
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha
        val a0 = (A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha
        b0 = (A * ((A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
        b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)) / a0
        b2 = (A * ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
        a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosW0)) / a0
        a2 = ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha) / a0
    }

    fun setAllPass(freqHz: Double, q: Double = 0.70710678) {
        val omega0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(omega0)
        val alpha = sin(omega0) / (2.0 * max(0.1, q))
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
 * Radix-2 In-Place Cooley-Tukey Fast Fourier Transform (FFT) for Bark-scale sub-band
 * spectral tracking and GCC-PHAT acoustic delay estimation.
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
 *
 * Implements the exact signal pipeline sequence from Section 7 of the Research Report:
 *  - Stage 1: -6.0 dB Digital Pre-Gain & 20 Hz 2nd-Order Butterworth DC-Blocker
 *  - Stage 2: 10-Band Cascaded Direct Form II Transposed RBJ Biquad Peaking EQ (Q = 1.414)
 *             & Bark-Scale Dynamic Intelligent Equalizer (IEQ) with fast attack (~10 ms),
 *             slow release (~100 ms), and adaptive clamping law:
 *             G_b[n] = clamp(gamma * (10*log10(Target_b) - 10*log10(E_b[n] + eps)), -10.0, +10.0)
 *  - Stage 3: Psychoacoustic Bass Synthesizer ("Missing Fundamental" / Tartini pitch effect):
 *             2nd-order Butterworth LPF at fc = 120 Hz -> Chebyshev Polynomial Wave-Shaper
 *             T_2(x) = 2x^2 - 1, T_3(x) = 4x^3 - 3x, T_4(x) = 8x^4 - 8x^2 + 1 ->
 *             steep High-Pass fundamental rejection + soft-saturation tanh blend
 *  - Stage 4: Dialog Enhancer (Mid/Side Formant Extraction):
 *             M[n] = 0.5*(L[n] + R[n]), S[n] = 0.5*(L[n] - R[n]),
 *             Vocal Activity Detector (VAD) measuring spectral energy ratio in 1.2 kHz - 3.8 kHz,
 *             and dynamic parametric formant boost on M[n] while preserving S[n]
 *  - Stage 5: Surround Virtualizer:
 *             * Headphone Mode (Binaural HRTF): Mid/Side decomposition, Woodworth spherical-head
 *               ITD delay (a/c * (theta + sin(theta)), a = 0.0875 m, c = 343 m/s), high-frequency
 *               ILD head-shadowing shelf (>1.5 kHz), and dual outer-ear pinna elevation notches
 *               between 6.5 kHz and 10.5 kHz (7.2 kHz & 9.6 kHz).
 *             * Loudspeaker Mode (Transaural Crosstalk Cancellation):
 *               2x2 inverted acoustic transfer matrix:
 *                 v_L = (x_L - A(z)*x_R) / (1 - A^2(z))
 *                 v_R = (x_R - A(z)*x_L) / (1 - A^2(z))
 *               where A(z) models contralateral head-shadow attenuation (alpha = 0.7) and
 *               fractional acoustic path delay (tau = 220 microseconds = 10.56 samples at 48 kHz).
 *  - Stage 6: ITU-R BS.1770 K-Weighted Volume Leveller:
 *             * Stage 1 High-Shelf:
 *               b = [1.53512485958697, -2.69169618940638, 1.19839281085285]
 *               a = [1.0, -1.69065929318241, 0.73248077421585]
 *             * Stage 2 RLB High-Pass:
 *               b = [1.0, -2.0, 1.0]
 *               a = [1.0, -1.99004745483398, 0.99007225034108]
 *             * Short-term LUFS integrator + AGC gain follower with -50 dBFS silence gate
 *               targeting -18.0 LUFS.
 *  - Stage 7: True-Peak Lookahead Brickwall Limiter:
 *             2.5 ms circular lookahead delay line (120 samples at 48 kHz) enforcing a strict
 *             -0.1 dBFS ceiling with smooth cubic release envelopes.
 */
class PsychoacousticDspEngine(
    private var sampleRate: Double = DolbyConstants.SAMPLE_RATE
) {
    @Volatile var masterEnabled: Boolean = true
    @Volatile var activeMode: AudioMode = AudioMode.MUSIC
    @Volatile var activePreset: EqualiserPreset = EqualiserPreset.BRILLIANT_TREBLE
    @Volatile var surroundVirtualiserEnabled: Boolean = true
    @Volatile var volumeLevellerEnabled: Boolean = true
    @Volatile var isHeadphoneRoute: Boolean = false
    @Volatile var macroBassTiltDb: Float = 0f
    @Volatile var macroTrebleTiltDb: Float = 0f

    // 10 ISO standard octave center frequencies: 31.5, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
    private val isoCenterFreqs = DoubleArray(10) { idx ->
        DolbyConstants.ISO_10_BAND_FREQUENCIES[idx].toDouble()
    }

    private val userBandGainsDb = DoubleArray(10) {
        EqualiserPreset.BRILLIANT_TREBLE.targetBandGainsDb[it].toDouble()
    }
    private val effectiveBandGainsDb = DoubleArray(10) { 0.0 }

    // =========================================================================
    // Stage 1: -6.0 dB Digital Pre-Gain & 20 Hz 2nd-Order Butterworth DC-Blocker
    // =========================================================================
    private val preGainHeadroomLinear = 10.0.pow(-6.0 / 20.0) // 0.5011872336272722
    private val dcBlockerFilter = RbjBiquadFilter(sampleRate).apply {
        setHighPass(20.0, 0.70710678)
    }

    // =========================================================================
    // Stage 2: 10-Band Cascaded Direct Form II Transposed RBJ Biquad Peaking EQ
    //          + Bark-Scale Sub-Band Energy Tracker (10 ms attack, 100 ms release)
    // =========================================================================
    private val biquadEqBands = Array(10) { idx ->
        RbjBiquadFilter(sampleRate).apply {
            setPeakingEq(isoCenterFreqs[idx], 1.414, 0.0)
        }
    }
    // 24 Bark critical band upper edge frequencies (Hz) mapped onto the 10 ISO octave bands
    private val barkCriticalBandEdgesHz = doubleArrayOf(
        100.0, 200.0, 300.0, 400.0, 510.0, 630.0, 770.0, 920.0,
        1080.0, 1270.0, 1480.0, 1720.0, 2000.0, 2320.0, 2700.0, 3150.0,
        3700.0, 4400.0, 5300.0, 6400.0, 7700.0, 9500.0, 12000.0, 15500.0
    )
    private val barkSubBandEnergy = DoubleArray(24) { 1e-4 }
    private val isoSubBandEnergy = DoubleArray(10) { 1e-4 }
    private val ieqGainOffsetsDb = DoubleArray(10) { 0.0 }

    // =========================================================================
    // Stage 3: Psychoacoustic Bass Synthesizer ("Missing Fundamental" / Tartini)
    // =========================================================================
    // 2nd-order Butterworth Low-Pass at fc = 120 Hz
    private val subBassLowPass120 = RbjBiquadFilter(sampleRate).apply {
        setLowPass(120.0, 0.70710678)
    }
    // Steep 4th-order High-Pass at 120 Hz to reject the physical fundamental & Chebyshev DC offset
    private val fundamentalRejectHp1 = RbjBiquadFilter(sampleRate).apply {
        setHighPass(120.0, 0.70710678)
    }
    private val fundamentalRejectHp2 = RbjBiquadFilter(sampleRate).apply {
        setHighPass(120.0, 0.70710678)
    }
    // Harmonic band-limiting Low-Pass at 360 Hz so only 2nd, 3rd, and 4th harmonics are injected
    private val harmonicBandLowPass = RbjBiquadFilter(sampleRate).apply {
        setLowPass(360.0, 0.70710678)
    }
    // Physical speaker driver protection High-Pass at 105 Hz when on small loudspeaker drivers
    private val speakerDriverHighPass = RbjBiquadFilter(sampleRate).apply {
        setHighPass(105.0, 0.70710678)
    }

    // =========================================================================
    // Stage 4: Dialog Enhancer (Mid/Side Formant Extraction 1.2 kHz - 3.8 kHz)
    // =========================================================================
    private val dialogFormantDetectorBp = RbjBiquadFilter(sampleRate).apply {
        setBandPass(2400.0, 0.92) // Covers 1.2 kHz - 3.8 kHz speech formant band
    }
    private val dialogMidPeakingFilter = RbjBiquadFilter(sampleRate).apply {
        setPeakingEq(2400.0, 0.90, 0.0)
    }
    private var speechFormantPowerEnv = 1e-5
    private var totalMidSidePowerEnv = 1e-4
    private var rhoSpeech = 0.20

    // =========================================================================
    // Stage 5: Surround Virtualizer (Binaural HRTF & Transaural Crosstalk Cancellation)
    // =========================================================================
    // A. Headphone Binaural Mode:
    // Woodworth Spherical-Head ITD formula:
    //   delta_tau_ITD = (a / c) * (theta + sin(theta))
    // For head radius a = 0.0875 m, speed of sound c = 343.0 m/s, virtual speaker angle theta = 30 deg (0.5236 rad):
    //   delta_tau_ITD = (0.0875 / 343.0) * (0.5235988 + 0.5) = 261.0 microseconds = 12.53 samples at 48 kHz
    private val woodworthRadiusM = 0.0875
    private val speedOfSoundMps = 343.0
    private val virtualAzimuthRad = PI / 6.0 // 30 degrees
    private val woodworthItdSamples: Double =
        ((woodworthRadiusM / speedOfSoundMps) * (virtualAzimuthRad + sin(virtualAzimuthRad))) * sampleRate

    private val hrtfRingSize = 256
    private val hrtfRingMask = hrtfRingSize - 1
    private val itdDelayLineL = DoubleArray(hrtfRingSize)
    private val itdDelayLineR = DoubleArray(hrtfRingSize)
    private val haasSideRing = DoubleArray(hrtfRingSize)
    private var hrtfWriteIdx = 0

    // High-frequency ILD head-shadowing shelving filter (> 1.5 kHz)
    private val ildHeadShadowShelf = RbjBiquadFilter(sampleRate).apply {
        setHighShelf(1500.0, 0.70710678, -3.5)
    }
    // Outer-ear Pinna Elevation Notches between 6.5 kHz and 10.5 kHz (7.2 kHz primary & 9.6 kHz secondary concha notch)
    private val pinnaNotchPrimary = RbjBiquadFilter(sampleRate).apply {
        setPeakingEq(7200.0, 4.0, -5.0)
    }
    private val pinnaNotchSecondary = RbjBiquadFilter(sampleRate).apply {
        setPeakingEq(9600.0, 4.5, -4.0)
    }
    private val haasAllPassFilter = RbjBiquadFilter(sampleRate).apply {
        setAllPass(950.0, 0.70710678)
    }

    // B. Loudspeaker Transaural Crosstalk Cancellation Mode:
    // 2x2 Inverted Acoustic Transfer Matrix:
    //   v_L = (x_L - A(z)*x_R) / (1 - A^2(z))
    //   v_R = (x_R - A(z)*x_L) / (1 - A^2(z))
    // where A(z) models contralateral head-shadow attenuation (alpha = 0.7) and fractional acoustic
    // path delay (tau = 220 microseconds = 10.56 samples at 48 kHz).
    // Notice that:
    //   v_L[n] = x_L[n] - A(z)*x_R[n] + A^2(z)*v_L[n]
    //   v_R[n] = x_R[n] - A(z)*x_L[n] + A^2(z)*v_R[n]
    // which is equivalent to the exact recursive feedback form:
    //   v_L[n] = x_L[n] - A(z)*v_R[n]
    //   v_R[n] = x_R[n] - A(z)*v_L[n]
    private val xtcAlpha = 0.70
    private val xtcDelaySamples: Double = 0.000220 * sampleRate // 220 us = 10.56 samples at 48 kHz
    private val xtcRingSize = 128
    private val xtcRingMask = xtcRingSize - 1
    private val xtcFeedbackRingL = DoubleArray(xtcRingSize)
    private val xtcFeedbackRingR = DoubleArray(xtcRingSize)
    private var xtcWriteIdx = 0
    private val xtcHeadShadowLp = RbjBiquadFilter(sampleRate).apply {
        setLowPass(5800.0, 0.70710678)
    }
    private val xtcLowFreqGuardHp = RbjBiquadFilter(sampleRate).apply {
        setHighPass(220.0, 0.70710678)
    }

    // =========================================================================
    // Stage 6: ITU-R BS.1770 K-Weighted Volume Leveller (-50 dBFS Gate, -18 LUFS Target)
    // =========================================================================
    // Exact Stage 1 High-Shelf filter coefficients from ITU-R BS.1770 at 48 kHz:
    //   b = [1.53512485958697, -2.69169618940638, 1.19839281085285]
    //   a = [1.0, -1.69065929318241, 0.73248077421585]
    private val kWeightStage1Shelf = RbjBiquadFilter(sampleRate).apply {
        setNormalizedCoefficients(
            b0Coeff = 1.53512485958697,
            b1Coeff = -2.69169618940638,
            b2Coeff = 1.19839281085285,
            a1Coeff = -1.69065929318241,
            a2Coeff = 0.73248077421585
        )
    }
    // Exact Stage 2 Revised Low-Frequency B-Weighting (RLB) High-Pass coefficients at 48 kHz:
    //   b = [1.0, -2.0, 1.0]
    //   a = [1.0, -1.99004745483398, 0.99007225034108]
    private val kWeightStage2RlbHp = RbjBiquadFilter(sampleRate).apply {
        setNormalizedCoefficients(
            b0Coeff = 1.0,
            b1Coeff = -2.0,
            b2Coeff = 1.0,
            a1Coeff = -1.99004745483398,
            a2Coeff = 0.99007225034108
        )
    }
    private var meanSquareL = 1e-6
    private var meanSquareR = 1e-6
    private var currentLoudnessLufs = -24.0
    private var agcGainLinear = 1.0
    private val agcAttackCoeff = exp(-1.0 / (0.015 * sampleRate))
    private val agcReleaseCoeff = exp(-1.0 / (0.350 * sampleRate))

    // =========================================================================
    // Stage 7: True-Peak Lookahead Brickwall Limiter (2.5 ms Delay Line, -0.1 dBFS Ceiling)
    // =========================================================================
    private val limiterCeilingLinear = 10.0.pow(-0.1 / 20.0) // 0.9885530946569389 (-0.1 dBFS)
    private val lookaheadDelaySamples = ((0.0025 * sampleRate).toInt()).coerceIn(16, 240) // 120 samples at 48 kHz
    private val lookaheadRingSize = 256
    private val lookaheadRingMask = lookaheadRingSize - 1
    private val lookaheadBufferL = DoubleArray(lookaheadRingSize)
    private val lookaheadBufferR = DoubleArray(lookaheadRingSize)
    private var lookaheadWriteIdx = 0
    private var limiterGainEnv = 1.0
    private var limiterReleasePhase = 1.0
    private val limiterReleaseSamples = (0.045 * sampleRate).coerceAtLeast(64.0)

    // Sidechain FFT buffers for Bark-scale sub-band energy & formant analysis
    private val fftSize = 256
    private val fftRe = DoubleArray(fftSize)
    private val fftIm = DoubleArray(fftSize)

    fun setSampleRate(newSampleRate: Double) {
        if (newSampleRate <= 8000.0 || abs(newSampleRate - sampleRate) < 1.0) return
        sampleRate = newSampleRate
        dcBlockerFilter.setSampleRate(sampleRate)
        dcBlockerFilter.setHighPass(20.0, 0.70710678)
        for (b in 0 until 10) {
            biquadEqBands[b].setSampleRate(sampleRate)
            biquadEqBands[b].setPeakingEq(isoCenterFreqs[b], 1.414, effectiveBandGainsDb[b])
        }
    }

    fun setBandGains(gainsDb: FloatArray) {
        for (i in 0 until min(10, gainsDb.size)) {
            userBandGainsDb[i] = gainsDb[i].toDouble().coerceIn(-10.0, 10.0)
        }
        for (i in 0 until 10) {
            effectiveBandGainsDb[i] = (userBandGainsDb[i] + ieqGainOffsetsDb[i]).coerceIn(-10.0, 10.0)
            biquadEqBands[i].setPeakingEq(isoCenterFreqs[i], 1.414, effectiveBandGainsDb[i])
        }
    }

    fun getEffectiveBandGainsDb(): List<Float> = effectiveBandGainsDb.map { it.toFloat() }

    /**
     * Primary real-time callback matching Section 8:
     * `processBlock(left, right, numSamples)`
     */
    fun processBlock(left: FloatArray, right: FloatArray, numSamples: Int) {
        processAudioBlock(left, right, numSamples)
    }

    /**
     * Executes all 7 stages in the exact pipeline order specified in Section 7 of the report.
     */
    fun processAudioBlock(left: FloatArray, right: FloatArray, numSamples: Int) {
        if (!masterEnabled || numSamples <= 0) return

        // Update Bark-scale Dynamic IEQ sub-band tracker & VAD formant parameters
        updateBarkScaleIeqAndVadSidechain(left, right, numSamples)

        // Chebyshev harmonic weights for Stage 3 Missing Fundamental synthesizer
        val bassBoostFactor = 1.0 + max(0.0, effectiveBandGainsDb[0] + effectiveBandGainsDb[1]) / 16.0
        val harmonicBlendGain = (if ( isHeadphoneRoute) 0.08 else 0.16) * bassBoostFactor
        val w2 = 0.52
        val w3 = 0.33
        val w4 = 0.15

        val lufsIntCoeff = exp(-1.0 / (0.400 * sampleRate)) // 400 ms momentary LUFS window

        for (i in 0 until numSamples) {
            // =========================================================================
            // STAGE 1: -6.0 dB Digital Pre-Gain & 20 Hz DC-Blocker
            // =========================================================================
            var sL = dcBlockerFilter.processLeft(left[i].toDouble() * preGainHeadroomLinear)
            var sR = dcBlockerFilter.processRight(right[i].toDouble() * preGainHeadroomLinear)

            // =========================================================================
            // STAGE 2: 10-Band Cascaded Direct Form II Transposed RBJ Biquad Peaking EQ
            //          & Dynamic IEQ Tracking
            // =========================================================================
            for (b in 0 until 10) {
                sL = biquadEqBands[b].processLeft(sL)
                sR = biquadEqBands[b].processRight(sR)
            }

            // =========================================================================
            // STAGE 3: Psychoacoustic Bass Synthesizer ("Missing Fundamental")
            //   1) Extract sub-bass below fc = 120 Hz via 2nd-order Butterworth LPF
            //   2) Chebyshev polynomial wave-shaper:
            //        T_2(x) = 2x^2 - 1
            //        T_3(x) = 4x^3 - 3x
            //        T_4(x) = 8x^4 - 8x^2 + 1
            //   3) Steep High-Pass filter removes physical fundamental & DC offset,
            //      blending the synthesized harmonic spectrum back with soft-saturation
            // =========================================================================
            val monoIn = 0.5 * (sL + sR)
            val subFundamental = subBassLowPass120.processLeft(monoIn)
            val x = (subFundamental * 2.4).coerceIn(-1.0, 1.0)
            val x2 = x * x
            val x3 = x2 * x
            val x4 = x2 * x2

            val t2 = 2.0 * x2 - 1.0
            val t3 = 4.0 * x3 - 3.0 * x
            val t4 = 8.0 * x4 - 8.0 * x2 + 1.0

            // Subtract DC bias (value at x = 0: -w2 + w4) before high-pass fundamental rejection
            val chebyshevRaw = (w2 * t2 + w3 * t3 + w4 * t4) - (-w2 + w4)
            val harmHp = fundamentalRejectHp2.processLeft(fundamentalRejectHp1.processLeft(chebyshevRaw))
            val harmBand = harmonicBandLowPass.processLeft(harmHp)
            val saturatedHarmonics = harmonicBlendGain * tanh(1.8 * harmBand)

            if (!isHeadphoneRoute) {
                // Remove diaphragm-distorting physical fundamental from small speaker path
                sL = speakerDriverHighPass.processLeft(sL) + saturatedHarmonics
                sR = speakerDriverHighPass.processRight(sR) + saturatedHarmonics
            } else {
                sL += saturatedHarmonics
                sR += saturatedHarmonics
            }

            // =========================================================================
            // STAGE 4: Dialog Enhancer (Mid/Side Formant Extraction 1.2 kHz - 3.8 kHz)
            //   M[n] = 0.5 * (L[n] + R[n]),  S[n] = 0.5 * (L[n] - R[n])
            //   Boost formant band on M[n] proportionally to rho_speech[n], keep S[n] untouched
            // =========================================================================
            val mid = 0.5 * (sL + sR)
            val side = 0.5 * (sL - sR)

            val formantSignal = dialogFormantDetectorBp.processLeft(mid)
            val formantSq = formantSignal * formantSignal
            val totalSq = mid * mid + side * side + 1e-12
            speechFormantPowerEnv = 0.9985 * speechFormantPowerEnv + 0.0015 * formantSq
            totalMidSidePowerEnv = 0.9985 * totalMidSidePowerEnv + 0.0015 * totalSq

            val enhancedMid = dialogMidPeakingFilter.processLeft(mid)
            sL = enhancedMid + side
            sR = enhancedMid - side

            // =========================================================================
            // STAGE 5: Surround Virtualizer (Binaural HRTF or Transaural Crosstalk Cancellation)
            // =========================================================================
            if (surroundVirtualiserEnabled || activeMode == AudioMode.SPATIAL_AUDIO) {
                if (isHeadphoneRoute) {
                    // Headphone Mode (Binaural HRTF Virtualization):
                    // 1) Mid/Side decomposition M = 0.5*(L+R), S = 0.5*(L-R)
                    // 2) Pinna elevation notches between 6.5 kHz and 10.5 kHz (7.2 kHz & 9.6 kHz)
                    // 3) Woodworth spherical-head ITD delay line: a/c * (theta + sin(theta))
                    //    + high-frequency ILD head-shadowing shelf (> 1.5 kHz)
                    val mHrtf = 0.5 * (sL + sR)
                    val sHrtf = 0.5 * (sL - sR)

                    haasSideRing[hrtfWriteIdx] = sHrtf
                    val haasDelayed = haasAllPassFilter.processLeft(
                        haasSideRing[(hrtfWriteIdx - 18) and hrtfRingMask]
                    )
                    val expandedSide = 1.32 * sHrtf + 0.36 * haasDelayed

                    val pinnaL = pinnaNotchSecondary.processLeft(
                        pinnaNotchPrimary.processLeft(mHrtf + expandedSide)
                    )
                    val pinnaR = pinnaNotchSecondary.processRight(
                        pinnaNotchPrimary.processRight(mHrtf - expandedSide)
                    )

                    itdDelayLineL[hrtfWriteIdx] = pinnaL
                    itdDelayLineR[hrtfWriteIdx] = pinnaR

                    val delayedContraR = readFractionalRing(itdDelayLineR, hrtfWriteIdx, woodworthItdSamples, hrtfRingMask)
                    val delayedContraL = readFractionalRing(itdDelayLineL, hrtfWriteIdx, woodworthItdSamples, hrtfRingMask)

                    val shadowedContraR = ildHeadShadowShelf.processLeft(delayedContraR)
                    val shadowedContraL = ildHeadShadowShelf.processRight(delayedContraL)

                    sL = pinnaL + 0.22 * shadowedContraR
                    sR = pinnaR + 0.22 * shadowedContraL
                    hrtfWriteIdx = (hrtfWriteIdx + 1) and hrtfRingMask
                } else {
                    // Loudspeaker Mode (Transaural Crosstalk Cancellation):
                    // 2x2 Inverted Acoustic Transfer Matrix:
                    //   v_L = (x_L - A(z)*x_R) / (1 - A^2(z))
                    //   v_R = (x_R - A(z)*x_L) / (1 - A^2(z))
                    // Evaluated via the exact recursive realization:
                    //   v_L[n] = x_L[n] - A(z)*v_R[n]
                    //   v_R[n] = x_R[n] - A(z)*v_L[n]
                    // where A(z) has contralateral head-shadow attenuation alpha = 0.7 (regularized
                    // across the transaural band) and fractional path delay tau = 220 microseconds.
                    val delayedVR = readFractionalRing(xtcFeedbackRingR, xtcWriteIdx, xtcDelaySamples, xtcRingMask)
                    val delayedVL = readFractionalRing(xtcFeedbackRingL, xtcWriteIdx, xtcDelaySamples, xtcRingMask)

                    val azVR = xtcAlpha * 0.48 * xtcHeadShadowLp.processLeft(xtcLowFreqGuardHp.processLeft(delayedVR))
                    val azVL = xtcAlpha * 0.48 * xtcHeadShadowLp.processRight(xtcLowFreqGuardHp.processRight(delayedVL))

                    val vL = sL - azVR
                    val vR = sR - azVL

                    xtcFeedbackRingL[xtcWriteIdx] = vL.coerceIn(-2.0, 2.0)
                    xtcFeedbackRingR[xtcWriteIdx] = vR.coerceIn(-2.0, 2.0)
                    xtcWriteIdx = (xtcWriteIdx + 1) and xtcRingMask

                    sL = vL
                    sR = vR
                }
            }

            // =========================================================================
            // STAGE 6: ITU-R BS.1770 K-Weighted Volume Leveller (-50 dBFS Gate, -18 LUFS Target)
            // =========================================================================
            val kwL = kWeightStage2RlbHp.processLeft(kWeightStage1Shelf.processLeft(sL))
            val kwR = kWeightStage2RlbHp.processRight(kWeightStage1Shelf.processRight(sR))

            meanSquareL = lufsIntCoeff * meanSquareL + (1.0 - lufsIntCoeff) * max(kwL * kwL, 1e-12)
            meanSquareR = lufsIntCoeff * meanSquareR + (1.0 - lufsIntCoeff) * max(kwR * kwR, 1e-12)

            // Integrated loudness in LUFS: L_K = -0.691 + 10 * log10(G_L * z_L + G_R * z_R)
            currentLoudnessLufs = -0.691 + 10.0 * log10(meanSquareL + meanSquareR)

            if (volumeLevellerEnabled) {
                // Silence-gating threshold at -50.0 dBFS targeting -18.0 LUFS
                val targetGainLinear = if (currentLoudnessLufs > -50.0) {
                    val deltaLufs = (-18.0 - currentLoudnessLufs).coerceIn(-10.0, 10.0)
                    10.0.pow(deltaLufs / 20.0)
                } else {
                    1.0
                }
                val coeff = if (targetGainLinear < agcGainLinear) agcAttackCoeff else agcReleaseCoeff
                agcGainLinear = coeff * agcGainLinear + (1.0 - coeff) * targetGainLinear
                sL *= agcGainLinear
                sR *= agcGainLinear
            } else {
                // Restore 6.0 dB pre-gain headroom when Volume Leveller is off
                agcGainLinear = 1.0
                sL *= 1.9952623149688795
                sR *= 1.9952623149688795
            }

            // =========================================================================
            // STAGE 7: True-Peak Lookahead Brickwall Limiter (2.5 ms Delay Line, -0.1 dBFS Ceiling)
            // =========================================================================
            lookaheadBufferL[lookaheadWriteIdx] = sL
            lookaheadBufferR[lookaheadWriteIdx] = sR

            val peakAbs = max(abs(sL), abs(sR))
            val targetLimGain = if (peakAbs > limiterCeilingLinear) {
                limiterCeilingLinear / peakAbs
            } else {
                1.0
            }

            if (targetLimGain < limiterGainEnv) {
                // Fast lookahead attack
                limiterGainEnv = 0.20 * limiterGainEnv + 0.80 * targetLimGain
                limiterReleasePhase = 0.0
            } else if (limiterGainEnv < 1.0) {
                // Smooth cubic release envelope: g(u) = g_min + (1 - g_min) * (3u^2 - 2u^3)
                limiterReleasePhase = (limiterReleasePhase + 1.0 / limiterReleaseSamples).coerceAtMost(1.0)
                val u = limiterReleasePhase
                val cubicEnv = u * u * (3.0 - 2.0 * u)
                limiterGainEnv = limiterGainEnv + (1.0 - limiterGainEnv) * cubicEnv * 0.04
            }

            val readIdx = (lookaheadWriteIdx - lookaheadDelaySamples) and lookaheadRingMask
            val outL = (lookaheadBufferL[readIdx] * limiterGainEnv).coerceIn(-limiterCeilingLinear, limiterCeilingLinear)
            val outR = (lookaheadBufferR[readIdx] * limiterGainEnv).coerceIn(-limiterCeilingLinear, limiterCeilingLinear)
            lookaheadWriteIdx = (lookaheadWriteIdx + 1) and lookaheadRingMask

            left[i] = outL.toFloat()
            right[i] = outR.toFloat()
        }
    }

    private fun readFractionalRing(
        ring: DoubleArray,
        writeIdx: Int,
        delaySamples: Double,
        mask: Int
    ): Double {
        val dInt = floor(delaySamples).toInt()
        val frac = delaySamples - dInt.toDouble()
        val idx0 = (writeIdx - dInt) and mask
        val idx1 = (writeIdx - dInt - 1) and mask
        return ring[idx0] * (1.0 - frac) + ring[idx1] * frac
    }

    /**
     * Section 2 & 5:
     * - Computes 24 Bark-scale critical sub-band energies E_b[n] with asymmetric time constants:
     *     Fast attack (~10 ms) and slow release (~100 ms).
     * - Applies the adaptive IEQ deviation clamping law:
     *     G_b[n] = clamp(gamma * (10*log10(Target_b) - 10*log10(E_b[n] + eps)), -10.0, +10.0)
     * - Updates Dialog Enhancer vocal presence ratio rho_speech[n] in 1.2 kHz - 3.8 kHz.
     */
    private fun updateBarkScaleIeqAndVadSidechain(
        left: FloatArray,
        right: FloatArray,
        numSamples: Int
    ) {
        val n = min(numSamples, fftSize)
        for (i in 0 until fftSize) {
            if (i < n) {
                val mono = 0.5 * (left[i] + right[i])
                val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
                fftRe[i] = mono * hann
                fftIm[i] = 0.0
            } else {
                fftRe[i] = 0.0
                fftIm[i] = 0.0
            }
        }

        FastFourierTransform.fft(fftRe, fftIm, invert = false)

        val binHz = sampleRate / fftSize.toDouble()
        val instBarkPower = DoubleArray(24) { 1e-9 }
        val barkBinCount = IntArray(24) { 0 }

        var speechBandEnergy = 1e-9
        var totalSpectrumEnergy = 1e-9

        for (bin in 1 until (fftSize / 2)) {
            val freq = bin * binHz
            val magSq = (fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]) / (fftSize * fftSize)
            totalSpectrumEnergy += magSq
            if (freq in 1200.0..3800.0) {
                speechBandEnergy += magSq
            }

            var barkIdx = 23
            for (b in 0 until 24) {
                if (freq <= barkCriticalBandEdgesHz[b]) {
                    barkIdx = b
                    break
                }
            }
            instBarkPower[barkIdx] += magSq
            barkBinCount[barkIdx]++
        }

        // Asymmetric Bark energy time constants for block rate (block duration = n / sampleRate):
        // Fast attack (~10 ms) vs Slow release (~100 ms)
        val blockDurationSec = max(1, n).toDouble() / sampleRate
        val alphaAttack = exp(-blockDurationSec / 0.010)
        val alphaRelease = exp(-blockDurationSec / 0.100)

        for (b in 0 until 24) {
            val avgPwr = instBarkPower[b] / max(1, barkBinCount[b])
            val coeff = if (avgPwr > barkSubBandEnergy[b]) alphaAttack else alphaRelease
            barkSubBandEnergy[b] = coeff * barkSubBandEnergy[b] + (1.0 - coeff) * avgPwr
        }

        // Map 24 Bark bands onto the 10 ISO octave bands & apply IEQ clamping law
        var anchorEnergySum = 0.0
        for (i in 0 until 10) {
            val f0 = isoCenterFreqs[i]
            var nearestBark = 0
            var minDist = Double.MAX_VALUE
            for (b in 0 until 24) {
                val dist = abs(ln(max(20.0, f0) / barkCriticalBandEdgesHz[b]))
                if (dist < minDist) {
                    minDist = dist
                    nearestBark = b
                }
            }
            val instBandPwr = barkSubBandEnergy[nearestBark]
            val coeff = if (instBandPwr > isoSubBandEnergy[i]) alphaAttack else alphaRelease
            isoSubBandEnergy[i] = coeff * isoSubBandEnergy[i] + (1.0 - coeff) * instBandPwr
            anchorEnergySum += isoSubBandEnergy[i]
        }

        // Anchor around 1 kHz (band index 5) so IEQ shapes spectral balance rather than overall volume
        val refEnergy = max(isoSubBandEnergy[5], (anchorEnergySum / 10.0).coerceAtLeast(1e-9))
        val gamma = if (activePreset == EqualiserPreset.MANUAL && activeMode == AudioMode.CUSTOM) {
            0.0
        } else {
            0.35
        }
        val eps = 1e-12

        for (i in 0 until 10) {
            val normPos = (i.toDouble() / 9.0) * 2.0 - 1.0
            val tiltOffset = if (normPos < 0.0) {
                -normPos * macroBassTiltDb.toDouble()
            } else {
                normPos * macroTrebleTiltDb.toDouble()
            }

            val targetCurveDb = when (activeMode) {
                AudioMode.SMART_AUDIO -> {
                    val musicDb = EqualiserPreset.BRILLIANT_TREBLE.targetBandGainsDb[i].toDouble()
                    val vocalDb = EqualiserPreset.VOCAL_BOOST.targetBandGainsDb[i].toDouble()
                    rhoSpeech * vocalDb + (1.0 - rhoSpeech) * musicDb
                }
                AudioMode.SPATIAL_AUDIO -> {
                    val spatialCurve = doubleArrayOf(4.5, 3.8, 1.5, 0.0, -0.5, 1.8, 3.5, 4.8, 5.5, 6.5)
                    spatialCurve[i]
                }
                else -> userBandGainsDb[i] + tiltOffset
            }

            // Target_b linear power relative to 1 kHz anchor
            val targetLinearPower = refEnergy * 10.0.pow(targetCurveDb / 10.0)
            // Exact IEQ adaptive deviation clamping law:
            // G_b[n] = clamp(gamma * (10*log10(Target_b) - 10*log10(E_b[n] + eps)), -10.0, +10.0)
            val ieqDynamicDeltaDb = (
                gamma * (10.0 * log10(targetLinearPower + eps) - 10.0 * log10(isoSubBandEnergy[i] + eps))
            ).coerceIn(-10.0, 10.0)

            ieqGainOffsetsDb[i] = if (gamma == 0.0) 0.0 else (0.80 * ieqGainOffsetsDb[i] + 0.20 * ieqDynamicDeltaDb)
            effectiveBandGainsDb[i] = (targetCurveDb + ieqGainOffsetsDb[i]).coerceIn(-10.0, 10.0)
            biquadEqBands[i].setPeakingEq(isoCenterFreqs[i], 1.414, effectiveBandGainsDb[i])
        }

        // Update Dialog Enhancer vocal presence ratio rho_speech[n] in 1.2 kHz - 3.8 kHz
        val fftRho = (speechBandEnergy / totalSpectrumEnergy).coerceIn(0.0, 1.0)
        val timeRho = (speechFormantPowerEnv / totalMidSidePowerEnv).coerceIn(0.0, 1.0)
        rhoSpeech = (0.80 * rhoSpeech + 0.20 * max(fftRho, timeRho)).coerceIn(0.0, 1.0)

        val maxDialogBoostDb = when (activeMode) {
            AudioMode.PODCAST -> 6.0
            AudioMode.FILM, AudioMode.GAME -> 4.5
            AudioMode.SMART_AUDIO -> 4.0
            else -> 2.2
        }
        val activeDialogGainDb = if (rhoSpeech > 0.12) {
            (maxDialogBoostDb * rhoSpeech).coerceIn(0.0, 6.0)
        } else {
            0.0
        }
        dialogMidPeakingFilter.setPeakingEq(2400.0, 0.90, activeDialogGainDb)
    }
}
