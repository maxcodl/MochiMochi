package com.kawai.mochi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.kawai.mochi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StickerPackListActivity : AddStickerPackActivity() {
    private lateinit var packLayoutManager: LinearLayoutManager
    private lateinit var packRecyclerView: RecyclerView
    private lateinit var allStickerPacksListAdapter: StickerPackListAdapter
    private var stickerPackList = ArrayList<StickerPack>()
    private lateinit var emptyStateLayout: View
    private lateinit var importFab: ExtendedFloatingActionButton
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            val isScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
            allStickerPacksListAdapter.setScrolling(isScrolling)
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            if (dy > 0 && importFab.isExtended) importFab.shrink() 
            else if (dy < 0 && !importFab.isExtended) importFab.extend()
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { 
        allStickerPacksListAdapter.invalidateAnimationsCache()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sticker_pack_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        packRecyclerView = findViewById(R.id.sticker_pack_list)
        emptyStateLayout = findViewById(R.id.empty_state_layout)
        importFab = findViewById(R.id.import_button_fab)

        val intentList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_STICKER_PACK_LIST_DATA, StickerPack::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_STICKER_PACK_LIST_DATA)
        }
        
        stickerPackList = intentList ?: ArrayList()

        supportActionBar?.title = resources.getQuantityString(R.plurals.title_activity_sticker_packs_list, stickerPackList.size)

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    importWastickerFile(uri)
                }
            }
        }

        showStickerPackList(stickerPackList)

        findViewById<Button>(R.id.import_button)?.setOnClickListener { openFilePicker() }
        importFab.setOnClickListener { openFilePicker() }

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position in stickerPackList.indices) {
                    val pack = stickerPackList[position]
                    MaterialAlertDialogBuilder(this@StickerPackListActivity)
                        .setTitle(R.string.delete_pack_title)
                        .setMessage(getString(R.string.delete_pack_confirm_with_name, pack.name))
                        .setPositiveButton(R.string.delete_button) { _, _ -> deletePack(position) }
                        .setNegativeButton(R.string.cancel) { _, _ -> allStickerPacksListAdapter.notifyItemChanged(position) }
                        .setOnCancelListener { allStickerPacksListAdapter.notifyItemChanged(position) }
                        .show()
                }
            }
        }).attachToRecyclerView(packRecyclerView)

        updateEmptyState()
        runThumbnailMigration()
    }

    private fun runThumbnailMigration() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rootFolder = WastickerParser.getStickerFolderPath(this@StickerPackListActivity)
            stickerPackList.forEach { pack ->
                val packDir = File(rootFolder, pack.identifier)
                if (!packDir.exists()) return@forEach
                
                val thumbDir = File(packDir, "thumbs")
                if (!thumbDir.exists() || (thumbDir.listFiles()?.size ?: 0) < (pack.stickers?.size ?: 0)) {
                    thumbDir.mkdirs()
                    pack.stickers?.forEach { sticker ->
                        val sourceFile = File(packDir, sticker.imageFileName)
                        val thumbFile = File(thumbDir, "thumb_" + sticker.imageFileName)
                        if (sourceFile.exists() && !thumbFile.exists()) {
                            try {
                                StickerProcessor.createThumbnail(sourceFile, thumbFile)
                            } catch (e: Exception) {
                                Log.e("Migration", "Failed to create thumb for ${sticker.imageFileName}", e)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_wasticker_file)))
    }

    private fun importWastickerFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WastickerParser.importStickerPack(this@StickerPackListActivity, uri)
                    StickerContentProvider.getInstance()?.invalidateStickerPackList()
                }
                
                val freshPacks = StickerPackLoader.fetchStickerPacks(this@StickerPackListActivity)
                
                Toast.makeText(this@StickerPackListActivity, R.string.pack_imported, Toast.LENGTH_SHORT).show()
                stickerPackList.clear()
                stickerPackList.addAll(freshPacks)
                allStickerPacksListAdapter.setStickerPackList(stickerPackList)
                allStickerPacksListAdapter.notifyItemInserted(stickerPackList.size - 1)
                updateEmptyState()
                
                supportActionBar?.title = resources.getQuantityString(R.plurals.title_activity_sticker_packs_list, stickerPackList.size)
                packRecyclerView.smoothScrollToPosition(stickerPackList.size - 1)
            } catch (e: Exception) {
                Toast.makeText(this@StickerPackListActivity, getString(R.string.import_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deletePack(position: Int) {
        if (position !in stickerPackList.indices) return
        val pack = stickerPackList[position]
        
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WastickerParser.deleteStickerPack(this@StickerPackListActivity, pack.identifier)
                    StickerContentProvider.getInstance()?.invalidateStickerPackList()
                }
                
                stickerPackList.removeAt(position)
                allStickerPacksListAdapter.notifyItemRemoved(position)
                if (position < stickerPackList.size) {
                    allStickerPacksListAdapter.notifyItemRangeChanged(position, stickerPackList.size - position)
                }
                updateEmptyState()
                supportActionBar?.title = resources.getQuantityString(R.plurals.title_activity_sticker_packs_list, stickerPackList.size)
                Toast.makeText(this@StickerPackListActivity, R.string.pack_deleted, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@StickerPackListActivity, getString(R.string.error_with_message, e.message), Toast.LENGTH_LONG).show()
                allStickerPacksListAdapter.notifyItemChanged(position)
            }
        }
    }

    private fun updateEmptyState() {
        if (stickerPackList.isEmpty()) {
            packRecyclerView.visibility = View.GONE
            emptyStateLayout.visibility = View.VISIBLE
            importFab.visibility = View.GONE
        } else {
            packRecyclerView.visibility = View.VISIBLE
            emptyStateLayout.visibility = View.GONE
            importFab.visibility = View.VISIBLE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1001, Menu.NONE, R.string.settings)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1001) {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            // Background whitelist check - optimized to only notify changes if state actually changes
            withContext(Dispatchers.IO) {
                val animationsChanged = allStickerPacksListAdapter.animationsEnabledCache == null
                for (i in stickerPackList.indices) {
                    val stickerPack = stickerPackList[i]
                    val isWhitelisted = WhitelistCheck.isWhitelisted(this@StickerPackListActivity, stickerPack.identifier)
                    if (stickerPack.isWhitelisted != isWhitelisted || animationsChanged) {
                        stickerPack.setIsWhitelisted(isWhitelisted)
                        withContext(Dispatchers.Main) {
                            allStickerPacksListAdapter.notifyItemChanged(i)
                        }
                    }
                }
            }
        }
    }

    private var globalLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    private fun showStickerPackList(stickerPackList: List<StickerPack>) {
        allStickerPacksListAdapter = StickerPackListAdapter(stickerPackList) { pack -> 
            addStickerPackToWhatsApp(pack.identifier, pack.name) 
        }
        packRecyclerView.adapter = allStickerPacksListAdapter
        packLayoutManager = LinearLayoutManager(this)
        packLayoutManager.orientation = RecyclerView.VERTICAL
        packRecyclerView.layoutManager = packLayoutManager
        packRecyclerView.setHasFixedSize(true)
        packRecyclerView.addOnScrollListener(scrollListener)
        // Modern view caching
        packRecyclerView.setItemViewCacheSize(20)
        
        globalLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { recalculateColumnCount() }
        packRecyclerView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    private fun recalculateColumnCount() {
        val rvWidth = packRecyclerView.width
        if (rvWidth <= 0) return

        val density = resources.displayMetrics.density
        val overhead = Math.round((12 + 12 + 16 + 16 + 48) * density)
        val imageRowWidth = rvWidth - overhead
        if (imageRowWidth <= 0) return

        val previewSize = resources.getDimensionPixelSize(R.dimen.sticker_pack_list_item_preview_image_size)
        val maxNumberOfImagesInARow = Math.min(STICKER_PREVIEW_DISPLAY_LIMIT, Math.max(imageRowWidth / previewSize, 1))
        val minMarginBetweenImages = if (maxNumberOfImagesInARow > 1) {
            (imageRowWidth - maxNumberOfImagesInARow * previewSize) / (maxNumberOfImagesInARow - 1)
        } else 0
        
        allStickerPacksListAdapter.setImageRowSpec(maxNumberOfImagesInARow, minMarginBetweenImages)
        
        if (packRecyclerView.viewTreeObserver.isAlive) {
            packRecyclerView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        }
    }

    companion object {
        const val EXTRA_STICKER_PACK_LIST_DATA = "sticker_pack_list"
        private const val STICKER_PREVIEW_DISPLAY_LIMIT = 5
    }
}
