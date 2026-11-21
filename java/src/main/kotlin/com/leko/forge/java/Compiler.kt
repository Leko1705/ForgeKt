package com.leko.forge.java

import com.leko.forge.ParseException
import com.leko.forge.msi.Interpreter
import com.leko.forge.msi.Module
import com.leko.forge.msi.MsiThrownException
import com.leko.forge.msi.StructValue
import com.leko.forge.msi.js.toJavaScript
import com.leko.forge.msi.jvm.JvmProgram
import com.leko.forge.msi.jvm.loadJvm
import com.leko.forge.msi.llvm.toLlvm
import com.leko.forge.msi.validate
import com.leko.forge.parse
import com.leko.forge.tree
import java.io.File

/**
 * A Java compiler made of [features]. The standard features implement the supported Java subset;
 * extensions (e.g. [com.leko.forge.java.extensions.Records]) add language constructs:
 *
 * ```
 * val compiler = JavaCompiler(JavaCompiler.standardFeatures + Records)
 * val result = compiler.compile("Main.java" to source)
 * result.problems.forEach(::println)
 * result.run()
 * ```
 */
class JavaCompiler(val features: List<JavaFeature> = standardFeatures) {

    companion object {
        /** The Java language: types, expressions, statements, classes and exceptions. */
        val standardFeatures: List<JavaFeature> get() = listOf(CoreTypes, CoreExpressions, CoreStatements, CoreClasses, CoreExceptions)

        internal val enabledFeatures: ThreadLocal<Set<JavaFeature>> = ThreadLocal.withInitial { emptySet() }

        /** Library classes, written in Java and compiled with every program. */
        val PRELUDE: String = """
            class Throwable {
                private String message;
                Throwable() { }
                Throwable(String message) { this.message = message; }
                String getMessage() { return message; }
            }
            class Exception extends Throwable {
                Exception() { }
                Exception(String message) { super(message); }
            }
            class RuntimeException extends Exception {
                RuntimeException() { }
                RuntimeException(String message) { super(message); }
            }
            class IllegalArgumentException extends RuntimeException {
                IllegalArgumentException() { }
                IllegalArgumentException(String message) { super(message); }
            }
            class IllegalStateException extends RuntimeException {
                IllegalStateException() { }
                IllegalStateException(String message) { super(message); }
            }
            class ArithmeticException extends RuntimeException {
                ArithmeticException() { }
                ArithmeticException(String message) { super(message); }
            }
            class UnsupportedOperationException extends RuntimeException {
                UnsupportedOperationException() { }
                UnsupportedOperationException(String message) { super(message); }
            }
            class ClassCastException extends RuntimeException {
                ClassCastException() { }
                ClassCastException(String message) { super(message); }
            }
            class Math {
                static int max(int a, int b) { return a >= b ? a : b; }
                static long max(long a, long b) { return a >= b ? a : b; }
                static double max(double a, double b) { return a >= b ? a : b; }
                static int min(int a, int b) { return a <= b ? a : b; }
                static long min(long a, long b) { return a <= b ? a : b; }
                static double min(double a, double b) { return a <= b ? a : b; }
                static int abs(int a) { return a < 0 ? -a : a; }
                static long abs(long a) { return a < 0 ? -a : a; }
                static double abs(double a) { return a < 0 ? -a : a; }
            }
        """.trimIndent()
    }

    init {
        features.forEach { it.hashCode() } // loads the features, which registers their syntax and semantics
    }

    /** Parses one compilation unit. */
    fun parse(source: String, name: String? = null): CompilationUnit {
        val previous = enabledFeatures.get()
        enabledFeatures.set(features.toSet())
        JavaGrammar.currentSource.set(name)
        try {
            return JavaGrammar.compilationUnit.parse(source, JavaGrammar.skip, source = name)
        } finally {
            enabledFeatures.set(previous)
            JavaGrammar.currentSource.set(null)
        }
    }

    /** Compiles sources given as (file name, text). */
    fun compile(vararg sources: Pair<String, String>): Compilation {
        val units = ArrayList<CompilationUnit>()
        val parseProblems = ArrayList<JavaProblem>()
        for ((name, text) in listOf("<prelude>" to PRELUDE) + sources) {
            try {
                units += parse(text, name)
            } catch (e: ParseException) {
                val position = "$name:${e.line}:${e.column}"
                parseProblems += JavaProblem(e.message!!.removePrefix("$position: "), null, position)
            } catch (e: FeatureDisabled) {
                parseProblems += JavaProblem(e.message!!, null, e.position.toString())
            }
        }
        if (parseProblems.isNotEmpty()) return Compilation(null, parseProblems, null)
        val program = tree(Program(units))
        val problems = program.javaProblems
        if (problems.isNotEmpty()) return Compilation(program, problems, null)
        val module = program.msiModule
        val internal = module.validate()
        if (internal.isNotEmpty()) return Compilation(program, internal.map { JavaProblem("internal compiler error: $it", null) }, null)
        return Compilation(program, emptyList(), module)
    }

    /** Compiles files. */
    fun compile(files: List<File>): Compilation = compile(*files.map { it.name to it.readText() }.toTypedArray())
}

/** The result of compiling: [problems], or a [module] that can be run and translated. */
class Compilation internal constructor(
    val program: Program?,
    val problems: List<JavaProblem>,
    private val compiled: Module?,
) {
    val succeeded: Boolean get() = compiled != null

    /** The program as an MSI module (the input of all backends). */
    val module: Module get() = compiled ?: error("compilation failed:\n" + problems.joinToString("\n"))

    /** Runs `main` with the MSI interpreter; output goes to [out]. An uncaught exception is reported like the JVM does. */
    fun run(out: (String) -> Unit = { print(it) }): Int {
        val interpreter = Interpreter(module, mapOf(
            "println" to { args -> out(args[0] as String + "\n"); Unit },
            "print" to { args -> out(args[0] as String); Unit },
        ))
        return try {
            interpreter.call("\$main")
            0
        } catch (e: MsiThrownException) {
            out("Exception in thread \"main\" ${describe(e.value)}\n")
            1
        }
    }

    private fun describe(value: Any?): String {
        val obj = value as? StructValue ?: return value.toString()
        val id = obj[Names.CLASS_FIELD] as Int
        val name = program!!.classTable.classes.values.first { it.id == id }.name
        val message = obj["Throwable__message"]?.let { if (it == "") null else it }
        return if (message != null) "$name: $message" else name
    }

    /** The program as JavaScript (see `Module.toJavaScript`). */
    fun toJavaScript(): String = module.toJavaScript()

    /** The program as LLVM IR (see `Module.toLlvm`). */
    fun toLlvm(): String = module.toLlvm()

    /** The program compiled to JVM classes and loaded; `call("\$main")` runs it. */
    fun loadJvm(out: (String) -> Unit = { print(it) }): JvmProgram = module.loadJvm(mapOf(
        "println" to { args -> out(args[0] as String + "\n"); Unit },
        "print" to { args -> out(args[0] as String); Unit },
    ))
}
