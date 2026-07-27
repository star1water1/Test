package com.novelcharacter.app.share

import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionRelationship

/**
 * 월드패키지의 세력 간 관계 직렬화 (B-6).
 *
 * 세력 참조는 **code가 정하고 이름은 보조 해석 입력**이다(R-1, 엑셀의 [FactionRefResolver]와
 * 같은 규약). DB id는 기기 간에 의미가 없으므로 싣지 않는다 — 실어 두면 미래의 임포터가
 * id 폴백을 쓰고 싶어지고, 그것이 정확히 R-1이 막는 오배정 경로다.
 *
 * 순수 로직 — 순수 JVM 하네스(tools/run_jvm_tests.sh)가 실행 검증한다.
 */
data class PortableFactionRelationship(
    val factionCode1: String,
    val factionName1: String,
    val factionCode2: String,
    val factionName2: String,
    val relationType: String,
    val description: String,
    val intensity: Int,
    val isBidirectional: Boolean,
    val displayOrder: Int
)

object WorldPackageFactionRelationships {

    /**
     * @property items 패키지에 실을 관계 (양쪽 세력이 모두 내보내는 집합 안에 있는 것)
     * @property droppedCount 한쪽만 내보내는 집합에 걸친 관계 수 — 패키지에 실을 수 없으므로
     *   버리되, 호출부가 반드시 사용자에게 개수를 고지해야 한다(변수 제어: 무통보 유실 금지).
     *   양쪽 다 집합 밖인 관계는 이 패키지의 범위 밖(다른 세계관)이므로 세지 않는다.
     */
    data class Result(
        val items: List<PortableFactionRelationship>,
        val droppedCount: Int
    )

    fun toPortable(
        exportedFactions: List<Faction>,
        relationships: List<FactionRelationship>
    ): Result {
        val byId = exportedFactions.associateBy { it.id }
        val items = mutableListOf<PortableFactionRelationship>()
        var dropped = 0
        for (rel in relationships) {
            val f1 = byId[rel.factionId1]
            val f2 = byId[rel.factionId2]
            when {
                f1 != null && f2 != null -> items.add(
                    PortableFactionRelationship(
                        factionCode1 = f1.code,
                        factionName1 = f1.name,
                        factionCode2 = f2.code,
                        factionName2 = f2.name,
                        relationType = rel.relationType,
                        description = rel.description,
                        intensity = rel.intensity,
                        isBidirectional = rel.isBidirectional,
                        displayOrder = rel.displayOrder
                    )
                )
                f1 != null || f2 != null -> dropped++
                // 둘 다 밖 → 다른 세계관의 관계, 이 패키지의 범위 밖
            }
        }
        // 결정적 순서 — 같은 데이터는 항상 같은 파일을 만든다 (진단·비교 가능성)
        items.sortWith(
            compareBy({ it.displayOrder }, { it.factionCode1 }, { it.factionCode2 }, { it.relationType })
        )
        return Result(items, dropped)
    }
}
