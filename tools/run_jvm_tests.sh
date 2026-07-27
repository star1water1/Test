#!/bin/bash
# 로컬 순수 JVM 단위 테스트 러너 (Android/Gradle 빌드 불가 환경용).
#
# 이 저장소는 dl.google.com 차단 환경에서 개발될 수 있어 ./gradlew 가 동작하지 않는다.
# 그런 환경에서도 Android 비의존 순수 로직은 표준 kotlinc + JUnitCore 로 실제 실행 검증이 가능하다.
#
# 사용법:
#   JARS_DIR=/path/to/jars tools/run_jvm_tests.sh
#
# JARS_DIR 에 아래 jar 들이 필요하다 (Maven Central 에서 받는다):
#   kotlin-compiler-embeddable-2.0.21.jar, kotlin-stdlib-2.0.21.jar, annotations-13.0.jar,
#   kotlinx-coroutines-core-jvm.jar, kotlinx-coroutines-test-jvm-1.9.0.jar, trove4j.jar,
#   junit-4.13.2.jar, hamcrest-core-1.3.jar, json-20240303.jar, gson-2.10.1.jar,
#   poi-5.2.5.jar, poi-ooxml-5.2.5.jar, poi-ooxml-lite-5.2.5.jar, commons-collections4-4.4.jar,
#   commons-io-2.15.0.jar, commons-compress-1.25.0.jar, xmlbeans-5.2.0.jar, log4j-api-2.21.1.jar
#
# 또한 androidx 스텁을 $JARS_DIR/out-room 에 컴파일해 두어야 한다:
#  - androidx.room: @Entity/@ForeignKey/@Index/@PrimaryKey/@ColumnInfo/@Ignore/@Dao/@Query/
#    @Insert/@Update/@Delete/@Transaction/@Upsert/@Embedded/@Relation, object OnConflictStrategy
#  - androidx.lifecycle: open class LiveData<T>, MutableLiveData<T>
# 선언만 있으면 충분하다(어노테이션·LiveData 는 로직 실행에 영향을 주지 않는다).
# DAO 인터페이스를 포함해야 테스트의 Fake 구현체가 실제로 검증된다 —
# DAO 에 메서드를 추가하고 Fake 를 갱신하지 않는 실수가 CI 전에 잡힌다.
set -u
SP="${JARS_DIR:-/tmp/claude-0/-home-user-Test/6a87d14f-0af6-505a-8734-77051e12d059/scratchpad}"
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
MAIN=$REPO/app/src/main/java/com/novelcharacter/app
TEST=$REPO/app/src/test/java/com/novelcharacter/app
STUBS=$REPO/tools/jvm-stubs
OUT=$SP/out-tests
rm -rf "$OUT"; mkdir -p "$OUT"

CP="$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/out-room:$SP/json-20240303.jar"
CP="$CP:$SP/gson-2.10.1.jar:$SP/junit-4.13.2.jar:$SP/hamcrest-core-1.3.jar"
CP="$CP:$SP/poi-5.2.5.jar:$SP/poi-ooxml-5.2.5.jar:$SP/poi-ooxml-lite-5.2.5.jar"
CP="$CP:$SP/commons-collections4-4.4.jar:$SP/commons-io-2.15.0.jar:$SP/commons-compress-1.25.0.jar"
CP="$CP:$SP/xmlbeans-5.2.0.jar:$SP/log4j-api-2.21.1.jar:$SP/kotlinx-coroutines-core-jvm.jar"
CP="$CP:$SP/kotlinx-coroutines-test-jvm-1.9.0.jar"

KOTLINC="java -cp $SP/kotlin-compiler-embeddable-2.0.21.jar:$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/kotlinx-coroutines-core-jvm.jar:$SP/trove4j.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib"

# androidx.room 어노테이션 스텁 (없으면 생성)
if [ ! -d "$SP/out-room" ]; then
  echo "room 스텁이 없습니다 — 먼저 스텁을 컴파일하세요" >&2; exit 1
fi

