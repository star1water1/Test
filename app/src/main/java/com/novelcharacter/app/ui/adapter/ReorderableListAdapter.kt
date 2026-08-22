package com.novelcharacter.app.ui.adapter

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 드래그로 순서를 바꾸는 목록 어댑터의 공통 뼈대 — 세계관·작품·캐릭터가 함께 쓴다.
 *
 * **왜 한 곳에 모았는가.** 셋이 같은 코드를 따로 들고 있었고, 셋 다 같은 자리에서 깨졌다.
 * 순서 편집 모드에서는 [getItemCount]가 [reorderList]로 얼어붙는데 `submitList`는 그대로
 * 통과했다 — 편집 중에 목록이 밖에서 다시 방출되면(항목 추가·삭제·이름 변경·엑셀 들이기)
 * `ListAdapter`의 비동기 diff가 `notifyItemInserted`/`Removed`를 내는데 **개수는 얼어 있어**
 * RecyclerView가 *Inconsistency detected*로 죽거나, 죽지 않으면 목록이 굳은 채 남았다.
 * 화면마다 따로 고치면 셋이 또 갈린다(R-18).
 *
 * **처분은 보류-후-반영이다.** 편집 모드가 켜져 있는 동안 항목 집합의 주인은 [reorderList]다.
 * 밖에서 온 목록은 **버리지 않고 최신 것 하나를 들고 있다가**(원칙: 조용한 유실 금지) 모드가
 * 끝나는 순간 한 번에 푼다. 그때 diff가 정상적으로 돌아 추가·삭제가 그대로 화면에 든다.
 *
 * **순서 저장은 값이 아니라 차례로 나간다.** [getReorderedIds]가 id만 돌려주고 저장 계층이
 * 그 차례로 `displayOrder`만 쓴다. 종전에는 스냅샷 **엔티티 전체**를 `@Update`로 되썼는데,
 * 편집 중에 다른 화면에서 바뀐 이름·이미지·핀이 그 되쓰기에 **말없이 되돌아갔다**
 * (개발 의도 2번 '변수 제어' — 사용자의 데이터가 조용히 유실되지 않는다).
 */
abstract class ReorderableListAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    diffCallback: DiffUtil.ItemCallback<T>
) : ListAdapter<T, VH>(diffCallback) {

    private var reorderMode = false
    private val reorderList = mutableListOf<T>()

    private var hasPending = false
    private var pendingList: List<T>? = null
    private var pendingCallback: Runnable? = null

    var itemTouchHelper: ItemTouchHelper? = null

    /** 드래그 한 번이 끝날 때마다 화면에 보이는 차례 그대로의 id 목록을 받는다. */
    var onOrderChanged: ((List<Long>) -> Unit)? = null

    /** 항목의 안정 식별자 — 순서 쓰기가 가리키는 행이다. */
    protected abstract fun idOf(item: T): Long

    fun isReorderMode() = reorderMode

    fun setReorderMode(enabled: Boolean) {
        if (reorderMode == enabled) return
        reorderMode = enabled
        if (enabled) {
            reorderList.clear()
            reorderList.addAll(currentList)
            notifyDataSetChanged()
        } else {
            reorderList.clear()
            // 항목의 출처가 reorderList → currentList로 돌아온다. 위치가 통째로 갈릴 수 있으므로
            // 먼저 뷰 상태를 currentList에 맞춘 뒤에 보류분을 푼다 — 순서를 뒤집으면 diff의
            // 알림이 아직 얼어 있는 뷰 상태 위에 떨어진다.
            notifyDataSetChanged()
            flushPending()
        }
    }

    final override fun submitList(list: List<T>?) = submitList(list, null)

    final override fun submitList(list: List<T>?, commitCallback: Runnable?) {
        if (reorderMode) {
            // 최신 것 하나만 든다 — `AsyncListDiffer`도 밀려난 제출의 콜백은 부르지 않는다.
            hasPending = true
            pendingList = list
            pendingCallback = commitCallback
            return
        }
        super.submitList(list, commitCallback)
    }

    private fun flushPending() {
        if (!hasPending) return
        val list = pendingList
        val callback = pendingCallback
        hasPending = false
        pendingList = null
        pendingCallback = null
        super.submitList(list, callback)
    }

    /**
     * 모드 진입 **직전에** 이미 떠 있던 비동기 diff가 뒤늦게 내려앉는 갈래 — 위 게이트가
     * 막지 못하는 유일한 통로다(제출은 이미 끝나 있었다). 스냅샷과 길이가 갈리면 그 순간
     * [getItemCount]와 RecyclerView의 뷰 상태가 어긋나므로, 스냅샷을 새 목록으로 다시 잡고
     * 통짜로 다시 그린다. 이 창은 메뉴를 누른 그 몇 ms이라 되돌릴 드래그가 아직 없다.
     */
    override fun onCurrentListChanged(previousList: List<T>, currentList: List<T>) {
        super.onCurrentListChanged(previousList, currentList)
        if (!reorderMode) return
        reorderList.clear()
        reorderList.addAll(currentList)
        notifyDataSetChanged()
    }

    fun onItemMove(from: Int, to: Int) {
        if (!reorderMode) return
        if (from < 0 || to < 0 || from >= reorderList.size || to >= reorderList.size) return
        reorderList.add(to, reorderList.removeAt(from))
        notifyItemMoved(from, to)
    }

    /** 드래그가 끝난 자리에서 부른다 — 순서 편집 모드가 아니면 아무 일도 하지 않는다. */
    fun onDragCompleted() {
        if (!reorderMode) return
        onOrderChanged?.invoke(getReorderedIds())
    }

    /** 화면에 보이는 차례 그대로의 id — 저장 계층이 이 차례로 `displayOrder`를 찍는다. */
    fun getReorderedIds(): List<Long> = reorderList.map { idOf(it) }

    /** 편집 중이면 스냅샷에서, 아니면 확정 목록에서 항목을 꺼낸다. */
    protected fun itemAt(position: Int): T =
        if (reorderMode && position < reorderList.size) reorderList[position] else getItem(position)

    override fun getItemCount(): Int = if (reorderMode) reorderList.size else super.getItemCount()
}
