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
#   junit-4.13.2.jar, hamcrest-core-1.3.jar, json-20240303.jar, gson, POI 계열
# **버전은 여기 적지 않는다** — `tools/jvm_env_versions.sh`가 단일 소스이고, POI·gson은
# 그 파일이 앱의 build.gradle.kts에서 읽는다(B-84: 하네스 5.2.5 vs 앱 5.3.0으로 갈렸던 자리).
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
. "$(cd "$(dirname "$0")" && pwd)/jvm_env_versions.sh"   # jar 버전 단일 소스 (B-84)
jvm_env_require_jars "$SP"
MAIN=$REPO/app/src/main/java/com/novelcharacter/app
TEST=$REPO/app/src/test/java/com/novelcharacter/app
STUBS=$REPO/tools/jvm-stubs
OUT=$SP/out-tests
rm -rf "$OUT"; mkdir -p "$OUT"

CP="$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/out-room:$SP/json-20240303.jar"
CP="$CP:$SP/gson-$GSON_VER.jar:$SP/junit-4.13.2.jar:$SP/hamcrest-core-1.3.jar"
CP="$CP:$(excel_cp "$SP"):$SP/kotlinx-coroutines-core-jvm.jar"
CP="$CP:$SP/kotlinx-coroutines-test-jvm-1.9.0.jar"

# 파일 이름 인코딩 — **한글 시험 이름이 조용히 겹치던 자리다**(2026.08.17, B-191 판이 실측).
# 컨테이너 기본 로케일은 `POSIX`라 `sun.jnu.encoding`이 ASCII가 되고, 그러면 코틀린이
# 클래스 파일을 쓸 때 이름의 한글이 **글자마다 `?`로 치환된다.** 같은 클래스 안에 글자 수가
# 같은 한글 시험 이름이 둘 있으면 **파일 이름이 똑같아져 뒤엣것이 앞엣것을 덮고**, 남은 하나는
# `NoClassDefFoundError: … (wrong name: …)`로 죽는다. **조용하지는 않다** — 빨간불은 뜬다.
# 다만 그 빨간불이 가리키는 자리가 **원인과 무관한 시험**이라(겹친 두 이름 중 하나가 임의로
# 걸린다) 원인을 캐는 데 걸리고, 이름을 한 글자 고치면 사라져 *고쳤다*고 오인하기 쉽다.
# `C.utf8`은 이 이미지에 있는 유일한 UTF-8 로케일이다(`locale -a`).
if locale -a 2>/dev/null | grep -qx "C.utf8"; then
  export LC_ALL=C.utf8
