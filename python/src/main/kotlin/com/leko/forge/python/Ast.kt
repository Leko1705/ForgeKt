package com.leko.forge.python

import com.leko.forge.Child

/*
 * The Python syntax tree. The interfaces are open: features (also from other modules) add node types
 * and give them meaning with attributes (see PythonFeature). Nodes are always distinct objects.
 */

interface PyNode
interface PyStmt : PyNode
interface PyExpr : PyNode

/** Nodes with their own namespace: modules, functions, lambdas, classes, comprehensions. */
interface ScopeNode : PyNode

/**
 * A source file. [name] is its path (relative to the source root, e.g. `pkg/util.py`), [layout] maps
 * positions back to the source. [qualifiedName] is the dotted module name (`pkg.util`; `__main__` for
 * the program); a package's module is its `__init__.py`.
 */
class PyModule(
    @Child val body: List<PyStmt>,
    val name: String,
    val layout: Layout,
    val qualifiedName: String = "__main__",
    val isPackage: Boolean = false,
) : ScopeNode {
    /** The modules of the program this module was compiled with (`null` if compiled on its own). */
    var graph: ModuleGraph? = null
        internal set

    /** The package relative imports start from (`""` for top-level modules). */
    val packageName: String get() = if (isPackage) qualifiedName else qualifiedName.substringBeforeLast('.', "")
}

// ---- statements ----------------------------------------------------------------------------------

data class ExprStmt(@Child val value: PyExpr) : PyStmt
/** `a = b = value`: several targets. */
data class Assign(@Child val targets: List<PyExpr>, @Child val value: PyExpr) : PyStmt
data class AugAssign(@Child val target: PyExpr, val op: String, @Child val value: PyExpr) : PyStmt
/** `x: int = 5`; the annotation is evaluated, then ignored. */
data class AnnAssign(@Child val target: PyExpr, @Child val annotation: PyExpr, @Child val value: PyExpr?) : PyStmt
data class If(@Child val test: PyExpr, @Child val body: List<PyStmt>, @Child val orElse: List<PyStmt>) : PyStmt

/** `while` and `for`; `else` runs if the loop was not left with `break`. */
interface LoopStmt : PyStmt {
    val body: List<PyStmt>
    val orElse: List<PyStmt>
}
data class While(@Child val test: PyExpr, @Child override val body: List<PyStmt>, @Child override val orElse: List<PyStmt>) : LoopStmt
/** `for` (and `async for`, see [CoreAsync]). */
data class For(@Child val target: PyExpr, @Child val iter: PyExpr, @Child override val body: List<PyStmt>, @Child override val orElse: List<PyStmt>, val isAsync: Boolean = false) : LoopStmt

class Break : PyStmt { override fun toString(): String = "break" }
class Continue : PyStmt { override fun toString(): String = "continue" }
class Pass : PyStmt { override fun toString(): String = "pass" }
data class Return(@Child val value: PyExpr?) : PyStmt
data class Global(val names: List<String>) : PyStmt
data class Nonlocal(val names: List<String>) : PyStmt
data class Delete(@Child val targets: List<PyExpr>) : PyStmt
data class Assert(@Child val test: PyExpr, @Child val message: PyExpr?) : PyStmt
data class Raise(@Child val exception: PyExpr?, @Child val cause: PyExpr?) : PyStmt
data class Try(@Child val body: List<PyStmt>, @Child val handlers: List<ExceptHandler>, @Child val orElse: List<PyStmt>, @Child val finalBody: List<PyStmt>) : PyStmt
/** `except Type as name:`; [isGroup] for `except*` (exception groups). */
data class ExceptHandler(@Child val type: PyExpr?, val name: String?, @Child val body: List<PyStmt>, val isGroup: Boolean = false) : PyNode

/** `with a as x, b:` (and `async with`). */
data class With(@Child val items: List<WithItem>, @Child val body: List<PyStmt>, val isAsync: Boolean = false) : PyStmt
data class WithItem(@Child val context: PyExpr, @Child val target: PyExpr?) : PyNode

/** `name as asName` in imports; `*` in `from m import *`. */
data class Alias(val name: String, val asName: String?) : PyNode
/** `import a.b as c, d` */
data class Import(@Child val names: List<Alias>) : PyStmt
/** `from ..module import a as b`: [level] counts the dots, [moduleName] is `null` for `from . import x`. */
data class ImportFrom(val moduleName: String?, @Child val names: List<Alias>, val level: Int = 0) : PyStmt

