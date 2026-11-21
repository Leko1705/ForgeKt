package com.leko.forge.python

import com.leko.forge.childrenOf
import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.python.PythonGrammar.name
import com.leko.forge.python.PythonGrammar.simpleStmt
import java.io.File

/**
 * `import a.b as c` and `from ..m import x as y, *`. Modules of the program (found by a
 * [ModuleFinder], e.g. next to the main file) are compiled too, with the same features, so imported
 * names can be checked; other modules (the standard library, installed packages) are left to Python.
 */
object CoreImports : PythonFeature("imports") {

    private val dottedName by rule { (listOf(name()) + many { +"."; name() }).joinToString(".") }
    private val moduleAlias by rule { val n = dottedName(); Alias(n, optional { +"as"; name() }) }
    private val nameAlias by rule { val n = name(); Alias(n, optional { +"as"; name() }) }
    private val names by rule {
        val items = sepBy(nameAlias, ",")
        if (items.isEmpty()) fail("a name")
        items
    }

    private val importStmt: com.leko.forge.Rule<PyStmt> by rule {
        +"import"
        val modules = sepBy(moduleAlias, ",")
        if (modules.isEmpty()) fail("a module name")
        Import(modules)
    }
    private val fromStmt: com.leko.forge.Rule<PyStmt> by rule {
        +"from"
        val level = many { oneOf({ +"..."; 3 }, { +"."; 1 }) }.sum()
        val module = optional(dottedName)
        if (level == 0 && module == null) fail("a module name")
        +"import"
        val imported = oneOf(
            { +"*"; listOf(Alias("*", null)) },
            { +"("; val n = names(); optional { +"," }; +")"; n },
            { names() },
        )
        ImportFrom(module, imported, level)
    }

    init {
        simpleStmt.on { importStmt() }.on { fromStmt() }
    }

    /** Features of `from __future__ import ...`. */
    private val futureFeatures = setOf("nested_scopes", "generators", "division", "absolute_import", "with_statement",
        "print_function", "unicode_literals", "barry_as_FLUFL", "generator_stop", "annotations")

    // ---- binding -----------------------------------------------------------------------------

    /** `import a.b` binds `a`; `import a.b as c` binds `c`. */
    private val Import.boundNames: List<String> by syn { names.map { it.asName ?: it.name.substringBefore('.') } }
    private val ImportFrom.boundNames: List<String> by syn { names.filter { it.name != "*" }.map { it.asName ?: it.name } }

    // ---- checks ------------------------------------------------------------------------------

    init {
        problems
            .on<ImportFrom> { p ->
                val m = (this as PyNode).module
                when {
                    names.any { it.name == "*" } && scope !is PyModule -> p + problem("SyntaxError: import * only allowed at module level")
                    moduleName == "__future__" && !isAtStart(this, m) -> p + problem("SyntaxError: from __future__ imports must occur at the beginning of the file")
                    moduleName == "__future__" -> p + names.filter { it.name !in futureFeatures }.flatMap { problem("SyntaxError: future feature ${it.name} is not defined") }
                    else -> p + importFromProblems(this)
                }
            }
            .on<Import> { p ->
                val graph = module.graph ?: return@on p
                p + names.flatMap { a ->
                    val top = a.name.substringBefore('.')
                    // only modules of the program are checked: others may be installed
                    if (graph.modules[top] == null || graph.modules[a.name] != null) emptyList()
                    else problem("ModuleNotFoundError: No module named '${a.name}'")
                }
            }
    }

    private fun isAtStart(s: PyStmt, m: PyModule): Boolean {
        for ((i, stmt) in m.body.withIndex()) {
            if (stmt === s) return true
            val docstring = i == 0 && stmt is ExprStmt && stmt.value.let { it is Constant && it.kind == ConstantKind.STR }
            if (!docstring && !(stmt is ImportFrom && stmt.moduleName == "__future__")) return false
        }
        return false
    }

