package com.novelcharacter.app.ui.timeline

import android.app.Dialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import com.novelcharacter.app.ui.character.InitialHydrationGuard
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.ui.field.FieldViewModel
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.EventFieldAiSuggester
import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.RequiredFieldMark
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.data.repository.EventFieldValueMerge
import com.novelcharacter.app.databinding.DialogTimelineEditBinding
import com.novelcharacter.app.ui.field.FieldEditDialog
import com.novelcharacter.app.util.FieldOptionParser
import com.novelcharacter.app.util.FieldValueFixRoute
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.isValidDay
import com.novelcharacter.app.util.reportAndNotify
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 사건(TimelineEvent) 편집 다이얼로그.
 *
 * AlertDialog 헬퍼(구 EventEditDialogHelper)와 달리 DialogFragment이므로
 * 회전/프로세스 킬 시에도 시스템이 다이얼로그를 재생성하고,
 * ID 있는 입력 뷰(연/월/일/역법/설명/유형)는 뷰 상태 자동 복원,
 * ID 없는 동적 체크박스 선택(캐릭터/작품)은 onSaveInstanceState로 복원한다.
 *
 * 데이터 접근은 호스트 프래그먼트가 [Host]로 제공한다 — 재생성 후에도
 * parentFragment를 통해 새 provider를 얻으므로 콜백 유실이 없다.
 */
class EventEditDialogFragment : DialogFragment() {

    enum class ShiftDirection { BEFORE, AFTER }

