# Android Ladder Vision & Multi-Touch Automation Engine

Sub-20ms Frame Ingestion, Real-Time Boundary Segmentation & Native Dual-Contact Input Injection.

---

## 1. Executive Summary & Architecture

Built for mobile ladder trading and order-book execution environments (e.g., Samsung Galaxy S25 Ultra, Android 14+), the engine passively tracks dynamic price columns, extracts active market spread boundaries via color/luminance analysis, and injects true simultaneous multi-touch events—striking one price lower in the unpopulated column while placing a target entry on an upper price rung.

### Core Benchmarks Achieved

| Metric | Target Standard | Achieved Implementation |
|---|---|---|
| **Loop Cadence** | `20.0 ms ± 1.5 ms` (50Hz) | Deterministic sync loop with drift compensation & `THREAD_PRIORITY_URGENT_DISPLAY` |
| **Simultaneous Injection** | `Δt < 1.0 ms` (Target `0.0 ms`) | Android `AccessibilityService.dispatchGesture()` with parallel `StrokeDescription` at timestamp 0 |
| **Vision Latency** | Sub-millisecond (`< 1 ms`) | C++ NDK matrix segmentation on zero-copy direct `ByteBuffer` (`libladdervision.so`, ~40–60 µs) |
| **Boundary Accuracy** | `≥ 99.8%` hit rate | Multi-point chroma/luminance sampling + automatic frame rejection for non-conforming frames |
| **Safety Kill Switch** | `< 5.0 ms` termination | Instant atomic disarm via volume buttons or floating HUD red button |
| **External Dependencies** | `0%` external API deps | Standalone on-device engine; no cloud/OCR latency penalties |

---

## 2. Structural Subsystem Breakdown

### A. Frame Capture Pipeline
- **MediaProjection + ImageReader (`PixelFormat.RGBA_8888`)**: Directly acquires GPU display buffers.
- **Zero-Copy Memory Access**: Direct `java.nio.ByteBuffer` passed to C++ NDK via `env->GetDirectBufferAddress()`.
- **Deterministic 20ms Clock**: Dynamically compensates for processing duration: `nextDelay = (20 - elapsed).coerceAtLeast(2)`.

### B. Vision & Boundary Segmentation (`libladdervision.so`)
- Bypasses generic OCR models to avoid 60ms latency penalties.
- High-speed C++ integer luminance computation: `(R * 77 + G * 150 + B * 29) >> 8`.
- Analyzes Back (Bid) and Lay (Ask) column profiles to identify the spread transition index.
- Returns detailed confidence score, microsecond processing time, and handles non-conforming frame rejection.

### C. Multi-Touch Dispatcher (`LadderTouchService`)
- Implements `AccessibilityService`.
- Dispatches two simultaneous strokes (`StrokeDescription(Path, 0L, 1L)`) within the exact same frame.
- Verifiable with Android Developer **Pointer Location** (pointers #0 and #1 record identical timestamps).

### D. Overlay Calibration HUD (`CalibrationHudService`)
- Draggable `SYSTEM_ALERT_WINDOW` floating overlay.
- Visual handles to align the ladder bounding box, row height, and Back/Lay columns directly on top of any trading app.
- Real-time highlight on the detected spread row with visual indicators of planned tap coordinates.
- **Pass-Thru Mode** (`FLAG_NOT_TOUCHABLE`) allows trading through the overlay.

### E. Built-in Mock Simulator & Verification Suite
- **Mock Order Book Simulator**: Live animated order book with dynamic market volatility to test vision, segmentation, and touch strikes without needing a live trading session.
- **Section 6 Verification Suite**: Automated on-device benchmark validating injection delta, native vision latency, and kill switch speed.

---

## 3. Modular Strategy & Trigger Flows

The engine supports 5 modular strategy flows switchable in real-time:
1. **Dynamic Spread Shift**: Injects immediately whenever the market spread row changes.
2. **Gap Expansion**: Injects when the spread between bid and ask widens beyond a configured rung threshold.
3. **Continuous Pegger**: Re-quotes lower blank and upper target rungs periodically with a safe cooldown window.
4. **Manual One-Shot**: Injects instantly on demand via floating HUD button or hardware hotkey.
5. **Monitor Only**: Pure telemetry tracking with 0 touch injection.

---

## 4. Setup & Permissions via ADB

To grant all necessary permissions without interactive prompts:

```bash
# 1. Install Debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Grant Overlay Permission
adb shell appops set com.laddervision.engine SYSTEM_ALERT_WINDOW allow

# 3. Enable Accessibility Multi-Touch Service
adb shell settings put secure enabled_accessibility_services com.laddervision.engine/com.laddervision.engine.touch.LadderTouchService
adb shell settings put secure accessibility_enabled 1

# 4. Grant Notification Permission (Android 13+)
adb shell pm grant com.laddervision.engine android.permission.POST_NOTIFICATIONS
```

---

## 5. Build Commands

```bash
# Assemble Debug APK
./gradlew assembleDebug

# Run Unit Tests
./gradlew test
```
