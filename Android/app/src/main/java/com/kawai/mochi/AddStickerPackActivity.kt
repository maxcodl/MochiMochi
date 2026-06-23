package com.kawai.mochi

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.kawai.mochi.BuildConfig
import com.kawai.mochi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Abstract activity that handles the logic of adding sticker packs to WhatsApp.
 *
 * For packs with ≤30 stickers the original single-intent path is used.
 * For packs with >30 stickers the pack is split into ≤30-sticker chunks by
 * [StickerPackChunkManager]; the user is walked through adding each chunk one
 * at a time (one WhatsApp dialog per chunk).  Temporary chunk entries are
 * written to disk before each WhatsApp intent and cleaned up after the last
 * chunk is processed.
 */
abstract class AddStickerPackActivity : BaseActivity() {

    // ── State for multi-chunk add session ────────────────────────────────────

    /** Chunks remaining to be added (mutable, consumed in order). */
    private var pendingChunks: MutableList<StickerPack> = mutableListOf()
    /** The original pack whose chunks we are currently sending. */
    private var chunkSourceIdentifier: String? = null
    /** How many chunks in total this session (for progress display). */
    private var totalChunkCount: Int = 0
    /** Index of the chunk being launched right now (0-based, set before launch). */
    private var currentChunkIndex: Int = 0

    // ── WhatsApp intent launcher ─────────────────────────────────────────────

