package com.leko.forge

/**
 * Where a parsed node came from: [start] until [end] (exclusive) in [text], as character offsets
 * (byte offsets for binary input). Recorded for every object a rule returns.
 */
public class SourceSpan(public val source: String?, public val text: String, public val start: Int, public val end: Int) {
    /** 1-based line of [start]. */
    public val line: Int get() = text.substring(0, start.coerceAtMost(text.length)).count { it == '\n' } + 1

    /** 1-based column of [start]. */
    public val column: Int get() = start - (text.lastIndexOf('\n', start - 1) + 1) + 1

    /** The source text of the node. */
    public val snippet: String get() = text.substring(start.coerceAtMost(text.length), end.coerceAtMost(text.length))

    /** `source:line:column`, e.g. for error messages. */
    override fun toString(): String = "${source?.let { "$it:" } ?: ""}$line:$column"
}

/** Where [node] was parsed from, or `null` if it was not created by a rule. */
public fun spanOf(node: Any): SourceSpan? = Spans.spans[node]

internal object Spans {
    val spans = weakIdentityMap<Any, SourceSpan>()

    fun record(node: Any, span: SourceSpan) {
        if (spans[node] == null) spans[node] = span
    }
}