fi

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
$MAIN/data/model/GradeSystem.kt
$MAIN/data/model/GradeSystemRef.kt
$MAIN/data/model/DuelGradeRef.kt
$MAIN/data/model/FieldConfigTransfer.kt
$MAIN/data/model/DefaultFieldTemplate.kt
$MAIN/data/model/DefaultFieldRef.kt
$MAIN/data/model/FieldValueLibraryConfig.kt
$MAIN/data/model/FieldAiPolicy.kt
$MAIN/data/model/FieldDescription.kt
$MAIN/excel/FieldConfigColumns.kt
$MAIN/data/model/ConfigParseCache.kt
$MAIN/data/model/DisplayFormat.kt
$MAIN/data/model/SearchPreset.kt
$MAIN/data/model/CharacterListPreset.kt
$MAIN/data/model/Faction.kt
$MAIN/data/model/StructuredInputConfig.kt
$MAIN/excel/FieldValueSheetMapper.kt
$MAIN/excel/PortableFieldFilters.kt
$MAIN/excel/PresetTemplateMatcher.kt
$MAIN/excel/FactionRefResolver.kt
$MAIN/excel/NovelRefResolver.kt
$MAIN/excel/CharacterRefResolver.kt
$MAIN/excel/ExcelRefColumns.kt
$MAIN/excel/ImageZipReport.kt
$MAIN/excel/ExportOptions.kt
$MAIN/excel/ExportProgress.kt
$MAIN/excel/OverwriteGuard.kt
$MAIN/excel/ImageMetaRowResolver.kt
$MAIN/excel/EntityFieldHeaders.kt
$MAIN/excel/EntityFieldColumnResolver.kt
$MAIN/excel/ExcelHeaderAliases.kt
$MAIN/excel/SheetSpec.kt
$MAIN/excel/ExcelCellValue.kt
$MAIN/excel/TransferInterruption.kt
$MAIN/excel/SheetResolver.kt
$MAIN/excel/CharacterFieldValueOverflow.kt
$MAIN/excel/AllCharactersSheet.kt
$MAIN/util/ProgressScale.kt
$MAIN/util/DialogScrollCap.kt
$MAIN/util/RequiredFieldGaps.kt
$MAIN/util/CompletionRate.kt
$MAIN/util/CharacterValueLedger.kt
$MAIN/util/ImportLookupIndex.kt
$MAIN/util/PreviewCreations.kt
$MAIN/util/FieldValueCellPlan.kt
$MAIN/util/FieldValueOverlay.kt
$MAIN/util/FieldValueScan.kt
$MAIN/util/CharacterFieldColumns.kt
$MAIN/backup/BackupChunkFormat.kt
$MAIN/util/ImportIdentityIndexes.kt
$MAIN/util/DisplayCap.kt
$MAIN/util/GraphForceLayout.kt
$MAIN/util/CrossTableFold.kt
$MAIN/util/BackupWorkerPolicy.kt
$MAIN/util/CsvTokens.kt
$MAIN/util/FieldValueTokenizer.kt
$MAIN/util/FieldValueSorter.kt
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
$MAIN/data/model/NovelFieldValue.kt
$MAIN/data/dao/EventFieldValueDao.kt
$MAIN/data/repository/EventFieldValueMerge.kt
$MAIN/data/dao/NovelFieldValueDao.kt
$MAIN/data/repository/NovelFieldValueMerge.kt
$MAIN/data/model/FactionRelationship.kt
$MAIN/data/model/EntitySnapshots.kt
$MAIN/data/model/DuelAxis.kt
$MAIN/data/model/DuelMatch.kt
$MAIN/data/model/DuelCounterVerdict.kt
$MAIN/data/model/DuelSnapshots.kt
$MAIN/data/dao/TrashSnapshotDao.kt
$MAIN/data/repository/TrashGrouping.kt
$MAIN/data/repository/TrashFilter.kt
$MAIN/data/repository/TrashListPlan.kt
$MAIN/data/repository/TrashRetentionPolicy.kt
$MAIN/data/repository/TrashPayloadCodec.kt
$MAIN/data/repository/SnapshotRefResolver.kt
$MAIN/data/repository/CharacterFieldValueMerge.kt
$MAIN/data/repository/TrashPruneSelector.kt
$MAIN/data/repository/RestoreModes.kt
$MAIN/data/repository/RestoreLossCounts.kt
$MAIN/data/repository/RestoreTally.kt
$MAIN/ai/AiModelSuggestions.kt
$MAIN/data/model/FieldFilter.kt
$MAIN/data/model/ImageMeta.kt
$MAIN/data/repository/QueryUtils.kt
$MAIN/data/dao/CharacterFieldValueDao.kt
$MAIN/data/dao/FieldDefinitionDao.kt
$MAIN/data/dao/FieldValueEntryDao.kt
$MAIN/data/dao/SearchPresetDao.kt
$MAIN/data/dao/CharacterListPresetDao.kt
$MAIN/data/repository/SearchPresetRepository.kt
$MAIN/data/repository/CharacterListPresetRepository.kt
$MAIN/util/PresetLimit.kt
$MAIN/util/PresetNameConflict.kt
$MAIN/util/FieldFilterHelper.kt
$MAIN/data/model/FieldStatsConfig.kt
$MAIN/data/model/FieldType.kt
$MAIN/data/model/NameBankEntry.kt
$MAIN/data/model/TimelineCharacterCrossRef.kt
$MAIN/data/model/TimelineEventNovelCrossRef.kt
$MAIN/data/model/BodyAnalysisConfig.kt
$MAIN/util/BodyAnalysisHelper.kt
$MAIN/util/BodyTargetRatio.kt
$MAIN/util/BodyMeasurements.kt
$MAIN/util/BodySilhouetteSpec.kt
$MAIN/util/BodyEditorModel.kt
$MAIN/util/BodyEditorState.kt
$MAIN/util/BodyGenerator.kt
$MAIN/data/model/CardDisplayConfig.kt
$MAIN/util/CardFieldSummary.kt
$MAIN/util/FieldSuggestionEntries.kt
$MAIN/util/GsonTypes.kt
$MAIN/util/FieldOptionParser.kt
$MAIN/util/FormulaLexer.kt
$MAIN/util/FormulaEvaluator.kt
$MAIN/util/FormulaValidator.kt
$MAIN/util/ImportedFormulaAudit.kt
$MAIN/util/FormulaDisplay.kt
$MAIN/util/GradeValueResolver.kt
$MAIN/util/GradeTable.kt
$MAIN/util/FactionMembershipMatcher.kt
$MAIN/util/FactionStanding.kt
$MAIN/util/FactionRelationshipMatcher.kt
$MAIN/util/InitialFieldValues.kt
$MAIN/ui/stats/StatsDataProvider.kt
$MAIN/util/UnassignedFilter.kt
$MAIN/data/model/RandomConfig.kt
$MAIN/data/model/SemanticRole.kt
$MAIN/data/model/RequiredEnforcement.kt
$MAIN/ai/CharacterFieldAiSuggester.kt
$MAIN/ai/FieldPromptSource.kt
$MAIN/ai/EventFieldAiSuggester.kt
$MAIN/ai/FieldLibraryAiOrganizer.kt
$MAIN/ai/AiModels.kt
$MAIN/ai/AiCreativity.kt
$MAIN/ai/AiProtocolCodec.kt
$MAIN/ai/AiProviderCodec.kt
$MAIN/ai/AiProviderActivation.kt
$MAIN/ai/AiProviderFallback.kt
$MAIN/ai/AiErrorText.kt
$MAIN/ai/AiErrorPolicy.kt
$MAIN/ai/NarrativeFieldAiWriter.kt
$MAIN/ai/CharacterNameAiSuggester.kt
$MAIN/ai/AiPromptPolicy.kt
$MAIN/ai/ImageFolderTagSuggester.kt
$MAIN/ai/ImageTagVocabulary.kt
$MAIN/ai/ImageBatchTagSuggester.kt
$MAIN/data/model/NarrativeMode.kt
$MAIN/util/ImageFilterHelper.kt
$MAIN/util/ImagePathMatch.kt
$MAIN/util/DetachedImageRule.kt
$MAIN/util/DuelRating.kt
$MAIN/util/DuelPairing.kt
$MAIN/util/DuelCounterRelations.kt
$MAIN/util/DuelRecords.kt
$MAIN/util/DuelImageParticipants.kt
$MAIN/util/DuelImageRoster.kt
$MAIN/util/DuelImagePrune.kt
$MAIN/util/RepresentativeWeighting.kt
$MAIN/util/DuelSession.kt
$MAIN/util/DuelRound.kt
$MAIN/util/DuelCardGrid.kt
$MAIN/util/DuelStandings.kt
$MAIN/util/DuelEntry.kt
$MAIN/util/DuelImageFit.kt
$MAIN/util/DuelFieldLinks.kt
$MAIN/util/DuelSystemFields.kt
$MAIN/util/DuelAxisTransfer.kt
$MAIN/util/DuelCandidateFilter.kt
$MAIN/util/DuelCardInfo.kt
$MAIN/util/DuelMatchLog.kt
$MAIN/util/DuelCategoryStats.kt
$MAIN/util/DuelScoreIndex.kt
$MAIN/util/DuelAiContext.kt
$MAIN/util/DuelGradeAssign.kt
$MAIN/util/DuelAxisChoice.kt
$MAIN/util/CharacterRepresentativeImage.kt
$MAIN/util/AiImageAttach.kt
$MAIN/util/RepresentativeImageCell.kt
$MAIN/util/LibraryPickerRow.kt
$MAIN/util/ImageRecommendationHelper.kt
$MAIN/util/ImageLinkResolver.kt
$MAIN/util/AutoLinkPlanner.kt
$MAIN/util/ImageAdoptionPlanner.kt
$MAIN/util/ImageLinkGroupPlanner.kt
$MAIN/util/ImageTagApplyPlanner.kt
$MAIN/util/SqlInChunks.kt
$MAIN/util/FolderNameToken.kt
$MAIN/util/FolderRoundtripPlanner.kt
$MAIN/util/FolderRoundtripLedger.kt
$MAIN/util/FolderExportPlanner.kt
$MAIN/ui/assistant/AssistantInsight.kt
$MAIN/ui/assistant/AssistantSort.kt
$MAIN/util/DetailListSort.kt
$MAIN/ui/supplement/RandomPickEngine.kt
$MAIN/ui/namebank/BulkRegisterPlanner.kt
$MAIN/util/NameBankMatch.kt
$MAIN/util/NameBankPickOrder.kt
$MAIN/excel/StreamingXlsxReader.kt
$MAIN/excel/MergedCellMap.kt
$MAIN/excel/DropdownListLimits.kt
$MAIN/excel/DropdownListSheet.kt
$MAIN/excel/ImportSource.kt
$MAIN/util/ResetPlan.kt
$MAIN/util/MembershipTimeline.kt
$MAIN/util/TimelineDisplayOrder.kt
$MAIN/data/model/UserPresetTemplate.kt
$MAIN/util/PresetTemplates.kt
$MAIN/util/PresetMerge.kt
$MAIN/util/FieldTypeCompatibility.kt
$MAIN/util/FieldValueTypeMismatch.kt
$MAIN/util/FieldValueFixRoute.kt
$MAIN/util/GlobalScopeFieldMove.kt
$MAIN/util/UnassignedHistoryScope.kt
$MAIN/util/DefaultFieldPlan.kt
$MAIN/data/repository/FieldValueRules.kt
$MAIN/share/WorldPackageFactionRelationships.kt
$MAIN/share/WorldPackageContents.kt
$MAIN/share/WorldPackageCodes.kt
$MAIN/share/WorldPackageDuels.kt
$MAIN/excel/ExportWorkbook.kt
$MAIN/excel/ImportFileFormat.kt
$MAIN/excel/FieldDefinitionPrune.kt
$MAIN/excel/FieldScopeCell.kt
$MAIN/excel/AppSettingsKeys.kt
$STUBS/StatsHarnessStubs.kt
$STUBS/AndroidLogStub.kt
$STUBS/AiServiceStub.kt
"
TESTS="
$TEST/excel/FieldValueSheetMapperTest.kt
$TEST/excel/PortableFieldFiltersTest.kt
$TEST/excel/EntityFieldHeadersTest.kt
$TEST/excel/EntityFieldColumnResolverTest.kt
$TEST/excel/EntityFieldValueOverflowTest.kt
$TEST/excel/CharacterHeaderIdentityTest.kt
$TEST/excel/CharacterFieldValueOverflowTest.kt
$TEST/excel/AllCharactersSheetTest.kt
$TEST/excel/GradeSystemSheetTest.kt
$TEST/excel/DefaultFieldSheetTest.kt
$TEST/excel/FieldDefinitionPruneTest.kt
$TEST/excel/FieldScopeCellTest.kt
$TEST/excel/AppSettingsKeysTest.kt
$TEST/util/GradeTableTest.kt
$TEST/data/GradeSystemRefTest.kt
$TEST/data/DuelGradeRefTest.kt
$TEST/data/SemanticRoleEntityScopeTest.kt
$TEST/data/RequiredEnforcementTest.kt
$TEST/data/PresetLimitTest.kt
$TEST/data/PresetNameGuardTest.kt
$TEST/util/PresetNameConflictTest.kt
$TEST/data/FieldConfigEntityTypeTransferTest.kt
$TEST/excel/PresetTemplateMatcherTest.kt
$TEST/excel/SheetValueConventionsTest.kt
$TEST/excel/SheetSpecCsvTest.kt
$TEST/excel/FactionRefResolverTest.kt
$TEST/excel/NovelRefResolverTest.kt
$TEST/excel/CharacterRefResolverTest.kt
$TEST/excel/ImageBackupIntegrityTest.kt
$TEST/excel/ExportPlanAndSpaceTest.kt
$TEST/excel/OverwriteGuardTest.kt
$TEST/excel/ExcelCellValueTest.kt
$TEST/excel/TransferInterruptionTest.kt
$TEST/util/ProgressScaleTest.kt
$TEST/util/DialogScrollCapTest.kt
$TEST/util/RequiredFieldGapsTest.kt
$TEST/util/CompletionRateTest.kt
$TEST/util/CharacterValueLedgerTest.kt
$TEST/util/ImportLookupIndexTest.kt
$TEST/util/ImportIdentityIndexesTest.kt
$TEST/util/PreviewCreationsTest.kt
$TEST/util/FieldValueCellPlanTest.kt
$TEST/util/FieldValueScanTest.kt
$TEST/util/DefaultFieldSlotGuardTest.kt
$TEST/util/CharacterFieldColumnsTest.kt
$TEST/util/DisplayCapTest.kt
$TEST/util/GraphForceLayoutTest.kt
$TEST/util/CrossTableFoldTest.kt
$TEST/util/DuelRatingTest.kt
$TEST/util/DuelPairingTest.kt
$TEST/util/DuelCandidateFilterTest.kt
$TEST/util/DuelCounterRelationsTest.kt
$TEST/util/DuelRecordsTest.kt
$TEST/util/DuelImageParticipantsTest.kt
$TEST/util/DuelImageRosterTest.kt
$TEST/util/DuelImageStandingsShiftTest.kt
$TEST/util/DuelImagePruneTest.kt
$TEST/util/RepresentativeWeightingTest.kt
$TEST/util/DuelSessionTest.kt
$TEST/util/DuelRoundTest.kt
$TEST/util/DuelPlackettLuceComparisonTest.kt
$TEST/util/DuelCardGridTest.kt
$TEST/util/DuelStandingsTest.kt
$TEST/util/DuelEntryTest.kt
$TEST/util/DuelImageFitTest.kt
$TEST/util/DuelFieldLinksTest.kt
$TEST/util/DuelSystemFieldsTest.kt
$TEST/util/DuelAxisTransferTest.kt
$TEST/util/FactionStandingTest.kt
$TEST/util/DuelCardInfoTest.kt
$TEST/util/DuelMatchLogTest.kt
$TEST/util/AiImageAttachTest.kt
$TEST/ai/AiImageRequestTest.kt
$TEST/ai/AiProviderCodecTest.kt
$TEST/ai/AiProviderActivationTest.kt
$TEST/ai/AiProviderFallbackTest.kt
$TEST/ai/AiErrorTextTest.kt
$TEST/ai/AiErrorPolicyTest.kt
$TEST/ai/ImageBatchTagSuggesterTest.kt
$TEST/util/DuelCategoryStatsTest.kt
$TEST/util/DuelScoreIndexTest.kt
$TEST/util/DuelAiContextTest.kt
$TEST/util/DuelGradeAssignTest.kt
$TEST/util/DuelAxisChoiceTest.kt
$TEST/excel/DuelSheetSpecTest.kt
$TEST/excel/ListPresetSpecColumnOrderTest.kt
$TEST/util/BackupWorkerPolicyTest.kt
$TEST/util/CsvTokensTest.kt
$TEST/util/FieldValueTokenizerTest.kt
$TEST/util/FieldValueSorterTest.kt
$TEST/util/FormulaDisplayTest.kt
$TEST/util/FormulaEvaluatorTest.kt
$TEST/util/FormulaValidatorTest.kt
$TEST/util/ImportedFormulaAuditTest.kt
$TEST/util/SortComparatorsTest.kt
$TEST/util/DetailListSortTest.kt
$TEST/ui/assistant/AssistantSortTest.kt
$TEST/util/EpochMemoTest.kt
$TEST/util/PresetTemplatesRoundtripTest.kt
$TEST/util/PresetMergeTest.kt
$TEST/util/DefaultFieldPlanTest.kt
$TEST/util/GlobalScopeFieldMoveTest.kt
$TEST/util/UnassignedHistoryScopeTest.kt
$TEST/data/model/DefaultFieldRefTest.kt
$TEST/data/model/FieldTypeBranchTest.kt
$TEST/data/model/DuelAxisBasisTest.kt
$TEST/ai/AiJsonExtractorTest.kt
$TEST/util/FieldFilterHelperTest.kt
$TEST/util/ImagePathMatchTest.kt
$TEST/util/CharacterRepresentativeImageTest.kt
$TEST/util/RepresentativeImageCellTest.kt
$TEST/excel/CharacterSpecColumnOrderTest.kt
$TEST/data/SnapshotRefResolverTest.kt
$TEST/data/CharacterSnapshotPayloadTest.kt
$TEST/data/RestoreModeTest.kt
$TEST/data/CharacterFieldValueMergeTest.kt
$TEST/data/EventFieldValueMergeTest.kt
$TEST/data/NovelFieldValueMergeTest.kt
$TEST/data/EventFieldValueDaoReplaceTest.kt
$TEST/data/NovelFieldValueDaoReplaceTest.kt
$TEST/excel/SheetNameAssignmentTest.kt
$TEST/data/TrashPruneSelectorTest.kt
$TEST/data/TrashGroupingTest.kt
$TEST/data/TrashListPlanTest.kt
$TEST/data/TrashFilterTest.kt
$TEST/data/TrashRetentionPolicyTest.kt
$TEST/data/TrashPayloadCodecTest.kt
$TEST/data/EntitySnapshotPayloadTest.kt
$TEST/data/DuelSnapshotPayloadTest.kt
$TEST/data/RestoreLossCountsTest.kt
$TEST/data/RestoreTallyTest.kt
$TEST/data/FieldValueUsageRecountTest.kt
$TEST/ai/AiModelSuggestionsTest.kt
$TEST/stats/StatsCrossAnalysisTest.kt
$TEST/stats/StatsDrilldownTest.kt
$TEST/stats/DuelRankingTest.kt
$TEST/stats/StatsConsistencyTest.kt
$TEST/stats/StatsMemoParityTest.kt
$TEST/stats/StatsKeysParityTest.kt
$TEST/stats/StatsFoldParityTest.kt
$TEST/stats/StatsScanParityTest.kt
$TEST/stats/StatsOverviewParityTest.kt
$TEST/stats/NumericBinDrilldownTest.kt
$TEST/stats/NumericDistributionFoldTest.kt
$TEST/stats/DataHealthReorgTest.kt
$TEST/util/FieldValueTypeMismatchTest.kt
$TEST/util/FieldValueFixRouteTest.kt
$TEST/util/ValueDistributionsTest.kt
$TEST/util/NumericBinningTest.kt
$TEST/util/StatsFieldPolicyTest.kt
$TEST/util/CardFieldSummaryTest.kt
$TEST/util/FieldSuggestionEntriesTest.kt
$TEST/util/UnassignedFilterTest.kt
$TEST/util/DetachedImageRuleTest.kt
$TEST/util/FieldValueResolverTest.kt
$TEST/util/ImageFilterHelperTest.kt
$TEST/util/LibraryPickerRowsTest.kt
$TEST/util/ImageRecommendationHelperTest.kt
$TEST/util/ImageLinkResolverTest.kt
$TEST/util/AutoLinkPlannerTest.kt
$TEST/util/ImageAdoptionPlannerTest.kt
$TEST/util/ImageLinkGroupPlannerTest.kt
$TEST/util/ImageTagApplyPlannerTest.kt
$TEST/util/SqlInChunksTest.kt
$TEST/util/FolderNameTokenTest.kt
$TEST/util/FolderRoundtripPlannerTest.kt
$TEST/util/CharacterFolderResolverTest.kt
$TEST/util/FolderRoundtripLedgerTest.kt
$TEST/util/FolderExportPlannerTest.kt
$TEST/ui/supplement/RandomPickEngineTest.kt
$TEST/stats/StatsDataProviderUnassignedTest.kt
$TEST/stats/PatternDetectionAxisTest.kt
$TEST/namebank/BulkRegisterPlannerTest.kt
$TEST/util/NameBankMatchTest.kt
$TEST/util/NameBankPickOrderTest.kt
$TEST/excel/StreamingXlsxReaderTest.kt
$TEST/excel/ImportSourceEquivalenceTest.kt
$TEST/excel/SheetResolverTest.kt
$TEST/util/ResetPlanTest.kt
$TEST/util/MembershipTimelineTest.kt
$TEST/util/TimelineDisplayOrderTest.kt
$TEST/util/FactionMembershipMatcherTest.kt
$TEST/util/FactionRelationshipMatcherTest.kt
$TEST/util/EventFieldRecommendationTest.kt
$TEST/ai/CharacterFieldAiSuggesterTest.kt
$TEST/ai/EventFieldAiSuggesterTest.kt
$TEST/ai/FieldAiTargetRuleTest.kt
$TEST/ai/NarrativeBulkDraftTest.kt
$TEST/ai/ImageFolderTagSuggesterTest.kt
$TEST/data/FieldConfigPolicyTest.kt
$TEST/excel/FieldConfigColumnsTest.kt
$TEST/ai/FieldLibraryAiOrganizerTest.kt
$TEST/ai/AiProtocolCodecTest.kt
$TEST/ai/AiCreativityTest.kt
$TEST/ai/AiTokenPolicyTest.kt
$TEST/ai/NarrativeFieldTest.kt
$TEST/ai/CharacterNameAiSuggesterTest.kt
$TEST/share/WorldPackageFactionRelationshipsTest.kt
$TEST/excel/MergedCellMapTest.kt
$TEST/excel/DropdownListLimitsTest.kt
$TEST/excel/DropdownListSheetTest.kt
$TEST/share/WorldPackageParserTest.kt
$TEST/share/WorldPackageCodesTest.kt
$TEST/share/WorldPackageDuelsTest.kt
$TEST/excel/ExportWorkbookParityTest.kt
$TEST/excel/ExportStreamingAvailabilityTest.kt
$TEST/excel/ExportPresentationSpecTest.kt
$TEST/excel/ImportFileFormatTest.kt
$TEST/util/BodyMeasurementsTest.kt
$TEST/data/BodyAnalysisConfigKeysTest.kt
$TEST/util/BodySilhouetteSpecTest.kt
$TEST/util/BodyEditorModelTest.kt
$TEST/util/BodyEditorStateTest.kt
$TEST/util/BodyCupContractTest.kt
$TEST/util/BodyTargetRatioTest.kt
$TEST/util/BodyTargetRatioSourceTest.kt
$TEST/util/BodyGeneratorTest.kt
$TEST/data/BodyGenerationConfigTest.kt
$TEST/backup/BackupChunkFormatTest.kt
$TEST/excel/CellTextLimitRoundtripTest.kt
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
# 종료 코드는 **JUnitCore의 것**이어야 한다 (B-185).
# 종전에는 이 줄이 파이프라인의 마지막인 `tail`의 코드를 그대로 스크립트의 코드로 넘겨
# **시험이 빨간불이어도 `exit 0`**이었다 — `if tools/run_jvm_tests.sh; then` 꼴로 판정하면
# 실패를 초록으로 읽는다. 이 저장소는 방어선을 세울 때 *되돌려 빨간불을 본다*를 관행으로
# 삼는데, 그 확인을 종료 코드로 자동화하면 **되돌려도 초록**이라 *방어선이 없다*는
# 정반대 결론에 닿는다(사람이 출력을 눈으로 읽는 동안에는 드러나지 않는 부류다).
#
# **`set -o pipefail`로 고치지 않는다.** 위 컴파일 줄이 `| grep -E "error:"`로 끝나는데
# grep은 **찾은 것이 없으면 1**이다 — 즉 pipefail을 켜면 *오류 0인 정상 컴파일*이 실패로
# 뒤집힌다. 그래서 이 줄에만 PIPESTATUS를 쓴다.
# shellcheck disable=SC2086
java -cp "$OUT:$CP" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -vE "^Picked up JAVA_TOOL_OPTIONS" | tail -25
STATUS=${PIPESTATUS[0]}   # 바로 다음 명령이 배열을 덮으므로 이 자리에서 받아 둔다
if [ "$STATUS" -ne 0 ]; then
  echo "시험 실패 — JUnitCore 종료 코드 $STATUS (위 출력은 tail -25로 잘렸을 수 있다)" >&2
fi
exit "$STATUS"
