// ---------------------------------------------------------------------------
// Tiny reflective JUnit runner for the offline harness.
//
// Scans a directory of compiled classes for *Test classes, instantiates each one,
// runs @Before then every @Test method, and prints a JUnit-style report.
// Exit code is non-zero if anything failed, so it can be used in CI.
// ---------------------------------------------------------------------------
import java.io.File

private class Failure(val className: String, val method: String, val error: Throwable)

fun main(args: Array<String>) {
    val classesDir = File(args.getOrElse(0) { "build/jvm-verify/classes" })
    val sourceDir = File(args.getOrElse(1) { "app/src/main/java" })
    if (!classesDir.isDirectory) {
        System.err.println("No compiled classes at ${classesDir.absolutePath}")
        kotlin.system.exitProcess(2)
    }

    val classNames = ArrayList<String>()
    classesDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith("Test.class") && !it.name.contains('$') }
        .forEach { file ->
            val relative = file.relativeTo(classesDir).path
            classNames += relative.removeSuffix(".class").replace(File.separatorChar, '.')
        }
    classNames.sort()

    // The compiled classes are already on the JVM classpath (see run-tests.sh), so
    // plain reflection is enough - no custom class loader needed.
    var passed = 0
    var skipped = 0
    val failures = ArrayList<Failure>()

    for (name in classNames) {
        val clazz = try {
            Class.forName(name)
        } catch (t: Throwable) {
            failures += Failure(name, "<load>", t)
            continue
        }
        if (clazz.getAnnotation(org.junit.Ignore::class.java) != null) {
            println("SKIPPED $name (class ignored)")
            skipped++
            continue
        }
        val testMethods = clazz.declaredMethods.filter { it.getAnnotation(org.junit.Test::class.java) != null }
        if (testMethods.isEmpty()) continue
        val beforeMethods = clazz.declaredMethods.filter { it.getAnnotation(org.junit.Before::class.java) != null }
        println("---- $name (${testMethods.size} tests)")
        for (method in testMethods.sortedBy { it.name }) {
            if (method.getAnnotation(org.junit.Ignore::class.java) != null) {
                println("  SKIP  ${method.name}")
                skipped++
                continue
            }
            val instance = try {
                clazz.getDeclaredConstructor().newInstance()
            } catch (t: Throwable) {
                failures += Failure(name, method.name, t.cause ?: t)
                println("  FAIL  ${method.name}  (could not instantiate)")
                continue
            }
            try {
                beforeMethods.forEach { it.invoke(instance) }
                method.invoke(instance)
                println("  PASS  ${method.name}")
                passed++
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                failures += Failure(name, method.name, cause)
                println("  FAIL  ${method.name}  -> ${cause.message?.lineSequence()?.firstOrNull()}")
            }
        }
    }

    println()
    println("==================================================================")
    println("Tests run: ${passed + failures.size}, passed: $passed, failed: ${failures.size}, skipped: $skipped")
    if (failures.isNotEmpty()) {
        println()
        for (failure in failures) {
            println("FAILED ${failure.className}.${failure.method}")
            println("       ${failure.error}")
            failure.error.stackTrace.take(6).forEach { println("         at $it") }
        }
        kotlin.system.exitProcess(1)
    }
    println("ALL TESTS PASSED  (source tree verified: ${sourceDir.path})")
}
