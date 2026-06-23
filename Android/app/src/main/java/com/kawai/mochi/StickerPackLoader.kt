package com.kawai.mochi

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kawai.mochi.BuildConfig
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

object StickerPackLoader {
    private const val TAG = "StickerPackLoader"
    private const val METADATA = "metadata"

    @JvmStatic
    fun fetchStickerPacks(context: Context): ArrayList<StickerPack> = runBlocking {
        ArrayList(fetchStickerPacksAsync(context))
    }

    @JvmStatic
    fun fetchStickerPack(context: Context, identifier: String): StickerPack? = runBlocking {
        fetchStickerPackAsync(context, identifier)
    }

    suspend fun fetchStickerPacksAsync(context: Context): List<StickerPack> = withContext(Dispatchers.IO) {
        val cursor = context.contentResolver.query(StickerContentProvider.AUTHORITY_URI, null, null, null, null)
            ?: throw IllegalStateException("could not fetch from content provider")

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

        val rootPath = WastickerParser.getStickerFolderPath(context)
        val isSAF = WastickerParser.isCustomPathUri(context)
        
        // Pre-fetch directory map for SAF to avoid repeated lookups
        val packDirsMap = if (isSAF) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath))
            root?.listFiles()?.filter { it.isDirectory }?.associateBy { it.name }
        } else null

        // Parallelize fetching stickers and sizes
        val deferreds = dedupedList.map { stickerPack ->
            async {
                try {
                    val stickers = fetchFromContentProviderForStickers(stickerPack.identifier, context.contentResolver)
                    // Re-introduced size population with optimizations
                    populateStickerSizes(context, stickerPack.identifier, stickers, stickerPack.animatedStickerPack, packDirsMap?.get(stickerPack.identifier))
                    stickerPack.setStickers(stickers)
                    stickers.forEach { it.isAnimated = stickerPack.animatedStickerPack }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid pack '${stickerPack.name}': ${e.message}")
                }
            }
        }
        deferreds.awaitAll()
        dedupedList
    }

    suspend fun fetchStickerPackAsync(context: Context, identifier: String): StickerPack? = withContext(Dispatchers.IO) {
        val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendPath(METADATA)
            .appendPath(identifier)
            .build()

        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return@withContext null
        cursor.use {
            val pack = fetchFromContentProvider(it).firstOrNull() ?: return@withContext null
            return@withContext try {
                val stickers = fetchFromContentProviderForStickers(pack.identifier, context.contentResolver)
                populateStickerSizes(context, pack.identifier, stickers, pack.animatedStickerPack)
                pack.setStickers(stickers)
                pack
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid imported pack '$identifier': ${e.message}")
                null
            }
        }
    }

    fun populateStickerSizes(context: Context, identifier: String, stickers: List<Sticker>, isAnimatedPack: Boolean, packDirDoc: DocumentFile? = null) {
        if (stickers.isEmpty()) return

        val rootPath = WastickerParser.getStickerFolderPath(context)
        if (!WastickerParser.isCustomPathUri(context)) {
            val packDir = File(File(rootPath), identifier)
            stickers.forEach { sticker ->
                val file = File(packDir, sticker.imageFileName)
                if (file.exists()) sticker.setSize(file.length())
                sticker.isAnimated = isAnimatedPack
            }
            return
        }

        try {
            val packDir = packDirDoc ?: run {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath))
                root?.findFile(identifier)
            } ?: return
            
            val sizeMap = HashMap<String, Long>()
            for (file in packDir.listFiles()) {
                val name = file.name
                if (name != null && !file.isDirectory) sizeMap[name] = file.length()
            }
            stickers.forEach { sticker ->
                sticker.setSize(sizeMap[sticker.imageFileName] ?: 0L)
                sticker.isAnimated = isAnimatedPack
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to populate sizes for SAF pack: $identifier", e)
        }
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
            if (inputStream == null) throw IOException("cannot read sticker asset:$identifier/$name")
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
    @Throws(IOException::class)
    fun fetchStickerAssetLength(identifier: String, name: String, contentResolver: ContentResolver): Long {
        val uri = getStickerAssetUri(identifier, name)
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (e: Exception) {
            0L
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
