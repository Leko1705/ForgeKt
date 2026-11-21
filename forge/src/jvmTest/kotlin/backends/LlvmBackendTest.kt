package backends

import com.leko.forge.msi.llvm.LLVM_RUNTIME_C
import com.leko.forge.msi.llvm.toLlvm
import com.leko.forge.mptest.Conformance
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compiles the conformance programs to native code with clang and runs them. */
class LlvmBackendTest {

    private val host = """
        #include <stdint.h>
        #include <stdio.h>
        const char* msi_cstr(int64_t s);
        int64_t msi_ext_print(int64_t s) { puts(msi_cstr(s)); return 0; }
        void msi_init(void);
        int64_t msi_f_main(void);
        int main(void) { msi_init(); msi_f_main(); return 0; }
    """.trimIndent()

    private fun exec(dir: File, vararg command: String): String {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        check(process.exitValue() == 0) { "${command.joinToString(" ")} failed:\n$output" }
        return output
    }

    private fun run(program: Conformance.Program, hostCode: String = host): List<String> {
        val dir = Files.createTempDirectory("msi-llvm").toFile()
        File(dir, "module.ll").writeText(program.module.toLlvm())
        File(dir, "msi_runtime.c").writeText(LLVM_RUNTIME_C)
        File(dir, "host.c").writeText(hostCode)
        try {
            exec(dir, "clang", "-O0", "-Wno-override-module", "module.ll", "msi_runtime.c", "host.c", "-lm", "-o", "program")
            return exec(dir, "./program").lines().dropLastWhile { it.isEmpty() }
        } catch (e: IllegalStateException) {
            throw AssertionError(e.message + "\n--- module.ll ---\n" + File(dir, "module.ll").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun arithmetic() = assertEquals(Conformance.arithmetic.expected, run(Conformance.arithmetic))
    @Test fun control() = assertEquals(Conformance.control.expected, run(Conformance.control))
    @Test fun data() = assertEquals(Conformance.data.expected, run(Conformance.data))
    @Test fun unions() = assertEquals(Conformance.unions.expected, run(Conformance.unions))
    @Test fun closures() = assertEquals(Conformance.closures.expected, run(Conformance.closures))
    @Test fun exceptions() = assertEquals(Conformance.exceptions.expected, run(Conformance.exceptions))
    @Test fun globals() = assertEquals(Conformance.globals.expected, run(Conformance.globals))
    @Test fun ownership() = assertEquals(Conformance.ownership.expected, run(Conformance.ownership))

    @Test
    fun memoryIsReclaimed() {
        val memoryHost = host.replace("int main(void) { msi_init(); msi_f_main(); return 0; }", """
            void msi_gc_collect(void);
            int64_t msi_live_objects(void);
            int64_t msi_live_linear(void);
            int main(void) {
                msi_init(); msi_f_main(); msi_gc_collect();
                printf("linear=%lld\n", (long long) msi_live_linear());
                printf("objects=%lld\n", (long long) msi_live_objects());
                return 0;
            }""".trimIndent())
        val output = run(Conformance.memory, memoryHost)
        assertEquals(Conformance.memory.expected + "linear=0", output.dropLast(1))
        val objects = output.last().removePrefix("objects=").toInt()
        kotlin.test.assertTrue(objects < 100, "shared garbage was not collected: $objects objects alive")
    }
}
