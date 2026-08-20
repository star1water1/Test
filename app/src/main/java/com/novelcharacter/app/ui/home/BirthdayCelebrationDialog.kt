package com.novelcharacter.app.ui.home

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.DialogBirthdayCelebrationBinding
import com.novelcharacter.app.databinding.ItemBirthdayCelebrationPageBinding
import com.novelcharacter.app.util.BirthdayCelebration
import com.novelcharacter.app.util.CharacterImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **그날 처음 앱을 열 때 뜨는 생일 축하 창** (사용자 요청 2026.08.20).
 *
 * 원문: *"생일인 캐릭터 띄우는 거 그날 처음 앱 켰을때 약간 크게 모달로 꾸며서 생일축하
 * 해줄 수 있으면 좋겠음. … 생일에는 해당 캐릭터의 생일 대사중 하나를 모달에 같이 띄워주면"*
 *
 * ## 여럿이면 좌우로 넘긴다 (사용자 확정)
 * 목록으로 이름만 세우면 **캐릭터마다의 그림과 대사를 띄울 자리가 없고**, 캐릭터마다 창을
 * 따로 띄우면 닫는 동작이 사람 수만큼 반복된다. 한 창 안에서 넘기면 둘 다 피한다.
 * 한 명뿐일 때는 점 표시가 통째로 사라져 그냥 한 장이다(R-24).
 *
 * ## 무엇이 뜨는지는 [BirthdayCelebration]이 정한다
 * 이 화면은 **읽어 온 것을 그리기만 한다.** 어느 대사를 집는가·나이를 셀 수 있는가는
 * 순수 계층이 들어 시험이 실제로 돌고, 화면은 그 답에 말글만 입힌다.
 */
class BirthdayCelebrationDialog : DialogFragment() {

    private var binding: DialogBirthdayCelebrationBinding? = null
    private var pages: List<BirthdayCelebration.Page> = emptyList()

    /** '캐릭터 열기'가 어디로 갈지 — 지금 보이는 쪽이 정한다. */
    private var currentPage: Int = 0

