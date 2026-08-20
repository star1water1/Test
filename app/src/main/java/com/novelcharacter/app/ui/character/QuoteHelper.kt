package com.novelcharacter.app.ui.character

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterQuote
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.databinding.DialogQuoteEditBinding
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.ui.adapter.CharacterQuoteAdapter
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError

/**
 * 상세 화면의 **명대사 구역** (사용자 요청 2026.08.20).
 *
 * [StateChangeHelper]와 같은 자리이고 같은 모양이다 — 화면이 1,200줄을 넘겨 구역마다
 * 도우미로 갈라 두었다.
 *
 * ## 정렬 메뉴가 없는 것이 상태 변화와 갈리는 자리다
 * 상태 변화는 저장 순서가 없어(DAO가 연·월·일 고정) 보기 정렬을 열 수 있었는데, 명대사는
 * **사용자가 끌어서 정한 차례 자체가 데이터**다. 여기에 보기 정렬을 얹으면 *"맨 위 것"*이
 * 화면마다 달라지고, 그러면 생일 모달·대결 카드가 집는 대사와 목록의 맨 위가 어긋난다.
 */
class QuoteHelper(
    private val binding: FragmentCharacterDetailBinding,
    private val viewModel: CharacterViewModel,
    private val viewLifecycleOwner: LifecycleOwner,
    private val characterId: Long,
    private val contextGetter: () -> Context,
    private val getString: (Int) -> String
) {
    private lateinit var adapter: CharacterQuoteAdapter
    private var touchHelper: ItemTouchHelper? = null

    fun setup() {
        adapter = CharacterQuoteAdapter(
            onClick = { quote -> showEditDeleteDialog(quote) },
            onDragStart = { holder -> touchHelper?.startDrag(holder) }
        )
        binding.quotesRecyclerView.layoutManager = LinearLayoutManager(contextGetter())
        binding.quotesRecyclerView.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.beginDrag()
                adapter.moveRow(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            /** 손을 뗄 때 **한 번** 저장한다 — 끄는 동안 쓰면 한 칸마다 트랜잭션이 돈다. */
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.endDrag()?.let { viewModel.updateQuoteOrders(it) }
            }

            override fun isLongPressDragEnabled(): Boolean = false
        }).also { it.attachToRecyclerView(binding.quotesRecyclerView) }

        binding.btnAddQuote.setOnClickListener { showQuoteDialog(null) }
    }

    fun observe() {
        viewModel.getQuotesByCharacter(characterId).observe(viewLifecycleOwner) { quotes ->
            adapter.submitList(quotes)

            val birthdayCount = quotes.count { it.isBirthday }
            binding.quotesCount.text = when {
                quotes.isEmpty() -> ""
                birthdayCount > 0 ->
                    binding.root.context.getString(
                        R.string.quote_count_with_birthday, quotes.size, birthdayCount
                    )
                else -> binding.root.context.getString(R.string.quote_count_label, quotes.size)
            }
            binding.quotesCount.visibility = if (quotes.isEmpty()) View.GONE else View.VISIBLE

            binding.textNoQuotes.visibility = if (quotes.isEmpty()) View.VISIBLE else View.GONE
            binding.quotesRecyclerView.visibility = if (quotes.isEmpty()) View.GONE else View.VISIBLE
            // 순서 안내는 **둘 이상일 때만** 뜬다 — 하나뿐이면 바꿀 순서가 없다(R-24).
            binding.quoteReorderHint.visibility = if (quotes.size > 1) View.VISIBLE else View.GONE
        }
    }

    fun teardown() {
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
        binding.quotesRecyclerView.adapter = null
    }

    // ── 편집 창 ──

    private fun showQuoteDialog(existing: CharacterQuote?) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        val dialogBinding = DialogQuoteEditBinding.inflate(LayoutInflater.from(context))

        /** 칩 셋과 자유 칸을 잇는다 — '직접 적기'일 때만 칸이 보인다(R-24). */
        fun syncCustomField() {
            dialogBinding.layoutQuoteOccasion.visibility =
                if (dialogBinding.chipOccasionCustom.isChecked) View.VISIBLE else View.GONE
        }

        if (existing != null) {
            dialogBinding.editQuoteText.setText(existing.text)
            dialogBinding.editQuoteNote.setText(existing.note)
            when {
                existing.isBirthday -> dialogBinding.chipOccasionBirthday.isChecked = true
                existing.occasionKey.isNotBlank() -> {
                    dialogBinding.chipOccasionCustom.isChecked = true
                    dialogBinding.editQuoteOccasion.setText(existing.occasionKey)
                }
                else -> dialogBinding.chipOccasionGeneral.isChecked = true
            }
        } else {
            dialogBinding.chipOccasionGeneral.isChecked = true
        }
        syncCustomField()
        dialogBinding.quoteOccasionChips.setOnCheckedStateChangeListener { _, _ -> syncCustomField() }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(
                if (existing == null) getString(R.string.quote_dialog_add)
                else getString(R.string.quote_dialog_edit)
            )
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        // 검증에 걸려도 창을 닫지 않는다 — 닫으면 적던 대사가 통째로 사라진다(R-27).
        dialog.setValidatedPositiveButton {
            val text = dialogBinding.editQuoteText.text.toString().trim()
            if (text.isEmpty()) {
                dialogBinding.layoutQuoteText.showInlineError(getString(R.string.quote_text_required))
                return@setValidatedPositiveButton false
            }

            val occasion = when {
                dialogBinding.chipOccasionBirthday.isChecked -> CharacterQuote.KEY_BIRTHDAY
                dialogBinding.chipOccasionCustom.isChecked ->
                    dialogBinding.editQuoteOccasion.text.toString().trim()
                        // 직접 적기를 골라 놓고 비워 두면 **일반**이다 — 빈 상황을 지어내
                        // 배지에 공백을 그리지 않는다.
                        .takeIf { it.isNotBlank() } ?: CharacterQuote.KEY_GENERAL
                else -> CharacterQuote.KEY_GENERAL
            }

            val quote = CharacterQuote(
                id = existing?.id ?: 0,
                characterId = characterId,
                text = text,
                occasionKey = occasion,
                note = dialogBinding.editQuoteNote.text.toString().trim(),
                // 새 대사의 차례는 저장소가 정한다(맨 끝). 편집은 있던 차례를 지킨다.
                sortOrder = existing?.sortOrder ?: 0,
                // 편집은 정체성을 보존한다(R-1) — 명시하지 않으면 기본값이 code를 재발급해
                // 엑셀 재가져오기가 같은 대사를 새 행으로 읽는다. 구버전 무코드 행은 1회 부여.
                code = existing?.code ?: generateEntityCode(),
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )

            if (existing != null) viewModel.updateQuote(quote) else viewModel.insertQuote(quote)
            true
        }
        dialog.show()
    }

    private fun showEditDeleteDialog(quote: CharacterQuote) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.edit_or_delete))
            .setMessage(context.getString(R.string.quote_wrapped, quote.text))
            .setPositiveButton(R.string.edit) { _, _ -> showQuoteDialog(quote) }
            .setNegativeButton(R.string.delete) { _, _ -> confirmDelete(quote) }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    /**
     * 지우기 전에 한 번 더 묻는다 — **휴지통에 안 담기기 때문이다**(R-4: 파괴적 동작은
     * 사전 고지 + 취소). 관계·관계 변화와 같은 처분이고, 그 사실을 창이 글로 적는다.
     */
    private fun confirmDelete(quote: CharacterQuote) {
        val context = try { contextGetter() } catch (_: Exception) { return }
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.quote_delete_title))
            .setMessage(getString(R.string.quote_delete_confirm))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteQuote(quote) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
