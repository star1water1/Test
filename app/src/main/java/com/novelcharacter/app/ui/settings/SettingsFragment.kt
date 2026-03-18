package com.novelcharacter.app.ui.settings

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
import com.novelcharacter.app.backup.BackupStatusStore
import com.novelcharacter.app.data.maintenance.SystemMaintenanceService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.novelcharacter.app.databinding.FragmentSettingsBinding
import com.novelcharacter.app.util.ThemeHelper
import kotlinx.coroutines.launch

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

        // Maintenance
        val app = requireContext().applicationContext as NovelCharacterApp
        val maintenanceService = SystemMaintenanceService(requireContext().applicationContext, app.database)

        binding.integrityCheckRow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.maintenanceResult.visibility = View.VISIBLE
                binding.maintenanceResult.text = getString(R.string.maintenance_running)
                val fkResult = maintenanceService.checkForeignKeyIntegrity()
                val dupResult = maintenanceService.checkDuplicateDisplayOrders()
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
                binding.maintenanceResult.visibility = View.VISIBLE
                binding.maintenanceResult.text = getString(R.string.maintenance_running)
                maintenanceService.reindexDisplayOrders()
                binding.maintenanceResult.text = getString(R.string.maintenance_reindex_done)
            }
        }

        binding.checkImagesRow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.maintenanceResult.visibility = View.VISIBLE
                binding.maintenanceResult.text = getString(R.string.maintenance_running)
                val result = maintenanceService.checkBrokenImagePaths()
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
        val statusStore = (requireContext().applicationContext as NovelCharacterApp).backupStatusStore
        viewLifecycleOwner.lifecycleScope.launch {
            val status = statusStore.getStatus()
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
            val backupDir = File(requireContext().filesDir, "backups")
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

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    ThemeHelper.saveTheme(requireContext(), which)
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
