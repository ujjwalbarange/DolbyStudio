use std::f64::consts::PI;

pub const SAMPLE_RATE: f64 = 48000.0;
pub const ISO_10_BAND_FREQS: [f64; 10] = [
    31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
];

/// Direct Form II Transposed Second-Order RBJ Biquad IIR Filter
/// Difference equations:
///   y[n] = b_0 * x[n] + s_1[n - 1]
///   s_1[n] = b_1 * x[n] - a_1 * y[n] + s_2[n - 1]
///   s_2[n] = b_2 * x[n] - a_2 * y[n]
#[derive(Clone, Copy)]
pub struct BiquadFilter {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
    s1_l: f64,
    s2_l: f64,
    s1_r: f64,
    s2_r: f64,
}

impl Default for BiquadFilter {
    fn default() -> Self {
        Self {
            b0: 1.0,
            b1: 0.0,
            b2: 0.0,
            a1: 0.0,
            a2: 0.0,
            s1_l: 0.0,
            s2_l: 0.0,
            s1_r: 0.0,
            s2_r: 0.0,
        }
    }
}

impl BiquadFilter {
    pub fn set_normalized_coefficients(&mut self, b0: f64, b1: f64, b2: f64, a1: f64, a2: f64) {
        self.b0 = b0;
        self.b1 = b1;
        self.b2 = b2;
        self.a1 = a1;
        self.a2 = a2;
    }

    pub fn set_peaking_eq(&mut self, f0: f64, q: f64, gain_db: f64, fs: f64) {
        let clamped_db = gain_db.clamp(-10.0, 10.0);
        let a = 10.0_f64.powf(clamped_db / 40.0);
        let omega0 = 2.0 * PI * f0 / fs;
        let cos_w0 = omega0.cos();
        let sin_w0 = omega0.sin();
        let alpha = sin_w0 / (2.0 * q.max(0.1));

        let b0 = 1.0 + alpha * a;
        let b1 = -2.0 * cos_w0;
        let b2 = 1.0 - alpha * a;
        let a0 = 1.0 + alpha / a;
        let a1 = -2.0 * cos_w0;
        let a2 = 1.0 - alpha / a;

        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = a1 / a0;
        self.a2 = a2 / a0;
    }

    pub fn set_low_shelf(&mut self, fc: f64, q: f64, gain_db: f64, fs: f64) {
        let a = 10.0_f64.powf(gain_db / 40.0);
        let w0 = 2.0 * PI * fc / fs;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let alpha = sin_w0 / (2.0 * q.max(0.1));
        let two_sqrt_a_alpha = 2.0 * a.sqrt() * alpha;

        let b0 = a * ((a + 1.0) - (a - 1.0) * cos_w0 + two_sqrt_a_alpha);
        let b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cos_w0);
        let b2 = a * ((a + 1.0) - (a - 1.0) * cos_w0 - two_sqrt_a_alpha);
        let a0 = (a + 1.0) + (a - 1.0) * cos_w0 + two_sqrt_a_alpha;
        let a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cos_w0);
        let a2 = (a + 1.0) + (a - 1.0) * cos_w0 - two_sqrt_a_alpha;

        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = a1 / a0;
        self.a2 = a2 / a0;
    }

    pub fn set_high_shelf(&mut self, fc: f64, q: f64, gain_db: f64, fs: f64) {
        let a = 10.0_f64.powf(gain_db / 40.0);
        let w0 = 2.0 * PI * fc / fs;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let alpha = sin_w0 / (2.0 * q.max(0.1));
        let two_sqrt_a_alpha = 2.0 * a.sqrt() * alpha;

        let b0 = a * ((a + 1.0) + (a - 1.0) * cos_w0 + two_sqrt_a_alpha);
        let b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w0);
        let b2 = a * ((a + 1.0) + (a - 1.0) * cos_w0 - two_sqrt_a_alpha);
        let a0 = (a + 1.0) - (a - 1.0) * cos_w0 + two_sqrt_a_alpha;
        let a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cos_w0);
        let a2 = (a + 1.0) - (a - 1.0) * cos_w0 - two_sqrt_a_alpha;

        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = a1 / a0;
        self.a2 = a2 / a0;
    }

    pub fn set_high_pass(&mut self, fc: f64, q: f64, fs: f64) {
        let w0 = 2.0 * PI * fc / fs;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let alpha = sin_w0 / (2.0 * q.max(0.1));

        let b0 = (1.0 + cos_w0) * 0.5;
        let b1 = -(1.0 + cos_w0);
        let b2 = (1.0 + cos_w0) * 0.5;
        let a0 = 1.0 + alpha;
        let a1 = -2.0 * cos_w0;
        let a2 = 1.0 - alpha;

        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = a1 / a0;
        self.a2 = a2 / a0;
    }

    pub fn set_low_pass(&mut self, fc: f64, q: f64, fs: f64) {
        let w0 = 2.0 * PI * fc / fs;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let alpha = sin_w0 / (2.0 * q.max(0.1));

        let b0 = (1.0 - cos_w0) * 0.5;
        let b1 = 1.0 - cos_w0;
        let b2 = (1.0 - cos_w0) * 0.5;
        let a0 = 1.0 + alpha;
        let a1 = -2.0 * cos_w0;
        let a2 = 1.0 - alpha;

        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = a1 / a0;
        self.a2 = a2 / a0;
    }

    pub fn set_band_pass(&mut self, fc: f64, q: f64, fs: f64) {
        let w0 = 2.0 * PI * fc / fs;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let alpha = sin_w0 / (2.0 * q.max(0.1));

        let b0 = alpha;
        let b1 = 0.0;
        let b2 = -alpha;
        let a0 = 1.0 + alpha;
        let a1 = -2.0 * cos_w0;
        let a2 = 1.0 - alpha;

        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = a1 / a0;
        self.a2 = a2 / a0;
    }

    #[inline]
    pub fn process_left(&mut self, x: f64) -> f64 {
        let y = self.b0 * x + self.s1_l;
        self.s1_l = self.b1 * x - self.a1 * y + self.s2_l;
        self.s2_l = self.b2 * x - self.a2 * y;
        y
    }

    #[inline]
    pub fn process_right(&mut self, x: f64) -> f64 {
        let y = self.b0 * x + self.s1_r;
        self.s1_r = self.b1 * x - self.a1 * y + self.s2_r;
        self.s2_r = self.b2 * x - self.a2 * y;
        y
    }

    #[inline]
    pub fn process_stereo(&mut self, l: f64, r: f64) -> (f64, f64) {
        (self.process_left(l), self.process_right(r))
    }
}

