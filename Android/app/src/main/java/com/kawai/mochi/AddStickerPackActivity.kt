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
 * Performance:
 * Uses Kotlin Coroutines (lifecycleScope) to offload validation from the Main thread,
 * ensuring the UI stays responsive even with many sticker packs.
 */
abstract class AddStickerPackActivity : BaseActivity() {

    private val addStickerLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) {
            val validationError = result.data?.getStringExtra("validation_error")
            if (validationError != null) {
                Log.e(TAG, "WhatsApp validation failed: $validationError")
                MessageDialogFragment.newInstance(
                    R.string.title_validation_error,
                    getString(R.string.whatsapp_reported_error, validationError)
                ).show(supportFragmentManager, "whatsapp validation error")
            }
        }
        window.decorView.post { restoreStatusBarAppearance() }
    }

    protected fun addStickerPackToWhatsApp(identifier: String, stickerPackName: String) {
        // 1. Check if WhatsApp is installed
        if (!WhitelistCheck.isWhatsAppConsumerAppInstalled(packageManager) &&
            !WhitelistCheck.isWhatsAppSmbAppInstalled(packageManager)
        ) {
            Toast.makeText(this, R.string.whatsapp_not_installed, Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Perform validation on a background thread using Coroutines
        lifecycleScope.launch {
            try {
                val packs = StickerPackLoader.fetchStickerPacks(this@AddStickerPackActivity)
                val targetPack = packs.find { it.identifier == identifier }
                
                if (targetPack != null) {
                    withContext(Dispatchers.Default) {
                        StickerPackValidator.verifyStickerPackValidity(this@AddStickerPackActivity, targetPack)
                    }
                }

                // If validation passes, proceed to launch on main thread
                proceedWithLaunch(identifier, stickerPackName)

            } catch (e: Exception) {
                Log.e(TAG, "Internal validation failed for pack: $identifier", e)
                MessageDialogFragment.newInstance(
                    R.string.title_validation_error,
                    getString(R.string.validation_internal_check_failed, e.message)
                ).show(supportFragmentManager, "internal validation error")
            }
        }
    }

    private fun proceedWithLaunch(identifier: String, stickerPackName: String) {
        val whitelistedConsumer = WhitelistCheck.isStickerPackWhitelistedInWhatsAppConsumer(this, identifier)
        val whitelistedSmb = WhitelistCheck.isStickerPackWhitelistedInWhatsAppSmb(this, identifier)

        when {
            !whitelistedConsumer && !whitelistedSmb -> launchIntentToAddPackToChooser(identifier, stickerPackName)
            !whitelistedConsumer -> launchIntentToAddPackToSpecificPackage(
                identifier, stickerPackName, WhitelistCheck.CONSUMER_WHATSAPP_PACKAGE_NAME
            )
            !whitelistedSmb -> launchIntentToAddPackToSpecificPackage(
                identifier, stickerPackName, WhitelistCheck.SMB_WHATSAPP_PACKAGE_NAME
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.post { restoreStatusBarAppearance() }
        }
    }

    override fun onResume() {
        super.onResume()
        // restoreStatusBarAppearance() removed to avoid issues when window doesn't have focus yet
    }

    private fun restoreStatusBarAppearance() {
        val decorView = window?.decorView ?: return
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val wic = WindowCompat.getInsetsController(window, decorView)
        wic.isAppearanceLightStatusBars = !isNight
    }

    private fun launchIntentToAddPackToSpecificPackage(identifier: String, stickerPackName: String, whatsappPackageName: String) {
        val intent = createIntentToAddStickerPack(identifier, stickerPackName)
        intent.setPackage(whatsappPackageName)
        try {
            addStickerLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Couldn't open WhatsApp", e)
            Toast.makeText(this, R.string.whatsapp_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchIntentToAddPackToChooser(identifier: String, stickerPackName: String) {
        val intent = createIntentToAddStickerPack(identifier, stickerPackName)
        try {
            addStickerLauncher.launch(Intent.createChooser(intent, getString(R.string.add_to_whatsapp)))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Couldn't open WhatsApp chooser", e)
        }
    }

    private fun createIntentToAddStickerPack(identifier: String, stickerPackName: String): Intent {
        return Intent().apply {
            action = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
            putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_ID, identifier)
            putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_NAME, stickerPackName)
        }
    }

    companion object {
        private const val TAG = "AddStickerPackActivity"
    }
}
