package com.novelcharacter.app.ui.image

import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.DialogImageSettingsBinding
import com.novelcharacter.app.util.DuelImageBasisPrefs
import com.novelcharacter.app.util.DuelImagePrune
import com.novelcharacter.app.util.ImageSettingsStore
import com.novelcharacter.app.util.RepresentativeWeighting
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
            val currentAutoLink = store.getAutoLinkByCharacter()
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
                MaterialAlertDialogBuilder(ctx)
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
                MaterialAlertDialogBuilder(ctx)
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

            // 캐릭터 자동 링크 — 기본 켜짐
            binding.autoLinkSwitch.isChecked = currentAutoLink

            // ── 대결 기준 축의 쓰임 (B-104 소비처 ⓑ·ⓒ · 설계 13-5) ──
            //
            // **어느 축인가는 여기서 고르지 않는다** — 그 값은 DB(축 편집 창)이고 여기 셋은
            // 기기 설정이다. 같은 창에 섞으면 백업을 따라가는 값과 안 가는 값이 한 줄에 선다.
            var strength = DuelImageBasisPrefs.strength(ctx)
            var pruneOptions = DuelImageBasisPrefs.pruneOptions(ctx)
            when (strength) {
                RepresentativeWeighting.Strength.OFF -> binding.strengthOff.isChecked = true
                RepresentativeWeighting.Strength.WEAK -> binding.strengthWeak.isChecked = true
                RepresentativeWeighting.Strength.STRONG -> binding.strengthStrong.isChecked = true
            }
            binding.duelStrengthGroup.setOnCheckedChangeListener { _, id ->
                strength = when (id) {
                    R.id.strengthOff -> RepresentativeWeighting.Strength.OFF
                    R.id.strengthStrong -> RepresentativeWeighting.Strength.STRONG
                    else -> RepresentativeWeighting.Strength.WEAK
                }
            }

            fun refreshPruneLabels() {
                binding.pruneBottomValue.text =
                    ctx.getString(R.string.image_settings_duel_prune_bottom, pruneOptions.percent)
                binding.pruneMinPlayedValue.text =
                    ctx.getString(R.string.image_settings_duel_prune_min_played, pruneOptions.played)
            }
            refreshPruneLabels()
            binding.pruneBottomValue.setOnClickListener {
                val choices = DuelImagePrune.BOTTOM_PERCENT_CHOICES
                val labels = choices
                    .map { ctx.getString(R.string.image_settings_duel_prune_bottom, it) }
                    .toTypedArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.image_settings_duel_basis_title)
                    .setSingleChoiceItems(labels, choices.indexOf(pruneOptions.percent).coerceAtLeast(0)) { d, w ->
                        pruneOptions = pruneOptions.copy(bottomPercent = choices[w])
                        refreshPruneLabels(); d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            binding.pruneMinPlayedValue.setOnClickListener {
                val choices = DuelImagePrune.MIN_PLAYED_CHOICES
                val labels = choices
                    .map { ctx.getString(R.string.image_settings_duel_prune_min_played, it) }
                    .toTypedArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.image_settings_duel_basis_title)
                    .setSingleChoiceItems(labels, choices.indexOf(pruneOptions.played).coerceAtLeast(0)) { d, w ->
                        pruneOptions = pruneOptions.copy(minPlayed = choices[w])
                        refreshPruneLabels(); d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            MaterialAlertDialogBuilder(ctx)
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
                        DuelImageBasisPrefs.save(ctx, strength, pruneOptions)
                        val autoLinkOn = binding.autoLinkSwitch.isChecked
                        store.setAutoLinkByCharacter(autoLinkOn)
                        // 끔 → 켬 전환은 즉시 전체 정리 — 캐릭터를 일일이 다시 저장하지 않아도
                        // 지금 등록 상태대로 링크가 잡히게 한다(원칙 04). 결과는 그 자리에서 고지.
                        if (autoLinkOn && !currentAutoLink) {
                            val app = fragment.activity?.application as? com.novelcharacter.app.NovelCharacterApp
                            if (app != null) {
                                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        com.novelcharacter.app.util.CharacterImageAutoLinker.resync(app.database)
                                    }.getOrNull()
                                }
                                if (result != null && result.hasChanges && fragment.isAdded) {
                                    android.widget.Toast.makeText(
                                        ctx.applicationContext,
                                        ctx.getString(R.string.image_settings_auto_link_resynced, result.linked, result.released),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                        onSaved?.invoke()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