/// 4-Second Circular Ring Buffer (`262,144` frames > 5.46s at 48 kHz) with:
/// 1. 4-Point Cubic Hermite Fractional Resampler & PI Clock-Drift Controller (`ratio` in `[0.9994, 1.0006]`)
/// 2. 30 ms Raised-Cosine Equal-Power Crossfade on Delay Target Changes (zero comb-filtering/glitches)
/// 3. 10 ms Exponential Gain Smoother for Proportional Master Volume * Per-Device Relative Gain
/// 4. Per-Device 20 Hz DC Blocker & -0.3 dBFS Soft Saturation Clipper
pub struct HermiteFractionalDelayBuffer {
    buffer_l: Vec<f32>,
    buffer_r: Vec<f32>,
    mask: usize,
    write_idx: usize,
    current_delay_samples: f64,
    prev_delay_samples: f64,
    target_delay_samples: f64,
    xfade_pos: usize,
    xfade_len: usize,
    drift_integral: f64,
    resample_ratio: f64,
    fractional_phase_offset: f64,
    current_gain: f64,
    target_gain: f64,
    gain_smooth_coeff: f64,
    dc_blocker: BiquadFilter,
}

impl HermiteFractionalDelayBuffer {
    pub fn new(capacity_power_of_two: usize) -> Self {
        let cap = capacity_power_of_two.next_power_of_two().max(262_144);
        let xfade_len = ((0.030 * SAMPLE_RATE).round() as usize).max(64);
        let gain_smooth_coeff = (-1.0 / (0.010 * SAMPLE_RATE)).exp();
        let mut dc_blocker = BiquadFilter::default();
        dc_blocker.set_high_pass(20.0, 0.70710678, SAMPLE_RATE);

        Self {
            buffer_l: vec![0.0; cap],
            buffer_r: vec![0.0; cap],
            mask: cap - 1,
            write_idx: 0,
            current_delay_samples: 1.0,
            prev_delay_samples: 1.0,
            target_delay_samples: 1.0,
            xfade_pos: xfade_len,
            xfade_len,
            drift_integral: 0.0,
            resample_ratio: 1.0,
            fractional_phase_offset: 0.0,
            current_gain: 1.0,
            target_gain: 1.0,
            gain_smooth_coeff,
            dc_blocker,
        }
    }

    pub fn set_delay_ms(&mut self, delay_ms: f64) {
        let clamped_ms = delay_ms.clamp(0.0, 1500.0);
        let new_target = ((clamped_ms * SAMPLE_RATE) / 1000.0).max(2.0);
        if (new_target - self.target_delay_samples).abs() > 12.0 {
            self.prev_delay_samples = self.current_delay_samples;
            self.target_delay_samples = new_target;
            self.xfade_pos = 0;
        } else {
            self.target_delay_samples = new_target;
        }
    }

    pub fn update_clock_drift_pi(&mut self, buffer_fill_error_frames: f64) {
        const KP: f64 = 1.2e-6;
        const KI: f64 = 1.5e-8;
        self.drift_integral = (self.drift_integral + buffer_fill_error_frames).clamp(-2000.0, 2000.0);
        let adj = KP * buffer_fill_error_frames + KI * self.drift_integral;
        self.resample_ratio = (1.0 + adj).clamp(0.9994, 1.0006);
    }

    pub fn set_effective_gain(&mut self, gain_scalar: f64) {
        self.target_gain = gain_scalar.clamp(0.0, 1.5);
    }

