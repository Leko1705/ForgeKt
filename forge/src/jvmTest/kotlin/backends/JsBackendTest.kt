package backends

import com.leko.forge.msi.js.toJavaScript
import com.leko.forge.mptest.Conformance
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the conformance programs as JavaScript on Node.js. */
class JsBackendTest {

    private fun node(): String = System.getenv("PATH").split(File.pathSeparator)
        .map { File(it, "node") }.firstOrNull { it.canExecute() }?.path
        ?: File(System.getProperty("user.home"), ".gradle/nodejs").listFiles()?.map { File(it, "bin/node") }?.firstOrNull { it.canExecute() }?.path
        ?: error("node not found")

    private fun run(program: Conformance.Program): List<String> {
        val dir = Files.createTempDirectory("msi-js").toFile()
        val script = File(dir, "${program.module.name}.js")
        script.writeText("const program = ${program.module.toJavaScript()}({ print: s => console.log(s) });\nprogram.functions.main();\n")
        val process = ProcessBuilder(node(), script.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        assertEquals(0, process.exitValue(), "node failed:\n$output\n--- script ---\n${script.readText()}")
        dir.deleteRecursively()
        return output.lines().dropLastWhile { it.isEmpty() }
    }

    @Test fun arithmetic() = assertEquals(Conformance.arithmetic.expected, run(Conformance.arithmetic))
    @Test fun control() = assertEquals(Conformance.control.expected, run(Conformance.control))
    @Test fun data() = assertEquals(Conformance.data.expected, run(Conformance.data))
    @Test fun unions() = assertEquals(Conformance.unions.expected, run(Conformance.unions))
    @Test fun closures() = assertEquals(Conformance.closures.expected, run(Conformance.closures))
    @Test fun exceptions() = assertEquals(Conformance.exceptions.expected, run(Conformance.exceptions))
    @Test fun globals() = assertEquals(Conformance.globals.expected, run(Conformance.globals))
    @Test fun ownership() = assertEquals(Conformance.ownership.expected, run(Conformance.ownership))
}
