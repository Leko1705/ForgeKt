@file:JvmName("ParseJvm")

package com.leko.forge

import java.io.File
import java.io.InputStream
import java.io.Reader
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.readText

// Input from streams and files (JVM only). As in java.io, an InputStream is bytes (binary input)
// and a Reader is characters (text input).

/** Reads all characters of [input] and parses them as text. The reader is not closed. */
public fun <T> Rule<T>.parse(
    input: Reader,
    skip: Regex = Regex("\\s+"),
    errors: ErrorFormatter = DefaultErrorFormatter,
    source: String? = null,
): T = parse(input.readText(), skip, errors, source)

/** Reads the file [path] as text and parses it. Error messages name the file. */
public fun <T> Rule<T>.parse(
    path: Path,
    charset: Charset = Charsets.UTF_8,
    skip: Regex = Regex("\\s+"),
    errors: ErrorFormatter = DefaultErrorFormatter,
): T = parse(path.readText(charset), skip, errors, path.toString())

/** Reads [file] as text and parses it. Error messages name the file. */
public fun <T> Rule<T>.parse(
    file: File,
    charset: Charset = Charsets.UTF_8,
    skip: Regex = Regex("\\s+"),
    errors: ErrorFormatter = DefaultErrorFormatter,
): T = parse(file.toPath(), charset, skip, errors)

/**
 * Reads all bytes of [input] and parses them as binary input, like `parse(ByteArray)`.
 * For text, pass a [Reader] instead (e.g. `stream.reader()`). The stream is not closed.
 */
public fun <T> Rule<T>.parse(
    input: InputStream,
    skip: Regex? = null,
    errors: ErrorFormatter = DefaultErrorFormatter,
    source: String? = null,
): T = parse(input.readBytes(), skip, errors, source)
