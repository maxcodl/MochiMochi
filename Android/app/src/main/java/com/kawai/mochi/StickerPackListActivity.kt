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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.Pair
import kotlin.math.roundToInt


class StickerPackListActivity : AddStickerPackActivity(), ThumbnailRegenerationManager.Listener {
    private lateinit var packLayoutManager: LinearLayoutManager
    private lateinit var packRecyclerView: RecyclerView
    private lateinit var allStickerPacksListAdapter: StickerPackListAdapter
    private var stickerPackList = ArrayList<StickerPack>()
    private lateinit var emptyStateLayout: View
    private lateinit var importFab: ExtendedFloatingActionButton
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var itemTouchHelper: ItemTouchHelper
    
    private var importProgressContainer: View? = null
    private var importProgressBar: LinearProgressIndicator? = null
    private var importStatusText: TextView? = null

    private val telegramImportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshStickerPacks()
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            // Feature removed: no longer notifying adapter of scroll state
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

    override fun showProgressBar(message: String?) {
        importProgressContainer?.visibility = View.VISIBLE
        importProgressBar?.isIndeterminate = true
        importStatusText?.text = message ?: getString(R.string.add_to_whatsapp)
    }

    override fun hideProgressBar() {
        if (!ThumbnailRegenerationManager.isRegenerating()) {
            importProgressContainer?.visibility = View.GONE
        }
    }

