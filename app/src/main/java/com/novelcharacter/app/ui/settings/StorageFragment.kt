package com.novelcharacter.app.ui.settings

import android.graphics.Color
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.maintenance.SystemMaintenanceService
import com.novelcharacter.app.databinding.FragmentStorageBinding
import com.novelcharacter.app.util.StorageAnalyzer
import kotlinx.coroutines.launch

/**
 * 저장 공간 분석 화면 — 앱 용량을 분류별로 실측해 보여주고(변수 제어),
 * 안전한 정리 액션(고아 이미지 정리 / 내보내기 캐시 비우기)을 자율 실행하게 한다.
 */
class StorageFragment : Fragment() {

    private var _binding: FragmentStorageBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as NovelCharacterApp }

    // 분류별 표시 색상 (막대) — 접근성 위해 라벨/수치도 항상 병기
    private data class Row(val labelRes: Int, val color: Int, val cat: StorageAnalyzer.Category)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.cleanOrphanRow.setOnClickListener { confirmCleanOrphans() }
        binding.clearCacheRow.setOnClickListener { confirmClearCache() }
        binding.backupManageRow.setOnClickListener {
            // 자동 백업 옵션은 설정 화면(여기로 진입한 곳)의 '자동 백업 관리' 행에서 조정한다
            findNavController().popBackStack()
        }

        loadReport()
    }

    private fun loadReport() {
        val ctx = context ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val report = StorageAnalyzer.analyze(ctx, app.database)
            if (_binding == null) return@launch
            render(report)
            binding.progressBar.visibility = View.GONE
            binding.scrollContent.visibility = View.VISIBLE
        }
    }

    private fun render(report: StorageAnalyzer.StorageReport) {
        binding.totalSizeText.text = StorageAnalyzer.formatBytes(report.totalBytes)

        val rows = listOf(
            Row(R.string.storage_cat_referenced, Color.parseColor("#5C6BC0"), report.referencedImages),
            Row(R.string.storage_cat_library, Color.parseColor("#66BB6A"), report.libraryImages),
            Row(R.string.storage_cat_backups, Color.parseColor("#EF5350"), report.autoBackups),
            Row(R.string.storage_cat_export_cache, Color.parseColor("#FF7043"), report.exportCache),
            Row(R.string.storage_cat_orphan, Color.parseColor("#FFA726"), report.orphanImages),
            Row(R.string.storage_cat_trash, Color.parseColor("#26A69A"), report.trashHeldImages),
            Row(R.string.storage_cat_database, Color.parseColor("#42A5F5"), report.database),
            Row(R.string.storage_cat_logs, Color.parseColor("#AB47BC"), report.logs),
            Row(R.string.storage_cat_other, Color.parseColor("#78909C"), report.other),
        ).filter { it.cat.bytes > 0 }.sortedByDescending { it.cat.bytes }

        val maxBytes = rows.maxOfOrNull { it.cat.bytes }?.coerceAtLeast(1) ?: 1
        val container = binding.categoriesContainer
        container.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        for (row in rows) {
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (12 * density).toInt()
                layoutParams = lp
            }

            // 라벨 + 크기
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val label = TextView(ctx).apply {
                text = getString(row.labelRes)
                textSize = 14f
                setTextColor(resolveOnSurface())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val size = TextView(ctx).apply {
                val count = row.cat.fileCount
                text = if (count > 0) "${StorageAnalyzer.formatBytes(row.cat.bytes)} · ${getString(R.string.storage_file_count, count)}"
                    else StorageAnalyzer.formatBytes(row.cat.bytes)
                textSize = 13f
                setTextColor(resolveOnSurfaceVariant())
            }
            header.addView(label)
            header.addView(size)
            rowLayout.addView(header)

            // 비례 막대
            val barBg = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (8 * density).toInt()
                )
                lp.topMargin = (4 * density).toInt()
                layoutParams = lp
                setBackgroundColor(Color.parseColor("#22808080"))
            }
            val fillWeight = (row.cat.bytes.toFloat() / maxBytes.toFloat()).coerceIn(0.02f, 1f)
            val bar = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fillWeight)
                setBackgroundColor(row.color)
            }
            val spacer = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - fillWeight)
            }
            barBg.addView(bar)
            if (1f - fillWeight > 0f) barBg.addView(spacer)
            rowLayout.addView(barBg)

            container.addView(rowLayout)
        }
    }

    private fun confirmCleanOrphans() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.storage_clean_orphan_images)
            .setMessage(R.string.storage_clean_orphan_confirm)
            .setPositiveButton(R.string.storage_clean_run) { _, _ -> runCleanOrphans() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runCleanOrphans() {
        val ctx = context ?: return
        showResult(getString(R.string.storage_working))
        viewLifecycleOwner.lifecycleScope.launch {
            val service = SystemMaintenanceService(ctx.applicationContext, app.database)
            val result = service.cleanOrphanImageFiles()
            if (_binding == null) return@launch
            when {
                // 참조 집합이 불완전(데이터 손상)해 안전을 위해 삭제하지 않음
                result.aborted -> showResult(getString(R.string.storage_clean_orphan_aborted))
                else -> {
                    val base = getString(R.string.storage_clean_orphan_done, result.deleted, StorageAnalyzer.formatBytes(result.freed))
                    val msg = if (result.skippedRecent > 0) {
                        base + "\n" + getString(R.string.storage_clean_orphan_skipped_recent, result.skippedRecent)
                    } else base
                    showResult(msg)
                }
            }
            loadReport()
        }
    }

    private fun confirmClearCache() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.storage_clear_export_cache)
            .setMessage(R.string.storage_clear_cache_confirm)
            .setPositiveButton(R.string.storage_clean_run) { _, _ -> runClearCache() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runClearCache() {
        val ctx = context ?: return
        showResult(getString(R.string.storage_working))
        viewLifecycleOwner.lifecycleScope.launch {
            val freed = StorageAnalyzer.clearExportCache(ctx.applicationContext)
            if (_binding == null) return@launch
            showResult(getString(R.string.storage_clear_cache_done, StorageAnalyzer.formatBytes(freed)))
            loadReport()
        }
    }

    private fun showResult(text: String) {
        _binding?.resultText?.apply {
            visibility = View.VISIBLE
            this.text = text
        }
    }

    private fun resolveOnSurface(): Int = themeColor(android.R.attr.textColorPrimary)
    private fun resolveOnSurfaceVariant(): Int = themeColor(android.R.attr.textColorSecondary)

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        val theme = context?.theme ?: return Color.GRAY
        if (!theme.resolveAttribute(attr, tv, true)) return Color.GRAY
        // textColorPrimary/Secondary는 ColorStateList 리소스로 올 수 있어 data가 아닌 resourceId로 해석
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(requireContext(), tv.resourceId)
        } else tv.data
    }

    override fun onDestroyView() {
        binding.categoriesContainer.removeAllViews()
        super.onDestroyView()
        _binding = null
    }
}
