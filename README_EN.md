# RealCUGAN Android SDK

[中文](README.md)

RealCUGAN Android SDK is an Android SDK and sample app for RealCUGAN image upscaling. It uses an ncnn + Vulkan native inference backend and provides a Kotlin-friendly API, cancellable long-running processing, explicit resource cleanup, and an optional foreground Service wrapper for background work.

The native inference path is based on nihui's Real-CUGAN ncnn/Vulkan implementation, with Android-specific handling for memory pressure, GPU memory, driver compatibility, and background execution.

## Features

- Runs RealCUGAN upscaling with ncnn + Vulkan. CUDA and PyTorch runtimes are not required.
- Mobile-oriented defaults with CPU and Vulkan GPU support.
- Validates `noise`, `scale`, `syncgap`, `gpuId`, and related options.
- `process()` supports progress callbacks and `cancel()`.
- Performs memory preflight checks before inference and throws `OutOfMemoryError` early when the task is clearly too large.
- Dynamically adjusts tile size to reduce GPU memory pressure for large images.
- Enables a safer path on risky Adreno drivers to avoid fp16-related black images or incorrect output.
- Serializes native/Vulkan create and release lifecycle calls globally to avoid Mali driver crashes during concurrent instance creation/destruction.
- Provides a foreground Service wrapper for background processing and notification progress.
- Provides Binder APIs: `process()`, `cancel()`, `configureConcurrency()`, and `dispose()`.

## Installation

Add the JitPack repository:

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Add the dependency:

```groovy
dependencies {
    implementation 'io.github.aoihoshino:realcugan-android-sdk:1.1'
}
```

## Basic Usage

Create an inference instance:

```kotlin
val opts = RealCUGANOption(
    context = this,
    noise = -1,
    scale = 2,
    syncgap = 3,
    modelName = ModelName.SE,
    ttaMode = false,
    gpuId = 0
)

val engine = RealCUGAN.create(opts)
```

Run processing:

```kotlin
val output = engine.process(inputBytes) { percent ->
    Log.d("RealCUGAN", "progress=$percent")
}

imageView.setImageBitmap(output)
```

Cancel an active process:

```kotlin
engine.cancel()
```

Release resources when done:

```kotlin
engine.release()
```

## Cancellation and Coroutines

`process()` observes coroutine cancellation. If the caller cancels the coroutine, the SDK forwards cancellation to the native layer:

```kotlin
val job = lifecycleScope.launch {
    val bitmap = engine.process(inputBytes)
}

job.cancel()
```

You can also cancel directly:

```kotlin
engine.cancel()
```

Successful cancellation throws `CancellationException`. Callers should usually treat it as a normal cancellation rather than a processing failure.

## Foreground Service

Use the built-in foreground Service wrapper if processing may continue in the background or if you need progress in the notification area.

Start the Service:

```kotlin
val intent = Intent(this, RealCUGANService::class.java)
    .putExtra(RealCUGANService.EXTRA_ENABLE_NOTIFICATION, true)
    .putExtra(RealCUGANService.EXTRA_MAX_CONCURRENT, 1)
    .putExtra(RealCUGANService.EXTRA_QUEUE_ENABLED, true)

ContextCompat.startForegroundService(this, intent)
```

Bind and submit work:

```kotlin
bindService(intent, conn, Context.BIND_AUTO_CREATE)

binder.process(bytes, "image.png", listener) { result ->
    result.onSuccess { bitmap ->
        imageView.setImageBitmap(bitmap)
    }
    result.onFailure { error ->
        Log.e("RealCUGAN", "processing failed", error)
    }
}
```

Cancel a task:

```kotlin
binder.cancel(taskId)
```

Dispose the Service:

```kotlin
binder.dispose(cancelRunning = true)
unbindService(conn)
stopService(intent)
```

## Memory and OOM

