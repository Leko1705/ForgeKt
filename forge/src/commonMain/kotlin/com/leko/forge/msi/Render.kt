package com.leko.forge.msi

/** A readable text form of the module, for debugging and tests. */
public fun Module.render(): String = buildString {
    append("module $name\n")
    for (s in structs) append("\n").append(s.render()).append("\n")
    for (u in unions) append("\n").append(u.render()).append("\n")
    if (globals.isNotEmpty()) append("\n")
    for (g in globals) append("global ${g.name}: ${g.type.render()}").append(g.init?.let { " = ${it.render()}" } ?: "").append("\n")
    for (f in functions) append("\n").append(f.render()).append("\n")
}

private fun typeParams(names: List<String>) = if (names.isEmpty()) "" else names.joinToString(", ", "<", ">")
private fun fields(fields: List<Field>) = fields.joinToString { "${it.name}: ${it.type.render()}" }

private fun kind(kind: MemoryKind) = when (kind) { MemoryKind.SHARED -> ""; MemoryKind.VALUE -> "value "; MemoryKind.OWNED -> "owned " }
private fun lifetimeParams(names: List<String>) = if (names.isEmpty()) "" else names.joinToString(", ", "<", ">") { "'$it" }

public fun Struct.render(): String = "${kind(kind)}struct $name${lifetimeParams(lifetimes)}${typeParams(typeParams)} { ${fields(fields)} }"

public fun Union.render(): String =
    "${kind(kind)}union $name${typeParams(typeParams)} { " +
        variants.joinToString { if (it.fields.isEmpty()) it.name else "${it.name}(${fields(it.fields)})" } + " }"

public fun Func.render(): String {
    val constraints = if (outlives.isEmpty()) "" else outlives.joinToString(", ", " where ") { "'${it.longer}: '${it.shorter}" }
    val signature = "fun $name${lifetimeParams(lifetimes)}${typeParams(typeParams)}(${params.joinToString { "${it.name}: ${it.type.render()}" }}): ${returnType.render()}$constraints"
    return if (body == null) "external $signature" else "$signature ${body.render(0)}"
}

private fun typeArgs(args: List<Type>) = if (args.isEmpty()) "" else args.joinToString(", ", "<", ">") { it.render() }

public fun Type.render(): String = when (this) {
    Type.Unit -> "unit"
    Type.Bool -> "bool"
    Type.I32 -> "i32"
    Type.I64 -> "i64"
    Type.F64 -> "f64"
    Type.Str -> "str"
    is Type.Struct -> name + (if (lifetimes.isEmpty()) "" else lifetimes.joinToString(", ", "<", ">") { "'$it" }) + typeArgs(args)
    is Type.Ref -> "&" + (lifetime?.let { "'$it " } ?: "") + (if (mutable) "mut " else "") + target.render()
    is Type.Union -> name + typeArgs(args)
    is Type.Array -> "[${element.render()}]"
    is Type.Function -> "(${params.joinToString { it.render() }}) -> ${result.render()}"
    is Type.Param -> name
    Type.Invalid -> "<invalid>"
}

public fun Stmt.render(indent: Int = 0): String {
    val pad = "    ".repeat(indent)
    return when (this) {
        is Block -> if (stmts.isEmpty()) "{}" else
            "{\n" + stmts.joinToString("") { "$pad    ${it.render(indent + 1)}\n" } + "$pad}"
        is Let -> "let $name: ${type.render()}" + (init?.let { " = ${it.render(indent)}" } ?: "")
        is Assign -> "${target.render(indent)} = ${value.render(indent)}"
        is Branch -> "if ${condition.render(indent)} ${then.render(indent)}" + (alternative?.let { " else ${it.render(indent)}" } ?: "")
        is Loop -> (label?.let { "$it: " } ?: "") + "loop" + (condition?.let { " while ${it.render(indent)}" } ?: "") + " ${body.render(indent)}"
        is Break -> "break" + (label?.let { " $it" } ?: "")
        is Continue -> "continue" + (label?.let { " $it" } ?: "")
        is Return -> "return" + (value?.let { " ${it.render(indent)}" } ?: "")
        is Eval -> expr.render(indent)
        is Match -> "match ${subject.render(indent)} {\n" +
            cases.joinToString("") { c ->
                val bindings = if (c.bindings.isEmpty()) "" else c.bindings.joinToString(", ", "(", ")")
                "$pad    ${c.variant}$bindings -> ${c.body.render(indent + 1)}\n"
            } +
            (otherwise?.let { "$pad    else -> ${it.render(indent + 1)}\n" } ?: "") + "$pad}"
        is Throw -> "throw ${value.render(indent)}"
        is Access -> "access ${if (mutable) "mut " else ""}${target.render(indent)} ${body.render(indent)}"
        is Try -> "try ${body.render(indent)}" +
            catches.joinToString("") { " catch (${it.name}: ${it.type.render()}) ${it.body.render(indent)}" } +
            (finally?.let { " finally ${it.render(indent)}" } ?: "")
    }
}

public fun Expr.render(indent: Int = 0): String = when (this) {
    is IntLit -> if (type == Type.I64) "$value" else "$value${type.render()}"
    is FloatLit -> "${value}f"
    is BoolLit -> "$value"
    is StrLit -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    is Local -> name
    is Binary -> "(${left.render(indent)} ${op.symbol} ${right.render(indent)})"
    is Unary -> "${op.symbol}${operand.render(indent)}"
    is Call -> "$function${typeArgs(typeArgs)}(${args.joinToString { it.render(indent) }})"
    is New -> "new $struct${typeArgs(typeArgs)}(${args.joinToString { it.render(indent) }})"
    is Get -> "${target.render(indent)}.$field"
    is NewArray -> "new [${element.render()}; ${size.render(indent)}]"
    is Index -> "${array.render(indent)}[${index.render(indent)}]"
    is Length -> "len(${array.render(indent)})"
    is Convert -> "(${value.render(indent)} as ${to.render()})"
    is Conditional -> "(${condition.render(indent)} ? ${then.render(indent)} : ${otherwise.render(indent)})"
    is Make -> "$union${typeArgs(typeArgs)}.$variant" + if (args.isEmpty()) "" else args.joinToString(", ", "(", ")") { it.render(indent) }
    is Is -> "(${subject.render(indent)} is $variant)"
    is FuncRef -> "::$function${typeArgs(typeArgs)}"
    is Lambda -> "fn(${params.joinToString { "${it.name}: ${it.type.render()}" }}): ${returnType.render()} ${body.render(indent)}"
    is Invoke -> "${callee.render(indent)}(${args.joinToString { it.render(indent) }})"
    is Borrow -> "&${if (mutable) "mut " else ""}${place.render(indent)}"
    is Deref -> "*${ref.render(indent)}"
    is Copy -> "copy(${value.render(indent)})"
    is Null -> "null"
}
