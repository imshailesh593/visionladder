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
    return static_cast<uint8_t>((r * 77 + g * 150 + b * 29) >> 8);
}

// Counts dark text pixels inside cell (volume present in Bet Angel)
static int32_t countDarkPixelsInCell(
        const uint8_t *buffer,
        int32_t bufWidth,
        int32_t bufHeight,
        int32_t rowStride,
        int32_t pixelStride,
        int32_t centerX,
        int32_t centerY,
        int32_t boxW,
        int32_t boxH,
        uint8_t darkThreshold
) {
    int32_t halfW = boxW / 2;
    int32_t halfH = boxH / 2;
    int32_t startX = std::max(0, centerX - halfW);
    int32_t endX = std::min(bufWidth - 1, centerX + halfW);
    int32_t startY = std::max(0, centerY - halfH);
    int32_t endY = std::min(bufHeight - 1, centerY + halfH);

    int32_t darkCount = 0;
    for (int32_t y = startY; y <= endY; ++y) {
        const uint8_t *rowPtr = buffer + (y * rowStride);
        for (int32_t x = startX; x <= endX; ++x) {
            const uint8_t *pixelPtr = rowPtr + (x * pixelStride);
            uint8_t r = pixelPtr[0];
            uint8_t g = pixelPtr[1];
            uint8_t b = pixelPtr[2];
            // Black/dark text on light blue or pink canvas
            if (r < darkThreshold && g < darkThreshold && b < darkThreshold) {
                darkCount++;
            }
        }
    }
    return darkCount;
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

    if (cfg.rowCount <= 1 || cfg.rowHeight <= 0) return -1;

    // Scan for Bet Angel Best Back and Best Lay
    int32_t bestBackRow = -1;
    int32_t bestLayRow = -1;

    // Highest back price has lowest row index with back volume
    for (int32_t r = 0; r < cfg.rowCount; ++r) {
        int32_t cellY = cfg.ladderTop + (r * cfg.rowHeight) + (cfg.rowHeight / 2);
        if (cellY >= height) break;

        int32_t darkCount = countDarkPixelsInCell(buf, width, height, rowStride, pixelStride,
                                                  cfg.backColX, cellY,
                                                  cfg.sampleBoxWidth, cfg.sampleBoxHeight, 80);
        if (darkCount >= 10) {
            bestBackRow = r;
            break; // Found highest price with Back volume
        }
    }

    // Lowest lay price has highest row index with lay volume
    for (int32_t r = cfg.rowCount - 1; r >= 0; --r) {
        int32_t cellY = cfg.ladderTop + (r * cfg.rowHeight) + (cfg.rowHeight / 2);
        if (cellY >= height) continue;

        int32_t darkCount = countDarkPixelsInCell(buf, width, height, rowStride, pixelStride,
                                                  cfg.layColX, cellY,
                                                  cfg.sampleBoxWidth, cfg.sampleBoxHeight, 80);
        if (darkCount >= 10) {
            bestLayRow = r;
            break; // Found lowest price with Lay volume
        }
    }

    if (bestBackRow != -1 && bestLayRow != -1) {
        return (bestBackRow + bestLayRow) / 2; // Median spread row
    } else if (bestBackRow != -1) {
        return bestBackRow;
    } else {
        return bestLayRow;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_detectBetAngelMedianAndStrikes(
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

    auto tStart = std::chrono::high_resolution_clock::now();

    LadderConfig cfg;
    {
        std::lock_guard<std::mutex> lock(gConfigMutex);
        cfg = gConfig;
    }

    int32_t bestBackRow = -1;
    int32_t bestLayRow = -1;

    // Scan Column A from top (highest price odds down to lowest)
    // Find the FIRST row that contains volume/numbers in Column A.
    // Above this row, Column A is completely vacant.
    for (int32_t r = 0; r < cfg.rowCount; ++r) {
        int32_t cellY = cfg.ladderTop + (r * cfg.rowHeight) + (cfg.rowHeight / 2);
        if (cellY >= height) break;

        int32_t darkBack = countDarkPixelsInCell(buf, width, height, rowStride, pixelStride,
                                                 cfg.backColX, cellY,
                                                 cfg.sampleBoxWidth, cfg.sampleBoxHeight, 80);
        if (darkBack >= 10) {
            bestBackRow = r;
            break; // Found top-most row with Back volume
        }
    }

    // Scan Column B from bottom (lowest price odds up to highest)
    // Find the FIRST row that contains volume/numbers in Column B.
    // Below this row, Column B is completely vacant.
    for (int32_t r = cfg.rowCount - 1; r >= 0; --r) {
        int32_t cellY = cfg.ladderTop + (r * cfg.rowHeight) + (cfg.rowHeight / 2);
        if (cellY >= height) continue;

        int32_t darkLay = countDarkPixelsInCell(buf, width, height, rowStride, pixelStride,
                                                cfg.layColX, cellY,
                                                cfg.sampleBoxWidth, cfg.sampleBoxHeight, 80);
        if (darkLay >= 10) {
            bestLayRow = r;
            break; // Found bottom-most row with Lay volume
        }
    }

    // Exact Boundary Targeting:
    // - Column A: Vacant row where Column A ends from the top (immediately above bestBackRow).
    //   There are NO numbers above this row in Column A.
    // - Column B: Vacant row where Column B ends from the bottom (immediately below bestLayRow).
    //   There are NO numbers below this row in Column B.
    int32_t backStrikeRow = (bestBackRow > 0) ? (bestBackRow - 1) : 0;
    int32_t layStrikeRow = (bestLayRow != -1 && bestLayRow < cfg.rowCount - 1) ? (bestLayRow + 1) : (cfg.rowCount - 1);

    auto tEnd = std::chrono::high_resolution_clock::now();
    int32_t timeUs = static_cast<int32_t>(std::chrono::duration_cast<std::chrono::microseconds>(tEnd - tStart).count());

    jintArray result = env->NewIntArray(6);
    if (!result) return nullptr;

    jint raw[6] = {
            bestBackRow,       // Best Back (e.g. Row 8 with 857)
            bestLayRow,        // Best Lay (e.g. Row 7 with 755)
            backStrikeRow,     // Strike in Back col: above 857 (Row 7)
            layStrikeRow,      // Strike in Lay col: below 755 (Row 8)
            (bestBackRow != -1 && bestLayRow != -1) ? 100 : 50, // Confidence %
            timeUs             // Processing time in microseconds (<100 µs)
    };

    env->SetIntArrayRegion(result, 0, 6, raw);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_autoDetectBetAngelLadder(
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

    // Detect Bet Angel royal blue price column using column-wise histogram:
    // Royal blue: R < 40, G < 40, B > 130
    std::vector<int32_t> colBlueCounts(width, 0);
    for (int32_t y = 0; y < height; y += 4) {
        const uint8_t *rowPtr = buf + (y * rowStride);
        for (int32_t x = 0; x < width; x += 4) {
            const uint8_t *p = rowPtr + (x * pixelStride);
            if (p[0] < 40 && p[1] < 40 && p[2] > 130) {
                colBlueCounts[x]++;
            }
        }
    }

    int32_t minX = width, maxX = 0;
    for (int32_t x = 0; x < width; x += 4) {
        if (colBlueCounts[x] > 35) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
        }
    }

    if (maxX <= minX) {
        return nullptr; // Could not detect Bet Angel price column automatically
    }

    int32_t minY = height, maxY = 0;
    for (int32_t y = 0; y < height; y += 4) {
        int32_t rowBlue = 0;
        const uint8_t *rowPtr = buf + (y * rowStride);
        for (int32_t x = minX; x <= maxX; x += 4) {
            const uint8_t *p = rowPtr + (x * pixelStride);
            if (p[0] < 40 && p[1] < 40 && p[2] > 130) {
                rowBlue++;
            }
        }
        if (rowBlue > 5) {
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
    }

    if (maxY <= minY) {
        return nullptr;
    }

    int32_t colWidth = maxX - minX + 1;
    int32_t priceColX = (minX + maxX) / 2;
    int32_t backColX = priceColX - colWidth;
    int32_t layColX = priceColX + colWidth;
    int32_t ladderTop = minY;
    int32_t ladderHeight = maxY - minY + 1;
    int32_t rowCount = 18;
    int32_t rowHeight = ladderHeight / rowCount;

    jintArray result = env->NewIntArray(8);
    if (!result) return nullptr;

    jint raw[8] = {
            backColX - (colWidth / 2), // ladderLeft
            ladderTop,
            colWidth * 3,              // ladderWidth
            ladderHeight,
            rowHeight,
            rowCount,
            backColX,
            layColX
    };

    env->SetIntArrayRegion(result, 0, 8, raw);
    return result;
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

    auto tStart = std::chrono::high_resolution_clock::now();

    LadderConfig cfg;
    {
        std::lock_guard<std::mutex> lock(gConfigMutex);
        cfg = gConfig;
    }

    int32_t bestBackRow = -1;
    int32_t bestLayRow = -1;

    for (int32_t r = 0; r < cfg.rowCount; ++r) {
        int32_t cellY = cfg.ladderTop + (r * cfg.rowHeight) + (cfg.rowHeight / 2);
        if (cellY >= height) break;

        int32_t darkBack = countDarkPixelsInCell(buf, width, height, rowStride, pixelStride,
                                                 cfg.backColX, cellY,
                                                 cfg.sampleBoxWidth, cfg.sampleBoxHeight, 80);
        if (bestBackRow == -1 && darkBack >= 10) {
            bestBackRow = r;
        }

        int32_t darkLay = countDarkPixelsInCell(buf, width, height, rowStride, pixelStride,
                                                cfg.layColX, cellY,
                                                cfg.sampleBoxWidth, cfg.sampleBoxHeight, 80);
        if (darkLay >= 10) {
            bestLayRow = r;
        }
    }

    int32_t medianRow = (bestBackRow != -1 && bestLayRow != -1) ? (bestBackRow + bestLayRow) / 2 :
                        (bestBackRow != -1 ? bestBackRow : bestLayRow);

    auto tEnd = std::chrono::high_resolution_clock::now();
    int64_t timeUs = std::chrono::duration_cast<std::chrono::microseconds>(tEnd - tStart).count();

    jlongArray result = env->NewLongArray(3);
    if (!result) return nullptr;

    jlong rawData[3] = {
            static_cast<jlong>(medianRow),
            static_cast<jlong>(medianRow != -1 ? 990 : 0),
            static_cast<jlong>(timeUs)
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

    auto tStart = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < iterations; ++i) {
        Java_com_laddervision_engine_vision_NativeVisionCore_detectSpreadBoundary(
                env, thiz, byteBuffer, rowStride, pixelStride, width, height
        );
    }
    auto tEnd = std::chrono::high_resolution_clock::now();

    double totalTimeMicros = std::chrono::duration_cast<std::chrono::microseconds>(tEnd - tStart).count();
    return totalTimeMicros / static_cast<double>(iterations);
}

}
