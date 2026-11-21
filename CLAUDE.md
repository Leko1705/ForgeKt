# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

ForgeKt is a Kotlin library for building compilers: a packrat parser (text and binary), attribute grammars (`syn`, `inh`, `coll`, `circular`) and graphs built from ASTs (flows). `README.md` has the feature overview and the user-facing API.

## Commands

```
./gradlew :forge:allTests                                   # all tests, JVM + JS (Node.js)
./gradlew :forge:jvmTest --tests "com.leko.inhtest.InhTest" # one JVM test class (also works with a method: "...InhTest.mainExample")
./gradlew :forge:jsNodeTest --tests "com.leko.forge.mptest.AbstractRuleTest"
./gradlew :forge:compileKotlinMetadata                      # checks commonMain uses no JVM-only API
./gradlew :examples:run                                     # Tiny on its bundled example; --args=path/to/file.tiny
./gradlew :forge:publishToMavenLocal                        # com.leko:forge(-jvm|-js):1.0-SNAPSHOT
./gradlew :java:test                                        # the Java compiler (also runs programs on node and clang)
./gradlew :java:installDist                                 # java/build/install/forge-javac/bin/forge-javac
./gradlew :python:test                                      # the Python compiler (runs the output on python3 and node, compares with CPython)
./gradlew :python:installDist                               # python/build/install/forge-python/bin/forge-python
```

There is no linter. The library compiles in **explicit API mode** (`explicitApi()` in `forge/build.gradle.kts`): every public declaration needs `public` and an explicit return type. Anything not meant for users should be `internal`. Use `@PublishedApi internal` when a public `inline` function needs it.

## Layout

- `forge/`: the library, Kotlin Multiplatform (`jvm()` and `js { nodejs() }`).
  - `commonMain`: all of the logic.
  - `jvmMain`: reflection, the JVM collections and locks, and the `java.io` parse overloads (`ParseJvm.kt`, as extension functions on `Rule`).
  - `jsMain`: the JS platform layer.
- `forge/src/commonTest`: tests without reflection. They run on JVM *and* JS, so trees there must use `TreeNode`, `children<T> { }` and `supertypes<T>()`.
- `forge/src/jvmTest`: older tests that rely on JVM reflection to find children. Each file has its own package (`com.leko.inhtest`, `com.leko.graphtest`, …). Test-only node classes in package `com.leko.forge` would clash with these through `import com.leko.forge.*`, so common tests live in `com.leko.forge.mptest`.
- `examples/`: Tiny (`com.leko.tiny`), a single-file language in `Main.kt` that demonstrates every feature. Its numbered sections match the features. Keep it working when APIs change.

## Architecture

**Platform layer.** `commonMain/.../Platform.kt` declares `internal expect` functions for everything platform-specific: identity maps, weak identity maps, weak refs, thread locals, `locked {}`, and the optional reflection (`reflectedChildren`, `reflectedIsSubtype`, `reflectedReceiver`). Adding a target means implementing only this file. Common code must not use `java.*`, `synchronized`, `String.format`, `KClass.qualifiedName` (not supported on JS; use `simpleName`) or classes implementing function types (forbidden on JS; this is why `Parselet` is a `fun interface`).

**Identity everywhere.** AST nodes are often data classes, so every per-node map (attribute caches, parents, graph vertices, memo tables) is identity-based. Never key node data by `equals`/`hashCode`.

**Tree registry (`Tree.kt`).** Parent links live in a global weak identity map, filled by `tree(root)`. `Rule.parse` calls `tree(root)` automatically. Children are resolved in this order: the `TreeNode` interface, then `children<T>{}` registrations, then JVM reflection (fields, restricted to `@Child`-annotated ones if any exist). `Tree.mostSpecific` is the shared dispatch rule for inh equations, syn implementations, coll and flows: the most specific type wins, and the last added wins ties. Subtypes come from JVM reflection or from `supertypes<T>()`; without either, the exact runtime class wins.

**Evaluation context (`EvalContext`).** A thread-local stack of the node currently being evaluated. Receiver-less inherited attributes (`val x by inh<T>()...` used inside other equations) read it. `syn` pushes its receiver, and `inh` equations run in the *parent's* context (JastAdd semantics).

**Attributes are extensible objects.** `inh<T>()`, `coll(initial)`, `graph<Root>()` and `rule<T>()` (abstract rules) return objects whose `.on…`/`.flow`/`.infix…` methods can be called from any module. Adding something replaces the internal cache map, which invalidates cached values. `syn` uses a name-based `SynRegistry` instead: all `syn` delegates with the same property name form one attribute, dispatched by runtime type. Extension modules are `object`s with `init` blocks and only register once referenced. Kotlin initializes top-level properties per file lazily, which is why contributions are not declared as free-standing top-level statements.

**Caching vs. fixpoints.** `circular` attributes compute fixpoints through a thread-local `CircularEval` cycle. While a cycle runs, `syn`, `inh` and `coll` must not store cached values (`CircularEval.inCycle()`), because they may depend on approximations.