    private fun importFromProblems(s: ImportFrom): List<PyProblem> {
        val m = s.module
        val target = absoluteName(m, s.level, s.moduleName)
        if (s.level > 0 && target == null) {
            return if (m.packageName.isEmpty()) s.problem("ImportError: attempted relative import with no known parent package")
            else s.problem("ImportError: attempted relative import beyond top-level package")
        }
        val graph = m.graph ?: return emptyList()
        val imported = graph.modules[target!!]
        if (imported == null) {
            val top = target.substringBefore('.')
            return if (s.level > 0 || graph.modules[top] != null) s.problem("ModuleNotFoundError: No module named '$target'") else emptyList()
        }
        if (imported.hasStarImport) return emptyList()
        return s.names.filter { it.name != "*" }.filter { a ->
            a.name !in imported.info.bound && graph.modules["$target.${a.name}"] == null && a.name !in moduleAttributes
        }.flatMap { s.problem("ImportError: cannot import name '${it.name}' from '$target' (${imported.name})") }
    }

    /** Names every module has. */
    private val moduleAttributes = setOf("__name__", "__doc__", "__file__", "__spec__", "__loader__", "__package__", "__path__", "__dict__", "__all__")

    /** The absolute name of `from <dots><name> import`, or `null` if it goes beyond the top-level package. */
    fun absoluteName(m: PyModule, level: Int, name: String?): String? {
        if (level == 0) return name
        var base = m.packageName
        if (base.isEmpty()) return null
        repeat(level - 1) {
            if ('.' !in base) return null
            base = base.substringBeforeLast('.')
        }
        return if (name == null) base else "$base.$name"
    }

    /** The modules an import statement may load (packages first): candidates for local modules. */
    internal fun importedModules(m: PyModule, s: PyStmt): List<String> = when (s) {
        is Import -> s.names.flatMap { a -> a.name.split('.').runningReduce { acc, part -> "$acc.$part" } }
        is ImportFrom -> {
            val base = absoluteName(m, s.level, s.moduleName)
            if (base == null || base == "__future__") emptyList()
            else base.split('.').runningReduce { acc, part -> "$acc.$part" } + s.names.filter { it.name != "*" }.map { "$base.${it.name}" }
        }
        else -> emptyList()
    }

    /** All import statements of a module, also those inside functions and classes. */
    internal fun imports(n: Any): List<PyStmt> = childrenOf(n).flatMap { c -> (if (c is Import || c is ImportFrom) listOf(c as PyStmt) else emptyList()) + imports(c) }

    // ---- Python ------------------------------------------------------------------------------

    private fun pyAlias(a: Alias): String = a.name + (a.asName?.let { " as $it" } ?: "")

    private val Import.py: String by syn { "import " + names.joinToString(", ") { pyAlias(it) } }
    private val ImportFrom.py: String by syn { "from ${".".repeat(level)}${moduleName ?: ""} import " + names.joinToString(", ") { pyAlias(it) } }

    // ---- JavaScript --------------------------------------------------------------------------

    private val Import.js: String by syn { jsUnsupported(this, "import") }
    private val ImportFrom.js: String by syn { jsUnsupported(this, "import") }
}

/** The source of a module of the program. [path] is relative to the source root (`pkg/__init__.py`). */
class ModuleFile(val path: String, val source: String, val isPackage: Boolean)

/** Finds the modules of a program by their dotted names; `null` for modules outside the program. */
fun interface ModuleFinder {
    fun find(qualifiedName: String): ModuleFile?
}

/** Modules in a directory: `a.b` is `a/b.py` or the package `a/b/__init__.py`. */
class DirectoryModuleFinder(val root: File) : ModuleFinder {
    override fun find(qualifiedName: String): ModuleFile? {
        val relative = qualifiedName.replace('.', '/')
        val file = File(root, "$relative.py")
        val init = File(root, "$relative/__init__.py")
        return when {
            init.isFile -> ModuleFile("$relative/__init__.py", init.readText(), isPackage = true)
            file.isFile -> ModuleFile("$relative.py", file.readText(), isPackage = false)
            else -> null
        }
    }
}

/** The modules of a program by qualified name (the main module is `__main__`). */
class ModuleGraph internal constructor(val modules: Map<String, PyModule>)
