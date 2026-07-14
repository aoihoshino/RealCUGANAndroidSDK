package io.github.aoihoshino.realcugan_android_sdk

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.Keep
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

@Keep
fun interface ProgressListener {
    fun onProgress(percent: Float)
}

class RealCUGAN private constructor(
    private val nativeHandle: Long,
    private val scaleFactor: Int,
    context: Context
) {
    private val appContext = context.applicationContext
    private val cancelRequested = AtomicBoolean(false)

    private val gpuExecutor by lazy {
        Executors.newSingleThreadExecutor {
            Thread(it, "RealCUGAN-GPU").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
        }
    }

    // native 推理串行执行，避免同一个 RealCUGAN 实例并发放大显存/线程栈占用
    private val gpuDispatcherDelegate = lazy {
        gpuExecutor.asCoroutineDispatcher()
    }
    private val gpuDispatcher: ExecutorCoroutineDispatcher
        get() = gpuDispatcherDelegate.value

    /**
     * 对一段 PNG/JPEG/WebP 的字节做推理，返回 ARGB_8888 的 Bitmap。
     * 全流程：头部解码 → JNI 计算(切到 gpuDispatcher) → 拼装输出 Bitmap
     */
    suspend fun process(
        imageData: ByteArray, onProgressListener: ProgressListener? = null
    ): Bitmap = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancel()
            }
        }
        var inBmp: Bitmap? = null
        var outBmp: Bitmap? = null
        var success = false
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw RuntimeException("decode bitmap bounds failed")
            }
            checkMemoryBudget(bounds.outWidth, bounds.outHeight)

// 1) 解码为 SOFTWARE + ARGB_8888 + 可写的 Bitmap（避免硬件位图导致无法锁像素）
            inBmp = BitmapFactory.decodeByteArray(
                imageData, 0, imageData.size, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }) ?: throw RuntimeException("decode bitmap failed")
            if (cancelRequested.get()) {
                throw CancellationException("RealCUGAN process cancelled")
            }

            checkMemoryBudget(inBmp.width, inBmp.height)
            val outW = inBmp.width * scaleFactor
            val outH = inBmp.height * scaleFactor
            outBmp = createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            if (cancelRequested.get()) {
                throw CancellationException("RealCUGAN process cancelled")
            }

