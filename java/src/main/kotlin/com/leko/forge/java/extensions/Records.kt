package com.leko.forge.java.extensions

import com.leko.forge.java.*
import com.leko.forge.java.JavaGrammar.ident
import com.leko.forge.java.JavaGrammar.member
import com.leko.forge.java.JavaGrammar.modifiers
import com.leko.forge.java.JavaGrammar.typeDecl

/**
 * Java 16 records: `record Point(int x, int y) { ... }`. A pure syntax extension: a record is
 * rewritten into a class with private final fields, the canonical constructor, accessor methods,
 * `toString()` (`Point[x=1, y=2]`) and `equals(Object)`, each unless the record declares it itself.
 */
public object Records : JavaFeature("records") {
    init {
        typeDecl.on {
            val mods = modifiers(); +"record"; val name = ident()
            requireEnabled()
            val components = CoreClasses.params()
            val interfaces = optional { +"implements"; sepBy(ident, ",") }.orEmpty()
            val body = inClass(name) { +"{"; val m = many(member); +"}"; flattenMembers(m) }
            desugar(name, mods, components, interfaces, body)
        }
    }

    private fun desugar(name: String, mods: Set<String>, components: List<ParamDecl>, interfaces: List<String>, body: List<JMember>): TypeDecl {
        val members = ArrayList<JMember>()
        for (c in components) members += FieldDecl(setOf("private", "final"), c.declaredType, c.name, null)
        val declares = { n: String, arity: Int -> body.any { it is MethodDecl && it.name == n && it.params.size == arity } }
        if (body.none { it is ConstructorDecl && it.params.map { p -> p.declaredType } == components.map { c -> c.declaredType } })
            members += ConstructorDecl(setOf("public"), name, components.map { ParamDecl(it.declaredType, it.name) },
                BlockStmt(components.map { AssignStmt(FieldAccess(ThisExpr(), it.name), "=", NameExpr(it.name)) }))
        for (c in components) if (!declares(c.name, 0))
            members += MethodDecl(setOf("public"), c.declaredType, c.name, emptyList(), BlockStmt(listOf(ReturnStmt(FieldAccess(ThisExpr(), c.name)))))
        if (!declares("toString", 0)) {
            // "Point[x=" + x + ", y=" + y + "]"
            var text: JExpr = Literal("$name[", StringType)
            components.forEachIndexed { i, c ->
                text = BinaryExpr("+", text, Literal("${if (i > 0) ", " else ""}${c.name}=", StringType))
                text = BinaryExpr("+", text, FieldAccess(ThisExpr(), c.name))
            }
            text = BinaryExpr("+", text, Literal("]", StringType))
            members += MethodDecl(setOf("public"), StringType, "toString", emptyList(), BlockStmt(listOf(ReturnStmt(text))))
        }
        if (!declares("equals", 1)) {
            // if (!(o instanceof Point)) return false; Point other = (Point) o; return x == other.x && ...;
            val same = components.map { c ->
                val mine = FieldAccess(ThisExpr(), c.name)
                val theirs = FieldAccess(NameExpr("other"), c.name)
                if (c.declaredType.isReference || c.declaredType == StringType) MethodCall(mine, "equals", listOf(theirs)) else BinaryExpr("==", mine, theirs)
            }.reduceOrNull { a, b -> BinaryExpr("&&", a, b) } ?: Literal(true, BooleanType)
            members += MethodDecl(setOf("public"), BooleanType, "equals", listOf(ParamDecl(ClassType("Object"), "o")), BlockStmt(listOf(
                IfStmt(UnaryExpr("!", InstanceOfExpr(NameExpr("o"), ClassType(name))), ReturnStmt(Literal(false, BooleanType)), null),
                LocalVar(ClassType(name), "other", CastExpr(ClassType(name), NameExpr("o"))),
                ReturnStmt(same),
            )))
        }
        return TypeDecl(name, false, null, interfaces, mods + "final", members + body)
    }
}