**Parser (`Parser.kt`, `AbstractRule.kt`).**
- Rule bodies are `ParseScope.() -> T` lambdas that consume input imperatively. A mismatch throws a singleton `Mismatch`, and `optional`/`many`/`oneOf` backtrack by resetting `pos`.
- `apply()` memoizes per `(rule, position, precedence)` and supports left recursion by seed growing, marking rules involved in a growing seed as not memoizable.
- `AbstractRule` parses by precedence climbing. `operand()` reads an operator-context stack, and `apply()` pushes a `null` barrier on it so `operand()` is only valid directly inside operator bodies.
- For binary input, `pos` counts bits (`unit = 8`), and text primitives operate on a Latin-1 view of the bytes at byte boundaries.
- Errors record the furthest failure position and the set of expected items, then go through an `ErrorFormatter`.

**Flows (`Flow.kt`).** A flow rule runs once per AST node, top-down from the graph root, and records an entry target, port bindings (`port(child) then target`) and edges from vertices. Resolution then turns targets into vertices. Unbound ports at the root become `ExitVertex`es. `inherited` ports are looked up through the flow parents. Inside a flow, the node itself and `vertex(...)` objects are vertices, and every other object is a child flow.

**MSI (`commonMain/.../msi/`).** The Multi-System-Tree: a typed IR with structured control flow that languages lower into and backends consume.
- **Platform independence:** all MSI nodes implement `TreeNode`, including `Field`/`Param`/`Variant`, so JVM reflection never makes shared `Type` singletons children.
- **`Check.kt`** implements `validate()` with Forge attributes:
  - `Expr.type` is one `syn` with a `when` over all expression kinds.
  - Scopes, loops, the enclosing callable and type parameters are `inh`.
  - Problems are a `coll` with `owner = Module::class`.
  - It also provides what backends need: generic type argument inference (`Call.typeArguments` …), `Lambda.captures` and `Module.isCaptured(v)`. Captured variables live in shared boxes in the JVM and LLVM backends.
- **`Interpreter.kt` is the reference semantics.** It picks arithmetic by *static* type (`e.left.type`), never by runtime value class, because Kotlin/JS can't tell `Int` from `Double`. Define a new semantic there first.
- **`Flows.kt`** is the default CFG (`msiFlows`). `msiFlows` is a getter, so the flow registrations are always initialized.
- **Backends** (`js/` and `llvm/` are common code; `jvm/` is in `jvmMain`, using ASM with tree `MethodNode`s):
  - **JS:** `I64` becomes `BigInt`, and every loop gets an explicit JS label.
  - **JVM:** generics are erased to `Object` and arrays are `Object[]`, with boxing at representation boundaries (`MethodContext.coerce`). `finally` is inlined on every exit, with exception-table ranges split around the inlined code.
  - **LLVM:** every value is one `i64` word, heap objects carry a type id, and exceptions use `setjmp`/`longjmp` through the C runtime in `LlvmRuntime.kt`.
- **Ownership (`Ownership.kt`):**
  - `validate()` runs it only when `Module.usesOwnership` is true, and only after the basic checks pass. It's a property of the module, never a caller option, because the LLVM backend frees owned memory based on it.
  - It is an abstract interpretation per function and lambda over `msiFlows`/`lambdaFlows`: a forward fixpoint for moved variables and `held` origins (loans, parameter lifetimes, closure captures), a backward pass for liveness (a loan blocks access only while a holder is live), then a reporting replay in evaluation order. Borrows passed as call arguments are temporaries until the call ends.
  - Variables are tracked by *identity ids*, because MSI variables are data classes.
  - Reborrows (`Borrow(Get(Deref(r)))`) create loans with path `"*"`; return and store checks skip those.
  - `Type.Ref` and `Type.Struct` ignore lifetimes in `equals`.
  - `insertCopies()` reuses the checker (`copySites`) together with the general `Module.rewrite`.
  - Backends: references are getter/setter objects in the interpreter and JS, `MsiRef` (`BoxRef`/`FieldRef`, borrowed locals are boxed like captured ones) on the JVM, and raw addresses in LLVM.
  - The LLVM backend keeps a stack of `exits` (block `Scope`s and `TryContext`s), so drops and `finally` interleave correctly on `break`/`continue`/`return`. Moves store 0 into the source slot. The C runtime registers every object and collects conservatively (scanning the stack up to `__libc_stack_end`).
- **Every backend change must keep `Conformance.kt` passing on all four runtimes:** `ConformanceTest` (common) plus `backends.*BackendTest` (jvmTest, which need `node` and `clang` on `PATH`). New MSI features need a conformance program with its expected output.
- **Name clashes:** MSI names (`Block`, `Func`, `Return`, `Type`, `Let`, …) clash with language ASTs and with the test packages. Import them explicitly or with aliases (see `examples/.../ToMsi.kt`).

