# RealCUGAN Android SDK

[English](README_EN.md)

RealCUGAN Android SDK 是一个面向 Android 的 RealCUGAN 图片超分 SDK 与示例应用。它基于 ncnn + Vulkan 的本地推理后端，提供 Kotlin 友好的调用接口、可取消的长任务处理、显式资源释放流程，以及适合后台任务的前台 Service 封装。

底层推理流程基于 nihui 的 Real-CUGAN ncnn/Vulkan 实现，并针对 Android 设备的内存、显存、驱动兼容性和后台任务场景做了封装。

## 功能特性

- 基于 ncnn + Vulkan 运行 RealCUGAN 超分，不依赖 CUDA 或 PyTorch 运行时。
- 面向移动端设备调整默认配置，支持 CPU 与 Vulkan GPU。
- 对 `noise`、`scale`、`syncgap`、`gpuId` 等参数做范围校验。
- `process()` 支持进度回调与 `cancel()` 中断。
- 推理前进行内存预检，提前抛出 `OutOfMemoryError`，降低系统直接杀进程的概率。
- 自动调整 tile 大小，降低大图显存压力。
- 针对部分 Adreno 驱动启用安全模式，避免 fp16 路径导致黑图或结果异常。
- 对 native / Vulkan 生命周期做全局串行化，避免部分 Mali 驱动在并发 create/release 时崩溃。
- 提供前台 Service 封装，可在后台处理任务并显示进度通知。
- 提供 Binder API：`process()`、`cancel()`、`configureConcurrency()`、`dispose()`。

## 安装

添加 JitPack 仓库：

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

添加依赖：

```groovy
dependencies {
    implementation 'io.github.aoihoshino:realcugan-android-sdk:1.1'
}
```

## 基础用法

创建推理实例：

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

执行处理：

```kotlin
val output = engine.process(inputBytes) { percent ->
    Log.d("RealCUGAN", "progress=$percent")
}

imageView.setImageBitmap(output)
```

取消正在运行的处理：

```kotlin
engine.cancel()
```

使用结束后释放资源：

```kotlin
engine.release()
```

## 取消与协程

`process()` 会监听协程取消。如果调用方取消所在协程，SDK 会转发到 native 层的 `cancel()`：

```kotlin
val job = lifecycleScope.launch {
    val bitmap = engine.process(inputBytes)
}

job.cancel()
```

也可以直接调用：

```kotlin
engine.cancel()
```

取消成功时会抛出 `CancellationException`。调用方通常应把它当作正常取消，不要当成普通失败上报。

## 前台 Service

如果任务可能在后台持续运行，或者需要在通知栏显示进度，可以使用内置的前台 Service 封装。

启动 Service：

```kotlin
val intent = Intent(this, RealCUGANService::class.java)
    .putExtra(RealCUGANService.EXTRA_ENABLE_NOTIFICATION, true)
    .putExtra(RealCUGANService.EXTRA_MAX_CONCURRENT, 1)
    .putExtra(RealCUGANService.EXTRA_QUEUE_ENABLED, true)

ContextCompat.startForegroundService(this, intent)
```

绑定并提交任务：

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

取消任务：

```kotlin
binder.cancel(taskId)
```

回收 Service：

```kotlin
binder.dispose(cancelRunning = true)
unbindService(conn)
stopService(intent)
```

## 内存与 OOM

Android 上的超分任务会同时占用 Java 堆、Bitmap native pixel 内存、native RGBA 缓冲区、ncnn allocator、Vulkan 显存和驱动缓存。内存数值看起来通常会比输入/输出图片本身大很多。

SDK 会在解码前根据输入尺寸、输出尺寸和推理 reserve 做预估。如果当前设备可用内存不足，会提前抛出 `OutOfMemoryError`：

```text
Not enough memory for RealCUGAN 1920x1080 -> 3840x2160. estimatedPeak=..., available=...
```

需要注意：如果系统 Low Memory Killer 已经直接杀掉进程，应用没有机会抛 Java 异常。SDK 的预检只能尽量提前拒绝明显过大的任务，不能捕获系统级 kill。

压测结果中如果 PSS 到达某个高水位后稳定，例如长期维持在约 2GB，而不是每轮持续线性上涨，通常表示 native/GPU/驱动缓存达到了工作集峰值，不一定是泄漏。

## 多实例与驱动兼容性

建议正常业务中复用一个 `RealCUGAN` 实例，不要频繁 new/release，也不要同时持有多个 GPU 实例。

原因：

- 多实例会放大模型、pipeline、allocator 和显存占用。
- 部分 Mali 驱动在并发 `vkCreateComputePipelines` 与 Vulkan 资源释放时会触发驱动级崩溃，例如 `pthread_mutex_lock called on a destroyed mutex`。
- SDK 已经对 `nativeInitialize` / `nativeRelease` 加了全局串行锁，避免 create/release 生命周期并发打穿驱动，但这不代表多实例是推荐用法。

部分 Qualcomm / Adreno 驱动存在 fp16 或 storage buffer 相关问题。SDK 会检测 ncnn 暴露的风险标记，并在高风险 Adreno 设备上禁用 fp16 相关选项，优先保证输出正确性。

tile 缩小仍然需要保留：它解决的是显存/内存压力；Adreno 安全模式解决的是驱动精度或存储路径问题，两者不是同一个问题。

## Bitmap 所有权

`process()` 返回的 `Bitmap` 由调用方持有。SDK 不会在成功返回后自动回收输出图。

批量处理时，如果输出图已经保存、不再显示，调用方应及时释放：

```kotlin
val output = engine.process(bytes)
try {
    saveBitmap(output)
} finally {
    output.recycle()
}
```

如果把输出图设置到 `ImageView`，替换下一张图前也应处理旧图的生命周期，避免输出图持续占用 native pixel 内存。

## 示例 App 压力测试

示例 app 内置了压力测试入口，默认启动后会运行：

```text
4 clients x 20 loops x 3 images = 240 tasks
```

每个任务都会：

```text
RealCUGAN.create() -> process() 或 cancel() -> release()
```

默认每 7 个任务夹带 1 个 cancel，启动 `process()` 后 300ms 调用 `cancel()`。输出 `Bitmap` 默认立即 `recycle()`，避免把调用方持有输出图误判为 SDK 泄漏。

查看日志：

```bash
adb logcat -s RealCUGANStress
```

调整参数：

```bash
adb shell am start -n io.github.aoihoshino.realcugan_android_sdk/.MainActivity \
  --ei stress_clients 4 \
  --ei stress_loops 20 \
  --ei stress_instances 2 \
  --ei stress_cancel_every 7 \
  --el stress_cancel_delay_ms 300 \
  --ez stress_show_last false
```

关闭 cancel：

```bash
adb shell am start -n io.github.aoihoshino.realcugan_android_sdk/.MainActivity \
  --ei stress_cancel_every 0
```

日志里的 `MEM[...]` 会打印 Java 堆与 PSS，适合观察内存是否在多轮任务后稳定。

## 使用建议

- 正常业务优先复用一个 `RealCUGAN` 实例。
- 默认并发建议为 1；批量任务用队列串行跑更稳定。
- 大图处理前先在目标设备上做内存测试。
- 调用方负责释放不再使用的输出 `Bitmap`。
- 处理结束后调用 `release()` 或 `dispose(cancelRunning = true)`。
- 低内存设备上优先降低 scale、减小输入图尺寸，或切换 CPU / 更保守的配置。
