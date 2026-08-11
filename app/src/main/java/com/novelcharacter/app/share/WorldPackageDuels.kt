package com.novelcharacter.app.share

import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.util.DuelRecords

/**
 * 월드패키지의 **대결**(축 · 판 · 상성) 직렬화 (B-118 ⓑ — schemaVersion 6).
 *
 * ## 왜 있는가
 *
 * 축은 **세계관 단위**인데(사용자 확정 ㄷ1) 패키지가 그것을 담지 않아, 세계관을 통째로
 * 내보내 다른 기기에서 들이면 **그 세계관의 대결이 통째로 사라진 채 도착했다.** 백업·복원은
 * 엑셀이 이미 담으므로 온전했고, 빠지는 것은 **공유 경로** 하나였다 — 그런데 월드패키지의
 * 목적이 *작품을 통째로 옮기는 것*이라 그 하나가 반쪽을 만든다(원칙 05 · 원칙 02).
 *
 * ## 참가자를 무엇으로 잇는가 — 여기가 이 파일의 실질이다
 *
 * 판·상성은 참가자를 **코드**로 가리키는데([DuelMatch.aCode]), 그 코드는 가져오기가
 * **재발급할 수 있다**([WorldPackageCodes.Registry]). 즉 코드를 그대로 실어 보내면
 * 재발급된 순간 그 판이 **이 기기에 이미 있던 남의 캐릭터에 붙는다** — R-1이
 * *"오배정은 생략보다 나쁘다"*라고 못 박은 바로 그 경로다. 세력 간 관계
 * ([WorldPackageFactionRelationships])가 이름 폴백으로 같은 문제를 다루는 것과 달리
 * 이쪽은 **폴백이 없다**: 판은 사람이 읽는 이름을 갖고 있지 않고, 동명이인으로의 해석은
 * 그 자체가 오배정이다. 그래서 **해석 실패는 언제나 제외 + 계수**다.
 *
 * 참가자가 무엇인지는 축이 정한다([DuelAxis.targetType]):
 * - [DuelAxis.TARGET_CHARACTER] → `Character.code` — 재발급 표를 따라간다
 * - [DuelAxis.TARGET_IMAGE] → 이미지 경로 — **복원된 새 경로**를 따라간다(패키지의 경로는
 *   내보낸 기기의 것이다). 같은 기기 재가져오기로 원 경로가 그대로 재사용된 장은
 *   *제자리 대응*(old → old)으로 표에 담기므로 같은 조회 하나가 둘을 함께 처리한다.
 *
 * 순수 로직 — 순수 JVM 하네스(tools/run_jvm_tests.sh)가 실행 검증한다. 배선(어느 순서로
 * 넣는가)은 [WorldPackageImporter]에 있고, 그 순서가 왜 그래야 하는지는 아래 [fromPortable]
 * 주석에 적었다.
 */
object WorldPackageDuels {

    /**
     * @property droppedMatches 참가자가 이 패키지 밖이라 싣지 못한 판 수 — 0이 아니면
     *   호출부가 반드시 고지할 것(무통보 유실 금지 · 개발 의도 2번).
     * @property droppedVerdicts 같은 이유로 싣지 못한 상성 판정 수.
     */
    data class ToPortableResult(
        val axes: List<DuelAxis>,
        val matches: List<DuelMatch>,
        val verdicts: List<DuelCounterVerdict>,
        val droppedMatches: Int,
        val droppedVerdicts: Int
    )

