use std::f64::consts::PI;

pub const SAMPLE_RATE: f64 = 48000.0;
pub const ISO_10_BAND_FREQS: [f64; 10] = [
    31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
];

/// Direct Form II Transposed Second-Order Biquad IIR Filter
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

/// 4-Point Hermite Cubic Fractional Delay Circular Buffer
/// Implements x(n + mu) = c0 + c1*mu + c2*mu^2 + c3*mu^3 for sample-accurate multi-device sync.
pub struct HermiteFractionalDelayBuffer {
    buffer_l: Vec<f32>,
    buffer_r: Vec<f32>,
    mask: usize,
    write_idx: usize,
    current_delay_samples: f64,
    target_delay_samples: f64,
    slew_per_sample: f64,
}

impl HermiteFractionalDelayBuffer {
    pub fn new(capacity_power_of_two: usize) -> Self {
        let cap = capacity_power_of_two.next_power_of_two().max(32768);
        Self {
            buffer_l: vec![0.0; cap],
            buffer_r: vec![0.0; cap],
            mask: cap - 1,
            write_idx: 0,
            current_delay_samples: 1.0,
            target_delay_samples: 1.0,
            slew_per_sample: 0.0005,
        }
    }

    pub fn set_delay_ms(&mut self, delay_ms: f64) {
        let clamped_ms = delay_ms.clamp(0.0, 450.0);
        self.target_delay_samples = ((clamped_ms * SAMPLE_RATE) / 1000.0).max(1.0);
    }

    pub fn process_interleaved(&mut self, input: &[f32], output: &mut [f32]) {
        let frames = input.len() / 2;
        for f in 0..frames {
            let in_l = input[f * 2];
            let in_r = input[f * 2 + 1];

            self.buffer_l[self.write_idx] = in_l;
            self.buffer_r[self.write_idx] = in_r;

            let diff = self.target_delay_samples - self.current_delay_samples;
            if diff.abs() <= self.slew_per_sample {
                self.current_delay_samples = self.target_delay_samples;
            } else if diff > 0.0 {
                self.current_delay_samples += self.slew_per_sample;
            } else {
                self.current_delay_samples -= self.slew_per_sample;
            }

            let d_int = self.current_delay_samples.floor() as usize;
            let mu = (self.current_delay_samples - d_int as f64) as f32;

            let idx_m1 = self.write_idx.wrapping_sub(d_int + 1) & self.mask;
            let idx_0 = self.write_idx.wrapping_sub(d_int) & self.mask;
            let idx_p1 = self.write_idx.wrapping_sub(d_int.saturating_sub(1)) & self.mask;
            let idx_p2 = self.write_idx.wrapping_sub(d_int.saturating_sub(2)) & self.mask;

            output[f * 2] = Self::hermite4(
                self.buffer_l[idx_m1],
                self.buffer_l[idx_0],
                self.buffer_l[idx_p1],
                self.buffer_l[idx_p2],
                mu,
            );
            output[f * 2 + 1] = Self::hermite4(
                self.buffer_r[idx_m1],
                self.buffer_r[idx_0],
                self.buffer_r[idx_p1],
                self.buffer_r[idx_p2],
                mu,
            );

            self.write_idx = (self.write_idx + 1) & self.mask;
        }
    }

    #[inline]
    fn hermite4(xm1: f32, x0: f32, xp1: f32, xp2: f32, mu: f32) -> f32 {
        let c0 = x0;
        let c1 = 0.5 * (xp1 - xm1);
        let c2 = xm1 - 2.5 * x0 + 2.0 * xp1 - 0.5 * xp2;
        let c3 = 0.5 * (xp2 - xm1) + 1.5 * (x0 - xp1);
        c0 + mu * (c1 + mu * (c2 + mu * c3))
    }
}

/// Complete 5-Stage Psychoacoustic DSP Engine
pub struct PsychoacousticDspPipeline {
    pub master_enabled: bool,
    pub surround_enabled: bool,
    pub volume_leveller_enabled: bool,
    pub is_headphone_route: bool,