    pub fn process_interleaved(&mut self, input: &[f32], output: &mut [f32]) {
        const SOFT_CLIP_CEILING: f64 = 0.9660508789898134; // -0.3 dBFS
        let frames = input.len() / 2;

        for f in 0..frames {
            let in_l = input[f * 2];
            let in_r = input[f * 2 + 1];

            self.buffer_l[self.write_idx] = in_l;
            self.buffer_r[self.write_idx] = in_r;

            self.fractional_phase_offset =
                (self.fractional_phase_offset + (self.resample_ratio - 1.0)).clamp(-24.0, 24.0);

            let (raw_l, raw_r) = if self.xfade_pos < self.xfade_len {
                let phase = (PI * 0.5) * (self.xfade_pos as f64 / self.xfade_len as f64);
                let w_out = phase.cos() as f32;
                let w_in = phase.sin() as f32;

                let d_old = (self.prev_delay_samples + self.fractional_phase_offset).max(2.0);
                let d_new = (self.target_delay_samples + self.fractional_phase_offset).max(2.0);

                let (old_l, old_r) = self.read_hermite_stereo(d_old);
                let (new_l, new_r) = self.read_hermite_stereo(d_new);

                self.xfade_pos += 1;
                if self.xfade_pos >= self.xfade_len {
                    self.current_delay_samples = self.target_delay_samples;
                }
                (w_out * old_l + w_in * new_l, w_out * old_r + w_in * new_r)
            } else {
                let diff = self.target_delay_samples - self.current_delay_samples;
                self.current_delay_samples += diff * 0.002;
                let d_cur = (self.current_delay_samples + self.fractional_phase_offset).max(2.0);
                self.read_hermite_stereo(d_cur)
            };

            self.current_gain = self.gain_smooth_coeff * self.current_gain
                + (1.0 - self.gain_smooth_coeff) * self.target_gain;

            let scaled_l = (raw_l as f64) * self.current_gain;
            let scaled_r = (raw_r as f64) * self.current_gain;

            let (dc_l, dc_r) = self.dc_blocker.process_stereo(scaled_l, scaled_r);
            output[f * 2] = Self::soft_clip(dc_l, SOFT_CLIP_CEILING) as f32;
            output[f * 2 + 1] = Self::soft_clip(dc_r, SOFT_CLIP_CEILING) as f32;

            self.write_idx = (self.write_idx + 1) & self.mask;
        }
    }

    #[inline]
    fn read_hermite_stereo(&self, delay_samples: f64) -> (f32, f32) {
        let d_int = delay_samples.floor() as usize;
        let mu = (delay_samples - d_int as f64) as f32;

        let idx_m1 = self.write_idx.wrapping_sub(d_int + 1) & self.mask;
        let idx_0 = self.write_idx.wrapping_sub(d_int) & self.mask;
        let idx_p1 = self.write_idx.wrapping_sub(d_int.saturating_sub(1)) & self.mask;
        let idx_p2 = self.write_idx.wrapping_sub(d_int.saturating_sub(2)) & self.mask;

        let l = Self::hermite4(
            self.buffer_l[idx_m1],
            self.buffer_l[idx_0],
            self.buffer_l[idx_p1],
            self.buffer_l[idx_p2],
            mu,
        );
        let r = Self::hermite4(
            self.buffer_r[idx_m1],
            self.buffer_r[idx_0],
            self.buffer_r[idx_p1],
            self.buffer_r[idx_p2],
            mu,
        );
        (l, r)
    }

    #[inline]
    fn hermite4(xm1: f32, x0: f32, xp1: f32, xp2: f32, mu: f32) -> f32 {
        let c0 = x0;
        let c1 = 0.5 * (xp1 - xm1);
        let c2 = xm1 - 2.5 * x0 + 2.0 * xp1 - 0.5 * xp2;
        let c3 = 0.5 * (xp2 - xm1) + 1.5 * (x0 - xp1);
        c0 + mu * (c1 + mu * (c2 + mu * c3))
    }

    #[inline]
    fn soft_clip(x: f64, ceiling: f64) -> f64 {
        let abs_x = x.abs();
        if abs_x <= 0.85 * ceiling {
            x
        } else {
            let sign = x.signum();
            let norm = (abs_x - 0.85 * ceiling) / (0.15 * ceiling);
            sign * (0.85 * ceiling + 0.15 * ceiling * norm.tanh())
        }
    }
}

/// Complete 7-Stage DAX3 Psychoacoustic DSP Pipeline at 48 kHz:
/// Stage 1: Input Conditioning (-6.0 dB Digital Pre-Gain + 20 Hz Butterworth DC-Blocker)
/// Stage 2: 10-Band Cascaded RBJ Biquad Peaking EQ + Dynamic Intelligent EQ (IEQ) with
///          10 ms Fast Attack / 100 ms Slow Release & Adaptive Deviation Clamping Law
/// Stage 3: Psychoacoustic Bass Synthesizer ("Missing Fundamental" / Tartini pitch effect):
///          2nd-order Butterworth 120 Hz LPF -> Chebyshev T2(x), T3(x), T4(x) -> Steep HPF -> tanh blend
/// Stage 4: Dialog Enhancer (Mid/Side Formant Extraction 1.2 kHz - 3.8 kHz on M[n] only)
/// Stage 5: Spatial Virtualizer (Binaural Woodworth ITD/ILD/Pinna Notches 6.5-10.5 kHz OR
///          2x2 Inverted Transaural Crosstalk Cancellation Matrix v_L = (x_L - A(z)x_R)/(1 - A^2(z)))
/// Stage 6: ITU-R BS.1770 K-Weighted Volume Leveller (Exact Stage 1 & Stage 2 coefficients,
///          -50 dBFS Silence Gate, -18 LUFS Target)
/// Stage 7: True-Peak Lookahead Brickwall Limiter (2.5 ms lookahead, -0.1 dBFS ceiling, cubic release)
pub struct PsychoacousticDspPipeline {
    pub master_enabled: bool,
    pub surround_enabled: bool,
    pub volume_leveller_enabled: bool,
    pub is_headphone_route: bool,