    /**
     * 내보내기 방향 — 이 패키지에 실을 수 있는 것만 고른다.
     *
     * **판을 거르는 기준은 참가자의 소속이다.** 축은 세계관 단위라 전부 실리지만, 판은
     * 캐릭터를 가리키고 **내보내기는 작품 일부만 고를 수 있다**(`ExportConfig.novelIds`).
     * 그래서 이 세계관의 축에 달린 판이라도 참가자가 이번 패키지 밖일 수 있고, 그 판은
     * 받는 쪽에서 이을 것이 없다. 세력 간 관계의 *한쪽 걸침*과 같은 자리다.
     *
     * **양쪽 다 밖인 판도 센다** — 세력 간 관계에서 *양쪽 다 밖*을 세지 않는 것은 그것이
     * **다른 세계관의 관계**라 이 패키지의 범위 자체가 아니기 때문인데, 판은 이 세계관 축에
     * 달려 있어 사정이 다르다. 참가자가 지워진 판(고아)도 여기 걸리며, 그것도 남겨지는
     * 사용자 데이터이므로 말없이 넘기지 않는다.
     *
     * @param characterCodesInScope 이번 패키지에 실리는 캐릭터의 code 집합.
     * @param imagePathsInScope 이번 패키지에 실리는 캐릭터들의 이미지 경로 집합.
     *   이미지를 담지 않는 내보내기여도 **경로는 실린다**(캐릭터 행의 `imagePaths`가 실리므로) —
     *   받는 쪽에서 장을 못 찾으면 그때 그쪽이 세어 알린다. 여기서 미리 버리면 같은 기기
     *   재가져오기에서 멀쩡한 판을 잃는다.
     */
    fun toPortable(
        axes: List<DuelAxis>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict>,
        characterCodesInScope: Set<String>,
        imagePathsInScope: Set<String>
    ): ToPortableResult {
        val axisById = axes.associateBy { it.id }

        fun inScope(axis: DuelAxis, participant: String): Boolean =
            if (axis.isImageAxis) participant in imagePathsInScope else participant in characterCodesInScope

        val keptMatches = ArrayList<DuelMatch>()
        var droppedMatches = 0
        for (match in matches) {
            val axis = axisById[match.axisId]
            // 축이 이번 집합에 없으면 다른 세계관의 판이다 — 이 패키지의 범위 밖이라 세지 않는다.
            if (axis == null) continue
            if (inScope(axis, match.aCode) && inScope(axis, match.bCode)) keptMatches.add(match)
            else droppedMatches++
        }

        val keptVerdicts = ArrayList<DuelCounterVerdict>()
        var droppedVerdicts = 0
        for (verdict in verdicts) {
            val axis = axisById[verdict.axisId] ?: continue
            val members = DuelRecords.decodeMembers(verdict.memberCodes)
            // 처분은 관계 하나에 대한 판정이라 **일부만 실을 수 없다** — 셋 중 하나가 빠진
            // 순환은 순환이 아니다. 전원이 있을 때만 싣는다.
            if (members.isNotEmpty() && members.all { inScope(axis, it) }) keptVerdicts.add(verdict)
            else droppedVerdicts++
        }

        // 결정적 순서 — 같은 데이터는 항상 같은 파일을 만든다(진단·비교 가능성.
        // 세력 간 관계가 같은 이유로 정렬한다). 축은 화면 차례를, 나머지는 code를 따른다.
        return ToPortableResult(
            axes = axes.sortedWith(compareBy({ it.displayOrder }, { it.name }, { it.code })),
            matches = keptMatches.sortedWith(compareBy({ it.decidedAt }, { it.code })),
            verdicts = keptVerdicts.sortedWith(compareBy({ it.decidedAt }, { it.code })),
            droppedMatches = droppedMatches,
            droppedVerdicts = droppedVerdicts
        )
    }

    /**
     * @property axes 삽입할 축 — [demotedBasisAxes]만큼 기준 표식이 내려간 상태다.
     * @property demotedBasisAxes 기준 축이 둘 이상이라 표식을 내린 축 수. 손편집 패키지에서만
     *   생긴다 — 저장소는 하나만 켜지도록 지키지만([DuelAxis.isBasisAxis]) 패키지 파일은
     *   그 불변식 밖에서 만들어질 수 있다.
     */
    data class AxesResult(val axes: List<DuelAxis>, val demotedBasisAxes: Int)

