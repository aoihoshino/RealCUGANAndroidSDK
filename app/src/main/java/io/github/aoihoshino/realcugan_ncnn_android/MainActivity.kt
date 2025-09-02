package io.github.aoihoshino.realcugan_ncnn_android

import RealCUGANOption
import ModelName
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.measureTime

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var lastProgress: Float = -1f
    private var lastProgressAt: Long = 0L
    private lateinit var imageView: ImageView

    // 选项覆盖集合（根据 RealCUGANOption 说明）：
    // - modelName 决定合法的 scale / noise 取值
    // - syncgap 仅允许 0..3
    // - gpuId 可为 null（按库默认），或 >= -1（-1 为 CPU）
    private val models = listOf(ModelName.NOSE, ModelName.PRO, ModelName.SE)
    private val syncgaps = listOf(0, 3)               // 可改为 listOf(0,1,2,3) 做更细覆盖
    private val gpuIds: List<Int?> = listOf(null)     // 如需 CPU/GPU 覆盖，设为 listOf(-1, 0)

    private data class Case(
        val name: String,
        val model: ModelName,
        val noise: Int,
        val scale: Int,
        val syncgap: Int,
        val tta: Boolean,
        val gpuId: Int?
    )

    // 待测 scale 列表（可以根据需要调）
    private val scales = listOf(4) // 如需更多覆盖：listOf(2, 3, 4)
    private val testFiles = listOf(
        "test1.png",
        "test2.jpeg",
        "test3.png"
    )

    // —— 并发测试用配置（可按需调整） ——
    private val serviceMaxConcurrent: Int = 2   // Service 侧最大并发许可数（Semaphore）
    private val queueEnabled: Boolean = true    // true=排队，false=满额即失败
    private val concurrentClients: Int = 4      // 并发提交的协程数量
    private val loopsPerClient: Int = 2         // 每个协程重复处理轮数

    // Service Binder 相关
    private var binder: RealCUGANService.LocalBinder? = null
    private val binderReady = CompletableDeferred<RealCUGANService.LocalBinder>()

    // 外部可配置：是否启用前台通知（演示：这里从 Intent extra 读取，默认 true）
    private val enableNotification: Boolean by lazy {
        intent?.getBooleanExtra(RealCUGANService.EXTRA_ENABLE_NOTIFICATION, true) ?: true
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as RealCUGANService.LocalBinder
            if (!binderReady.isCompleted) binderReady.complete(binder!!)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imageView = findViewById<ImageView>(R.id.resultImageView)

        // —— 启动与绑定 Service（外部可控制是否显示前台通知）——
        val svcIntent = Intent(this, RealCUGANService::class.java)
            .putExtra(RealCUGANService.EXTRA_ENABLE_NOTIFICATION, enableNotification)

        if (enableNotification) {
            // 需要前台通知：用前台启动
            ContextCompat.startForegroundService(this, svcIntent)
        } else {
            // 不需要前台通知：普通 startService，避免 5s 内必须 startForeground 的约束
            startService(svcIntent)
        }

        bindService(svcIntent, conn, Context.BIND_AUTO_CREATE)

        // 通过 Service 处理：等待 Binder → 初始化 → 逐个处理
        lifecycleScope.launch(Dispatchers.IO) {
            val b = binderReady.await()

            // 配置 Service 并发闸
            b.configureConcurrency(max = serviceMaxConcurrent, queue = queueEnabled)

            // 基础测试用例：单一默认 Case
            val cases = listOf(
                Case(
                    name = "basic",
                    model = ModelName.SE,
                    noise = -1,
                    scale = 2,
                    syncgap = 3,
                    tta = false,
                    gpuId = null
                )
            )
            val totalImages = concurrentClients * loopsPerClient * testFiles.size * cases.size
            val totalCost = measureTime {
                for (case in cases) {
                    // 每个用例单独初始化（确保 native 参数生效）
                    b.init(
                        RealCUGANOption(
                            context = applicationContext,
                            noise = case.noise,
                            scale = case.scale,
                            syncgap = case.syncgap,
                            modelName = case.model,
                            ttaMode = case.tta,
                            gpuId = case.gpuId
                        )
                    )

                    Log.i(
                        TAG,
                        "==== Begin Concurrency Case: ${case.name} | clients=$concurrentClients loops=$loopsPerClient svcMax=$serviceMaxConcurrent queue=$queueEnabled ===="
                    )

                    val caseCost = measureTime {
                        coroutineScope {
                            repeat(concurrentClients) { clientIdx ->
                                launch(Dispatchers.IO) {
                                    repeat(loopsPerClient) { loopIdx ->
                                        for (filename in testFiles) {
                                            // 重置节流进度，避免跨任务沿用
                                            lastProgress = -1f
                                            lastProgressAt = 0L

                                            val bytes = assets.open(filename).use { it.readBytes() }
                                            val displayName =
                                                "${case.name}::C$clientIdx-L$loopIdx::$filename"
                                            try {
                                                val bmp = awaitProcess(b, bytes, displayName)
                                                // 为降低 UI 干扰，这里不必每次都刷新到 ImageView；如需观察可以放开以下代码：
                                                // withContext(Dispatchers.Main) { imageView.setImageBitmap(bmp) }
                                                Log.i(
                                                    TAG,
                                                    "OK ${displayName} → ${bmp.width}×${bmp.height}"
                                                )
                                            } catch (t: Throwable) {
                                                Log.e(TAG, "FAIL ${displayName}: ${t.message}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Log.i(TAG, "==== End Case: ${case.name}, cost=${caseCost} ====")
                }
            }
            Log.i(TAG, "==== Concurrency Test Done: images=$totalImages, total=$totalCost ====")

            // —— 测试完成：回收 Service ——
            try {
                withContext(Dispatchers.Main) {
                    binder?.dispose() // 调用 Service 提供的释放方法（需在 LocalBinder 实现）
                    unbindService(conn)
                    stopService(Intent(this@MainActivity, RealCUGANService::class.java))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error stopping service: ${t.message}")
            }
        }
    }

    private suspend fun awaitProcess(
        b: RealCUGANService.LocalBinder,
        bytes: ByteArray,
        displayName: String
    ): Bitmap = suspendCancellableCoroutine { cont ->
        val taskId = b.process(
            imageData = bytes,
            displayName = displayName,
            listener = { p ->
                // 在主线程打印/更新（节流：每提升≥1%或间隔≥200ms再更新一次）
                lifecycleScope.launch(Dispatchers.Main) {
                    val now = System.currentTimeMillis()
                    if (p - lastProgress >= 1f || now - lastProgressAt >= 200) {
                        lastProgress = p
                        lastProgressAt = now
                        Log.i(TAG, String.format("%s Processing %.2f%%", displayName, p))
                    }
                }
            }
        ) { result ->
            result.onSuccess { bmp ->
                if (cont.isActive) {
                    runOnUiThread {
                        imageView.setImageBitmap(bmp)
                    }
                    cont.resume(bmp)
                }
            }.onFailure { t ->
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
        cont.invokeOnCancellation {
            try {
                b.cancel(taskId)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unbindService(conn)
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
