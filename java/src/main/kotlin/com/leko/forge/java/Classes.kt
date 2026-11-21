package com.leko.forge.java

import com.leko.forge.Child
import com.leko.forge.ParseScope
import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.java.JavaGrammar.block
import com.leko.forge.java.JavaGrammar.currentClass
import com.leko.forge.java.JavaGrammar.ident
import com.leko.forge.java.JavaGrammar.member
import com.leko.forge.java.JavaGrammar.modifiers
import com.leko.forge.java.JavaGrammar.type
import com.leko.forge.java.JavaGrammar.typeDecl

/** Several members parsed from one declaration (`int a, b;`); type declarations flatten them. */
data class MemberList(@Child val members: List<JMember>) : JMember

fun flattenMembers(members: List<JMember>): List<JMember> = members.flatMap { if (it is MemberList) flattenMembers(it.members) else listOf(it) }

/** Runs [body] while the members of class [name] are parsed (constructors are recognized by it). */
fun <T> ParseScope.inClass(name: String, body: ParseScope.() -> T): T {
    val previous = currentClass.get()
    currentClass.set(name)
    try { return body() } finally { currentClass.set(previous) }
}

/** Classes, interfaces, fields, methods, constructors, inheritance. */
object CoreClasses : JavaFeature("classes") {

    val params: com.leko.forge.Rule<List<ParamDecl>> by rule {
        +"("; val p = sepBy({ optional { +"final" }; val t = type(); ParamDecl(t, ident()) }, ","); +")"; p
    }
    /** `throws A, B`: accepted and ignored (exceptions are not checked). */
    private val throwsClause by rule { optional { +"throws"; sepBy(ident, ",") } }
    private val classBody by rule { +"{"; val m = many(member); +"}"; flattenMembers(m) }

    init {
        typeDecl
            .on {
                val mods = modifiers(); +"class"; val name = ident()
                val sup = optional { +"extends"; ident() }
                val interfaces = optional { +"implements"; sepBy(ident, ",") }.orEmpty()
                TypeDecl(name, false, sup, interfaces, mods, inClass(name) { classBody() })
            }
            .on {
                val mods = modifiers(); +"interface"; val name = ident()
                val interfaces = optional { +"extends"; sepBy(ident, ",") }.orEmpty()
                TypeDecl(name, true, null, interfaces, mods, inClass(name) { classBody() })
            }
        member
            .on {
                val mods = modifiers(); val name = ident()
                if (name != currentClass.get()) fail("a constructor of ${currentClass.get()}")
                val p = params(); throwsClause()
                ConstructorDecl(mods, name, p, block())
            }
            .on {
                val mods = modifiers(); val t = type(); val name = ident(); val p = params(); throwsClause()
                MethodDecl(mods, t, name, p, oneOf({ block() }, { +";"; null }))
            }
            .on {
                val mods = modifiers(); val t = type()
                val fields = sepBy({ val n = ident(); FieldDecl(mods, t, n, optional { +"="; CoreStatements.initializer() }) }, ",")
                if (fields.isEmpty()) fail("a field name")
                +";"
                if (fields.size == 1) fields.single() else MemberList(fields)
            }
    }

    // ---- checks ------------------------------------------------------------------------------

    private fun JNode.known(t: JType, what: String) = if (isKnown(t)) emptyList() else problem("cannot find class ${t.display} ($what)")

    private fun duplicates(names: List<String>) = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun cyclic(table: ClassTable, c: ClassInfo): Boolean {
        val seen = HashSet<String>()
        var x: ClassInfo? = c
        while (x != null) { if (!seen.add(x.name)) return true; x = table.superclass(x) }
        return false
    }

    init {
        problems
            .on<Program> { p -> p + duplicates(units.flatMap { u -> u.types.map { it.name } }).map { JavaProblem("duplicate class $it", this) } }
            .on<TypeDecl> { p ->
                val table = classes
                val c = table[name] ?: return@on p
                val result = ArrayList<JavaProblem>()
                superclass?.let { s ->
                    val sc = table[s]
                    when {
                        sc == null -> result += problem("cannot find class $s")
                        sc.isInterface -> result += problem("$name cannot extend interface $s; use implements")
                        cyclic(table, c) -> result += problem("cyclic inheritance involving $name")
                    }
                }
                for (i in interfaces) {
                    val ic = table[i]
                    if (ic == null) result += problem("cannot find interface $i") else if (!ic.isInterface) result += problem("$i is not an interface")
                }
                if (!c.isAbstract && result.isEmpty()) for (m in table.ancestors(c).flatMap { it.methods }) {
                    if (!m.isStatic && m.isAbstract && table.implementation(c, m.key) == null)
                        result += problem("$name is not abstract and does not override abstract method ${m.key} in ${m.owner.name}")
                }
                result += duplicates(c.methods.map { it.key }).flatMap { problem("method $it is already defined in $name") }
                result += duplicates(members.filterIsInstance<FieldDecl>().map { it.name }).flatMap { problem("field $it is already defined in $name") }
                p + result
            }
            .on<MethodDecl> { p ->
                val c = enclosingClass
                val result = known(returnType, "return type of $name") + params.flatMap { known(it.declaredType, "parameter ${it.name}") }.toMutableList()
                val body = body
                if (body == null && enclosingType?.isAbstract == false) return@on p + result + problem("missing method body in $name")
                if (body != null && returnType != VoidType && body.completesNormally) return@on p + result + problem("missing return statement in $name")
                if (c != null && !isStatic) {
                    val key = "$name(${params.joinToString(",") { it.declaredType.display }})"
                    val overridden = classes.ancestors(c).drop(1).flatMap { it.methods }.firstOrNull { it.key == key && !it.isStatic }
                    if (overridden != null && overridden.returnType != returnType)
                        return@on p + result + problem("$name in ${c.name} cannot override ${overridden.key} in ${overridden.owner.name}: return type ${returnType.display} is not ${overridden.returnType.display}")
                }
                p + result
            }
            .on<FieldDecl> { p ->
                val i = init
                val t = type
                val result = known(t, "field $name")
                if (i != null && i.jtype != ErrorType && !classes.assignable(i.jtype, t)) p + result + i.problem("incompatible types: ${i.jtype.display} cannot be converted to ${t.display}")
                else p + result
            }
            .on<ThisExpr> { p -> if (isStaticContext) p + problem("non-static variable this cannot be referenced from a static context") else p }
    }
}
