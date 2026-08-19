#!/bin/bash
# 순수 JVM 검증 환경 재구축 — jar 수집 + androidx 스텁 컴파일.
#
# **왜 스크립트인가:** jar과 스텁은 세션 스크래치패드에 있어 세션이 바뀌면 사라진다.
# 매번 손으로 다시 만들면 같은 함정에 같은 시간을 쓴다(아래 두 가지는 실제로 두 번 막혔다).
#
# 사용법:
#   JARS_DIR=/path/to/scratchpad tools/setup_jvm_env.sh
#   JARS_DIR=/path/to/scratchpad tools/run_jvm_tests.sh
#
# 전제: Maven Central 접근 가능(dl.google.com은 차단돼 있어도 무관),
#       /opt/gradle-8.14.3/lib 에 kotlin-compiler-embeddable·kotlin-stdlib·trove4j 존재.
set -eu

SP="${JARS_DIR:?JARS_DIR를 지정하세요 (예: 세션 스크래치패드 경로)}"
G="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
# jar 버전의 단일 소스 — POI·gson은 앱의 build.gradle.kts에서 읽는다(B-84).
. "$(cd "$(dirname "$0")" && pwd)/jvm_env_versions.sh"
mkdir -p "$SP"
cd "$SP"

# ── 1. 코틀린 컴파일러 (Gradle 배포본에서 복사) ──
cp -n "$G/kotlin-compiler-embeddable-2.0.21.jar" . 2>/dev/null || true
cp -n "$G/kotlin-stdlib-2.0.21.jar" . 2>/dev/null || true
cp -n "$G"/trove4j-*.jar trove4j.jar 2>/dev/null || true

# ── 2. 나머지 의존 (Maven Central) ──
M=https://repo1.maven.org/maven2
dl() { [ -f "$2" ] || { curl -sSfL -o "$2.part" "$1" && mv "$2.part" "$2"; }; }

dl $M/org/jetbrains/annotations/13.0/annotations-13.0.jar annotations-13.0.jar
dl $M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar kotlinx-coroutines-core-jvm.jar
dl $M/org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/1.9.0/kotlinx-coroutines-test-jvm-1.9.0.jar kotlinx-coroutines-test-jvm-1.9.0.jar
dl $M/junit/junit/4.13.2/junit-4.13.2.jar junit-4.13.2.jar
dl $M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar hamcrest-core-1.3.jar
dl $M/org/json/json/20240303/json-20240303.jar json-20240303.jar
# 버전은 전부 `tools/jvm_env_versions.sh`가 정한다 — POI·gson은 **앱이 정하고**(B-84),
# 나머지는 그 파일이 든다. 여기 숫자를 다시 적으면 그 순간 두 벌이 되어 갈린다.
dl $M/com/google/code/gson/gson/$GSON_VER/gson-$GSON_VER.jar gson-$GSON_VER.jar
dl $M/org/apache/poi/poi/$POI_VER/poi-$POI_VER.jar poi-$POI_VER.jar
dl $M/org/apache/poi/poi-ooxml/$POI_VER/poi-ooxml-$POI_VER.jar poi-ooxml-$POI_VER.jar
dl $M/org/apache/poi/poi-ooxml-lite/$POI_VER/poi-ooxml-lite-$POI_VER.jar poi-ooxml-lite-$POI_VER.jar
dl $M/org/apache/commons/commons-collections4/$COMMONS_COLLECTIONS4_VER/commons-collections4-$COMMONS_COLLECTIONS4_VER.jar commons-collections4-$COMMONS_COLLECTIONS4_VER.jar
dl $M/commons-io/commons-io/$COMMONS_IO_VER/commons-io-$COMMONS_IO_VER.jar commons-io-$COMMONS_IO_VER.jar
dl $M/org/apache/commons/commons-compress/$COMMONS_COMPRESS_VER/commons-compress-$COMMONS_COMPRESS_VER.jar commons-compress-$COMMONS_COMPRESS_VER.jar
dl $M/org/apache/xmlbeans/xmlbeans/$XMLBEANS_VER/xmlbeans-$XMLBEANS_VER.jar xmlbeans-$XMLBEANS_VER.jar
dl $M/org/apache/logging/log4j/log4j-api/$LOG4J_API_VER/log4j-api-$LOG4J_API_VER.jar log4j-api-$LOG4J_API_VER.jar
dl $M/org/apache/commons/commons-lang3/$COMMONS_LANG3_VER/commons-lang3-$COMMONS_LANG3_VER.jar commons-lang3-$COMMONS_LANG3_VER.jar
dl $M/com/zaxxer/SparseBitSet/$SPARSEBITSET_VER/SparseBitSet-$SPARSEBITSET_VER.jar SparseBitSet-$SPARSEBITSET_VER.jar

