package com.leko.forge.python

import com.leko.forge.python.extensions.MatchStatement
import com.leko.forge.python.extensions.PipeOperator
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: forge-python [options] file.py [arguments...]
  --run              compile and run (default): Python with python3, JavaScript with node
  --emit             write the generated code: Python into the directory given by -o
                     (or print the main module), JavaScript into the file given by -o (or print it)
  --target <t>       python (default) or js
  -o <path>          output directory (Python) or file (JavaScript) for --emit
  --enable <list>    language extensions: match, pipe
  --check            only report problems

Arguments after the file are passed to the program (sys.argv)."""

/** A command line front-end: `forge-python --enable pipe main.py`. */
fun main(args: Array<String>) {
    var emit = false
    var check = false
    var js = false
    var output: String? = null
    val extensions = ArrayList<PythonFeature>()
    var file: File? = null
    var i = 0
    while (i < args.size && file == null) {
        when (val a = args[i++]) {
            "--run" -> emit = false
            "--emit" -> emit = true
            "--check" -> check = true
            "--target" -> js = when (val t = args.getOrNull(i++)) {
                "python", "py" -> false
                "js", "javascript" -> true
                else -> { System.err.println("unknown target: $t"); exitProcess(2) }
            }
            "-o" -> output = args.getOrNull(i++)
            "--enable" -> for (name in args.getOrNull(i++).orEmpty().split(",")) extensions += when (name.trim()) {
                "match" -> MatchStatement
                "pipe" -> PipeOperator
                else -> { System.err.println("unknown extension: $name"); exitProcess(2) }
            }
            "-h", "--help" -> { println(USAGE); return }
            else -> if (a.startsWith("-")) { System.err.println("unknown option: $a\n$USAGE"); exitProcess(2) } else file = File(a)
        }
    }
    val programArgs = args.drop(i)
    val source = file ?: run { System.err.println(USAGE); exitProcess(2) }
    if (!source.isFile) { System.err.println("forge-python: can't open file '$source'"); exitProcess(2) }
    // load all known extensions, so their syntax is recognized and reported as "not enabled" if needed
    listOf(MatchStatement, PipeOperator).forEach { it.hashCode() }
    val result = PythonCompiler(PythonCompiler.standardFeatures + extensions).compile(source)
    if (!result.succeeded) {
        result.problems.forEach { System.err.println(it) }
        exitProcess(1)
    }
    if (check) return
    try {
        when {
            emit && js -> { val code = result.toJavaScript(); if (output == null) print(code) else File(output).writeText(code) }
            emit -> if (output == null) print(result.toPython()) else result.writePython(File(output))
            js -> exitProcess(result.runJavaScript())
            else -> exitProcess(result.runInteractive(programArgs))
        }
    } catch (e: UnsupportedOnTarget) {
        System.err.println(e.problem)
        exitProcess(1)
    }
}
