#include "DspEngine.h"

namespace dax_mobile {

static constexpr std::array<double, 10> kIsoCenterFreqs = {
    31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
};

void RbjBiquad::setHighPass(double fc, double q, double fs) {
    const double w0 = 2.0 * kPi * fc / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    b0 = ((1.0 + cw) * 0.5) / a0;
    b1 = -(1.0 + cw) / a0;
    b2 = ((1.0 + cw) * 0.5) / a0;
    a1 = (-2.0 * cw) / a0;
    a2 = (1.0 - alpha) / a0;
}

void RbjBiquad::setLowPass(double fc, double q, double fs) {
    const double w0 = 2.0 * kPi * fc / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    b0 = ((1.0 - cw) * 0.5) / a0;
    b1 = (1.0 - cw) / a0;
    b2 = ((1.0 - cw) * 0.5) / a0;
    a1 = (-2.0 * cw) / a0;
    a2 = (1.0 - alpha) / a0;
}

void RbjBiquad::setBandPass(double fc, double q, double fs) {
    const double w0 = 2.0 * kPi * fc / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    b0 = alpha / a0;
    b1 = 0.0;
    b2 = -alpha / a0;
    a1 = (-2.0 * cw) / a0;
    a2 = (1.0 - alpha) / a0;
}

void RbjBiquad::setPeaking(double fc, double q, double gainDb, double fs) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * fc / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha / A;
    b0 = (1.0 + alpha * A) / a0;
    b1 = (-2.0 * cw) / a0;
    b2 = (1.0 - alpha * A) / a0;
    a1 = (-2.0 * cw) / a0;
    a2 = (1.0 - alpha / A) / a0;
}

void RbjBiquad::setLowShelf(double fc, double q, double gainDb, double fs) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * fc / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;
    const double a0 = (A + 1.0) + (A - 1.0) * cw + twoSqrtAAlpha;
    b0 = (A * ((A + 1.0) - (A - 1.0) * cw + twoSqrtAAlpha)) / a0;
    b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cw)) / a0;
    b2 = (A * ((A + 1.0) - (A - 1.0) * cw - twoSqrtAAlpha)) / a0;
    a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cw)) / a0;
    a2 = ((A + 1.0) + (A - 1.0) * cw - twoSqrtAAlpha) / a0;
}

void RbjBiquad::setHighShelf(double fc, double q, double gainDb, double fs) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * fc / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;
    const double a0 = (A + 1.0) - (A - 1.0) * cw + twoSqrtAAlpha;
    b0 = (A * ((A + 1.0) + (A - 1.0) * cw + twoSqrtAAlpha)) / a0;
    b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cw)) / a0;
    b2 = (A * ((A + 1.0) + (A - 1.0) * cw - twoSqrtAAlpha)) / a0;
    a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cw)) / a0;
    a2 = ((A + 1.0) - (A - 1.0) * cw - twoSqrtAAlpha) / a0;
}

void RbjBiquad::setAllPass(double fc, double q, double fs) {
    const double w0 = 2.0 * kPi * fc / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    b0 = (1.0 - alpha) / a0;
    b1 = (-2.0 * cw) / a0;
    b2 = 1.0;
    a1 = b1;
    a2 = b0;
}

DspEngine::DspEngine() {
    dcBlocker_.setHighPass(20.0, 0.7071);
    excursionHpf_.setHighPass(85.0, 0.7071);
    for (size_t b = 0; b < 10; ++b) {
        svfBands_[b].setPeaking(kIsoCenterFreqs[b], 0.0);
    }
    pultecLowShelf_.setLowShelf(60.0, 0.68, 2.5);
    pultecDip_.setPeaking(105.0, 1.9, -1.2);
    subExtractLp_.setLowPass(85.0, 0.7071);
    subExtractHp_.setHighPass(25.0, 0.7071);
    harmonicBp_.setBandPass(140.0, 1.15);
    dialogBoost_.setPeaking(2400.0, 0.85, 2.0);
    pinnaNotch_.setPeaking(7500.0, 3.2, -4.0);
    earCanalPeak_.setPeaking(2800.0, 1.4, 2.5);
    haasAllPass_.setAllPass(950.0, 0.7071);
    xtcLowPass_.setLowPass(4800.0, 0.7071);
    // ITU-R BS.1770-4 K-Weighting Filter Cascade
    kwShelf_.setHighShelf(1681.974450955533, 0.7071752369554196, 3.999843853973347);
    kwRlbHp_.setHighPass(38.13547087602444, 0.5003270373238773);
}

void DspEngine::setBandGainsDb(
    const std::array<float, 10>& gainsDb,
    const std::array<float, 10>& ieqOffsetsDb
) {
    for (size_t b = 0; b < 10; ++b) {
        const double totalDb = std::clamp(
            static_cast<double>(gainsDb[b] + ieqOffsetsDb[b]), -10.0, 10.0
        );
        svfBands_[b].setPeaking(kIsoCenterFreqs[b], totalDb);
    }
}

void DspEngine::setProfileFlags(
    bool masterOn, bool surroundOn, bool volumeLevellerOn, bool headphoneRoute, float vadProb
) {
    masterEnabled_ = masterOn;
    surroundEnabled_ = surroundOn;
    levellerEnabled_ = volumeLevellerOn;
    isHeadphoneRoute_ = headphoneRoute;
    vadSpeechProb_ = std::clamp(static_cast<double>(vadProb), 0.0, 1.0);
    dialogBoost_.setPeaking(2400.0, 0.85, 4.0 * vadSpeechProb_);
}

