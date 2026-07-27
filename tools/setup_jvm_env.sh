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
dl $M/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar gson-2.10.1.jar
dl $M/org/apache/poi/poi/5.2.5/poi-5.2.5.jar poi-5.2.5.jar
dl $M/org/apache/poi/poi-ooxml/5.2.5/poi-ooxml-5.2.5.jar poi-ooxml-5.2.5.jar
dl $M/org/apache/poi/poi-ooxml-lite/5.2.5/poi-ooxml-lite-5.2.5.jar poi-ooxml-lite-5.2.5.jar
dl $M/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar commons-collections4-4.4.jar
dl $M/commons-io/commons-io/2.15.0/commons-io-2.15.0.jar commons-io-2.15.0.jar
dl $M/org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar commons-compress-1.25.0.jar
dl $M/org/apache/xmlbeans/xmlbeans/5.2.0/xmlbeans-5.2.0.jar xmlbeans-5.2.0.jar
dl $M/org/apache/logging/log4j/log4j-api/2.21.1/log4j-api-2.21.1.jar log4j-api-2.21.1.jar
dl $M/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar commons-lang3-3.14.0.jar
dl $M/com/zaxxer/SparseBitSet/1.3/SparseBitSet-1.3.jar SparseBitSet-1.3.jar

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
EOF

cat > "$SP/stub-src/LifecycleStubs.kt" <<'EOF'
package androidx.lifecycle

open class LiveData<T> {
    open var value: T? = null
    open fun postValue(v: T) { value = v }
    open fun observeForever(o: Any) {}
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
  -d "$SP/out-room" "$SP/stub-src/AndroidxStubs.kt" "$SP/stub-src/LifecycleStubs.kt" 2>&1 \
  | grep -E "error:" | head -20

STUBS=$(find "$SP/out-room" -name '*.class' | wc -l)
if [ "$STUBS" -eq 0 ]; then echo "스텁 컴파일 실패"; exit 1; fi
echo "스텁 클래스 ${STUBS}개 — 준비 완료"
echo
echo "다음: JARS_DIR=$SP tools/run_jvm_tests.sh"
