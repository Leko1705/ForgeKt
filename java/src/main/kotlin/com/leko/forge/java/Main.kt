package com.leko.forge.java

import com.leko.forge.java.extensions.PowerOperator
import com.leko.forge.java.extensions.Records
import com.leko.forge.java.extensions.Unless
import com.leko.forge.msi.render
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: forge-javac [options] File.java...
  --run              run main with the MSI interpreter (default)
  --run-jvm          compile to JVM bytecode and run it
  --emit msi|js|llvm write the translation to the file given by -o (or print it)
  -o <file>          output file for --emit
  --enable <list>    language extensions: records, unless, power"""

/** A command line front-end: `forge-javac --enable records Main.java`. */
fun main(args: Array<String>) {
    var mode = "run"
    var emit: String? = null
    var output: String? = null
    val extensions = ArrayList<JavaFeature>()
    val files = ArrayList<File>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i++]) {
            "--run" -> mode = "run"
            "--run-jvm" -> mode = "jvm"
            "--emit" -> { mode = "emit"; emit = args.getOrNull(i++) }
            "-o" -> output = args.getOrNull(i++)
            "--enable" -> for (name in args.getOrNull(i++).orEmpty().split(",")) extensions += when (name.trim()) {
                "records" -> Records
                "unless" -> Unless
                "power" -> PowerOperator
                else -> { System.err.println("unknown extension: $name"); exitProcess(2) }
            }
            "-h", "--help" -> { println(USAGE); return }
            else -> files += File(a)
        }
    }
    if (files.isEmpty()) { System.err.println(USAGE); exitProcess(2) }
    // load all known extensions, so their syntax is recognized and reported as "not enabled" if needed
    listOf(Records, Unless, PowerOperator).forEach { it.hashCode() }
    val result = JavaCompiler(JavaCompiler.standardFeatures + extensions).compile(files)
    if (!result.succeeded) {
        result.problems.forEach { System.err.println(it) }
        System.err.println("${result.problems.size} error${if (result.problems.size == 1) "" else "s"}")
        exitProcess(1)
    }
    when (mode) {
        "run" -> exitProcess(result.run())
        "jvm" -> result.loadJvm().call("\$main")
        else -> {
            val text = when (emit) {
                "msi" -> result.module.render()
                "js" -> "const program = ${result.toJavaScript()}({ println: s => console.log(s), print: s => process.stdout.write(s) });\nprogram.functions.\$main();\n"
                "llvm" -> result.toLlvm()
                else -> { System.err.println("--emit needs msi, js or llvm"); exitProcess(2) }
            }
            if (output == null) print(text) else File(output).writeText(text)
        }
    }
}
