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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.kawai.mochi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.Pair

class StickerPackListActivity : AddStickerPackActivity() {
    private lateinit var packLayoutManager: LinearLayoutManager
    private lateinit var packRecyclerView: RecyclerView
    private lateinit var allStickerPacksListAdapter: StickerPackListAdapter
    private var stickerPackList = ArrayList<StickerPack>()
    private lateinit var emptyStateLayout: View
    private lateinit var importFab: ExtendedFloatingActionButton
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var migrationJob: Job? = null
    private lateinit var itemTouchHelper: ItemTouchHelper

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
        refreshStickerPacks()
    }

    private val mergePacksLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshStickerPacks()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sticker_pack_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        swipeRefresh = findViewById(R.id.swipe_refresh)
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

        swipeRefresh.setOnRefreshListener {
            refreshStickerPacks(true)
        }

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            // Disable pull-to-refresh the moment a swipe gesture starts so the two
            // gestures can't interfere with each other.
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                swipeRefresh.isEnabled = actionState == ItemTouchHelper.ACTION_STATE_IDLE
            }

            // Re-enable pull-to-refresh once the finger lifts and the view snaps back.
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                swipeRefresh.isEnabled = true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position in stickerPackList.indices) {
                    val pack = stickerPackList[position]
                    MaterialAlertDialogBuilder(this@StickerPackListActivity)
                        .setTitle(R.string.delete_pack_title)
                        .setMessage(getString(R.string.delete_pack_confirm_with_name, pack.name))
                        .setPositiveButton(R.string.delete_button) { _, _ -> deletePack(position) }
                        .setNegativeButton(R.string.cancel) { _, _ -> 
                            cancelSwipeDelete(position)
                        }
                        .setOnCancelListener { 
                            cancelSwipeDelete(position)
                        }
                        .show()
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(packRecyclerView)

        updateEmptyState()
        runThumbnailMigration()
    }

    private fun refreshStickerPacks(fromSwipe: Boolean = false) {
        if (!fromSwipe) swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val freshPacks = withContext(Dispatchers.IO) {
                    StickerPackLoader.fetchStickerPacks(this@StickerPackListActivity)
                }
                stickerPackList.clear()
                stickerPackList.addAll(freshPacks)
                allStickerPacksListAdapter.setStickerPackList(stickerPackList)
                allStickerPacksListAdapter.notifyDataSetChanged()
                updateEmptyState()
                supportActionBar?.title = resources.getQuantityString(R.plurals.title_activity_sticker_packs_list, stickerPackList.size)
                
                runThumbnailMigration()
            } catch (e: Exception) {
                Log.e("ListActivity", "Refresh failed", e)
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun cancelSwipeDelete(position: Int) {
        val viewHolder = packRecyclerView.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {
            // Clear the swipe state from ItemTouchHelper to animate item back to normal position
            ItemTouchHelper.Callback.getDefaultUIUtil().clearView(viewHolder.itemView)
        }
        // Refresh the adapter to ensure the item is properly displayed
        allStickerPacksListAdapter.notifyItemChanged(position)
    }

    private fun runThumbnailMigration() {
        migrationJob?.cancel()
        val snapshot = ArrayList(stickerPackList)
        if (snapshot.isEmpty()) return

        migrationJob = lifecycleScope.launch(Dispatchers.IO) {
            val rootPath = WastickerParser.getStickerFolderPath(this@StickerPackListActivity)
            val isSAF = WastickerParser.isCustomPathUri(this@StickerPackListActivity)
            
            snapshot.forEach { pack ->
                try {
                    if (isSAF) {
                        val rootDoc = DocumentFile.fromTreeUri(this@StickerPackListActivity, Uri.parse(rootPath))
                        val packDir = rootDoc?.findFile(pack.identifier) ?: return@forEach
                        val thumbDir = packDir.findFile("thumbs") ?: packDir.createDirectory("thumbs") ?: return@forEach
                        
                        pack.stickers?.forEach { sticker ->
                            val thumbName = "thumb_" + sticker.imageFileName
                            if (thumbDir.findFile(thumbName) == null) {
                                val sourceFile = packDir.findFile(sticker.imageFileName)
                                if (sourceFile != null) {
                                    val newThumb = thumbDir.createFile("image/webp", thumbName)
                                    if (newThumb != null) {
                                        StickerProcessor.createThumbnail(this@StickerPackListActivity, sourceFile.uri, newThumb.getUri())
                                    }
                                }
                            }
                        }
                    } else {
                        val packDir = File(rootPath, pack.identifier)
                        if (!packDir.exists()) return@forEach
                        val thumbDir = File(packDir, "thumbs")
                        if (!thumbDir.exists()) thumbDir.mkdirs()
                        
                        pack.stickers?.forEach { sticker ->
                            val thumbFile = File(thumbDir, "thumb_" + sticker.imageFileName)
                            if (!thumbFile.exists()) {
                                StickerProcessor.createThumbnail(File(packDir, sticker.imageFileName), thumbFile)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Migration", "Failed for pack ${pack.identifier}", e)
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
                val imported = withContext(Dispatchers.IO) {
                    val importedId = WastickerParser.importStickerPack(this@StickerPackListActivity, uri)
                    StickerContentProvider.getInstance()?.invalidateStickerPackList()
                    val importedPack = if (!importedId.isNullOrBlank()) {
                        StickerPackLoader.fetchStickerPack(this@StickerPackListActivity, importedId)
                    } else {
                        null
                    }
                    Pair(importedId, importedPack)
                }

                Toast.makeText(this@StickerPackListActivity, R.string.pack_imported, Toast.LENGTH_SHORT).show()

                val importedId = imported.first
                val importedPack = imported.second
                if (importedPack != null) {
                    val existingIndex = stickerPackList.indexOfFirst { it.identifier == importedId }
                    if (existingIndex >= 0) {
                        stickerPackList[existingIndex] = importedPack
                        allStickerPacksListAdapter.notifyItemChanged(existingIndex)
                    } else {
                        stickerPackList.add(importedPack)
                        allStickerPacksListAdapter.notifyItemInserted(stickerPackList.size - 1)
                    }
                    runThumbnailMigration()
                } else {
                    refreshStickerPacks()
                }

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

        // Optimistic remove — update the UI instantly so the row disappears immediately.
        stickerPackList.removeAt(position)
        allStickerPacksListAdapter.notifyDataSetChanged()
        updateEmptyState()
        supportActionBar?.title = resources.getQuantityString(
            R.plurals.title_activity_sticker_packs_list, stickerPackList.size)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WastickerParser.deleteStickerPack(this@StickerPackListActivity, pack.identifier)
                    StickerContentProvider.getInstance()?.invalidateStickerPackList()
                }
                Toast.makeText(this@StickerPackListActivity, R.string.pack_deleted, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Roll back the optimistic remove on failure.
                stickerPackList.add(position.coerceAtMost(stickerPackList.size), pack)
                allStickerPacksListAdapter.notifyDataSetChanged()
                updateEmptyState()
                supportActionBar?.title = resources.getQuantityString(
                    R.plurals.title_activity_sticker_packs_list, stickerPackList.size)
                Toast.makeText(
                    this@StickerPackListActivity,
                    getString(R.string.error_with_message, e.message),
                    Toast.LENGTH_LONG
                ).show()
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
        menu.add(Menu.NONE, 1002, Menu.NONE, R.string.merge_packs_menu_item)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1001 -> {
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                true
            }
            1002 -> {
                mergePacksLauncher.launch(Intent(this, MergeStickerPacksActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
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
        
        // HIGH PERFORMANCE: Custom LayoutManager with extra pre-fetch space
        packLayoutManager = object : LinearLayoutManager(this) {
            override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
                // Pre-render 1000px worth of items off-screen so they are ready before scroll
                extraLayoutSpace[0] = 1000
                extraLayoutSpace[1] = 1000
            }
        }
        
        packLayoutManager.orientation = RecyclerView.VERTICAL
        packRecyclerView.layoutManager = packLayoutManager
        packRecyclerView.itemAnimator = null
        packRecyclerView.layoutAnimation = null
        packRecyclerView.adapter = allStickerPacksListAdapter
        packRecyclerView.setHasFixedSize(true)
        packRecyclerView.addOnScrollListener(scrollListener)
        
        // AGGRESSIVE CACHING: Keep 40 rows alive off-screen to prevent "despawning"
        packRecyclerView.setItemViewCacheSize(40)
        
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
