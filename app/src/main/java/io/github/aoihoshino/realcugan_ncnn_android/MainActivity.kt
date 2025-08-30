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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.measureTime

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var lastProgress: Float = -1f
    private var lastProgressAt: Long = 0L

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
        val imageView = findViewById<ImageView>(R.id.resultImageView)

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

            // 组合测试用例：按模型约束枚举合法 (model × noise × scale × syncgap × tta × gpuId)
            val cases = buildList {
                for (m in models) {
                    for (n in m.allowedNoises) {
                        for (s in m.allowedScales) {
                            for (sg in syncgaps) {
                                for (gid in gpuIds) {
                                    add(
                                        Case(
                                            name = "${m.name}-n${n}-x${s}-sg${sg}-ttaF ${if (gid == null) "" else "-gpu$gid"}",
                                            model = m,
                                            noise = n,
                                            scale = s,
                                            syncgap = sg,
                                            tta = false, // tta开启会更费时
                                            gpuId = gid
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

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

                    Log.i(TAG, "==== Begin Case: ${case.name} ==== ")

                    val caseCost = measureTime {
                        for (filename in testFiles) {
                            // 重置节流进度，避免跨任务沿用
                            lastProgress = -1f
                            lastProgressAt = 0L

                            val bytes = assets.open(filename).use { it.readBytes() }
                            val displayName = "${case.name}::$filename"
                            val bmp = awaitProcess(b, bytes, displayName)

                            withContext(Dispatchers.Main) {
                                imageView.setImageBitmap(bmp)
                            }
                            Log.i(TAG, "Processed ${displayName} → ${bmp.width}×${bmp.height}")
                        }
                    }

                    Log.i(TAG, "==== End Case: ${case.name}, cost=${caseCost} ====")
                }
            }
            Log.i(TAG, "Processed all cases in $totalCost")
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
            listener = ProgressListener { p ->
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
                if (cont.isActive) cont.resume(bmp)
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