    // Stage 1: 20 Hz DC Blocker
    dc_blocker: BiquadFilter,

    // Stage 2: 10-band cascaded biquad EQ + Dynamic IEQ sub-band energy tracker
    user_bands_db: [f64; 10],
    eq_bands: [BiquadFilter; 10],
    ieq_detector_bands: [BiquadFilter; 10],
    ieq_subband_energy: [f64; 10],
    ieq_target_energy: [f64; 10],
    ieq_alpha_attack: f64,
    ieq_alpha_release: f64,
    pultec_boost: BiquadFilter,
    pultec_cut: BiquadFilter,

    // Stage 3: Psychoacoustic Bass Synthesizer ("Missing Fundamental")
    sub_butterworth_lpf: BiquadFilter,
    sub_reject_fund_hp1: BiquadFilter,
    sub_reject_fund_hp2: BiquadFilter,
    sub_harmonic_upper_lp: BiquadFilter,

    // Stage 4: Dialog Enhancer (Mid/Side Formant Extraction 1.2-3.8 kHz)
    speech_formant_bp: BiquadFilter,
    dialog_mid_peak: BiquadFilter,
    speech_energy_env: f64,
    mid_broadband_energy_env: f64,

    // Stage 5: Spatial Virtualizer (Woodworth ITD + ILD + Pinna Notches 6.5-10.5 kHz + 2x2 Inverted XTC)
    pinna_notch_low: BiquadFilter,
    pinna_notch_high: BiquadFilter,
    ild_contra_shelf: BiquadFilter,
    xtc_head_shadow_lpf: BiquadFilter,
    xtc_hp: BiquadFilter,
    xtc_lp: BiquadFilter,
    side_delay_ring: Vec<f64>,
    itd_ring_l: Vec<f64>,
    itd_ring_r: Vec<f64>,
    xtc_delay_ring_l: Vec<f64>,
    xtc_delay_ring_r: Vec<f64>,
    virt_idx: usize,

    // Stage 6: ITU-R BS.1770 K-Weighted Volume Leveller (-50 dBFS Gate, -18 LUFS Target)
    k_pre_shelf: BiquadFilter,
    k_rlb_hp: BiquadFilter,
    lufs_mean_square_env: f64,
    agc_gain_linear: f64,

    // Stage 7: 2.5 ms True-Peak Lookahead Brickwall Limiter (-0.1 dBFS ceiling + cubic release)
    lookahead_l: Vec<f64>,
    lookahead_r: Vec<f64>,
    lookahead_idx: usize,
    limiter_env: f64,
    limiter_release_start_gain: f64,
    limiter_release_progress: f64,
}

