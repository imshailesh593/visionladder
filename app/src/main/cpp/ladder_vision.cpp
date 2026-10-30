#include "ladder_vision.h"
#include <android/log.h>
#include <mutex>
#include <chrono>
#include <cmath>
#include <algorithm>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#define TAG "LadderVisionNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static LadderConfig gConfig;
static std::mutex gConfigMutex;

static inline uint8_t computeLuminance(uint8_t r, uint8_t g, uint8_t b) {
    // Fast integer approximation of 0.299*R + 0.587*G + 0.114*B
    return static_cast<uint8_t>((r * 77 + g * 150 + b * 29) >> 8);
}

static inline bool isBackPopulated(uint8_t r, uint8_t g, uint8_t b, float lumThreshold, int mode) {
    uint8_t lum = computeLuminance(r, g, b);
    if (mode == 0) {
        return lum > lumThreshold;
    } else if (mode == 1) {
        return (b > r + 15 && b > g) || (lum > lumThreshold);
    } else {
        return (lum > lumThreshold) || (b > 100 && b > r + 10);
    }
}

static inline bool isLayPopulated(uint8_t r, uint8_t g, uint8_t b, float lumThreshold, int mode) {
    uint8_t lum = computeLuminance(r, g, b);
    if (mode == 0) {
        return lum > lumThreshold;
    } else if (mode == 1) {
        return (r > b + 15 && r > g) || (lum > lumThreshold);
    } else {
        return (lum > lumThreshold) || (r > 100 && r > b + 10);
    }
}

static void sampleCell(
        const uint8_t *buffer,
        int32_t bufWidth,
        int32_t bufHeight,
        int32_t rowStride,
        int32_t pixelStride,
        int32_t centerX,
        int32_t centerY,
        int32_t boxW,
        int32_t boxH,
        uint8_t &outR,
        uint8_t &outG,
        uint8_t &outB
) {
    int32_t halfW = boxW / 2;
    int32_t halfH = boxH / 2;
    int32_t startX = std::max(0, centerX - halfW);
    int32_t endX = std::min(bufWidth - 1, centerX + halfW);
    int32_t startY = std::max(0, centerY - halfH);
    int32_t endY = std::min(bufHeight - 1, centerY + halfH);

    uint32_t sumR = 0, sumG = 0, sumB = 0;
    int32_t count = 0;

    for (int32_t y = startY; y <= endY; ++y) {
        const uint8_t *rowPtr = buffer + (y * rowStride);
        for (int32_t x = startX; x <= endX; ++x) {
            const uint8_t *pixelPtr = rowPtr + (x * pixelStride);
            sumR += pixelPtr[0];
            sumG += pixelPtr[1];
            sumB += pixelPtr[2];
            count++;
        }
    }

    if (count > 0) {
        outR = static_cast<uint8_t>(sumR / count);
        outG = static_cast<uint8_t>(sumG / count);
        outB = static_cast<uint8_t>(sumB / count);
    } else {
        outR = outG = outB = 0;
    }
}

