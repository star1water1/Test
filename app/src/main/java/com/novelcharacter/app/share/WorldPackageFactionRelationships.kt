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
    val displayOrder: Int,
    /**
     * 관계 자신의 안정 식별자(v58, 2026.08.25) — **nullable이라 옛 패키지도 그대로 읽힌다**
     * (없으면 가져오기가 새로 발급한다. 형제인 `CharacterRelationship`은 엔티티를 통째로
     * 실어 이 칸을 이미 갖고 있었고, 이 표만 DTO에 빠져 있었다).
     *
     * 없으면 패키지를 한 번 건널 때마다 코드가 새로 발급되어, 엑셀 '세력 관계' 시트의
     * 코드 매칭이 기기를 옮기는 순간 끊긴다 — 그것이 이 칸을 더한 이유다.
     */
    val code: String? = null
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
                        displayOrder = rel.displayOrder,
                        code = rel.code
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

    /**
     * @property relationships 해석에 성공해 삽입할 관계 (factionId는 **이 기기의 새 id**)
     * @property unresolvedCount 양끝 세력 중 하나라도 해석하지 못한 관계 수 —
     *   호출부가 반드시 고지할 것(무통보 유실 금지).
     */
    data class FromPortableResult(
        val relationships: List<FactionRelationship>,
        val unresolvedCount: Int
    )

    /**
     * 가져오기 방향 매퍼 (S-5) — [toPortable]의 역.
     *
     * **code가 정하고 이름은 보조 해석 입력**이다(R-1): code 조회가 실패했을 때만 이름을
     * 보되, 그 이름이 패키지 안에서 유일할 때만 쓴다(중복 이름으로의 해석은 오배정이다).
     *
     * @param idByCode 패키지 세력의 **원 code** → 이 기기에 삽입된 새 id.
     *   (가져오며 code가 재발급됐어도 키는 원 code여야 한다 — 관계 파일은 원 code를 갖고 있다)
     * @param idByUniqueName 패키지 안에서 유일한 세력 이름 → 새 id. 중복 이름은 싣지 말 것.
     */
    fun fromPortable(
        items: List<PortableFactionRelationship>,
        idByCode: Map<String, Long>,
        idByUniqueName: Map<String, Long>
    ): FromPortableResult {
        val resolved = mutableListOf<FactionRelationship>()
        var unresolved = 0
        for (item in items) {
            val id1 = idByCode[item.factionCode1] ?: idByUniqueName[item.factionName1]
            val id2 = idByCode[item.factionCode2] ?: idByUniqueName[item.factionName2]
            if (id1 == null || id2 == null) {
                unresolved++
                continue
            }
            resolved.add(
                FactionRelationship(
                    factionId1 = id1,
                    factionId2 = id2,
                    relationType = item.relationType,
                    description = item.description,
                    intensity = item.intensity,
                    isBidirectional = item.isBidirectional,
                    displayOrder = item.displayOrder,
                    // 옛 패키지(code 없음)는 엔티티 기본값이 새로 발급한다. 겹치는 코드의
                    // 재발급은 부르는 쪽이 등록부로 처리한다(`WorldPackageImporter`).
                    code = item.code?.takeIf { it.isNotBlank() }
                        ?: com.novelcharacter.app.data.model.generateEntityCode()
                )
            )
        }
        return FromPortableResult(resolved, unresolved)
    }
}