    /**
     * 가져올 축을 삽입 전에 다듬는다 — **기준 축은 세계관당 하나뿐**이라는 불변식을 세운다.
     *
     * 둘이 켜진 채로 들어오면 대표 이미지 추첨이 어느 축을 따르는지 **사용자가 알 길이 없다**
     * ([DuelAxis.isBasisAxis]). 첫 축만 남기는 것은 순서가 곧 사용자가 보는 차례이기 때문이고,
     * **내린 수를 세는 것이 이 함수의 절반**이다(조용히 고치면 사용자는 자기가 켜 둔 것이
     * 왜 꺼졌는지 모른다).
     */
    fun normalizeImportedAxes(axes: List<DuelAxis>): AxesResult {
        var seenBasis = false
        var demoted = 0
        val normalized = axes.map { axis ->
            if (!axis.isBasisAxis) return@map axis
            if (!seenBasis) {
                seenBasis = true
                axis
            } else {
                demoted++
                axis.copy(isBasisAxis = false)
            }
        }
        return AxesResult(normalized, demoted)
    }

    /**
     * @property unresolvedMatches 참가자를 잇지 못해 제외한 판 수 — 재발급 표에 없는 코드,
     *   복원되지 않은 이미지 경로, 그리고 **두 참가자가 같은 행**(비교가 성립하지 않는다 —
     *   엑셀 가져오기가 같은 결함을 같은 방식으로 거른다)이 여기 든다.
     * @property unresolvedVerdicts 같은 이유로 제외한 상성 판정 수.
     * @property duplicateVerdicts 한 축 안에서 같은 관계가 두 번 등재돼 제외한 수.
     *   `(축, 정규 키)`는 유니크 인덱스라, 걸러내지 않으면 **가져오기 전체가 예외로 죽는다**
     *   (트랜잭션이 하나라 세계관 절반이 아니라 전부가 없어진다).
     */
    data class FromPortableResult(
        val matches: List<DuelMatch>,
        val verdicts: List<DuelCounterVerdict>,
        val unresolvedMatches: Int,
        val unresolvedVerdicts: Int,
        val duplicateVerdicts: Int
    )