# 순수 JVM으로 컴파일 가능한 소스 집합 (Android SDK/Room DB 런타임 비의존)
SOURCES="
$MAIN/data/model/EntityCode.kt
$MAIN/data/model/Character.kt
$MAIN/data/model/Novel.kt
$MAIN/data/model/Universe.kt
$MAIN/data/model/FieldDefinition.kt
$MAIN/data/model/FieldValueEntry.kt
$MAIN/data/model/FieldValueLibraryConfig.kt
$MAIN/data/model/DisplayFormat.kt
$MAIN/data/model/SearchPreset.kt
$MAIN/data/model/Faction.kt
$MAIN/data/model/StructuredInputConfig.kt
$MAIN/excel/FieldValueSheetMapper.kt
$MAIN/excel/PortableFieldFilters.kt
$MAIN/excel/PresetTemplateMatcher.kt
$MAIN/excel/FactionRefResolver.kt
$MAIN/excel/ExcelRefColumns.kt
$MAIN/excel/ImageZipReport.kt
$MAIN/excel/ImageMetaRowResolver.kt
$MAIN/excel/EventFieldHeaders.kt
$MAIN/excel/ExcelHeaderAliases.kt
$MAIN/excel/SheetSpec.kt
$MAIN/excel/ExcelCellValue.kt
$MAIN/excel/CharacterFieldValueOverflow.kt
$MAIN/util/FieldValueTokenizer.kt
$MAIN/util/StatsFieldPolicy.kt
$MAIN/util/ValueDistributions.kt
$MAIN/util/NumericBinning.kt
$MAIN/util/FieldValueMatchSpec.kt
$MAIN/util/FieldValueResolver.kt
$MAIN/data/model/CharacterFieldValue.kt
$MAIN/data/model/CharacterStateChange.kt
$MAIN/data/model/CharacterTag.kt
$MAIN/data/model/CharacterRelationship.kt
$MAIN/data/model/TimelineEvent.kt
$MAIN/data/model/CharacterRelationshipChange.kt
$MAIN/data/model/FactionMembership.kt
$MAIN/data/model/CharacterSnapshot.kt
$MAIN/data/model/SnapshotRefs.kt
$MAIN/data/model/TrashSnapshot.kt
$MAIN/data/model/EventFieldValue.kt
$MAIN/data/dao/EventFieldValueDao.kt
$MAIN/data/repository/EventFieldValueMerge.kt
$MAIN/data/model/FactionRelationship.kt
$MAIN/data/model/EntitySnapshots.kt
$MAIN/data/dao/TrashSnapshotDao.kt
$MAIN/data/repository/TrashGrouping.kt
$MAIN/data/repository/SnapshotRefResolver.kt
$MAIN/data/repository/CharacterFieldValueMerge.kt
$MAIN/data/repository/TrashPruneSelector.kt
$MAIN/data/repository/RestoreModes.kt
$MAIN/data/repository/RestoreLossCounts.kt
$MAIN/data/repository/RestoreTally.kt
$MAIN/ai/AiModelSuggestions.kt
$MAIN/data/model/FieldFilter.kt
$MAIN/data/repository/QueryUtils.kt
$MAIN/data/dao/CharacterFieldValueDao.kt
$MAIN/data/dao/FieldDefinitionDao.kt
$MAIN/data/dao/FieldValueEntryDao.kt
$MAIN/util/FieldFilterHelper.kt
$MAIN/data/model/FieldStatsConfig.kt
$MAIN/data/model/FieldType.kt
$MAIN/data/model/NameBankEntry.kt
$MAIN/data/model/TimelineCharacterCrossRef.kt
$MAIN/data/model/TimelineEventNovelCrossRef.kt
$MAIN/data/model/BodyAnalysisConfig.kt
$MAIN/data/model/CardDisplayConfig.kt
$MAIN/util/CardFieldSummary.kt
$MAIN/util/GsonTypes.kt
$MAIN/util/FieldOptionParser.kt
$MAIN/util/FormulaEvaluator.kt
$MAIN/util/GradeValueResolver.kt
$MAIN/ui/stats/StatsDataProvider.kt
$MAIN/util/UnassignedFilter.kt
$MAIN/data/model/RandomConfig.kt
$MAIN/data/model/SemanticRole.kt
$MAIN/ai/CharacterFieldAiSuggester.kt
$MAIN/ai/FieldLibraryAiOrganizer.kt
$MAIN/ai/AiModels.kt
$MAIN/ai/AiProtocolCodec.kt
$MAIN/ai/NarrativeFieldAiWriter.kt
$MAIN/data/model/NarrativeMode.kt
$MAIN/util/ImageFilterHelper.kt
$MAIN/util/ImageRecommendationHelper.kt
$MAIN/util/ImageLinkResolver.kt
$MAIN/ui/supplement/RandomPickEngine.kt
$MAIN/ui/namebank/BulkRegisterPlanner.kt
$MAIN/excel/StreamingXlsxReader.kt
$MAIN/excel/MergedCellMap.kt
$MAIN/data/repository/FieldValueRules.kt
$MAIN/share/WorldPackageFactionRelationships.kt
$MAIN/share/WorldPackageContents.kt
$MAIN/share/WorldPackageCodes.kt
$MAIN/excel/ImportFileFormat.kt
$STUBS/StatsHarnessStubs.kt
$STUBS/AndroidLogStub.kt
$STUBS/AiServiceStub.kt
"
TESTS="
$TEST/excel/FieldValueSheetMapperTest.kt
$TEST/excel/PortableFieldFiltersTest.kt
$TEST/excel/EventFieldHeadersTest.kt
$TEST/excel/CharacterHeaderIdentityTest.kt
$TEST/excel/CharacterFieldValueOverflowTest.kt
$TEST/excel/PresetTemplateMatcherTest.kt
$TEST/excel/SheetValueConventionsTest.kt
$TEST/excel/FactionRefResolverTest.kt
$TEST/excel/ImageBackupIntegrityTest.kt
$TEST/excel/ExcelCellValueTest.kt
$TEST/util/FieldValueTokenizerTest.kt
$TEST/util/SortComparatorsTest.kt
$TEST/util/EpochMemoTest.kt
$TEST/ai/AiJsonExtractorTest.kt
$TEST/util/FieldFilterHelperTest.kt
$TEST/data/SnapshotRefResolverTest.kt
$TEST/data/CharacterSnapshotPayloadTest.kt
$TEST/data/RestoreModeTest.kt
$TEST/data/CharacterFieldValueMergeTest.kt
$TEST/data/EventFieldValueMergeTest.kt
$TEST/data/EventFieldValueDaoReplaceTest.kt
$TEST/excel/SheetNameAssignmentTest.kt
$TEST/data/TrashPruneSelectorTest.kt
$TEST/data/TrashGroupingTest.kt
$TEST/data/EntitySnapshotPayloadTest.kt
$TEST/data/RestoreLossCountsTest.kt
$TEST/data/RestoreTallyTest.kt
$TEST/ai/AiModelSuggestionsTest.kt
$TEST/stats/StatsCrossAnalysisTest.kt
$TEST/stats/StatsDrilldownTest.kt
$TEST/stats/StatsConsistencyTest.kt
$TEST/util/ValueDistributionsTest.kt
$TEST/util/NumericBinningTest.kt
$TEST/util/StatsFieldPolicyTest.kt
$TEST/util/CardFieldSummaryTest.kt
$TEST/util/UnassignedFilterTest.kt
$TEST/util/FieldValueResolverTest.kt
$TEST/util/ImageFilterHelperTest.kt
$TEST/util/ImageRecommendationHelperTest.kt
$TEST/util/ImageLinkResolverTest.kt
$TEST/ui/supplement/RandomPickEngineTest.kt
$TEST/stats/StatsDataProviderUnassignedTest.kt
$TEST/namebank/BulkRegisterPlannerTest.kt
$TEST/excel/StreamingXlsxReaderTest.kt
$TEST/ai/CharacterFieldAiSuggesterTest.kt
$TEST/ai/FieldLibraryAiOrganizerTest.kt
$TEST/ai/AiProtocolCodecTest.kt
$TEST/ai/AiTokenPolicyTest.kt
$TEST/ai/NarrativeFieldTest.kt
$TEST/share/WorldPackageFactionRelationshipsTest.kt
$TEST/excel/MergedCellMapTest.kt
$TEST/share/WorldPackageParserTest.kt
$TEST/share/WorldPackageCodesTest.kt
$TEST/excel/ImportFileFormatTest.kt
"
# 주의: AiPresetsConsistencyTest는 R을 참조하므로 여기서 돌릴 수 없다(파일 상단 KDoc 참조) — CI 전용.
# 선택 소스: 존재하고 순수 JVM이면 추가
for extra in "$MAIN/util/SortComparators.kt" "$MAIN/util/EpochMemo.kt" "$MAIN/ai/AiJsonExtractor.kt"; do
  [ -f "$extra" ] && SOURCES="$SOURCES $extra"
done

echo "── 컴파일 ──"
# shellcheck disable=SC2086
$KOTLINC -cp "$CP" -d "$OUT" $SOURCES $TESTS 2>&1 | grep -E "error:" | head -25
COMPILED=$(find "$OUT" -name '*.class' 2>/dev/null | wc -l)
if [ "$COMPILED" -eq 0 ]; then
  echo "컴파일 실패 — 클래스 산출물 없음"; exit 1
fi
echo "컴파일 OK (클래스 $COMPILED개)"

echo "── 실행 ──"
CLASSES=$(cd "$OUT" && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
echo "$CLASSES" | sed 's/^/  /'
# shellcheck disable=SC2086
java -cp "$OUT:$CP" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -vE "^Picked up JAVA_TOOL_OPTIONS" | tail -25