impl PsychoacousticDspPipeline {
    pub fn new() -> Self {
        let mut dc_blocker = BiquadFilter::default();
        dc_blocker.set_high_pass(20.0, 0.70710678, SAMPLE_RATE);

        let mut eq_bands = [BiquadFilter::default(); 10];
        let mut ieq_detector_bands = [BiquadFilter::default(); 10];
        let mut ieq_target_energy = [1e-4; 10];
        let target_curve_db = [1.5, 2.0, 1.2, 0.5, 0.0, 0.8, 1.8, 2.5, 2.8, 2.2];
        for (i, &fc) in ISO_10_BAND_FREQS.iter().enumerate() {
            eq_bands[i].set_peaking_eq(fc, 1.414, 0.0, SAMPLE_RATE);
            ieq_detector_bands[i].set_band_pass(fc, 1.414, SAMPLE_RATE);
            ieq_target_energy[i] = 1e-4 * 10.0_f64.powf(target_curve_db[i] / 10.0);
        }

        // Dynamic IEQ asymmetric time constants (~10 ms fast attack, ~100 ms slow release)
        let ieq_alpha_attack = 1.0 - (-1.0 / (0.010 * SAMPLE_RATE)).exp();
        let ieq_alpha_release = 1.0 - (-1.0 / (0.100 * SAMPLE_RATE)).exp();

        let mut pultec_boost = BiquadFilter::default();
        pultec_boost.set_low_shelf(60.0, 0.70710678, 2.0, SAMPLE_RATE);
        let mut pultec_cut = BiquadFilter::default();
        pultec_cut.set_peaking_eq(100.0, 1.8, -1.4, SAMPLE_RATE);

        // Stage 3: 2nd-order Butterworth LPF at fc = 120 Hz + steep high-pass fundamental rejection
        let mut sub_butterworth_lpf = BiquadFilter::default();
        sub_butterworth_lpf.set_low_pass(120.0, 0.70710678, SAMPLE_RATE);
        let mut sub_reject_fund_hp1 = BiquadFilter::default();
        sub_reject_fund_hp1.set_high_pass(120.0, 0.70710678, SAMPLE_RATE);
        let mut sub_reject_fund_hp2 = BiquadFilter::default();
        sub_reject_fund_hp2.set_high_pass(120.0, 0.70710678, SAMPLE_RATE);
        let mut sub_harmonic_upper_lp = BiquadFilter::default();
        sub_harmonic_upper_lp.set_low_pass(260.0, 0.70710678, SAMPLE_RATE);

        // Stage 4: Dialog Enhancer (1.2 kHz - 3.8 kHz speech formant extraction on Mid channel)
        let mut speech_formant_bp = BiquadFilter::default();
        speech_formant_bp.set_band_pass(2135.0, 0.82, SAMPLE_RATE);
        let mut dialog_mid_peak = BiquadFilter::default();
        dialog_mid_peak.set_peaking_eq(2400.0, 0.85, 1.5, SAMPLE_RATE);

        // Stage 5: Outer-ear pinna elevation notches (6.5 kHz & 9.8 kHz) + ILD shelf (> 1.5 kHz) + XTC
        let mut pinna_notch_low = BiquadFilter::default();
        pinna_notch_low.set_peaking_eq(7200.0, 4.0, -4.5, SAMPLE_RATE);
        let mut pinna_notch_high = BiquadFilter::default();
        pinna_notch_high.set_peaking_eq(9800.0, 4.2, -3.8, SAMPLE_RATE);
        let mut ild_contra_shelf = BiquadFilter::default();
        ild_contra_shelf.set_high_shelf(1500.0, 0.70710678, -3.5, SAMPLE_RATE);

        let mut xtc_head_shadow_lpf = BiquadFilter::default();
        xtc_head_shadow_lpf.set_low_pass(2200.0, 0.70710678, SAMPLE_RATE);
        let mut xtc_hp = BiquadFilter::default();
        xtc_hp.set_high_pass(250.0, 0.70710678, SAMPLE_RATE);
        let mut xtc_lp = BiquadFilter::default();
        xtc_lp.set_low_pass(6000.0, 0.70710678, SAMPLE_RATE);

        // Stage 6: Exact ITU-R BS.1770 K-Weighting Filter Coefficients at 48 kHz
        let mut k_pre_shelf = BiquadFilter::default();
        k_pre_shelf.set_normalized_coefficients(
            1.53512485958697,
            -2.69169618940638,
            1.19839281085285,
            -1.69065929318241,
            0.73248077421585,
        );
        let mut k_rlb_hp = BiquadFilter::default();
        k_rlb_hp.set_normalized_coefficients(
            1.0,
            -2.0,
            1.0,
            -1.99004745483398,
            0.99007225034108,
        );

        let lookahead_samples = ((0.0025 * SAMPLE_RATE).round() as usize).max(120);

        let mut pipeline = Self {
            master_enabled: true,
            surround_enabled: true,
            volume_leveller_enabled: true,
            is_headphone_route: false,
            dc_blocker,
            user_bands_db: [0.0; 10],
            eq_bands,
            ieq_detector_bands,
            ieq_subband_energy: [1e-4; 10],
            ieq_target_energy,
            ieq_alpha_attack,
            ieq_alpha_release,
            pultec_boost,
            pultec_cut,
            sub_butterworth_lpf,
            sub_reject_fund_hp1,
            sub_reject_fund_hp2,
            sub_harmonic_upper_lp,
            speech_formant_bp,
            dialog_mid_peak,
            speech_energy_env: 1e-5,
            mid_broadband_energy_env: 1e-4,
            pinna_notch_low,
            pinna_notch_high,
            ild_contra_shelf,
            xtc_head_shadow_lpf,
            xtc_hp,
            xtc_lp,
            side_delay_ring: vec![0.0; 256],
            itd_ring_l: vec![0.0; 256],
            itd_ring_r: vec![0.0; 256],
            xtc_delay_ring_l: vec![0.0; 256],
            xtc_delay_ring_r: vec![0.0; 256],
            virt_idx: 0,
            k_pre_shelf,
            k_rlb_hp,
            lufs_mean_square_env: 1e-4,
            agc_gain_linear: 1.0,
            lookahead_l: vec![0.0; lookahead_samples],
            lookahead_r: vec![0.0; lookahead_samples],
            lookahead_idx: 0,
            limiter_env: 1.0,
            limiter_release_start_gain: 1.0,
            limiter_release_progress: 1.0,
        };
        pipeline.update_eq_bands(&[0.0, 0.0, 0.3, 0.8, 1.8, 3.2, 4.8, 6.2, 7.4, 8.0]);
        pipeline
    }

