# ForgeKt

A Kotlin library for building compilers: parsers, attribute grammars and graph analyses, in plain Kotlin.

```kotlin
val expr: Rule<Expr> by rule { oneOf(plus, atom) }
val plus: Rule<Expr> by rule { val l = expr(); +"+"; Plus(l, atom()) }

val Expr.type: Type by syn()
val Plus.type: Type by syn { if (l.type == r.type) l.type else Type.ERROR }

val Node.scope by inh<Scope>().on<Block> { child -> ... }
val Program.diags by coll<List<Diagnostic>>(emptyList()).on<Plus> { d -> ... }

val Any.liveIn: Set<String> by circular(emptySet()) { uses(this) + (liveOut - defs(this)) }
```

## Modules

| Module      | Contents                                                                                    |
|-------------|---------------------------------------------------------------------------------------------|
| `forge`     | The library (Kotlin Multiplatform: JVM and JS)                                              |
| `examples`  | *Tiny*, a small language that uses every feature (`./gradlew :examples:run`)                |
| `java`      | An extensible compiler for a subset of Java, built with Forge, compiling to MSI             |
| `python`    | An extensible Python compiler, built with Forge, compiling to Python (and JavaScript)        |

## Features

| Feature            | API                                                          | Source            |
|--------------------|--------------------------------------------------------------|-------------------|
| Parsing (text)     | `rule { }`, `token(regex)`, `+"literal"`, `oneOf`, `many`, … | `Parser.kt`       |
| Abstract rules     | `rule<T>().on { }.infixLeft(10) { l -> }.prefix(..)`, `operand()` | `AbstractRule.kt` |
| Keywords           | `token(pattern, except = Keywords("if", ...))`               | `Parser.kt`       |
| Parsing (binary)   | `+bits("0101")`, `+hex("CAFE")`, `uint(n)`, `u2()`, `bytes(n)`| `Parser.kt`       |
| Error messages     | `ErrorFormatter`, `ParseException.error`                     | `Errors.kt`       |
| Synthesized attr.  | `syn { }`, dispatch with `syn()` on interfaces               | `Syn.kt`          |
| Inherited attr.    | `inh<T>().on<Parent> { child -> }.default { }`               | `Inh.kt`          |
| Collections        | `coll(initial).on<Source>(to = { }) { acc -> }`              | `Coll.kt`         |
| Circular attr.     | `circular(initial) { }` (fixpoints, data flow analyses)      | `Circular.kt`     |
| Graphs             | `Graph`, `graphOf(...)`, `reversePostorder()`, `toDot()`     | `Graph.kt`        |
| Flows (AST → graph)| `graph<Root>().flow<Node> { entry(..); a then b }`, `port()` | `Flow.kt`         |
| Multi-System-Tree  | `msi.Module`, `validate()`, `Interpreter`, `render()`          | `msi/`            |
| Trees              | `tree(root)`, `parentOf`, `childrenOf`, `TreeNode`, `@Child` | `Tree.kt`         |

Abstract rules work like abstract synthesized attributes: alternatives and operators (with precedence
and associativity) are added from anywhere, so a grammar can be extended like the attributes.

Equations, contributions and flows can be added from any module (`scopes.on<For> { ... }`), so a
language can be extended without changing it. The most specific node type wins; among equally
specific ones, the one added last. Extension modules must be loaded (referenced) before evaluation.

## Multi-System-Tree (MSI)

`com.leko.forge.msi` is a system independent program representation that languages convert their
trees into, typically with synthesized attributes (`val Stmt.msi: msi.Stmt by syn()`). It is
statically typed and uses structured control flow, so it maps onto JVM bytecode, native code and
JavaScript alike:

- declarations: `Module`, `Struct` (records; backends generate classes), `Union` (tagged unions; enums
  are unions without fields), `Global`, `Func` (without a body: `external`, implemented by the host,
  e.g. `print`). Structs, unions and functions can be generic (`Type.Param`, type arguments inferred)
- statements: `Block`, `Let`, `Assign`, `Branch`, `Loop` (optional condition and label), `Break`,
  `Continue`, `Return`, `Eval`, `Match` (on union variants, binding fields), `Throw`, `Try` (typed
  catches, `finally`)
- expressions: literals, `Local`, `Binary`, `Unary`, `Call`, `New`, `Get`, `NewArray`, `Index`, `Length`,
  `Convert`, `Conditional`, `Make`, `Is`, `FuncRef`, `Lambda` (closures capture by reference), `Invoke`
- types: `Unit`, `Bool`, `I32`, `I64`, `F64`, `Str`, `Struct`, `Union`, `Array`, `Function`, `Param`

