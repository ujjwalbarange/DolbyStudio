#pragma once
#include <array>
#include <cmath>
#include <cstdint>
#include <algorithm>

namespace dax_mobile {

constexpr double kSampleRate = 48000.0;
constexpr double kPi = 3.14159265358979323846;

struct CytomicSvf {
    double a1 = 0.0, a2 = 0.0, a3 = 0.0, m1 = 0.0;
    double ic1L = 0.0, ic2L = 0.0, ic1R = 0.0, ic2R = 0.0;

    void setPeaking(double fc, double gainDb, double q = 1.41421356, double fs = kSampleRate) {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double g = std::tan(kPi * fc / fs);
        const double k = 1.0 / (q * A);
        a1 = 1.0 / (1.0 + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
        m1 = k * (A * A - 1.0);
    }

    inline double processL(double x) noexcept {
        const double v3 = x - ic2L;
        const double v1 = a1 * ic1L + a2 * v3;
        const double v2 = ic2L + a2 * ic1L + a3 * v3;
        ic1L = 2.0 * v1 - ic1L;
        ic2L = 2.0 * v2 - ic2L;
        return x + m1 * v1;
    }

    inline double processR(double x) noexcept {
        const double v3 = x - ic2R;
        const double v1 = a1 * ic1R + a2 * v3;
        const double v2 = ic2R + a2 * ic1R + a3 * v3;
        ic1R = 2.0 * v1 - ic1R;
        ic2R = 2.0 * v2 - ic2R;
        return x + m1 * v1;
    }
};

struct RbjBiquad {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
    double z1L = 0.0, z2L = 0.0, z1R = 0.0, z2R = 0.0;

    void setHighPass(double fc, double q = 0.70710678, double fs = kSampleRate);
    void setLowPass(double fc, double q = 0.70710678, double fs = kSampleRate);
    void setBandPass(double fc, double q = 1.0, double fs = kSampleRate);
    void setPeaking(double fc, double q, double gainDb, double fs = kSampleRate);
    void setLowShelf(double fc, double q, double gainDb, double fs = kSampleRate);
    void setHighShelf(double fc, double q, double gainDb, double fs = kSampleRate);
    void setAllPass(double fc, double q = 0.70710678, double fs = kSampleRate);

    inline double processL(double x) noexcept {
        const double y = b0 * x + z1L;
        z1L = b1 * x - a1 * y + z2L;
        z2L = b2 * x - a2 * y;
        return y;
    }

    inline double processR(double x) noexcept {
        const double y = b0 * x + z1R;
        z1R = b1 * x - a1 * y + z2R;
        z2R = b2 * x - a2 * y;
        return y;
    }
};

class DspEngine {
public:
    DspEngine();
    void setBandGainsDb(const std::array<float, 10>& gainsDb, const std::array<float, 10>& ieqOffsetsDb);
    void setProfileFlags(bool masterOn, bool surroundOn, bool volumeLevellerOn, bool headphoneRoute, float vadProb);
    void processAudioBlock(float* left, float* right, int32_t numSamples) noexcept;

private:
    bool masterEnabled_ = true;
    bool surroundEnabled_ = true;
    bool levellerEnabled_ = true;
    bool isHeadphoneRoute_ = false;
    double vadSpeechProb_ = 0.25;

    RbjBiquad dcBlocker_;
    RbjBiquad excursionHpf_;
    std::array<CytomicSvf, 10> svfBands_;
    RbjBiquad pultecLowShelf_;
    RbjBiquad pultecDip_;
    RbjBiquad subExtractLp_;
    RbjBiquad subExtractHp_;
    RbjBiquad harmonicBp_;
    RbjBiquad dialogBoost_;
    RbjBiquad pinnaNotch_;
    RbjBiquad earCanalPeak_;
    RbjBiquad haasAllPass_;
    RbjBiquad xtcLowPass_;
    RbjBiquad kwShelf_;
    RbjBiquad kwRlbHp_;

    std::array<double, 1024> haasRing_{};
    int32_t haasWriteIdx_ = 0;
    std::array<double, 32> xtcRingL_{};
    std::array<double, 32> xtcRingR_{};
    int32_t xtcWriteIdx_ = 0;

    double lufsPowerMean_ = 1e-5;
    double agcGain_ = 1.0;
    double limiterGain_ = 1.0;
};

} // namespace dax_mobile
