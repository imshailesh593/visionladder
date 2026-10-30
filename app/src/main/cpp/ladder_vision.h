#ifndef LADDER_VISION_H
#define LADDER_VISION_H

#include <jni.h>
#include <cstdint>
#include <vector>

#ifdef __cplusplus
extern "C" {
#endif

struct LadderConfig {
    int32_t ladderLeft = 0;
    int32_t ladderTop = 0;
    int32_t ladderWidth = 1080;
    int32_t ladderHeight = 1920;
    int32_t rowHeight = 48;
    int32_t rowCount = 20;
    int32_t backColX = 360;
    int32_t layColX = 720;
    int32_t sampleBoxWidth = 30;
    int32_t sampleBoxHeight = 16;
    float luminanceThreshold = 45.0f; // Blank vs Populated threshold
    int32_t detectionMode = 0;        // 0: Luminance, 1: Back/Lay Color Chroma, 2: Bet Angel Volume Text
    float minConfidence = 0.5f;       // Frame rejection threshold for >= 99.8% hit rate
};

// JNI exports
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
);

JNIEXPORT jint JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_detectSpreadBoundary(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height
);

JNIEXPORT jlongArray JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_detectSpreadBoundaryDetailed(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height
);

JNIEXPORT jintArray JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_detectBetAngelMedianAndStrikes(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height
);

JNIEXPORT jintArray JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_autoDetectBetAngelLadder(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height
);

JNIEXPORT jfloatArray JNICALL
Java_com_laddervision_engine_vision_NativeVisionCore_getLuminanceProfile(
        JNIEnv *env,
        jobject thiz,
        jobject byteBuffer,
        jint rowStride,
        jint pixelStride,
        jint width,
        jint height
);

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
);

#ifdef __cplusplus
}
#endif

#endif // LADDER_VISION_H