/**
 * In the order of a parameter list: positional-only (before `/`), normal, `*args`, keyword-only
 * (after `*` or `*args`), `**kwargs`.
 */
enum class ParamKind { POSONLY, NORMAL, VARARGS, KWONLY, KWARGS }
data class Param(val name: String, @Child val default: PyExpr?, val kind: ParamKind = ParamKind.NORMAL, @Child val annotation: PyExpr? = null) : PyNode

data class FunctionDef(
    val name: String,
    @Child val params: List<Param>,
    @Child val body: List<PyStmt>,
    @Child val decorators: List<PyExpr>,
    @Child val returns: PyExpr? = null,
    val isAsync: Boolean = false,
) : PyStmt, ScopeNode

/** `class C(Base, metaclass=M):`; [bases] may contain `*args`, [keywords] `**kwargs` (name `**`). */
data class ClassDef(
    val name: String,
    @Child val bases: List<PyExpr>,
    @Child val body: List<PyStmt>,
    @Child val decorators: List<PyExpr>,
    @Child val keywords: List<Keyword> = emptyList(),
) : PyStmt, ScopeNode

// ---- expressions ---------------------------------------------------------------------------------

data class Name(val id: String) : PyExpr

enum class ConstantKind { INT, FLOAT, STR, BYTES, BOOL, NONE, ELLIPSIS }
/**
 * A literal. Integers keep their decimal digits (Python integers are unbounded); bytes are a string
 * of the characters 0 to 255.
 */
data class Constant(val value: Any?, val kind: ConstantKind) : PyExpr

data class BinOp(val op: String, @Child val left: PyExpr, @Child val right: PyExpr) : PyExpr
data class UnaryOp(val op: String, @Child val operand: PyExpr) : PyExpr
/** `and` / `or`: they return one of their operands. */
data class BoolOp(val op: String, @Child val left: PyExpr, @Child val right: PyExpr) : PyExpr
/** `a < b <= c`: a chain of comparisons. */
data class Compare(@Child val left: PyExpr, val ops: List<String>, @Child val comparators: List<PyExpr>) : PyExpr
data class IfExp(@Child val test: PyExpr, @Child val body: PyExpr, @Child val orElse: PyExpr) : PyExpr
data class Lambda(@Child val params: List<Param>, @Child val body: PyExpr) : PyExpr, ScopeNode

data class Keyword(val name: String, @Child val value: PyExpr) : PyNode
data class Call(@Child val func: PyExpr, @Child val args: List<PyExpr>, @Child val keywords: List<Keyword>) : PyExpr
data class Attribute(@Child val value: PyExpr, val attr: String) : PyExpr
data class Subscript(@Child val value: PyExpr, @Child val index: PyExpr) : PyExpr
data class Slice(@Child val lower: PyExpr?, @Child val upper: PyExpr?, @Child val step: PyExpr?) : PyExpr
/** `*value` in calls, displays and assignment targets. */
data class Starred(@Child val value: PyExpr) : PyExpr

data class ListExpr(@Child val elements: List<PyExpr>) : PyExpr
data class TupleExpr(@Child val elements: List<PyExpr>) : PyExpr
data class SetExpr(@Child val elements: List<PyExpr>) : PyExpr
/** `{k: v, **other}`: an unpacked mapping has a [DictUnpack] key and the mapping as value. */
data class DictExpr(@Child val keys: List<PyExpr>, @Child val values: List<PyExpr>) : PyExpr
class DictUnpack : PyExpr { override fun toString(): String = "**" }

enum class ComprehensionKind { LIST, SET, DICT, GENERATOR }
data class CompFor(@Child val target: PyExpr, @Child val iter: PyExpr, @Child val conditions: List<PyExpr>, val isAsync: Boolean = false) : PyNode
/** `[element for ... if ...]`; [value] is the value of dict comprehensions. */
data class Comprehension(val kind: ComprehensionKind, @Child val element: PyExpr, @Child val value: PyExpr?, @Child val generators: List<CompFor>) : PyExpr, ScopeNode

data class Yield(@Child val value: PyExpr?) : PyExpr
data class YieldFrom(@Child val value: PyExpr) : PyExpr

/** `await value` */
data class Await(@Child val value: PyExpr) : PyExpr
/** `(name := value)`: binds in the enclosing function, also from inside a comprehension. */
data class NamedExpr(@Child val target: Name, @Child val value: PyExpr) : PyExpr
