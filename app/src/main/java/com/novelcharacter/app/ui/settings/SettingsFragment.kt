package com.novelcharacter.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.backup.BackupEncryptor
import com.novelcharacter.app.backup.BackupSettingsStore
import com.novelcharacter.app.backup.BackupStatusStore
import com.novelcharacter.app.data.maintenance.SystemMaintenanceService
import com.novelcharacter.app.share.WorldPackageExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.novelcharacter.app.databinding.FragmentSettingsBinding
import com.novelcharacter.app.util.AppLogger
import com.novelcharacter.app.util.ThemeHelper
import com.novelcharacter.app.util.setValidatedPositiveButton
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var importerInitialized = false
    private val importer by lazy {
        importerInitialized = true
        com.novelcharacter.app.excel.ExcelImporter(requireContext().applicationContext)
    }
    private var exporter: com.novelcharacter.app.excel.ExcelExporter? = null
    private var pendingExportFile: java.io.File? = null
    private var pendingBackupExportFile: File? = null

    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (!isAdded || uri == null) return@registerForActivityResult
        restoreFromEncryptedUri(uri)
    }

    private val backupExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (!isAdded || uri == null) return@registerForActivityResult
        val file = pendingBackupExportFile ?: return@registerForActivityResult
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                    }
                }
                if (_binding != null) {
                    Toast.makeText(ctx, R.string.backup_export_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.error("Settings", "백업 내보내기 실패", e)
                if (_binding != null) {
                    Toast.makeText(ctx, getString(R.string.backup_export_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
            pendingBackupExportFile = null
        }
    }

    private var pendingExportOptions: com.novelcharacter.app.excel.ExportOptions? = null

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (!isAdded) return@registerForActivityResult
        val file = pendingExportFile
        if (uri != null && file != null) {
            if (exporter == null) {
                exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext().applicationContext)
            }
            exporter?.writeToUri(uri, file)
        }
        pendingExportFile = null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.getString("pendingExportFilePath")?.let {
            pendingExportFile = java.io.File(it)
        }
        importer.registerLauncher(this)

        updateThemeLabel()

        binding.themeRow.setOnClickListener {
            showThemeDialog()
        }

        binding.supplementRow.setOnClickListener {
            findNavController().navigate(R.id.supplementFragment)
        }

        // Data management
        binding.exportRow.setOnClickListener {
            exportToExcel()
        }

        binding.worldPackageRow.setOnClickListener {
            exportWorldPackage()
        }

        binding.importRow.setOnClickListener {
            importFromExcel()
        }

        binding.trashRow.setOnClickListener {
            findNavController().navigate(R.id.trashFragment)
        }

        binding.storageRow.setOnClickListener {
            findNavController().navigate(R.id.storageFragment)
        }

        binding.operationHistoryRow.setOnClickListener {
            findNavController().navigate(R.id.operationHistoryFragment)
        }

        // 자동 백업 이미지 기본값이 데이터 전용으로 바뀐 것을 최초 1회 고지 (변수 제어 — 무통보 변경 금지)
        maybeShowBackupImageNotice()

        binding.backupRestoreRow.setOnClickListener {
            showBackupRestoreDialog()
        }

        binding.backupOptionsRow.setOnClickListener {
            showBackupOptionsDialog()
        }
        updateBackupOptionsLabel()

        // Maintenance
        val app = requireContext().applicationContext as NovelCharacterApp
        val maintenanceService = SystemMaintenanceService(requireContext().applicationContext, app.database)

        binding.integrityCheckRow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (_binding == null) return@launch
                binding.maintenanceResult.visibility = View.VISIBLE
                binding.maintenanceResult.text = getString(R.string.maintenance_running)
                val fkResult = maintenanceService.checkForeignKeyIntegrity()
                if (_binding == null) return@launch
                val dupResult = maintenanceService.checkDuplicateDisplayOrders()
                if (_binding == null) return@launch
                val sb = StringBuilder()
                val hasIssues = fkResult.fkViolations > 0 || dupResult.duplicateOrders > 0 ||
                    dupResult.negativeOrders > 0 || dupResult.sparseOrders > 0
                if (!hasIssues) {
                    sb.append(getString(R.string.maintenance_no_issues))
                } else {
                    if (fkResult.fkViolations > 0) {
                        sb.appendLine(getString(R.string.maintenance_fk_violations, fkResult.fkViolations))
                    }
                    if (dupResult.duplicateOrders > 0) {
                        sb.appendLine(getString(R.string.maintenance_duplicate_order, dupResult.duplicateOrders))
                    }
                    if (dupResult.negativeOrders > 0) {
                        sb.appendLine(getString(R.string.maintenance_negative_orders, dupResult.negativeOrders))
                    }
                    if (dupResult.sparseOrders > 0) {
                        sb.appendLine(getString(R.string.maintenance_sparse_orders, dupResult.sparseOrders))
                    }
                    // Show detail items
                    val allDetails = fkResult.details + dupResult.details
                    if (allDetails.isNotEmpty()) {
                        sb.appendLine()
                        allDetails.take(20).forEach { sb.appendLine("  - $it") }
                        if (allDetails.size > 20) {
                            sb.appendLine("  ... (+${allDetails.size - 20}건)")
                        }
                    }
                }
                binding.maintenanceResult.text = sb.toString()
            }
        }

        binding.reindexRow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (_binding == null) return@launch
                binding.maintenanceResult.visibility = View.VISIBLE
                binding.maintenanceResult.text = getString(R.string.maintenance_running)
                maintenanceService.reindexDisplayOrders()
                if (_binding == null) return@launch
                binding.maintenanceResult.text = getString(R.string.maintenance_reindex_done)
            }
        }

        binding.cleanOrphanRow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (_binding == null) return@launch
                binding.maintenanceResult.visibility = View.VISIBLE
                binding.maintenanceResult.text = getString(R.string.maintenance_running)
                val result = maintenanceService.cleanOrphanData()
                if (_binding == null) return@launch
                val sb = StringBuilder()
                sb.appendLine(getString(R.string.maintenance_clean_orphan_done))
                result.details.forEach { sb.appendLine("  - $it") }
                binding.maintenanceResult.text = sb.toString()
            }
        }

        binding.checkImagesRow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (_binding == null) return@launch
                binding.maintenanceResult.visibility = View.VISIBLE
                binding.maintenanceResult.text = getString(R.string.maintenance_running)
                val result = maintenanceService.checkBrokenImagePaths()
                if (_binding == null) return@launch
                if (result.orphanImages == 0) {
                    binding.maintenanceResult.text = getString(R.string.maintenance_no_issues)
                } else {
                    val sb = StringBuilder()
                    sb.appendLine(getString(R.string.maintenance_orphan_images, result.orphanImages))
                    result.details.take(10).forEach { sb.appendLine("  - $it") }
                    binding.maintenanceResult.text = sb.toString()
                }
            }
        }

        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.versionText.text = getString(R.string.settings_version_format, pInfo.versionName)
        } catch (e: Exception) {
            binding.versionText.text = getString(R.string.settings_version_format, "1.0")
        }

        loadBackupStatus()

        // Error log section
        binding.viewErrorLogRow.setOnClickListener {
            showErrorLogDialog()
        }
        loadErrorLogSummary()

        // App reset
        binding.resetAppRow.setOnClickListener {
            showResetConfirmDialog()
        }
    }

    private fun loadBackupStatus() {
        val app = requireContext().applicationContext as NovelCharacterApp
        val statusStore = app.backupStatusStore
        val filesDir = app.filesDir
        viewLifecycleOwner.lifecycleScope.launch {
            val status = statusStore.getStatus()
            if (_binding == null) return@launch
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder()

            val successText = if (status.lastSuccessAt > 0) {
                dateFormat.format(Date(status.lastSuccessAt))
            } else {
                getString(R.string.backup_never)
            }
            sb.appendLine(getString(R.string.backup_last_success, successText))

            if (status.lastFailureAt > 0) {
                val failText = dateFormat.format(Date(status.lastFailureAt))
                sb.appendLine(getString(R.string.backup_last_failure, failText))
                if (status.lastFailureReason.isNotBlank()) {
                    sb.appendLine(getString(R.string.backup_failure_reason, status.lastFailureReason))
                }
            }

            // Count backup files + total size (용량 잠식을 사용자가 인지할 수 있도록)
            val backupDir = File(filesDir, "backups")
            val backupFiles = backupDir.listFiles { f ->
                f.name.startsWith("NovelCharacter_AutoBackup_") && f.name.endsWith(".enc")
            } ?: emptyArray()
            val totalMb = String.format(Locale.US, "%.1f", backupFiles.sumOf { it.length() } / 1024.0 / 1024.0)
            sb.appendLine(
                getString(R.string.backup_file_count, backupFiles.size) +
                    getString(R.string.backup_total_size_suffix, totalMb)
            )
            // 기기 종속 암호화 상시 고지 — 폰 교체용 백업으로 오인하지 않도록
            sb.append(getString(R.string.backup_device_bound_caption))

            binding.backupStatusText.text = sb.toString()
        }
    }

    /**
     * 자동 백업 이미지 포함 기본값이 데이터 전용(false)으로 바뀐 것을 최초 1회 안내한다.
     * 무통보 변경 금지(변수 제어) — 이미지는 앱에 그대로 보관되며 재활성화 경로를 함께 알린다.
     *
     * 실제로 영향받는 사용자(현재 설정이 이미지 미포함인 경우)에게만 표시한다 — 이미지 포함을
     * 명시 ON으로 유지 중인 사용자에게는 오해 소지가 있어 띄우지 않는다. markShown은 show 성공
     * 이후에 기록해 드물게 표시 실패 시 고지가 소진되지 않게 한다.
     */
    private fun maybeShowBackupImageNotice() {
        val ctx = context ?: return
        if (com.novelcharacter.app.util.OnboardingPrefs.isShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_BACKUP_IMAGE_NOTICE_SHOWN)) return
        viewLifecycleOwner.lifecycleScope.launch {
            val includeImages = com.novelcharacter.app.backup.BackupSettingsStore(ctx).getSettings().includeImages
            if (_binding == null || !isAdded) return@launch
            // 이미지 포함을 유지 중이면 변화가 없으므로 고지하지 않음
            if (includeImages) {
                com.novelcharacter.app.util.OnboardingPrefs.markShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_BACKUP_IMAGE_NOTICE_SHOWN)
                return@launch
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.backup_image_notice_title)
                .setMessage(R.string.backup_image_notice_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            com.novelcharacter.app.util.OnboardingPrefs.markShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_BACKUP_IMAGE_NOTICE_SHOWN)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExportFile?.absolutePath?.let {
            outState.putString("pendingExportFilePath", it)
        }
    }

    private fun updateThemeLabel() {
        val mode = ThemeHelper.getSavedTheme(requireContext())
        binding.themeValue.text = when (mode) {
            ThemeHelper.MODE_LIGHT -> getString(R.string.settings_theme_light)
            ThemeHelper.MODE_DARK -> getString(R.string.settings_theme_dark)
            else -> getString(R.string.settings_theme_system)
        }
    }

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.settings_theme_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        val current = ThemeHelper.getSavedTheme(requireContext())

        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    ThemeHelper.saveTheme(ctx.applicationContext, which)
                    ThemeHelper.applyTheme(which)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportWorldPackage() {
        if (!isAdded) return
        val app = requireContext().applicationContext as NovelCharacterApp

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val universes = withContext(Dispatchers.IO) {
                    app.database.universeDao().getAllUniversesList()
                }
                if (universes.isEmpty()) {
                    Toast.makeText(requireContext(), "내보낼 세계관이 없습니다", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val names = universes.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.share_world_package)
                    .setItems(names) { _, which ->
                        val universe = universes[which]
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                Toast.makeText(requireContext(), R.string.export_preparing, Toast.LENGTH_SHORT).show()
                                val exporter = WorldPackageExporter(requireContext())
                                val config = WorldPackageExporter.ExportConfig(universeId = universe.id)
                                val file = withContext(Dispatchers.IO) { exporter.export(config) }

                                if (!isAdded) return@launch
                                val uri = FileProvider.getUriForFile(
                                    requireContext(),
                                    "${requireContext().packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_world_package)))
                            } catch (e: Exception) {
                                if (isAdded) {
                                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    .show()
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportToExcel() {
        if (!isAdded) return
        showExportOptionsDialog()
    }

    private fun showExportOptionsDialog() {
        if (!isAdded) return
        val labels = com.novelcharacter.app.excel.ExportOptions.LABELS
        val checked = com.novelcharacter.app.excel.ExportOptions.ALL.toBooleanArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_options_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.confirm) { _, _ ->
                val options = com.novelcharacter.app.excel.ExportOptions.fromBooleanArray(checked)
                pendingExportOptions = options
                showExportModeDialog(options)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExportModeDialog(options: com.novelcharacter.app.excel.ExportOptions) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_mode_title)
            .setItems(arrayOf(getString(R.string.export_mode_share), getString(R.string.export_mode_save))) { _, which ->
                exporter?.cancel()
                exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext().applicationContext)
                when (which) {
                    0 -> exporter?.exportAll(options)
                    1 -> exporter?.exportAll(options) { file, fileName ->
                        if (isAdded) {
                            pendingExportFile = file
                            saveFileLauncher.launch(fileName)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importFromExcel() {
        if (!isAdded) return
        importer.showImportDialog(this)
    }

    // ── 백업/복원 ──

    /** 자동 백업 옵션 행의 현재값 라벨 갱신 */
    private fun updateBackupOptionsLabel() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = BackupSettingsStore(requireContext().applicationContext).getSettings()
            if (_binding == null) return@launch
            val imagesText = getString(
                if (settings.includeImages) R.string.backup_option_images_on else R.string.backup_option_images_off
            )
            binding.backupOptionsValue.text =
                getString(R.string.backup_options_value, imagesText, settings.maxBackups)
        }
    }

    /** 자동 백업 옵션 다이얼로그 — 이미지 포함 토글, 보관 개수 선택 */
    private fun showBackupOptionsDialog() {
        if (!isAdded) return
        val store = BackupSettingsStore(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = store.getSettings()
            if (_binding == null || !isAdded) return@launch
            val imagesLabel = getString(
                R.string.backup_option_toggle_images,
                getString(if (settings.includeImages) R.string.backup_option_images_on else R.string.backup_option_images_off)
            )
            val maxLabel = getString(R.string.backup_option_choose_max, settings.maxBackups)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.backup_options)
                .setItems(arrayOf(imagesLabel, maxLabel)) { _, which ->
                    when (which) {
                        0 -> viewLifecycleOwner.lifecycleScope.launch {
                            store.setIncludeImages(!settings.includeImages)
                            updateBackupOptionsLabel()
                        }
                        1 -> showMaxBackupsDialog(store, settings.maxBackups)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showMaxBackupsDialog(store: BackupSettingsStore, current: Int) {
        if (!isAdded) return
        val choices = BackupSettingsStore.MAX_BACKUPS_CHOICES
        val labels = choices.map { getString(R.string.backup_max_item, it) }.toTypedArray()
        val checked = choices.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_option_max_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewLifecycleOwner.lifecycleScope.launch {
                    store.setMaxBackups(choices[which])
                    updateBackupOptionsLabel()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBackupRestoreDialog() {
        if (!isAdded) return

        if (!BackupEncryptor.isKeyAvailable()) {
            // 기기 키가 없어도 암호(패스프레이즈)로 만든 이식 가능 백업은 복원할 수 있으므로
            // 외부 파일 복원 경로는 열어둔다
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.backup_restore_title)
                .setMessage(getString(R.string.backup_restore_key_missing) + "\n\n" + getString(R.string.backup_restore_portable_still_ok))
                .setPositiveButton(R.string.backup_restore_from_external) { _, _ ->
                    restoreFileLauncher.launch(arrayOf("application/octet-stream"))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val options = arrayOf(
            getString(R.string.backup_export_to_external),
            getString(R.string.backup_restore_from_internal),
            getString(R.string.backup_restore_from_external)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_restore_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportBackupToExternal()
                    1 -> showInternalBackupList()
                    2 -> restoreFileLauncher.launch(arrayOf("application/octet-stream"))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportBackupToExternal() {
        if (!isAdded) return
        val app = requireContext().applicationContext as NovelCharacterApp
        val backupDir = File(app.filesDir, "backups")
        val latestBackup = backupDir.listFiles { f ->
            f.name.startsWith("NovelCharacter_AutoBackup_") && f.name.endsWith(".enc")
        }?.maxByOrNull { it.lastModified() }

        if (latestBackup == null) {
            Toast.makeText(requireContext(), R.string.backup_restore_no_backups, Toast.LENGTH_SHORT).show()
            return
        }

        // 형식 선택: 이식 가능(암호 설정, 다른 기기 복원 가능) / 기기 전용(현재 기기에서만 복원)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_export_format_title)
            .setItems(
                arrayOf(
                    getString(R.string.backup_export_portable),
                    getString(R.string.backup_export_device_only)
                )
            ) { _, which ->
                when (which) {
                    0 -> showSetPassphraseDialog { passphrase ->
                        exportPortableBackup(latestBackup, passphrase)
                    }
                    1 -> confirmDeviceOnlyExport(latestBackup)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 기기 전용 내보내기 — 기기 종속 암호화 고지 후 원본 .enc를 그대로 복사 */
    private fun confirmDeviceOnlyExport(latestBackup: File) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_device_bound_title)
            .setMessage(R.string.backup_device_bound_message)
            .setPositiveButton(R.string.backup_export_continue) { _, _ ->
                pendingBackupExportFile = latestBackup
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                val fileName = "NovelCharacter_Backup_${dateFormat.format(Date(latestBackup.lastModified()))}.enc"
                backupExportLauncher.launch(fileName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 이식 가능 백업용 암호 설정 다이얼로그 (암호 + 확인 입력).
     * 검증 통과 시에만 onSet 호출 — 최소 길이/일치 여부를 다이얼로그 안에서 안내.
     */
    private fun showSetPassphraseDialog(onSet: (CharArray) -> Unit) {
        if (!isAdded) return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val passwordType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val editPass = android.widget.EditText(ctx).apply {
            hint = getString(R.string.backup_passphrase_hint)
            inputType = passwordType
        }
        val editConfirm = android.widget.EditText(ctx).apply {
            hint = getString(R.string.backup_passphrase_confirm_hint)
            inputType = passwordType
        }
        container.addView(editPass)
        container.addView(editConfirm)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.backup_passphrase_set_title)
            .setMessage(R.string.backup_passphrase_message)
            .setView(container)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val pass = editPass.text.toString()
            val confirm = editConfirm.text.toString()
            when {
                pass.length < BackupEncryptor.MIN_PASSPHRASE_LENGTH -> {
                    Toast.makeText(
                        ctx,
                        getString(R.string.backup_passphrase_too_short, BackupEncryptor.MIN_PASSPHRASE_LENGTH),
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
                pass != confirm -> {
                    Toast.makeText(ctx, R.string.backup_passphrase_mismatch, Toast.LENGTH_SHORT).show()
                    false
                }
                else -> {
                    onSet(pass.toCharArray())
                    true
                }
            }
        }
        dialog.show()
    }

    /** 복원용 암호 입력 다이얼로그. */
    private fun showEnterPassphraseDialog(onEntered: (CharArray) -> Unit) {
        if (!isAdded) return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val editPass = android.widget.EditText(ctx).apply {
            hint = getString(R.string.backup_passphrase_hint)
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(editPass)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.backup_passphrase_enter_title)
            .setView(container)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val pass = editPass.text.toString()
            if (pass.isEmpty()) {
                Toast.makeText(ctx, R.string.backup_passphrase_enter_title, Toast.LENGTH_SHORT).show()
                false
            } else {
                onEntered(pass.toCharArray())
                true
            }
        }
        dialog.show()
    }

    /**
     * 이식 가능 백업 생성: 기기 전용 .enc 복호화 → 암호 기반 재암호화 → SAF 저장.
     * 중간 평문 파일은 재암호화 직후 즉시 삭제한다.
     */
    private fun exportPortableBackup(deviceEncFile: File, passphrase: CharArray) {
        if (!isAdded) return
        val ctx = requireContext().applicationContext
        val progressDialog = createProgressDialog(R.string.backup_portable_preparing)
        progressDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val portableFile = withContext(Dispatchers.IO) {
                    val plainTemp = File.createTempFile("export_plain_", ".xlsx", ctx.cacheDir)
                    try {
                        BackupEncryptor.decryptFile(deviceEncFile, plainTemp)
                        val portable = File.createTempFile("export_portable_", ".enc", ctx.cacheDir)
                        BackupEncryptor.encryptFilePortable(plainTemp, portable, passphrase)
                        portable
                    } finally {
                        plainTemp.delete()
                    }
                }
                progressDialog.dismiss()
                if (!isAdded || _binding == null) return@launch
                pendingBackupExportFile = portableFile
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                val fileName =
                    "NovelCharacter_Backup_${dateFormat.format(Date(deviceEncFile.lastModified()))}_portable.enc"
                backupExportLauncher.launch(fileName)
            } catch (e: Exception) {
                AppLogger.error("Settings", "이식 가능 백업 생성 실패", e)
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (_binding != null) {
                    Toast.makeText(ctx, getString(R.string.backup_export_failed, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                passphrase.fill(' ')
            }
        }
    }

    private fun showInternalBackupList() {
        if (!isAdded) return
        val app = requireContext().applicationContext as NovelCharacterApp
        val backupDir = File(app.filesDir, "backups")
        val backupFiles = backupDir.listFiles { f ->
            f.name.startsWith("NovelCharacter_AutoBackup_") && f.name.endsWith(".enc")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (backupFiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.backup_restore_no_backups, Toast.LENGTH_SHORT).show()
            return
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val labels = backupFiles.map { file ->
            val date = dateFormat.format(Date(file.lastModified()))
            val sizeMb = String.format(Locale.US, "%.1f", file.length() / 1024.0 / 1024.0)
            "$date  (${sizeMb}MB)"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_restore_select)
            .setItems(labels) { _, which ->
                confirmAndRestore(backupFiles[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmAndRestore(encFile: File) {
        if (!isAdded) return
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val label = dateFormat.format(Date(encFile.lastModified()))

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_restore_confirm_title)
            .setMessage(getString(R.string.backup_restore_confirm_message, label))
            .setPositiveButton(R.string.confirm) { _, _ ->
                restoreFromEncryptedFile(encFile)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restoreFromEncryptedUri(uri: Uri) {
        if (!isAdded) return
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tempEncFile = withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("restore_ext_", ".enc", ctx.cacheDir)
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception(getString(R.string.backup_file_open_failed))
                    temp
                }
                // tempEncFile 삭제를 여기서 하지 않음:
                // restoreFromEncryptedFile()이 별도 코루틴을 실행하여 비동기로 파일을 읽으므로
                // 즉시 삭제하면 경쟁 조건 발생. cacheDir 파일은 시스템이 관리.
                val isPortable = withContext(Dispatchers.IO) { BackupEncryptor.isPortableFormat(tempEncFile) }
                if (!isAdded) return@launch
                if (isPortable) {
                    // 이식 가능 형식 — 기기 키 대신 암호 입력으로 복원
                    showEnterPassphraseDialog { passphrase ->
                        restoreFromPortableFile(tempEncFile, passphrase)
                    }
                } else {
                    restoreFromEncryptedFile(tempEncFile)
                }
            } catch (e: Exception) {
                AppLogger.error("Settings", "백업 복원 실패 (복호화)", e)
                if (_binding != null) {
                    Toast.makeText(ctx, getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 불확정 진행 표시 다이얼로그 (복호화/재암호화 등 IO 작업용) */
    private fun createProgressDialog(messageRes: Int): AlertDialog {
        val ctx = requireContext()
        return MaterialAlertDialogBuilder(ctx)
            .setMessage(getString(messageRes))
            .setCancelable(false)
            .setView(android.widget.ProgressBar(ctx).apply {
                isIndeterminate = true
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            })
            .create()
    }

    private fun restoreFromEncryptedFile(encFile: File) {
        if (!isAdded) return

        val ctx = requireContext()
        val progressDialog = createProgressDialog(R.string.backup_restore_decrypting)
        progressDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            var tempXlsx: File? = null
            try {
                // 복호화
                tempXlsx = withContext(Dispatchers.IO) {
                    val xlsx = File.createTempFile("restore_", ".xlsx", ctx.cacheDir)
                    BackupEncryptor.decryptFile(encFile, xlsx)
                    xlsx
                }
                if (_binding == null) return@launch

                // 복호화된 파일을 직접 전달 (불필요한 복사 없이)
                progressDialog.dismiss()
                // tempXlsx 삭제를 여기서 하지 않음:
                // importFromLocalFile()이 별도 코루틴을 실행하여 비동기로 파일을 읽으므로
                // 즉시 삭제하면 경쟁 조건 발생. cacheDir 파일은 시스템이 관리.
                importer.importFromLocalFile(tempXlsx!!)

            } catch (e: Exception) {
                AppLogger.error("Settings", "백업 복원 실패", e)
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (_binding != null) {
                    // 복호화 실패의 가장 흔한 원인(다른 기기의 백업)을 함께 안내
                    Toast.makeText(
                        ctx,
                        getString(R.string.backup_restore_failed, e.message) + "\n" + getString(R.string.backup_restore_device_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * 이식 가능(암호) 백업 복원. 잘못된 암호(GCM 태그 검증 실패)는
     * 오류로 끝내지 않고 재입력 다이얼로그로 되돌린다 — 변수 제어 원칙.
     */
    private fun restoreFromPortableFile(encFile: File, passphrase: CharArray) {
        if (!isAdded) return

        val ctx = requireContext()
        val progressDialog = createProgressDialog(R.string.backup_restore_decrypting)
        progressDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tempXlsx = withContext(Dispatchers.IO) {
                    val xlsx = File.createTempFile("restore_", ".xlsx", ctx.cacheDir)
                    try {
                        BackupEncryptor.decryptFilePortable(encFile, xlsx, passphrase)
                    } catch (e: Exception) {
                        xlsx.delete()
                        throw e
                    }
                    xlsx
                }
                if (_binding == null) return@launch

                progressDialog.dismiss()
                // tempXlsx 삭제를 여기서 하지 않음 — importFromLocalFile()이 비동기로 읽음
                importer.importFromLocalFile(tempXlsx)
            } catch (e: javax.crypto.AEADBadTagException) {
                // 잘못된 암호 — 재입력 기회 제공
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (_binding != null && isAdded) {
                    Toast.makeText(ctx, R.string.backup_passphrase_wrong, Toast.LENGTH_SHORT).show()
                    showEnterPassphraseDialog { retry ->
                        restoreFromPortableFile(encFile, retry)
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("Settings", "이식 백업 복원 실패", e)
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (_binding != null) {
                    Toast.makeText(ctx, getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                passphrase.fill(' ')
            }
        }
    }

    // ── Error Log ──

    private fun loadErrorLogSummary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { AppLogger.getErrorCount() }
            if (_binding == null) return@launch
            if (count == 0) {
                binding.errorLogSummaryText.text = getString(R.string.error_log_no_errors)
            } else {
                val lastTime = withContext(Dispatchers.IO) { AppLogger.getLastErrorTime() }
                val timeStr = if (lastTime != null && lastTime > 0) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastTime))
                } else "?"
                binding.errorLogSummaryText.text = getString(R.string.error_log_summary, timeStr, count)
            }
        }
    }

    private fun showErrorLogDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val maxDisplay = 100
            val entries = withContext(Dispatchers.IO) { AppLogger.readAllLogs(maxDisplay + 1) }
            if (_binding == null) return@launch
            val ctx = requireContext()

            if (entries.isEmpty()) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.settings_error_log)
                    .setMessage(getString(R.string.error_log_empty))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val displayEntries = entries.take(maxDisplay)
            val sb = StringBuilder()
            for (entry in displayEntries) {
                val timeStr = if (entry.timestamp > 0) dateFormat.format(Date(entry.timestamp)) else "?"
                sb.appendLine("[${entry.level}] $timeStr")
                sb.appendLine("[${entry.tag}] ${entry.message}")
                if (!entry.stackTrace.isNullOrBlank()) {
                    val trace = entry.stackTrace.lines().take(8).joinToString("\n")
                    sb.appendLine(trace)
                    if (entry.stackTrace.lines().size > 8) sb.appendLine("  ...")
                }
                sb.appendLine()
            }
            if (entries.size > maxDisplay) {
                sb.appendLine(getString(R.string.error_log_more, entries.size - maxDisplay))
            }

            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.settings_error_log)
                .setMessage(sb.toString().trimEnd())
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.error_log_share) { _, _ -> shareErrorLogs() }
                .setNegativeButton(R.string.error_log_clear) { _, _ -> confirmClearLogs() }
                .show()
        }
    }

    private fun shareErrorLogs() {
        val ctx = requireContext()
        val files = AppLogger.getLogFiles()
        if (files.isEmpty()) {
            Toast.makeText(ctx, R.string.error_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val authority = "${ctx.packageName}.fileprovider"
        val uris = files.mapNotNull { file ->
            try {
                androidx.core.content.FileProvider.getUriForFile(ctx, authority, file)
            } catch (_: Exception) { null }
        }
        if (uris.isEmpty()) return
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.error_log_share)))
    }

    private fun confirmClearLogs() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_error_log)
            .setMessage(R.string.error_log_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppLogger.clearAllLogs()
                Toast.makeText(requireContext(), R.string.error_log_cleared, Toast.LENGTH_SHORT).show()
                loadErrorLogSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── App Reset ──

    private fun showResetConfirmDialog() {
        val ctx = requireContext()
        val deleteBackupsCheckBox = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.reset_delete_backups)
            setPadding((16 * resources.displayMetrics.density).toInt(), 0, 0, 0)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_message)
            .setView(deleteBackupsCheckBox)
            .setPositiveButton(R.string.reset_action) { _, _ ->
                // 2단계 최종 확인
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.reset_confirm_title)
                    .setMessage(R.string.reset_final_confirm)
                    .setPositiveButton(R.string.reset_action) { _, _ ->
                        executeReset(deleteBackupsCheckBox.isChecked)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeReset(deleteBackups: Boolean) {
        val ctx = requireContext()
        val app = ctx.applicationContext as NovelCharacterApp
        val db = app.database

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // DB 초기화 (트랜잭션 내 FK CASCADE 안전 순서)
                db.withTransaction {
                    db.characterRelationshipChangeDao().deleteAll()
                    db.characterRelationshipDao().deleteAll()
                    db.factionMembershipDao().deleteAll()
                    db.factionDao().deleteAll()
                    db.characterStateChangeDao().deleteAll()
                    db.timelineDao().deleteAllCrossRefs()
                    db.timelineDao().deleteAllEventNovelCrossRefs()
                    db.timelineDao().deleteAllEvents()
                    db.characterDao().deleteAll()
                    db.fieldDefinitionDao().deleteAll()
                    db.novelDao().deleteAll()
                    db.universeDao().deleteAll()
                    db.nameBankDao().deleteAll()
                    db.userPresetTemplateDao().deleteAll()
                    db.searchPresetDao().deleteAll()
                    db.recentActivityDao().deleteAll()
                }

                // SharedPreferences 초기화 (테마 제외)
                withContext(Dispatchers.IO) {
                    listOf(
                        "image_index_prefs", "timeline_ui_state", "stats_prefs",
                        "supplement_criteria", "search_filters", "namebank_prefs",
                        "graph_prefs", "universe_list_state", "app_migrations"
                    ).forEach { name ->
                        ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                            .edit().clear().apply()
                    }
                }

                // 파일 삭제
                withContext(Dispatchers.IO) {
                    // 이미지 파일
                    ctx.filesDir.listFiles()?.filter {
                        it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".png") || it.name.endsWith(".webp"))
                    }?.forEach { it.delete() }

                    // 로그
                    AppLogger.clearAllLogs()

                    // 백업 (선택)
                    if (deleteBackups) {
                        java.io.File(ctx.filesDir, "backups").deleteRecursively()
                    }

                    // DataStore
                    app.backupStatusStore.clear()
                }

                if (_binding != null) {
                    Toast.makeText(ctx, R.string.reset_complete, Toast.LENGTH_LONG).show()
                    loadBackupStatus()
                    loadErrorLogSummary()
                }
            } catch (e: Exception) {
                AppLogger.error("Settings", "앱 초기화 실패", e)
                if (_binding != null) {
                    Toast.makeText(ctx, "초기화 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        exporter?.cancel()
        exporter = null
        if (importerInitialized) {
            importer.cleanup()
        }
    }
}