    pub fn update_eq_bands(&mut self, bands_db: &[f32]) {
        let base_target_db = [1.5, 2.0, 1.2, 0.5, 0.0, 0.8, 1.8, 2.5, 2.8, 2.2];
        for (i, &fc) in ISO_10_BAND_FREQS.iter().enumerate() {
            let db = bands_db.get(i).copied().unwrap_or(0.0).clamp(-10.0, 10.0) as f64;
            self.user_bands_db[i] = db;
            self.eq_bands[i].set_peaking_eq(fc, 1.414, db, SAMPLE_RATE);
            self.ieq_target_energy[i] = 1e-4 * 10.0_f64.powf((base_target_db[i] + 0.5 * db) / 10.0);
        }
    }

    /// Executes the 7-stage DAX3 psychoacoustic DSP pipeline on planar stereo buffers `left` and `right`.
    pub fn process_block(&mut self, left: &mut [f32], right: &mut [f32], num_samples: usize) {
        let count = num_samples.min(left.len()).min(right.len());
        let mut interleaved = vec![0.0f32; count * 2];
        for i in 0..count {
            interleaved[i * 2] = left[i];
            interleaved[i * 2 + 1] = right[i];
        }
        self.process_interleaved_buffer(&mut interleaved);
        for i in 0..count {
            left[i] = interleaved[i * 2];
            right[i] = interleaved[i * 2 + 1];
        }
    }

