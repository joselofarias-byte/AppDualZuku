package com.nathanhanapps.appdual

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nathanhanapps.appdual.databinding.ActivityMainBinding
import com.nathanhanapps.appdual.databinding.BottomSheetAppActionsBinding
import com.nathanhanapps.appdual.databinding.BottomSheetBatchActionsBinding
import com.nathanhanapps.appdual.databinding.DialogExportFilenameBinding
import com.nathanhanapps.appdual.databinding.DialogTappableMessageBinding
import com.nathanhanapps.appdual.databinding.DialogWorkspaceNameBinding
import com.nathanhanapps.appdual.databinding.ItemWorkspaceActionBinding
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {

    // ── Background executor ──────────────────────────────────────────────────
    private val bg = Executors.newSingleThreadExecutor()

    // ── Core components ──────────────────────────────────────────────────────
    private lateinit var repo:      AppRepository
    private lateinit var adapter:   AppAdapter
    private lateinit var shell:     IShellExecutor
    private lateinit var wsRepo:    WorkspaceRepository
    private lateinit var wsAdapter: WorkspaceAdapter
    private lateinit var dhizukuBridge: DhizukuDeviceOwnerBridge
    private lateinit var binding:   ActivityMainBinding

    // ── State caches ─────────────────────────────────────────────────────────
    private var cachedFullList:   List<AppItem>       = emptyList()
    private var cachedWorkspaces: List<WorkspaceInfo> = emptyList()

    // Android assigns app UIDs as userId * 100000 + appId.
    private val runtimeUserId: Int get() = android.os.Process.myUid() / 100000
    private val isSecondaryRuntimeUser: Boolean get() = runtimeUserId != 0

    // ── Per-space filter chips ───────────────────────────────────────────────
    /** Selected workspace userIds for the space filter row. Empty = "All" (no filter). */
    private var spaceFilterUserIds: Set<Int> = emptySet()
    /** userIds the chip row was last built for, so we only rebuild when the workspace set changes. */
    private var chipWorkspaceIds: List<Int> = emptyList()

    // ── UI / lifecycle flags ──────────────────────────────────────────────────
    private var isInitialized = false
    private var aboutClickCount = 0
    private var currentFilter = AppRepository.AppFilter.ALL

    // ── Shimmer debounce ─────────────────────────────────────────────────────
    private val uiHandler = Handler(Looper.getMainLooper())
    private var showShimmerRunnable: Runnable? = null

    // ── Batch management ─────────────────────────────────────────────────────
    private var batchDialog: BottomSheetDialog? = null
    private var pendingExportJson: String? = null
    private var pendingExportCount: Int = 0
    private var pendingWorkspaceImport: WorkspaceInfo? = null

    // Must be registered during construction (before onCreate), per ComponentActivity contract.
    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeExportToUri(uri) else pendingExportJson = null }

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readImportFromUri(it) } }

    private val workspaceImportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val workspace = pendingWorkspaceImport
        pendingWorkspaceImport = null
        if (uri != null && workspace != null) readWorkspaceImportFromUri(uri, workspace)
    }

    companion object {
        private const val REQUEST_SHIZUKU_PERMISSION = 1234
        private const val SHIMMER_DELAY_MS = 400L

        // Used to sanity-check that a "clone" selection looks like a full workspace app
        // list before deleting everything else out of a target workspace. Each inner list
        // is a set of interchangeable package names satisfying one requirement - AOSP vs.
        // GMS-flavored builds ship some of these under different names. This no longer
        // hard-blocks the clone (see showIncompleteSelectionDialog) - it's a warning gate.
        private val CLONE_REQUIRED_PACKAGE_GROUPS = listOf(
            listOf("com.android.settings"),
            listOf("com.android.providers.settings"),
            listOf("com.android.providers.media", "com.android.providers.media.module")
        )

        // Reference list shown only after the 5-tap reveal - broader than what's actually
        // enforced above, for the user's own context on what a "full" workspace app set
        // typically includes. Deliberately excludes Camera: its package name varies too
        // much by OEM to be a useful reference, let alone an enforced check.
        private val CLONE_REFERENCE_PACKAGE_GROUPS = CLONE_REQUIRED_PACKAGE_GROUPS + listOf(
            listOf("com.android.documentsui"),
            listOf("com.android.externalstorage"),
            listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller"),
            listOf("com.android.packageinstaller", "com.google.android.packageinstaller")
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Material 3 Dynamic Colors
        DynamicColors.applyToActivityIfAvailable(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        dhizukuBridge = DhizukuDeviceOwnerBridge(this)
        setContentView(binding.root)

        applyStatusBarToMatchToolbar()
        setupUI()
        setupAboutVersion()
        if (isSecondaryRuntimeUser) configureSecondaryUserUi()

        repo = AppRepository(this)
        loadAppsUser0()
        initializeExecution()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        tintOverflowIcon()

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.queryHint = getString(R.string.search_apps)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText.orEmpty())
                return true
            }
        })

        // Hide search when switched to Settings
        searchItem.isVisible = binding.layoutAppList.isVisible

        // Selection-shortcut items only make sense in batch mode, on the app list
        val inBatch = binding.layoutAppList.isVisible && ::adapter.isInitialized && adapter.batchMode
        menu.findItem(R.id.action_select_all).isVisible = inBatch
        menu.findItem(R.id.action_invert_selection).isVisible = inBatch
        menu.findItem(R.id.action_deselect_all).isVisible = inBatch

        return true
    }

    /** Matches the toolbar's "⋮" overflow icon to the bottom nav's (unselected) icon color. */
    private fun tintOverflowIcon() {
        val tint = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        binding.toolbar.overflowIcon?.mutate()?.setTint(tint)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all -> { adapter.selectAll(); true }
            R.id.action_invert_selection -> { adapter.invertSelection(); true }
            R.id.action_deselect_all -> { adapter.deselectAll(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Hidden Developer Console
    // ════════════════════════════════════════════════════════════════════════

    private fun setupDevPanel() {
        val cardAbout   = binding.cardAbout
        val cardDevPanel = binding.cardDevPanel
        val etDevCmd    = binding.etDevCmd
        val btnDevRun   = binding.btnDevRun
        val btnDevClear = binding.btnDevClear
        val tvDevOutput = binding.tvDevOutput
        val scrollDevOutput = binding.scrollDevOutput

        // 5-tap unlock on the About card
        cardAbout.setOnClickListener {
            aboutClickCount++
            val remaining = 5 - aboutClickCount
            when {
                aboutClickCount in 1..4 -> {
                    Toast.makeText(this, getString(R.string.dev_remaining_taps, remaining, if (remaining > 1) "s" else ""), Toast.LENGTH_SHORT).show()
                }
                aboutClickCount >= 5 -> {
                    aboutClickCount = 0
                    val isCurrentlyVisible = cardDevPanel.isVisible
                    cardDevPanel.isVisible = !isCurrentlyVisible
                    if (!isCurrentlyVisible) {
                        Toast.makeText(this, getString(R.string.dev_console_unlocked), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Run button
        btnDevRun.setOnClickListener {
            val cmd = etDevCmd.text?.toString()?.trim() ?: return@setOnClickListener
            if (cmd.isEmpty()) return@setOnClickListener
            if (!::shell.isInitialized) {
                tvDevOutput.text = getString(R.string.error_shizuku_not_init)
                return@setOnClickListener
            }

            btnDevRun.isEnabled = false
            tvDevOutput.text = getString(R.string.dev_running_cmd, cmd)

            shell.execWhenReady(cmd) { output ->
                runOnUiThread {
                    btnDevRun.isEnabled = true
                    val result = getString(R.string.dev_output_format, cmd, "─".repeat(40), output.ifBlank { getString(R.string.dev_no_output) })
                    tvDevOutput.text = result
                    // Scroll to top so user sees the command echo
                    scrollDevOutput.post { scrollDevOutput.scrollTo(0, 0) }
                }
            }
        }

        // Allow Enter key to run command
        etDevCmd.setOnEditorActionListener { _, _, _ ->
            btnDevRun.performClick()
            true
        }

        // Clear button
        btnDevClear.setOnClickListener {
            tvDevOutput.setText(R.string.dev_output_hint)
            etDevCmd.text?.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (::shell.isInitialized) runCatching { shell.unbind() }
        bg.shutdown()
    }

    /**
     * Posts to [bg], skipping the task if the activity has already been torn down.
     * Async chains (e.g. querying packages per workspace) can outlive the activity -
     * without this guard, the final `bg.execute` throws RejectedExecutionException
     * once onDestroy() has shut the executor down (easiest to hit with clone
     * workspaces, whose package lists are large enough to make the query slow).
     */
    private fun runBg(task: () -> Unit) {
        if (bg.isShutdown) return
        bg.execute(task)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI Setup
    // ════════════════════════════════════════════════════════════════════════

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)

        // ── App list RecyclerView ────────────────────────────────────────────
        val columns = resources.getInteger(R.integer.app_grid_columns)
        binding.rvApps.layoutManager = GridLayoutManager(this, columns)
        adapter = AppAdapter(
            onItemClick = { item ->
                if (isSecondaryRuntimeUser) launchLocalApp(item) else showAppActionsBottomSheet(item)
            },
            onLongPress = { item ->
                if (isSecondaryRuntimeUser) launchLocalApp(item) else enterBatchModeAndSelect(item)
            },
            onSelectionChanged = { selected -> updateBatchFab(selected) }
        )
        binding.rvApps.adapter = adapter

        // ── Per-space filter chips (All / Main / Clone1 / Work1 / …) ────────────
        rebuildSpaceFilterChips()

        // ── Bottom navigation ────────────────────────────────────────────────
        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_all -> {
                    currentFilter = AppRepository.AppFilter.ALL
                    showAppList()
                    loadAppsUser0()
                    true
                }
                R.id.nav_user -> {
                    currentFilter = AppRepository.AppFilter.USER_ONLY
                    showAppList()
                    loadAppsUser0()
                    true
                }
                R.id.nav_system -> {
                    currentFilter = AppRepository.AppFilter.SYSTEM_ONLY
                    showAppList()
                    loadAppsUser0()
                    true
                }
                R.id.nav_settings -> {
                    showSettings()
                    true
                }
                else -> false
            }
        }

        // ── About card secret tap ───────────────────────────────────────────
        setupDevPanel()

        // ── Workspace adapter for Settings ───────────────────────────────────
        wsAdapter = WorkspaceAdapter(
            onStart  = { ws -> doStartWorkspace(ws) },
            onStop   = { ws -> doStopWorkspace(ws) },
            onSwitch = { ws -> confirmSwitchWorkspace(ws) },
            onExport = { ws -> exportWorkspaceApps(ws) },
            onImport = { ws -> beginImportWorkspaceApps(ws) },
            onRemove = { ws -> confirmRemoveWorkspace(ws) }
        )
        binding.rvWorkspaces.layoutManager = LinearLayoutManager(this)
        binding.rvWorkspaces.adapter       = wsAdapter
        binding.rvWorkspaces.isNestedScrollingEnabled = false

        // ── Create workspace button (long-press to customize the name) ──────────
        binding.btnCreateWorkspace.setOnClickListener {
            if (!requireShellOrToast()) return@setOnClickListener
            val name = wsRepo.suggestName(cachedWorkspaces, "Work")
            doCreateWorkspace(name, "managed")
        }
        binding.btnCreateWorkspace.setOnLongClickListener {
            if (requireShellOrToast()) promptCreateWorkspaceName("managed")
            true
        }

        // ── Create clone workspace button (long-press to customize the name) ────
        binding.btnCreateCloneWorkspace.setOnClickListener {
            if (!requireShellOrToast()) return@setOnClickListener
            val name = wsRepo.suggestName(cachedWorkspaces, "Clone")
            doCreateWorkspace(name, "clone")
        }
        binding.btnCreateCloneWorkspace.setOnLongClickListener {
            if (requireShellOrToast()) promptCreateWorkspaceName("clone")
            true
        }

        // ── Create a full secondary user through Dhizuku Device Owner ────────
        binding.btnCreateDhizukuUser.setOnClickListener {
            val name = wsRepo.suggestName(cachedWorkspaces, "Dual")
            doCreateDhizukuUser(name)
        }
        binding.btnCreateDhizukuUser.setOnLongClickListener {
            promptCreateWorkspaceName("dhizuku")
            true
        }

        // ── Refresh workspaces button ────────────────────────────────────────
        binding.btnRefreshWorkspaces.setOnClickListener {
            if (::wsRepo.isInitialized) loadWorkspaces()
        }

        // ── Execution mode (root vs. Shizuku) card ───────────────────────────
        setupExecutionModeCard()

        // ── Batch management ─────────────────────────────────────────────────
        binding.chipBatchMode.setOnClickListener {
            val newMode = !adapter.batchMode
            adapter.setBatchMode(newMode)
            binding.chipBatchMode.isChecked = newMode
            invalidateOptionsMenu()
        }
        binding.fabBatchActions.setOnClickListener { showBatchActionsBottomSheet() }
        // Set in code, not XML: ExtendedFloatingActionButton's default Material3 style
        // applies its own icon tint via a theme overlay that can outrank a plain
        // app:iconTint attribute, so only an explicit post-inflation set is reliable.
        binding.fabBatchActions.iconTint = ColorStateList.valueOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        )
    }

    // ── Per-space filter chips ───────────────────────────────────────────────

    /** (Re)builds the chip row from [cachedWorkspaces]: "All" plus one chip per space. */
    private fun rebuildSpaceFilterChips() {
        val group = binding.chipGroupSpaceFilter
        group.removeAllViews()

        val allChip = layoutInflater.inflate(R.layout.item_filter_chip, group, false) as Chip
        allChip.text = getString(R.string.filter_all)
        allChip.tag = null
        allChip.setOnClickListener { onSpaceFilterChipClicked(null) }
        group.addView(allChip)

        for (ws in cachedWorkspaces.sortedBy { it.userId }) {
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, group, false) as Chip
            chip.text = if (ws.isMainUser) getString(R.string.main_space) else ws.displayName
            chip.tag = ws.userId
            chip.setOnClickListener { onSpaceFilterChipClicked(ws.userId) }
            group.addView(chip)
        }

        updateSpaceFilterChipStates()
    }

    /** Rebuilds the chip row only when the set of known workspaces actually changed. */
    private fun refreshSpaceFilterChipsIfNeeded() {
        val ids = cachedWorkspaces.map { it.userId }.sorted()
        if (ids == chipWorkspaceIds) return
        chipWorkspaceIds = ids
        // Drop selections for spaces that no longer exist (e.g. a removed workspace).
        spaceFilterUserIds = spaceFilterUserIds.intersect(ids.toSet())
        rebuildSpaceFilterChips()
        adapter.setWorkspaceFilter(spaceFilterUserIds)
    }

    /**
     * Tapping "All" (null) clears the filter. Tapping a space chip toggles it and, if that
     * empties the selection, falls back to "All" automatically.
     */
    private fun onSpaceFilterChipClicked(tappedUserId: Int?) {
        spaceFilterUserIds = if (tappedUserId == null) {
            emptySet()
        } else if (spaceFilterUserIds.contains(tappedUserId)) {
            spaceFilterUserIds - tappedUserId
        } else {
            spaceFilterUserIds + tappedUserId
        }
        adapter.setWorkspaceFilter(spaceFilterUserIds)
        updateSpaceFilterChipStates()
    }

    private fun updateSpaceFilterChipStates() {
        val group = binding.chipGroupSpaceFilter
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as Chip
            val userId = chip.tag as? Int
            chip.isChecked = if (userId == null) spaceFilterUserIds.isEmpty()
                              else spaceFilterUserIds.contains(userId)
        }
    }

    private fun enterBatchModeAndSelect(item: AppItem) {
        if (!adapter.batchMode) {
            adapter.setBatchMode(true)
            binding.chipBatchMode.isChecked = true
            invalidateOptionsMenu()
        }
        adapter.toggleSelection(item.packageName)
    }

    private fun updateBatchFab(selected: Set<String>) {
        if (selected.isEmpty()) {
            binding.fabBatchActions.isVisible = false
        } else {
            binding.fabBatchActions.text = getString(R.string.batch_fab_selected, selected.size)
            binding.fabBatchActions.isVisible = true
            // Setting .text alone doesn't re-layout an ExtendedFloatingActionButton that
            // hasn't gone through an extend()/shrink() transition yet - without this it
            // stays in its initial icon-only "shrunk" state and the text never shows.
            binding.fabBatchActions.extend()
        }
    }

    private fun setupExecutionModeCard() {
        binding.switchUseRoot.isChecked = Prefs.useRoot(this)
        binding.switchUseRoot.setOnCheckedChangeListener { _, checked ->
            if (Prefs.useRoot(this) == checked) return@setOnCheckedChangeListener
            Prefs.setUseRoot(this, checked)
            reinitializeExecution()
        }
        refreshRootStatusText()
    }

    private fun refreshRootStatusText() {
        binding.tvRootStatus.setText(R.string.root_checking)
        runBg {
            val available = RootShellClient.isRootAvailable()
            runOnUiThread {
                binding.tvRootStatus.setText(
                    if (available) R.string.root_detected_yes else R.string.root_detected_no
                )
            }
        }
    }

    private fun setupAboutVersion() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            val appName = getString(R.string.app_name)
            binding.tvAppVersion.text = getString(R.string.app_name_version, appName, version)
        } catch (e: Exception) {
            binding.tvAppVersion.text = getString(R.string.app_name_version, getString(R.string.app_name), "1.5")
        }
    }

    private fun configureSecondaryUserUi() {
        binding.cardSecondaryUser.isVisible = true
        binding.tvSecondaryUserTitle.text = "Dual1 · User $runtimeUserId"
        binding.tvSecondaryUserStatus.text =
            getString(R.string.secondary_user_status_needs_dhizuku, runtimeUserId)

        // Workspace management belongs to User 0. In the managed secondary user AppDual
        // is intentionally a lightweight local launcher and return portal.
        binding.layoutAppListControls.isVisible = false
        binding.fabBatchActions.isVisible = false
        binding.bottomNav.menu.findItem(R.id.nav_settings).isVisible = false

        binding.btnReturnPrimary.setOnClickListener { returnToPrimaryUser() }
    }

    private fun launchLocalApp(item: AppItem) {
        val intent = packageManager.getLaunchIntentForPackage(item.packageName)
        if (intent == null) {
            Toast.makeText(
                this,
                getString(R.string.local_launch_failed, item.label),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(
                    this,
                    getString(R.string.local_launch_failed, item.label),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun returnToPrimaryUser() {
        fun logoutNow() {
            Toast.makeText(this, R.string.returning_primary, Toast.LENGTH_SHORT).show()
            runBg {
                val affiliation = dhizukuBridge.ensureAffiliation()
                val result = if (affiliation.success) {
                    dhizukuBridge.logoutSecondaryUser()
                } else {
                    affiliation
                }
                if (!result.success) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.failed_generic, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        if (!dhizukuBridge.init()) {
            Toast.makeText(this, "Dhizuku unavailable", Toast.LENGTH_LONG).show()
            return
        }

        if (dhizukuBridge.isPermissionGranted()) {
            logoutNow()
        } else {
            dhizukuBridge.requestPermission { granted, message ->
                runOnUiThread {
                    if (granted) logoutNow()
                    else Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAppList() {
        binding.layoutAppList.isVisible = true
        binding.layoutSettings.isVisible = false
        invalidateOptionsMenu() // Refresh search button visibility
    }

    private fun showSettings() {
        binding.layoutAppList.isVisible = false
        binding.layoutSettings.isVisible = true
        title = getString(R.string.settings)
        if (::wsRepo.isInitialized) loadWorkspaces()
        invalidateOptionsMenu() // Refresh search button visibility
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Shimmer
    // ════════════════════════════════════════════════════════════════════════

    private fun scheduleShowShimmer() {
        cancelShowShimmer()
        showShimmerRunnable = Runnable {
            populateSkeletonList()
            binding.rvApps.isVisible = false
            binding.shimmerOverlay.isVisible = true
            binding.shimmerOverlay.startShimmer()
        }
        uiHandler.postDelayed(showShimmerRunnable!!, SHIMMER_DELAY_MS)
    }

    /**
     * Fills [skeletonList] with just enough placeholder rows to cover the visible area,
     * computed from the container's actual measured height instead of a fixed count -
     * a fixed count of 8 left blank space below it on tall screens. Runs once: by the
     * time this is first called (from the delayed shimmer runnable above) the container
     * has already been through a layout pass, so its height is reliably non-zero.
     */
    private fun populateSkeletonList() {
        val container = binding.skeletonList
        if (container.childCount > 0) return

        val containerHeightPx = binding.appsListContainer.height
        val itemHeightPx = (72 * resources.displayMetrics.density).toInt()
        if (containerHeightPx <= 0 || itemHeightPx <= 0) return

        val count = containerHeightPx / itemHeightPx + 2 // +2: partial trailing row + safety margin
        repeat(count) {
            layoutInflater.inflate(R.layout.item_app_skeleton, container, true)
        }
    }

    private fun hideShimmerNow() {
        cancelShowShimmer()
        binding.shimmerOverlay.stopShimmer()
        binding.shimmerOverlay.isVisible = false
        binding.rvApps.isVisible = true
    }

    private fun cancelShowShimmer() {
        showShimmerRunnable?.let { uiHandler.removeCallbacks(it) }
        showShimmerRunnable = null
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Status bar
    // ════════════════════════════════════════════════════════════════════════

    private fun applyStatusBarToMatchToolbar() {
        val surfaceColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, 0
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = surfaceColor
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = MaterialColors.isColorLight(surfaceColor)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Shizuku initialisation
    // ════════════════════════════════════════════════════════════════════════

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_SHIZUKU_PERMISSION) {
            runOnUiThread {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.shizuku_granted), Toast.LENGTH_SHORT).show()
                    initializeShellAndUpdate()
                } else {
                    Toast.makeText(this, getString(R.string.shizuku_required), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkShizukuAndInitialize() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, getString(R.string.shi_not_run), Toast.LENGTH_LONG).show()
            return
        }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_SHIZUKU_PERMISSION)
        } else {
            initializeShellAndUpdate()
        }
    }

    private fun initializeShellAndUpdate() {
        if (isInitialized) return
        try {
            shell    = ShellClient(this)
            wsRepo   = WorkspaceRepository(shell)
            isInitialized = true
            updateAllWorkspaceStatuses()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_initializing, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Execution mode (Shizuku vs. root) selection
    // ════════════════════════════════════════════════════════════════════════

    private fun initializeExecution() {
        if (isSecondaryRuntimeUser) {
            initializeSecondaryUserMode()
            return
        }

        if (Prefs.useRoot(this)) {
            initRootShellAndUpdate()
        } else {
            checkShizukuAndInitialize()
        }
    }

    private fun initializeSecondaryUserMode() {
        // A full secondary user has its own app data and launcher context. AppDual does
        // not need shell access merely to list and launch apps in that same user.
        if (!dhizukuBridge.init()) {
            binding.tvSecondaryUserStatus.text =
                "Dual user $runtimeUserId · Dhizuku unavailable"
            return
        }

        if (dhizukuBridge.isPermissionGranted()) {
            completeSecondaryUserDhizukuSetup()
            return
        }

        binding.tvSecondaryUserStatus.text =
            getString(R.string.secondary_user_status_needs_dhizuku, runtimeUserId)
        dhizukuBridge.requestPermission { granted, message ->
            runOnUiThread {
                if (granted) {
                    completeSecondaryUserDhizukuSetup()
                } else {
                    binding.tvSecondaryUserStatus.text =
                        "Dual user $runtimeUserId · $message"
                }
            }
        }
    }

    private fun completeSecondaryUserDhizukuSetup() {
        runBg {
            val result = dhizukuBridge.ensureAffiliation()
            runOnUiThread {
                binding.tvSecondaryUserStatus.text = if (result.success) {
                    getString(R.string.secondary_user_status_affiliated, runtimeUserId)
                } else {
                    "Dual user $runtimeUserId · ${result.message}"
                }
            }
        }
    }

    private fun initRootShellAndUpdate() {
        if (isInitialized) return
        runBg {
            val available = RootShellClient.isRootAvailable()
            runOnUiThread {
                if (!available) {
                    Toast.makeText(this, getString(R.string.root_unavailable), Toast.LENGTH_LONG).show()
                    Prefs.setUseRoot(this, false)
                    binding.switchUseRoot.isChecked = false
                    checkShizukuAndInitialize()
                    return@runOnUiThread
                }
                try {
                    shell  = RootShellClient(this)
                    wsRepo = WorkspaceRepository(shell)
                    isInitialized = true
                    updateAllWorkspaceStatuses()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.error_initializing, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Tears down whichever backend is active and switches to the other. */
    private fun reinitializeExecution() {
        if (::shell.isInitialized) runCatching { shell.unbind() }
        isInitialized = false
        initializeExecution()
    }

    private fun requireShellOrToast(silent: Boolean = false): Boolean {
        val useRoot = Prefs.useRoot(this)
        val ok = ::shell.isInitialized && (
            useRoot || (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        )
        if (!ok && !silent) {
            val msgRes = if (useRoot) R.string.root_required else R.string.shizuku_required
            Toast.makeText(this, getString(msgRes), Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    // ════════════════════════════════════════════════════════════════════════
    //  App list loading
    // ════════════════════════════════════════════════════════════════════════

    private fun loadAppsUser0() {
        runOnUiThread { scheduleShowShimmer() }
        runBg {
            try {
                val list = repo.loadInstalledAppsUser0(currentFilter)
                cachedFullList = list
                runOnUiThread {
                    hideShimmerNow()
                    adapter.submitFullList(list)
                    updateTitle(list)
                    updateAllWorkspaceStatuses()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideShimmerNow()
                    Toast.makeText(this, getString(R.string.error_loading_apps, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateTitle(list: List<AppItem>) {
        if (binding.layoutSettings.isVisible) return

        val filterText = when (currentFilter) {
            AppRepository.AppFilter.ALL         -> getString(R.string.filter_all)
            AppRepository.AppFilter.USER_ONLY   -> getString(R.string.filter_user)
            AppRepository.AppFilter.SYSTEM_ONLY -> getString(R.string.filter_system)
        }
        val inWorkspace = list.count { it.isDual }
        val formatted = getString(R.string.app_title_format, filterText, list.size, inWorkspace)

        // Split the string by the newline character
        val parts = formatted.split("\n", limit = 2)

        // Set the first part as the main title
        supportActionBar?.title = parts[0]

        // Set the second part (the one in parentheses) as the subtitle
        // Subtitles are automatically smaller and appear on the second line
        if (parts.size > 1) {
            supportActionBar?.subtitle = parts[1]
        } else {
            supportActionBar?.subtitle = null
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Workspace status polling (updates isDual / installedUserIds for each app)
    // ════════════════════════════════════════════════════════════════════════

    private fun updateAllWorkspaceStatuses() {
        if (!::wsRepo.isInitialized) return
        if (!Prefs.useRoot(this) && (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)) return

        wsRepo.listWorkspaces { workspaces ->
            cachedWorkspaces = workspaces
            runOnUiThread { refreshSpaceFilterChipsIfNeeded() }
            val managed = workspaces.filter { !it.isMainUser }

            if (managed.isEmpty()) {
                runOnUiThread { applyWorkspaceStatus(emptyMap()) }
                return@listWorkspaces
            }

            // Sequentially query each workspace to avoid overloading the shell client
            loadPackagesSequentially(managed, 0, mutableMapOf())
        }
    }

    private fun loadPackagesSequentially(
        workspaces: List<WorkspaceInfo>,
        index: Int,
        acc: MutableMap<Int, Set<String>>
    ) {
        if (index >= workspaces.size) {
            val snapshot = acc.toMap()
            runOnUiThread { applyWorkspaceStatus(snapshot) }
            return
        }
        val ws = workspaces[index]
        wsRepo.getInstalledPackages(ws.userId) { pkgs ->
            acc[ws.userId] = pkgs
            loadPackagesSequentially(workspaces, index + 1, acc)
        }
    }

    private fun applyWorkspaceStatus(installedByUser: Map<Int, Set<String>>) {
        runBg {
            val updated = cachedFullList.map { item ->
                val userIds = installedByUser.entries
                    .filter { (_, pkgs) -> pkgs.contains(item.packageName) }
                    .map { it.key }
                    .toSet()
                item.copy(installedUserIds = userIds)
            }
            cachedFullList = updated
            runOnUiThread {
                adapter.submitFullList(updated)
                updateTitle(updated)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Workspace settings management
    // ════════════════════════════════════════════════════════════════════════

    private fun loadWorkspaces() {
        wsRepo.listWorkspaces { workspaces ->
            cachedWorkspaces = workspaces
            val managed = workspaces.filter { !it.isMainUser }
            runOnUiThread {
                wsAdapter.submitList(managed)
                binding.tvNoWorkspacesInfo.isVisible = managed.isEmpty()
                refreshSpaceFilterChipsIfNeeded()
            }
        }
    }

    /** Only these characters are allowed in a user-typed workspace name (defense against
     *  shell injection: names flow into a raw `sh -c` command in WorkspaceRepository). */
    private val workspaceNameCharset = Regex("^[\\p{L}\\p{N} _.-]+$")

    private fun promptCreateWorkspaceName(type: String) {
        val prefix = when (type) {
            "clone" -> "Clone"
            "dhizuku" -> "Dual"
            else -> "Work"
        }
        val suggested = wsRepo.suggestName(cachedWorkspaces, prefix)

        val dialogBinding = DialogWorkspaceNameBinding.inflate(layoutInflater)
        dialogBinding.etWorkspaceName.setText(suggested)
        dialogBinding.etWorkspaceName.text?.let { dialogBinding.etWorkspaceName.setSelection(it.length) }

        val titleRes = when (type) {
            "clone" -> R.string.create_clone_workspace_dialog_title
            "dhizuku" -> R.string.create_dhizuku_user_dialog_title
            else -> R.string.create_workspace_dialog_title
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.create, null)
            .setNegativeButton(R.string.cancel, null)
            .show()

        // Overriding the click listener after show() lets us keep the dialog open (with an
        // inline error) instead of dismissing on an invalid name.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = dialogBinding.etWorkspaceName.text?.toString()?.trim().orEmpty()
            val error = validateWorkspaceName(typed)
            if (error != null) {
                dialogBinding.root.error = error
            } else {
                dialog.dismiss()
                doCreateWorkspace(typed, type)
            }
        }
    }

    private fun validateWorkspaceName(name: String): String? = when {
        name.isBlank() -> getString(R.string.workspace_name_empty_error)
        name.length > 24 -> getString(R.string.workspace_name_too_long_error)
        !workspaceNameCharset.matches(name) -> getString(R.string.workspace_name_invalid_chars_error)
        cachedWorkspaces.any { it.name.equals(name, ignoreCase = true) } -> getString(R.string.workspace_name_duplicate_error)
        else -> null
    }

    private fun setWorkspaceCreationButtonsEnabled(enabled: Boolean) {
        binding.btnCreateWorkspace.isEnabled = enabled
        binding.btnCreateCloneWorkspace.isEnabled = enabled
        binding.btnCreateDhizukuUser.isEnabled = enabled
    }

    private fun doCreateWorkspace(name: String, type: String) {
        if (type == "dhizuku") {
            doCreateDhizukuUser(name)
            return
        }

        setWorkspaceCreationButtonsEnabled(false)
        Toast.makeText(this, getString(R.string.creating_workspace_toast, name), Toast.LENGTH_SHORT).show()

        wsRepo.createWorkspace(name, type) { success, userId, output ->
            runOnUiThread {
                setWorkspaceCreationButtonsEnabled(true)
                if (success) {
                    // Auto-start new workspace so it's immediately usable
                    wsRepo.startWorkspace(userId) { _, _ ->
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.workspace_created_toast, name, userId), Toast.LENGTH_SHORT).show()
                            loadWorkspaces()
                            updateAllWorkspaceStatuses()
                        }
                    }
                } else {
                    Toast.makeText(this, getString(R.string.failed_to_create_workspace, output), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doCreateDhizukuUser(name: String) {
        setWorkspaceCreationButtonsEnabled(false)
        Toast.makeText(this, getString(R.string.creating_workspace_toast, name), Toast.LENGTH_SHORT).show()

        fun fail(message: String) {
            runOnUiThread {
                setWorkspaceCreationButtonsEnabled(true)
                Toast.makeText(
                    this,
                    getString(R.string.failed_to_create_workspace, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        fun createNow() {
            bg.execute {
                val result = dhizukuBridge.createManagedSecondaryUser(name)
                runOnUiThread {
                    setWorkspaceCreationButtonsEnabled(true)
                    if (result.success) {
                        Toast.makeText(
                            this,
                            getString(R.string.dhizuku_user_created_toast, name, result.userId),
                            Toast.LENGTH_LONG
                        ).show()
                        loadWorkspaces()
                        updateAllWorkspaceStatuses()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.failed_to_create_workspace, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        if (!dhizukuBridge.init()) {
            fail("Dhizuku is not available or is not active")
            return
        }

        if (dhizukuBridge.isPermissionGranted()) {
            createNow()
            return
        }

        Toast.makeText(this, R.string.dhizuku_permission_required, Toast.LENGTH_LONG).show()
        dhizukuBridge.requestPermission { granted, message ->
            if (granted) createNow() else fail(message)
        }
    }

    private fun confirmRemoveWorkspace(ws: WorkspaceInfo) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remove_workspace_title))
            .setMessage(getString(R.string.remove_workspace_confirm, ws.displayName, ws.userId))
            .setPositiveButton(getString(R.string.remove)) { _, _ -> doRemoveWorkspace(ws) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        // Tint the destructive button red after show() so the button exists in the view tree
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
        }
    }

    private fun doRemoveWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(this, getString(R.string.removing_workspace_toast, ws.displayName), Toast.LENGTH_SHORT).show()
        wsRepo.removeWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, getString(R.string.workspace_removed_toast), Toast.LENGTH_SHORT).show()
                    loadWorkspaces()
                    updateAllWorkspaceStatuses()
                } else {
                    Toast.makeText(this, getString(R.string.failed_generic, output), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doStartWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(this, getString(R.string.starting_workspace_toast, ws.displayName), Toast.LENGTH_SHORT).show()
        wsRepo.startWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    loadWorkspaces()
                } else {
                    Toast.makeText(this, getString(R.string.failed_generic, output), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doStopWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(this, getString(R.string.stopping_workspace_toast, ws.displayName), Toast.LENGTH_SHORT).show()
        wsRepo.stopWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    loadWorkspaces()
                } else {
                    Toast.makeText(this, getString(R.string.failed_generic, output), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmSwitchWorkspace(ws: WorkspaceInfo) {
        if (!requireShellOrToast()) return
        if (!ws.isFullUser) {
            Toast.makeText(this, R.string.switch_user_unsupported, Toast.LENGTH_LONG).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.switch_user_title)
            .setMessage(getString(R.string.switch_user_message, ws.displayName, ws.userId))
            .setPositiveButton(R.string.switch_user) { _, _ -> doSwitchWorkspace(ws) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doSwitchWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(
            this,
            getString(R.string.switching_user, ws.displayName),
            Toast.LENGTH_SHORT
        ).show()

        fun switchNow() {
            wsRepo.switchUser(ws.userId) { success, output ->
                if (!success) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.failed_generic, output),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        if (ws.isRunning) {
            switchNow()
        } else {
            wsRepo.startWorkspace(ws.userId) { success, output ->
                if (success) switchNow()
                else runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.failed_generic, output),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun exportWorkspaceApps(ws: WorkspaceInfo) {
        if (!requireShellOrToast()) return

        wsRepo.getUserInstalledPackages(ws.userId) { packages ->
            val safeName = ws.displayName
                .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
                .trim('_')
                .ifBlank { "user${ws.userId}" }
            val defaultName = PackageListIO.defaultFileName()
                .replace("AppDual_export", "AppDual_${safeName}_user${ws.userId}")

            runOnUiThread {
                pendingExportJson = PackageListIO.serialize(packages.sorted())
                pendingExportCount = packages.size
                exportDocumentLauncher.launch(defaultName)
            }
        }
    }

    private fun beginImportWorkspaceApps(ws: WorkspaceInfo) {
        if (!requireShellOrToast()) return
        pendingWorkspaceImport = ws
        workspaceImportDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun readWorkspaceImportFromUri(uri: Uri, ws: WorkspaceInfo) {
        runBg {
            try {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                } ?: throw IllegalStateException("openInputStream returned null")

                val packages = PackageListIO.deserialize(text)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()

                runOnUiThread {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.import_user_apps_title, ws.displayName))
                        .setMessage(
                            getString(
                                R.string.import_user_apps_confirm,
                                packages.size,
                                ws.displayName
                            )
                        )
                        .setPositiveButton(R.string.import_apps) { _, _ ->
                            performWorkspaceImport(ws, packages)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.batch_import_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun performWorkspaceImport(ws: WorkspaceInfo, packages: List<String>) {
        if (packages.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.import_user_apps_done, 0, 0, 0),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(
            this,
            getString(R.string.import_user_apps_working, ws.displayName),
            Toast.LENGTH_SHORT
        ).show()

        fun collectAndRun() {
            wsRepo.getInstalledPackages(ws.userId) { current ->
                val already = packages.count { it in current }
                val jobs = packages.filterNot { it in current }
                runWorkspaceImportJobs(ws, jobs, 0, installed = 0, failed = 0, already = already)
            }
        }

        if (ws.isRunning) {
            collectAndRun()
        } else {
            wsRepo.startWorkspace(ws.userId) { success, output ->
                if (success) collectAndRun()
                else runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.failed_generic, output),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun runWorkspaceImportJobs(
        ws: WorkspaceInfo,
        jobs: List<String>,
        index: Int,
        installed: Int,
        failed: Int,
        already: Int
    ) {
        if (index >= jobs.size) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.import_user_apps_done, installed, already, failed),
                    Toast.LENGTH_LONG
                ).show()
                loadWorkspaces()
                updateAllWorkspaceStatuses()
            }
            return
        }

        wsRepo.installToWorkspace(ws.userId, jobs[index]) { success, _ ->
            runWorkspaceImportJobs(
                ws = ws,
                jobs = jobs,
                index = index + 1,
                installed = installed + if (success) 1 else 0,
                failed = failed + if (success) 0 else 1,
                already = already
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  App launch / info helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun getLauncherComponent(packageName: String): String? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return intent.component?.flattenToShortString()
    }

    private fun launchApp(userId: Int, packageName: String) {
        if (!requireShellOrToast()) return

        if (userId == 0) {
            val component = getLauncherComponent(packageName)
            if (component == null) {
                Toast.makeText(this, getString(R.string.no_launcher_found), Toast.LENGTH_SHORT).show()
                return
            }
            wsRepo.launchInWorkspace(userId, component) { success, output ->
                runOnUiThread {
                    if (!success) {
                        Toast.makeText(
                            this,
                            getString(R.string.launch_failed, output),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }

        val workspace = cachedWorkspaces.firstOrNull { it.userId == userId }
        wsRepo.resolveLauncherComponent(userId, packageName) { component ->
            if (component == null) {
                runOnUiThread {
                    Toast.makeText(this, R.string.no_launcher_found, Toast.LENGTH_SHORT).show()
                }
                return@resolveLauncherComponent
            }

            val callback: (Boolean, String) -> Unit = { success, output ->
                if (!success) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.launch_failed, output),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            if (workspace?.isFullUser == true) {
                // A full secondary user's Activity cannot be visible while user 0 is
                // foreground, so make the user foreground first and then launch.
                wsRepo.switchAndLaunch(userId, component, callback)
            } else {
                wsRepo.launchInWorkspace(userId, component, callback)
            }
        }
    }

    private fun openAppInfo(userId: Int, packageName: String) {
        if (!requireShellOrToast()) return
        wsRepo.openAppInfoInWorkspace(userId, packageName) { success, output ->
            runOnUiThread {
                if (!success) Toast.makeText(this, getString(R.string.failed_generic, output), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  App-actions bottom sheet
    // ════════════════════════════════════════════════════════════════════════

    private fun showAppActionsBottomSheet(item: AppItem) {
        val sheetBinding = BottomSheetAppActionsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this).apply {
            setContentView(sheetBinding.root)
        }

        // Header Setup
        with(sheetBinding) {
            ivAppIcon.setImageDrawable(item.icon)
            tvAppName.text = item.label
            tvPackageName.text = item.packageName
            btnCancel.setOnClickListener { dialog.dismiss() }
        }

        // Main Space Buttons
        val shellReady = requireShellOrToast(silent = true)
        with(sheetBinding) {
            btnUninstallMain.isEnabled = shellReady
            btnLaunchMain.isEnabled = shellReady
            btnAppInfoMain.isEnabled = shellReady
            btnUninstallMain.setOnClickListener { confirmAndUninstallFromMain(item, dialog, sheetBinding) }
            btnLaunchMain.setOnClickListener  { launchApp(0, item.packageName) }
            btnAppInfoMain.setOnClickListener { openAppInfo(0, item.packageName) }
        }

        // Workspaces Logic
        val managedWorkspaces = cachedWorkspaces.filter { !it.isMainUser }
        sheetBinding.tvNoWorkspaces.isVisible = managedWorkspaces.isEmpty()
        sheetBinding.layoutWorkspaceActions.removeAllViews()

        managedWorkspaces.forEach { ws ->
            val rowBinding = ItemWorkspaceActionBinding.inflate(layoutInflater, sheetBinding.layoutWorkspaceActions, true)
            bindWorkspaceActionRow(rowBinding, ws, item, dialog, sheetBinding)
        }

        dialog.show()
    }

    /**
     * Uninstalling from Main removes the app from AppDual's master list (it's sourced from
     * Main's own package list - see AppRepository.loadInstalledAppsUser0), even though the
     * app keeps working in any other space it's still installed in. Warn before doing that
     * so it isn't a silent surprise; skip the warning when there's nothing to lose.
     */
    private fun confirmAndUninstallFromMain(item: AppItem, dialog: BottomSheetDialog, sheetBinding: BottomSheetAppActionsBinding) {
        if (item.isDual) {
            val warnDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_uninstall_warning_title)
                .setMessage(getString(R.string.main_uninstall_warning_message, item.label, item.workspaceCount))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_anyway) { _, _ -> uninstallFromMain(item, dialog, sheetBinding) }
                .show()
            warnDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
            }
        } else {
            uninstallFromMain(item, dialog, sheetBinding)
        }
    }

    private fun uninstallFromMain(item: AppItem, dialog: BottomSheetDialog, sheetBinding: BottomSheetAppActionsBinding) {
        sheetBinding.layoutProgress.isVisible = true
        sheetBinding.tvProgressText.text = getString(R.string.uninstall)
        sheetBinding.btnUninstallMain.isEnabled = false

        wsRepo.uninstallFromWorkspace(0, item.packageName, sheetBinding.cbKeepData.isChecked) { success, msg ->
            runOnUiThread {
                sheetBinding.layoutProgress.isVisible = false
                if (success) {
                    dialog.dismiss()
                    loadAppsUser0()
                } else {
                    sheetBinding.btnUninstallMain.isEnabled = true
                    Toast.makeText(this, getString(R.string.failed_generic, msg), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Configures a single workspace row inside the app-actions bottom sheet.
     * Handles install ↔ uninstall toggle and launch / app-info buttons.
     */
    private fun bindWorkspaceActionRow(
        row: ItemWorkspaceActionBinding,
        ws: WorkspaceInfo,
        item: AppItem,
        dialog: BottomSheetDialog,
        sheetBinding: BottomSheetAppActionsBinding
    ) {
        var isInstalled = item.installedUserIds.contains(ws.userId)
        val running = ws.isRunning

        row.tvWsActionName.text = if (running) {
            getString(R.string.workspace_user_label, ws.displayName, ws.userId)
        } else {
            getString(R.string.workspace_user_label_stopped, ws.displayName, ws.userId, getString(R.string.stopped))
        }

        fun updateUiState(installed: Boolean) {
            isInstalled = installed
            with(row) {
                chipWsInstalled.text = getString(if (installed) R.string.installed else R.string.not_installed)
                chipWsInstalled.isChecked = installed
                btnWsInstallToggle.text = getString(if (installed) R.string.uninstall else R.string.install)
                btnWsInstallToggle.setIconResource(
                    if (installed) android.R.drawable.ic_menu_delete else android.R.drawable.ic_input_add
                )
                btnWsLaunch.isEnabled = installed && (running || ws.isFullUser)
                btnWsPin.isEnabled = installed && ws.isFullUser
                btnWsAppInfo.isEnabled = installed
            }
        }

        updateUiState(isInstalled)

        row.btnWsInstallToggle.setOnClickListener {
            val actionText = row.btnWsInstallToggle.text
            sheetBinding.layoutProgress.isVisible = true
            sheetBinding.tvProgressText.text = actionText
            row.btnWsInstallToggle.isEnabled = false

            val callback: (Boolean, String) -> Unit = { success, msg ->
                runOnUiThread {
                    sheetBinding.layoutProgress.isVisible = false
                    row.btnWsInstallToggle.isEnabled = true
                    if (success) {
                        updateUiState(!isInstalled)
                        updateAllWorkspaceStatuses()
                    } else {
                        Toast.makeText(this, getString(R.string.failed_generic, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }

            if (isInstalled) {
                wsRepo.uninstallFromWorkspace(ws.userId, item.packageName, sheetBinding.cbKeepData.isChecked, callback)
            } else {
                if (!running) {
                    wsRepo.startWorkspace(ws.userId) { _, _ ->
                        wsRepo.installToWorkspace(ws.userId, item.packageName, callback)
                    }
                } else {
                    wsRepo.installToWorkspace(ws.userId, item.packageName, callback)
                }
            }
        }

        row.btnWsLaunch.setOnClickListener {
            dialog.dismiss()
            launchApp(ws.userId, item.packageName)
        }

        row.btnWsPin.setOnClickListener {
            if (!WorkspaceShortcutManager.isSupported(this)) {
                Toast.makeText(this, R.string.pin_shortcut_unsupported, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            wsRepo.resolveLauncherComponent(ws.userId, item.packageName) { component ->
                runOnUiThread {
                    if (component == null) {
                        Toast.makeText(this, R.string.no_launcher_found, Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val ok = WorkspaceShortcutManager.pin(this, ws, item, component)
                    val message = if (ok) {
                        getString(R.string.pin_shortcut_requested, item.label, ws.displayName)
                    } else {
                        getString(R.string.pin_shortcut_failed, item.label)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        row.btnWsAppInfo.setOnClickListener {
            openAppInfo(ws.userId, item.packageName)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Batch management
    // ════════════════════════════════════════════════════════════════════════

    private fun showBatchActionsBottomSheet() {
        val selected = adapter.selectedPackages()
        if (selected.isEmpty()) return

        val sheetBinding = BottomSheetBatchActionsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this).apply { setContentView(sheetBinding.root) }
        batchDialog = dialog
        dialog.setOnDismissListener { if (batchDialog === dialog) batchDialog = null }

        sheetBinding.tvBatchSelectedCount.text = getString(R.string.batch_selected_count, selected.size)

        val managedWorkspaces = cachedWorkspaces.filter { !it.isMainUser }
        sheetBinding.tvBatchNoWorkspaces.isVisible = managedWorkspaces.isEmpty()
        sheetBinding.layoutBatchWorkspaces.removeAllViews()

        val workspaceCheckBoxes = mutableListOf<Pair<Int, MaterialCheckBox>>()
        managedWorkspaces.forEach { ws ->
            val cb = layoutInflater.inflate(
                R.layout.item_workspace_checkbox, sheetBinding.layoutBatchWorkspaces, false
            ) as MaterialCheckBox
            cb.text = getString(R.string.workspace_user_label, ws.displayName, ws.userId)
            sheetBinding.layoutBatchWorkspaces.addView(cb)
            workspaceCheckBoxes.add(ws.userId to cb)
        }

        fun selectedWorkspaceIds(): List<Int> =
            workspaceCheckBoxes.filter { it.second.isChecked }.map { it.first }

        // Shared by whichever operation is currently running (only one can run at a time,
        // since the action buttons are disabled while one is in progress).
        val cancelled = AtomicBoolean(false)

        sheetBinding.btnBatchInstall.setOnClickListener {
            cancelled.set(false)
            performBatchOperation(install = true, selected.toList(), selectedWorkspaceIds(), sheetBinding, cancelled)
        }
        sheetBinding.btnBatchUninstall.setOnClickListener {
            cancelled.set(false)
            performBatchOperation(install = false, selected.toList(), selectedWorkspaceIds(), sheetBinding, cancelled)
        }
        sheetBinding.btnBatchClone.setOnClickListener {
            cancelled.set(false)
            confirmAndPerformClone(selected, selectedWorkspaceIds(), sheetBinding, cancelled)
        }
        sheetBinding.btnBatchExport.setOnClickListener { showExportDialog(selected) }
        sheetBinding.btnBatchImport.setOnClickListener { importDocumentLauncher.launch(arrayOf("*/*")) }
        sheetBinding.btnBatchCancel.setOnClickListener {
            if (sheetBinding.layoutBatchProgress.isVisible) {
                // A batch op is running: stop queuing further jobs instead of just hiding the
                // sheet, since the op previously kept running invisibly in the background.
                cancelled.set(true)
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun setBatchButtonsEnabled(sheetBinding: BottomSheetBatchActionsBinding, enabled: Boolean) {
        sheetBinding.btnBatchInstall.isEnabled = enabled
        sheetBinding.btnBatchUninstall.isEnabled = enabled
        sheetBinding.btnBatchClone.isEnabled = enabled
        sheetBinding.btnBatchExport.isEnabled = enabled
        sheetBinding.btnBatchImport.isEnabled = enabled
    }

    private fun updateBatchProgressText(sheetBinding: BottomSheetBatchActionsBinding, phase: String, done: Int, total: Int) {
        sheetBinding.tvBatchProgressText.text = getString(R.string.batch_progress_format, phase, done, total)
    }

    /**
     * Installs/uninstalls [packages] into each of [workspaceIds]. "Smart": an install is
     * skipped where the app is already present, an uninstall is skipped where it isn't -
     * so re-running the same batch op is always a safe no-op for already-settled pairs.
     * Runtime failures (e.g. Shizuku/ADB refusing to uninstall a protected package) are
     * folded into the same skip count rather than aborting the batch.
     */
    private fun performBatchOperation(
        install: Boolean,
        packages: List<String>,
        workspaceIds: List<Int>,
        sheetBinding: BottomSheetBatchActionsBinding,
        cancelled: AtomicBoolean
    ) {
        if (workspaceIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.batch_no_workspace_selected), Toast.LENGTH_SHORT).show()
            return
        }

        val pkgByName = cachedFullList.associateBy { it.packageName }
        val jobs = mutableListOf<Pair<String, Int>>() // (packageName, userId)
        var preSkipped = 0
        for (pkg in packages) {
            val installedIn = pkgByName[pkg]?.installedUserIds ?: emptySet()
            for (uid in workspaceIds) {
                val alreadyInstalled = uid in installedIn
                val shouldRun = if (install) !alreadyInstalled else alreadyInstalled
                if (shouldRun) jobs.add(pkg to uid) else preSkipped++
            }
        }

        val phase = getString(if (install) R.string.batch_installing else R.string.batch_uninstalling)
        sheetBinding.layoutBatchProgress.isVisible = true
        updateBatchProgressText(sheetBinding, phase, 0, jobs.size)
        setBatchButtonsEnabled(sheetBinding, false)

        fun runJobs() {
            runBatchJobsSequentially(jobs, 0, install, successCount = 0, skippedCount = preSkipped, cancelled, sheetBinding) { success, skipped ->
                runOnUiThread {
                    sheetBinding.layoutBatchProgress.isVisible = false
                    setBatchButtonsEnabled(sheetBinding, true)
                    val msg = if (install) getString(R.string.batch_install_summary, success, skipped)
                              else getString(R.string.batch_uninstall_summary, success, skipped)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    updateAllWorkspaceStatuses()
                }
            }
        }

        if (install) {
            // Best-effort: a stopped workspace can't receive installs, so start every target first.
            startWorkspacesThenRun(workspaceIds) { runJobs() }
        } else {
            runJobs()
        }
    }

    private fun startWorkspacesThenRun(workspaceIds: List<Int>, onDone: () -> Unit) {
        fun step(index: Int) {
            if (index >= workspaceIds.size) {
                onDone()
                return
            }
            wsRepo.startWorkspace(workspaceIds[index]) { _, _ -> step(index + 1) }
        }
        step(0)
    }

    private fun runBatchJobsSequentially(
        jobs: List<Pair<String, Int>>,
        index: Int,
        install: Boolean,
        successCount: Int,
        skippedCount: Int,
        cancelled: AtomicBoolean,
        sheetBinding: BottomSheetBatchActionsBinding,
        onDone: (Int, Int) -> Unit
    ) {
        if (index >= jobs.size || cancelled.get()) {
            // Anything left un-run (including because the user cancelled) counts as skipped.
            onDone(successCount, skippedCount + (jobs.size - index).coerceAtLeast(0))
            return
        }
        val (pkg, userId) = jobs[index]
        val callback: (Boolean, String) -> Unit = { success, _ ->
            val nextSuccess = successCount + if (success) 1 else 0
            val nextSkipped  = skippedCount + if (success) 0 else 1
            runOnUiThread {
                val phase = getString(if (install) R.string.batch_installing else R.string.batch_uninstalling)
                updateBatchProgressText(sheetBinding, phase, index + 1, jobs.size)
            }
            runBatchJobsSequentially(jobs, index + 1, install, nextSuccess, nextSkipped, cancelled, sheetBinding, onDone)
        }
        if (install) {
            wsRepo.installToWorkspace(userId, pkg, callback)
        } else {
            wsRepo.uninstallFromWorkspace(userId, pkg, sheetBinding.cbBatchKeepData.isChecked, callback)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Batch clone: make target workspace(s) match the selection exactly
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Guard against cloning a partial/accidental selection over a whole workspace: a real
     * "full app list" export always includes a few core system packages, so their absence
     * is a strong signal the selection is a subset, not a full snapshot. This only warns -
     * the user can push through via the red "continue anyway" button.
     */
    private fun confirmAndPerformClone(
        packages: Set<String>,
        workspaceIds: List<Int>,
        sheetBinding: BottomSheetBatchActionsBinding,
        cancelled: AtomicBoolean
    ) {
        if (workspaceIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.batch_no_workspace_selected), Toast.LENGTH_SHORT).show()
            return
        }

        if (selectionLooksLikeFullWorkspace(packages)) {
            showCloneConfirmDialog(packages, workspaceIds, sheetBinding, cancelled)
        } else {
            showIncompleteSelectionDialog { showCloneConfirmDialog(packages, workspaceIds, sheetBinding, cancelled) }
        }
    }

    private fun selectionLooksLikeFullWorkspace(packages: Set<String>): Boolean =
        CLONE_REQUIRED_PACKAGE_GROUPS.all { group -> group.any { it in packages } }

    private fun showCloneConfirmDialog(
        packages: Set<String>,
        workspaceIds: List<Int>,
        sheetBinding: BottomSheetBatchActionsBinding,
        cancelled: AtomicBoolean
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clone_confirm_title)
            .setMessage(R.string.clone_confirm_message)
            .setPositiveButton(R.string.clone_button) { _, _ -> runCloneOperation(packages, workspaceIds, sheetBinding, cancelled) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Deliberately doesn't say which packages are being checked up front - showing that
     * list unprompted would teach anyone probing the warning exactly what to add to slip
     * past it. Tapping the message once reveals the reference list on demand instead.
     */
    private fun showIncompleteSelectionDialog(onContinueAnyway: () -> Unit) {
        val messageBinding = DialogTappableMessageBinding.inflate(layoutInflater)
        messageBinding.tvDialogMessage.text = getString(R.string.clone_incomplete_selection_message)

        var revealed = false
        messageBinding.tvDialogMessage.setOnClickListener {
            if (revealed) return@setOnClickListener
            revealed = true
            val referenceList = CLONE_REFERENCE_PACKAGE_GROUPS.joinToString("\n") { it.joinToString(" / ") }
            messageBinding.tvDialogMessage.text = getString(R.string.clone_reference_list_prompt, referenceList)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clone_incomplete_selection_title)
            .setView(messageBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_anyway) { _, _ -> onContinueAnyway() }
            .show()

        // Tint the override button red after show() so the button exists in the view tree
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
        }
    }

    private data class CloneJob(val userId: Int, val isInstall: Boolean, val packageName: String)

    private fun runCloneOperation(
        packages: Set<String>,
        workspaceIds: List<Int>,
        sheetBinding: BottomSheetBatchActionsBinding,
        cancelled: AtomicBoolean
    ) {
        sheetBinding.layoutBatchProgress.isVisible = true
        sheetBinding.tvBatchProgressText.setText(R.string.batch_clone_preparing)
        setBatchButtonsEnabled(sheetBinding, false)

        startWorkspacesThenRun(workspaceIds) {
            // Fetch every target workspace's actual current package list first (not the
            // cached main-space view) so the diff is correct, and so the total job count -
            // and therefore the N/Total progress below - is known up front.
            collectCloneJobs(workspaceIds, 0, packages, mutableListOf()) { jobs ->
                // This callback chain runs on the shell client's background executor, not
                // the main thread - touching a view directly here throws
                // CalledFromWrongThreadException (intermittently, depending on view
                // attachment timing), unlike the synchronous initial progress text set at
                // the top of runCloneOperation which runs on the UI thread already.
                runOnUiThread {
                    updateBatchProgressText(sheetBinding, getString(R.string.batch_cloning), 0, jobs.size)
                }
                runCloneJobsSequentially(jobs, 0, installed = 0, uninstalled = 0, skipped = 0, cancelled, sheetBinding) { i, u, s ->
                    runOnUiThread {
                        sheetBinding.layoutBatchProgress.isVisible = false
                        setBatchButtonsEnabled(sheetBinding, true)
                        Toast.makeText(this, getString(R.string.batch_clone_summary, i, u, s), Toast.LENGTH_LONG).show()
                        updateAllWorkspaceStatuses()
                    }
                }
            }
        }
    }

    private fun collectCloneJobs(
        workspaceIds: List<Int>,
        index: Int,
        packages: Set<String>,
        acc: MutableList<CloneJob>,
        onCollected: (List<CloneJob>) -> Unit
    ) {
        if (index >= workspaceIds.size) {
            onCollected(acc)
            return
        }
        val userId = workspaceIds[index]
        wsRepo.getInstalledPackages(userId) { currentPkgs ->
            (packages - currentPkgs).forEach { acc.add(CloneJob(userId, true, it)) }
            (currentPkgs - packages).forEach { acc.add(CloneJob(userId, false, it)) }
            collectCloneJobs(workspaceIds, index + 1, packages, acc, onCollected)
        }
    }

    private fun runCloneJobsSequentially(
        jobs: List<CloneJob>,
        index: Int,
        installed: Int,
        uninstalled: Int,
        skipped: Int,
        cancelled: AtomicBoolean,
        sheetBinding: BottomSheetBatchActionsBinding,
        onDone: (Int, Int, Int) -> Unit
    ) {
        if (index >= jobs.size || cancelled.get()) {
            onDone(installed, uninstalled, skipped + (jobs.size - index).coerceAtLeast(0))
            return
        }
        val job = jobs[index]
        val callback: (Boolean, String) -> Unit = { success, _ ->
            val (i, u, s) = when {
                success && job.isInstall  -> Triple(installed + 1, uninstalled, skipped)
                success && !job.isInstall -> Triple(installed, uninstalled + 1, skipped)
                else                       -> Triple(installed, uninstalled, skipped + 1)
            }
            runOnUiThread {
                updateBatchProgressText(sheetBinding, getString(R.string.batch_cloning), index + 1, jobs.size)
            }
            runCloneJobsSequentially(jobs, index + 1, i, u, s, cancelled, sheetBinding, onDone)
        }
        if (job.isInstall) {
            wsRepo.installToWorkspace(job.userId, job.packageName, callback)
        } else {
            wsRepo.uninstallFromWorkspace(job.userId, job.packageName, sheetBinding.cbBatchKeepData.isChecked, callback)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Batch list import / export (Storage Access Framework)
    // ════════════════════════════════════════════════════════════════════════

    private fun showExportDialog(selected: Set<String>) {
        val dialogBinding = DialogExportFilenameBinding.inflate(layoutInflater)
        dialogBinding.etExportFileName.setText(PackageListIO.defaultFileName())
        dialogBinding.etExportFileName.text?.let { dialogBinding.etExportFileName.setSelection(it.length) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val typed = dialogBinding.etExportFileName.text?.toString()?.trim()
                val name = if (typed.isNullOrBlank()) PackageListIO.defaultFileName() else typed
                pendingExportJson = PackageListIO.serialize(selected)
                pendingExportCount = selected.size
                exportDocumentLauncher.launch(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun writeExportToUri(uri: Uri) {
        val json = pendingExportJson
        val count = pendingExportCount
        pendingExportJson = null
        if (json == null) return

        runBg {
            try {
                val stream = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("openOutputStream returned null")
                stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.batch_export_success, count), Toast.LENGTH_SHORT).show()
                    batchDialog?.dismiss()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.batch_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun readImportFromUri(uri: Uri) {
        runBg {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("openInputStream returned null")
                val text = stream.use { it.bufferedReader().readText() }

                val importedPkgs = PackageListIO.deserialize(text).toSet()
                val installedOnDevice = cachedFullList.map { it.packageName }.toSet()
                val matched = importedPkgs.intersect(installedOnDevice)
                val notFound = importedPkgs.size - matched.size

                runOnUiThread {
                    adapter.setBatchMode(true)
                    binding.chipBatchMode.isChecked = true
                    invalidateOptionsMenu()
                    adapter.setSelectedPackages(matched)
                    Toast.makeText(this, getString(R.string.batch_import_success, matched.size, notFound), Toast.LENGTH_LONG).show()
                    // Reopen (not just dismiss) so the sheet's header count and its button
                    // closures pick up the freshly-imported selection instead of the stale
                    // one captured when the sheet was first opened.
                    batchDialog?.dismiss()
                    showBatchActionsBottomSheet()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.batch_import_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
