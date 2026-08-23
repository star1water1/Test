package com.novelcharacter.app.ui.character

import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.util.ImagePathMatch
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 캐릭터 이미지 스트립(80dp 가로 썸네일 목록) 공용 컨트롤러 — CharacterEditFragment에서 추출.
 * 이미지 경로 목록 소유, 썸네일 어댑터(탭=뷰어, 롱프레스=삭제), 갤러리 픽 가져오기(importUris)를 담당한다.
 * 캐릭터 편집 화면과 보충탭 인라인 편집이 공유하며, 이미지 뷰어 내비게이션 출발지는
 * [navOriginDestId]로 화면별 주입한다.
 *
 * 사용 계약:
 * - 호스트의 onViewCreated에서 [attach], onDestroyView에서 [detach]를 호출한다.
 * - ActivityResult 런처는 프래그먼트 초기화 시점 제약 때문에 호스트가 소유하고 [importUris]로 전달한다.
 * - 추가/삭제 시 [onChanged]가 호출된다(더티 플래그 훅). 삭제 시에는 [onRemoved]도 뒤이어 호출된다.
 */
class CharacterImageStripController(
    private val fragment: Fragment,
    private val recyclerViewGetter: () -> RecyclerView?,
    private val navOriginDestId: Int,
    private val onChanged: () -> Unit,
    private val onRemoved: () -> Unit = {}
) {

    private val gson = Gson()
    private val imagePaths = mutableListOf<String>()
    private var imageAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    /**
     * 폼이 들고 있는 대표 이미지 지정(B-103 D7). 빈 문자열 = 지정 없음.
     *
     * **저장 시점에 반영된다** — 편집창의 이미지 조작은 취소할 수 있으므로(취소 계약)
     * 여기서 곧바로 DB를 고치지 않는다. 호스트가 저장할 때 `FormSnapshot`에 실어 넘긴다.
     */
    var representativePath: String = ""
        private set

    /**
     * "앱에서 삭제"를 고른 경로들(B-107 D7) — **저장할 때 실행된다.**
     *
     * 즉시 지우지 않는 이유가 이 화면의 계약이다: 편집창의 모든 변경(이름·필드·이미지 추가)은
     * 저장해야 반영되고 취소하면 무해하다. 여기만 예외로 두면 **취소가 듣지 않는 조작**이
     * 하나 생긴다 — 어리둥절함보다 나쁘다(설계 6장 적대 검토).
     *
     * 저장 시 `CharacterSaveCoordinator`가 가져가고, 그 삭제도 `ImageOwnershipGuard`를 거쳐
     * 다른 캐릭터·작품·세계관·휴지통이 쓰는 파일은 지우지 못한다(조용한 실패 금지).
     */
    private val pendingDeletePaths = mutableListOf<String>()

    /** 저장 코디네이터가 가져간다. 목록에 다시 들어온 경로는 자동으로 빠진다(마음이 바뀐 것). */
    val pendingDeletes: List<String>
        get() = pendingDeletePaths.filterNot { com.novelcharacter.app.util.ImagePathMatch.containedIn(imagePaths, it) }

    /** 저장이 끝났거나 편집을 버렸을 때 — 대기 목록을 비운다. */
    fun clearPendingDeletes() = pendingDeletePaths.clear()

    /**
     * 화면 상태 복원이 "지우기로 했다"는 사실을 되살린다 (B-170).
     *
     * 이 목록은 이 컨트롤러의 메모리 필드뿐이라, 회전·화면 이동으로 뷰가 다시 서면
     * **선택만 조용히 사라져** 캐릭터에서는 빠졌는데 파일은 앱에 남는 고아가 생겼다 —
     * *뺐다는 사실*(imagePaths)은 Bundle·드래프트에 실려 살아남는데 *지우기로 했다는
     * 사실*만 죽는 비대칭이 결함의 핵심이었다. 호스트가 Bundle·드래프트에 실어 두 값을
     * 같은 수명으로 만든다.
     *
     * 거른 원문을 담는다 — [pendingDeletes] 게터가 다시 붙은 경로를 매번 거르므로
     * 여기서 거르면 두 벌이 된다. 내부 저장소 검증은 호스트가 [validateInternalPaths]로
     * 이미 했다(imagePaths와 같은 규칙).
     */
    fun restorePendingDeletes(paths: List<String>) {
        pendingDeletePaths.clear()
        pendingDeletePaths.addAll(paths)
    }

    /** 현재 이미지 경로 목록 (읽기 전용 뷰) */
    val paths: List<String> get() = imagePaths

    /** 드래프트·회전 복원과 기존 캐릭터 로드가 대표 지정을 되살릴 때 쓴다. */
    fun setRepresentativePath(path: String?) {
        representativePath = com.novelcharacter.app.util.CharacterRepresentativeImage
            .retain(path, imagePaths)
        imageAdapter?.notifyDataSetChanged()
    }

    private val appDir: File? get() = fragment.context?.filesDir

    companion object {
        /**
         * 내부 저장소 경로만 수용하는 검증 — 회전 복원·드래프트 복원이 같은 규칙을 공유한다.
         * 판정은 [ImagePathMatch.isInside]가 든다 (B-106 ⓐ · R-39) — `appDir`이 null이거나
         * 정규화가 실패하면 **막는다**(그 함수가 두 경우를 모두 false로 접는다).
         */
        fun validateInternalPaths(paths: List<String>, appDir: File?): List<String> =
            paths.filter { ImagePathMatch.isInside(it, appDir) }
    }

    /** 호스트 onViewCreated에서 호출 — 리사이클러뷰 레이아웃 매니저 설정 */
    fun attach() {
        val rv = recyclerViewGetter() ?: return
        rv.layoutManager =
            LinearLayoutManager(fragment.requireContext(), LinearLayoutManager.HORIZONTAL, false)
    }

    /** 호스트 onDestroyView에서 호출 — 어댑터 참조 해제 */
    fun detach() {
        recyclerViewGetter()?.adapter = null
        imageAdapter = null
    }

    /** 경로 목록 전체 교체(프로그램적 적재 — 더티 훅을 부르지 않음) + 화면 갱신 */
    fun setPaths(newPaths: List<String>) {
        imagePaths.clear()
        imagePaths.addAll(newPaths)
        refresh()
    }

    /** 경로 추가(추천 첨부 등) — 미저장 변경이므로 더티 훅 호출 */
    fun addPaths(newPaths: List<String>) {
        if (newPaths.isEmpty()) return
        imagePaths.addAll(newPaths)
        refresh()
        // 첨부는 미저장 변경 — 없으면 뒤로가기가 확인 없이 이탈하고 드래프트도 안 남는다(무음 유실)
        onChanged()
    }

    /**
     * 픽한 이미지들을 내부 저장소에 저장한다. 공용 ImageImportHelper로 라우팅하여
     * 압축 설정(용량↔화질)을 적용한다. 압축 설정은 배치당 1회만 로드한다.
     */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = fragment.context?.applicationContext ?: return
        val app = ctx as? com.novelcharacter.app.NovelCharacterApp ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val settings = com.novelcharacter.app.util.ImageSettingsStore(ctx).getSettings()
            var anyFailed = false
            // **폼이 받지 못한 파일** — 이미 디스크에 있는데 가리키는 곳이 없는 몫이다.
            //
            // 종전에는 `recyclerViewGetter() == null`이면 그 자리에서 `return@launch`라
            // ⓐ 방금 쓴 파일이 **고아**로 남고 ⓑ 남은 uri는 **말없이 버려졌다.** 회전 한 번이면
            // 닿는다(화면 수명 스코프라 코루틴 자체가 끊긴다). 여러 장을 고른 사용자는
            // *일부만 붙은* 결과를 받고 그 사실을 어디서도 듣지 못했다.
            val stranded = mutableListOf<String>()
            // **시도를 마친 장수** — `uris.size`와의 차가 곧 *손도 못 댄 몫*이다.
            //
            // 위 문단이 *"끝까지 가져온다"*라고 적어 놓았지만 실제로는 못 갔다: 루프의 유일한
            // 중단점(`importImage`)이 취소를 되던지므로 회전이 들어오면 **남은 uri는 시도조차
            // 되지 않은 채** 루프를 빠져나갔고, `stranded`에도 안 들어가 어디에도 세어지지
            // 않았다. 열 장을 고른 사용자가 두 장만 받고 **나머지 여덟이 왜 없는지 들을 곳이
            // 없었다**(개발 의도 2번 — 말없이 유실되지 않는다).
            var processed = 0
            try {
                for (uri in uris) {
                    val filePath = try {
                        com.novelcharacter.app.util.ImageImportHelper.importImage(ctx, uri, "char", settings)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // 취소는 실패가 아니다 — 삼키면 아래 `anyFailed` 고지가 거짓이 된다.
                        // 이 장은 `processed`에 들지 않는다 — 시도가 끝나지 않았다.
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    // 성공이든 실패든 **이 장의 시도는 끝났다** — `continue`보다 앞에 둔다.
                    processed++
                    if (filePath == null) {
                        anyFailed = true
                        continue
                    }
                    // 폼이 없어도 **끝까지 가져온다** — 사용자가 고른 것을 버리지 않는다.
                    if (recyclerViewGetter() == null) {
                        stranded.add(filePath)
                        continue
                    }
                    imagePaths.add(filePath)
                    refresh()
                    onChanged()
                }
            } finally {
                // **지우지 않고 라이브러리에 담는다**(개발 의도 2번 — 사용자가 고른 것이다).
                // 편집창의 제거 정책(`EditorRemovePolicy`)을 따르지 않는 것은 성질이 달라서다:
                // 그쪽은 *붙어 있던 것을 뗀* 조작이라 사용자가 처분을 정했고, 이쪽은
                // **붙은 적이 없는** 새 파일이다 — 지우면 방금 고른 것이 통째로 사라진다.
                // `adoptOrphans`는 참조·보류·meta·드래프트가 보호하는 경로를 건너뛰므로
                // 정상적으로 붙은 것은 건드리지 않는다.
                val notAttempted = uris.size - processed
                if (stranded.isNotEmpty() || notAttempted > 0) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        val adopted = if (stranded.isEmpty()) 0 else runCatching {
                            com.novelcharacter.app.util.ImageOwnershipGuard
                                .adoptOrphans(app.database, ctx, stranded)
                        }.getOrDefault(0)
                        // **세 몫을 따로 말한다.** 종전에는 `adopted > 0`만 말했는데,
                        // `adoptOrphans`가 예외 하나에 0을 돌려주므로(`getOrDefault(0)`)
                        // **디스크에 쓴 파일이 주인도 meta도 없이 남은 실패**가 정확히
                        // *담을 것이 없었다*와 같은 침묵으로 나갔다.
                        val lines = buildList {
                            if (adopted > 0) {
                                add(ctx.getString(R.string.image_import_stranded_adopted, adopted))
                            }
                            val notAdopted = stranded.size - adopted
                            if (notAdopted > 0) {
                                add(ctx.getString(R.string.image_import_stranded_failed, notAdopted))
                            }
                            if (notAttempted > 0) {
                                add(ctx.getString(R.string.image_import_interrupted, notAttempted))
                            }
                        }
                        if (lines.isNotEmpty()) {
                            // 화면이 사라져도 고지는 간다 — 앱 컨텍스트로, 이 블록 안에서.
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, lines.joinToString("\n"), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            if (anyFailed && fragment.isAdded) {
                val c = fragment.context ?: return@launch
                Toast.makeText(c, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 썸네일 목록 갱신 (최초 호출 시 어댑터 생성) */
    fun refresh() {
        val rv = recyclerViewGetter() ?: return
        if (imageAdapter == null) {
            imageAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val d = parent.context.resources.displayMetrics.density
                    val sizePx = (80 * d).toInt()
                    // 썸네일 위에 ☆를 얹으려면 겹칠 자리가 있어야 한다(B-103 D7).
                    // 탭(=뷰어)·롱프레스(=삭제)는 썸네일이 그대로 가져간다 — 기존 제스처를 뺏지 않는다.
                    val container = android.widget.FrameLayout(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(sizePx, sizePx).apply {
                            marginEnd = (4 * d).toInt()
                        }
                    }
                    val imageView = ImageView(parent.context).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        id = R.id.strip_thumbnail
                    }
                    val starPx = (28 * d).toInt()
                    val star = ImageView(parent.context).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(starPx, starPx).apply {
                            gravity = android.view.Gravity.TOP or android.view.Gravity.END
                            topMargin = (2 * d).toInt()
                            marginEnd = (2 * d).toInt()
                        }
                        setBackgroundResource(R.drawable.bg_image_badge)
                        val pad = (5 * d).toInt()
                        setPadding(pad, pad, pad, pad)
                        id = R.id.strip_representative_star
                    }
                    container.addView(imageView)
                    container.addView(star)
                    return object : RecyclerView.ViewHolder(container) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val imageView = holder.itemView.findViewById<ImageView>(R.id.strip_thumbnail)
                    bindRepresentativeStar(holder)
                    // 이전 로드 작업 취소 + 이미지 초기화
                    (imageView.getTag(R.id.image_load_job) as? kotlinx.coroutines.Job)?.cancel()
                    imageView.setTag(R.id.image_load_job, null)
                    imageView.setImageResource(R.drawable.ic_character_placeholder)
                    if (position < imagePaths.size) {
                        val path = imagePaths[position]
                        val boundPosition = position
                        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
                            val targetSize = (80 * holder.itemView.context.resources.displayMetrics.density).toInt()
                            val bitmap = withContext(Dispatchers.IO) {
                                decodeSampledBitmap(path, targetSize)
                            }
                            if (bitmap != null && holder.bindingAdapterPosition == boundPosition && fragment.isAdded) {
                                imageView.setImageBitmap(bitmap)
                            }
                        }
                        imageView.setTag(R.id.image_load_job, job)
                    }
                    // 탭 → 이미지 뷰어에서 확대
                    imageView.setOnClickListener {
                        if (!fragment.isAdded) return@setOnClickListener
                        val adapterPosition = holder.bindingAdapterPosition
                        if (adapterPosition >= 0 && adapterPosition < imagePaths.size) {
                            val bundle = Bundle().apply {
                                putString("imagePaths", gson.toJson(imagePaths))
                                putInt("startPosition", adapterPosition)
                            }
                            fragment.findNavController().navigateSafe(navOriginDestId, R.id.imageViewerFragment, bundle)
                        }
                    }
                    // 롱프레스 → 삭제
                    imageView.setOnLongClickListener {
                        val adapterPosition = holder.bindingAdapterPosition
                        if (adapterPosition >= 0 && adapterPosition < imagePaths.size) {
                            // 사전 고지(D6ⓐ) — 대표를 빼는 것이면 결과를 먼저 말한다.
                            // 확인창 하나가 두 줄을 함께 말한다(무엇을 빼는지 + 그것이 대표라는 것).
                            val removingRepresentative = com.novelcharacter.app.util.ImagePathMatch
                                .same(imagePaths[adapterPosition], representativePath)
                            val message = if (removingRepresentative) {
                                fragment.getString(R.string.image_remove_choice_desc) + "\n\n" +
                                    fragment.getString(R.string.representative_image_delete_warning_self)
                            } else {
                                fragment.getString(R.string.image_remove_choice_desc)
                            }
                            // 2지 선택(B-107 D7) — "캐릭터에서만 빼기"와 "앱에서 삭제"를
                            // **한 창**에서 고른다. 창을 둘로 띄우면 조작 마찰이고, 대표 고지는
                            // 같은 창이 함께 말해야 한다(B-103 ㄷ1과 한 창 — 설계 8장 3번).
                            //
                            // **고른 즉시 지우지 않는다.** 편집창의 제거는 저장해야 반영되고
                            // 취소하면 무해하다는 것이 이 화면의 계약이다 — 즉시 지우면
                            // 편집을 취소해도 파일이 사라져 계약이 깨진다. 저장할 때
                            // `CharacterSaveCoordinator`가 `pendingDeletePaths`를 처리한다.
                            val removeOne: (Boolean) -> Unit = { alsoDeleteFile ->
                                val currentPos = holder.bindingAdapterPosition
                                if (currentPos >= 0 && currentPos < imagePaths.size) {
                                    val removedPath = imagePaths[currentPos]
                                    imagePaths.removeAt(currentPos)
                                    if (alsoDeleteFile) pendingDeletePaths.add(removedPath)
                                    // 목록에서 빠졌으면 포인터도 함께 풀린다(D5).
                                    representativePath = com.novelcharacter.app.util
                                        .CharacterRepresentativeImage.retain(representativePath, imagePaths)
                                    imageAdapter?.notifyItemRemoved(currentPos)
                                    imageAdapter?.notifyItemRangeChanged(currentPos, imagePaths.size - currentPos)
                                    onChanged()
                                    onRemoved()
                                }
                            }
                            MaterialAlertDialogBuilder(fragment.requireContext())
                                .setTitle(R.string.image_remove_choice_title)
                                .setMessage(message)
                                .setPositiveButton(R.string.image_remove_choice_detach) { _, _ -> removeOne(false) }
                                .setNegativeButton(R.string.image_remove_choice_delete) { _, _ -> removeOne(true) }
                                .setNeutralButton(R.string.cancel, null)
                                .show()
                        }
                        true
                    }
                }

                override fun getItemCount() = imagePaths.size
            }
            rv.adapter = imageAdapter
        } else {
            imageAdapter?.notifyDataSetChanged()
        }
    }

    /**
     * 썸네일 코너의 ☆ (B-103 D7) — 탭 한 번으로 지정/해제.
     *
     * 목록에서 어느 것이 대표인지 **보이게** 두는 것이 절반이다(원칙 04 — 일일이 확인하지
     * 않으면 존재를 알 수 없는 데이터가 있어서는 안 된다).
     */
    private fun bindRepresentativeStar(holder: RecyclerView.ViewHolder) {
        val star = holder.itemView.findViewById<ImageView>(R.id.strip_representative_star) ?: return
        val position = holder.bindingAdapterPosition
        val path = imagePaths.getOrNull(position)
        if (path == null) {
            star.visibility = android.view.View.GONE
            return
        }
        val pinned = com.novelcharacter.app.util.ImagePathMatch.same(path, representativePath)
        star.visibility = android.view.View.VISIBLE
        star.setImageResource(if (pinned) R.drawable.ic_star else R.drawable.ic_star_outline)
        star.contentDescription = fragment.getString(
            if (pinned) R.string.representative_image_clear else R.string.representative_image_set
        )
        star.setOnClickListener {
            val current = holder.bindingAdapterPosition
            val target = imagePaths.getOrNull(current) ?: return@setOnClickListener
            representativePath = if (
                com.novelcharacter.app.util.ImagePathMatch.same(target, representativePath)
            ) "" else target
            // 다른 항목의 ☆도 함께 꺼져야 하므로 전량 갱신한다(대표는 하나뿐이다).
            imageAdapter?.notifyDataSetChanged()
            onChanged()
        }
    }

    private fun decodeSampledBitmap(path: String, reqPx: Int): android.graphics.Bitmap? {
        // 공용 유틸 위임 — filesDir 경로 가드 + 총 픽셀 상한(파노라마 OOM 방지, P2-6). 정상 이미지 화질 보존.
        val dir = appDir ?: return null
        return com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, dir, reqPx)
    }
}
