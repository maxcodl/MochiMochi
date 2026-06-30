package com.kawai.mochi

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object ThumbnailRegenerationManager {
    interface Listener {
        fun onProgress(current: Int, total: Int)
        fun onFinished()
        fun onError(message: String)
    }

    private var isRunning = false
    private var currentProgress = 0
    private var totalProgress = 0
    private val listeners = mutableSetOf<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var regenerationThread: Thread? = null

    @JvmStatic
    fun isRegenerating(): Boolean = isRunning

    @JvmStatic
    fun getProgress(): IntArray = intArrayOf(currentProgress, totalProgress)

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners.add(listener)
        if (isRunning) {
            listener.onProgress(currentProgress, totalProgress)
        }
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun regenerateMissing(context: Context) {
        runRegenerationTask(context, deleteExisting = false)
    }

    @JvmStatic
    fun start(context: Context) {
        runRegenerationTask(context, deleteExisting = true)
    }

    private fun runRegenerationTask(context: Context, deleteExisting: Boolean) {
        if (isRunning) return
        isRunning = true
        currentProgress = 0
        totalProgress = 0

        regenerationThread?.interrupt()
        regenerationThread = Thread {
            try {
                val appContext = context.applicationContext
                val masterRoot = WastickerParser.getOrSeedMasterRootPublic(appContext)
                val packsArray = masterRoot.optJSONArray("sticker_packs") ?: JSONObject().optJSONArray("sticker_packs")
                
                if (packsArray == null || packsArray.length() == 0) {
                    mainHandler.post { notifyFinished() }
                    return@Thread
                }

                var total = 0
                for (i in 0 until packsArray.length()) {
                    val pack = packsArray.getJSONObject(i)
                    total += pack.optJSONArray("stickers")?.length() ?: 0
                }
                totalProgress = total

                if (total == 0) {
                    mainHandler.post { notifyFinished() }
                    return@Thread
                }

                val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                val executor = Executors.newFixedThreadPool(threads)
                val processedCount = AtomicInteger(0)
                val latch = CountDownLatch(total)

                for (i in 0 until packsArray.length()) {
                    val pack = packsArray.getJSONObject(i)
                    val identifier = pack.optString("identifier")
                    val stickers = pack.optJSONArray("stickers") ?: continue

                    // Pass deletion flag to the worker
                    executor.submit {
                        try {
                            WastickerParser.regenerateThumbnailsForPackParallel(appContext, identifier, stickers, deleteExisting) {
                                val done = processedCount.incrementAndGet()
                                mainHandler.post {
                                    currentProgress = done
                                    notifyProgress(done, total)
                                }
                                latch.countDown()
                            }
                        } catch (e: Exception) {
                            // If a pack fails, still decrement latch for its stickers
                            for (s in 0 until stickers.length()) latch.countDown()
                        }
                    }
                }

                latch.await()
                executor.shutdown()
                
                val prefs = appContext.getSharedPreferences("mochi_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("thumbnails_generated", true).apply()

                mainHandler.post { notifyFinished() }
            } catch (e: Exception) {
                isRunning = false
                mainHandler.post { notifyError(e.message ?: "Unknown error") }
            }
        }
        regenerationThread?.start()
    }

    private fun notifyProgress(current: Int, total: Int) {
        listeners.toList().forEach { it.onProgress(current, total) }
    }

    private fun notifyFinished() {
        isRunning = false
        listeners.toList().forEach { it.onFinished() }
        StickerUpdateManager.triggerUpdate()
    }

    private fun notifyError(message: String) {
        isRunning = false
        listeners.toList().forEach { it.onError(message) }
    }
}