**Java compiler (`java/`).**
- **Every construct is a `JavaFeature` object** (`CoreTypes`, `CoreExpressions`, `CoreStatements`, `CoreClasses`, `CoreExceptions`, `extensions/*`). Its `init` registers grammar alternatives on `JavaGrammar`'s abstract rules, check contributions on `problems`, and scope equations on `scopes`.
- **Typing and lowering** are *private member-extension* `syn` properties (`private val X.jtype by syn {}`, `private val X.msi by syn {}`) that register with the dispatchers `JExpr.jtype`/`JExpr.msi`/`JStmt.msi` by property name.
- **Features register on first reference.** `JavaCompiler` touches its features. A feature's syntax is only accepted while a compiler enabling it parses (`requireEnabled()` throws `FeatureDisabled` with a position), and only exists once the feature object is loaded.
- **AST nodes must be distinct objects,** never Kotlin `object`s: a shared instance gets one parent for the whole tree, so `inh` attributes resolve wrongly.
- **Mark children with `@Child`,** so types and names stored in nodes don't become tree children.
- **Object model in MSI** (`Lowering.kt`): one shared struct `$Object` (class id, vtable, all instance fields as `Class__field`), one `$VTable` global per class with a slot per method signature (`ClassTable.slots`), dispatcher functions `$call__i`, constructors as `Class__init_i` + factory `Class__new_i`. Helpers (casts, array literals, …) are generated on demand via `JNode.helper`.
- **`for` and `do`-`while`** lower to an outer labelled loop plus an inner one-pass loop or a first-flag, so `continue` behaves like Java's.
- **Test programs** live in `java/src/test/resources`, with their expected output in `Programs`. They are run on the interpreter, the JVM, node and clang.

**Python compiler (`python/`).**
- **Pipeline:** `Layout` turns indentation into `\n`/`\u0002`/`\u0003` markers (NEWLINE/INDENT/DEDENT), drops comments, joins bracketed lines, and keeps an `origin` map, so `PyProblem` positions refer to the source. The grammar matches the markers as literals.
- **Two backends.** Python (`py`, `PyGen.kt`) is the main target: `PyCompilation.run()` runs `python3`. JavaScript (`js`, `JsGen.kt` + `runtime.js`) is optional: constructs it lacks call `jsUnsupported(node, what)`, which `toJavaScript()` turns into an `UnsupportedOnTarget` with a position. New core syntax needs `py`; `js` may be unsupported.
- **Features** (`PythonFeature` objects) work like the Java compiler's: syntax, `boundNames` (and `bindingScope` for constructs like `:=`), checks on `problems`, `py`, `js`. Extensions lower to standard Python in `py`; helper code goes through `pythonSupport(...)` (prepended to every module compiled with the feature). A feature's syntax that needs a node position must come from a rule returning that node: rules returning lists get no `spanOf` (problems then use the nearest parent with a span).
- **Python output:** `pyOperand(e, min)` parenthesizes by `pyPrecedence` (a `syn`, default atom). Statements go through `pyBlock`/`pySuite`, which mark source lines; `alignLines` pads with blank lines so the generated code keeps the source line numbers. `CPythonTest` checks that `ast.dump` of the output equals that of the source, so printing must be exact (e.g. `elif`, tuples, f-string parts).
- **Modules:** `compile(File)` follows imports through a `ModuleFinder` (`DirectoryModuleFinder`) and compiles every local module (`PyModule.qualifiedName`, `isPackage`, `graph`); other imports are left to Python. `ImportFrom.moduleName` (not `module`, which is `PyNode.module`).
- **Names:** resolution (`Scopes.kt`) follows LEGB; builtins are `PythonBuiltins` (CPython's list plus feature additions); `from m import *` makes unknown globals legal. JavaScript names are prefixed by kind: `v_` function and comprehension locals, `c_` class-body locals (so methods can't see them), `m_` module globals, `$b.` builtins. Locals are declared at the top of their JavaScript function.
- **JavaScript semantics live in `src/main/resources/.../runtime.js`.** Generated code only calls `$`-functions (`$call`, `$getattr`, `$add`, …). Features can add runtime code with `PythonRuntime.add`. Its builtins are scraped from `runtime.js` (`builtin`/`nb`/`nativeClass`/`exceptionClass` calls with literal names); a Python builtin missing there is a `jsUnsupported`.
- **Ordered choice matters:** the `name` token must not be followed by a quote (f-strings), `//` must come before `/`, `:=` before a plain name, and literal patterns are not expressions (`0 | 1`).
- **Tests:** `CPythonTest` runs the programs in `src/test/resources` (`imports/` is a multi-module program) with `python3` and compares stdout and exit status with the Python output and, for programs it supports, the JavaScript output. New language support needs a program there.

## Conventions

- New public API needs KDoc. The existing files document semantics (dispatch, caching, ordering) in the KDoc of the factory function (`syn`, `inh`, `coll`, `rule<T>()`, `graph`).
- The project is not under git. Avoid destructive, large-scale moves without making a backup first.