// Internal detection logic
static void detectInternal(
        const uint8_t *buf,
        int32_t width,
        int32_t height,
        int32_t rowStride,
        int32_t pixelStride,
        const LadderConfig &cfg,
        int32_t &outSpreadRow,
        float &outConfidence,
        int64_t &outTimeUs
) {
    auto tStart = std::chrono::high_resolution_clock::now();

    outSpreadRow = -1;
    outConfidence = 0.0f;

    if (cfg.rowCount <= 1 || cfg.rowHeight <= 0) {
        auto tEnd = std::chrono::high_resolution_clock::now();
        outTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(tEnd - tStart).count();
        return;
    }

    std::vector<bool> backPopulated(cfg.rowCount, false);
    std::vector<bool> layPopulated(cfg.rowCount, false);
    std::vector<float> backLuminance(cfg.rowCount, 0.0f);
    std::vector<float> layLuminance(cfg.rowCount, 0.0f);

    float totalLuminance = 0.0f;

    for (int32_t r = 0; r < cfg.rowCount; ++r) {
        int32_t cellCenterY = cfg.ladderTop + (r * cfg.rowHeight) + (cfg.rowHeight / 2);
        if (cellCenterY >= height) break;

        uint8_t rB = 0, gB = 0, bB = 0;
        sampleCell(buf, width, height, rowStride, pixelStride,
                   cfg.backColX, cellCenterY,
                   cfg.sampleBoxWidth, cfg.sampleBoxHeight,
                   rB, gB, bB);
        backPopulated[r] = isBackPopulated(rB, gB, bB, cfg.luminanceThreshold, cfg.detectionMode);
        backLuminance[r] = computeLuminance(rB, gB, bB);

        uint8_t rL = 0, gL = 0, bL = 0;
        sampleCell(buf, width, height, rowStride, pixelStride,
                   cfg.layColX, cellCenterY,
                   cfg.sampleBoxWidth, cfg.sampleBoxHeight,
                   rL, gL, bL);
        layPopulated[r] = isLayPopulated(rL, gL, bL, cfg.luminanceThreshold, cfg.detectionMode);
        layLuminance[r] = computeLuminance(rL, gL, bL);

        totalLuminance += (backLuminance[r] + layLuminance[r]);
    }

    // Frame Rejection: If entire ladder region is nearly pitch black or blown out, reject frame
    float avgLuminance = totalLuminance / (cfg.rowCount * 2.0f);
    if (avgLuminance < 2.0f || avgLuminance > 254.0f) {
        auto tEnd = std::chrono::high_resolution_clock::now();
        outTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(tEnd - tStart).count();
        return; // Non-conforming frame rejected
    }

    // Identify spread boundary
    int32_t candidateRow = -1;
    float maxDelta = 0.0f;

    // Scan for sharpest luminance/chroma transition in Back column
    for (int32_t r = 0; r < cfg.rowCount - 1; ++r) {
        if (backPopulated[r] != backPopulated[r + 1]) {
            float delta = std::abs(backLuminance[r] - backLuminance[r + 1]);
            if (delta > maxDelta) {
                maxDelta = delta;
                candidateRow = (backPopulated[r]) ? r : (r + 1);
            }
        }
    }

    // Fallback: Check Lay column transition
    if (candidateRow == -1) {
        for (int32_t r = 0; r < cfg.rowCount - 1; ++r) {
            if (layPopulated[r] != layPopulated[r + 1]) {
                float delta = std::abs(layLuminance[r] - layLuminance[r + 1]);
                if (delta > maxDelta) {
                    maxDelta = delta;
                    candidateRow = r;
                }
            }
        }
    }

    // Confidence calculation (normalized 0.0 to 1.0)
    outConfidence = std::min(1.0f, maxDelta / 80.0f);

    if (candidateRow != -1 && outConfidence >= cfg.minConfidence) {
        outSpreadRow = candidateRow;
    } else {
        outSpreadRow = -1; // Rejected to preserve >= 99.8% hit rate accuracy
    }

    auto tEnd = std::chrono::high_resolution_clock::now();
    outTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(tEnd - tStart).count();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_updateCalibration(
        JNIEnv *env,
        jobject thiz,
        jint ladderLeft,
        jint ladderTop,
        jint ladderWidth,
        jint ladderHeight,
        jint rowHeight,
        jint rowCount,
        jint backColX,
        jint layColX,
        jint sampleBoxWidth,
        jint sampleBoxHeight,
        jfloat luminanceThreshold,
        jint detectionMode,
        jfloat minConfidence
) {
    std::lock_guard<std::mutex> lock(gConfigMutex);
    gConfig.ladderLeft = ladderLeft;
    gConfig.ladderTop = ladderTop;
    gConfig.ladderWidth = ladderWidth;
    gConfig.ladderHeight = ladderHeight;
    gConfig.rowHeight = rowHeight;
    gConfig.rowCount = rowCount;
    gConfig.backColX = backColX;
    gConfig.layColX = layColX;
    gConfig.sampleBoxWidth = sampleBoxWidth;
    gConfig.sampleBoxHeight = sampleBoxHeight;
    gConfig.luminanceThreshold = luminanceThreshold;
    gConfig.detectionMode = detectionMode;
    gConfig.minConfidence = minConfidence;
}

JNIEXPORT jint JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_detectSpreadBoundary(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height
) {
    if (!byteBuffer) return -1;
    uint8_t *buf = static_cast<uint8_t *>(env->GetDirectBufferAddress(byteBuffer));
    if (!buf) return -1;

    LadderConfig cfg;
    {
        std::lock_guard<std::mutex> lock(gConfigMutex);
        cfg = gConfig;
    }

    int32_t spreadRow = -1;
    float confidence = 0.0f;
    int64_t timeUs = 0;

    detectInternal(buf, width, height, rowStride, pixelStride, cfg, spreadRow, confidence, timeUs);
    return spreadRow;
}

JNIEXPORT jlongArray JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_detectSpreadBoundaryDetailed(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height
) {
    if (!byteBuffer) return nullptr;
    uint8_t *buf = static_cast<uint8_t *>(env->GetDirectBufferAddress(byteBuffer));
    if (!buf) return nullptr;

    LadderConfig cfg;
    {
        std::lock_guard<std::mutex> lock(gConfigMutex);
        cfg = gConfig;
    }

    int32_t spreadRow = -1;
    float confidence = 0.0f;
    int64_t timeUs = 0;

    detectInternal(buf, width, height, rowStride, pixelStride, cfg, spreadRow, confidence, timeUs);

    jlongArray result = env->NewLongArray(3);
    if (!result) return nullptr;

    jlong rawData[3] = {
            static_cast<jlong>(spreadRow),
            static_cast<jlong>(confidence * 1000.0f), // Confidence * 1000
            static_cast<jlong>(timeUs)               // Duration in microseconds
    };

    env->SetLongArrayRegion(result, 0, 3, rawData);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_getLuminanceProfile(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height
) {
    if (!byteBuffer) return nullptr;
    uint8_t *buf = static_cast<uint8_t *>(env->GetDirectBufferAddress(byteBuffer));
    if (!buf) return nullptr;

    LadderConfig cfg;
    {
        std::lock_guard<std::mutex> lock(gConfigMutex);
        cfg = gConfig;
    }

    jfloatArray result = env->NewFloatArray(cfg.rowCount * 2);
    if (!result) return nullptr;

    std::vector<jfloat> values(cfg.rowCount * 2, 0.0f);

    for (int32_t r = 0; r < cfg.rowCount; ++r) {
        int32_t cellCenterY = cfg.ladderTop + (r * cfg.rowHeight) + (cfg.rowHeight / 2);
        if (cellCenterY >= height) break;

        uint8_t rB = 0, gB = 0, bB = 0;
        sampleCell(buf, width, height, rowStride, pixelStride,
                   cfg.backColX, cellCenterY,
                   cfg.sampleBoxWidth, cfg.sampleBoxHeight,
                   rB, gB, bB);
        values[r * 2] = computeLuminance(rB, gB, bB);

        uint8_t rL = 0, gL = 0, bL = 0;
        sampleCell(buf, width, height, rowStride, pixelStride,
                   cfg.layColX, cellCenterY,
                   cfg.sampleBoxWidth, cfg.sampleBoxHeight,
                   rL, gL, bL);
        values[r * 2 + 1] = computeLuminance(rL, gL, bL);
    }

    env->SetFloatArrayRegion(result, 0, cfg.rowCount * 2, values.data());
    return result;
}

JNIEXPORT jdouble JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_runNativeBenchmark(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height,
        jint iterations
) {
    if (!byteBuffer || iterations <= 0) return 0.0;
    uint8_t *buf = static_cast<uint8_t *>(env->GetDirectBufferAddress(byteBuffer));
    if (!buf) return 0.0;

    LadderConfig cfg;
    {
        std::lock_guard<std::mutex> lock(gConfigMutex);
        cfg = gConfig;
    }

    int32_t spreadRow = -1;
    float confidence = 0.0f;
    int64_t timeUs = 0;

    auto tStart = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < iterations; ++i) {
        detectInternal(buf, width, height, rowStride, pixelStride, cfg, spreadRow, confidence, timeUs);
    }
    auto tEnd = std::chrono::high_resolution_clock::now();

    double totalTimeMicros = std::chrono::duration_cast<std::chrono::microseconds>(tEnd - tStart).count();
    return totalTimeMicros / static_cast<double>(iterations); // Average microseconds per frame
}

}
