package com.leko.forge.java

import com.leko.forge.ParseScope
import com.leko.forge.coll
import com.leko.forge.inh
import com.leko.forge.parentOf
import com.leko.forge.spanOf
import com.leko.forge.syn

// ==== features ===================================================================================

/**
 * A part of the language: its syntax (alternatives of the [JavaGrammar] rules), typing (`jtype`),
 * checks (contributions to [problems]) and lowering to MSI (`msi`). The Java language is the set of
 * [JavaCompiler.standardFeatures]; extensions are features too and can come from other modules.
 *
 * A feature registers everything in its `init` block, which runs when the feature is first used.
 * Its syntax is only accepted while a compiler that enables it is parsing.
 */
abstract class JavaFeature(val name: String) {
    /** Whether the compiler that is currently parsing enables this feature. */
    val isEnabled: Boolean get() = this in JavaCompiler.enabledFeatures.get()

    /**
     * Inside a rule, after the syntax that identifies the feature: stops parsing with a clear error
     * unless the feature is enabled.
     */
    fun ParseScope.requireEnabled() {
        if (!isEnabled) throw FeatureDisabled(this@JavaFeature, com.leko.forge.SourceSpan(JavaGrammar.currentSource.get(), input, offset, offset))
    }

    override fun toString(): String = name
}

/** Syntax of a feature the compiler does not enable. */
class FeatureDisabled(val feature: JavaFeature, val position: com.leko.forge.SourceSpan) :
    RuntimeException("${feature.name} are not enabled (add the ${feature::class.simpleName} feature to the compiler)")

// ==== problems ===================================================================================

/** A compile error, at the source position of [node] if known. */
class JavaProblem(val message: String, val node: Any?, private val at: String? = null) {
    val position: String? get() = at ?: node?.let { spanOf(it) }?.toString()
    override fun toString(): String = "${position?.let { "$it: " } ?: ""}error: $message"
}

/** Java level checks. Features add contributions; the owner of the collection is the [Program]. */
val problems: com.leko.forge.Coll<List<JavaProblem>> = coll(emptyList(), owner = Program::class)
val Program.javaProblems: List<JavaProblem> by problems

/** A problem at this node. */
fun JNode.problem(message: String): List<JavaProblem> = listOf(JavaProblem(message, this))

// ==== class table ================================================================================

class FieldInfo(val owner: ClassInfo, val name: String, val type: JType, val isStatic: Boolean, val decl: FieldDecl?)

class MethodInfo(
    val owner: ClassInfo,
    val name: String,
    val params: List<JType>,
    val returnType: JType,
    val isStatic: Boolean,
    val decl: MethodDecl?,
    /** Index among the methods of [owner], for unique names. */
    val index: Int,
    /** For methods without Java source: the name of the built-in implementation. */
    val builtin: String? = null,
) {
    val isAbstract: Boolean get() = builtin == null && decl?.body == null
    /** Methods with the same key override each other. */
    val key: String get() = "$name(${params.joinToString(",") { it.display }})"
    override fun toString(): String = "${owner.name}.$key"
}

class ConstructorInfo(val owner: ClassInfo, val params: List<JType>, val decl: ConstructorDecl?, val index: Int)

class ClassInfo(val name: String, val decl: TypeDecl?, val id: Int) {
    val superName: String? get() = if (name == OBJECT) null else decl?.superclass ?: if (decl?.isInterface == true) null else OBJECT
    val interfaces: List<String> get() = decl?.interfaces.orEmpty()
    val isInterface: Boolean get() = decl?.isInterface == true
    val isAbstract: Boolean get() = decl?.isAbstract == true
    val fields: LinkedHashMap<String, FieldInfo> = LinkedHashMap()
    val methods: ArrayList<MethodInfo> = ArrayList()
    val constructors: ArrayList<ConstructorInfo> = ArrayList()
    override fun toString(): String = name
}

const val OBJECT: String = "Object"

class ClassTable(val classes: Map<String, ClassInfo>) {
    operator fun get(name: String): ClassInfo? = classes[name]

    fun superclass(c: ClassInfo): ClassInfo? = c.superName?.let { classes[it] }

