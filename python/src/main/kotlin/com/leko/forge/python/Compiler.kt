package com.leko.forge.python

import com.leko.forge.ParseException
import com.leko.forge.parse
import com.leko.forge.syn
import com.leko.forge.tree
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A Python compiler made of [features]. It compiles to Python (the main target: extensions are
 * lowered to standard Python, and the result runs on CPython) and, as an option, to JavaScript:
 *
 * ```
 * val result = PythonCompiler(PythonCompiler.standardFeatures + MatchStatement).compile(File("main.py"))
 * result.problems.forEach(::println)
 * result.writePython(File("out"))   // python3 out/main.py
 * result.run()                      // the same, in a temporary directory
 * ```
 *
 * Compiling a file also compiles the modules it imports from its directory (see [ModuleFinder]).
 */
class PythonCompiler(val features: List<PythonFeature> = standardFeatures) {

    companion object {
        /** The Python language. */
        val standardFeatures: List<PythonFeature>
            get() = listOf(CoreExpressions, CoreStatements, CoreFunctions, CoreClasses, CoreExceptions, CoreComprehensions, FStrings,
                CoreImports, CoreAsync, CoreWith)

        internal val enabledFeatures: ThreadLocal<Set<PythonFeature>> = ThreadLocal.withInitial { emptySet() }

        /** Parses an expression on its own (for expressions inside f-strings). */
        fun parseExpression(source: String): PyExpr = PythonGrammar.expr.parse(source.trim(), PythonGrammar.skip)
    }

    init {
        features.forEach { it.hashCode() } // loads the features: they register their syntax and semantics
    }

    /** Parses a source file. [name] is its path for messages; see [PyModule] for the other parameters. */
    fun parse(source: String, name: String = "main.py", qualifiedName: String = "__main__", isPackage: Boolean = false): PyModule {
        val layout = Layout.of(source)
        val previous = enabledFeatures.get()
        enabledFeatures.set(features.toSet())
        try {
            return tree(PyModule(PythonGrammar.module.parse(layout.text, PythonGrammar.skip, source = name), name, layout, qualifiedName, isPackage))
        } finally {
            enabledFeatures.set(previous)
        }
    }

    /** Compiles a program file and the modules it imports from its directory. */
    fun compile(file: File): PyCompilation {
        val root = file.absoluteFile.parentFile
        return compile(file.readText(), file.name, DirectoryModuleFinder(root), root)
    }

    /**
     * Compiles a source file. With a [finder], the modules of the program it imports are compiled
     * too (and imports from them are checked); [sourceRoot] is where they come from, so that Python
     * finds modules imported in other ways when the program runs.
     */
    fun compile(source: String, name: String = "main.py", finder: ModuleFinder? = null, sourceRoot: File? = null): PyCompilation {
        val (main, mainProblems) = parseChecked(source, name, "__main__", false)
        if (main == null) return PyCompilation(emptyList(), mainProblems, features, sourceRoot)
        val modules = linkedMapOf("__main__" to main)
        val syntaxProblems = ArrayList<PyProblem>()
        if (finder != null) {
            val tried = HashSet<String>()
            val queue = ArrayDeque(listOf(main))
            while (queue.isNotEmpty()) {
                val m = queue.removeFirst()
                for (s in CoreImports.imports(m)) for (candidate in CoreImports.importedModules(m, s)) {
                    if (candidate in modules || !tried.add(candidate)) continue
                    val file = finder.find(candidate) ?: continue
                    val (parsed, problems) = parseChecked(file.source, file.path, candidate, file.isPackage)
                    syntaxProblems += problems
                    if (parsed != null) { modules[candidate] = parsed; queue += parsed }
                }
            }
        }
        val graph = ModuleGraph(modules)
        modules.values.forEach { it.graph = graph }
        return PyCompilation(modules.values.toList(), syntaxProblems + modules.values.flatMap { it.pyProblems }, features, sourceRoot)
    }

    /** Parses a module; syntax errors become problems. */
    private fun parseChecked(source: String, name: String, qualifiedName: String, isPackage: Boolean): Pair<PyModule?, List<PyProblem>> {
        fun failure(message: String, line: Int, column: Int) = null to listOf(PyProblem(message, null, "$name:$line:$column"))
        return try {
            parse(source, name, qualifiedName, isPackage) to emptyList()
        } catch (e: LayoutException) {
            val (line, column) = position(source, e.offset)
            val kind = if ("indent" in e.message.orEmpty()) "IndentationError" else "SyntaxError"
            failure("$kind: ${e.message}", line, column)
        } catch (e: ParseException) {
            val (line, column) = Layout.of(source).position(e.offset)
            val message = e.message!!.substringAfter(": ").replace("\\u0002", "INDENT").replace("\\u0003", "DEDENT")
                .replace("'\n'", "NEWLINE").replace("'\u0002'", "an indented block").replace("'\u0003'", "DEDENT").replace("found '\n'", "found end of line")
            failure("SyntaxError: $message", line, column)
        } catch (e: FeatureDisabled) {
            val (line, column) = Layout.of(source).position(e.offset)
            failure("SyntaxError: ${e.message}", line, column)
        }
    }

    private fun position(source: String, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, source.length)
        return (source.substring(0, o).count { it == '\n' } + 1) to (o - (source.lastIndexOf('\n', o - 1) + 1) + 1)
    }
}

/** The JavaScript of a module: a function running its statements. */
val PyModule.jsModule: String by syn {
    val locals = info.locals
    val declarations = if (locals.isEmpty()) "" else "let ${locals.joinToString { "m_$it" }};\n"
    "function \$module() {\n${indent(declarations + block(body))}\n}"
}

/** A construct the chosen target does not support (see [JsUnsupported]). */
class UnsupportedOnTarget(val problem: PyProblem) : RuntimeException(problem.toString())

