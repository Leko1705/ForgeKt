package com.leko.forge.java

import com.leko.forge.msi.MsiThrownException
import com.leko.forge.msi.llvm.LLVM_RUNTIME_C
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Java programs, compiled to JVM bytecode, JavaScript and native code, must print the same. */
class JavaBackendsTest {

    private fun compile(name: String) = JavaCompiler().compile(Programs.source(name)).also { assertTrue(it.succeeded, it.problems.joinToString("\n")) }

    /** Without the line of an uncaught exception, whose format is runtime specific. */
    private fun expected(name: String) = Programs.expected.getValue(name).filterNot { it.startsWith("Exception in thread") }
    private fun throwsAtEnd(name: String) = Programs.expected.getValue(name).last().startsWith("Exception in thread")

    private fun exec(dir: File, vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).directory(dir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return process.exitValue() to output
    }

    private fun jvm(name: String) {
        val out = StringBuilder()
        val program = compile(name).loadJvm { out.append(it) }
        try {
            program.call("\$main")
            assertTrue(!throwsAtEnd(name))
        } catch (e: MsiThrownException) {
            assertTrue(throwsAtEnd(name), "unexpected exception ${e.value}")
        }
        assertEquals(expected(name), out.lines().dropLastWhile { it.isEmpty() })
    }

    private fun js(name: String) {
        val dir = Files.createTempDirectory("java-js").toFile()
        File(dir, "program.js").writeText("const program = ${compile(name).toJavaScript()}({ println: s => console.log(s), print: s => process.stdout.write(s) });\nprogram.functions.\$main();\n")
        val (exit, output) = exec(dir, node(), "program.js")
        assertEquals(throwsAtEnd(name), exit != 0)
        assertEquals(expected(name), output.lines().dropLastWhile { it.isEmpty() })
        dir.deleteRecursively()
    }

    private fun native(name: String) {
        val dir = Files.createTempDirectory("java-llvm").toFile()
        File(dir, "program.ll").writeText(compile(name).toLlvm())
        File(dir, "msi_runtime.c").writeText(LLVM_RUNTIME_C)
        File(dir, "host.c").writeText("""
            #include <stdint.h>
            #include <stdio.h>
            const char* msi_cstr(int64_t s);
            int64_t msi_ext_println(int64_t s) { puts(msi_cstr(s)); return 0; }
            int64_t msi_ext_print(int64_t s) { fputs(msi_cstr(s), stdout); return 0; }
            void msi_init(void);
            int64_t msi_f__main(void);
            int main(void) { setvbuf(stdout, 0, _IONBF, 0); msi_init(); msi_f__main(); return 0; }
        """.trimIndent())
        val (cc, ccOutput) = exec(dir, "clang", "-O0", "-Wno-override-module", "program.ll", "msi_runtime.c", "host.c", "-lm", "-o", "program")
        assertEquals(0, cc, ccOutput)
        val (exit, output) = exec(dir, "./program")
        assertEquals(throwsAtEnd(name), exit != 0)
        assertEquals(expected(name), output.lines().dropLastWhile { it.isEmpty() })
        dir.deleteRecursively()
    }

    private fun node(): String = System.getenv("PATH").split(File.pathSeparator).map { File(it, "node") }.firstOrNull { it.canExecute() }?.path
        ?: File(System.getProperty("user.home"), ".gradle/nodejs").listFiles()?.map { File(it, "bin/node") }?.firstOrNull { it.canExecute() }?.path
        ?: error("node not found")

    @Test fun basicsOnJvm() = jvm("Basics.java")
    @Test fun shapesOnJvm() = jvm("Shapes.java")
    @Test fun exceptionsOnJvm() = jvm("Exceptions.java")
    @Test fun algorithmsOnJvm() = jvm("Algorithms.java")
    @Test fun basicsOnJs() = js("Basics.java")
    @Test fun shapesOnJs() = js("Shapes.java")
    @Test fun exceptionsOnJs() = js("Exceptions.java")
    @Test fun algorithmsOnJs() = js("Algorithms.java")
    @Test fun basicsNative() = native("Basics.java")
    @Test fun shapesNative() = native("Shapes.java")
    @Test fun exceptionsNative() = native("Exceptions.java")
    @Test fun algorithmsNative() = native("Algorithms.java")
}
