use std::f64::consts::PI;

pub const SAMPLE_RATE: f64 = 48000.0;
pub const ISO_10_BAND_FREQS: [f64; 10] = [
    31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
];

/// Direct Form II Transposed Second-Order RBJ Biquad IIR Filter
#[derive(Clone, Copy)]
pub struct BiquadFilter {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
    z1_l: f64,
    z2_l: f64,
    z1_r: f64,
    z2_r: f64,
}

impl Default for BiquadFilter {
    fn default() -> Self {
        Self {
            b0: 1.0,
            b1: 0.0,
            b2: 0.0,
            a1: 0.0,
            a2: 0.0,
            z1_l: 0.0,
            z2_l: 0.0,
            z1_r: 0.0,
            z2_r: 0.0,
        }
    }
}

impl BiquadFilter {
    pub fn set_peaking_eq(&mut self, fc: f64, q: f64, gain_db: f64, fs: f64) {
        let a = 10.0_f64.powf(gain_db / 40.0);
        let w0 = 2.0 * PI * fc / fs;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
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

    pub fn set_all_pass(&mut self, fc: f64, q: f64, fs: f64) {
        let w0 = 2.0 * PI * fc / fs;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let alpha = sin_w0 / (2.0 * q.max(0.1));
        let a0 = 1.0 + alpha;

        self.b0 = (1.0 - alpha) / a0;
        self.b1 = (-2.0 * cos_w0) / a0;
        self.b2 = 1.0;
        self.a1 = self.b1;
        self.a2 = self.b0;
    }

    #[inline]
    pub fn process_left(&mut self, x: f64) -> f64 {
        let y = self.b0 * x + self.z1_l;
        self.z1_l = self.b1 * x - self.a1 * y + self.z2_l;
        self.z2_l = self.b2 * x - self.a2 * y;
        y
    }

    #[inline]
    pub fn process_right(&mut self, x: f64) -> f64 {
        let y = self.b0 * x + self.z1_r;
        self.z1_r = self.b1 * x - self.a1 * y + self.z2_r;
        self.z2_r = self.b2 * x - self.a2 * y;
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
    // PI Clock-Drift Controller state
    drift_integral: f64,
    resample_ratio: f64,
    fractional_phase_offset: f64,
    // 10ms Smoothed Effective Gain (V_master * G_relative)
    current_gain: f64,
    target_gain: f64,
    gain_smooth_coeff: f64,
    // Per-device 20 Hz DC Blocker
    dc_blocker: BiquadFilter,
}

impl HermiteFractionalDelayBuffer {
    pub fn new(capacity_power_of_two: usize) -> Self {
        // Minimum 262,144 stereo frames (~5.46 seconds at 48 kHz)
        let cap = capacity_power_of_two.next_power_of_two().max(262_144);
        let xfade_len = ((0.030 * SAMPLE_RATE).round() as usize).max(64); // 30 ms = 1440 samples
        let gain_smooth_coeff = (-1.0 / (0.010 * SAMPLE_RATE)).exp(); // 10 ms time constant
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

    /// Updates target delay in milliseconds. If the change exceeds 0.25 ms, triggers a
    /// smooth 30 ms raised-cosine equal-power crossfade between the old and new read pointers.
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

    /// Updates the PI clock-drift controller based on the device's current buffer fill error.
    pub fn update_clock_drift_pi(&mut self, buffer_fill_error_frames: f64) {
        const KP: f64 = 1.2e-6;
        const KI: f64 = 1.5e-8;
        self.drift_integral = (self.drift_integral + buffer_fill_error_frames).clamp(-2000.0, 2000.0);
        let adj = KP * buffer_fill_error_frames + KI * self.drift_integral;
        self.resample_ratio = (1.0 + adj).clamp(0.9994, 1.0006);
    }

    /// Sets the target effective gain `G_effective,i = V_master * G_relative,i` (or 0.0 if muted).
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

            // Smoothly advance PI clock-drift fractional phase offset
            self.fractional_phase_offset =
                (self.fractional_phase_offset + (self.resample_ratio - 1.0)).clamp(-24.0, 24.0);

            // Compute read sample via 30 ms Raised-Cosine Equal-Power Crossfade when delay changes
            let (raw_l, raw_r) = if self.xfade_pos < self.xfade_len {
                let phase =
                    (PI * 0.5) * (self.xfade_pos as f64 / self.xfade_len as f64);
                let w_out = phase.cos() as f32;
                let w_in = phase.sin() as f32;

                let d_old =
                    (self.prev_delay_samples + self.fractional_phase_offset).max(2.0);
                let d_new =
                    (self.target_delay_samples + self.fractional_phase_offset).max(2.0);

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
                let d_cur =
                    (self.current_delay_samples + self.fractional_phase_offset).max(2.0);
                self.read_hermite_stereo(d_cur)
            };

            // 10 ms exponential smoothing ramp for G_effective = V_master * G_relative
            self.current_gain = self.gain_smooth_coeff * self.current_gain
                + (1.0 - self.gain_smooth_coeff) * self.target_gain;

            let scaled_l = (raw_l as f64) * self.current_gain;
            let scaled_r = (raw_r as f64) * self.current_gain;

            // Per-device 20 Hz DC Blocker + -0.3 dBFS Soft Saturation Clipper
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
/// Stage 2: 10-Band Cascaded RBJ Biquad Graphic EQ + Pultec Low-End Shaper + Dynamic IEQ Tracking
/// Stage 3: Sub-Bass Harmonic Wave-Shaper (Missing Fundamental: 4th-order LR 120 Hz LPF,
///          Chebyshev T2(x), T3(x), T4(x), and 120 Hz Fundamental Rejection HPF)
/// Stage 4: Dialog Enhancer (Mid/Side Decomposition + 1.2 kHz - 3.8 kHz Speech Formant VAD Ratio +
///          Dynamic 2.4 kHz Mid-Channel Peaking Boost)
/// Stage 5: Surround Virtualizer (Headphone Woodworth ITD/ILD/Pinna/Haas OR
///          Loudspeaker Transaural Crosstalk Cancellation C(z) band-limited to 250 Hz - 6000 Hz)
/// Stage 6: Volume Leveller (ITU-R BS.1770-4 K-Weighting + -50 dBFS Silence-Gated AGC)
/// Stage 7: Lookahead True-Peak Brickwall Limiter (2.5 ms lookahead, -0.1 dBFS ceiling)
pub struct PsychoacousticDspPipeline {
    pub master_enabled: bool,
    pub surround_enabled: bool,
    pub volume_leveller_enabled: bool,
    pub is_headphone_route: bool,

    // Stage 1: 20 Hz DC Blocker
    dc_blocker: BiquadFilter,

    // Stage 2: 10-band cascaded biquad EQ + Dynamic IEQ sub-band energy tracker + Pultec shaper
    user_bands_db: [f64; 10],
    eq_bands: [BiquadFilter; 10],
    band_rms_energy: [f64; 10],
    ieq_detector_bp: BiquadFilter,
    ieq_dynamic_notch: BiquadFilter,
    ieq_harshness_env: f64,
    pultec_boost: BiquadFilter,
    pultec_cut: BiquadFilter,

    // Stage 3: Sub-Bass Harmonic Wave-Shaper (Missing Fundamental)
    sub_lr_lp1: BiquadFilter,
    sub_lr_lp2: BiquadFilter,
    sub_reject_fund_hp: BiquadFilter,
    sub_harmonic_upper_lp: BiquadFilter,

    // Stage 4: Dialog Enhancer (Formant Detector 1.2-3.8 kHz + Mid-Channel 2.4 kHz Boost)
    speech_formant_bp: BiquadFilter,
    dialog_mid_peak: BiquadFilter,
    speech_energy_env: f64,
    total_energy_env: f64,

    // Stage 5: Surround Virtualizer (Woodworth ITD + ILD + Pinna Notch + Speaker Transaural XTC)
    pinna_notch: BiquadFilter,
    ild_contra_shelf: BiquadFilter,
    xtc_hp: BiquadFilter,
    xtc_lp: BiquadFilter,
    side_delay_ring: Vec<f64>,
    itd_ring_l: Vec<f64>,
    itd_ring_r: Vec<f64>,
    xtc_delay_ring_l: Vec<f64>,
    xtc_delay_ring_r: Vec<f64>,
    virt_idx: usize,

    // Stage 6: K-Weighted LUFS Volume Leveller (ITU-R BS.1770-4)
    k_pre_shelf: BiquadFilter,
    k_rlb_hp: BiquadFilter,
    lufs_mean_square_env: f64,
    agc_gain_linear: f64,

    // Stage 7: 2.5ms True-Peak Lookahead Brickwall Limiter (-0.1 dBFS ceiling)
    lookahead_l: Vec<f64>,
    lookahead_r: Vec<f64>,
    lookahead_idx: usize,
    limiter_env: f64,
}

impl PsychoacousticDspPipeline {
    pub fn new() -> Self {
        let mut dc_blocker = BiquadFilter::default();
        dc_blocker.set_high_pass(20.0, 0.70710678, SAMPLE_RATE);

        let mut eq_bands = [BiquadFilter::default(); 10];
        for (i, &fc) in ISO_10_BAND_FREQS.iter().enumerate() {
            eq_bands[i].set_peaking_eq(fc, 1.41421356, 0.0, SAMPLE_RATE);
        }

        let mut ieq_detector_bp = BiquadFilter::default();
        ieq_detector_bp.set_band_pass(3600.0, 1.7, SAMPLE_RATE);

        let mut ieq_dynamic_notch = BiquadFilter::default();
        ieq_dynamic_notch.set_peaking_eq(3600.0, 2.2, 0.0, SAMPLE_RATE);

        let mut pultec_boost = BiquadFilter::default();
        pultec_boost.set_low_shelf(60.0, 0.70710678, 2.2, SAMPLE_RATE);

        let mut pultec_cut = BiquadFilter::default();
        pultec_cut.set_peaking_eq(100.0, 1.8, -1.5, SAMPLE_RATE);

        let mut sub_lr_lp1 = BiquadFilter::default();
        sub_lr_lp1.set_low_pass(120.0, 0.70710678, SAMPLE_RATE);
        let mut sub_lr_lp2 = BiquadFilter::default();
        sub_lr_lp2.set_low_pass(120.0, 0.70710678, SAMPLE_RATE);
        let mut sub_reject_fund_hp = BiquadFilter::default();
        sub_reject_fund_hp.set_high_pass(120.0, 0.70710678, SAMPLE_RATE);
        let mut sub_harmonic_upper_lp = BiquadFilter::default();
        sub_harmonic_upper_lp.set_low_pass(250.0, 0.70710678, SAMPLE_RATE);

        let mut speech_formant_bp = BiquadFilter::default();
        speech_formant_bp.set_band_pass(2400.0, 0.90, SAMPLE_RATE);
        let mut dialog_mid_peak = BiquadFilter::default();
        dialog_mid_peak.set_peaking_eq(2400.0, 0.85, 1.5, SAMPLE_RATE);

        let mut pinna_notch = BiquadFilter::default();
        pinna_notch.set_peaking_eq(7500.0, 3.5, -4.5, SAMPLE_RATE);
        let mut ild_contra_shelf = BiquadFilter::default();
        ild_contra_shelf.set_high_shelf(1800.0, 0.70710678, -3.2, SAMPLE_RATE);

        let mut xtc_hp = BiquadFilter::default();
        xtc_hp.set_high_pass(250.0, 0.70710678, SAMPLE_RATE);
        let mut xtc_lp = BiquadFilter::default();
        xtc_lp.set_low_pass(6000.0, 0.70710678, SAMPLE_RATE);

        let mut k_pre_shelf = BiquadFilter::default();
        k_pre_shelf.set_high_shelf(1681.974450955533, 0.7071752369554196, 3.999843853973347, SAMPLE_RATE);

        let mut k_rlb_hp = BiquadFilter::default();
        k_rlb_hp.set_high_pass(38.13547087602444, 0.5003270373238773, SAMPLE_RATE);

        let lookahead_samples = ((0.0025 * SAMPLE_RATE).round() as usize).max(16);

        let mut pipeline = Self {
            master_enabled: true,
            surround_enabled: true,
            volume_leveller_enabled: true,
            is_headphone_route: false,
            dc_blocker,
            user_bands_db: [0.0; 10],
            eq_bands,
            band_rms_energy: [1e-4; 10],
            ieq_detector_bp,
            ieq_dynamic_notch,
            ieq_harshness_env: 0.0,
            pultec_boost,
            pultec_cut,
            sub_lr_lp1,
            sub_lr_lp2,
            sub_reject_fund_hp,
            sub_harmonic_upper_lp,
            speech_formant_bp,
            dialog_mid_peak,
            speech_energy_env: 1e-5,
            total_energy_env: 1e-4,
            pinna_notch,
            ild_contra_shelf,
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
            lufs_mean_square_env: 1e-6,
            agc_gain_linear: 1.0,
            lookahead_l: vec![0.0; lookahead_samples],
            lookahead_r: vec![0.0; lookahead_samples],
            lookahead_idx: 0,
            limiter_env: 1.0,
        };
        pipeline.update_eq_bands(&[0.0, 0.0, 0.3, 0.8, 1.8, 3.2, 4.8, 6.2, 7.4, 8.0]);
        pipeline
    }

    pub fn update_eq_bands(&mut self, bands_db: &[f32]) {
        for (i, &fc) in ISO_10_BAND_FREQS.iter().enumerate() {
            let db = bands_db.get(i).copied().unwrap_or(0.0).clamp(-10.0, 10.0) as f64;
            self.user_bands_db[i] = db;
            self.eq_bands[i].set_peaking_eq(fc, 1.41421356, db, SAMPLE_RATE);
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
        const INV_SQRT2: f64 = 0.7071067811865475;

        let frames = buffer.len() / 2;
        let ring_mask = self.side_delay_ring.len() - 1;
        let haas_delay_samples = 18usize;
        let woodworth_itd_samples = 14usize; // ~0.30 ms spherical-head ITD
        let xtc_delay_samples = 11usize; // ~0.22 ms speaker crosstalk delay
        let lookahead_len = self.lookahead_l.len();

        // Update Dynamic IEQ harshness notch & Dialog Enhancer formant boost once per block
        let harsh_cut_db = if self.ieq_harshness_env > 0.08 {
            -((self.ieq_harshness_env - 0.08) * 14.0).clamp(0.0, 3.5)
        } else {
            0.0
        };
        self.ieq_dynamic_notch
            .set_peaking_eq(3600.0, 2.2, harsh_cut_db, SAMPLE_RATE);

        let rho_speech = (self.speech_energy_env / (self.total_energy_env + 1e-12)).clamp(0.0, 1.0);
        let dialog_boost_db = (2.8 * rho_speech).clamp(0.0, 4.5);
        self.dialog_mid_peak
            .set_peaking_eq(2400.0, 0.85, dialog_boost_db, SAMPLE_RATE);

        let bass_drive = 0.14 * (1.0 + (self.user_bands_db[0].max(0.0) + self.user_bands_db[1].max(0.0)) / 16.0);
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
            // STAGE 2: 10-Band Cascaded RBJ Biquad Graphic EQ + Dynamic IEQ + Pultec Shaper
            // =========================================================================
            let (pl, pr) = self.pultec_boost.process_stereo(l, r);
            let (cl, cr) = self.pultec_cut.process_stereo(pl, pr);
            l = cl;
            r = cr;

            for (b, band) in self.eq_bands.iter_mut().enumerate() {
                let (nl, nr) = band.process_stereo(l, r);
                let band_power = 0.5 * (nl * nl + nr * nr);
                self.band_rms_energy[b] = 0.999 * self.band_rms_energy[b] + 0.001 * band_power;
                l = nl;
                r = nr;
            }

            let (hl, hr) = self.ieq_detector_bp.process_stereo(l, r);
            let harsh_peak = hl.abs().max(hr.abs());
            self.ieq_harshness_env = 0.998 * self.ieq_harshness_env + 0.002 * harsh_peak;
            let (dl, dr) = self.ieq_dynamic_notch.process_stereo(l, r);
            l = dl;
            r = dr;

            // =========================================================================
            // STAGE 3: Sub-Bass Harmonic Wave-Shaper (Missing Fundamental)
            // 4th-order Linkwitz-Riley 120 Hz LPF -> Chebyshev T2, T3, T4 -> 120 Hz HPF
            // =========================================================================
            let sub_mono = 0.5 * (l + r);
            let x_lp1 = self.sub_lr_lp1.process_left(sub_mono);
            let x_lp = self.sub_lr_lp2.process_left(x_lp1);
            let x_norm = (x_lp * 2.2).clamp(-1.0, 1.0);
            let x2 = x_norm * x_norm;
            let t2 = 2.0 * x2;
            let t3 = 4.0 * x2 * x_norm - 3.0 * x_norm;
            let t4 = 8.0 * x2 * x2 - 8.0 * x2;
            let raw_harm = k2 * t2 + k3 * t3 + k4 * t4;
            let harm_hp = self.sub_reject_fund_hp.process_left(raw_harm);
            let harm_inject = self.sub_harmonic_upper_lp.process_left(harm_hp);
            l += harm_inject;
            r += harm_inject;

            // =========================================================================
            // STAGE 4: Dialog Enhancer (Mid/Side Decomposition + 2.4 kHz Formant Boost)
            // =========================================================================
            let mid_in = (l + r) * INV_SQRT2;
            let side_in = (l - r) * INV_SQRT2;
            let speech_comp = self.speech_formant_bp.process_left(mid_in);
            self.speech_energy_env =
                0.999 * self.speech_energy_env + 0.001 * (speech_comp * speech_comp);
            self.total_energy_env =
                0.999 * self.total_energy_env + 0.001 * (mid_in * mid_in + side_in * side_in + 1e-12);

            let mid_enhanced = self.dialog_mid_peak.process_left(mid_in);
            l = (mid_enhanced + side_in) * INV_SQRT2;
            r = (mid_enhanced - side_in) * INV_SQRT2;

            // =========================================================================
            // STAGE 5: Surround Virtualizer (Headphone Woodworth ITD/ILD vs Speaker XTC)
            // =========================================================================
            if self.surround_enabled {
                let mid = (l + r) * INV_SQRT2;
                let side = (l - r) * INV_SQRT2;

                self.side_delay_ring[self.virt_idx] = side;
                let delayed_side = self.side_delay_ring
                    [(self.virt_idx.wrapping_sub(haas_delay_samples)) & ring_mask];
                let notched_side = self.pinna_notch.process_left(delayed_side);

                if self.is_headphone_route {
                    let wide_side = 1.32 * side + 0.36 * notched_side;
                    let bin_l = (mid + wide_side) * INV_SQRT2;
                    let bin_r = (mid - wide_side) * INV_SQRT2;

                    self.itd_ring_l[self.virt_idx] = bin_l;
                    self.itd_ring_r[self.virt_idx] = bin_r;
                    let itd_read = (self.virt_idx.wrapping_sub(woodworth_itd_samples)) & ring_mask;
                    let contra_r = self.ild_contra_shelf.process_left(self.itd_ring_r[itd_read]);
                    let contra_l = self.ild_contra_shelf.process_right(self.itd_ring_l[itd_read]);

                    l = bin_l + 0.16 * contra_r;
                    r = bin_r + 0.16 * contra_l;
                } else {
                    // Loudspeaker Transaural Crosstalk Cancellation C(z) in [250 Hz, 6000 Hz]
                    let wide_l = (mid + (1.38 * side + 0.22 * notched_side)) * INV_SQRT2;
                    let wide_r = (mid - (1.38 * side + 0.22 * notched_side)) * INV_SQRT2;

                    let (hp_l, hp_r) = self.xtc_hp.process_stereo(wide_l, wide_r);
                    let (bp_l, bp_r) = self.xtc_lp.process_stereo(hp_l, hp_r);

                    let xtc_read = (self.virt_idx.wrapping_sub(xtc_delay_samples)) & ring_mask;
                    let cancel_from_r = self.xtc_delay_ring_r[xtc_read];
                    let cancel_from_l = self.xtc_delay_ring_l[xtc_read];

                    let g_xtc = 0.30;
                    l = wide_l - g_xtc * cancel_from_r;
                    r = wide_r - g_xtc * cancel_from_l;

                    self.xtc_delay_ring_l[self.virt_idx] = bp_l - g_xtc * 0.30 * cancel_from_r;
                    self.xtc_delay_ring_r[self.virt_idx] = bp_r - g_xtc * 0.30 * cancel_from_l;
                }
                self.virt_idx = (self.virt_idx + 1) & ring_mask;
            }

            // =========================================================================
            // STAGE 6: K-Weighted LUFS Volume Leveller (ITU-R BS.1770-4 + -50 dBFS Gate)
            // =========================================================================
            if self.volume_leveller_enabled {
                let (kl1, kr1) = self.k_pre_shelf.process_stereo(l, r);
                let (kl2, kr2) = self.k_rlb_hp.process_stereo(kl1, kr1);
                let power = 0.5 * (kl2 * kl2 + kr2 * kr2);

                self.lufs_mean_square_env =
                    0.9997 * self.lufs_mean_square_env + 0.0003 * power.max(1e-12);
                let est_lufs = -0.691 + 10.0 * self.lufs_mean_square_env.log10();

                let target_agc_gain = if est_lufs > -50.0 {
                    let delta_db = (-14.0 - est_lufs).clamp(-9.0, 9.0);
                    10.0_f64.powf(delta_db / 20.0)
                } else {
                    1.0
                };

                self.agc_gain_linear = 0.9995 * self.agc_gain_linear + 0.0005 * target_agc_gain;
                l *= self.agc_gain_linear;
                r *= self.agc_gain_linear;
            } else {
                l *= 1.85;
                r *= 1.85;
            }

            // =========================================================================
            // STAGE 7: 2.5 ms Lookahead True-Peak Brickwall Limiter (-0.1 dBFS ceiling)
            // =========================================================================
            let peak = l.abs().max(r.abs());
            let desired_lim = if peak > CEILING_LINEAR {
                CEILING_LINEAR / peak
            } else {
                1.0
            };
            if desired_lim < self.limiter_env {
                self.limiter_env = 0.25 * self.limiter_env + 0.75 * desired_lim;
            } else {
                self.limiter_env = 0.9992 * self.limiter_env + 0.0008 * desired_lim;
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