echo "jar 준비 완료: $(ls *.jar | wc -l)개"

# ── 3. androidx 스텁 ──
# 여기서 막히기 쉬운 곳 셋(전부 실제로 막혔던 것):
#  (1) @ForeignKey에 CASCADE/SET_NULL 등 companion 상수가 있어야 한다.
#  (2) @Ignore의 @Target에 PROPERTY_GETTER가 있어야 Character.displayName이 컴파일된다.
#  (3) @Relation.associateBy는 **KClass**여야 한다(Any::class 타입으로 두면 "invalid type of
#      annotation member"). 그리고 LiveData는 `open var value` **하나로** 둬야 한다 —
#      getValue()를 따로 선언하면 JVM 시그니처 충돌로 컴파일이 죽는다.
mkdir -p "$SP/stub-src"
cat > "$SP/stub-src/AndroidxStubs.kt" <<'EOF'
package androidx.room

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val primaryKeys: Array<String> = [],
    val inheritSuperIndices: Boolean = false,
    val ignoredColumns: Array<String> = []
)

@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class Index(vararg val value: String, val name: String = "", val unique: Boolean = false)

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class ForeignKey(
    val entity: kotlin.reflect.KClass<*>,
    val parentColumns: Array<String>,
    val childColumns: Array<String>,
    val onDelete: Int = NO_ACTION,
    val onUpdate: Int = NO_ACTION,
    val deferred: Boolean = false
) {
    companion object {
        const val NO_ACTION = 1
        const val RESTRICT = 2
        const val SET_NULL = 3
        const val SET_DEFAULT = 4
        const val CASCADE = 5
    }
}

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD) @Retention(AnnotationRetention.BINARY)
annotation class PrimaryKey(val autoGenerate: Boolean = false)

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class ColumnInfo(
    val name: String = "[field-name]",
    val typeAffinity: Int = 1,
    val index: Boolean = false,
    val collate: Int = 1,
    val defaultValue: String = "[value-unspecified]"
)

@Target(
    AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER
)
@Retention(AnnotationRetention.BINARY)
annotation class Ignore

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Dao

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Query(val value: String)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Insert(val entity: kotlin.reflect.KClass<*> = Any::class, val onConflict: Int = 1)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Update(val entity: kotlin.reflect.KClass<*> = Any::class, val onConflict: Int = 1)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Delete(val entity: kotlin.reflect.KClass<*> = Any::class)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Transaction

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Upsert(val entity: kotlin.reflect.KClass<*> = Any::class)

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Embedded(val prefix: String = "")

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Relation(
    val entity: kotlin.reflect.KClass<*> = Any::class,
    val entityColumn: String,
    val parentColumn: String,
    val associateBy: kotlin.reflect.KClass<*> = Any::class
)

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Database(
    val entities: Array<kotlin.reflect.KClass<*>> = [],
    val views: Array<kotlin.reflect.KClass<*>> = [],
    val version: Int,
    val exportSchema: Boolean = true
)

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class TypeConverters(vararg val value: kotlin.reflect.KClass<*>)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class TypeConverter

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class DatabaseView(val value: String = "", val viewName: String = "")

object OnConflictStrategy {
    const val REPLACE = 1
    const val ABORT = 3
    const val IGNORE = 5
    const val NONE = 0
    const val ROLLBACK = 2
    const val FAIL = 4
}

// `RoomDatabase`와 `withTransaction`은 2026.08.17(B-190)에 들어왔다.
// **없는 동안 `db.withTransaction { … }`이 미해석이었고, 그러면 그 블록 안이 suspend 문맥이
// 아니게 된다** — `ExcelImportService.kt` 하나에서 `suspension functions can only be called
// within coroutine body.`가 55건 발행됐고(그 파일 오류의 8할), 그 소음 아래에서 진짜 오류를
// 가릴 수 없었다. `withTransaction`의 시그니처는 진짜와 같게 둔다 — **suspend 블록을 받는
// suspend 확장**이라는 성질이 바로 저 55건을 없애는 그 성질이다.
//
// `openHelper`는 2026.08.19(B-265)에 들어왔다. **`data/maintenance/`를 프로브 범위에 들이자
// 신규 오류 115건이 떴는데 그 전부가 이 한 줄의 그림자였다** — `db.openHelper`가 미해석이면
// 그 아래 `.writableDatabase.query(…)`도, 그 커서의 `getLong`·`isNull`도, 그것을 받는 람다의
// 타입 추론도 줄줄이 죽는다(B-190이 `ExcelExporter.kt`에서, B-251 ⓐ가 `StorageAnalyzer`에서
// 겪은 *수신 타입 하나가 아래를 통째로 죽이는* 그 모양). 수신 타입이 미해석인 동안에는
// **그 아래 진짜 오류가 접히는 것이 아니라 발행조차 되지 않으므로**, 목록에 넣고도 안 보는 자리가 된다.
abstract class RoomDatabase {
    open val openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper
        get() = throw UnsupportedOperationException("스텁 전용")
}

suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = block()
EOF

# `androidx.sqlite.db`·`android.database`는 위 `openHelper`가 가리키는 끝단이다 (B-265).
#
# ⚠️ **여기서 표면을 늘리면 로컬이 거짓 초록을 낸다** — 이 저장소가 `androidx.datastore`에서
# 실측으로 확인한 그대로다(B-251 ⓐ: 넓은 스텁이었으면 타입 불일치 일곱이 조용히 통과했다).
# 그래서 **지금 범위 안 코드가 실제로 부르는 것만** 적는다. 좁으면 나중에 *가짜 오류*로 드러나
# 그 자리에서 한 줄 보태면 되고, 넓으면 **아무 데서도 안 드러난다.**
#   - `query`/`execSQL`의 `bindArgs` 오버로드는 뺐다 — 범위 안에서 부르는 자리가 없다
#     (`AppDatabase.kt`의 마이그레이션이 쓰지만 그 파일은 프로브가 스스로 스텁으로 세운다).
#   - `Cursor`는 `moveToFirst`·`getColumnIndex`·`count` 따위를 두지 않는다 — 같은 이유다.
#   - `Cursor : java.io.Closeable`은 **진짜 그대로**이며 뺄 수 없다. 이 계층의 관용구가
#     `query(…).use { … }`이고, `use`는 `Closeable`의 확장이라 이 관계가 없으면 열일곱 자리가
#     전부 미해석이 된다.
cat > "$SP/stub-src/SqliteStubs.kt" <<'EOF'
package androidx.sqlite.db

interface SupportSQLiteOpenHelper {
    val readableDatabase: SupportSQLiteDatabase
    val writableDatabase: SupportSQLiteDatabase
}

interface SupportSQLiteDatabase {
    fun query(query: String): android.database.Cursor
    fun execSQL(sql: String)
}
EOF

cat > "$SP/stub-src/CursorStubs.kt" <<'EOF'
package android.database

// 진짜는 자바 인터페이스라 반환 타입이 플랫폼 타입(`String!`)이다. 널 허용으로 적으면
// 지금 코드가 치르지 않는 널 검사를 요구해 **가짜 오류**가 되고, 널 불가로 적으면
// 플랫폼 타입을 널 불가로 쓰는 현행 호출부와 같은 모양이 된다 — 뒤쪽을 고른다.
interface Cursor : java.io.Closeable {
    fun moveToNext(): Boolean
    fun isNull(columnIndex: Int): Boolean
    fun getInt(columnIndex: Int): Int
    fun getLong(columnIndex: Int): Long
    fun getString(columnIndex: Int): String
    override fun close()
}
EOF

cat > "$SP/stub-src/LifecycleStubs.kt" <<'EOF'
package androidx.lifecycle

open class LiveData<T> {
    open var value: T? = null
    open fun postValue(v: T) { value = v }
    open fun observeForever(o: Any) {}

    // observe 는 **함수 타입**으로 받아야 한다. Any 로 두면 관측 람다의 파라미터 타입이
    // 추론되지 않아 람다 **본문 전체**가 오류 타입으로 무너지고, 차분 컴파일에서 관측자
    // 안의 멀쩡한 코드가 새 오류로 잡힌다(실제로 겪은 오탐 — suspend 람다 전달이 거부됐다).
    open fun observe(owner: Any, observer: (T) -> Unit) {}
}

open class MutableLiveData<T> : LiveData<T> {
    constructor() : super()
    constructor(v: T) : super() { value = v }
}
EOF

CPC="$SP/kotlin-compiler-embeddable-2.0.21.jar:$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/kotlinx-coroutines-core-jvm.jar:$SP/trove4j.jar"
rm -rf "$SP/out-room"; mkdir -p "$SP/out-room"
java -cp "$CPC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib \
  -cp "$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar" \
  -d "$SP/out-room" "$SP/stub-src/AndroidxStubs.kt" "$SP/stub-src/LifecycleStubs.kt" \
                    "$SP/stub-src/SqliteStubs.kt" "$SP/stub-src/CursorStubs.kt" 2>&1 \
  | grep -E "error:" | head -20

STUBS=$(find "$SP/out-room" -name '*.class' | wc -l)
if [ "$STUBS" -eq 0 ]; then echo "스텁 컴파일 실패"; exit 1; fi
echo "스텁 클래스 ${STUBS}개 — 준비 완료"
echo
echo "다음: JARS_DIR=$SP tools/run_jvm_tests.sh"