// 2) 真正跑 native 推理，只在 gpuDispatcher 线程池
            val ok = withContext(gpuDispatcher) {
                nativeProcessBitmap(nativeHandle, inBmp, outBmp, onProgressListener)
            }
            if (!ok) {
                if (nativeIsCancelled(nativeHandle)) {
                    throw CancellationException("RealCUGAN process cancelled")
                }
                throw RuntimeException("nativeProcessBitmap failed")
            }

            Log.i("RealCUGAN", "process(Bitmap path) → $outW×$outH ready")
            success = true
            outBmp
        } finally {
            inBmp?.recycle()
            if (!success) {
                outBmp?.recycle()
            }
            cancellationHandle?.dispose()
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        nativeCancel(nativeHandle)
    }

    fun release() {
        try {
            nativeLifecycleLock.withLock {
                nativeRelease(nativeHandle)
            }
        } finally {
            if (gpuDispatcherDelegate.isInitialized()) {
                gpuDispatcher.close()
            }
        }
    }

    private fun checkMemoryBudget(inputWidth: Int, inputHeight: Int) {
        val inPixels = checkedPixelCount(inputWidth, inputHeight)
        val outWidth = checkedScaledDimension(inputWidth)
        val outHeight = checkedScaledDimension(inputHeight)
        val outPixels = checkedPixelCount(outWidth, outHeight)

        val inBytes = checkedBytes(inPixels)
        val outBytes = checkedBytes(outPixels)

        // Peak resident memory has both Bitmap pixel stores and native tight RGBA buffers alive.
        val bitmapBytes = checkedAdd(inBytes, outBytes)
        val nativeTightBytes = checkedAdd(inBytes, outBytes)
        val inferenceReserveBytes = maxOf(outBytes / 2, MIN_INFERENCE_RESERVE_BYTES)
        val estimatedPeakBytes = checkedAdd(
            checkedAdd(bitmapBytes, nativeTightBytes),
            inferenceReserveBytes
        )

        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val availableForProcess = (memoryInfo.availMem - memoryInfo.threshold).coerceAtLeast(0L)

        if (memoryInfo.lowMemory || availableForProcess < estimatedPeakBytes) {
            throw OutOfMemoryError(
                "Not enough memory for RealCUGAN ${inputWidth}x$inputHeight -> " +
                        "${outWidth}x$outHeight. estimatedPeak=${formatBytes(estimatedPeakBytes)}, " +
                        "available=${formatBytes(availableForProcess)}"
            )
        }
    }

    private fun checkedScaledDimension(value: Int): Int {
        val scaled = value.toLong() * scaleFactor
        if (scaled <= 0L || scaled > Int.MAX_VALUE) {
            throw OutOfMemoryError("RealCUGAN output dimension is too large: $value x $scaleFactor")
        }
        return scaled.toInt()
    }

    private fun checkedPixelCount(width: Int, height: Int): Long {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= 0L || pixels > Long.MAX_VALUE / BYTES_PER_PIXEL) {
            throw OutOfMemoryError("RealCUGAN image is too large: ${width}x$height")
        }
        return pixels
    }

    private fun checkedBytes(pixels: Long): Long = checkedMultiply(pixels, BYTES_PER_PIXEL)

    private fun checkedMultiply(a: Long, b: Long): Long {
        if (a > Long.MAX_VALUE / b) throw OutOfMemoryError("RealCUGAN memory estimate overflow")
        return a * b
    }

    private fun checkedAdd(a: Long, b: Long): Long {
        if (Long.MAX_VALUE - a < b) throw OutOfMemoryError("RealCUGAN memory estimate overflow")
        return a + b
    }

    private fun formatBytes(bytes: Long): String =
        String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))

    companion object {
        private const val BYTES_PER_PIXEL = 4L
        private const val MIN_INFERENCE_RESERVE_BYTES = 128L * 1024L * 1024L
        private val nativeLifecycleLock = ReentrantLock()

        @JvmStatic
        private external fun nativeInitialize(
            modelRoot: String?,
            noise: Int?,
            scale: Int?,
            syncgap: Int?,
            modelName: String?,
            ttaMode: Boolean?,
            gpuId: Int?
        ): Long

        @JvmStatic
        private external fun nativeProcessBitmap(
            handle: Long,
            inBitmap: Bitmap,
            outBitmap: Bitmap,
            progressListener: ProgressListener? = null
        ): Boolean

        @JvmStatic
        private external fun nativeCancel(handle: Long)

        @JvmStatic
        private external fun nativeIsCancelled(handle: Long): Boolean

        @JvmStatic
        private external fun nativeRelease(handle: Long)

        /**
         * 创建一个RealCUGAN实例。
         * - 非必要请只创建一个实例
         * - 请不要创建太多实例，以免导致堆栈溢出
         *
         * @param realCUGANOption 创建 RealCUGAN 的配置
         * @see RealCUGANOption
         * 拷贝了 assets/models → filesDir/models
         */
        suspend fun create(realCUGANOption: RealCUGANOption): RealCUGAN =
            withContext(Dispatchers.Default) {
                val handle = nativeLifecycleLock.withLock {
                    System.loadLibrary("realcugan_android_sdk")
                    val destRoot = copyModels(context = realCUGANOption.context)
                    nativeInitialize(
                        destRoot.absolutePath,
                        realCUGANOption.noise,
                        realCUGANOption.scale,
                        realCUGANOption.syncgap,
                        realCUGANOption.modelName.dir,
                        realCUGANOption.ttaMode,
                        realCUGANOption.gpuId,
                    )
                }
                require(handle >= 1L) { "RealCUGAN nativeInitialize failed: $handle" }
                return@withContext RealCUGAN(
                    handle,
                    realCUGANOption.scale,
                    realCUGANOption.context.applicationContext
                )
            }

        internal fun copyModels(context: Context): File {
            val destRoot = File(context.filesDir, "models")
            if (!destRoot.exists()) {
                destRoot.mkdirs()
                context.assets.list("models")?.forEach { subdir ->
                    val dstSub = File(destRoot, subdir).apply { mkdirs() }
                    context.assets.list("models/$subdir")?.forEach { fname ->
                        context.assets.open("models/$subdir/$fname").use { inp ->
                            FileOutputStream(File(dstSub, fname)).use { out ->
                                inp.copyTo(out)
                            }
                        }
                    }
                }
            }
            return destRoot
        }
    }
}