    /** 창을 닫은 뒤 부르는 쪽이 캐릭터로 보낸다. 화면 이동은 이 창의 몫이 아니다. */
    var onOpenCharacter: ((Long) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val views = DialogBirthdayCelebrationBinding.inflate(LayoutInflater.from(context))
        binding = views

        pages = decodePages(arguments?.getString(ARG_PAGES))
        currentPage = savedInstanceState?.getInt(STATE_PAGE) ?: 0

        views.celebrationSummary.text = when {
            pages.size == 1 -> getString(R.string.birthday_celebration_single, pages[0].name)
            else -> getString(R.string.birthday_celebration_multi, pages.size)
        }

        val adapter = PageAdapter()
        views.celebrationPager.adapter = adapter
        views.celebrationPager.registerOnPageChangeCallback(pageCallback)

        buildDots(views)
        views.celebrationIndicatorRow.visibility =
            if (pages.size > 1) View.VISIBLE else View.GONE
        renderIndicator(currentPage)
        if (currentPage in pages.indices) {
            views.celebrationPager.setCurrentItem(currentPage, false)
        }

        views.btnCelebrationClose.setOnClickListener { dismiss() }
        views.btnCelebrationOpen.setOnClickListener {
            val page = pages.getOrNull(currentPage)
            dismiss()
            page?.let { onOpenCharacter?.invoke(it.characterId) }
        }

        return MaterialAlertDialogBuilder(context)
            .setView(views.root)
            .create()
            .also {
                // 축하 창이라 바깥을 눌러도 닫힌다 — 읽고 넘어가는 창이지 답할 것이 없다.
                it.setCanceledOnTouchOutside(true)
            }
    }

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            currentPage = position
            renderIndicator(position)
        }
    }

    /** 점을 미리 만들어 두고 색만 바꾼다 — 넘길 때마다 뷰를 다시 짓지 않는다. */
    private fun buildDots(views: DialogBirthdayCelebrationBinding) {
        val context = views.root.context
        views.celebrationDots.removeAllViews()
        if (pages.size <= 1) return
        val density = context.resources.displayMetrics.density
        val size = (7 * density).toInt()
        val gap = (4 * density).toInt()
        repeat(pages.size) { index ->
            val dot = View(context)
            dot.setBackgroundResource(R.drawable.bg_birthday_dot)
            val params = LinearLayout.LayoutParams(size, size)
            if (index > 0) params.marginStart = gap
            views.celebrationDots.addView(dot, params)
        }
    }

    private fun renderIndicator(position: Int) {
        val views = binding ?: return
        if (pages.size <= 1) return
        views.celebrationPageLabel.text =
            getString(R.string.birthday_celebration_page, position + 1, pages.size)
        val context = views.root.context
        val on = ContextCompat.getColor(context, R.color.primary)
        val off = ContextCompat.getColor(context, R.color.outline)
        for (i in 0 until views.celebrationDots.childCount) {
            views.celebrationDots.getChildAt(i).backgroundTintList =
                ColorStateList.valueOf(if (i == position) on else off)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
    }

    override fun onDestroyView() {
        binding?.celebrationPager?.unregisterOnPageChangeCallback(pageCallback)
        binding?.celebrationPager?.adapter = null
        binding = null
        super.onDestroyView()
    }

    // ── 쪽 ──

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(
                ItemBirthdayCelebrationPageBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

        override fun onBindViewHolder(holder: PageHolder, position: Int) =
            holder.bind(pages[position])

        override fun onViewRecycled(holder: PageHolder) {
            super.onViewRecycled(holder)
            holder.cancelLoad()
        }
    }

    private inner class PageHolder(
        private val views: ItemBirthdayCelebrationPageBinding
    ) : RecyclerView.ViewHolder(views.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun bind(page: BirthdayCelebration.Page) {
            val context = views.root.context
            views.celebrationName.text = page.name
            views.celebrationSubtitle.text = subtitleOf(page)

            // 대사가 없으면 상자가 통째로 사라진다 — 빈 인용 상자를 그려 두지 않는다.
            if (page.quoteText.isNullOrBlank()) {
                views.celebrationQuoteBox.visibility = View.GONE
            } else {
                views.celebrationQuoteBox.visibility = View.VISIBLE
                views.celebrationQuoteText.text =
                    context.getString(R.string.quote_wrapped, page.quoteText)
                views.celebrationQuoteNote.text = page.quoteNote.orEmpty()
                views.celebrationQuoteNote.visibility =
                    if (page.quoteNote.isNullOrBlank()) View.GONE else View.VISIBLE
            }

            loadImage(page)
        }

        /** `8월 20일` · 나이 · 작품 — **있는 조각만** 잇는다(없는 자리를 빈칸으로 두지 않는다). */
        private fun subtitleOf(page: BirthdayCelebration.Page): String {
            val context = views.root.context
            return listOfNotNull(
                context.getString(R.string.birthday_celebration_date, page.month, page.day),
                page.novelTitle?.takeIf { it.isNotBlank() }
            ).joinToString(context.getString(R.string.birthday_celebration_separator))
        }

        private fun loadImage(page: BirthdayCelebration.Page) {
            loadJob?.cancel()
            views.celebrationImage.setImageResource(R.drawable.ic_character_placeholder)
            val path = page.imagePath ?: return
            val appDir = views.root.context.filesDir
            loadJob = viewLifecycleOwnerOrThis().lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    // 공용 유틸 위임 — filesDir 경로 가드 + 총 픽셀 상한(파노라마 OOM 방지).
                    CharacterImageLoader.decodeThumbnail(path, appDir, 256)
                }
                if (bitmap != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    views.celebrationImage.setImageBitmap(bitmap)
                }
            }
        }
    }

    /**
     * `onCreateDialog`로 만든 창은 `viewLifecycleOwner`가 서지 않는다 — 그 자리에서는
     * 프래그먼트 자신의 수명이 그림 읽기의 주기다(창이 사라지면 함께 멈춘다).
     */
    private fun viewLifecycleOwnerOrThis() = this

    companion object {
        const val TAG = "BirthdayCelebrationDialog"
        private const val STATE_PAGE = "current_page"

        private const val ARG_PAGES = "pages"

        private val GSON = Gson()
        private val PAGES_TYPE = object : TypeToken<List<BirthdayCelebration.Page>>() {}.type

        /**
         * 쪽을 **한 칸의 글자로** 실어 보낸다 — 순수 계층([BirthdayCelebration.Page])이
         * `Bundle`을 모르게 두려는 것이고(그래야 시험이 돈다), 앱이 이미 쓰는 코덱이라
         * 새 형식을 만들지 않는다(R-65의 그 규약).
         */
        fun newInstance(pages: List<BirthdayCelebration.Page>): BirthdayCelebrationDialog =
            BirthdayCelebrationDialog().apply {
                arguments = Bundle().apply { putString(ARG_PAGES, GSON.toJson(pages)) }
            }

        /** 손상된 인자는 **빈 목록**이다 — 축하 창 하나 때문에 앱이 죽지 않는다. */
        private fun decodePages(json: String?): List<BirthdayCelebration.Page> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching { GSON.fromJson<List<BirthdayCelebration.Page>>(json, PAGES_TYPE) }
                .getOrNull().orEmpty()
        }
    }
}
