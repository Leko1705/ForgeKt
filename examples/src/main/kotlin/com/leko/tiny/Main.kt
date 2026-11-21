package com.leko.tiny

import com.leko.forge.*
import com.leko.forge.msi.Interpreter
import com.leko.forge.msi.js.toJavaScript
import com.leko.forge.msi.jvm.loadJvm
import com.leko.forge.msi.llvm.toLlvm
import com.leko.forge.msi.render
import com.leko.forge.msi.validate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.Path

/*
 * Tiny: a small language that uses every Forge feature.
 *
 *   1. parser          text grammar with an abstract rule for operators and keywords, read from a file
 *   2. syn             expression types, with interface dispatch (Expr.type, Decl.declType)
 *   3. inh             name resolution (scope), the enclosing function, the expected return type
 *   4. coll + on       diagnostics collected from the whole tree
 *   5. extension       an extra module adds warnings to the same collection
 *   6. graph + flows   a control flow graph per function, with short-circuit && and !
 *   7. circular        liveness analysis as two fixpoint equations, used for the warnings
 *   8. binary parser   a binary "signature file" written and read back from an InputStream
 *   9. ErrorFormatter  syntax errors with a caret under the source line
 *  10. MSI             conversion to the Multi-System-Tree with syn (ToMsi.kt), checked and run
 */

// ==== 0. AST ====================================================================================

enum class Type { INT, BOOL, ERROR } // ERROR: the problem is already reported, don't report it again

sealed interface Node
data class Program(val funcs: List<Func>) : Node
data class Func(val name: String, val params: List<Param>, val ret: Type, val body: Block) : Node

sealed interface Decl : Node { val name: String }
data class Param(override val name: String, val declared: Type) : Decl

sealed interface Stmt : Node
data class Block(val stmts: List<Stmt>) : Stmt
data class VarDecl(override val name: String, val init: Expr) : Stmt, Decl
data class Assign(val name: String, val value: Expr) : Stmt
data class While(val cond: Expr, val body: Block) : Stmt
data class If(val cond: Expr, val thenBlock: Block, val elseBlock: Block?) : Stmt
data class Return(val value: Expr) : Stmt

sealed interface Expr : Node
data class Num(val value: Int) : Expr
data class Bool(val value: Boolean) : Expr
data class Name(val name: String) : Expr
data class Add(val l: Expr, val r: Expr) : Expr
data class Less(val l: Expr, val r: Expr) : Expr
data class And(val l: Expr, val r: Expr) : Expr
data class Not(val e: Expr) : Expr

// ==== 1. Parser =================================================================================

object TinyParser {
    /** Whitespace and `//` comments are skipped. */
    val skip = Regex("(\\s|//[^\\n]*)+")

    /** Keywords take precedence over identifiers: `return x` is never the variable `return`. */
    val keywords = Keywords("fun", "var", "while", "if", "else", "return", "true", "false", "int", "bool")
    val ident by token("[A-Za-z_][A-Za-z0-9_]*", except = keywords)
    val number by token("[0-9]+")

    val program by rule { Program(many(func)) }
    val func by rule {
        +"fun"; val name = ident()
        +"("; val params = sepBy(param, ","); +")"
        +":"; val ret = type()
        Func(name, params, ret, block())
    }
    val param by rule { val name = ident(); +":"; Param(name, type()) }
    val type by rule { oneOf({ +"int"; Type.INT }, { +"bool"; Type.BOOL }) }

    val block: Rule<Block> by rule { +"{"; val stmts = many(stmt); +"}"; Block(stmts) }
    val stmt: Rule<Stmt> by rule { oneOf(varDecl, whileStmt, ifStmt, returnStmt, assign) }
    val varDecl by rule { +"var"; val name = ident(); +"="; VarDecl(name, expr()) }
    val assign by rule { val name = ident(); +"="; Assign(name, expr()) }
    val whileStmt by rule { +"while"; val cond = expr(); While(cond, block()) }
    val ifStmt by rule { +"if"; val cond = expr(); If(cond, block(), optional { +"else"; block() }) }
    val returnStmt by rule { +"return"; Return(expr()) }