    /** The class, its superclasses and all interfaces they implement. */
    fun ancestors(c: ClassInfo): List<ClassInfo> {
        val result = LinkedHashSet<ClassInfo>()
        fun visit(x: ClassInfo) {
            if (!result.add(x)) return
            superclass(x)?.let(::visit)
            x.interfaces.mapNotNull { classes[it] }.forEach(::visit)
        }
        visit(c)
        return result.toList()
    }

    fun isSubclass(sub: String, sup: String): Boolean = classes[sub]?.let { c -> ancestors(c).any { it.name == sup } } ?: false

    /** The field [name] of [c] or a superclass. */
    fun field(c: ClassInfo, name: String): FieldInfo? {
        var x: ClassInfo? = c
        while (x != null) { x.fields[name]?.let { return it }; x = superclass(x) }
        return null
    }

    /** The methods called [name] visible in [c]: its own, inherited ones that are not overridden. */
    fun methods(c: ClassInfo, name: String): List<MethodInfo> {
        val seen = HashSet<String>()
        val result = ArrayList<MethodInfo>()
        for (x in ancestors(c)) for (m in x.methods) if (m.name == name && seen.add(m.key)) result += m
        return result
    }

    /** The implementation of the method with [key] for objects of class [c], or `null` if it is abstract. */
    fun implementation(c: ClassInfo, key: String): MethodInfo? {
        var x: ClassInfo? = c
        while (x != null) {
            x.methods.firstOrNull { it.key == key && !it.isStatic && !it.isAbstract }?.let { return it }
            x = superclass(x)
        }
        return null
    }

    /** All instance method keys of the program: the slots of the virtual method tables. */
    val slots: List<MethodInfo> by lazy {
        val byKey = LinkedHashMap<String, MethodInfo>()
        for (c in classes.values) for (m in c.methods) if (!m.isStatic) byKey.putIfAbsent(m.key, m)
        byKey.values.toList()
    }

    /** Whether a value of type [from] can be assigned to [to] (widening and subtyping). */
    fun assignable(from: JType, to: JType): Boolean = from == to || from == ErrorType || to == ErrorType ||
        (from == IntType && (to == LongType || to == DoubleType)) || (from == LongType && to == DoubleType) ||
        (from == NullType && to.isReference) ||
        (from is ClassType && to is ClassType && isSubclass(from.name, to.name)) ||
        (to == ClassType(OBJECT) && from.isReference) ||
        (from is ArrayType && to is ArrayType && from.element.isReference && assignable(from.element, to.element))
}

val Program.classTable: ClassTable by syn { buildClassTable(this) }

private fun buildClassTable(program: Program): ClassTable {
    val classes = LinkedHashMap<String, ClassInfo>()
    val obj = ClassInfo(OBJECT, null, 0)
    obj.methods += MethodInfo(obj, "toString", emptyList(), StringType, false, null, 0, builtin = "toString")
    obj.methods += MethodInfo(obj, "equals", listOf(ClassType(OBJECT)), BooleanType, false, null, 1, builtin = "equals")
    obj.methods += MethodInfo(obj, "hashCode", emptyList(), IntType, false, null, 2, builtin = "hashCode")
    classes[OBJECT] = obj
    for (unit in program.units) for (t in unit.types) {
        val c = ClassInfo(t.name, t, classes.size)
        classes.putIfAbsent(t.name, c)
        for (m in t.members) when (m) {
            is FieldDecl -> c.fields[m.name] = FieldInfo(c, m.name, m.type, m.isStatic, m)
            is MethodDecl -> c.methods += MethodInfo(c, m.name, m.params.map { it.declaredType }, m.returnType, m.isStatic, m, c.methods.size)
            is ConstructorDecl -> c.constructors += ConstructorInfo(c, m.params.map { it.declaredType }, m, c.constructors.size)
        }
        if (c.constructors.isEmpty() && !c.isInterface) c.constructors += ConstructorInfo(c, emptyList(), null, 0) // the default constructor
    }
    return ClassTable(classes)
}

/** The class table of the program a node belongs to. */
val JNode.classes: ClassTable get() = program.classTable

val JNode.program: Program by inh<Program>().on<Program> { this }

// ==== context ====================================================================================

