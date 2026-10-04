// ---------------------------------------------------------------------------
// Minimal JUnit 4 API surface, used ONLY by tools/jvm-verify/run-tests.sh.
//
// The Android build never sees this directory: it is outside every Gradle source
// set. It exists so the exact same test sources in app/src/test can be compiled and
// executed on a machine that has a JDK but no Gradle, AGP or Android SDK.
//
// When you build with Gradle, the real junit:junit:4.13.2 artifact is used instead
// and these files are ignored.
// ---------------------------------------------------------------------------
package org.junit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test(val timeout: Long = 0, val expected: kotlin.reflect.KClass<out Throwable> = Throwable::class)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Before

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class After

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ignore(val value: String = "")

class AssertionErrorCompat(message: String?) : AssertionError(message)

object Assert {

    @JvmStatic
    fun assertTrue(message: String?, condition: Boolean) {
        if (!condition) throw AssertionErrorCompat(message ?: "Expected true")
    }

    @JvmStatic
    fun assertTrue(condition: Boolean) = assertTrue(null, condition)

    @JvmStatic
    fun assertFalse(message: String?, condition: Boolean) {
        if (condition) throw AssertionErrorCompat(message ?: "Expected false")
    }

    @JvmStatic
    fun assertFalse(condition: Boolean) = assertFalse(null, condition)

    @JvmStatic
    fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected != actual) {
            throw AssertionErrorCompat("${message ?: "Values differ"}\n  expected: <$expected>\n  actual:   <$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

    @JvmStatic
    fun assertEquals(message: String?, expected: Long, actual: Long) {
        if (expected != actual) {
            throw AssertionErrorCompat("${message ?: "Values differ"}\n  expected: <$expected>\n  actual:   <$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(expected: Long, actual: Long) = assertEquals(null, expected, actual)

    @JvmStatic
    fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
        if (kotlin.math.abs(expected - actual) > delta) {
            throw AssertionErrorCompat("${message ?: "Values differ"} expected <$expected> but was <$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(expected: Float, actual: Float, delta: Float) =
        assertEquals(null, expected, actual, delta)

    @JvmStatic
    fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) {
            throw AssertionErrorCompat("${message ?: "Values differ"} expected <$expected> but was <$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(expected: Double, actual: Double, delta: Double) =
        assertEquals(null, expected, actual, delta)

    @JvmStatic
    fun assertNull(message: String?, value: Any?) {
        if (value != null) throw AssertionErrorCompat(message ?: "Expected null but was <$value>")
    }

    @JvmStatic
    fun assertNull(value: Any?) = assertNull(null, value)

    @JvmStatic
    fun assertNotNull(message: String?, value: Any?) {
        if (value == null) throw AssertionErrorCompat(message ?: "Expected a value but was null")
    }

    @JvmStatic
    fun assertNotNull(value: Any?) = assertNotNull(null, value)

    @JvmStatic
    fun assertNotEquals(message: String?, unexpected: Any?, actual: Any?) {
        if (unexpected == actual) throw AssertionErrorCompat(message ?: "Expected a different value than <$actual>")
    }

    @JvmStatic
    fun assertNotEquals(unexpected: Any?, actual: Any?) = assertNotEquals(null, unexpected, actual)

    @JvmStatic
    fun assertSame(message: String?, expected: Any?, actual: Any?) {
        if (expected !== actual) throw AssertionErrorCompat(message ?: "Expected the same instance")
    }

    @JvmStatic
    fun fail(message: String?): Nothing = throw AssertionErrorCompat(message ?: "Failed")
}