| Tool                    | API                                         | Runs on            |
|-------------------------|---------------------------------------------|--------------------|
| Checker                 | `module.validate()`                         | all platforms      |
| Interpreter (reference) | `Interpreter(module, externals).call(..)`   | all platforms      |
| Control flow graphs     | `func.controlFlow()`, `msiFlows`            | all platforms      |
| JavaScript backend      | `module.toJavaScript()`                     | all platforms      |
| LLVM IR backend         | `module.toLlvm()` + `LLVM_RUNTIME_C`        | all platforms      |
| JVM bytecode backend    | `module.toJvm()`, `module.loadJvm(..)`      | JVM (uses ASM)     |

### Ownership: Swift style and Rust style

Structs and unions have a memory kind: `SHARED` (references, the default), `VALUE` (value semantics,
like Swift structs) or `OWNED` (unique ownership, like Rust). References are `Type.Ref(target, mutable,
lifetime?)`, created with `Borrow` and read or written with `Deref`:

- without a lifetime a reference is second-class (Swift style): a parameter or local that can not escape;
  a result can borrow from the only reference parameter (elision)
- with explicit lifetimes (`Func.lifetimes`, `Func.outlives`, `Struct.lifetimes`) references can be returned
  and stored in fields (Rust style)

A module that uses any of this is borrow checked by `validate()`, automatically, because backends rely on
it: no use after move, definite initialization, no conflicting borrows (with non-lexical lifetimes; closures
borrow what they capture), no reference outliving its data. The checker only rejects programs, so all
backends run accepted programs unchanged. On top of that, `Copy` duplicates value types, `insertCopies()`
adds the copies a Swift-like language makes implicitly, and `Access` checks exclusive access to shared
objects at runtime. The LLVM backend frees owned and value objects deterministically and collects the
rest with a conservative tracing collector.

A conformance suite (`forge/src/commonTest/.../Conformance.kt`) runs the same programs on the
interpreter, Node.js, the JVM and native code (clang) and requires identical output. Backends use the
resolution attributes `Expr.type`, `Local.variable`, `Call.callee`, `Break.target`, `Lambda.captures`,
`Module.isCaptured`, `Case.variables`. Known limits: exceptions skip the drops of the LLVM frames they leave;
the formatting of very large or small doubles differs between platforms. The Tiny example converts its functions to MSI
in `ToMsi.kt` and runs them with the interpreter and as JVM bytecode.

## A Java compiler (`java` module)

`com.leko.forge.java` compiles a substantial subset of Java to MSI, so Java programs run on the MSI
interpreter, as JVM bytecode, as JavaScript and as native code:

- classes, interfaces, abstract classes, single inheritance with virtual dispatch, `super(...)`/`this(...)`,
  static and instance fields and methods, overloading, `instanceof`, casts
- `int`, `long`, `double`, `boolean`, `String`, arrays (also multi-dimensional and literals), numeric promotion
- `if`, `while`, `do`, `for`, for-each, labelled `break`/`continue`, compound assignment, `++`/`--`, `?:`
- exceptions (`throw`, `try`/`catch` with multi-catch, `finally`), `System.out.println`, a prelude written
  in Java (`Throwable` hierarchy, `Math`)
- diagnostics with source positions (`Main.java:7:9: error: cannot find symbol x`)

Everything is a `JavaFeature`: syntax (alternatives of the abstract rules in `JavaGrammar`), typing
(`jtype`), checks (contributions to `problems`), scopes and lowering (`msi`). The Java language is
`JavaCompiler.standardFeatures`; extensions are features too, also from other modules. Included:
`Records` (Java 16 records, as pure syntax), `Unless` (a new statement) and `PowerOperator` (`**`).
The tests add Java's `assert` from outside the module.

```kotlin
val compiler = JavaCompiler(JavaCompiler.standardFeatures + Records + PowerOperator)
val result = compiler.compile("Main.java" to source)
if (result.succeeded) result.run() else result.problems.forEach(::println)
```

```
./gradlew :java:installDist
java/build/install/forge-javac/bin/forge-javac --enable records,power Main.java          # run
java/build/install/forge-javac/bin/forge-javac --run-jvm Main.java                       # as JVM bytecode
java/build/install/forge-javac/bin/forge-javac --emit js -o main.js Main.java            # or msi, llvm
```

Not supported: generics, nested and anonymous classes, lambdas, `switch`, `char`, string methods
other than `equals`, assignments and `++` inside expressions, the standard library beyond the prelude.

## A Python compiler (`python` module)

`com.leko.forge.python` is an extensible Python 3 compiler. Its main target is **Python**: the
program is checked, language extensions are lowered to standard Python, and the result runs on
CPython (`python3`). The generated code keeps the source line numbers, so tracebacks point to the
right lines. Compiling a file also compiles the modules it imports from its directory, with the same
extensions, and writes them out as a package tree.

Compiling to **JavaScript** is an optional second backend (runtime included; run it with `node`): a
JavaScript library implements Python's semantics (unbounded `int` as BigInt, Python's `//`, `%`,
comparisons, truthiness, the class model with C3 method resolution order, dunder methods,
`super()`, properties, exceptions as classes, generators, about 50 builtins and the methods of
`str`, `list`, `dict` and `set`). Anything it doesn't implement is reported with its position
(`main.py:2:1: NotImplementedError: async def is not supported by the JavaScript backend`).