fun interface JScope { fun find(name: String): JVariable? }

private fun JScope.with(variables: List<JVariable>): JScope = JScope { n -> variables.lastOrNull { it.name == n } ?: find(n) }

/** The variables visible at a node. Features with new variable declarations add equations. */
val scopes: com.leko.forge.Inh<JScope> = inh<JScope>()
    .on<Program> { JScope { null } }
    .on<MethodDecl> { scope.with(params) }
    .on<ConstructorDecl> { scope.with(params) }
    .on<BlockStmt> { child -> scope.with(declared(stmts.takeWhile { it !== child })) }
    .on<StatementList> { child -> scope.with(declared(stmts.takeWhile { it !== child })) }
    .on<ForStmt> { child -> scope.with((if (child in init) init.takeWhile { it !== child } else init).filterIsInstance<JVariable>()) }
    .on<ForEachStmt> { child -> if (child === body) scope.with(listOf(this)) else scope }
    .on<CatchClause> { scope.with(listOf(this)) }
val JNode.scope: JScope by scopes

/** Variables declared by statements (not loops: their variables are only visible inside them). */
private fun declared(stmts: List<JStmt>): List<JVariable> = flatten(stmts).filter { it is JVariable && it !is LoopStmt }.map { it as JVariable }

val JNode.enclosingType: TypeDecl? by inh<TypeDecl?>().on<Program> { null }.on<TypeDecl> { this }
val JNode.enclosingClass: ClassInfo? get() = enclosingType?.let { classes[it.name] }

/** The method, constructor or field (initializer) a node belongs to. */
val JNode.enclosingMember: JMember? by inh<JMember?>()
    .on<TypeDecl> { null }.on<MethodDecl> { this }.on<ConstructorDecl> { this }.on<FieldDecl> { this }

val JNode.isStaticContext: Boolean get() = when (val m = enclosingMember) {
    is MethodDecl -> m.isStatic
    is FieldDecl -> m.isStatic
    else -> false
}

/** Enclosing loops, innermost last (only their bodies are inside them). */
val JNode.enclosingLoops: List<LoopStmt> by inh<List<LoopStmt>>()
    .on<TypeDecl> { emptyList() }.on<MethodDecl> { emptyList() }.on<ConstructorDecl> { emptyList() }
    .on<LoopStmt> { child -> if (child === body || this is ForStmt && child in update) enclosingLoops + this else enclosingLoops }

// ==== types and resolution =======================================================================

/** The static type of an expression. Every feature implements it for its expressions. */
val JExpr.jtype: JType by syn()

/** The type of a variable: declared, or inferred for `var`. */
val JVariable.type: JType get() = declaredType ?: when (this) {
    is LocalVar -> init?.jtype ?: ErrorType
    is ForEachStmt -> (iterable.jtype as? ArrayType)?.element ?: ErrorType
    else -> ErrorType
}

sealed class Resolution {
    class Variable(val variable: JVariable) : Resolution()
    class Field(val field: FieldInfo) : Resolution()
    class Class(val info: ClassInfo) : Resolution()
    object None : Resolution()
}

/** What a simple name refers to: a variable, a field (of the enclosing class or inherited), or a class. */
val NameExpr.resolution: Resolution by syn {
    scope.find(name)?.let { return@syn Resolution.Variable(it) }
    enclosingClass?.let { c -> classes.field(c, name) }?.let { return@syn Resolution.Field(it) }
    classes[name]?.let { return@syn Resolution.Class(it) }
    Resolution.None
}

/** Whether the type names a known class, primitive, String or array of those. */
fun JNode.isKnown(t: JType): Boolean = when (t) {
    is ArrayType -> isKnown(t.element)
    StringType -> true
    is ClassType -> classes[t.name] != null
    else -> true
}

/** The loop a `break`/`continue` refers to. */
fun JNode.loopTarget(label: String?): LoopStmt? = enclosingLoops.lastOrNull { label == null || it.label == label }

/** The enclosing node of a given type. */
inline fun <reified T> JNode.ancestor(): T? {
    var n: Any? = parentOf(this)
    while (n != null) { if (n is T) return n; n = parentOf(n) }
    return null
}