/**
 * The result of a compilation: the [modules] of the program (the main module first) and the
 * [problems] found. The main target is Python ([toPython], [writePython], [run]); JavaScript
 * ([toJavaScript], [runJavaScript]) supports single modules without coroutines.
 */
class PyCompilation internal constructor(
    val modules: List<PyModule>,
    val problems: List<PyProblem>,
    private val features: List<PythonFeature>,
    private val sourceRoot: File?,
) {
    /** The main module (`null` if it has syntax errors). */
    val module: PyModule? get() = modules.firstOrNull()
    val succeeded: Boolean get() = module != null && problems.isEmpty()

    private fun requireSuccess(): PyModule {
        val m = module
        check(m != null && problems.isEmpty()) { "compilation failed:\n" + problems.joinToString("\n") }
        return m
    }

    // ---- Python ------------------------------------------------------------------------------

    /** The Python code of the main module (extensions lowered to standard Python). */
    fun toPython(): String = pythonModule(requireSuccess(), features)

    /** The Python files of the program: path (relative to the output directory) to code. */
    fun pythonFiles(): Map<String, String> {
        val main = requireSuccess()
        val files = LinkedHashMap<String, String>()
        files[File(main.name).name] = pythonModule(main, features)
        for (m in modules.drop(1)) files[m.name] = pythonModule(m, features)
        // packages whose __init__.py is missing in the source are namespace packages: nothing to write
        return files
    }

    /** Writes the program to [dir]. Returns the main file (run it with `python3`). */
    fun writePython(dir: File): File {
        val files = pythonFiles()
        for ((path, code) in files) File(dir, path).also { it.parentFile.mkdirs() }.writeText(code)
        return File(dir, files.keys.first())
    }

    /**
     * Runs the program with CPython ([python]), with [args] as `sys.argv[1:]`. Returns the exit
     * status; stdout goes to [out], stderr to [err], stdin is empty.
     */
    fun run(out: (String) -> Unit = { print(it) }, err: (String) -> Unit = { System.err.print(it) }, args: List<String> = emptyList()): Int =
        inTemporaryDirectory { dir ->
            val main = writePython(dir)
            execute(pythonProcess(main, args), out, err)
        }

    /** Runs the program with CPython, connected to this process's stdin, stdout and stderr. */
    fun runInteractive(args: List<String> = emptyList()): Int = inTemporaryDirectory { dir ->
        val main = writePython(dir)
        pythonProcess(main, args).inheritIO().start().waitFor()
    }

    private fun pythonProcess(main: File, args: List<String>): ProcessBuilder {
        val builder = ProcessBuilder(listOf(python(), "-B", main.path) + args)
        // modules the compiler did not see (e.g. imported with importlib) still come from the sources
        if (sourceRoot != null) {
            val path = listOfNotNull(System.getenv("PYTHONPATH")?.takeIf { it.isNotEmpty() }, sourceRoot.path).joinToString(File.pathSeparator)
            builder.environment()["PYTHONPATH"] = path
        }
        return builder
    }

    // ---- JavaScript --------------------------------------------------------------------------

    /**
     * A standalone JavaScript program: the runtime and the module. Run it with `node`. Throws
     * [UnsupportedOnTarget] for constructs the JavaScript backend does not implement.
     */
    fun toJavaScript(): String {
        val m = requireSuccess()
        val module = try {
            m.jsModule
        } catch (e: JsUnsupported) {
            throw UnsupportedOnTarget(PyProblem("NotImplementedError: ${e.message}", e.node))
        }
        return "\"use strict\";\n// generated by Forge from ${m.name}\n(function () {\n${PythonRuntime.code}\n$module\n\$run(\$module);\n})();\n"
    }

    /** Runs the JavaScript program with Node.js. Returns the exit status; stdout goes to [out], stderr to [err]. */
    fun runJavaScript(out: (String) -> Unit = { print(it) }, err: (String) -> Unit = { System.err.print(it) }): Int {
        val js = toJavaScript()
        return inTemporaryDirectory { dir ->
            val file = File(dir, "program.js")
            file.writeText(js)
            execute(ProcessBuilder(node(), file.path), out, err)
        }
    }

    companion object {
        /** The Python interpreter: `$FORGE_PYTHON`, or `python3` (or `python`) on the PATH. */
        fun python(): String = System.getenv("FORGE_PYTHON")?.takeIf { it.isNotEmpty() }
            ?: onPath("python3") ?: onPath("python") ?: error("Python (python3) was not found")

        /** The `node` executable: on the PATH, or downloaded by Gradle. */
        fun node(): String = onPath("node")
            ?: File(System.getProperty("user.home"), ".gradle/nodejs").listFiles()?.map { File(it, "bin/node") }?.firstOrNull { it.canExecute() }?.path
            ?: error("Node.js (node) was not found")

        private fun onPath(name: String): String? =
            System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }?.path

        private fun <T> inTemporaryDirectory(action: (File) -> T): T {
            val dir = Files.createTempDirectory("forge-python").toFile()
            try {
                return action(dir)
            } finally {
                dir.deleteRecursively()
            }
        }

        private fun execute(builder: ProcessBuilder, out: (String) -> Unit, err: (String) -> Unit): Int {
            val process = builder.start()
            process.outputStream.close()
            val stderr = StringBuilder()
            val errors = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }.also { it.start() }
            process.inputStream.bufferedReader().use { r -> val buffer = CharArray(4096); while (true) { val n = r.read(buffer); if (n < 0) break; out(String(buffer, 0, n)) } }
            process.waitFor(120, TimeUnit.SECONDS)
            errors.join()
            if (stderr.isNotEmpty()) err(stderr.toString())
            return process.exitValue()
        }
    }
}