    /**
     * An abstract rule: alternatives and operators with precedence and associativity, instead of one
     * rule per precedence level. Extension modules can add more, e.g. `TinyParser.expr.infixLeft(30) { ... }`.
     */
    val expr: AbstractRule<Expr> by rule<Expr>()
        .on { +"true"; Bool(true) }
        .on { +"false"; Bool(false) }
        .on { Num(number().toInt()) }
        .on { Name(ident()) }
        .on { +"("; expr().also { +")" } }
        .infixLeft(10) { l -> +"&&"; And(l, operand()) }
        .infixLeft(20) { l -> +"<"; Less(l, operand()) }
        .infixLeft(30) { l -> +"+"; Add(l, operand()) }
        .prefix(40) { +"!"; Not(operand()) }
}

// ==== 9. Error messages =========================================================================

object CaretErrors : ErrorFormatter {
    override fun format(error: TextParseError) =
        "${prefix(error)}${error.line}:${error.column}: syntax error, expected ${describeExpected(error.expected)}\n" +
        "    ${error.lineText}\n" +
        "    ${" ".repeat(error.column - 1)}^"
}

// ==== 3. Inherited attributes ===================================================================

/** The declarations visible at a node. */
fun interface Scope { fun find(name: String): Decl? }

/**
 * Inherited with a receiver. The `child` parameter tells which statement of a block asks.
 * The attribute object is kept in `scopes`, so extension modules can add equations for their own
 * node types, e.g. a `for` loop that declares its variable: `scopes.on<For> { child -> ... }`.
 */
val scopes = inh<Scope>()
    .on<Func> { Scope { name -> params.lastOrNull { it.name == name } } }
    .on<Block> { child ->
        val outer = scope // equations run in the context of the parent: this is the block's own scope
        val before = stmts.takeWhile { it !== child }.filterIsInstance<VarDecl>()
        Scope { name -> before.lastOrNull { it.name == name } ?: outer.find(name) }
    }
val Node.scope: Scope by scopes

/** The function a node belongs to. */
val Node.function: Func by inh<Func>().on<Func> { this }

/** Inherited without a receiver: it applies to the node whose attribute is being evaluated. */
val expectedReturn: Type by inh<Type>().on<Func> { ret }

// ==== 2. Synthesized attributes =================================================================

val Expr.type: Type by syn()
val Num.type: Type by syn { Type.INT }
val Bool.type: Type by syn { Type.BOOL }
val Name.type: Type by syn { scope.find(name)?.declType ?: Type.ERROR }
val Add.type: Type by syn { if (l.type == Type.INT && r.type == Type.INT) Type.INT else Type.ERROR }
val Less.type: Type by syn { Type.BOOL }
val And.type: Type by syn { Type.BOOL }
val Not.type: Type by syn { Type.BOOL }

val Decl.declType: Type by syn()
val Param.declType: Type by syn { declared }
val VarDecl.declType: Type by syn { init.type }

// ==== 4. Collection: diagnostics ================================================================

data class Diagnostic(val severity: String, val function: String, val message: String) {
    override fun toString() = "$severity in fun $function: $message"
}

fun Node.error(message: String) = Diagnostic("error", function.name, message)
fun Node.warning(message: String) = Diagnostic("warning", function.name, message)

/** Reports [actual] unless it is fine or already reported. */
fun Node.expect(expected: Type, actual: Type, what: String) =
    if (actual == expected || actual == Type.ERROR) emptyList() else listOf(error("$what must be $expected, not $actual"))

val diagnostics = coll<List<Diagnostic>>(emptyList())
    .on<Name> { d -> if (scope.find(name) == null) d + error("unknown variable '$name'") else d }
    .on<Add> { d ->
        val bad = type == Type.ERROR && l.type != Type.ERROR && r.type != Type.ERROR
        if (bad) d + error("can not add ${l.type} and ${r.type}") else d
    }
    .on<Less> { d ->
        buildList {
            addAll(d)
            addAll(this@on.expect(Type.INT, this@on.l.type, "left side of <"))
            addAll(this@on.expect(Type.INT, this@on.r.type, "right side of <"))
        }
    }
    .on<And> { d ->
        buildList {
            addAll(d)
            addAll(this@on.expect(Type.BOOL, this@on.l.type, "left side of &&"))
            addAll(this@on.expect(Type.BOOL, this@on.r.type, "right side of &&"))
        }
    }
    .on<Not> { d -> d + expect(Type.BOOL, e.type, "operand of !") }
    .on<While> { d -> d + expect(Type.BOOL, cond.type, "condition") }
    .on<If> { d -> d + expect(Type.BOOL, cond.type, "condition") }
    .on<Return> { d -> d + expect(expectedReturn, value.type, "return value") }
    .on<Assign> { d ->
        val decl = scope.find(name) ?: return@on d + error("unknown variable '$name'")
        if (decl.declType == Type.ERROR) d else d + expect(decl.declType, value.type, "value of '$name'")
    }