- **Layout:** indentation becomes INDENT/DEDENT markers in a layout pass (with a map back to source positions).
- **Name resolution:** Python's rules (local, enclosing, global, builtin; `global`/`nonlocal`; class scopes invisible to methods; `:=` in comprehensions), written as Forge attributes. This also gives static errors such as `main.py:3:12: NameError: name 'x' is not defined`.
- **The language:**
  - functions with defaults, positional-only (`/`) and keyword-only (`*`) parameters, `*args`/`**kwargs`, closures, lambdas, decorators, annotations
  - classes with inheritance, class keywords (`metaclass=`, `__init_subclass__` arguments)
  - comprehensions, generators, `yield from`, `:=`, `**` in dict displays
  - `try`/`except`/`except*`/`else`/`finally`, `raise ... from`, `assert`, `with` (also parenthesized)
  - `import`, `from ... import` (relative imports, `*`, `__future__`)
  - coroutines: `async def`, `await`, `async for`, `async with`, asynchronous comprehensions
  - f-strings with format specs and `{x=}`, bytes, `...`
- **Checks** with CPython's messages: misplaced `return`/`yield`/`await`/`async for`, `from __future__` placement, `import *` in functions, relative imports without a package, names imported from the program's own modules that don't exist, parameter order, and more.
- **Features:** like the Java compiler, everything is a `PythonFeature` (syntax, `boundNames`, checks, `py`, optionally `js` and runtime code). Extensions: `MatchStatement` (Python 3.10 pattern matching) and `PipeOperator` (`x |> f`, lowered to calls). The tests add a `repeat n:` statement from outside the module.
- **Tested against CPython:** the test programs must print exactly what CPython prints, compiled to Python and (where supported) to JavaScript, and the generated Python must have the same syntax tree as the source.

```
./gradlew :python:installDist
python/build/install/forge-python/bin/forge-python --enable pipe main.py arg1        # compile, run with python3
python/build/install/forge-python/bin/forge-python --emit -o out main.py             # python3 out/main.py
python/build/install/forge-python/bin/forge-python --target js main.py               # compile to JavaScript, run with node
```

The generated Python uses the syntax of its source, so it needs the CPython version the source
needs; only a string containing `'` inside an f-string expression makes it require 3.12.

**Not supported by the JavaScript backend:** imports, coroutines, bytes, `except*`, class keywords,
builtins it doesn't implement (`open`, ...), subclassing builtin types, complex numbers.

## Using it

```kotlin
// build.gradle.kts, after ./gradlew :forge:publishToMavenLocal
repositories { mavenLocal() }
dependencies { implementation("com.leko:forge:1.0-SNAPSHOT") }
```

Parse from text, bytes, and on the JVM from a `Reader` (text), an `InputStream` (binary), a `Path` or
a `File`:

```kotlin
val program = Tiny.program.parse(Path("main.tiny"), errors = CaretErrors)
val classFile = ClassFormat.classFile.parse(File("Main.class").inputStream())
```

## Platforms

The core is common Kotlin. Platform differences are isolated in a small internal layer
(`Platform.kt` with `Platform.jvm.kt` and `Platform.js.kt`): identity maps, weak references, thread
locals, locks, and the optional reflection.

Reflection is only a convenience, used on the JVM:

| Needs                                  | JVM                      | Everywhere (incl. JVM)                          |
|----------------------------------------|--------------------------|-------------------------------------------------|
| Children of a node                     | fields (filtered by `@Child`) | `TreeNode.children`, `children<T> { ... }` |
| Subtypes, for "most specific" matches  | class hierarchy          | `supertypes<T>(...)`; else exact class wins     |
| Owner type of a collection             | property receiver        | `coll(initial, owner = Root::class)`            |

Without reflection, `@Child` alone can not help: reading annotations at runtime *is* reflection.
It can be read at compile time instead. A KSP processor (a planned separate module) would generate
the `children<T> { }` and `supertypes<T>()` registrations from `@Child`-annotated classes into one
function per module, e.g. `registerTinyModel()`. The runtime already supports those registrations,
so the processor only produces code users could also write by hand.

Adding a target, such as native or wasm, means implementing `Platform.kt` for it: about 60 lines,
see `Platform.js.kt`. Native would use `kotlin.native.ref.WeakReference`, `identityHashCode` and a
lock from `kotlinx-atomicfu`.

## Building

```
./gradlew :forge:allTests          # JVM and JS (Node.js) tests
./gradlew :examples:run            # run Tiny on its example; --args=file.tiny for your own file
./gradlew :forge:publishToMavenLocal
```

The library is compiled in explicit API mode: every public declaration is deliberate.