    override fun updateProgress(current: Int, total: Int, message: String?) {
        importProgressContainer?.visibility = View.VISIBLE
        importProgressBar?.isIndeterminate = false
        importProgressBar?.max = total
        importProgressBar?.setProgressCompat(current, true)
        if (message != null) {
            importStatusText?.text = message
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
        
        importProgressContainer = findViewById(R.id.import_progress_container)
        importProgressBar = findViewById(R.id.import_progress_bar)
        importStatusText = findViewById(R.id.import_status_text)

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

        findViewById<Button>(R.id.import_button)?.setOnClickListener { showImportChoice() }
        importFab.setOnClickListener { showImportChoice() }

        swipeRefresh.setOnRefreshListener {
            refreshStickerPacks()
        }

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                swipeRefresh.isEnabled = actionState == ItemTouchHelper.ACTION_STATE_IDLE
            }
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
                        .setNegativeButton(R.string.cancel) { _, _ -> cancelSwipeDelete(position) }
                        .setOnCancelListener { cancelSwipeDelete(position) }
                        .show()
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(packRecyclerView)

        updateEmptyState()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StickerUpdateManager.updateEvent.collect {
                    refreshStickerPacks()
                }
            }
        }
        
        // Listen for thumbnail regeneration progress
        ThumbnailRegenerationManager.addListener(this)
    }

    override fun onDestroy() {
        ThumbnailRegenerationManager.removeListener(this)
        super.onDestroy()
    }

    // ThumbnailRegenerationManager.Listener implementation
    override fun onProgress(current: Int, total: Int) {
        runOnUiThread {
            importProgressContainer?.visibility = View.VISIBLE
            importProgressBar?.isIndeterminate = false
            importProgressBar?.max = total
            importProgressBar?.setProgressCompat(current, true)
            importStatusText?.text = getString(R.string.progress_format, current, total)
        }
    }

    override fun onFinished() {
        runOnUiThread {
            importProgressContainer?.visibility = View.GONE
            refreshStickerPacks()
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            importProgressContainer?.visibility = View.GONE
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStickerPacks() {
        lifecycleScope.launch {
            try {
                val freshPacks = withContext(Dispatchers.IO) {
                    StickerPackLoader.fetchStickerPacks(this@StickerPackListActivity)
                }
                stickerPackList.clear()
                stickerPackList.addAll(freshPacks)

                if (::allStickerPacksListAdapter.isInitialized) {
                    allStickerPacksListAdapter.submitList(ArrayList(stickerPackList))
                }
                
                updateEmptyState()
                supportActionBar?.title = resources.getQuantityString(R.plurals.title_activity_sticker_packs_list, stickerPackList.size)
                
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
            ItemTouchHelper.Callback.getDefaultUIUtil().clearView(viewHolder.itemView)
        }
        allStickerPacksListAdapter.notifyItemChanged(position)
    }


    private fun showImportChoice() {
        val sheet = ImportChoiceBottomSheet.newInstance()
        sheet.setListener(object : ImportChoiceBottomSheet.Listener {
            override fun onImportFromFile() { openFilePicker() }
            override fun onImportFromTelegram() {
                telegramImportLauncher.launch(
                    Intent(this@StickerPackListActivity, TelegramImportActivity::class.java)
                )
            }
        })
        sheet.show(supportFragmentManager, "import_choice")
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_wasticker_file)))
    }

    private fun importWastickerFile(uri: Uri) {
        importProgressContainer?.visibility = View.VISIBLE
        importProgressBar?.isIndeterminate = true
        importStatusText?.setText(R.string.importing_pack)
        
        lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val importedId = WastickerParser.importStickerPack(this@StickerPackListActivity, uri) { current, total ->
                        runOnUiThread {
                            importProgressBar?.isIndeterminate = false
                            importProgressBar?.max = total
                            importProgressBar?.setProgressCompat(current, true)
                            importStatusText?.text = getString(R.string.import_progress, current, total)
                        }
                    }
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
                    } else {
                        stickerPackList.add(importedPack)
                    }
                    allStickerPacksListAdapter.submitList(ArrayList(stickerPackList))
                } else {
                    refreshStickerPacks()
                }

                updateEmptyState()
                supportActionBar?.title = resources.getQuantityString(R.plurals.title_activity_sticker_packs_list, stickerPackList.size)
                packRecyclerView.smoothScrollToPosition(stickerPackList.size - 1)
            } catch (e: Exception) {
                Toast.makeText(this@StickerPackListActivity, getString(R.string.import_error, e.message), Toast.LENGTH_LONG).show()
            } finally {
                if (!ThumbnailRegenerationManager.isRegenerating()) {
                    importProgressContainer?.visibility = View.GONE
                }
            }
        }
    }

    private fun deletePack(position: Int) {
        if (position !in stickerPackList.indices) return
        val pack = stickerPackList[position]

        stickerPackList.removeAt(position)
        allStickerPacksListAdapter.submitList(ArrayList(stickerPackList))
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
                stickerPackList.add(position.coerceAtMost(stickerPackList.size), pack)
                allStickerPacksListAdapter.submitList(ArrayList(stickerPackList))
                updateEmptyState()
                supportActionBar?.title = resources.getQuantityString(
                    R.plurals.title_activity_sticker_packs_list, stickerPackList.size)
                Toast.makeText(this@StickerPackListActivity, getString(R.string.error_with_message, e.message), Toast.LENGTH_LONG).show()
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
        refreshStickerPacks()
        if (::allStickerPacksListAdapter.isInitialized) {
            allStickerPacksListAdapter.invalidateAnimationsCache()
        }
    }

    private var globalLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    private fun showStickerPackList(packList: List<StickerPack>) {
        allStickerPacksListAdapter = StickerPackListAdapter { pack -> 
            addStickerPackToWhatsApp(pack.identifier, pack.name) 
        }
        allStickerPacksListAdapter.submitList(packList)
        
        packLayoutManager = LinearLayoutManager(this)
        packLayoutManager.orientation = RecyclerView.VERTICAL
        packRecyclerView.layoutManager = packLayoutManager
        packRecyclerView.itemAnimator = null
        packRecyclerView.layoutAnimation = null
        packRecyclerView.adapter = allStickerPacksListAdapter
        packRecyclerView.setHasFixedSize(true)
        packRecyclerView.addOnScrollListener(scrollListener)
        
        globalLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { recalculateColumnCount() }
        packRecyclerView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    private fun recalculateColumnCount() {
        val rvWidth = packRecyclerView.width
        if (rvWidth <= 0) return

        val density = resources.displayMetrics.density
        // Correct overhead: Card margins (12+12) + RV start (16) + RV to button (16) + button (48) + button end (16)
        val overhead = ((12 + 12 + 16 + 16 + 48 + 16) * density).roundToInt()
        val imageRowWidth = rvWidth - overhead
        if (imageRowWidth <= 0) return

        val maxNumberOfImagesInARow = STICKER_PREVIEW_DISPLAY_LIMIT
        
        // Aim for a small fixed margin between stickers
        val desiredMargin = (4 * density).roundToInt()
        val totalMargin = desiredMargin * (maxNumberOfImagesInARow - 1)
        
        // Calculate the preview size that allows exactly 5 stickers to fit
        val previewSize = (imageRowWidth - totalMargin) / maxNumberOfImagesInARow
        
        // Recalculate actual margin to distribute any rounding remainder
        val actualMargin = (imageRowWidth - maxNumberOfImagesInARow * previewSize) / (maxNumberOfImagesInARow - 1)
        
        allStickerPacksListAdapter.setImageRowSpec(maxNumberOfImagesInARow, actualMargin, previewSize)
        
        if (packRecyclerView.viewTreeObserver.isAlive) {
            packRecyclerView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        }
    }

    companion object {
        const val EXTRA_STICKER_PACK_LIST_DATA = "sticker_pack_list"
        private const val STICKER_PREVIEW_DISPLAY_LIMIT = 5
    }
}