    /// Executes the 7-stage DAX3 psychoacoustic DSP pipeline in-place on interleaved stereo PCM float audio.
    pub fn process_interleaved_buffer(&mut self, buffer: &mut [f32]) {
        if !self.master_enabled {
            return;
        }

        // Stage 1: -6.0 dB Digital Pre-Gain Headroom
        const HEADROOM_GAIN: f64 = 0.5011872336272722; // 10^(-6/20)
        // Stage 7: -0.1 dBFS True-Peak Brickwall Ceiling
        const CEILING_LINEAR: f64 = 0.9885530946569389; // 10^(-0.1/20)

        // Woodworth spherical-head ITD model: ITD(theta) = (a / c) * (theta + sin(theta))
        // a = 0.0875 m, c = 343.0 m/s, theta = 60 deg (pi / 3) => ~482.5 us => ~23 samples at 48 kHz
        let woodworth_theta = PI / 3.0;
        let woodworth_itd_sec = (0.0875 / 343.0) * (woodworth_theta + woodworth_theta.sin());
        let woodworth_itd_samples = ((woodworth_itd_sec * SAMPLE_RATE).round() as usize).clamp(8, 32);

        // Transaural Crosstalk Cancellation contralateral delay tau = 220 us = 10.56 samples at 48 kHz
        let xtc_delay_samples = 10.56_f64;
        let xtc_d_int = xtc_delay_samples.floor() as usize;
        let xtc_mu = xtc_delay_samples - (xtc_d_int as f64);
        let xtc_alpha = 0.70_f64;

        let frames = buffer.len() / 2;
        let ring_mask = self.side_delay_ring.len() - 1;
        let haas_delay_samples = 18usize;
        let lookahead_len = self.lookahead_l.len();
        let release_step = 1.0 / (0.045 * SAMPLE_RATE);

        // Update Dynamic IEQ + User EQ coefficients once per block using adaptive clamping law:
        // G_b[n] = clamp(gamma * (10 * log10(Target_b) - 10 * log10(E_b[n] + eps)), -10.0, +10.0)
        const GAMMA_IEQ: f64 = 0.25;
        const EPSILON: f64 = 1e-12;
        for b in 0..10 {
            let target_db = 10.0 * (self.ieq_target_energy[b] + EPSILON).log10();
            let current_db = 10.0 * (self.ieq_subband_energy[b] + EPSILON).log10();
            let ieq_delta_db = (GAMMA_IEQ * (target_db - current_db)).clamp(-10.0, 10.0);
            let combined_db = (self.user_bands_db[b] + 0.35 * ieq_delta_db).clamp(-10.0, 10.0);
            self.eq_bands[b].set_peaking_eq(ISO_10_BAND_FREQS[b], 1.414, combined_db, SAMPLE_RATE);
        }

        // Update Dialog Enhancer Mid-channel formant boost based on vocal activity ratio rho_speech[n]
        let rho_speech = (self.speech_energy_env / (self.mid_broadband_energy_env + EPSILON)).clamp(0.0, 1.0);
        let dialog_boost_db = (3.6 * rho_speech).clamp(0.0, 4.5);
        self.dialog_mid_peak
            .set_peaking_eq(2400.0, 0.85, dialog_boost_db, SAMPLE_RATE);

        let bass_drive = 0.16 * (1.0 + (self.user_bands_db[0].max(0.0) + self.user_bands_db[1].max(0.0)) / 16.0);
        let k2 = 0.52 * bass_drive;
        let k3 = 0.34 * bass_drive;
        let k4 = 0.14 * bass_drive;

        for f in 0..frames {
            // =========================================================================
            // STAGE 1: -6.0 dB Pre-Gain Headroom + 20 Hz 2nd-Order Butterworth DC-Blocker
            // =========================================================================
            let raw_l = (buffer[f * 2] as f64) * HEADROOM_GAIN;
            let raw_r = (buffer[f * 2 + 1] as f64) * HEADROOM_GAIN;
            let (mut l, mut r) = self.dc_blocker.process_stereo(raw_l, raw_r);

            // =========================================================================
            // STAGE 2: 10-Band Cascaded RBJ Biquad Peaking EQ + Dynamic IEQ Tracking
            // =========================================================================
            let (pl, pr) = self.pultec_boost.process_stereo(l, r);
            let (cl, cr) = self.pultec_cut.process_stereo(pl, pr);
            l = cl;
            r = cr;

            let mono_in = 0.5 * (l + r);
            for b in 0..10 {
                let sub = self.ieq_detector_bands[b].process_left(mono_in);
                let inst_energy = sub * sub;
                let prev_e = self.ieq_subband_energy[b];
                let alpha = if inst_energy > prev_e {
                    self.ieq_alpha_attack
                } else {
                    self.ieq_alpha_release
                };
                self.ieq_subband_energy[b] = (1.0 - alpha) * prev_e + alpha * inst_energy;

                let (nl, nr) = self.eq_bands[b].process_stereo(l, r);
                l = nl;
                r = nr;
            }

            // =========================================================================
            // STAGE 3: Psychoacoustic Bass Synthesizer ("Missing Fundamental" / Tartini)
            // 2nd-order Butterworth 120 Hz LPF -> Chebyshev T2, T3, T4 -> Steep HPF -> tanh
            // =========================================================================
            let sub_mono = 0.5 * (l + r);
            let x_low = self.sub_butterworth_lpf.process_left(sub_mono);
            let x_norm = (x_low * 2.2).clamp(-1.0, 1.0);
            let x2 = x_norm * x_norm;
            // Exact Chebyshev polynomials with DC offset subtracted so silence maps to 0.0:
            // T_2(x) = 2x^2 - 1, T_3(x) = 4x^3 - 3x, T_4(x) = 8x^4 - 8x^2 + 1
            let t2 = (2.0 * x2 - 1.0) + 1.0;
            let t3 = 4.0 * x2 * x_norm - 3.0 * x_norm;
            let t4 = (8.0 * x2 * x2 - 8.0 * x2 + 1.0) - 1.0;
            let raw_harm = k2 * t2 + k3 * t3 + k4 * t4;
            let hp1 = self.sub_reject_fund_hp1.process_left(raw_harm);
            let hp2 = self.sub_reject_fund_hp2.process_left(hp1);
            let harm_band = self.sub_harmonic_upper_lp.process_left(hp2);
            let synth_bass = 0.35 * (harm_band * 1.8).tanh();
            l += synth_bass;
            r += synth_bass;

            // =========================================================================
            // STAGE 4: Dialog Enhancer (Mid/Side Formant Extraction 1.2 - 3.8 kHz)
            // M[n] = 0.5*(L[n] + R[n]), S[n] = 0.5*(L[n] - R[n]); S[n] untouched
            // =========================================================================
            let mid_in = 0.5 * (l + r);
            let side_in = 0.5 * (l - r);
            let speech_band = self.speech_formant_bp.process_left(mid_in);
            self.speech_energy_env =
                0.999 * self.speech_energy_env + 0.001 * (speech_band * speech_band);
            self.mid_broadband_energy_env =
                0.999 * self.mid_broadband_energy_env + 0.001 * (mid_in * mid_in + EPSILON);

            let mid_enhanced = self.dialog_mid_peak.process_left(mid_in);
            l = mid_enhanced + side_in;
            r = mid_enhanced - side_in;

            // =========================================================================
            // STAGE 5: Spatial Virtualizer (Binaural HRTF vs 2x2 Inverted Transaural XTC)
            // =========================================================================
            if self.surround_enabled {
                let mid = 0.5 * (l + r);
                let side = 0.5 * (l - r);

                self.side_delay_ring[self.virt_idx] = side;
                let delayed_side = self.side_delay_ring
                    [(self.virt_idx.wrapping_sub(haas_delay_samples)) & ring_mask];
                let pinna1 = self.pinna_notch_low.process_left(delayed_side);
                let notched_side = self.pinna_notch_high.process_left(pinna1);

                if self.is_headphone_route {
                    let wide_side = 1.32 * side + 0.36 * notched_side;
                    let bin_l = mid + wide_side;
                    let bin_r = mid - wide_side;

                    self.itd_ring_l[self.virt_idx] = bin_l;
                    self.itd_ring_r[self.virt_idx] = bin_r;
                    let itd_read = (self.virt_idx.wrapping_sub(woodworth_itd_samples)) & ring_mask;
                    let contra_r = self.ild_contra_shelf.process_left(self.itd_ring_r[itd_read]);
                    let contra_l = self.ild_contra_shelf.process_right(self.itd_ring_l[itd_read]);

                    l = bin_l + 0.16 * contra_r;
                    r = bin_r + 0.16 * contra_l;
                } else {
                    // Loudspeaker Mode: 2x2 Inverted Transaural Crosstalk Cancellation Matrix
                    // v_L = (x_L - A(z)*x_R) / (1 - A^2(z)), v_R = (x_R - A(z)*x_L) / (1 - A^2(z))
                    // Realized recursively: v_L[n] = x_L[n] - A(z)*v_R[n], v_R[n] = x_R[n] - A(z)*v_L[n]
                    let x_l = mid + (1.35 * side + 0.22 * notched_side);
                    let x_r = mid - (1.35 * side + 0.22 * notched_side);

                    let idx0 = (self.virt_idx.wrapping_sub(xtc_d_int)) & ring_mask;
                    let idx1 = (self.virt_idx.wrapping_sub(xtc_d_int + 1)) & ring_mask;
                    let delayed_v_r = (1.0 - xtc_mu) * self.xtc_delay_ring_r[idx0]
                        + xtc_mu * self.xtc_delay_ring_r[idx1];
                    let delayed_v_l = (1.0 - xtc_mu) * self.xtc_delay_ring_l[idx0]
                        + xtc_mu * self.xtc_delay_ring_l[idx1];

                    let (sh_r, sh_l) = self.xtc_head_shadow_lpf.process_stereo(delayed_v_r, delayed_v_l);
                    let (hp_r, hp_l) = self.xtc_hp.process_stereo(sh_r, sh_l);
                    let (a_vr, a_vl) = self.xtc_lp.process_stereo(hp_r, hp_l);

                    let g_reg = 0.45 * xtc_alpha; // Regularized α=0.7 contralateral attenuation
                    let v_l = x_l - g_reg * a_vr;
                    let v_r = x_r - g_reg * a_vl;

                    self.xtc_delay_ring_l[self.virt_idx] = v_l.clamp(-2.0, 2.0);
                    self.xtc_delay_ring_r[self.virt_idx] = v_r.clamp(-2.0, 2.0);

                    l = v_l;
                    r = v_r;
                }
                self.virt_idx = (self.virt_idx + 1) & ring_mask;
            }

            // =========================================================================
            // STAGE 6: ITU-R BS.1770 K-Weighted Volume Leveller (-50 dBFS Gate, -18 LUFS)
            // =========================================================================
            if self.volume_leveller_enabled {
                let (kl1, kr1) = self.k_pre_shelf.process_stereo(l, r);
                let (kl2, kr2) = self.k_rlb_hp.process_stereo(kl1, kr1);
                let power = 0.5 * (kl2 * kl2 + kr2 * kr2);

                self.lufs_mean_square_env =
                    0.9995 * self.lufs_mean_square_env + 0.0005 * power.max(EPSILON);
                let est_lufs = -0.691 + 10.0 * self.lufs_mean_square_env.log10();

                let target_agc_gain = if est_lufs > -50.0 {
                    // Target -18.0 LUFS reference loudness (+6 dB makeup for Stage 1 -6 dB headroom)
                    let delta_db = ((-18.0 - est_lufs) * 0.65 + 6.0).clamp(-6.0, 12.0);
                    10.0_f64.powf(delta_db / 20.0)
                } else {
                    // Below -50 dBFS silence-gating threshold: freeze noise-floor amplification
                    1.9952623149688795
                };

                self.agc_gain_linear = 0.9995 * self.agc_gain_linear + 0.0005 * target_agc_gain;
                l *= self.agc_gain_linear;
                r *= self.agc_gain_linear;
            } else {
                l *= 1.9952623149688795; // Restore -6 dB input headroom
                r *= 1.9952623149688795;
            }

            // =========================================================================
            // STAGE 7: 2.5 ms Lookahead True-Peak Brickwall Limiter (-0.1 dBFS, Cubic Release)
            // =========================================================================
            let peak = l.abs().max(r.abs());
            let desired_lim = if peak > CEILING_LINEAR {
                CEILING_LINEAR / peak
            } else {
                1.0
            };

            if desired_lim < self.limiter_env {
                self.limiter_env = 0.20 * self.limiter_env + 0.80 * desired_lim;
                self.limiter_release_start_gain = self.limiter_env;
                self.limiter_release_progress = 0.0;
            } else {
                self.limiter_release_progress = (self.limiter_release_progress + release_step).min(1.0);
                let u = self.limiter_release_progress;
                // Smooth cubic Hermite release polynomial S(u) = 3u^2 - 2u^3
                let cubic_env = u * u * (3.0 - 2.0 * u);
                self.limiter_env = self.limiter_release_start_gain
                    + (1.0 - self.limiter_release_start_gain) * cubic_env;
            }

            let out_l = self.lookahead_l[self.lookahead_idx] * self.limiter_env;
            let out_r = self.lookahead_r[self.lookahead_idx] * self.limiter_env;

            self.lookahead_l[self.lookahead_idx] = l;
            self.lookahead_r[self.lookahead_idx] = r;
            self.lookahead_idx = (self.lookahead_idx + 1) % lookahead_len;

            buffer[f * 2] = out_l.clamp(-CEILING_LINEAR, CEILING_LINEAR) as f32;
            buffer[f * 2 + 1] = out_r.clamp(-CEILING_LINEAR, CEILING_LINEAR) as f32;
        }
    }
}
