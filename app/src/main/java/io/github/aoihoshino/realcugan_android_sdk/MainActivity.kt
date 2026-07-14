package io.github.aoihoshino.realcugan_android_sdk

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.measureTime

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "RealCUGANStress"

        private const val EXTRA_STRESS_AUTO = "stress_auto"
        private const val EXTRA_STRESS_CLIENTS = "stress_clients"
        private const val EXTRA_STRESS_LOOPS = "stress_loops"
        private const val EXTRA_STRESS_INSTANCES = "stress_instances"
        private const val EXTRA_STRESS_SHOW_LAST = "stress_show_last"
        private const val EXTRA_STRESS_CANCEL_EVERY = "stress_cancel_every"
        private const val EXTRA_STRESS_CANCEL_DELAY_MS = "stress_cancel_delay_ms"
        private const val MAX_SCREEN_LOG_LINES = 500
    }

    private data class Case(
        val name: String,
        val model: ModelName,
        val noise: Int,
        val scale: Int,
        val syncgap: Int,
        val tta: Boolean,
        val gpuId: Int?
    )

    private data class StressConfig(
        val clients: Int,
        val loops: Int,
        val maxConcurrentInstances: Int,
        val showLastBitmap: Boolean,
        val cancelEvery: Int,
        val cancelDelayMs: Long
    )

    private class ExpectedStressCancel(message: String) : RuntimeException(message)

    private val testFiles = listOf("test1.png", "test2.jpeg", "test3.png")
    private val stressCase = Case(
        name = "se-up2x",
        model = ModelName.SE,
        noise = -1,
        scale = 2,
        syncgap = 3,
        tta = false,
        gpuId = null
    )

    private lateinit var imageView: ImageView
    private lateinit var logScrollView: ScrollView
    private lateinit var logTextView: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    private val screenLogLines = ArrayDeque<String>()
    private var displayedBitmap: Bitmap? = null
    private var stressJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imageView = findViewById(R.id.resultImageView)
        logScrollView = findViewById(R.id.logScrollView)
        logTextView = findViewById(R.id.logTextView)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startStressButton)

        startButton.setOnClickListener { startStressRun() }

        if (intent?.getBooleanExtra(EXTRA_STRESS_AUTO, true) != false) {
            startStressRun()
        }
    }

    private fun readStressConfig(): StressConfig = StressConfig(
        clients = intent?.getIntExtra(EXTRA_STRESS_CLIENTS, 4)?.coerceAtLeast(1) ?: 4,
        loops = intent?.getIntExtra(EXTRA_STRESS_LOOPS, 20)?.coerceAtLeast(1) ?: 20,
        maxConcurrentInstances = intent?.getIntExtra(EXTRA_STRESS_INSTANCES, 2)
            ?.coerceAtLeast(1) ?: 2,
        showLastBitmap = intent?.getBooleanExtra(EXTRA_STRESS_SHOW_LAST, false) ?: false,
        cancelEvery = intent?.getIntExtra(EXTRA_STRESS_CANCEL_EVERY, 7)?.coerceAtLeast(0) ?: 7,
        cancelDelayMs = intent?.getLongExtra(EXTRA_STRESS_CANCEL_DELAY_MS, 300L)
            ?.coerceAtLeast(0L) ?: 300L
    )

    private fun startStressRun() {
        if (stressJob?.isActive == true) return

        stressJob = lifecycleScope.launch(Dispatchers.IO) {
            val config = readStressConfig()
            val submitted = AtomicInteger(0)
            val successes = AtomicInteger(0)
            val cancellations = AtomicInteger(0)
            val failures = AtomicInteger(0)
            val total = config.clients * config.loops * testFiles.size
            val assetBytes = testFiles.associateWith { name ->
                assets.open(name).use { it.readBytes() }
            }
            val instanceGate = Semaphore(config.maxConcurrentInstances)

            withContext(Dispatchers.Main) {
                clearScreenLog()
                startButton.isEnabled = false
                statusText.text = "Stress running: 0/$total"
                appendScreenLog("Stress running: 0/$total")
            }

            logMemory("before")
            logInfo(
                "Begin stress case=${stressCase.name}, clients=${config.clients}, " +
                        "loops=${config.loops}, maxInstances=${config.maxConcurrentInstances}, " +
                        "showLast=${config.showLastBitmap}, cancelEvery=${config.cancelEvery}, " +
                        "cancelDelayMs=${config.cancelDelayMs}"
            )

            val elapsed = measureTime {
                coroutineScope {
                    repeat(config.clients) { clientIdx ->
                        launch(Dispatchers.IO) {
                            repeat(config.loops) { loopIdx ->
                                for ((filename, bytes) in assetBytes) {
                                    val taskNo = submitted.incrementAndGet()
                                    val cancelThis = config.cancelEvery > 0 &&
                                            taskNo % config.cancelEvery == 0
                                    val displayName =
                                        "${stressCase.name}::#$taskNo::C$clientIdx-L$loopIdx::$filename"
                                    runOneStressTask(
                                        gate = instanceGate,
                                        bytes = bytes,
                                        displayName = displayName,
                                        cancelThis = cancelThis,
                                        cancelDelayMs = config.cancelDelayMs,
                                        total = total,
                                        successes = successes,
                                        cancellations = cancellations,
                                        failures = failures,
                                        showBitmap = config.showLastBitmap
                                    )
                                }
                            }
                        }
                    }
                }
            }

            logMemory("after")
            logInfo(
                "Stress done ok=${successes.get()}, cancel=${cancellations.get()}, " +
                        "fail=${failures.get()}, total=$total, cost=$elapsed"
            )

            withContext(Dispatchers.Main) {
                statusText.text =
                    "Stress done: ok=${successes.get()} cancel=${cancellations.get()} fail=${failures.get()} $elapsed"
                appendScreenLog(statusText.text.toString())
                startButton.isEnabled = true
            }
        }
    }

    private suspend fun runOneStressTask(
        gate: Semaphore,
        bytes: ByteArray,
        displayName: String,
        cancelThis: Boolean,
        cancelDelayMs: Long,
        total: Int,
        successes: AtomicInteger,
        cancellations: AtomicInteger,
        failures: AtomicInteger,
        showBitmap: Boolean
    ) {
        var rcg: RealCUGAN? = null
        try {
            val bmp = gate.withPermit {
                val instance = RealCUGAN.create(
                    RealCUGANOption(
                        context = applicationContext,
                        noise = stressCase.noise,
                        scale = stressCase.scale,
                        syncgap = stressCase.syncgap,
                        modelName = stressCase.model,
                        ttaMode = stressCase.tta,
                        gpuId = stressCase.gpuId
                    )
                )
                rcg = instance
                logInfo("NEW instance $displayName")
                try {
                    coroutineScope {
                        val cancelJob = if (cancelThis) {
                            launch {
                                delay(cancelDelayMs)
                                logInfo("CANCEL $displayName after ${cancelDelayMs}ms")
                                instance.cancel()
                            }
                        } else {
                            null
                        }

                        try {
                            instance.process(bytes) { p ->
                                if (p == 0f || p >= 100f) {
                                    logInfo(
                                        String.format(Locale.US, "%s %.1f%%", displayName, p)
                                    )
                                }
                            }
                        } catch (t: CancellationException) {
                            if (cancelThis) {
                                throw ExpectedStressCancel("cancelled as expected")
                            }
                            throw t
                        } finally {
                            cancelJob?.cancel()
                        }
                    }
                } finally {
                    try {
                        instance.release()
                    } finally {
                        rcg = null
                    }
                }
            }
            val done = successes.incrementAndGet() + cancellations.get() + failures.get()
            logInfo("OK $displayName -> ${bmp.width}x${bmp.height}, $done/$total")

            if (showBitmap) {
                withContext(Dispatchers.Main) {
                    showResultBitmap(bmp)
                }
            } else {
                bmp.recycle()
            }

            if (done == 1 || done % 5 == 0 || done == total) {
                logMemory("progress $done/$total")
                withContext(Dispatchers.Main) {
                    statusText.text = "Stress running: $done/$total"
                    appendScreenLog(statusText.text.toString())
                }
            }
        } catch (t: ExpectedStressCancel) {
            val done = successes.get() + cancellations.incrementAndGet() + failures.get()
            logInfo("CANCELLED $displayName: ${t.message}, $done/$total")
            if (done == 1 || done % 5 == 0 || done == total) {
                logMemory("cancel $done/$total")
                withContext(Dispatchers.Main) {
                    statusText.text = "Stress running: $done/$total, cancel=${cancellations.get()}"
                    appendScreenLog(statusText.text.toString())
                }
            }
        } catch (t: Throwable) {
            try {
                rcg?.cancel()
            } catch (_: Throwable) {
            }
            try {
                rcg?.release()
            } catch (_: Throwable) {
            }
            if (t is CancellationException) {
                throw t
            }
            val done = successes.get() + cancellations.get() + failures.incrementAndGet()
            logError("FAIL $displayName: ${t.javaClass.simpleName}: ${t.message}", t)
            logMemory("failure $done/$total")
            withContext(Dispatchers.Main) {
                statusText.text = "Stress running: $done/$total, fail=${failures.get()}"
                appendScreenLog(statusText.text.toString())
            }
        }
    }

    private fun showResultBitmap(bitmap: Bitmap) {
        val old = displayedBitmap
        imageView.setImageBitmap(bitmap)
        displayedBitmap = bitmap
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }
    }

    private fun logMemory(stage: String) {
        val runtime = Runtime.getRuntime()
        val usedJava = runtime.totalMemory() - runtime.freeMemory()
        val maxJava = runtime.maxMemory()
        val pssKb = getProcessPssKb()
        logInfo(
            "MEM[$stage] java=${formatBytes(usedJava)}/${formatBytes(maxJava)}, pss=${pssKb / 1024}MB"
        )
    }

    private fun logInfo(message: String) {
        Log.i(TAG, message)
        lifecycleScope.launch(Dispatchers.Main) {
            appendScreenLog(message)
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, message, throwable)
        }
        lifecycleScope.launch(Dispatchers.Main) {
            appendScreenLog(message)
        }
    }

    private fun clearScreenLog() {
        screenLogLines.clear()
        logTextView.text = ""
    }

    private fun appendScreenLog(message: String) {
        screenLogLines.addLast(message)
        while (screenLogLines.size > MAX_SCREEN_LOG_LINES) {
            screenLogLines.removeFirst()
        }
        logTextView.text = screenLogLines.joinToString(separator = "\n", postfix = "\n")
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun getProcessPssKb(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
        return info.firstOrNull()?.totalPss ?: Debug.getPss().toInt()
    }

    private fun formatBytes(bytes: Long): String =
        String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))

    override fun onDestroy() {
        stressJob?.cancel()
        displayedBitmap?.recycle()
        displayedBitmap = null
        super.onDestroy()
    }
}