    interface DataProvider {
        suspend fun getAllNovelsList(): List<Novel>
        suspend fun getAllCharactersList(): List<Character>
        suspend fun getCharacterIdsForEvent(eventId: Long): List<Long>
        suspend fun getNovelIdsForEvent(eventId: Long): List<Long>
        /** 사건 폼이 그릴 필드. `universeId`가 null이면 **전역 구역**이다(B-258). */
        suspend fun getEventFieldsForUniverse(universeId: Long?): List<FieldDefinition>
        suspend fun getEventFieldValuesForEvent(eventId: Long): List<EventFieldValue>
        /**
         * 사건 편집 자리에서 만든 사건 필드를 심는다(P5). 실패는 예외로 올려 호출부가 알린다 —
         * 호스트에 따라 result 채널 관찰 여부가 달라(캐릭터 편집 화면은 관찰하지 않는다)
         * 고지를 호스트에 맡기면 한쪽에서 조용해진다.
         */
        suspend fun insertEventField(field: FieldDefinition): Long
        // 필드값은 값·커버 집합 한 벌([EventFieldValueMerge.Submission])로 전달한다(S-6) —
        // 값만 넘기면 "폼이 전체 진실"이 되어 폼이 렌더하지 못한 기존 값이 전량 삭제된다.
        fun insertEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, fieldSubmission: EventFieldValueMerge.Submission)
        fun updateEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, fieldSubmission: EventFieldValueMerge.Submission)
        suspend fun getEventsInScope(novelIds: List<Long>, universeId: Long?): List<TimelineEvent>
        fun updateEventAndShiftOthers(
            event: TimelineEvent,
            characterIds: List<Long>,
            novelIds: List<Long>,
            shiftDirection: ShiftDirection,
            delta: Int,
            originalNovelIds: List<Long>,
            originalUniverseId: Long?,
            fieldSubmission: EventFieldValueMerge.Submission
        )
    }

    /** 호스트 프래그먼트가 구현. 재생성 후 provider 재획득 경로. */
    interface Host {
        fun eventDialogDataProvider(): DataProvider
    }

    private var _binding: DialogTimelineEditBinding? = null
    private val binding get() = _binding!!

    private val gson = Gson()
    private var editingEvent: TimelineEvent? = null
    private val selectedCharIds = mutableSetOf<Long>()
    private val selectedNovelIds = mutableSetOf<Long>()
    private var selectionsInitialized = false
    private var novels: List<Novel> = emptyList()
    private var characters: List<Character> = emptyList()
    private var eventTypes: List<Pair<String, String>> = emptyList()

    // 사건 커스텀 필드 (B-10)
    private var eventFields: List<FieldDefinition> = emptyList()
    private val eventFieldInputMap = mutableMapOf<Long, Any>()  // fieldId -> EditText/Spinner
    private var pendingEventFieldValues: MutableMap<String, String>? = null

    /**
     * 폼의 커버 집합(S-6/R-5) — 필드 섹션 로딩이 **완료된 시점에 조회된 정의 전체**의 id.
     * 렌더에서 걸러지는 CALCULATED도 포함한다(계산 필드 정의를 가리키는 잔여 저장 행이
     * 저장 시 함께 정리되어 매번 반복되는 "보관했습니다" 거짓 고지를 막는다 — 캐릭터판과 동일).
     * 로딩 미완(초기·회전 직후·fetch 대기 중 재구성 전)에는 마지막으로 렌더된 상태가 곧
     * 화면의 진실이므로 그대로 둔다.
     *
     * **'세계관 미해결이면 공집합'이던 갈래는 없어졌다**(B-258 ⓐ). 무소속은 미해결이 아니라
     * 전역 구역이고, 그 구역의 정의가 곧 커버다 — 다른 구역(임자와 어긋난 정의)에 매달린 값은
     * 여전히 커버 밖이라 저장이 건드리지 않는다(전량 보존은 그대로다).
     */
    private var coveredEventFieldIds: Set<Long> = emptySet()

    /**
     * 필드 섹션이 해석한 **구역** — 무엇을 그리는가와 어디에 만드는가가 둘 다 여기서 나온다(P5).
     *
     * **`Long?` 하나로는 못 적는다**(B-258). 종전에는 이 자리가 `resolvedFieldUniverseId: Long?`
     * 였고 그 `null` 하나가 두 뜻을 겸했다 — *구역을 아직 못 정했다*와 *전역 구역이다*.
     * 겸직인 채로 전역을 그리기 시작하면 같은 검사가 갈라야 할 둘을 못 가른다:
     * 만드는 단추는 **전역이라서** 감추고([Global] — 근거는 아래), 조회는 **몰라서** 안 건다.
     * 그래서 뜻을 타입으로 올린다.
     */
    private var resolvedFieldScope: FieldScope? = null

    /**
     * 사건 필드가 사는 구역.
     *
     * [Global]에 **만드는 경로를 두지 않는 것이 규약이다**(B-129가 작품 축에 세운 그것).
     * 전역 구역의 필드는 기본 필드 템플릿의 그림자이고, 만들고 고치고 지우는 자리가
     * 설정 → 기본 필드 관리 하나다. 여기서 단추를 열면 **관리 화면이 없는 필드**가 생겨
     * 만든 사람도 다시 찾아가 고칠 수 없다(원칙 04).
     */
    private sealed interface FieldScope {
        /** 무소속 — 전역 기본 필드를 쓴다. */
        object Global : FieldScope
        /** 그 세계관의 사건 필드를 쓴다. 만드는 경로도 여기에만 있다. */
        data class Universe(val id: Long) : FieldScope
    }

    /** 만들기·추천·AI 근거가 묻는 것은 *세계관 id*다 — 전역 구역에는 그것이 없다. */
    private fun scopeUniverseId(): Long? = (resolvedFieldScope as? FieldScope.Universe)?.id

    // 역법 시드(R3): 신규 사건이면 스코프 세계관 최빈 역법을 시드하되, 편집·회전 복원·사용자 직접 입력은 존중.
    private var isRecreated = false
    /** 편집 사건의 초기값을 폼에 채운 적이 있는가 — 채우기 전 회전이면 재생성이라도 다시 채운다(재공격 F1) */
    private var initialValuesFilled = false
    private var calendarUserEdited = false
    private var suppressCalendarWatcher = false

    /**
     * 초기 적재가 **적재 전에 사용자가 적은 칸**을 덮지 못하게 막는다 (B-268 ⓑ).
     *
     * 이 창의 정적 칸들은 질의 넷을 지난 뒤에야 채워진다(`getAllNovelsList` ·
     * `getAllCharactersList` · `getCharacterIdsForEvent` · `getNovelIdsForEvent`).
     * 그 사이는 창이 이미 떠 있어 사용자가 적을 수 있고, 종전에는 [fillInitialValues]가
     * 조건 없이 덮었다 — 캐릭터 편집에서 난 것과 **같은 부류의 같은 결함**이다.
     *
     * 이 창은 이미 같은 생각을 역법 칸 하나에만 쓰고 있었다([calendarUserEdited] —
     * *"자동 시드가 사용자 입력을 덮어쓰지 않게"*). 그 판정을 나머지 칸으로 넓힌 것이
     * 이 판정기이고, 규칙은 [InitialHydrationGuard]가 든다.
     */
    private var hydrationGuard = InitialHydrationGuard()

    /**
     * 사건 유형 스피너에 **손가락이 닿았는가** (B-268) — *골랐다*를 알릴지 가르는 절반이다.
     * 나머지 절반은 뒤이어 오는 `onItemSelected`이고, **둘이 다 있어야** 사용자 선택이다.
     */
    private var eventTypeSpinnerTouched = false
    private var seedJob: kotlinx.coroutines.Job? = null
    private var fieldSectionJob: kotlinx.coroutines.Job? = null

    private fun requireProvider(): DataProvider =
        (parentFragment as? Host ?: activity as? Host)?.eventDialogDataProvider()
            ?: throw IllegalStateException(
                "EventEditDialogFragment host must implement EventEditDialogFragment.Host"
            )

    /**
     * AI 관측자는 **여기서** 단다 (B-43).
     *
     * `onCreateDialog`가 아닌 이유: 그쪽은 뷰가 다시 만들어질 때 함께 다시 도는데, 관측자를
     * 창 수명(`this`)에 달면 **같은 인스턴스에 둘이 쌓여 검토 창이 두 번 뜬다.** 유료 응답을
     * 두 번 보여 주는 것은 그 자체로 오해를 만든다(두 번 적용될 것처럼 보인다).
     * `onCreate`는 인스턴스당 한 번이라 그 자리가 없다.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeAiSuggest()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogTimelineEditBinding.inflate(layoutInflater)
        editingEvent = arguments?.getString(ARG_EVENT_JSON)?.let {
            gson.fromJson(it, TimelineEvent::class.java)
        }

        eventTypes = listOf(
            TimelineEvent.TYPE_NONE to getString(R.string.event_type_none),
            TimelineEvent.TYPE_BIRTH to getString(R.string.event_type_birth),
            TimelineEvent.TYPE_DEATH to getString(R.string.event_type_death)
        )
        val typeAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, eventTypes.map { it.second }
        )
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerEventType.adapter = typeAdapter

        // 다대다 작품 선택 — 구 단일 스피너는 사용 안 함
        binding.spinnerNovel.visibility = View.GONE

        // 적재 판정기는 창 수명 단위다 (B-268 ⓑ) — 워처를 달기 전에 세운다. 창이 다시 서면
        // 위젯도 워처도 새것이므로, 지난 창에서 만져진 칸 목록을 물려받으면 안 된다.
        hydrationGuard = InitialHydrationGuard()
        eventTypeSpinnerTouched = false

        // 역법 필드를 사용자가 직접 건드렸는지 추적 — 자동 시드가 사용자 입력을 덮어쓰지 않게(무단 확정 금지).
        binding.editCalendarType.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // **억제 중이면 판정기에도 알리지 않는다** (B-268 ⓑ). 역법 칸은 시드 경로가
                // 적재보다 **먼저** 프로그램적으로 쓰는 자리가 있어(`updateCalendarSeed`의
                // 스코프 없음 가지 — `setCalendarProgrammatically("")`), 그것을 사용자 편집으로
                // 적으면 뒤따르는 진짜 적재가 **스스로 막힌다.** `suppressCalendarWatcher`가
                // 이미 그 구별을 들고 있으므로 같은 조건을 쓴다.
                if (!suppressCalendarWatcher) {
                    calendarUserEdited = true
                    hydrationGuard.onFieldChanged(InitialHydrationGuard.KEY_CALENDAR)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 나머지 정적 칸도 같은 것을 집는다 (B-268 ⓑ) — 초기 적재가 질의 넷 뒤에 오므로
        // 그때까지 적은 것을 [fillInitialValues]가 덮으면 **말없이 사라진다.** 위의
        // 역법 추적이 한 칸에만 있던 것을 나머지로 넓힌 자리다.
        fun trackEarly(view: android.widget.EditText, key: String) {
            view.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    hydrationGuard.onFieldChanged(key)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        trackEarly(binding.editYear, InitialHydrationGuard.KEY_YEAR)
        trackEarly(binding.editMonth, InitialHydrationGuard.KEY_MONTH)
        trackEarly(binding.editDay, InitialHydrationGuard.KEY_DAY)
        trackEarly(binding.editDescription, InitialHydrationGuard.KEY_DESCRIPTION)
        // 사건 유형 스피너는 워처가 없어 조작 자리에서 알린다. **재료가 둘이라야 한다**:
        // `onItemSelected`만 보면 프로그램적 `setSelection`에도 떠서 못 가르고, 터치만
        // 보면 **드롭다운을 열었다 그냥 닫아도 '골랐다'가 된다.** 뒤쪽이 특히 나쁘다 —
        // 그러면 적재가 유형을 못 채워 '없음'에 머물고, 저장하면 **출생·사망 표식이
        // 날아간다**(나이 계산이 그것에 걸려 있다. 콜드 검토 2026.08.19).
        binding.spinnerEventType.setOnTouchListener { _, _ ->
            eventTypeSpinnerTouched = true
            false
        }
        binding.spinnerEventType.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    // 표식은 여기서 내린다 — 남겨 두면 뒤따르는 프로그램적 선택까지
                    // 사용자 선택 행세를 한다.
                    if (eventTypeSpinnerTouched) {
                        eventTypeSpinnerTouched = false
                        hydrationGuard.onFieldChanged(InitialHydrationGuard.KEY_EVENT_TYPE)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        // 체크 선택 복원 (뷰 ID가 없는 동적 체크박스는 자동 복원 대상이 아님)
        savedInstanceState?.getLongArray(STATE_CHAR_IDS)?.let {
            selectedCharIds.addAll(it.toList())
            selectedNovelIds.addAll(savedInstanceState.getLongArray(STATE_NOVEL_IDS)?.toList() ?: emptyList())
            selectionsInitialized = true
        }
        savedInstanceState?.getBundle(STATE_EVENT_FIELD_VALUES)?.let { bundle ->
            val restored = mutableMapOf<String, String>()
            for (key in bundle.keySet()) {
                bundle.getString(key)?.let { restored[key] = it }
            }
            pendingEventFieldValues = restored
        }
        isRecreated = savedInstanceState != null
        initialValuesFilled = savedInstanceState?.getBoolean(STATE_INITIAL_FILLED) ?: false

        val alertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (editingEvent == null) R.string.add_event else R.string.edit_event)
            .setView(binding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        alertDialog.setOnShowListener {
            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onSaveClicked()
            }
        }

        setupAddEventFieldPath()

        // 목록/선택 상태 비동기 로드. 정적 입력값은 재생성 시 뷰 상태로 자동 복원되므로
        // 초기값 채우기는 최초 생성(savedInstanceState == null)에만 수행한다.
        lifecycleScope.launch {
            val provider = requireProvider()
            novels = provider.getAllNovelsList()
            characters = provider.getAllCharactersList()
            if (!selectionsInitialized) {
                val event = editingEvent
                if (event != null) {
                    selectedCharIds.addAll(provider.getCharacterIdsForEvent(event.id))
                    selectedNovelIds.addAll(provider.getNovelIdsForEvent(event.id))
                } else {
                    selectedCharIds.addAll(arguments?.getLongArray(ARG_PRE_CHAR_IDS)?.toList() ?: emptyList())
                    selectedNovelIds.addAll(arguments?.getLongArray(ARG_PRE_NOVEL_IDS)?.toList() ?: emptyList())
                }
                selectionsInitialized = true
            }
            if (_binding == null) return@launch
            val filling = editingEvent
            if (filling == null) {
                // 새 사건은 덮을 값이 없다 — 국면만 닫는다 (B-268 ⓑ).
                hydrationGuard.endHydration()
            }
            filling?.let { event ->
                // 회전이 최초 채움보다 먼저 오면 뷰 상태 복원은 빈 화면을 되살릴 뿐이다 —
                // 채운 적이 없으면 재생성 인스턴스라도 다시 채운다(재공격 F1).
                if (!isRecreated || !initialValuesFilled) {
                    // 적재 창 — 이 안의 워처 신호는 프로그램적 쓰기다 (B-268 ⓑ).
                    // `fillInitialValues`에 suspend가 없어 창 안에서 사용자 입력이 유실될 틈이 없다.
                    hydrationGuard.beginHydration()
                    fillInitialValues(event)
                    hydrationGuard.endHydration()
                    initialValuesFilled = true
                } else {
                    hydrationGuard.endHydration()
                }
                // 기존 사건 필드값 → 폼 빌드 후 지연 적용. 로드 조건은 재생성 여부가 아니라
                // **보유 여부**다 — 값 fetch 전에 회전하면 pending이 비어 있고, 그대로 렌더하면
                // '공란 폼 + 전체 커버'가 되어 저장이 커버된 값 전량을 삭제한다(재공격 F1).
                if (pendingEventFieldValues == null) {
                    val values = provider.getEventFieldValuesForEvent(event.id)
                    if (values.isNotEmpty()) {
                        pendingEventFieldValues = values
                            .associate { it.fieldDefinitionId.toString() to it.value }
                            .toMutableMap()
                    }
                }
            }
            if (_binding == null) return@launch
            setupNovelCheckboxes()
            setupCharacterCheckboxes()
            rebuildEventFieldSection()
            maybeSeedCalendarType()   // 스코프(선택/필터 작품) 알면 최빈 역법 시드
        }

        return alertDialog
    }

    /**
     * 사건 필드를 **이 자리에서** 만드는 경로 (P5).
     *
     * 종전에는 사건 필드를 만들 길이 세계관 목록 → 카드의 필드 관리 버튼 → 종류 칩 전환 → FAB
     * 하나뿐이었다. 사건을 쓰다가 필드가 필요해지면 편집을 버리고 되돌아가야 했고, 그래서
     * 기능이 통계·연표까지 배선된 채로 쓰이지 않았다(사용자 회신: "알지만 조작이 불편했다").
     *
     * 여는 것은 필드 관리와 **같은 다이얼로그**다 — 새 편집 화면을 만들지 않는다. 이 다이얼로그가
     * 부모로 살아 있으므로 사건 입력값은 그대로 보존되고(러프 입력 → 정밀 조정의 이중 경로),
     * 정밀한 설정은 필드 관리에서 이어서 하면 된다.
     */
    private fun setupAddEventFieldPath() {
        childFragmentManager.setFragmentResultListener(
            FieldEditDialog.RESULT_KEY, this
        ) { _, bundle ->
            val json = bundle.getString(FieldEditDialog.RESULT_FIELD_JSON) ?: return@setFragmentResultListener
            val field = gson.fromJson(json, FieldDefinition::class.java) ?: return@setFragmentResultListener
            // 이 경로는 생성 전용이다(편집은 필드 관리에서 한다) — id가 붙어 오면 무시한다.
            if (field.id != 0L) return@setFragmentResultListener
            // 생성 창에서 미리 적어 둔 값(값 사전 등록)도 함께 온다 — 받지 않으면 사용자가 적은 것이
            // 조용히 사라진다(B-68. 필드 관리·작품 편집은 이미 받고 있었고 여기만 빠져 있었다).
            val initialValues = bundle.getString(FieldEditDialog.RESULT_INITIAL_VALUES).orEmpty()
            createEventField(field, initialValues)
        }
        binding.btnAddEventField.setOnClickListener {
            // 전역 구역에서는 이 단추가 아예 안 보인다(FieldScope KDoc) — 여기 검사는 그 뒤의 빗장이다.
            val universeId = scopeUniverseId() ?: return@setOnClickListener
            FieldEditDialog.newInstance(universeId, null, FieldDefinition.ENTITY_EVENT)
                .show(childFragmentManager, "EventFieldEditDialog")
        }
        binding.btnPickEventField.setOnClickListener { showRecommendedEventFields() }
        // `?` — 상시 노출 안내문 한 줄로는 모자란 설명을 담는다(H15. 가이드 9-2 승인 본문).
        binding.btnEventFieldHelp.setOnClickListener {
            com.novelcharacter.app.ui.common.HelpDialog.showHelp(
                requireContext(),
                com.novelcharacter.app.ui.common.HelpDialog.Topic.EVENT_FIELD
            )
        }
    }

    private fun createEventField(field: FieldDefinition, initialValues: String = "") {
        // 이 경로로 들어온 것은 사건 필드다 — 종류를 여기서 못박아 호출부마다 되풀이하지 않는다.
        val toInsert = field.copy(entityType = FieldDefinition.ENTITY_EVENT)
        lifecycleScope.launch {
            val result = try {
                val newId = requireProvider().insertEventField(toInsert)
                // 값 사전 등록은 **호스트가 아니라 여기서** 심는다. DataProvider 구현이 셋이라
                // (그중 하나는 완전 수식 이름이라 착수 grep에 안 걸린 전력이 있다 — 1-p장)
                // 호스트에 맡기면 한 곳이 빠져도 조용하다. 인터페이스의 고지 규약과 같은 취지다.
                val planted = plantInitialValues(newId, toInsert, initialValues)
                OpResult.success(
                    OpResult.CAT_FIELD,
                    getString(R.string.event_field_created, field.name),
                    planted
                )
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                Log.e("EventEditDialog", "Duplicate event field key: ${field.key}", e)
                OpResult.failure(
                    OpResult.CAT_FIELD,
                    getString(R.string.event_field_key_duplicate, field.key)
                )
            } catch (e: Exception) {
                Log.e("EventEditDialog", "Failed to insert event field", e)
                OpResult.failure(
                    OpResult.CAT_FIELD,
                    getString(R.string.event_field_create_failed),
                    e.message
                )
            }
            if (!isAdded) return@launch
            reportAndNotify(result)
            // 성공한 것만 폼에 반영한다. 입력 중인 값은 rebuild가 보존한다(pendingEventFieldValues).
            if (result.success) rebuildEventFieldSection()
        }
    }

    /**
     * 추천 사건 필드 고르기 — 빈 캔버스 앞의 "무엇을 만들지 모르겠다"를 없애는 자리(설계 D4 러프 경로).
     *
     * 후보는 [FieldViewModel.getFieldsFromAllSources]가 조립한다 — **기본 제공 추천 ∪ 다른 세계관
     * ∪ 프리셋**. 그 함수를 여기서 다시 짜지 않는 이유는 필드 관리의 '필드 가져오기'가 이미
     * 같은 합집합을 쓰기 때문이다(두 벌이 되면 갈린다). 그래서 다이얼로그가 `FieldViewModel`을
     * 직접 얻어 쓴다 — `AndroidViewModel`이라 호스트가 무엇이든 같은 결과가 나온다.
     *
     * 이미 있는 `key`는 **지우지 않고 비활성 + 사유**로 남긴다(조용히 빼면 "왜 없지"가 된다).
     */
    private fun showRecommendedEventFields() {
        val universeId = scopeUniverseId() ?: return
        val ctx = context ?: return
        lifecycleScope.launch {
            val fieldViewModel = ViewModelProvider(this@EventEditDialogFragment)[FieldViewModel::class.java]
            val sources = runCatching {
                fieldViewModel.getFieldsFromAllSources(universeId, FieldDefinition.ENTITY_EVENT)
            }.getOrNull().orEmpty()
            val existingKeys = runCatching {
                fieldViewModel.getCurrentFieldKeys(universeId, FieldDefinition.ENTITY_EVENT)
            }.getOrNull().orEmpty()
            if (!isAdded) return@launch
            // 합집합이 비면 만드는 길은 '사건 필드 추가'뿐이다 — 그 사실을 말한다.
            val candidates = sources.values.flatten().distinctBy { it.key }
            if (candidates.isEmpty()) {
                Toast.makeText(ctx, R.string.import_no_event_field_sources, Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = candidates.map { f ->
                val dup = if (f.key in existingKeys) " " + getString(R.string.event_field_pick_duplicate_suffix) else ""
                "${f.name} (${f.type})$dup"
            }.toTypedArray()
            val checked = BooleanArray(candidates.size) { false }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.event_field_pick_title)
                .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                    // 이미 있는 것은 고를 수 없다 — 고르면 중복 키로 실패할 뿐이다.
                    if (isChecked && candidates[which].key in existingKeys) {
                        checked[which] = false
                        (dialog as? AlertDialog)?.listView?.setItemChecked(which, false)
                    } else {
                        checked[which] = isChecked
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                // 자동 닫힘 버튼 안에서 조기 return을 하면 검증 실패에 창이 닫혀 고른 것이
                // 날아간다(R-27 — 검사 도구가 잡는 자리다). 실패하면 창을 유지한다.
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .also { dialog ->
                    dialog.setValidatedPositiveButton {
                        val picked = candidates.filterIndexed { i, _ -> checked[i] }
                        if (picked.isEmpty()) {
                            Toast.makeText(ctx, R.string.event_field_pick_none, Toast.LENGTH_SHORT).show()
                            false
                        } else {
                            createEventFields(picked, universeId)
                            true
                        }
                    }
                }
                .show()
        }
    }

    /**
     * 고른 추천을 심는다 — **필드 관리의 '필드 가져오기'와 같은 함수를 쓴다**([FieldViewModel.importFieldsNow]).
     *
     * 손으로 넣으면 세 가지를 빠뜨린다: 세계관 못박기(후보는 남의 세계관·프리셋에서 온다) ·
     * `displayOrder` 이어 붙이기 · **다른 세계관의 등급 체계 참조 강등**(R-30 — 그대로 두면
     * 남의 체계를 가리키는 유령 참조가 된다). 그 셋이 이미 그 함수 안에 있다.
     *
     * 고지는 여기서 한다 — 이 화면은 `FieldViewModel`의 result 채널을 관찰하지 않는다.
     */
    private fun createEventFields(fields: List<FieldDefinition>, universeId: Long) {
        lifecycleScope.launch {
            val fieldViewModel = ViewModelProvider(this@EventEditDialogFragment)[FieldViewModel::class.java]
            val result = try {
                val added = fieldViewModel.importFieldsNow(universeId, fields, FieldDefinition.ENTITY_EVENT)
                OpResult.success(OpResult.CAT_FIELD, getString(R.string.event_field_pick_added, added))
            } catch (e: Exception) {
                Log.e("EventEditDialog", "Failed to add recommended event fields", e)
                OpResult.failure(
                    OpResult.CAT_FIELD,
                    getString(R.string.event_field_create_failed),
                    e.message
                )
            }
            if (!isAdded) return@launch
            reportAndNotify(result)
            if (result.success) rebuildEventFieldSection()
        }
    }

    /**
     * 값 사전 등록분을 라이브러리에 심고, **못 심은 것이 있으면 그 사실을 돌려준다**(무음 유실 금지).
     * 해석·등재 규칙은 [com.novelcharacter.app.data.repository.FieldValueLibraryRepository]가
     * 단일 소스다 — 필드 관리·작품 편집도 같은 경로를 쓴다.
     */
    private suspend fun plantInitialValues(
        newId: Long,
        field: FieldDefinition,
        raw: String
    ): String? {
        if (newId <= 0L || raw.isBlank()) return null
        val app = activity?.application as? com.novelcharacter.app.NovelCharacterApp ?: return null
        val outcome = runCatching {
            app.fieldValueLibraryRepository.registerInitialValues(newId, field, raw)
        }.getOrNull() ?: return null
        if (outcome.failed <= 0) return null
        return getString(R.string.event_field_initial_values_partial, outcome.failed)
    }

    /**
     * 신규 사건이면 스코프 세계관의 기존 사건에서 **최빈 역법**을 시드하고 자동완성 후보를 채운다.
     * 편집·회전 복원·사용자 직접 입력이면 건드리지 않는다(무단 확정 금지). 스코프 없으면 공란(천개력 아님).
     */
    private fun maybeSeedCalendarType() {
        if (_binding == null) return
        // 필드 섹션(rebuildEventFieldSection)과 같은 세계관 해석 — 작품 연결이 끊긴 편집 사건도
        // 자동완성 후보를 받는다(한쪽만 폴백하면 같은 화면에서 한 기능만 세계관을 인지한다).
        val universeId = novels.firstOrNull { it.id in selectedNovelIds }?.universeId
            ?: editingEvent?.universeId
        // 값 시드는 신규 사건 & 회전 복원 아님 & 사용자 미편집일 때만. 자동완성 후보는 편집 시에도 채운다.
        val canSeed = !isRecreated && editingEvent == null && !calendarUserEdited
        if (universeId == null) {
            setCalendarSuggestions(emptyList())
            if (canSeed) setCalendarProgrammatically("")  // 스코프 없으면 공란(천개력 아님)
            return
        }
        // 작품을 빠르게 토글해도 이전 세계관 결과가 늦게 덮어쓰지 않게 이전 시드 취소 + await 후 재확인.
        seedJob?.cancel()
        val target = universeId
        seedJob = lifecycleScope.launch {
            val events = requireProvider().getEventsInScope(emptyList(), target)
            if (_binding == null) return@launch
            val current = novels.firstOrNull { it.id in selectedNovelIds }?.universeId
                ?: editingEvent?.universeId
            if (current != target) return@launch
            val ranked = events.map { it.calendarType }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key }
            setCalendarSuggestions(ranked)   // 편집·신규 모두 자동완성 제공
            if (!isRecreated && editingEvent == null && !calendarUserEdited) {
                setCalendarProgrammatically(ranked.firstOrNull() ?: "")
            }
        }
    }

    private fun setCalendarSuggestions(types: List<String>) {
        if (_binding == null) return
        binding.editCalendarType.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, types)
        )
    }

    /** 역법 필드를 프로그램적으로 설정(watcher 억제 → calendarUserEdited 오탐 방지). */
    private fun setCalendarProgrammatically(value: String) {
        if (_binding == null) return
        suppressCalendarWatcher = true
        binding.editCalendarType.setText(value)
        binding.editCalendarType.dismissDropDown()
        suppressCalendarWatcher = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)  // 다이얼로그 뷰 계층 상태(정적 입력) 저장
        outState.putBoolean(STATE_INITIAL_FILLED, initialValuesFilled)
        if (selectionsInitialized) {
            outState.putLongArray(STATE_CHAR_IDS, selectedCharIds.toLongArray())
            outState.putLongArray(STATE_NOVEL_IDS, selectedNovelIds.toLongArray())
        }
        // 사건 필드 입력 (동적 위젯은 ID가 없어 자동 복원 대상이 아님)
        if (eventFieldInputMap.isNotEmpty() || pendingEventFieldValues != null) {
            val bundle = Bundle()
            pendingEventFieldValues?.forEach { (k, v) -> bundle.putString(k, v) }
            for ((fieldId, widget) in eventFieldInputMap) {
                bundle.putString(fieldId.toString(), eventFieldWidgetValue(widget))
            }
            outState.putBundle(STATE_EVENT_FIELD_VALUES, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // 진행 창은 이 화면의 창이다 — 두고 가면 응답이 늦게 끝났을 때 붙일 화면이 없어
        // 새는 창이 된다(실행 자체는 VM이 들고 계속 간다. 그것이 이 구조의 요점이다).
        aiProgressDialog?.dismiss()
        aiProgressDialog = null
    }

    /**
     * 기존 사건의 값을 창에 얹는다 — **적재 전에 사용자가 만진 칸은 건드리지 않는다** (B-268 ⓑ).
     *
     * 판정은 칸 단위다. 연도만 적어 둔 사용자의 설명 칸은 DB 값으로 채우는 것이 맞고
     * 연도 칸은 아니다 — 통째로 건너뛰면 나머지가 빈 채로 남는 새 결함이 된다.
     */
    private fun fillInitialValues(event: TimelineEvent) {
        val g = hydrationGuard
        if (g.mayWrite(InitialHydrationGuard.KEY_YEAR)) binding.editYear.setText(event.year.toString())
        if (g.mayWrite(InitialHydrationGuard.KEY_MONTH)) binding.editMonth.setText(event.month?.toString() ?: "")
        if (g.mayWrite(InitialHydrationGuard.KEY_DAY)) binding.editDay.setText(event.day?.toString() ?: "")
        // 편집: 기존 값 설정은 '사용자 편집'이 아니므로 watcher 억제.
        if (g.mayWrite(InitialHydrationGuard.KEY_CALENDAR)) setCalendarProgrammatically(event.calendarType)
        if (g.mayWrite(InitialHydrationGuard.KEY_DESCRIPTION)) binding.editDescription.setText(event.description)
        if (g.mayWrite(InitialHydrationGuard.KEY_EVENT_TYPE)) {
            val typeIndex = eventTypes.indexOfFirst { (key, _) -> key == event.eventType }
            if (typeIndex >= 0) binding.spinnerEventType.setSelection(typeIndex)
        }
    }

    /** 선택된 작품 소속 캐릭터가 위로 오도록 정렬 */
    private fun filteredChars(): List<Character> {
        if (selectedNovelIds.isEmpty()) return characters
        return characters.sortedWith(compareBy<Character> {
            if (it.novelId in selectedNovelIds) 0 else 1
        }.thenBy { it.name })
    }

    private fun onSaveClicked() {
        if (!selectionsInitialized) return  // 목록 로딩 중
        val context = context ?: return

        val yearStr = binding.editYear.text.toString().trim()
        val description = binding.editDescription.text.toString().trim()

        if (yearStr.isEmpty() || description.isEmpty()) {
            Toast.makeText(context, R.string.enter_year_and_desc, Toast.LENGTH_SHORT).show()
            return
        }

        val year = yearStr.toIntOrNull()
        if (year == null) {
            Toast.makeText(context, R.string.enter_valid_year, Toast.LENGTH_SHORT).show()
            return
        }

        val monthStr = binding.editMonth.text.toString().trim()
        val dayStr = binding.editDay.text.toString().trim()
        val month = if (monthStr.isNotEmpty()) monthStr.toIntOrNull() else null
        val day = if (dayStr.isNotEmpty()) dayStr.toIntOrNull() else null

        if (month != null && (month < 1 || month > 12)) {
            Toast.makeText(context, R.string.month_valid_range, Toast.LENGTH_SHORT).show()
            return
        }
        if (day != null && !isValidDay(month, day)) {
            Toast.makeText(context, R.string.day_valid_range, Toast.LENGTH_SHORT).show()
            return
        }

        val calendarType = binding.editCalendarType.text.toString().trim()
        val selectedTypeIndex = binding.spinnerEventType.selectedItemPosition
        val selectedEventType = eventTypes.getOrNull(selectedTypeIndex)?.first ?: TimelineEvent.TYPE_NONE

        // 선택된 작품들에서 세계관 ID 결정 (첫 번째 작품 기준).
        // 작품이 없으면 기존 세계관을 유지한다(S-6) — 작품 연결이 끊긴 사건의 편집이
        // 세계관 소속·필드값 가시성까지 조용히 지우지 않게. 사용자가 이 다이얼로그에서
        // 지운 것은 작품 연결이지 세계관 소속이 아니다.
        val event = editingEvent
        val selectedNovels = novels.filter { it.id in selectedNovelIds }
        val universeId = selectedNovels.firstOrNull()?.universeId ?: event?.universeId
        val newEvent = TimelineEvent(
            id = event?.id ?: 0,
            year = year,
            month = month,
            day = day,
            calendarType = calendarType,
            description = description,
            eventType = selectedEventType,
            universeId = universeId,
            displayOrder = event?.displayOrder ?: 0,  // 편집 시 기존 표시 순서 보존
            isTemporary = false,
            // 편집은 정체성을 보존한다(R-1) — 명시하지 않으면 기본값이 code를 재발급해
            // 내보낸 엑셀·휴지통 참조가 전부 무효가 된다. 구버전 무코드 행은 여기서 1회 부여.
            code = event?.code ?: generateEntityCode(),
            createdAt = event?.createdAt ?: System.currentTimeMillis()
        )
        val novelIdsList = selectedNovelIds.toList()
        val provider = requireProvider()

        // 값과 커버 집합은 같은 프레임에 스냅샷한다(S-6) — 커버는 지금 화면에 렌더된 폼의 권한이다.
        val fieldSubmission = buildFieldSubmission()
        guardRestrictedEventValues(fieldSubmission.values) {
        if (event == null) {
            provider.insertEvent(newEvent, selectedCharIds.toList(), novelIdsList, fieldSubmission)
            // 가드 경유로 비동기 실행됨 — 상태 저장 후 도착해도 크래시하지 않게 (수정 경로와 동일)
            dismissAllowingStateLoss()
        } else {
            val delta = newEvent.year - event.year
            lifecycleScope.launch {
                // 기존 연결된 작품 IDs를 원본 scope로 사용
                val originalNovelIds = provider.getNovelIdsForEvent(event.id)
                val hasScope = originalNovelIds.isNotEmpty() || event.universeId != null
                if (delta != 0 && hasScope) {
                    showYearShiftDialog(
                        provider, newEvent, selectedCharIds.toList(), novelIdsList,
                        delta, event.year,
                        originalNovelIds = originalNovelIds,
                        originalUniverseId = event.universeId,
                        fieldSubmission = fieldSubmission
                    )
                } else {
                    provider.updateEvent(newEvent, selectedCharIds.toList(), novelIdsList, fieldSubmission)
                    dismissAllowingStateLoss()
                }
            }
        }
        }
    }

    /** restricted 사건 필드 검증 — 위반 없으면 즉시 진행, 위반 시 사유 + 교정 경로 (검토 A8) */
    private fun guardRestrictedEventValues(values: List<EventFieldValue>, onProceed: () -> Unit) {
        val app = activity?.application as? com.novelcharacter.app.NovelCharacterApp
        if (app == null) {
            onProceed()
            return
        }
        lifecycleScope.launch {
            val fieldsById = eventFields.associateBy { it.id }
            val violations = mutableListOf<Pair<FieldDefinition, List<String>>>()
            for (v in values) {
                val fd = fieldsById[v.fieldDefinitionId] ?: continue
                if (!com.novelcharacter.app.util.FieldValueTokenizer.supportsLibrary(fd)) continue
                if (!com.novelcharacter.app.data.model.FieldValueLibraryConfig.fromConfig(fd.config).isRestricted) continue
                val entries = app.fieldValueLibraryRepository.entriesForField(fd.id)
                val bad = com.novelcharacter.app.data.repository.FieldValueLibraryRepository
                    .validateRestricted(fd, v.value, entries)
                if (bad.isNotEmpty()) violations.add(fd to bad)
            }
            if (violations.isEmpty()) {
                onProceed()
                return@launch
            }
            val message = violations.joinToString("\n") { (fd, tokens) ->
                getString(R.string.field_library_restricted_violation_line, fd.name, tokens.joinToString(", "))
            } + "\n\n" + getString(R.string.field_library_restricted_violation_paths)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.field_library_restricted_violation_title)
                .setMessage(message)
                .setPositiveButton(R.string.field_library_restricted_add_and_save) { _, _ ->
                    lifecycleScope.launch {
                        for ((fd, tokens) in violations) {
                            tokens.forEach { app.fieldValueLibraryRepository.addEntry(fd.id, it) }
                        }
                        onProceed()
                    }
                }
                .setNegativeButton(R.string.field_library_restricted_edit_input, null)
                .show()
        }
    }

    /**
     * 연도 변경 시 같은 scope의 다른 사건들을 함께 이동할지 선택.
     * 순간적 결정 다이얼로그이므로 재생성 대상이 아니다 — 회전 시 사라져도
     * 본 다이얼로그의 입력은 보존되며 저장을 다시 누르면 재표시된다.
     */
    private suspend fun showYearShiftDialog(
        provider: DataProvider,
        newEvent: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long>,
        delta: Int,
        oldYear: Int,
        originalNovelIds: List<Long>,
        originalUniverseId: Long?,
        fieldSubmission: EventFieldValueMerge.Submission
    ) {
        val scopeEvents = provider.getEventsInScope(originalNovelIds, originalUniverseId)
            .filter { it.id != newEvent.id }
        val afterCount = scopeEvents.count { it.year >= oldYear }
        val beforeCount = scopeEvents.count { it.year <= oldYear }

        val direction = if (delta > 0) "${delta}년 뒤로" else "${-delta}년 앞으로"
        val items = mutableListOf(getString(R.string.shift_this_only))
        val actions = mutableListOf<() -> Unit>()
        actions.add {
            provider.updateEvent(newEvent, characterIds, novelIds, fieldSubmission)
            dismissAllowingStateLoss()
        }
        if (afterCount > 0) {
            items.add(getString(R.string.shift_after_events, afterCount, direction))
            actions.add {
                provider.updateEventAndShiftOthers(
                    newEvent, characterIds, novelIds, ShiftDirection.AFTER, delta,
                    originalNovelIds, originalUniverseId, fieldSubmission
                )
                dismissAllowingStateLoss()
            }
        }
        if (beforeCount > 0) {
            items.add(getString(R.string.shift_before_events, beforeCount, direction))
            actions.add {
                provider.updateEventAndShiftOthers(
                    newEvent, characterIds, novelIds, ShiftDirection.BEFORE, delta,
                    originalNovelIds, originalUniverseId, fieldSubmission
                )
                dismissAllowingStateLoss()
            }
        }

        withContext(Dispatchers.Main) {
            val ctx = context ?: return@withContext
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.shift_events_title)
                .setItems(items.toTypedArray()) { _, which ->
                    actions.getOrNull(which)?.invoke()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ── 사건 커스텀 필드 (B-10) ──

    /** 선택된 작품(없으면 편집 중인 사건)의 세계관 기준으로 사건 필드 입력 섹션 재구성. 입력 중이던 값은 보존. */
    private fun rebuildEventFieldSection() {
        if (_binding == null) return
        // 현재 입력값 보존
        if (eventFieldInputMap.isNotEmpty()) {
            val preserved = pendingEventFieldValues ?: mutableMapOf()
            for ((fieldId, widget) in eventFieldInputMap) {
                preserved[fieldId.toString()] = eventFieldWidgetValue(widget)
            }
            pendingEventFieldValues = preserved
        }

        // 작품 미선택/연결 소실이어도 편집 중인 사건의 세계관으로 폴백한다(S-6, 원칙 04) —
        // 작품이 끊긴 사건의 기존 필드값이 '일일이 확인하지 않으면 존재를 알 수 없는 데이터'가
        // 되지 않게 화면에 드러내 편집할 수 있어야 한다.
        // **무소속이면 전역 구역이다** — 종전에는 여기서 섹션을 통째로 감췄고, 그래서
        // 무소속 사건(작품 거르개 없이 간편 추가로 만든 사건)의 폼에는 필드 구역 자체가
        // 없었다(B-258). 무소속 캐릭터는 B-119 확장이, 무소속 작품은 B-129가 이미 그 구역을
        // 받고 있었고 사건만 못 받았다 — 원칙 01·05에 어긋나던 자리다.
        val universeId = novels.firstOrNull { it.id in selectedNovelIds }?.universeId
            ?: editingEvent?.universeId

        // 작품을 빠르게 토글해도 이전 세계관 fetch가 늦게 도착해 덮어쓰지 않게 이전 작업 취소 + await 후 재확인(P2-2).
        val target: FieldScope =
            if (universeId == null) FieldScope.Global else FieldScope.Universe(universeId)
        // **fetch 전에 대입한다.** 조회가 끝난 뒤에 넣으면 작품을 A→B로 바꾼 직후 조회가 도는
        // 동안 이 값이 A로 남고, 버튼은 계속 보이므로 그 사이에 누르면 **사용자가 고른 적 없는
        // 세계관 A에 필드가 생긴다.** null 검사만으로는 이 자리를 막지 못한다 —
        // 막아야 하는 것은 '모르는 상태'가 아니라 '낡은 상태'다.
        resolvedFieldScope = target
        fieldSectionJob?.cancel()
        fieldSectionJob = lifecycleScope.launch {
            val fields = requireProvider().getEventFieldsForUniverse(universeId)
            if (_binding == null) return@launch
            val current = novels.firstOrNull { it.id in selectedNovelIds }?.universeId
                ?: editingEvent?.universeId
            if (current != universeId) return@launch
            // 커버는 조회된 정의 전체(CALCULATED 포함), 렌더는 입력 가능한 것만 — 필드 주석 참조.
            coveredEventFieldIds = fields.mapTo(HashSet()) { it.id }
            eventFields = fields
                .filter { FieldType.fromName(it.type) != FieldType.CALCULATED }
                .sortedBy { it.displayOrder }
            buildEventFieldInputs()
        }
    }

    private fun buildEventFieldInputs() {
        val ctx = context ?: return
        eventFieldInputMap.clear()
        binding.eventFieldContainer.removeAllViews()

        // 구역을 아직 못 정한 상태(창이 막 서서 조회 전)에서는 섹션 자체가 없다 —
        // 아래 셋이 그 상태와 **전역 구역**을 가른다(종전에는 `Long?`의 null 하나가 둘을 겸했다).
        val scope = resolvedFieldScope
        val universeScope = scope is FieldScope.Universe
        val globalScope = scope is FieldScope.Global
        // **만드는 경로는 세계관 구역에만 있다**([FieldScope] KDoc — B-129가 작품 축에 세운 규약).
        binding.btnAddEventField.visibility = if (universeScope) View.VISIBLE else View.GONE
        // 추천은 빈 캔버스보다 값싼 출발점이라 **함께** 남긴다. 필드가 있을 때도 하나 더
        // 필요할 수 있다는 현행 판단(바로 위 주석)을 그대로 따른다. 전역 구역에서는 이것도
        // 함께 사라진다 — 심을 자리가 없는데 고르게 하면 고른 것이 갈 곳이 없다.
        binding.btnPickEventField.visibility = if (universeScope) View.VISIBLE else View.GONE
        // 도움말은 두 구역 모두에 남긴다 — 전역 구역에서는 **어디서 만드는가**를 말하는 자리가
        // 안내문과 이 단추뿐이다.
        binding.btnEventFieldHelp.visibility = if (scope != null) View.VISIBLE else View.GONE

        if (eventFields.isEmpty()) {
            binding.eventFieldContainer.visibility = View.GONE
            // 빈 상태에서도 머리글과 사유를 남긴다 — 섹션을 통째로 감추면 사건을 쓰는 자리에서
            // '사건 필드'라는 것의 존재를 알 길이 없다(B-31이 세운 규약과 같은 취지).
            binding.eventFieldSectionLabel.visibility = if (scope != null) View.VISIBLE else View.GONE
            binding.eventFieldEmptyHint.visibility = if (scope != null) View.VISIBLE else View.GONE
            binding.eventFieldEmptyHint.text = getString(
                if (globalScope) R.string.event_field_global_scope else R.string.event_field_empty_hint
            )
            consumeFieldFocus()
            return
        }
        binding.eventFieldContainer.visibility = View.VISIBLE
        binding.eventFieldSectionLabel.visibility = View.VISIBLE
        // 전역 구역에서는 필드가 있어도 안내를 남긴다 — 만드는 단추가 없으므로 **어디서
        // 만드는지를 말하는 자리가 여기뿐**이다(B-129와 같은 근거: 발견성).
        if (globalScope) {
            binding.eventFieldEmptyHint.visibility = View.VISIBLE
            binding.eventFieldEmptyHint.text = getString(R.string.event_field_global_scope)
        } else {
            binding.eventFieldEmptyHint.visibility = View.GONE
        }

        val density = resources.displayMetrics.density
        for (field in eventFields) {
            val saved = pendingEventFieldValues?.get(field.id.toString()) ?: ""
            when (FieldType.fromName(field.type)) {
                FieldType.SELECT, FieldType.GRADE -> {
                    val label = android.widget.TextView(ctx).apply {
                        text = RequiredFieldMark.label(field)
                        textSize = 13f
                    }
                    binding.eventFieldContainer.addView(label)
                    // (✨은 아래 위젯 줄에 함께 얹는다 — 라벨 줄에 붙이면 값과 떨어져 보인다)

                    val options = mutableListOf(getString(R.string.no_selection))
                    options.addAll(
                        if (FieldType.fromName(field.type) == FieldType.SELECT) {
                            FieldOptionParser.parseSelectOptions(field.config)
                        } else {
                            FieldOptionParser.parseGradeOptions(field.config)
                        }
                    )
                    // 고아 값 보존: 저장된 값이 현재 옵션에 없어도 유실하지 않는다
                    if (saved.isNotBlank() && saved !in options) options.add(saved)

                    val spinner = android.widget.Spinner(ctx).apply {
                        val spinnerAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, options)
                        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        adapter = spinnerAdapter
                        val idx = options.indexOf(saved)
                        if (idx > 0) setSelection(idx)
                    }
                    addEventFieldRow(ctx, density, field, spinner)
                    eventFieldInputMap[field.id] = spinner
                }
                else -> {
                    // MaterialAutoCompleteTextView(EditText 하위) — 라이브러리 제안 장착 지점 (검토 A9:
                    // 사건 값도 수확만 하고 제안하지 않는 비대칭 제거)
                    val editText = com.google.android.material.textfield.MaterialAutoCompleteTextView(ctx).apply {
                        hint = RequiredFieldMark.label(field)
                        setText(saved)
                        threshold = 1
                        if (FieldType.fromName(field.type) == FieldType.NUMBER) {
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (4 * density).toInt() }
                    }
                    addEventFieldRow(ctx, density, field, editText)
                    eventFieldInputMap[field.id] = editText
                }
            }
        }
        attachEventFieldSuggestions()
        consumeFieldFocus()
    }

    /**
     * 고치러 온 요청이 가리킨 칸을 잡는다 (B-198).
     *
     * **폼이 다 선 뒤에만 부른다** — 위젯은 [buildEventFieldInputs]가 만들고, 그 함수는
     * 세계관 조회가 끝난 뒤에야 돈다. 세우기 전에 부르면 언제나 *칸이 없다*가 된다.
     *
     * **인자는 한 번 쓰고 지운다.** 이 시트는 작품 선택을 바꿀 때마다 폼을 다시 세우는데
     * (`rebuildEventFieldSection`), 남겨 두면 그때마다 초점이 튀어 사용자가 보던 자리를 뺏는다.
     *
     * **칸이 없으면 무엇이 없는지 말한다.** 값은 있는데 칸이 없는 조합이 아직 남아 있다.
     * 전역 구역(무소속) 정의는 **이제 그린다**(B-258 ⓐ) — 남은 것은 *구역이 어긋난* 값,
     * 즉 임자의 세계관과 다른 구역의 정의에 매달린 값이다. 그 조합은 엑셀로만 들어오고
     * (가져오기가 행의 세계관 열로 정의를 찾을 뿐 임자와 같은지 묻지 않는다 — B-258 ⓒ),
     * 어느 창에도 그릴 자리가 없다. 조용히 끝내면 누른 사람은 자기가 잘못 눌렀다고 여긴다.
     */
    private fun consumeFieldFocus() {
        val args = arguments ?: return
        val fieldId = args.getLong(FieldValueFixRoute.ARG_FOCUS_FIELD_ID, 0L)
        if (fieldId <= 0L) return
        val fieldName = args.getString(FieldValueFixRoute.ARG_FOCUS_FIELD_NAME).orEmpty()
        args.remove(FieldValueFixRoute.ARG_FOCUS_FIELD_ID)
        args.remove(FieldValueFixRoute.ARG_FOCUS_FIELD_NAME)

        val widget = eventFieldInputMap[fieldId] as? View
        if (widget == null) {
            val ctx = context ?: return
            Toast.makeText(
                ctx, getString(R.string.fix_field_not_in_form, fieldName), Toast.LENGTH_LONG
            ).show()
            return
        }
        // 부착 뒤에 잡는다 — 스크롤 컨테이너는 자식이 초점을 얻을 때 그 자리로 스크롤한다.
        widget.post {
            if (_binding == null) return@post
            widget.requestFocus()
            (widget as? android.widget.EditText)?.let { it.setSelection(it.text?.length ?: 0) }
        }
    }

    /**
     * 입력 위젯 + ✨ 한 줄 (B-43).
     *
     * 캐릭터 폼의 인라인 버튼과 **같은 문법**이다(`DynamicFieldFormBuilder`의 🎲·✨·ⓘ) —
     * 사용자가 한 화면에서 배운 조작이 다른 화면에서 다르게 생기면 그 자체가 마찰이다.
     * 노출 판정도 같은 단일 소스를 쓴다([FieldAiPolicy.isInlineSparkleEnabled]) — '개별만'은
     * 여기서 살아 있어야 하는 상태이고, '끄기'는 버튼 자체가 없어야 하는 상태다.
     */
    private fun addEventFieldRow(
        ctx: android.content.Context,
        density: Float,
        field: FieldDefinition,
        input: View
    ) {
        val spec = aiSpecOf(field)
        if (spec == null) {
            binding.eventFieldContainer.addView(input)
            return
        }
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        }
        // 아래 여백은 **줄에 준다** — 입력칸에 주면 그만큼 ✨과 세로로 어긋난다.
        input.layoutParams = android.widget.LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        row.addView(input)
        row.addView(
            com.google.android.material.button.MaterialButton(
                ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "✨"; textSize = 14f; minWidth = 0; minimumWidth = 0
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                contentDescription = getString(R.string.ai_field_suggest_title)
                setOnClickListener { requestAiSuggestion(field) }
            }
        )
        binding.eventFieldContainer.addView(row)
    }

    /**
     * 이 필드가 AI 추천 대상인가 — 대상이면 요청에 쓸 스펙을 돌려준다.
     *
     * 판정을 한 함수에 모으는 이유는 R-29가 세운 것과 같다: 버튼을 그리는 자리와 요청을
     * 만드는 자리가 각자 판정하면 **버튼은 보이는데 눌러도 아무 일이 없는** 조합이 생긴다.
     */
    private fun aiSpecOf(field: FieldDefinition): CharacterFieldAiSuggester.FieldSpec? {
        if (!FieldAiPolicy.isInlineSparkleEnabled(field.config)) return null
        val current = eventFieldInputMap[field.id]?.let { eventFieldWidgetValue(it) }
            ?: pendingEventFieldValues?.get(field.id.toString()).orEmpty()
        return CharacterFieldAiSuggester.fieldSpecOf(field, current)
    }

    // ===== 사건 필드 AI 추천 (B-43) =====

    /**
     * 실행은 창 스코프의 VM이 든다 — 회전이 유료 응답을 폐기하지 않게 ([EventFieldAiViewModel]).
     * `by lazy`가 아니라 필요할 때 얻는 이유: `onCreateDialog` 이전에 건드리면 안 된다.
     */
    private fun aiViewModel(): EventFieldAiViewModel =
        ViewModelProvider(this)[EventFieldAiViewModel::class.java]

    private var aiProgressDialog: AlertDialog? = null

    /** 진행 표시와 결과 수신 — 창이 다시 만들어져도 VM에 남아 있던 결과가 그대로 뜬다. */
    private fun observeAiSuggest() {
        val vm = aiViewModel()
        vm.running.observe(this) { running ->
            if (running == true) {
                if (aiProgressDialog == null && isAdded) {
                    aiProgressDialog = MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.ai_event_field_running)
                        .setCancelable(false)
                        .show()
                }
            } else {
                aiProgressDialog?.dismiss()
                aiProgressDialog = null
            }
        }
        vm.result.observe(this) { run ->
            if (run == null || !isAdded) return@observe
            EventAiSuggestSheet.showResult(
                fragment = this,
                viewModel = vm,
                run = run,
                currentEventId = editingEvent?.id ?: -1L,
                applyValues = ::applyAiSuggestions
            )
        }
    }

    private fun requestAiSuggestion(field: FieldDefinition) {
        val spec = aiSpecOf(field) ?: return
        EventAiSuggestSheet.showForField(
            fragment = this,
            fieldName = field.name,
            spec = spec,
            viewModel = aiViewModel(),
            eventId = editingEvent?.id ?: -1L,
            contextLoader = { buildEventAiContext() }
        )
    }

    /**
     * 채택분을 폼에 기입한다. **저장하지 않는다** — 사용자가 창에서 마저 손보고 저장을 누르는
     * 것이 이 창의 규약이고, AI 값만 다른 경로로 저장되면 검토 뒤 취소가 통하지 않는다.
     *
     * 위젯이 아직 없으면 false를 돌려준다 — 호출측이 그것을 보고 유료 응답을 되살린다(B-163).
     */
    private fun applyAiSuggestions(
        suggestions: List<CharacterFieldAiSuggester.Suggestion>
    ): Boolean {
        if (_binding == null) return false
        val byKey = eventFields.associateBy { it.key }
        var applied = 0
        for (suggestion in suggestions) {
            val field = byKey[suggestion.fieldKey] ?: continue
            when (val widget = eventFieldInputMap[field.id]) {
                is android.widget.Spinner -> {
                    val adapter = widget.adapter as? ArrayAdapter<String> ?: continue
                    val idx = (0 until adapter.count).firstOrNull {
                        adapter.getItem(it) == suggestion.value
                    } ?: run {
                        // 목록에 없는 값이면 **버리지 않고 항목을 늘려 담는다** — 이 폼이 저장된
                        // 고아 값에 대해 이미 하는 처분과 같다(위 '고아 값 보존'). 조용히 빠지면
                        // 사용자는 적용을 눌렀는데 값이 안 바뀐 이유를 알 수 없다.
                        adapter.add(suggestion.value)
                        adapter.count - 1
                    }
                    widget.setSelection(idx); applied++
                }
                is android.widget.EditText -> { widget.setText(suggestion.value); applied++ }
                else -> Unit
            }
        }
        return applied > 0
    }

    /**
     * 프롬프트에 실을 사건 컨텍스트 — **창의 라이브 입력값**이 기준이다(저장된 값이 아니라).
     * 사건을 쓰다가 ✨를 누르는 것이 정상 동선이고, 그때 저장은 아직 안 됐다.
     *
     * 조회 실패는 '없음'과 갈라 [EventFieldAiSuggester.EventAiContext.loadFailures]로 고지한다 —
     * 근거가 빠진 채 답이 나오면 사용자는 왜 빈약한지 알 길이 없다(변수 제어).
     */
    private suspend fun buildEventAiContext(): EventFieldAiSuggester.EventAiContext? {
        if (_binding == null) return null
        // 구역을 아직 못 정했으면 근거를 모을 수 없다. **전역 구역은 그 상태가 아니다** —
        // 세계관 이름이 없을 뿐 사건·작품·이웃은 그대로 근거가 된다(B-258). 종전에는 `Long?`의
        // null 하나가 둘을 겸해, 전역 구역을 그리기 시작하면 그 줄의 ✨가 **눌리는데 아무 일도
        // 안 나는 단추**가 됐을 자리다 — 이 저장소가 침묵이라 부르는 그 부류다.
        val scope = resolvedFieldScope ?: return null
        val universeId = (scope as? FieldScope.Universe)?.id
        val year = binding.editYear.text.toString().trim().toIntOrNull()
        val month = binding.editMonth.text.toString().trim().toIntOrNull()
        val day = binding.editDay.text.toString().trim().toIntOrNull()
        val calendarType = binding.editCalendarType.text.toString().trim()
        // 날짜 표기는 모델이 든다([TimelineEvent.getFormattedDate]) — 여기서 다시 조립하면
        // 카드·연표와 다른 모양의 날짜가 프롬프트에만 실린다. 역법을 안 적었으면 기본값 그대로.
        val dateLabel = if (year == null) "" else {
            val probe = TimelineEvent(year = year, month = month, day = day, description = "")
            (if (calendarType.isBlank()) probe else probe.copy(calendarType = calendarType))
                .getFormattedDate()
        }
        val typeIndex = binding.spinnerEventType.selectedItemPosition
        val typeLabel = if (typeIndex > 0) eventTypes.getOrNull(typeIndex)?.second.orEmpty() else ""

        val novelNames = novels.filter { it.id in selectedNovelIds }.map { it.title }
        val characterNames = characters.filter { it.id in selectedCharIds }.map { it.name }

        // 조회 실패는 **전부 고지로 나간다** — 근거가 빠진 채 답이 나오면 사용자는 왜 빈약한지
        // 알 수 없다. 조용히 빈 값으로 떨어뜨리는 것이 이 저장소가 반복해 잡아 온 결함이다.
        val failures = mutableListOf<String>()
        val app = activity?.application as? com.novelcharacter.app.NovelCharacterApp
        // Room의 suspend 질의라 스레드를 여기서 옮기지 않는다(이 파일의 다른 조회와 같다).
        // 전역 구역에는 세계관이 없다 — **조회 실패가 아니므로 고지에도 넣지 않는다.**
        // 실패로 세면 사용자는 있지도 않은 고장을 찾는다.
        val universeName = if (universeId == null) "" else try {
            app?.universeRepository?.getUniverseById(universeId)?.name.orEmpty()
        } catch (e: Exception) {
            Log.w("EventEditDialog", "Failed to load universe name for AI context", e)
            failures.add("세계관 이름")
            ""
        }

        // 앞뒤 사건 — 같은 연표에 이미 적힌 사실이라 값을 지어내기 전에 맞춰 볼 근거가 된다.
        // 스코프 조회는 이미 있는 경로를 쓴다(사건 밀기가 쓰는 그것) — 새 질의를 만들면
        // '어느 사건이 이웃인가'의 답이 두 벌이 된다.
        val neighbors = try {
            requireProvider().getEventsInScope(selectedNovelIds.toList(), universeId)
        } catch (e: Exception) {
            Log.w("EventEditDialog", "Failed to load neighbor events for AI context", e)
            failures.add("가까운 사건")
            emptyList()
        }
        val editingId = editingEvent?.id
        val neighborLines = if (year == null) emptyList() else neighbors
            .asSequence()
            .filter { it.id != editingId }
            .sortedBy { kotlin.math.abs(it.year - year) }
            .take(EventFieldAiSuggester.MAX_NEIGHBORS)
            .map { "${it.year}년 – ${it.description}" }
            .toList()

        val filled = eventFields.mapNotNull { field ->
            val widget = eventFieldInputMap[field.id] ?: return@mapNotNull null
            val value = eventFieldWidgetValue(widget)
            if (value.isBlank()) null else field.name to value
        }

        return EventFieldAiSuggester.EventAiContext(
            description = binding.editDescription.text.toString(),
            dateLabel = dateLabel,
            eventTypeLabel = typeLabel,
            universeName = universeName,
            novels = novelNames,
            characters = characterNames,
            neighborEvents = neighborLines,
            filledFields = filled,
            loadFailures = failures
        )
    }

    /** 사건 필드 자동완성 — 라이브러리 제안 (1쿼리 배치, 빈 필드는 제안 없음) */
    private fun attachEventFieldSuggestions() {
        val universeId = eventFields.firstOrNull()?.universeId ?: return
        val app = activity?.application as? com.novelcharacter.app.NovelCharacterApp ?: return
        lifecycleScope.launch {
            val suggestions = runCatching {
                app.fieldValueLibraryRepository.suggestionsForUniverse(
                    universeId, com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT)
            }.getOrDefault(emptyMap())
            if (!isAdded) return@launch
            for (field in eventFields) {
                val widget = eventFieldInputMap[field.id]
                    as? com.google.android.material.textfield.MaterialAutoCompleteTextView ?: continue
                if (!com.novelcharacter.app.data.model.FieldValueLibraryConfig
                        .fromConfig(field.config).isSuggestEnabled) continue
                val entries = suggestions[field.id].orEmpty()
                if (entries.isNotEmpty()) {
                    widget.setAdapter(com.novelcharacter.app.ui.fieldlibrary.LibrarySuggestionAdapter(
                        requireContext(), entries))
                }
            }
        }
    }

    private fun eventFieldWidgetValue(widget: Any): String = when (widget) {
        is android.widget.EditText -> widget.text.toString().trim()
        is android.widget.Spinner -> {
            val pos = widget.selectedItemPosition
            if (pos <= 0) "" else widget.selectedItem?.toString() ?: ""
        }
        else -> ""
    }

    /**
     * 폼 제출 한 벌(S-6). 기본은 지금 렌더된 위젯이 진실이고 커버는 [coveredEventFieldIds]다.
     * 필드 섹션 상태가 아직 없는데(커버·위젯 모두 공집합 — 회전 직후·로딩 미완 창)
     * 마지막으로 렌더됐던 화면의 스냅샷([pendingEventFieldValues])이 있으면 그것이 폼의
     * 진실이다(재공격 F3) — 버리면 회전 전 사용자의 편집이 무통보로 사라지고, 빈 제출로
     * 전량 보존하면 "보관했습니다" 고지가 사용자의 최신 편집 대신 낡은 DB 값을 가리킨다.
     * 그마저 없으면 빈 제출(커버 ∅ = 전량 보존)이다.
     */
    private fun buildFieldSubmission(): EventFieldValueMerge.Submission {
        val pending = pendingEventFieldValues
        if (coveredEventFieldIds.isEmpty() && eventFieldInputMap.isEmpty() && pending != null) {
            val cover = mutableSetOf<Long>()
            val values = mutableListOf<EventFieldValue>()
            for ((key, raw) in pending) {
                val fieldId = key.toLongOrNull() ?: continue
                cover.add(fieldId)   // 스냅샷의 빈 값도 커버에는 남는다 — 화면에서 비운 의도 존중
                val value = raw.trim()
                if (value.isNotEmpty()) {
                    values.add(EventFieldValue(
                        eventId = editingEvent?.id ?: 0, fieldDefinitionId = fieldId, value = value
                    ))
                }
            }
            return EventFieldValueMerge.Submission(values, cover)
        }
        return EventFieldValueMerge.Submission(collectEventFieldValues(), coveredEventFieldIds)
    }

    /** 빈 값은 저장하지 않는다 — 커버된 필드가 폼에 없으면 삭제(비움 의도)로 처리된다(S-6) */
    private fun collectEventFieldValues(): List<EventFieldValue> {
        val result = mutableListOf<EventFieldValue>()
        for (field in eventFields) {
            val widget = eventFieldInputMap[field.id] ?: continue
            val value = eventFieldWidgetValue(widget)
            if (value.isNotBlank()) {
                result.add(
                    EventFieldValue(
                        eventId = editingEvent?.id ?: 0,
                        fieldDefinitionId = field.id,
                        value = value
                    )
                )
            }
        }
        return result
    }

    private fun setupNovelCheckboxes() {
        val recyclerView = binding.novelSelectRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val checkBox = CheckBox(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                    textSize = 14f
                }
                return object : RecyclerView.ViewHolder(checkBox) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val novel = novels[position]
                val checkBox = holder.itemView as CheckBox
                checkBox.text = novel.title
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = selectedNovelIds.contains(novel.id)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedNovelIds.add(novel.id)
                    else selectedNovelIds.remove(novel.id)
                    // 작품 선택 변경 시 캐릭터 정렬 + 사건 필드(세계관 기준) 갱신 + 역법 재시드
                    setupCharacterCheckboxes()
                    rebuildEventFieldSection()
                    maybeSeedCalendarType()
                }
            }

            override fun getItemCount() = novels.size
        }
    }

    private fun setupCharacterCheckboxes() {
        val chars = filteredChars()
        val recyclerView = binding.characterSelectRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val checkBox = CheckBox(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                    textSize = 14f
                }
                return object : RecyclerView.ViewHolder(checkBox) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val character = chars[position]
                val checkBox = holder.itemView as CheckBox
                checkBox.text = character.name
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = selectedCharIds.contains(character.id)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedCharIds.add(character.id)
                    else selectedCharIds.remove(character.id)
                }
            }

            override fun getItemCount() = chars.size
        }
    }

    companion object {
        const val TAG = "EventEditDialogFragment"
        private const val ARG_EVENT_JSON = "eventJson"
        private const val ARG_PRE_CHAR_IDS = "preSelectedCharacterIds"
        private const val ARG_PRE_NOVEL_IDS = "preSelectedNovelIds"
        private const val STATE_CHAR_IDS = "selectedCharIds"
        private const val STATE_NOVEL_IDS = "selectedNovelIds"
        private const val STATE_EVENT_FIELD_VALUES = "eventFieldValues"
        private const val STATE_INITIAL_FILLED = "initialValuesFilled"

        /**
         * 다이얼로그 표시. 호스트 프래그먼트의 childFragmentManager로 띄워야
         * 재생성 시 parentFragment를 통해 [Host]를 다시 찾을 수 있다.
         */
        fun show(
            fragmentManager: FragmentManager,
            event: TimelineEvent? = null,
            preSelectedCharacterIds: Set<Long> = emptySet(),
            preSelectedNovelIds: List<Long> = emptyList(),
            focusFieldId: Long = 0L,
            focusFieldName: String = ""
        ) {
            val fragment = EventEditDialogFragment()
            fragment.arguments = bundleOf(
                ARG_EVENT_JSON to event?.let { Gson().toJson(it) },
                ARG_PRE_CHAR_IDS to preSelectedCharacterIds.toLongArray(),
                ARG_PRE_NOVEL_IDS to preSelectedNovelIds.toLongArray(),
                // 인자 이름은 연표가 받은 것을 그대로 물려준다 — 이름을 여기서 새로 정하면
                // 두 벌이 되고, 갈리면 아무 일도 일어나지 않는다(FieldValueFixRoute KDoc).
                FieldValueFixRoute.ARG_FOCUS_FIELD_ID to focusFieldId,
                FieldValueFixRoute.ARG_FOCUS_FIELD_NAME to focusFieldName
            )
            fragment.show(fragmentManager, TAG)
        }
    }
}