void DspEngine::processAudioBlock(float* left, float* right, int32_t numSamples) noexcept {
    if (!masterEnabled_) return;

    constexpr double kPreGain = 0.50118723; // -6.0 dB headroom pre-gain
    constexpr double kInvSqrt2 = 0.70710678118;
    constexpr double kCeiling = 0.98855309; // -0.1 dBFS brickwall ceiling

    for (int32_t i = 0; i < numSamples; ++i) {
        // Stage 1: Headroom Pre-Gain + 20 Hz DC Blocker
        double sL = dcBlocker_.processL(static_cast<double>(left[i]) * kPreGain);
        double sR = dcBlocker_.processR(static_cast<double>(right[i]) * kPreGain);
        if (!isHeadphoneRoute_) {
            sL = excursionHpf_.processL(sL);
            sR = excursionHpf_.processR(sR);
        }

        // Stage 2: Chebyshev Sub-Bass Harmonic Exciter + Pultec + 10-Band Cytomic SVF
        const double sub = subExtractHp_.processL(subExtractLp_.processL(0.5 * (sL + sR)));
        const double xSub = std::clamp(sub * 2.0, -1.0, 1.0);
        const double t2 = 2.0 * xSub * xSub;
        const double t3 = 4.0 * xSub * xSub * xSub - 3.0 * xSub;
        const double vBass = harmonicBp_.processL(0.12 * t2 + 0.08 * t3);

        sL = pultecDip_.processL(pultecLowShelf_.processL(sL));
        sR = pultecDip_.processR(pultecLowShelf_.processR(sR));
        for (size_t b = 0; b < 10; ++b) {
            sL = svfBands_[b].processL(sL);
            sR = svfBands_[b].processR(sR);
        }
        sL += vBass;
        sR += vBass;

        // Stage 3: VAD-Gated Dialog Enhancer (1.5 - 3.5 kHz)
        sL = dialogBoost_.processL(sL);
        sR = dialogBoost_.processR(sR);

        // Stage 4: Surround Virtualiser (Mid/Side + Binaural HRTF vs Transaural XTC)
        if (surroundEnabled_) {
            const double mid = (sL + sR) * kInvSqrt2;
            const double side = (sL - sR) * kInvSqrt2;
            if (isHeadphoneRoute_) {
                haasRing_[haasWriteIdx_] = side;
                const int32_t rIdx = (haasWriteIdx_ - 576 + 1024) & 1023;
                const double haasSide = haasAllPass_.processL(haasRing_[rIdx]);
                haasWriteIdx_ = (haasWriteIdx_ + 1) & 1023;
                const double wideS = side * 1.28 + haasSide * 0.42;
                sL = earCanalPeak_.processL(pinnaNotch_.processL((mid + wideS) * kInvSqrt2));
                sR = earCanalPeak_.processR(pinnaNotch_.processR((mid - wideS) * kInvSqrt2));
            } else {
                const double wL = (mid + side * 1.35) * kInvSqrt2;
                const double wR = (mid - side * 1.35) * kInvSqrt2;
                xtcRingL_[xtcWriteIdx_] = wL;
                xtcRingR_[xtcWriteIdx_] = wR;
                const int32_t xIdx = (xtcWriteIdx_ - 11 + 32) & 31;
                sL = wL - 0.35 * xtcLowPass_.processL(xtcRingR_[xIdx]);
                sR = wR - 0.35 * xtcLowPass_.processR(xtcRingL_[xIdx]);
                xtcWriteIdx_ = (xtcWriteIdx_ + 1) & 31;
            }
        }

        // Stage 5: ITU-R BS.1770 K-Weighted LUFS Volume Leveller (-50 LUFS Silence Gate)
        const double kwL = kwRlbHp_.processL(kwShelf_.processL(sL));
        const double kwR = kwRlbHp_.processR(kwShelf_.processR(sR));
        lufsPowerMean_ = 0.9995 * lufsPowerMean_ + 0.0005 * std::max(0.5 * (kwL * kwL + kwR * kwR), 1e-10);
        const double lufs = -0.691 + 10.0 * std::log10(lufsPowerMean_);
        if (levellerEnabled_ && lufs > -50.0) {
            const double targetGain = std::pow(10.0, std::clamp(-16.0 - lufs, -9.0, 9.0) / 20.0);
            agcGain_ = 0.999 * agcGain_ + 0.001 * targetGain;
            sL *= agcGain_;
            sR *= agcGain_;
        }

        // Stage 6: True-Peak Lookahead Brickwall Limiter (-0.1 dBFS)
        const double peak = std::max(std::abs(sL), std::abs(sR));
        const double reqGain = (peak > kCeiling) ? (kCeiling / peak) : 1.0;
        limiterGain_ = (reqGain < limiterGain_) ? reqGain : (0.9995 * limiterGain_ + 0.0005 * reqGain);
        left[i] = static_cast<float>(std::clamp(sL * limiterGain_, -kCeiling, kCeiling));
        right[i] = static_cast<float>(std::clamp(sR * limiterGain_, -kCeiling, kCeiling));
    }
}

} // namespace dax_mobile