    private val addStickerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_CANCELED) {
                val validationError = result.data?.getStringExtra("validation_error")
                if (validationError != null) {
                    Log.e(TAG, "WhatsApp validation failed: $validationError")
                    Toast.makeText(
                        this,
                        getString(R.string.whatsapp_reported_error, validationError),
                        Toast.LENGTH_LONG
                    ).show()
                    // Try adding the next chunk instead of aborting
                    window.decorView.post { restoreStatusBarAppearance() }
                    launchNextChunkOrFinish()
                    return@registerForActivityResult
                }
                // User dismissed the WhatsApp dialog — warn and abort.
                if (pendingChunks.isNotEmpty()) {
                    val remaining = pendingChunks.size
                    Toast.makeText(
                        this,
                        getString(R.string.chunk_add_cancelled, remaining),
                        Toast.LENGTH_LONG
                    ).show()
                    abortChunkSession()
                    return@registerForActivityResult
                }
            }

            // Current chunk was accepted by WhatsApp — advance to the next one.
            window.decorView.post { restoreStatusBarAppearance() }
            launchNextChunkOrFinish()
        }

    // ── Progress Bar Hooks ───────────────────────────────────────────────────

    protected abstract fun showProgressBar(message: String?)
    protected abstract fun hideProgressBar()
    protected abstract fun updateProgress(current: Int, total: Int, message: String?)

    // ── Public API ────────────────────────────────────────────────────────────

    protected fun addStickerPackToWhatsApp(identifier: String, stickerPackName: String) {
        if (!WhitelistCheck.isWhatsAppConsumerAppInstalled(packageManager) &&
            !WhitelistCheck.isWhatsAppSmbAppInstalled(packageManager)
        ) {
            Toast.makeText(this, R.string.whatsapp_not_installed, Toast.LENGTH_SHORT).show()
            return
        }

        showProgressBar(getString(R.string.add_to_whatsapp))
        lifecycleScope.launch {
            try {
                val targetPack = withContext(Dispatchers.IO) {
                    StickerPackLoader.fetchStickerPack(this@AddStickerPackActivity, identifier)
                } ?: run {
                    hideProgressBar()
                    Toast.makeText(
                        this@AddStickerPackActivity,
                        getString(R.string.error_with_message, "Pack not found"),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                if (StickerPackChunkManager.needsChunking(targetPack)) {
                    // ── Large pack: split and walk the user through each chunk ──
                    startChunkSession(targetPack)
                } else {
                    // ── Normal path (≤30 stickers) ──────────────────────────────
                    withContext(Dispatchers.Default) {
                        StickerPackValidator.verifyStickerPackValidity(
                            this@AddStickerPackActivity, targetPack, true
                        ) { current, total ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                updateProgress(current, total, getString(R.string.validation_progress, current, total))
                            }
                        }
                    }
                    hideProgressBar()
                    proceedWithLaunch(identifier, stickerPackName)
                }

            } catch (e: Exception) {
                hideProgressBar()
                Log.e(TAG, "Validation failed: $identifier", e)
                MessageDialogFragment.newInstance(
                    R.string.title_validation_error,
                    getString(R.string.validation_internal_check_failed, e.message)
                ).show(supportFragmentManager, "internal validation error")
            }
        }
    }

    // ── Chunk-session logic ───────────────────────────────────────────────────

    private fun startChunkSession(originalPack: StickerPack) {
        val chunks = StickerPackChunkManager.splitIntoChunks(originalPack)
        if (chunks.isEmpty()) {
            hideProgressBar()
            Toast.makeText(this, R.string.error_no_stickers_found, Toast.LENGTH_SHORT).show()
            return
        }

        pendingChunks = chunks.toMutableList()
        chunkSourceIdentifier = originalPack.identifier
        totalChunkCount = chunks.size
        currentChunkIndex = 0

        launchNextChunkOrFinish()
    }

    /**
     * Registers the next pending chunk on disk and launches the WhatsApp intent
     * for it. If there are no more chunks, cleans up and shows a success toast.
     */
    private fun launchNextChunkOrFinish() {
        if (pendingChunks.isEmpty()) {
            // All chunks sent — clean up.
            hideProgressBar()
            finishChunkSession(cancelled = false)
            return
        }

        val chunk = pendingChunks.removeAt(0)
        val partNumber = currentChunkIndex + 1

        val progressMsg = getString(R.string.chunk_add_progress, partNumber, totalChunkCount)
        updateProgress(partNumber, totalChunkCount, progressMsg)

        lifecycleScope.launch {
            try {
                // Find the original pack to source sticker files from.
                val originalId = chunkSourceIdentifier ?: return@launch
                val originalPack = withContext(Dispatchers.IO) {
                    StickerPackLoader.fetchStickerPack(this@AddStickerPackActivity, originalId)
                } ?: run {
                    hideProgressBar()
                    return@launch
                }

                currentChunkIndex++

                // Validate chunk (quick check only — deep decode not needed).
                withContext(Dispatchers.Default) {
                    StickerPackValidator.verifyStickerPackValidity(
                        this@AddStickerPackActivity, chunk, true
                    ) { current, total ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            updateProgress(current, total, getString(R.string.validation_progress, current, total))
                        }
                    }
                }

                // Briefly show intent preparation is done
                updateProgress(totalChunkCount, totalChunkCount, progressMsg)

                hideProgressBar()
                proceedWithLaunch(chunk.identifier, chunk.name)

            } catch (e: Exception) {
                hideProgressBar()
                Log.e(TAG, "Chunk registration/validation failed", e)
                MessageDialogFragment.newInstance(
                    R.string.title_validation_error,
                    getString(R.string.validation_internal_check_failed, e.message)
                ).show(supportFragmentManager, "chunk validation error")
                abortChunkSession()
            }
        }
    }

    private fun finishChunkSession(cancelled: Boolean) {
        resetChunkState()
        if (!cancelled) {
            Toast.makeText(
                this@AddStickerPackActivity,
                R.string.chunk_add_complete,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun abortChunkSession() {
        pendingChunks.clear()
        finishChunkSession(cancelled = true)
    }

    private fun resetChunkState() {
        pendingChunks = mutableListOf()
        chunkSourceIdentifier = null
        totalChunkCount = 0
        currentChunkIndex = 0
    }

    // ── Standard (non-chunked) launch path ───────────────────────────────────

    private fun proceedWithLaunch(identifier: String, stickerPackName: String) {
        val whitelistedConsumer =
            WhitelistCheck.isStickerPackWhitelistedInWhatsAppConsumer(this, identifier)
        val whitelistedSmb =
            WhitelistCheck.isStickerPackWhitelistedInWhatsAppSmb(this, identifier)

        when {
            !whitelistedConsumer && !whitelistedSmb ->
                launchIntentToAddPackToChooser(identifier, stickerPackName)
            !whitelistedConsumer ->
                launchIntentToAddPackToSpecificPackage(
                    identifier, stickerPackName,
                    WhitelistCheck.CONSUMER_WHATSAPP_PACKAGE_NAME
                )
            !whitelistedSmb ->
                launchIntentToAddPackToSpecificPackage(
                    identifier, stickerPackName,
                    WhitelistCheck.SMB_WHATSAPP_PACKAGE_NAME
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.post { restoreStatusBarAppearance() }
        }
    }

    private fun restoreStatusBarAppearance() {
        val decorView = window?.decorView ?: return
        val isNight =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        val wic = WindowCompat.getInsetsController(window, decorView)
        wic.isAppearanceLightStatusBars = !isNight
    }

    private fun launchIntentToAddPackToSpecificPackage(
        identifier: String, stickerPackName: String, whatsappPackageName: String
    ) {
        val intent = createIntentToAddStickerPack(identifier, stickerPackName)
        intent.setPackage(whatsappPackageName)
        try {
            addStickerLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.whatsapp_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchIntentToAddPackToChooser(identifier: String, stickerPackName: String) {
        val intent = createIntentToAddStickerPack(identifier, stickerPackName)
        try {
            addStickerLauncher.launch(
                Intent.createChooser(intent, getString(R.string.add_to_whatsapp))
            )
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Couldn't open WhatsApp chooser", e)
        }
    }

    private fun createIntentToAddStickerPack(identifier: String, stickerPackName: String): Intent {
        return Intent().apply {
            action = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
            putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_ID, identifier)
            putExtra(
                StickerPackDetailsActivity.EXTRA_STICKER_PACK_AUTHORITY,
                BuildConfig.CONTENT_PROVIDER_AUTHORITY
            )
            putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_NAME, stickerPackName)
        }
    }

    companion object {
        private const val TAG = "AddStickerPackActivity"
    }
}
