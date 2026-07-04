package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.OperationLog
import com.novelcharacter.app.util.OpResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 데이터 처리 작업 이력 저장소.
 *
 * OpResult를 시간순으로 기록하고 상한을 유지한다. 이력 기록 실패가 원 작업을 방해하면 안 되므로
 * logAsync는 fire-and-forget이며 예외를 삼킨다(로깅은 부가 기능).
 */
class OperationLogRepository(db: AppDatabase) {

    private val dao = db.operationLogDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val recent: LiveData<List<OperationLog>> = dao.getRecent()

    /** OpResult를 이력에 비동기 기록(+상한 정리). 실패는 무시(부가 기능). */
    fun logAsync(result: OpResult) {
        scope.launch {
            try {
                dao.insert(
                    OperationLog(
                        category = result.category,
                        summary = result.summary,
                        detail = result.detail,
                        success = result.success
                    )
                )
                if (dao.getCount() > OperationLog.MAX_ENTRIES) {
                    dao.trimToMax(OperationLog.MAX_ENTRIES)
                }
            } catch (_: Exception) {
                // 이력 기록 실패는 원 작업에 영향 없음
            }
        }
    }

    suspend fun clear() = dao.clear()
}