    /**
     * 가져오기 방향 매퍼 — [toPortable]의 역.
     *
     * **배선 순서가 이 함수의 전제다.** 부르기 전에 셋이 확정돼 있어야 한다:
     * ① 축이 삽입돼 `old id → new id`가 있다 ② 캐릭터 코드가 claim돼 `old code → new code`가
     * 있다 ③ 이미지가 복사돼 `old path → new path`가 있다. 그래서 임포터는 축을 세계관 직후에
     * 넣고 판·상성은 캐릭터·이미지 뒤에 넣는다.
     *
     * **해석은 표에 있는 것만 인정한다(엄격).** *"표에 없으면 원문 그대로 둔다"*는 폴백을
     * 두지 않는 것이 요점이다 — 그렇게 두면 재발급으로 비게 된 코드가 **이 기기에 이미 있던
     * 동명 코드**를 가리켜 남의 캐릭터의 전적이 된다(R-1). 코드는 전역 네임스페이스라
     * *못 찾는 것*이 아니라 *정확히 남을 찾는 것*이 위험이다.
     *
     * @param axisIdByOldId 패키지 축의 원 id → 이 기기에 삽입된 새 id.
     * @param axisTargetTypeByOldId 패키지 축의 원 id → [DuelAxis.targetType]. 참가자를
     *   코드로 볼지 경로로 볼지가 여기서 갈린다.
     * @param characterCodeRemap 패키지 캐릭터의 **원 code** → 이 기기에서 확정된 code.
     *   재발급되지 않은 캐릭터도 **제자리 대응으로 반드시 담을 것** — 담기지 않은 코드는
     *   "패키지 밖"으로 판정된다(그것이 엄격 해석의 값이다).
     * @param imagePathRemap 패키지 이미지의 **원 경로** → 복원된 새 경로. 원 경로를 그대로
     *   재사용한 장은 제자리 대응으로 담는다.
     */
    fun fromPortable(
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict>,
        axisIdByOldId: Map<Long, Long>,
        axisTargetTypeByOldId: Map<Long, String>,
        characterCodeRemap: Map<String, String>,
        imagePathRemap: Map<String, String>
    ): FromPortableResult {
        fun remapOf(oldAxisId: Long): Map<String, String> =
            if (axisTargetTypeByOldId[oldAxisId] == DuelAxis.TARGET_IMAGE) imagePathRemap else characterCodeRemap

        val resolvedMatches = ArrayList<DuelMatch>()
        var unresolvedMatches = 0
        for (match in matches) {
            val newAxisId = axisIdByOldId[match.axisId]
            if (newAxisId == null) {
                unresolvedMatches++
                continue
            }
            val remap = remapOf(match.axisId)
            val a = remap[match.aCode]
            val b = remap[match.bCode]
            if (a == null || b == null || a == b) {
                unresolvedMatches++
                continue
            }
            resolvedMatches.add(
                match.copy(
                    id = 0,
                    axisId = newAxisId,
                    aCode = a,
                    bCode = b,
                    // 승자도 같은 표를 따른다. **표에 없으면 원문을 남긴다** — 이 칸의 미해석은
                    // 참가자의 미해석과 처분이 다르다: 비우면 무승부로 되읽혀 사용자가 고른
                    // 승패가 왕복 한 번에 사라지고(내보내기가 같은 이유로 코드를 적어 둔다),
                    // 남겨 두면 적합이 `malformedMatches`로 세어 알린다. 참가자 둘이 이미
                    // 해석됐으므로 이 값이 남의 캐릭터를 가리킬 자리는 없다.
                    winnerCode = match.winnerCode?.let { remap[it] ?: it }
                )
            )
        }

        val resolvedVerdicts = ArrayList<DuelCounterVerdict>()
        val seenKeys = HashSet<Pair<Long, String>>()
        var unresolvedVerdicts = 0
        var duplicateVerdicts = 0
        for (verdict in verdicts) {
            val newAxisId = axisIdByOldId[verdict.axisId]
            if (newAxisId == null) {
                unresolvedVerdicts++
                continue
            }
            val remap = remapOf(verdict.axisId)
            val members = DuelRecords.decodeMembers(verdict.memberCodes).map { remap[it] }
            if (members.isEmpty() || members.any { it == null }) {
                unresolvedVerdicts++
                continue
            }
            val mapped = members.map { it!! }
            // 관계의 모양은 **참가자 수가 정한다**([DuelRecords.shapeOf]) — 옮긴 뒤 다시 물어야
            // 저장된 shape가 참가자와 어긋난 손편집 파일이 그대로 들어오지 않는다.
            val shape = DuelRecords.shapeOf(mapped)
            if (shape == null) {
                unresolvedVerdicts++
                continue
            }
            // 정규 키는 **다시 낸다.** 코드가 재발급되면 옛 키는 옛 코드로 만들어진 문자열이라
            // 유니크 인덱스가 다른 관계로 읽고, 같은 관계가 두 번 등재된다.
            val memberKey = DuelRecords.memberKey(mapped)
            if (!seenKeys.add(newAxisId to memberKey)) {
                duplicateVerdicts++
                continue
            }
            resolvedVerdicts.add(
                verdict.copy(
                    id = 0,
                    axisId = newAxisId,
                    shape = shape,
                    memberCodes = DuelRecords.encodeMembers(mapped),
                    memberKey = memberKey
                )
            )
        }

        return FromPortableResult(
            matches = resolvedMatches,
            verdicts = resolvedVerdicts,
            unresolvedMatches = unresolvedMatches,
            unresolvedVerdicts = unresolvedVerdicts,
            duplicateVerdicts = duplicateVerdicts
        )
    }
}
