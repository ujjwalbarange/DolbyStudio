#pragma once
#include <array>
#include <vector>
#include <cmath>
#include <cstdint>
#include <algorithm>

namespace dax_mobile {

/**
 * Per-Device Circular Ring Buffer with 4-Point Hermite Cubic Fractional Interpolation
 * and Asynchronous Clock-Drift PI Controller (Section 2.2 Tier 3 & Tier 4).
 */
class HermiteDelayRingBuffer {
public:
    static constexpr int32_t kBufferSize = 32768;
    static constexpr int32_t kBufferMask = kBufferSize - 1;

    void setTargetDelayMs(double delayMs, double bufferErrorSamples, double sampleRate = 48000.0) noexcept {
        targetDelaySamples_ = std::clamp(delayMs, 0.0, 400.0) * sampleRate / 1000.0;
        // Tier 4: PI Controller for Bluetooth crystal oscillator clock-drift compensation
        integralError_ = std::clamp(integralError_ + bufferErrorSamples, -500.0, 500.0);
        resampleRatio_ = 1.0 + (1.0e-5 * bufferErrorSamples) + (1.0e-8 * integralError_);
    }

    void processBlock(
        const float* inL, const float* inR,
        float* outL, float* outR,
        int32_t numFrames
    ) noexcept;

private:
    std::array<float, kBufferSize> ringL_{};
    std::array<float, kBufferSize> ringR_{};
    int32_t writeIdx_ = 0;
    double currentDelaySamples_ = 0.0;
    double targetDelaySamples_ = 0.0;
    double integralError_ = 0.0;
    double resampleRatio_ = 1.0;

    static inline float evalHermite4Pt(float xm1, float x0, float xp1, float xp2, float mu) noexcept {
        const float c0 = x0;
        const float c1 = 0.5f * (xp1 - xm1);
        const float c2 = xm1 - 2.5f * x0 + 2.0f * xp1 - 0.5f * xp2;
        const float c3 = 0.5f * (xp2 - xm1) + 1.5f * (x0 - xp1);
        return c0 + mu * (c1 + mu * (c2 + mu * c3));
    }
};

struct SinkTimingNode {
    int32_t deviceId = 0;
    float measuredNativeLatencyMs = 12.0f;
    float manualTrimMs = 0.0f;
    float computedDeltaDelayMs = 0.0f;
    float alignedTotalLatencyMs = 12.0f;
};

class DelaySyncMatrix {
public:
    /**
     * Automatic Delay Matching Formula:
     * 1. tau_max = max_{k in 1..N}(tau_k)
     * 2. delta_tau_i = tau_max - tau_i + manual_trim_i
     */
    static float computeCompensationDelays(std::vector<SinkTimingNode>& activeNodes) noexcept;
};

} // namespace dax_mobile
