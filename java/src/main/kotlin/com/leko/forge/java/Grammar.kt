package com.leko.forge.java

import com.leko.forge.AbstractRule
import com.leko.forge.Keywords
import com.leko.forge.Rule
import com.leko.forge.rule
import com.leko.forge.token

/**
 * The Java grammar: abstract rules whose alternatives and operators come from the features.
 * Extensions add syntax the same way, e.g. `JavaGrammar.stmt.on { requireEnabled(); +"unless"; ... }`.
 */
object JavaGrammar {
    /** Whitespace and comments. */
    val skip: Regex = Regex("(\\s|//[^\\n]*|/\\*[\\s\\S]*?\\*/)+")

    /** Reserved words; features add theirs. They are never identifiers. */
    val keywords: Keywords = Keywords(
        "class", "interface", "extends", "implements", "public", "private", "protected", "static", "final", "abstract",
        "void", "int", "long", "double", "boolean", "if", "else", "while", "do", "for", "break", "continue", "return",
        "new", "this", "super", "null", "true", "false", "instanceof", "throw", "throws", "try", "catch", "finally", "package", "import",
    )

    val ident: Rule<String> by token("[A-Za-z_$][A-Za-z0-9_$]*", except = keywords)
    val qualifiedName: Rule<String> by rule { listOf(ident()).plus(many { +"."; ident() }).joinToString(".") }

    val type: AbstractRule<JType> by rule<JType>()
    val expr: AbstractRule<JExpr> by rule<JExpr>()
    val stmt: AbstractRule<JStmt> by rule<JStmt>()
    val member: AbstractRule<JMember> by rule<JMember>()
    val typeDecl: AbstractRule<TypeDecl> by rule<TypeDecl>()

    val block: Rule<BlockStmt> by rule { +"{"; val stmts = many(stmt); +"}"; BlockStmt(stmts) }

    val modifiers: Rule<Set<String>> by rule {
        many { oneOf({ +"public" }, { +"private" }, { +"protected" }, { +"static" }, { +"final" }, { +"abstract" }) }.toSet()
    }

    val compilationUnit: Rule<CompilationUnit> by rule {
        optional { +"package"; qualifiedName(); +";" }
        many { +"import"; qualifiedName(); optional { +"."; +"*" }; +";" }
        CompilationUnit(currentSource.get(), many(typeDecl))
    }

    /** The name of the class whose members are being parsed (constructors are recognized by it). */
    val currentClass: ThreadLocal<String?> = ThreadLocal()
    internal val currentSource: ThreadLocal<String?> = ThreadLocal()
}
