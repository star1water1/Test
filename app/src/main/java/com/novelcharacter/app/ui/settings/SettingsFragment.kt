package com.novelcharacter.app.ui.settings

import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.backup.BackupEncryptor
import com.novelcharacter.app.backup.BackupStatusStore
import com.novelcharacter.app.data.maintenance.SystemMaintenanceService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.novelcharacter.app.databinding.FragmentSettingsBinding
import com.novelcharacter.app.util.ThemeHelper
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
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                    }
                }
                if (_binding != null) {
                    Toast.makeText(requireContext(), R.string.backup_export_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    Toast.makeText(requireContext(), getString(R.string.backup_export_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
            pendingBackupExportFile = null
        }
    }

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
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

        // Data management
        binding.exportRow.setOnClickListener {
            exportToExcel()
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

    private fun exportToExcel() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_mode_title)
            .setItems(arrayOf(getString(R.string.export_mode_share), getString(R.string.export_mode_save))) { _, which ->
                exporter?.cancel()
                exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext().applicationContext)
                when (which) {
                    0 -> exporter?.exportAll()
                    1 -> exporter?.exportAll { file, fileName ->
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
        viewLifecycleOwner.lifecycleScope.launch {
            var tempEncFile: File? = null
            try {
                // 외부 Uri를 로컬 임시 파일로 복사
                tempEncFile = withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("restore_ext_", ".enc", requireContext().cacheDir)
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("파일을 열 수 없습니다")
                    temp
                }
                restoreFromEncryptedFile(tempEncFile)
            } catch (e: Exception) {
                tempEncFile?.delete()
                if (_binding != null) {
                    Toast.makeText(requireContext(), getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreFromEncryptedFile(encFile: File) {
        if (!isAdded) return

        val ctx = requireContext()
        val progressDialog = ProgressDialog(ctx).apply {
            setMessage(getString(R.string.backup_restore_decrypting))
            setCancelable(false)
            isIndeterminate = true
        }
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

                // 복호화된 xlsx를 ExcelImporter로 전달
                val xlsxUri = Uri.fromFile(tempXlsx)
                progressDialog.dismiss()
                importer.importFromExcel(xlsxUri)

            } catch (e: Exception) {
                tempXlsx?.delete()
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (_binding != null) {
                    Toast.makeText(ctx, getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
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