Android upscaling workloads use Java heap, Bitmap native pixel memory, native RGBA buffers, ncnn allocators, Vulkan memory, and driver caches. The reported memory footprint can be much larger than the input and output image sizes alone.

Before decoding and inference, the SDK estimates the input size, output size, and inference reserve. If the device does not have enough available memory, it throws `OutOfMemoryError` early:

```text
Not enough memory for RealCUGAN 1920x1080 -> 3840x2160. estimatedPeak=..., available=...
```

Important: if Android's Low Memory Killer has already killed the process, the app has no chance to throw a Java exception. The SDK preflight only rejects obviously oversized work earlier; it cannot catch a system-level kill.

In stress tests, if PSS reaches a high watermark, for example around 2GB, and then stays stable instead of growing linearly after every task, it usually means the native/GPU/driver working set has reached its peak. That is not necessarily a leak.

## Multiple Instances and Driver Compatibility

For production use, prefer reusing a single `RealCUGAN` instance. Avoid frequent create/release cycles and avoid holding multiple GPU instances at the same time.

Reasons:

- Multiple instances multiply model, pipeline, allocator, and GPU memory usage.
- Some Mali drivers can crash when `vkCreateComputePipelines` runs concurrently with Vulkan resource destruction, with errors such as `pthread_mutex_lock called on a destroyed mutex`.
- The SDK serializes `nativeInitialize` and `nativeRelease` globally to avoid this class of driver crash. This makes concurrent stress tests safer, but it does not make multi-instance usage the recommended pattern.

Some Qualcomm / Adreno drivers have fp16 or storage-buffer-related issues. The SDK checks risk flags exposed by ncnn and disables fp16-related options on risky Adreno devices to prioritize correct output.

Tile shrinking should still be kept: it addresses memory and GPU memory pressure. The Adreno safe mode addresses driver precision/storage-path issues. They solve different problems.

## Bitmap Ownership

The `Bitmap` returned by `process()` is owned by the caller. The SDK does not recycle a successfully returned output bitmap.

For batch processing, recycle the output after it has been saved or is no longer displayed:

```kotlin
val output = engine.process(bytes)
try {
    saveBitmap(output)
} finally {
    output.recycle()
}
```

If you set output bitmaps on an `ImageView`, also manage the previous bitmap when replacing it, otherwise old outputs can keep consuming native pixel memory.

## Sample App Stress Test

The sample app includes a stress-test entry point. By default it runs:

```text
4 clients x 20 loops x 3 images = 240 tasks
```

Each task performs:

```text
RealCUGAN.create() -> process() or cancel() -> release()
```

By default, every 7th task is cancelled. Cancellation is triggered 300ms after `process()` starts. Output bitmaps are recycled immediately by default, so caller-side bitmap retention is not mistaken for an SDK leak.

View logs:

```bash
adb logcat -s RealCUGANStress
```

Adjust stress parameters:

```bash
adb shell am start -n io.github.aoihoshino.realcugan_android_sdk/.MainActivity \
  --ei stress_clients 4 \
  --ei stress_loops 20 \
  --ei stress_instances 2 \
  --ei stress_cancel_every 7 \
  --el stress_cancel_delay_ms 300 \
  --ez stress_show_last false
```

Disable cancellation:

```bash
adb shell am start -n io.github.aoihoshino.realcugan_android_sdk/.MainActivity \
  --ei stress_cancel_every 0
```

The `MEM[...]` log lines print Java heap and PSS, which are useful for checking whether memory stabilizes after repeated work.

## Recommendations

- Prefer reusing one `RealCUGAN` instance in normal app flows.
- Keep default concurrency at 1; use a queue for batch work.
- Test large images on target devices before shipping.
- The caller is responsible for recycling output `Bitmap`s that are no longer needed.
- Call `release()` or `dispose(cancelRunning = true)` when processing is done.
- On low-memory devices, reduce scale, reduce input image size, or switch to CPU / more conservative settings.
