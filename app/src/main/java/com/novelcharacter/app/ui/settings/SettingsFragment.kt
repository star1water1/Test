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
import com.novelcharacter.app.excel.ExportCancelledException
import com.novelcharacter.app.excel.ImageNoticeRes
import com.novelcharacter.app.share.WorldPackageExporter
import com.novelcharacter.app.ui.common.TaskProgressDialog
import com.novelcharacter.app.util.ProgressScale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.novelcharacter.app.databinding.FragmentSettingsBinding
import com.novelcharacter.app.util.AppLogger
import com.novelcharacter.app.util.ThemeHelper
import com.novelcharacter.app.util.dismissSafely
import com.novelcharacter.app.util.setValidatedPositiveButton
import androidx.fragment.app.viewModels
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var excel: com.novelcharacter.app.excel.ExcelTransferController
    private var pendingBackupExportFile: File? = null

    /**
     * 정리 폴더 왕복 — **이미지 탭과 같은 컨트롤러를 쓴다**(흐름을 복제하지 않는다).
     *
     * 배너는 이 화면에 없으므로 콜백을 비워 둔다. 뷰모델은 이미지 탭의 것을 그대로 쓰되,
     * 이 화면에서는 목록을 적재하지 않으므로(`load()` 미호출) 비용이 없다.
     *
     * **필드 초기화 시점에 만드는 것이 계약이다** — 컨트롤러 생성자가 SAF 선택기를
     * `registerForActivityResult`로 등록하는데, STARTED 이후 등록은 예외가 난다.
     * 뷰모델은 그래서 람다로 넘긴다 — 이 시점엔 프래그먼트가 아직 붙기 전이라
     * `by viewModels()`를 바로 건드리면 "detached fragment" 예외가 난다.
     */
    private val organizeViewModel: com.novelcharacter.app.ui.image.ImageManagerViewModel by viewModels()
    private val organizeFolder =
        com.novelcharacter.app.ui.image.OrganizeFolderController(this, { organizeViewModel })

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
        // 대용량 백업 복사도 진행 표시 — 조용한 실패와 구분(변수 제어)
        val progress = createProgressDialog(R.string.backup_export_saving)
        progress.show()
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
            } finally {
                progress.dismissSafely()
                pendingBackupExportFile = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 런처 등록 순서 보존을 위해 onCreate에서 생성 (컨트롤러 KDoc 참조)
        excel = com.novelcharacter.app.excel.ExcelTransferController(this)
        excel.restoreState(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateThemeLabel()

        binding.themeRow.setOnClickListener {
            showThemeDialog()
        }

        // 읽기 화면의 필드 설명 ⓘ (B-44) — 저장은 즉시다(별도 확인 단계를 두지 않는다, 원칙 04).
        // 리스너를 붙이기 **전에** 상태를 세운다: 순서를 바꾸면 화면을 여는 것만으로
        // 저장된 값이 그대로 다시 저장돼 무해해 보이지만, 나중에 부수 효과가 붙으면 문다.
        binding.switchReadFieldNote.isChecked =
            com.novelcharacter.app.util.FieldNoteDisplayPrefs.isReadScreenNoteEnabled(requireContext())
        binding.switchReadFieldNote.setOnCheckedChangeListener { _, checked ->
            com.novelcharacter.app.util.FieldNoteDisplayPrefs
                .setReadScreenNoteEnabled(requireContext(), checked)
        }

        // 생일 축하 창 (사용자 요청 2026.08.20) — 위와 같은 규약(리스너 전에 상태를 세운다).
        binding.switchBirthdayCelebration.isChecked =
            com.novelcharacter.app.util.BirthdayCelebrationPrefs.isEnabled(requireContext())
        binding.switchBirthdayCelebration.setOnCheckedChangeListener { _, checked ->
            com.novelcharacter.app.util.BirthdayCelebrationPrefs
                .setEnabled(requireContext(), checked)
        }

        // Data management — 백업(전부 보관)과 데이터 추출(표로 뽑기)은 원하는 기본값이
        // 정반대라 행부터 갈라 둔다(설계 D1·D3)
        binding.fullBackupRow.setOnClickListener {
            excel.startFullBackup()
        }

        binding.exportRow.setOnClickListener {
            excel.showExportDialog()
        }

        binding.worldPackageRow.setOnClickListener {
            exportWorldPackage()
        }

        binding.importRow.setOnClickListener {
            excel.showImportDialog()
        }

        // 정리 폴더 왕복 — 이미지 탭에만 있던 것을 설정에서도 바로 쓸 수 있게(사용자 요청).
        binding.organizeFolderExportRow.setOnClickListener { organizeFolder.startOrganizeFolderExport() }
        binding.organizeFolderImportRow.setOnClickListener { organizeFolder.startOrganizeFolderImport() }
        binding.organizeFolderSettingsRow.setOnClickListener { organizeFolder.showOrganizeFolderSettings() }
        binding.organizeFolderHelpRow.setOnClickListener {
            com.novelcharacter.app.ui.common.HelpDialog.showHelp(
                requireContext(), com.novelcharacter.app.ui.common.HelpDialog.Topic.ORGANIZE_FOLDER
            )
        }

        binding.trashRow.setOnClickListener {
            findNavController().navigate(R.id.trashFragment)
        }

        binding.storageRow.setOnClickListener {
            findNavController().navigate(R.id.storageFragment)
        }

        binding.aiSettingsRow.setOnClickListener {
            findNavController().navigate(R.id.aiSettingsFragment)
        }

        binding.defaultFieldsRow.setOnClickListener {
            findNavController().navigate(R.id.defaultFieldManageFragment)
        }

        binding.imageCompressRow.setOnClickListener {
            com.novelcharacter.app.ui.image.ImageSettingsDialog.show(this)
        }

        binding.operationHistoryRow.setOnClickListener {
            findNavController().navigate(R.id.operationHistoryFragment)
        }

        // 자동 백업 이미지 기본값이 데이터 전용으로 바뀐 것을 최초 1회 고지 (변수 제어 — 무통보 변경 금지)
        maybeShowBackupImageNotice()

        binding.backupRestoreRow.setOnClickListener {
            showBackupRestoreDialog()
        }

        setupBackupNow()

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
            // **빌드 번호를 함께 보인다.** `versionName`은 손으로 올리는 값이라 모든 빌드가
            // 같은 문자열이고, 실제로 빌드를 가르는 것은 `versionCode`(CI 빌드 번호)다.
            // 그래서 종전 표시로는 **어느 빌드가 깔려 있는지 알 수 없었고**, 2026.08.02
            // 설치 충돌을 진단할 때 그 자리에서 막혔다(설치본과 아티팩트를 대조할 수 없었다).
            binding.versionText.text = getString(
                R.string.settings_version_format,
                pInfo.versionName,
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo)
            )
        } catch (e: Exception) {
            // 자기 패키지 조회라 사실상 실패하지 않지만, 실패했다면 **모른다고 말한다** —
            // 종전에는 "1.0"을 지어내 보여 줬고, 그것은 대조에 쓰면 틀린 답을 주는 값이다
            binding.versionText.text = getString(R.string.settings_version_unknown)
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
            // 성공한 백업이라도 이미지가 빠졌으면 반드시 보인다 — '완전한 백업'으로 오인 방지
            if (status.lastImageWarning.isNotBlank()) {
                sb.appendLine(status.lastImageWarning)
            }

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
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.backup_image_notice_title)
                .setMessage(R.string.backup_image_notice_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            com.novelcharacter.app.util.OnboardingPrefs.markShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_BACKUP_IMAGE_NOTICE_SHOWN)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        excel.saveState(outState)
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
        MaterialAlertDialogBuilder(ctx)
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
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.share_world_package)
                    .setItems(names) { _, which ->
                        askWorldPackageImages { includeImages ->
                            runWorldPackageExport(universes[which].id, includeImages)
                        }
                    }
                    .show()
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 월드패키지 '이미지 포함' 선택(곁다리 W-1 · B-73 해소).
     *
     * 종전에는 세계관을 고르는 즉시 기본값으로 나갔고, `share_world_include_images`는 정의만
     * 있고 참조가 0건이었다 — **수신측 고지("이미지 미포함 패키지")는 이미 배선돼 있었는데
     * 보내는 쪽에 고를 자리가 없었다.** 기본값은 현행 그대로 포함(true)이다.
     */
    private fun askWorldPackageImages(onChosen: (Boolean) -> Unit) {
        if (!isAdded) return
        val checked = booleanArrayOf(true)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_world_package)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.share_world_include_images)), checked
            ) { _, _, isChecked -> checked[0] = isChecked }
            .setPositiveButton(R.string.confirm) { _, _ -> onChosen(checked[0]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runWorldPackageExport(universeId: Long, includeImages: Boolean) {
        if (!isAdded) return
        // 작업형 진행도(R-26 · B-51) — 종전에는 불확정 스피너라 대형 세계관에서 진행 중인지
        // 멈춘 것인지 알 수 없었다. 자료(절)와 이미지(장)는 단위가 달라 구간을 나눠 보고한다.
        // 취소는 산출물을 만들지 않고 멈추는 것이다(D5가 엑셀 내보내기에서 정한 것과 같다).
        var cancelled = false
        val progress = showTaskProgress(
            R.string.world_package_progress_title,
            total = 0,
            stageRes = R.string.world_package_stage_sections
        ) { cancelled = true }
        val sectionsStage = getString(R.string.world_package_stage_sections)
        val imagesStage = getString(R.string.export_progress_stage_images)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val exporter = WorldPackageExporter(requireContext())
                val config = WorldPackageExporter.ExportConfig(
                    universeId = universeId,
                    includeImages = includeImages
                )
                val sink = WorldPackageExporter.ProgressSink(
                    onSections = { done, total -> postProgress(progress, done, total, sectionsStage) },
                    onImages = { done, total -> postProgress(progress, done, total, imagesStage) },
                    isCancelled = { cancelled }
                )
                val result = withContext(Dispatchers.IO) { exporter.export(config, sink) }

                if (!isAdded) return@launch
                // 패키지에 싣지 못한 것은 전부 고지한다(무통보 유실 금지 — 개발 의도 2번).
                // **한 토스트에 모아 띄우는 것이 요점이다**: 셋이 각자 뜨면 뒤엣것이 앞엣것을
                // 밀어내 사용자가 마지막 줄만 본다(B-118에서 둘이 더 늘어 그 자리가 됐다).
                val dropNotices = buildList {
                    if (result.droppedFactionRelationships > 0) {
                        add(
                            getString(
                                R.string.world_package_dropped_faction_relationships,
                                result.droppedFactionRelationships
                            )
                        )
                    }
                    if (result.droppedDuelMatches > 0) {
                        add(getString(R.string.world_package_dropped_duel_matches, result.droppedDuelMatches))
                    }
                    if (result.droppedDuelVerdicts > 0) {
                        add(getString(R.string.world_package_dropped_duel_verdicts, result.droppedDuelVerdicts))
                    }
                    // 이미지도 같은 자리에서 고지한다(B-225). 종전에는 이 축만 계수가 없어,
                    // 한 장의 읽기 오류가 그 엔티티의 남은 장까지 무음으로 떨어뜨렸다 —
                    // 받는 기기에서 비어 보이는데 보낸 사람은 끝까지 모른다.
                    // 사유별 내역은 엑셀 백업과 같은 문구를 쓴다(ImageNoticeRes).
                    val images = result.images
                    if (images.hasLoss) {
                        add(
                            if (images.includedCount == 0) {
                                getString(R.string.world_package_images_none_included, images.referencedCount)
                            } else {
                                getString(
                                    R.string.world_package_images_incomplete,
                                    images.referencedCount, images.includedCount, images.excludedCount
                                )
                            }
                        )
                        for ((reason, count) in images.lossReasons()) {
                            add(getString(ImageNoticeRes.lossReason(reason), count))
                        }
                        // 표본 파일명까지 싣는다 — 모으기만 하고 안 쓰면 다음 사람이 쓰이고
                        // 있다고 믿는다(콜드 검토). 엑셀은 이력 상세에 같은 줄을 싣는데
                        // 월드패키지는 그 상세 화면이 없어 이 토스트가 유일한 자리다.
                        if (images.sampleNames.isNotEmpty()) {
                            add(getString(R.string.export_images_detail_samples, images.sampleNames.joinToString(", ")))
                        }
                    }
                    if (images.referencesIncomplete) {
                        add(getString(R.string.export_images_refs_unreadable, images.unreadableRefCount))
                    }
                }
                if (dropNotices.isNotEmpty()) {
                    Toast.makeText(requireContext(), dropNotices.joinToString("\n"), Toast.LENGTH_LONG).show()
                }
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    result.file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_world_package)))
            } catch (e: ExportCancelledException) {
                // 실패가 아니다 — 내보내는 쪽이 파일을 이미 지웠으므로 알리기만 한다.
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.world_package_cancelled, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                progress?.dismiss()
            }
        }
    }

    // ── 백업/복원 ──

    /**
     * '지금 백업'(설계 D2 · B-86) — 주기(최대 하루)를 기다리지 않고 한 벌 만든다.
     *
     * 결과 고지는 **상태 카드**가 한다. WorkManager 백그라운드 작업이라 진행도 다이얼로그의
     * 대상이 아니고(D5에서 제외), 그래서 끝난 것을 알 길이 필요하다 — 작업 상태를 관찰해
     * 끝나면 카드를 다시 읽는다. 관찰하지 않으면 사용자는 화면을 나갔다 와야 결과를 본다.
     *
     * 연타 방어는 **`ExistingWorkPolicy.KEEP`이 하고**(워커 쪽), 버튼 비활성은 보조 수단이다 —
     * 다만 도는 동안 눌러도 아무 일이 없으면서 "시작했습니다"만 뜨는 것은 거짓이므로,
     * 도는 동안에는 버튼이 그 사실을 말한다.
     */
    private fun setupBackupNow() {
        val appContext = requireContext().applicationContext
        binding.backupNowButton.setOnClickListener {
            com.novelcharacter.app.backup.AutoBackupWorker.enqueueManual(appContext)
            Toast.makeText(requireContext(), R.string.backup_now_started, Toast.LENGTH_SHORT).show()
        }
        androidx.work.WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkLiveData(
                com.novelcharacter.app.backup.AutoBackupWorker.MANUAL_WORK_NAME
            )
            .observe(viewLifecycleOwner) { infos ->
                if (_binding == null) return@observe
                val running = infos?.any { !it.state.isFinished } == true
                binding.backupNowButton.isEnabled = !running
                binding.backupNowButton.setText(
                    if (running) R.string.backup_now_running else R.string.backup_now
                )
                // 막 끝났다면 상태 카드의 '마지막 성공/실패'가 이미 갱신돼 있다 — 다시 읽는다
                if (!running) loadBackupStatus()
            }
    }

    /** 자동 백업 옵션 행의 현재값 라벨 갱신 + '지금 백업'의 성격 고지 */
    private fun updateBackupOptionsLabel() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = BackupSettingsStore(requireContext().applicationContext).getSettings()
            if (_binding == null) return@launch
            val imagesText = getString(
                if (settings.includeImages) R.string.backup_option_images_on else R.string.backup_option_images_off
            )
            binding.backupOptionsValue.text =
                getString(R.string.backup_options_value, imagesText, settings.maxBackups)
            // '지금 백업'이 회전 풀을 주기와 공유한다는 사실을 숨기지 않는다 — 연타하면
            // 주기 이력이 밀려난다(설계 D2 고지). 보관 개수는 설정을 따르므로 함께 갱신한다.
            binding.backupNowCaption.text =
                getString(R.string.backup_now_purpose, settings.maxBackups)
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
            MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
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
            MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
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

        val dialog = MaterialAlertDialogBuilder(ctx)
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

        val dialog = MaterialAlertDialogBuilder(ctx)
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
        // 두 구간(복호화 · 암호를 걸어 다시 쓰기)이 모두 바이트 순회이고 총량을 안다 —
        // 각자 자기 총량으로 보고한다(합치면 단위가 다른 둘이 한 막대에 섞인다. R-26 · D5).
        val decryptScale = ProgressScale.forBytes(deviceEncFile.length())
        val progressDialog = showTaskProgress(
            R.string.backup_portable_progress_title,
            total = decryptScale.totalSteps,
            stageRes = R.string.backup_restore_stage_decrypt,
            format = TaskProgressDialog.CountFormat.MEGABYTES
        )
        val decryptStage = getString(R.string.backup_restore_stage_decrypt)
        val encryptStage = getString(R.string.backup_portable_stage_encrypt)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val portableFile = withContext(Dispatchers.IO) {
                    val plainTemp = File.createTempFile("export_plain_", ".xlsx", ctx.cacheDir)
                    try {
                        BackupEncryptor.decryptFile(deviceEncFile, plainTemp) { read ->
                            postProgress(
                                progressDialog, decryptScale.stepsFor(read), decryptScale.totalSteps,
                                decryptStage, TaskProgressDialog.CountFormat.MEGABYTES
                            )
                        }
                        // 두 번째 구간의 총량은 **평문 크기**다 — 복호화가 끝나야 알 수 있으므로
                        // 여기서 눈금을 새로 잡는다(총량 확정 후에 보고한다).
                        val encryptScale = ProgressScale.forBytes(plainTemp.length())
                        val portable = File.createTempFile("export_portable_", ".enc", ctx.cacheDir)
                        BackupEncryptor.encryptFilePortable(plainTemp, portable, passphrase) { written ->
                            postProgress(
                                progressDialog, encryptScale.stepsFor(written), encryptScale.totalSteps,
                                encryptStage, TaskProgressDialog.CountFormat.MEGABYTES
                            )
                        }
                        portable
                    } finally {
                        plainTemp.delete()
                    }
                }
                progressDialog?.dismiss()
                if (!isAdded || _binding == null) return@launch
                pendingBackupExportFile = portableFile
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                val fileName =
                    "NovelCharacter_Backup_${dateFormat.format(Date(deviceEncFile.lastModified()))}_portable.enc"
                backupExportLauncher.launch(fileName)
            } catch (e: Exception) {
                AppLogger.error("Settings", "이식 가능 백업 생성 실패", e)
                progressDialog?.dismiss()
                if (_binding != null) {
                    Toast.makeText(ctx, getString(R.string.backup_export_failed, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                passphrase.fill('\u0000')
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

        MaterialAlertDialogBuilder(requireContext())
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

        MaterialAlertDialogBuilder(requireContext())
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
                // **소유권을 함께 넘긴다.** 종전에는 *"비동기로 읽으므로 즉시 지우면 경쟁
                // 조건"*이라며 삭제를 포기하고 시스템에 맡겼는데, 받는 쪽도 남의 파일로 보아
                // 지우지 않아 **소유자가 아무도 없었다** — 복원할 때마다 옮겨 온 백업이
                // 캐시에 그대로 쌓였다. 만든 자리가 지운다(이 저장소의 규약).
                val isPortable = withContext(Dispatchers.IO) { BackupEncryptor.isPortableFormat(tempEncFile) }
                if (!isAdded) {
                    // 화면이 사라져 아무도 이어받지 않는다 — 여기서 지운다.
                    withContext(Dispatchers.IO) { tempEncFile.delete() }
                    return@launch
                }
                if (isPortable) {
                    // 이식 가능 형식 — 기기 키 대신 암호 입력으로 복원
                    showEnterPassphraseDialog { passphrase ->
                        restoreFromPortableFile(tempEncFile, passphrase, ownsEncFile = true)
                    }
                } else {
                    restoreFromEncryptedFile(tempEncFile, ownsEncFile = true)
                }
            } catch (e: Exception) {
                AppLogger.error("Settings", "백업 복원 실패 (복호화)", e)
                if (_binding != null) {
                    Toast.makeText(ctx, getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 불확정 진행 표시 다이얼로그 (총량을 셀 수 없는 단발 IO 작업용) — 공용 유틸 위임 */
    private fun createProgressDialog(messageRes: Int): AlertDialog =
        com.novelcharacter.app.util.createProgressDialog(requireContext(), messageRes)

    // ---------- 작업형 진행도 (규약 R-26 · B-51) ----------
    //
    // 백업 복원(복호화)·이식 백업 만들기·월드패키지 내보내기는 전부 **총량을 아는** 작업인데
    // 종전에는 불확정 스피너뿐이었다 — 수백 MB짜리 백업에서 "도는 중"과 "멈춤"이 구분되지 않았다.
    // 바이트 구간은 MB 눈금으로 환산해 보고한다([ProgressScale] — 상한이 Int를 넘기 때문).

    /** 작업형 진행 창. 화면이 없으면 null이고 작업은 그대로 진행된다. */
    private fun showTaskProgress(
        @androidx.annotation.StringRes titleRes: Int,
        total: Int,
        @androidx.annotation.StringRes stageRes: Int,
        format: TaskProgressDialog.CountFormat = TaskProgressDialog.CountFormat.ITEMS,
        onCancel: (() -> Unit)? = null
    ): TaskProgressDialog.Handle? {
        if (!isAdded) return null
        return runCatching {
            TaskProgressDialog.show(
                requireContext(), titleRes = titleRes, total = total,
                stageRes = stageRes, format = format, onCancel = onCancel
            )
        }.getOrNull()
    }

    /** 진행도 갱신 — 작업은 IO, 갱신은 메인. 화면이 사라진 뒤의 갱신은 조용히 버린다. */
    private fun postProgress(
        handle: TaskProgressDialog.Handle?,
        current: Int,
        total: Int,
        stage: String? = null,
        format: TaskProgressDialog.CountFormat = TaskProgressDialog.CountFormat.ITEMS
    ) {
        handle ?: return
        activity?.runOnUiThread { if (isAdded) handle.update(current, total, stage, format) }
    }

    /**
     * @param ownsEncFile 넘긴 쪽이 **소유권까지 넘겼는가**(외부에서 옮겨 온 임시 .enc).
     *   자동 백업 목록에서 고른 파일은 앱의 보관물이라 **지우면 안 된다** — 그래서 기본은 false다.
     */
    private fun restoreFromEncryptedFile(encFile: File, ownsEncFile: Boolean = false) {
        if (!isAdded) return

        val ctx = requireContext()
        // 복호화는 총량(파일 바이트)을 아는 작업형이다(R-26 · B-51) — 종전의 불확정 스피너로는
        // 수백 MB짜리 백업에서 도는 중인지 멈춘 것인지 알 수 없었다(사용자 회신이 이 항목의 근거다).
        // 취소는 제공하지 않는다: 이어지는 가져오기가 DB를 갈아 끼우므로 중간 경계가 없고,
        // 복호화만 끊어 봐야 얻는 것이 없다.
        val scale = ProgressScale.forBytes(encFile.length())
        val progressDialog = showTaskProgress(
            R.string.backup_restore_progress_title,
            total = scale.totalSteps,
            stageRes = R.string.backup_restore_stage_decrypt,
            format = TaskProgressDialog.CountFormat.MEGABYTES
        )

        viewLifecycleOwner.lifecycleScope.launch {
            var tempXlsx: File? = null
            try {
                // 복호화
                tempXlsx = withContext(Dispatchers.IO) {
                    val xlsx = File.createTempFile("restore_", ".xlsx", ctx.cacheDir)
                    BackupEncryptor.decryptFile(encFile, xlsx) { read ->
                        postProgress(
                            progressDialog, scale.stepsFor(read), scale.totalSteps,
                            format = TaskProgressDialog.CountFormat.MEGABYTES
                        )
                    }
                    xlsx
                }
                if (_binding == null) return@launch

                // 복호화된 파일을 직접 전달 (불필요한 복사 없이)
                progressDialog?.dismiss()
                // 복호화 xlsx의 소유권을 가져오기에 넘긴다 — 다 읽은 뒤 그쪽이 지운다.
                excel.importFromLocalFile(tempXlsx!!, ownsFile = true)

            } catch (e: Exception) {
                AppLogger.error("Settings", "백업 복원 실패", e)
                // 실패로 끝났으면 만들다 만 복호화 파일의 주인도 여기다.
                tempXlsx?.let { f -> withContext(Dispatchers.IO) { f.delete() } }
                progressDialog?.dismiss()
                if (_binding != null) {
                    // 복호화 실패의 가장 흔한 원인(다른 기기의 백업)을 함께 안내
                    Toast.makeText(
                        ctx,
                        getString(R.string.backup_restore_failed, e.message) + "\n" + getString(R.string.backup_restore_device_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                // 복호화가 끝났으면(성공이든 실패든) 옮겨 온 .enc는 더 볼 일이 없다.
                if (ownsEncFile) withContext(Dispatchers.IO) { encFile.delete() }
            }
        }
    }

    /**
     * 이식 가능(암호) 백업 복원. 잘못된 암호(GCM 태그 검증 실패)는
     * 오류로 끝내지 않고 재입력 다이얼로그로 되돌린다 — 변수 제어 원칙.
     */
    /**
     * @param ownsEncFile [restoreFromEncryptedFile]과 같은 계약. **암호 재입력 갈래에서는
     *   지우지 않고 소유권을 그대로 넘긴다** — 다음 시도가 같은 파일을 다시 읽는다.
     */
    private fun restoreFromPortableFile(encFile: File, passphrase: CharArray, ownsEncFile: Boolean = false) {
        if (!isAdded) return

        val ctx = requireContext()
        val scale = ProgressScale.forBytes(encFile.length())
        val progressDialog = showTaskProgress(
            R.string.backup_restore_progress_title,
            total = scale.totalSteps,
            stageRes = R.string.backup_restore_stage_decrypt,
            format = TaskProgressDialog.CountFormat.MEGABYTES
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tempXlsx = withContext(Dispatchers.IO) {
                    val xlsx = File.createTempFile("restore_", ".xlsx", ctx.cacheDir)
                    try {
                        BackupEncryptor.decryptFilePortable(encFile, xlsx, passphrase) { read ->
                            postProgress(
                                progressDialog, scale.stepsFor(read), scale.totalSteps,
                                format = TaskProgressDialog.CountFormat.MEGABYTES
                            )
                        }
                    } catch (e: Exception) {
                        xlsx.delete()
                        throw e
                    }
                    xlsx
                }
                if (_binding == null) return@launch

                progressDialog?.dismiss()
                // 복호화 xlsx의 소유권을 가져오기에 넘긴다 — 다 읽은 뒤 그쪽이 지운다.
                excel.importFromLocalFile(tempXlsx, ownsFile = true)
                if (ownsEncFile) withContext(Dispatchers.IO) { encFile.delete() }
            } catch (e: javax.crypto.AEADBadTagException) {
                // 잘못된 암호 — 재입력 기회 제공. **여기서는 .enc를 지우지 않는다**:
                // 다음 시도가 같은 파일을 다시 읽으므로 소유권을 그대로 넘긴다.
                progressDialog?.dismiss()
                if (_binding != null && isAdded) {
                    Toast.makeText(ctx, R.string.backup_passphrase_wrong, Toast.LENGTH_SHORT).show()
                    showEnterPassphraseDialog { retry ->
                        restoreFromPortableFile(encFile, retry, ownsEncFile = ownsEncFile)
                    }
                } else if (ownsEncFile) {
                    // 다시 물을 화면이 없다 — 이어받을 사람이 없으므로 여기서 지운다.
                    withContext(Dispatchers.IO) { encFile.delete() }
                }
            } catch (e: Exception) {
                AppLogger.error("Settings", "이식 백업 복원 실패", e)
                progressDialog?.dismiss()
                if (ownsEncFile) withContext(Dispatchers.IO) { encFile.delete() }
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

        // 대량 DB·파일 삭제 — 완료까지 진행 표시로 조용한 실패와 구분(변수 제어)
        val progress = createProgressDialog(R.string.reset_in_progress)
        progress.show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // DB 초기화 (트랜잭션 내 FK CASCADE 안전 순서)
                //
                // **비우는 범위의 단일 소스는 [ResetPlan]이다** (S-13). 아래 호출 순서는
                // ResetPlan.explicitOrder와 같아야 하며, 순수 JVM 테스트(ResetPlanTest)와
                // tools/verify_reset_coverage.py가 셋(엔티티 목록 · 계획 · 이 호출부)을 대조한다.
                // 엔티티를 늘리고 여기를 잊으면 그 테이블은 '모든 데이터 삭제' 뒤에도 살아남는다.
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
                    // ── S-13: 여기부터가 종전에 빠져 있던 독립 테이블들이다 ──
                    // 스냅샷 행을 **파일 삭제보다 먼저** 지운다. 순서가 반대면 "스냅샷이 살아 있는 동안
                    // 그 파일은 남긴다"는 규약과 충돌하고, 그 사이 복원하면 깨진 캐릭터가 되살아난다.
                    db.trashSnapshotDao().deleteAll()
                    db.operationLogDao().clear()
                    db.characterListPresetDao().deleteAll()
                    // image_meta는 FK가 없어 어떤 부모로도 지워지지 않았다. 이것을 지워야
                    // 자식 image_tags의 CASCADE도 비로소 성립한다(그전까지 태그가 영원히 남았다).
                    db.imageMetaDao().deleteAll()
                    // 전역 기본 필드 템플릿(B-119)은 세계관에 매달리지 않는다 — 위 universes
                    // 삭제로는 사라지지 않으므로 직접 지운다(ResetPlan이 explicit로 든 이유).
                    db.defaultFieldTemplateDao().deleteAll()
                }

                // SharedPreferences 초기화 (테마 제외) — 초기화가 UI 상태 찌꺼기를 남기지 않게
                // 실제 사용 중인 prefs 파일명과 일치시켜 유지한다(과거 죽은 이름 4개 교정:
                // search_filters→search_ui_state, namebank_prefs→namebank_ui_state,
                // graph_prefs→graph_ui_state, universe_list_state→character_list_ui)
                withContext(Dispatchers.IO) {
                    listOf(
                        // **`image_index_prefs`는 이제 쓰는 코드가 없다 (B-106 ⓑ)** — 작품·세계관
                        // 카드도 시드 방식으로 옮겨 가며 저장소가 사라졌다. **그래도 목록에 남긴다:**
                        // 옛 버전에서 올라온 기기에는 그 파일이 그대로 있고, 지우는 경로가 여기뿐이다.
                        // 쓰는 코드가 없다는 이유로 이 줄을 걷어내면 **그 찌꺼기가 영영 남는다.**
                        "image_index_prefs", "timeline_ui_state", "stats_prefs",
                        "supplement_criteria", "supplement_ui_state", "search_ui_state",
                        "namebank_ui_state", "graph_ui_state", "character_list_ui",
                        "analysis_ui_state", "image_manager_ui_state", "field_manage_ui_state",
                        "field_library_ui_state",
                        "assistant_prefs", "app_migrations",
                        // 편집 드래프트 — 초기화 후 재사용된 캐릭터 id에 이전 드래프트가 되살아나지 않도록
                        "character_edit_drafts",
                        // S-13: 이미지 폴더 왕복 장부(지문·개명 별칭). 데이터를 다 지우고도 이것이 남으면,
                        // 사용자가 정리 폴더에 다시 놓은 이미지가 "이미 내보낸 사본"으로 판정돼
                        // 진입 감지에 잡히지 않는다 — 조용히 안 들어온다(무통보 유실).
                        "folder_roundtrip_prefs"
                        // **일부러 남기는 것:** theme_cache(테마) · ai_keys · ai_providers ·
                        // ai_prompt_settings · onboarding_prefs. 이들은 '작품 데이터'가 아니라
                        // 사용자 설정·자격증명이며, 데이터를 지운다고 API 키를 다시 입력하게 만들
                        // 이유가 없다. 지우려면 확인 다이얼로그에 별도 항목으로 물어야 한다(자율성).
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
            } finally {
                progress.dismissSafely()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
