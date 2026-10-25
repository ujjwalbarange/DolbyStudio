#include "DelaySyncMatrix.h"

namespace dax_mobile {

void HermiteDelayRingBuffer::processBlock(
    const float* inL, const float* inR,
    float* outL, float* outR,
    int32_t numFrames
) noexcept {
    constexpr double kSlewRate = 0.0002; // 0.0002 samples/sample glitch-free slew

    for (int32_t n = 0; n < numFrames; ++n) {
        ringL_[writeIdx_] = inL[n];
        ringR_[writeIdx_] = inR[n];

        const double diff = targetDelaySamples_ - currentDelaySamples_;
        if (std::abs(diff) <= kSlewRate) {
            currentDelaySamples_ = targetDelaySamples_;
        } else {
            currentDelaySamples_ += (diff > 0.0) ? kSlewRate : -kSlewRate;
        }

        const double effDelay = std::max(1.0, currentDelaySamples_ * resampleRatio_);
        const int32_t dInt = static_cast<int32_t>(std::floor(effDelay));
        const float mu = static_cast<float>(effDelay - static_cast<double>(dInt));

        const int32_t idxM1 = (writeIdx_ - dInt - 1) & kBufferMask;
        const int32_t idx0  = (writeIdx_ - dInt) & kBufferMask;
        const int32_t idxP1 = (writeIdx_ - dInt + 1) & kBufferMask;
        const int32_t idxP2 = (writeIdx_ - dInt + 2) & kBufferMask;

        outL[n] = evalHermite4Pt(ringL_[idxM1], ringL_[idx0], ringL_[idxP1], ringL_[idxP2], mu);
        outR[n] = evalHermite4Pt(ringR_[idxM1], ringR_[idx0], ringR_[idxP1], ringR_[idxP2], mu);

        writeIdx_ = (writeIdx_ + 1) & kBufferMask;
    }
}

float DelaySyncMatrix::computeCompensationDelays(std::vector<SinkTimingNode>& activeNodes) noexcept {
    if (activeNodes.empty()) return 0.0f;

    float tauMax = 0.0f;
    for (const auto& node : activeNodes) {
        if (node.measuredNativeLatencyMs > tauMax) {
            tauMax = node.measuredNativeLatencyMs;
        }
    }

    for (auto& node : activeNodes) {
        node.computedDeltaDelayMs = std::max(0.0f, tauMax - node.measuredNativeLatencyMs + node.manualTrimMs);
        node.alignedTotalLatencyMs = node.measuredNativeLatencyMs + node.computedDeltaDelayMs;
    }
    return tauMax;
}

} // namespace dax_mobile