    // Stage 2: 10-band cascaded biquad EQ + Pultec low-end shaper
    eq_bands: [BiquadFilter; 10],
    pultec_boost: BiquadFilter,
    pultec_cut: BiquadFilter,
    sub_harmonic_bp: BiquadFilter,

    // Stage 3: Surround Virtualizer (ITD delay + Pinna notch + Speaker crosstalk filter)
    pinna_notch: BiquadFilter,
    xtc_bandpass: BiquadFilter,
    side_delay_ring: Vec<f64>,
    xtc_delay_ring_l: Vec<f64>,
    xtc_delay_ring_r: Vec<f64>,
    virt_idx: usize,

    // Stage 4: K-Weighted LUFS Volume Leveller (ITU-R BS.1770)
    k_pre_shelf: BiquadFilter,
    k_rlb_hp: BiquadFilter,
    lufs_mean_square_env: f64,
    agc_gain_linear: f64,

    // Stage 5: 2.5ms True-Peak Lookahead Brickwall Limiter
    lookahead_l: Vec<f64>,
    lookahead_r: Vec<f64>,
    lookahead_idx: usize,
    limiter_env: f64,
}

impl PsychoacousticDspPipeline {
    pub fn new() -> Self {
        let mut eq_bands = [BiquadFilter::default(); 10];
        for (i, &fc) in ISO_10_BAND_FREQS.iter().enumerate() {
            eq_bands[i].set_peaking_eq(fc, 1.414, 0.0, SAMPLE_RATE);
        }

        let mut pultec_boost = BiquadFilter::default();
        pultec_boost.set_low_shelf(60.0, 0.707, 2.2, SAMPLE_RATE);

        let mut pultec_cut = BiquadFilter::default();
        pultec_cut.set_peaking_eq(100.0, 1.8, -1.5, SAMPLE_RATE);

        let mut sub_harmonic_bp = BiquadFilter::default();
        sub_harmonic_bp.set_band_pass(55.0, 2.0, SAMPLE_RATE);

        let mut pinna_notch = BiquadFilter::default();
        pinna_notch.set_peaking_eq(7500.0, 3.5, -4.5, SAMPLE_RATE);

        let mut xtc_bandpass = BiquadFilter::default();
        xtc_bandpass.set_band_pass(850.0, 0.85, SAMPLE_RATE);

        let mut k_pre_shelf = BiquadFilter::default();
        k_pre_shelf.set_high_shelf(1681.97, 0.7071, 4.0, SAMPLE_RATE);

        let mut k_rlb_hp = BiquadFilter::default();
        k_rlb_hp.set_high_pass(38.13, 0.5003, SAMPLE_RATE);

        let lookahead_samples = ((0.0025 * SAMPLE_RATE).round() as usize).max(16);

        let mut pipeline = Self {
            master_enabled: true,
            surround_enabled: true,
            volume_leveller_enabled: true,
            is_headphone_route: false,
            eq_bands,
            pultec_boost,
            pultec_cut,
            sub_harmonic_bp,
            pinna_notch,
            xtc_bandpass,
            side_delay_ring: vec![0.0; 256],
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
        // Default Brilliant Treble curve
        pipeline.update_eq_bands(&[0.0, 0.0, 0.3, 0.8, 1.8, 3.2, 4.8, 6.2, 7.4, 8.0]);
        pipeline
    }

    pub fn update_eq_bands(&mut self, bands_db: &[f32]) {
        for (i, &fc) in ISO_10_BAND_FREQS.iter().enumerate() {
            let db = bands_db.get(i).copied().unwrap_or(0.0).clamp(-10.0, 10.0) as f64;
            self.eq_bands[i].set_peaking_eq(fc, 1.414, db, SAMPLE_RATE);
        }
    }

    /// Processes an interleaved stereo 32-bit float PCM buffer in-place through all 5 stages.
    pub fn process_interleaved_buffer(&mut self, buffer: &mut [f32]) {
        if !self.master_enabled {
            return;
        }

        // Stage 1: -6.0 dB Input Headroom Attenuation
        const HEADROOM_GAIN: f64 = 0.5011872336272722; // 10^(-6/20)
        // -0.1 dBFS True-Peak Brickwall Ceiling
        const CEILING_LINEAR: f64 = 0.9885530946569389; // 10^(-0.1/20)

        let frames = buffer.len() / 2;
        let ring_mask = self.side_delay_ring.len() - 1;
        let haas_delay_samples = 18usize; // ~0.375 ms ITD
        let xtc_delay_samples = 11usize; // ~0.22 ms speaker crosstalk delay
        let lookahead_len = self.lookahead_l.len();

        for f in 0..frames {
            let mut l = (buffer[f * 2] as f64) * HEADROOM_GAIN;
            let mut r = (buffer[f * 2 + 1] as f64) * HEADROOM_GAIN;

            // Stage 2: Dynamic 10-Band Cascaded Biquad Equalizer + Pultec Low-End Shaper
            for band in self.eq_bands.iter_mut() {
                let (nl, nr) = band.process_stereo(l, r);
                l = nl;
                r = nr;
            }
            let (pl, pr) = self.pultec_boost.process_stereo(l, r);
            let (cl, cr) = self.pultec_cut.process_stereo(pl, pr);
            let (sub_l, sub_r) = self.sub_harmonic_bp.process_stereo(cl, cr);
            l = cl + 0.08 * (2.5 * sub_l).tanh();
            r = cr + 0.08 * (2.5 * sub_r).tanh();

            // Stage 3: Surround Virtualizer (Mid/Side Matrix + Headphone ITD/Pinna or Speaker XTC)
            if self.surround_enabled {
                let mid = 0.5 * (l + r);
                let side = 0.5 * (l - r);

                self.side_delay_ring[self.virt_idx] = side;
                let delayed_side =
                    self.side_delay_ring[(self.virt_idx.wrapping_sub(haas_delay_samples)) & ring_mask];
                let notched_side = self.pinna_notch.process_left(delayed_side);

                if self.is_headphone_route {
                    let wide_side = 1.38 * side + 0.32 * notched_side;
                    l = mid + wide_side;
                    r = mid - wide_side;
                } else {
                    let (filt_l, filt_r) = self.xtc_bandpass.process_stereo(l, r);
                    self.xtc_delay_ring_l[self.virt_idx] = filt_l;
                    self.xtc_delay_ring_r[self.virt_idx] = filt_r;
                    let xtc_l = self.xtc_delay_ring_l
                        [(self.virt_idx.wrapping_sub(xtc_delay_samples)) & ring_mask];
                    let xtc_r = self.xtc_delay_ring_r
                        [(self.virt_idx.wrapping_sub(xtc_delay_samples)) & ring_mask];

                    let wide_side = 1.45 * side + 0.25 * notched_side;
                    l = (mid + wide_side) - 0.24 * xtc_r;
                    r = (mid - wide_side) - 0.24 * xtc_l;
                }
                self.virt_idx = (self.virt_idx + 1) & ring_mask;
            }

            // Stage 4: K-Weighted LUFS Volume Leveller (ITU-R BS.1770 / EBU R128)
            if self.volume_leveller_enabled {
                let (kl1, kr1) = self.k_pre_shelf.process_stereo(l, r);
                let (kl2, kr2) = self.k_rlb_hp.process_stereo(kl1, kr1);
                let power = 0.5 * (kl2 * kl2 + kr2 * kr2);

                self.lufs_mean_square_env =
                    0.9997 * self.lufs_mean_square_env + 0.0003 * power.max(1e-12);
                let est_lufs = -0.691 + 10.0 * self.lufs_mean_square_env.log10();

                // Silence gate at -50 dBFS to prevent noise floor pumping
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
                // Makeup gain for -6 dB headroom when volume leveller is off
                l *= 1.85;
                r *= 1.85;
            }

            // Stage 5: 2.5 ms Lookahead True-Peak Brickwall Limiter (-0.1 dBFS ceiling)
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
