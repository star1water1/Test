// android.util.Log 자리만 채우는 스텁 (하네스 전용). Android SDK jar 없이 util 계층을 컴파일한다.
package android.util

object Log {
    @JvmStatic fun v(tag: String, msg: String): Int = 0
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int = 0
}
