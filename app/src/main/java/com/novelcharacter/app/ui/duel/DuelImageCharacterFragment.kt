package com.novelcharacter.app.ui.duel

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentDuelImageCharacterBinding
import com.novelcharacter.app.ui.adapter.DuelImageCharacterAdapter
import com.novelcharacter.app.util.CharacterImageLoader
import com.novelcharacter.app.util.DuelImageRoster
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **이미지 축에서 누구의 그림을 겨룰지 고르는 화면** (B-104 이미지 축, 설계 13장).
 *
 * 이미지 대결은 캐릭터마다 따로 논다 — 사용자 확정: *"이미지 대결은 캐릭터마다 하는거야.
 * 다른 캐릭터랑 붙는 게 아니라. 자기 이미지중에 하는 거야."* 그래서 축 하나를 열면 곧바로
 * 대결이 아니라 이 목록이 먼저이고, 여기서 고른 캐릭터가 대결 화면의 참가자 집합을 정한다.
 *
 * **판단은 [DuelImageRoster]가 pure로 든다** — 누가 목록에 오르는가(이미지 2장 이상),
 * 몇 판을 쳤는가, 남은 짝이 몇인가. 이 조각이 하는 일은 그것을 붙이는 것뿐이다
 * (「세션 착수 규칙」 4번 — 자동 검증이 화면을 못 본다).
 *
 * **빠진 캐릭터를 세어서 말한다.** 이미지가 한 장뿐이거나 없는 캐릭터는 붙일 짝이 없어
 * 목록에 없는데, 그 사실을 말하지 않으면 사용자는 *"내 캐릭터가 왜 없지"*의 답을 얻지 못한다
 * (개발 의도 2번).
 */
class DuelImageCharacterFragment : Fragment() {

    private var _binding: FragmentDuelImageCharacterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DuelViewModel by viewModels()

    private var axisId: Long = -1L

    /** 캐릭터 id → 첫 그림 섬네일. 비동기로 채우고 채워지는 대로 그 줄만 다시 그린다. */
    private val thumbnails = HashMap<Long, Bitmap>()

    private val adapter by lazy {
        DuelImageCharacterAdapter(
            onClick = { entry -> openPlay(entry) },
            thumbnailOf = { entry -> thumbnails[entry.characterId] }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDuelImageCharacterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        axisId = arguments?.getLong("axisId", -1L) ?: -1L
        if (axisId == -1L) {
            findNavController().popBackStack()
            return
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.characterRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.characterRecyclerView.adapter = adapter

        load()
    }

    /**
     * 목록을 다시 읽는다 — **`onResume`이 아니라 진입과 복귀에서** 부른다.
     *
     * 대결을 하고 돌아오면 판 수가 달라져 있다. 그때 옛 숫자가 남아 있으면 사용자가 방금 한
     * 일이 목록에 반영되지 않은 것처럼 보인다.
     */
    override fun onResume() {
        super.onResume()
        if (axisId != -1L) load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val axis = viewModel.axis(axisId) ?: run { findNavController().popBackStack(); return@launch }
            binding.toolbar.title = axis.name
            val roster = viewModel.imageRoster(axis)
            if (!isAdded) return@launch

            adapter.submitList(roster.entries)
            binding.emptyText.visibility = if (roster.any) View.GONE else View.VISIBLE
            binding.characterRecyclerView.visibility = if (roster.any) View.VISIBLE else View.GONE

            // 한 장짜리와 0장을 갈라 말한다 — 사용자가 할 일이 다르다(한 장 더 넣기 vs 넣기).
            binding.skippedText.visibility = if (roster.hasSkipped) View.VISIBLE else View.GONE
            if (roster.hasSkipped) {
                binding.skippedText.text = when {
                    roster.skippedSingleImage > 0 && roster.skippedNoImage > 0 -> getString(
                        R.string.duel_image_character_skipped_both,
                        roster.skippedSingleImage, roster.skippedNoImage
                    )
                    roster.skippedSingleImage > 0 -> getString(
                        R.string.duel_image_character_skipped_single, roster.skippedSingleImage
                    )
                    else -> getString(
                        R.string.duel_image_character_skipped_none, roster.skippedNoImage
                    )
                }
            }

            loadThumbnails(roster.entries)
        }
    }

    /**
     * 섬네일은 **첫 그림**으로 든다.
     *
     * 대표 이미지를 쓰지 않는 것은 일부러다 — 이 화면에서 그림은 *"누구인가"*를 알아보는
     * 표시일 뿐이고, 대표는 대결이 정하려는 것 중 하나라 여기 끌어들이면 **아직 정하지 않은
     * 답을 미리 보여 주는 셈**이 된다(대결 화면이 점수를 안 띄우는 것과 같은 근거).
     */
    private fun loadThumbnails(entries: List<DuelImageRoster.Entry>) {
        if (entries.isEmpty()) return
        val filesDir = requireContext().filesDir
        viewLifecycleOwner.lifecycleScope.launch {
            for (entry in entries) {
                if (thumbnails.containsKey(entry.characterId)) continue
                val path = entry.paths.firstOrNull() ?: continue
                val bitmap = withContext(Dispatchers.IO) {
                    CharacterImageLoader.decodeThumbnail(path, filesDir, 128)
                }
                if (!isAdded) return@launch
                if (bitmap != null) {
                    thumbnails[entry.characterId] = bitmap
                    val index = adapter.currentList.indexOfFirst { it.characterId == entry.characterId }
                    if (index >= 0) adapter.notifyItemChanged(index)
                }
            }
        }
    }

    private fun openPlay(entry: DuelImageRoster.Entry) {
        val bundle = Bundle().apply {
            putLong("axisId", axisId)
            putLong("characterId", entry.characterId)
        }
        findNavController().navigateSafe(
            R.id.duelImageCharacterFragment, R.id.duelPlayFragment, bundle
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.characterRecyclerView.adapter = null
        _binding = null
    }
}
