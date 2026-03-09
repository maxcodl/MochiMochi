package com.kawai.mochi

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import com.kawai.mochi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

object StickerPackLoader {
    private const val TAG = "StickerPackLoader"

    /**
     * For Java compatibility: provides a blocking way to fetch sticker packs.
     */
    @JvmStatic
    fun fetchStickerPacks(context: Context): ArrayList<StickerPack> = runBlocking {
        ArrayList(fetchStickerPacksAsync(context))
    }

    /**
     * Fetch sticker packs using Coroutines to avoid blocking the UI thread.
     */
    suspend fun fetchStickerPacksAsync(context: Context): List<StickerPack> = withContext(Dispatchers.IO) {
        val cursor = context.contentResolver.query(StickerContentProvider.AUTHORITY_URI, null, null, null, null)
            ?: throw IllegalStateException("could not fetch from content provider, ${BuildConfig.CONTENT_PROVIDER_AUTHORITY}")

        val identifierSet = HashSet<String>()
        val stickerPackList = fetchFromContentProvider(cursor)
        cursor.close()

        val dedupedList = stickerPackList.filter {
            if (identifierSet.contains(it.identifier)) {
                Log.w(TAG, "Skipping duplicate pack identifier: ${it.identifier}")
                false
            } else {
                identifierSet.add(it.identifier)
                true
            }
        }

        dedupedList.forEach { stickerPack ->
            try {
                val stickers = getStickersForPack(context, stickerPack)
                stickerPack.setStickers(stickers)
                StickerPackValidator.verifyStickerPackValidity(context, stickerPack)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid pack '${stickerPack.name}': ${e.message}")
            }
        }
        dedupedList
    }

    private fun getStickersForPack(context: Context, stickerPack: StickerPack): List<Sticker> {
        val stickers = fetchFromContentProviderForStickers(stickerPack.identifier, context.contentResolver)
        stickers.forEach { sticker ->
            try {
                val bytes = fetchStickerAsset(stickerPack.identifier, sticker.imageFileName, context.contentResolver)
                if (bytes.isEmpty()) {
                    throw IllegalStateException("Asset file is empty, pack: ${stickerPack.name}, sticker: ${sticker.imageFileName}")
                }
                sticker.setSize(bytes.size.toLong())
            } catch (e: Exception) {
                throw IllegalStateException("Asset file doesn't exist. pack: ${stickerPack.name}, sticker: ${sticker.imageFileName}", e)
            }
        }
        return stickers
    }

    private fun fetchFromContentProvider(cursor: android.database.Cursor): List<StickerPack> {
        val stickerPackList = mutableListOf<StickerPack>()
        if (cursor.moveToFirst()) {
            do {
                val identifier = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_IDENTIFIER_IN_QUERY))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_NAME_IN_QUERY))
                val publisher = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_PUBLISHER_IN_QUERY))
                val trayImage = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_ICON_IN_QUERY))
                val androidPlayStoreLink = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.ANDROID_APP_DOWNLOAD_LINK_IN_QUERY))
                val iosAppLink = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.IOS_APP_DOWNLOAD_LINK_IN_QUERY))
                val publisherEmail = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.PUBLISHER_EMAIL))
                val publisherWebsite = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.PUBLISHER_WEBSITE))
                val privacyPolicyWebsite = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.PRIVACY_POLICY_WEBSITE))
                val licenseAgreementWebsite = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.LICENSE_AGREEMENT_WEBSITE))
                val imageDataVersion = cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.IMAGE_DATA_VERSION))
                val avoidCache = cursor.getShort(cursor.getColumnIndexOrThrow(StickerContentProvider.AVOID_CACHE)) > 0
                val animatedStickerPack = cursor.getShort(cursor.getColumnIndexOrThrow(StickerContentProvider.ANIMATED_STICKER_PACK)) > 0
                
                val stickerPack = StickerPack(identifier, name, publisher, trayImage, publisherEmail, publisherWebsite, privacyPolicyWebsite, licenseAgreementWebsite, imageDataVersion, avoidCache, animatedStickerPack)
                stickerPack.setAndroidPlayStoreLink(androidPlayStoreLink)
                stickerPack.setIosAppStoreLink(iosAppLink)
                stickerPackList.add(stickerPack)
            } while (cursor.moveToNext())
        }
        return stickerPackList
    }

    private fun fetchFromContentProviderForStickers(identifier: String, contentResolver: ContentResolver): List<Sticker> {
        val uri = getStickerListUri(identifier)
        val projection = arrayOf(
            StickerContentProvider.STICKER_FILE_NAME_IN_QUERY,
            StickerContentProvider.STICKER_FILE_EMOJI_IN_QUERY,
            StickerContentProvider.STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY
        )
        val cursor = contentResolver.query(uri, projection, null, null, null)
        val stickers = mutableListOf<Sticker>()
        cursor?.use {
            if (it.moveToFirst()) {
                do {
                    val name = it.getString(it.getColumnIndexOrThrow(StickerContentProvider.STICKER_FILE_NAME_IN_QUERY))
                    val emojisConcatenated = it.getString(it.getColumnIndexOrThrow(StickerContentProvider.STICKER_FILE_EMOJI_IN_QUERY))
                    val accessibilityText = it.getString(it.getColumnIndexOrThrow(StickerContentProvider.STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY))
                    val emojis = if (!TextUtils.isEmpty(emojisConcatenated)) {
                        emojisConcatenated.split(",").toList()
                    } else {
                        emptyList()
                    }
                    stickers.add(Sticker(name, emojis, accessibilityText))
                } while (it.moveToNext())
            }
        }
        return stickers
    }

    @JvmStatic
    @Throws(IOException::class)
    fun fetchStickerAsset(identifier: String, name: String, contentResolver: ContentResolver): ByteArray {
        contentResolver.openInputStream(getStickerAssetUri(identifier, name)).use { inputStream ->
            if (inputStream == null) {
                throw IOException("cannot read sticker asset:$identifier/$name")
            }
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(16384)
            var read: Int
            while (inputStream.read(data, 0, data.size).also { read = it } != -1) {
                buffer.write(data, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    @JvmStatic
    fun getStickerListUri(identifier: String): Uri {
        return Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendPath(StickerContentProvider.STICKERS)
            .appendPath(identifier).build()
    }

    @JvmStatic
    fun getStickerAssetUri(identifier: String, stickerName: String): Uri {
        return Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendPath(StickerContentProvider.STICKERS_ASSET)
            .appendPath(identifier)
            .appendPath(stickerName).build()
    }
}
