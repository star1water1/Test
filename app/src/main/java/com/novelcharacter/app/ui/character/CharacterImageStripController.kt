package com.novelcharacter.app.ui.character

import android.net.Uri
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

    /** 현재 이미지 경로 목록 (읽기 전용 뷰) */
    val paths: List<String> get() = imagePaths

    private val appDir: File? get() = fragment.context?.filesDir

    companion object {
        /** 내부 저장소 경로만 수용하는 검증 — 회전 복원·드래프트 복원이 같은 규칙을 공유한다 */
        fun validateInternalPaths(paths: List<String>, appDir: File?): List<String> {
            if (appDir == null) return emptyList()
            return paths.filter { path ->
                try {
                    File(path).canonicalPath.startsWith(appDir.canonicalPath + File.separator)
                } catch (_: Exception) {
                    false
                }
            }
        }
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
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val settings = com.novelcharacter.app.util.ImageSettingsStore(ctx).getSettings()
            var anyFailed = false
            for (uri in uris) {
                val filePath = try {
                    com.novelcharacter.app.util.ImageImportHelper.importImage(ctx, uri, "char", settings)
                } catch (e: Exception) {
                    null
                }
                if (recyclerViewGetter() == null) return@launch
                if (filePath != null) {
                    imagePaths.add(filePath)
                    refresh()
                    onChanged()
                } else {
                    anyFailed = true
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
                    val imageView = ImageView(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(sizePx, sizePx).apply {
                            marginEnd = (4 * d).toInt()
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    return object : RecyclerView.ViewHolder(imageView) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val imageView = holder.itemView as ImageView
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
                            AlertDialog.Builder(fragment.requireContext())
                                .setTitle(R.string.delete)
                                .setMessage(R.string.image_delete_confirm)
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    val currentPos = holder.bindingAdapterPosition
                                    if (currentPos >= 0 && currentPos < imagePaths.size) {
                                        imagePaths.removeAt(currentPos)
                                        imageAdapter?.notifyItemRemoved(currentPos)
                                        imageAdapter?.notifyItemRangeChanged(currentPos, imagePaths.size - currentPos)
                                        onChanged()
                                        onRemoved()
                                    }
                                }
                                .setNegativeButton(R.string.cancel, null)
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

    private fun decodeSampledBitmap(path: String, reqPx: Int): android.graphics.Bitmap? {
        // 공용 유틸 위임 — filesDir 경로 가드 + 총 픽셀 상한(파노라마 OOM 방지, P2-6). 정상 이미지 화질 보존.
        val dir = appDir ?: return null
        return com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, dir, reqPx)
    }
}
