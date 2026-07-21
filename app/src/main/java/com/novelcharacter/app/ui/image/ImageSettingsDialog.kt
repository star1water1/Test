package com.novelcharacter.app.ui.image

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.DialogImageSettingsBinding
import com.novelcharacter.app.util.ImageSettingsStore
import com.novelcharacter.app.util.StorageAnalyzer
import kotlinx.coroutines.launch

/**
 * 이미지 불러오기 자동 압축 설정 다이얼로그. 설정 화면과 이미지 관리 탭 양쪽에서 재사용한다.
 * 마스터 스위치(기본 OFF)를 켜면 품질 %·최대 해상도·"일정 이하 압축 안 함" 옵션이 노출된다.
 */
object ImageSettingsDialog {

    fun show(fragment: Fragment, onSaved: (() -> Unit)? = null) {
        val ctx = fragment.context ?: return
        val store = ImageSettingsStore(ctx.applicationContext)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val current = store.getSettings()
            val currentPolicy = store.getEditorRemovePolicy()
            val binding = DialogImageSettingsBinding.inflate(fragment.layoutInflater)

            // 로컬 편집 상태 (저장 버튼에서만 반영)
            var quality = current.qualityPercent
            var maxEdge = current.maxLongEdgePx
            var skipBytes = current.skipBelowBytes

            fun refreshQualityLabel() {
                binding.qualityLabel.text = ctx.getString(R.string.image_settings_quality_format, quality)
            }
            fun refreshMaxEdgeLabel() {
                binding.maxEdgeValue.text = ctx.getString(R.string.image_settings_max_edge_format, maxEdge)
            }
            fun refreshSkipLabel() {
                binding.skipValue.text = ctx.getString(
                    R.string.image_settings_skip_format, StorageAnalyzer.formatBytes(skipBytes)
                )
            }

            binding.enableSwitch.isChecked = current.enabled
            binding.compressOptions.visibility = if (current.enabled) View.VISIBLE else View.GONE
            binding.enableSwitch.setOnCheckedChangeListener { _, on ->
                binding.compressOptions.visibility = if (on) View.VISIBLE else View.GONE
            }

            binding.qualitySlider.value = quality.toFloat()
            refreshQualityLabel()
            binding.qualitySlider.addOnChangeListener { _, v, _ -> quality = v.toInt(); refreshQualityLabel() }

            binding.capSwitch.isChecked = current.capDimension
            binding.maxEdgeValue.isEnabled = current.capDimension
            refreshMaxEdgeLabel()
            binding.capSwitch.setOnCheckedChangeListener { _, on -> binding.maxEdgeValue.isEnabled = on }
            binding.maxEdgeValue.setOnClickListener {
                val choices = ImageSettingsStore.MAX_LONG_EDGE_CHOICES
                val labels = choices.map { ctx.getString(R.string.image_settings_max_edge_format, it) }.toTypedArray()
                val checked = choices.indexOf(maxEdge).coerceAtLeast(0)
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.image_settings_cap_dimension)
                    .setSingleChoiceItems(labels, checked) { d, w -> maxEdge = choices[w]; refreshMaxEdgeLabel(); d.dismiss() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            binding.skipSwitch.isChecked = current.skipBelowEnabled
            binding.skipValue.isEnabled = current.skipBelowEnabled
            refreshSkipLabel()
            binding.skipSwitch.setOnCheckedChangeListener { _, on -> binding.skipValue.isEnabled = on }
            binding.skipValue.setOnClickListener {
                val choices = ImageSettingsStore.SKIP_BELOW_CHOICES
                val labels = choices.map {
                    ctx.getString(R.string.image_settings_skip_format, StorageAnalyzer.formatBytes(it))
                }.toTypedArray()
                val checked = choices.indexOf(skipBytes).coerceAtLeast(0)
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.image_settings_skip_below)
                    .setSingleChoiceItems(labels, checked) { d, w -> skipBytes = choices[w]; refreshSkipLabel(); d.dismiss() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            // 편집창 이미지 제거 정책(라이브러리 기능) — 기본: 라이브러리만 보존
            when (currentPolicy) {
                ImageSettingsStore.EditorRemovePolicy.ALWAYS_ADOPT -> binding.policyAlwaysAdopt.isChecked = true
                ImageSettingsStore.EditorRemovePolicy.LIBRARY_ONLY -> binding.policyLibraryOnly.isChecked = true
            }

            AlertDialog.Builder(ctx)
                .setTitle(R.string.image_settings_title)
                .setView(binding.root)
                .setPositiveButton(R.string.save) { _, _ ->
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        store.setEnabled(binding.enableSwitch.isChecked)
                        store.setQualityPercent(quality)
                        store.setCapDimension(binding.capSwitch.isChecked)
                        store.setMaxLongEdgePx(maxEdge)
                        store.setSkipBelowEnabled(binding.skipSwitch.isChecked)
                        store.setSkipBelowBytes(skipBytes)
                        store.setEditorRemovePolicy(
                            if (binding.policyAlwaysAdopt.isChecked) ImageSettingsStore.EditorRemovePolicy.ALWAYS_ADOPT
                            else ImageSettingsStore.EditorRemovePolicy.LIBRARY_ONLY
                        )
                        onSaved?.invoke()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