val Program.diags by diagnostics

// ==== 6. Control flow graph =====================================================================

val onTrue = port("onTrue")
val onFalse = port("onFalse")
val ret = port("return", inherited = true) // leaves all enclosing statements; unbound: the function's exit

class PushTrue { override fun toString() = "push true" }
class PushFalse { override fun toString() = "push false" }

/** Leaves an expression vertex: with two labeled edges if used as a condition, else to `next`. */
fun FlowScope<*>.leave(v: Any) {
    if (bound(onTrue)) {
        v then onTrue label true
        v then onFalse label false
    } else v then next
}

val cfg = graph<Func>()
    .flow<Func> { f -> entry(f.body); f.body then next }
    .flow<Block> { b -> sequence(b.stmts) }
    .flow<VarDecl> { d -> entry(d.init); d.init then d; d then next }
    .flow<Assign> { a -> entry(a.value); a.value then a; a then next }
    .flow<Return> { r -> entry(r.value); r.value then r; r then ret }
    .flow<While> { (cond, body) ->
        entry(cond)
        onTrue(cond) then body
        onFalse(cond) then next
        body then cond
    }
    .flow<If> { s ->
        entry(s.cond)
        onTrue(s.cond) then s.thenBlock
        onFalse(s.cond) then (s.elseBlock ?: next)
        s.thenBlock then next
        s.elseBlock?.let { it then next }
    }
    .flow<Num> { entry(node); leave(node) }
    .flow<Bool> { entry(node); leave(node) }
    .flow<Name> { entry(node); leave(node) }
    .flow<Add> { (l, r) -> entry(l); l then r; r then node; leave(node) }
    .flow<Less> { (l, r) -> entry(l); l then r; r then node; leave(node) }
    .flow<And> { (l, r) ->
        entry(l)
        onTrue(l) then r
        if (bound(onTrue)) {           // used as a condition: pure jumps
            onFalse(l) then onFalse
            onTrue(r) then onTrue
            onFalse(r) then onFalse
        } else {                       // used as a value: produce true / false
            val t = vertex(PushTrue())
            val f = vertex(PushFalse())
            onFalse(l) then f
            onTrue(r) then t
            onFalse(r) then f
            t then next
            f then next
        }
    }
    .flow<Not> { (e) ->
        entry(e)
        if (bound(onTrue)) {           // as a condition: swap the branches, no vertex needed
            onTrue(e) then onFalse
            onFalse(e) then onTrue
        } else {
            e then node
            leave(node)
        }
    }

// ==== 7. Circular attributes: liveness ==========================================================

fun uses(v: Any): Set<String> = if (v is Name) setOf(v.name) else emptySet()
fun defs(v: Any): Set<String> = when (v) {
    is Assign -> setOf(v.name)
    is VarDecl -> setOf(v.name)
    else -> emptySet()
}

val Any.liveOut: Set<String> by circular(emptySet()) { cfg.succ(this).flatMap { it.liveIn }.toSet() }
val Any.liveIn: Set<String> by circular(emptySet()) { uses(this) + (liveOut - defs(this)) }

// ==== 5. Extension: warnings, added to the diagnostics from a separate module ===================

object UnusedValues {
    init {
        diagnostics
            .on<VarDecl> { d -> if (name !in liveOut) d + warning("value of '$name' is never used") else d }
            .on<Assign> { d -> if (name !in liveOut) d + warning("value assigned to '$name' is never used") else d }
    }
}

// ==== 8. Binary format: function signatures =====================================================

data class Signature(val name: String, val params: List<Type>, val ret: Type) {
    override fun toString() = "fun $name(${params.joinToString { it.name.lowercase() }}): ${ret.name.lowercase()}"
}

/**
 * "TINY", 4 bit version (1), 12 bit function count, then per function:
 * u1 name length, the name (UTF-8), 4 bit parameter count, 4 bit return type, 4 bits per parameter type,
 * padded to a whole byte.
 */
