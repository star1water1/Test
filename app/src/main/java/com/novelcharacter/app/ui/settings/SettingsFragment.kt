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

        binding.backupRestoreRow.setOnClickListener {
            showBackupRestoreDialog()
        }

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

            // Count backup files
            val backupDir = File(filesDir, "backups")
            val backupCount = backupDir.listFiles { f ->
                f.name.startsWith("NovelCharacter_AutoBackup_") && f.name.endsWith(".enc")
            }?.size ?: 0
            sb.append(getString(R.string.backup_file_count, backupCount))

            binding.backupStatusText.text = sb.toString()
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

    private fun showBackupRestoreDialog() {
        if (!isAdded) return

        if (!BackupEncryptor.isKeyAvailable()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.backup_restore_title)
                .setMessage(R.string.backup_restore_key_missing)
                .setPositiveButton(R.string.confirm, null)
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

        pendingBackupExportFile = latestBackup
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val fileName = "NovelCharacter_Backup_${dateFormat.format(Date(latestBackup.lastModified()))}.enc"
        backupExportLauncher.launch(fileName)
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
                restoreFromEncryptedFile(tempEncFile)
            } catch (e: Exception) {
                AppLogger.error("Settings", "백업 복원 실패 (복호화)", e)
                if (_binding != null) {
                    Toast.makeText(ctx, getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restoreFromEncryptedFile(encFile: File) {
        if (!isAdded) return

        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(
            android.R.layout.simple_list_item_1, null
        )
        val progressDialog = MaterialAlertDialogBuilder(ctx)
            .setMessage(getString(R.string.backup_restore_decrypting))
            .setCancelable(false)
            .setView(android.widget.ProgressBar(ctx).apply {
                isIndeterminate = true
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            })
            .create()
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
                    Toast.makeText(ctx, getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
                }
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