fun Program.signatureFile(): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("TINY".encodeToByteArray())
    val header = 0x1000 or funcs.size
    out.write(header shr 8); out.write(header and 0xFF)
    for (f in funcs) {
        val name = f.name.encodeToByteArray()
        out.write(name.size); out.write(name)
        out.write((f.params.size shl 4) or f.ret.ordinal)
        for (pair in f.params.chunked(2)) out.write((pair[0].declared.ordinal shl 4) or (pair.getOrNull(1)?.declared?.ordinal ?: 0))
    }
    return out.toByteArray()
}

object SignatureFormat {
    val file by rule {
        +"TINY"                  // text literals work on binary input too
        +bits("0001")            // version
        val count = uint(12).toInt()
        List(count) { signature() }
    }
    val signature by rule {
        val name = bytes(u1()).decodeToString()
        val paramCount = uint(4).toInt()
        val ret = type()
        val params = List(paramCount) { type() }
        if (paramCount % 2 == 1) +bits("0000") // padding
        Signature(name, params, ret)
    }
    val type by rule { Type.entries.getOrNull(uint(4).toInt()) ?: fail("type") }
}

// ==== main ======================================================================================

fun describe(v: Any): String = when (v) {
    is Num -> "${v.value}"
    is Bool -> "${v.value}"
    is Name -> v.name
    is Add -> "+"
    is Less -> "<"
    is Not -> "!"
    is VarDecl -> "var ${v.name} ="
    is Assign -> "${v.name} ="
    is Return -> "return"
    else -> v.toString()
}

fun main(args: Array<String>) {
    UnusedValues // load the extension module, so its contributions are registered

    // 1. parse a file: the path given as argument, or the bundled example (read from an InputStream)
    val program = try {
        if (args.isNotEmpty()) {
            TinyParser.program.parse(Path(args[0]), skip = TinyParser.skip, errors = CaretErrors)
        } else {
            val stream = TinyParser::class.java.getResourceAsStream("/example.tiny") ?: error("example.tiny not found")
            stream.reader().use { TinyParser.program.parse(it, TinyParser.skip, CaretErrors, source = "example.tiny") }
        }
    } catch (e: ParseException) {
        System.err.println(e.message)
        kotlin.system.exitProcess(1)
    }

    println("== diagnostics ==")
    program.diags.forEach { println("  $it") }

    println()
    println("== types of the return values ==")
    for (f in program.funcs) {
        val returns = f.body.stmts.filterIsInstance<Return>()
        println("  fun ${f.name}: declared ${f.ret}, returns ${returns.joinToString { it.value.type.toString() }}")
    }

    for (f in program.funcs.take(2)) {
        val g = cfg[f]
        println()
        println("== control flow graph of ${f.name}: ${g.vertices.size} vertices, ${g.edges.size} edges ==")
        for (v in g.reversePostorder()) {
            val next = g.outEdges(v).joinToString { e -> describe(e.to) + (e.label?.let { " [$it]" } ?: "") }
            println("  %-14s -> %-28s live: %s".format(describe(v), next.ifEmpty { "-" }, v.liveIn.sorted()))
        }
    }

    println()
    println("== binary signature file ==")
    val bytes = program.signatureFile()
    println("  ${bytes.size} bytes: ${bytes.joinToString(" ") { "%02X".format(it) }}")
    SignatureFormat.file.parse(ByteArrayInputStream(bytes)).forEach { println("  $it") }

    println()
    println("== Multi-System-Tree (functions without errors) ==")
    val msi = program.msi
    println("  problems: ${msi.validate().ifEmpty { "none" }}")
    println(msi.functions.first().render().prependIndent("  "))
    val run = Interpreter(msi)
    println("  sum(10) = ${run.call("sum", 10L)}")
    println("  clamp(5, 3, true) = ${run.call("clamp", 5L, 3L, true)}, clamp(1, 3, true) = ${run.call("clamp", 1L, 3L, true)}")
    val jvm = msi.loadJvm()
    println("  compiled to JVM bytecode: sum(10) = ${jvm.call("sum", 10L)}")
    println("  as JavaScript: ${msi.toJavaScript().length} characters, as LLVM IR: ${msi.toLlvm().lines().size} lines")

    println()
    println("== syntax error ==")
    try {
        TinyParser.program.parse("fun f(): int {\n    return 1 +\n}", TinyParser.skip, CaretErrors, source = "snippet.tiny")
    } catch (e: ParseException) {
        println(e.message!!.prependIndent("  "))
    }
}
