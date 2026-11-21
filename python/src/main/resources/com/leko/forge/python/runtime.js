// The Python runtime for JavaScript: Python's values, object model and builtins.
// int -> BigInt, float -> number, bool -> boolean, str -> string, None -> null, list -> Array,
// tuple -> Tuple, dict -> Dict, set -> PySet, objects -> PyObject, classes -> PyClass.

const $out = typeof $pyOut === "function" ? $pyOut : (typeof process !== "undefined" ? (s) => process.stdout.write(s) : (s) => console.log(s));
const $b = Object.create(null);
function builtin(name, value) { $b[name] = value; if (typeof value === "function" && !value.$name) value.$name = name; return value; }

// ---- values ------------------------------------------------------------------------------------

class PyClass {
  constructor(name, bases, dict, native) {
    this.name = name; this.bases = bases; this.dict = dict || new Map(); this.native = native || null;
    this.mro = mroOf(this);
  }
}
class PyObject { constructor(cls) { this.$cls = cls; this.$dict = new Map(); } }
class Tuple { constructor(items) { this.items = items; } }
class Dict {
  constructor() { this.map = new Map(); }
  get(k) { const e = this.map.get($key(k)); return e === undefined ? undefined : e[1]; }
  set(k, v) { const h = $key(k); const e = this.map.get(h); if (e) e[1] = v; else this.map.set(h, [k, v]); }
  has(k) { return this.map.has($key(k)); }
  delete(k) { return this.map.delete($key(k)); }
  keys() { return [...this.map.values()].map(e => e[0]); }
}
class PySet {
  constructor(items) { this.map = new Map(); if (items) for (const x of items) this.add(x); }
  add(x) { const h = $key(x); if (!this.map.has(h)) this.map.set(h, x); }
  has(x) { return this.map.has($key(x)); }
  delete(x) { return this.map.delete($key(x)); }
  items() { return [...this.map.values()]; }
}
class Range { constructor(start, stop, step) { this.start = start; this.stop = stop; this.step = step; } }
class BoundMethod { constructor(self, func) { this.self = self; this.func = func; } }
class Property { constructor(fget, fset) { this.fget = fget; this.fset = fset; } }
class StaticMethod { constructor(f) { this.f = f; } }
class ClassMethod { constructor(f) { this.f = f; } }
class Super { constructor(cls, self) { this.cls = cls; this.self = self; } }
const $NotImplemented = { $notImplemented: true };

function mroOf(cls) {
  // C3 linearization
  const seqs = cls.bases.map(b => [...b.mro]).concat([[...cls.bases]]);
  const result = [cls];
  for (;;) {
    const nonEmpty = seqs.filter(s => s.length > 0);
    if (nonEmpty.length === 0) return result;
    let head = null;
    for (const s of nonEmpty) {
      const candidate = s[0];
      if (!nonEmpty.some(t => t.indexOf(candidate) > 0)) { head = candidate; break; }
    }
    if (head === null) throw $error("TypeError", "Cannot create a consistent method resolution order (MRO)");
    result.push(head);
    for (const s of nonEmpty) if (s[0] === head) s.shift();
  }
}

/** A native function: called with (args, kwargs). */
function native(name, f) { f.$native = true; f.$name = name; return f; }
function nativeClass(name, bases, methods, construct) {
  const dict = new Map();
  for (const [k, v] of Object.entries(methods || {})) dict.set(k, typeof v === "function" && !v.$sig ? native(k, v) : v);
  return builtin(name, new PyClass(name, bases, dict, construct || null));
}

// ---- types -------------------------------------------------------------------------------------

const object = nativeClass("object", [], {
  __init__: (args) => null,
  __eq__: (args) => args[0] === args[1],
  __ne__: (args) => { const r = $eq(args[0], args[1]); return !r; },
  __str__: (args) => $repr(args[0]),
  __repr__: (args) => { const o = args[0]; return `<__main__.${$type(o).name} object at 0x${id(o).toString(16).padStart(12, "0")}>`; },
  __hash__: (args) => BigInt(id(args[0])),
});
object.mro = [object];
const type = nativeClass("type", [object], {}, (args) => $type(args[0]));
const int = nativeClass("int", [object], {
  bit_length: (args) => BigInt(args[0] < 0n ? (-args[0]).toString(2).length : args[0] === 0n ? 0 : args[0].toString(2).length),
}, (args) => toInt(args[0] === undefined ? 0n : args[0], args[1]));
const bool = nativeClass("bool", [int], {}, (args) => args.length ? $bool(args[0]) : false);
const float = nativeClass("float", [object], {
  is_integer: (args) => Number.isInteger(args[0]),
}, (args) => toFloat(args.length ? args[0] : 0));
const NoneType = nativeClass("NoneType", [object], {});
const functionType = new PyClass("function", [object], new Map());
const methodType = new PyClass("method", [object], new Map());
const generatorType = new PyClass("generator", [object], new Map([
  ["__next__", native("__next__", ([g]) => { const r = g.next(); if (r.done) throw $error("StopIteration"); return r.value; })],
  ["__iter__", native("__iter__", ([g]) => g)],
  ["send", native("send", ([g, v]) => { const r = g.next(v); if (r.done) throw $error("StopIteration"); return r.value; })],
]));
const rangeType = nativeClass("range", [object], {}, (args) => {
  const [a, b, c] = args.map(toIndex);
  if (args.length === 1) return new Range(0n, a, 1n);
  if (c === 0n) throw $error("ValueError", "range() arg 3 must not be zero");
  return new Range(a, b, args.length > 2 ? c : 1n);
});

let ids = new WeakMap(), nextId = 0x7f0000000000;
function id(o) { if (o === null || typeof o !== "object" && typeof o !== "function") return 0; let i = ids.get(o); if (!i) { i = nextId += 16; ids.set(o, i); } return i; }

function $type(x) {
  switch (typeof x) {
    case "bigint": return int;
    case "number": return float;
    case "boolean": return bool;
    case "string": return str;
    case "function": return x instanceof PyClass ? type : functionType;
  }
  if (x === null || x === undefined) return NoneType;
  if (x instanceof PyObject) return x.$cls;
  if (Array.isArray(x)) return list;
  if (x instanceof Tuple) return tuple;
  if (x instanceof Dict) return dict;
  if (x instanceof PySet) return set;
  if (x instanceof Range) return rangeType;
  if (x instanceof PyClass) return type;
  if (x instanceof BoundMethod) return methodType;
  if (x instanceof Property) return $b.property;
  if (x instanceof StaticMethod) return $b.staticmethod;
  if (x instanceof ClassMethod) return $b.classmethod;
  if (typeof x.next === "function") return generatorType;
  return object;
}

function lookup(cls, name) {
  for (const c of cls.mro) { const v = c.dict.get(name); if (v !== undefined) return v; }
  return undefined;
}
function isSubclass(c, base) { return c.mro.includes(base); }
function $isinstance(x, cls) {
  if (cls instanceof Tuple) return cls.items.some(c => $isinstance(x, c));
  return isSubclass($type(x), cls);
}

// ---- exceptions --------------------------------------------------------------------------------

const BaseException = nativeClass("BaseException", [object], {
  __init__: (args) => { const self = args[0]; self.$dict.set("args", new Tuple(args.slice(1))); return null; },
  __str__: (args) => { const a = $getattr(args[0], "args").items; return a.length === 0 ? "" : a.length === 1 ? $str(a[0]) : $repr(new Tuple(a)); },
  __repr__: (args) => { const a = $getattr(args[0], "args").items; return `${$type(args[0]).name}(${a.map($repr).join(", ")})`; },
});
function exceptionClass(name, base, str) { return nativeClass(name, [base], str ? { __str__: str } : {}); }
const Exception = exceptionClass("Exception", BaseException);
exceptionClass("SystemExit", BaseException); exceptionClass("KeyboardInterrupt", BaseException);
const ArithmeticError = exceptionClass("ArithmeticError", Exception);
exceptionClass("ZeroDivisionError", ArithmeticError); exceptionClass("OverflowError", ArithmeticError);
const LookupError = exceptionClass("LookupError", Exception);
exceptionClass("IndexError", LookupError);
exceptionClass("KeyError", LookupError, (args) => { const a = $getattr(args[0], "args").items; return a.length === 1 ? $repr(a[0]) : $b.BaseException.dict.get("__str__")(args); });
exceptionClass("ValueError", Exception); exceptionClass("TypeError", Exception); exceptionClass("NameError", Exception);
exceptionClass("AttributeError", Exception); exceptionClass("AssertionError", Exception); exceptionClass("StopIteration", Exception);
exceptionClass("ImportError", Exception);
const RuntimeError = exceptionClass("RuntimeError", Exception);
exceptionClass("NotImplementedError", RuntimeError); exceptionClass("RecursionError", RuntimeError);
exceptionClass("UnboundLocalError", $b.NameError);

/** A new exception of the builtin class [name]. */
function $error(name, message) { return $call($b[name], message === undefined ? [] : [message]); }
/** What `raise x` throws: an instance (classes are instantiated). */
function $exception(x) {
  if (x instanceof PyClass) x = $call(x, []);
  if (!$isinstance(x, BaseException)) return $error("TypeError", "exceptions must derive from BaseException");
  return x;
}
/** Turns JavaScript errors into Python exceptions. */
function $wrap(e) {
  if (e instanceof PyObject) return e;
  if (e instanceof RangeError && /call stack/.test(e.message)) return $error("RecursionError", "maximum recursion depth exceeded");
  if (e instanceof TypeError) return $error("TypeError", e.message);
  return $error("RuntimeError", String(e && e.message || e));
}
builtin("$undefined", (name) => { throw $error("NameError", `name '${name}' is not defined`); });

// ---- conversions -------------------------------------------------------------------------------

function toInt(x, base) {
  if (typeof x === "bigint") return x;
  if (typeof x === "boolean") return x ? 1n : 0n;
  if (typeof x === "number") { if (!Number.isFinite(x)) throw $error(Number.isNaN(x) ? "ValueError" : "OverflowError", "cannot convert float to integer"); return BigInt(Math.trunc(x)); }
  if (typeof x === "string") {
    const s = x.trim().replace(/_/g, "");
    const b = base === undefined ? 10 : Number(base);
    const valid = b === 10 ? /^[+-]?\d+$/ : b === 16 ? /^[+-]?(0[xX])?[0-9a-fA-F]+$/ : b === 2 ? /^[+-]?(0[bB])?[01]+$/ : b === 8 ? /^[+-]?(0[oO])?[0-7]+$/ : /^[+-]?\w+$/;
    if (!valid.test(s)) throw $error("ValueError", `invalid literal for int() with base ${b}: ${$repr(x)}`);
    const negative = s.startsWith("-");
    const digits = s.replace(/^[+-]/, "").replace(/^0[xXbBoO]/, "");
    const prefix = b === 16 ? "0x" : b === 2 ? "0b" : b === 8 ? "0o" : "";
    const v = prefix ? BigInt(prefix + digits) : BigInt(digits);
    return negative ? -v : v;
  }
  const m = x instanceof PyObject && lookup(x.$cls, "__int__");
  if (m) return $call(m, [x]);
  throw $error("TypeError", `int() argument must be a string or a number, not '${$type(x).name}'`);
}
function toFloat(x) {
  if (typeof x === "number") return x;
  if (typeof x === "bigint") return Number(x);
  if (typeof x === "boolean") return x ? 1 : 0;
  if (typeof x === "string") {
    const s = x.trim().toLowerCase();
    if (s === "inf" || s === "+inf" || s === "infinity") return Infinity;
    if (s === "-inf" || s === "-infinity") return -Infinity;
    if (s === "nan") return NaN;
    if (!/^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/.test(s)) throw $error("ValueError", `could not convert string to float: ${$repr(x)}`);
    return Number(s);
  }
  throw $error("TypeError", `float() argument must be a string or a real number, not '${$type(x).name}'`);
}
function toIndex(x) {
  if (typeof x === "bigint") return x;
  if (typeof x === "boolean") return x ? 1n : 0n;
  throw $error("TypeError", `'${$type(x).name}' object cannot be interpreted as an integer`);
}
function isNumber(x) { return typeof x === "bigint" || typeof x === "number" || typeof x === "boolean"; }
function toNumeric(x) { return typeof x === "boolean" ? (x ? 1n : 0n) : x; }

// ---- text --------------------------------------------------------------------------------------

function floatRepr(x) {
  if (Number.isNaN(x)) return "nan";
  if (!Number.isFinite(x)) return x > 0 ? "inf" : "-inf";
  if (x === 0) return Object.is(x, -0) ? "-0.0" : "0.0";
  const [mantissa, exponentText] = x.toExponential().split("e");
  const exponent = Number(exponentText);
  if (exponent < -4 || exponent >= 16) {
    const m = mantissa.includes(".") ? mantissa : mantissa;
    return `${m}e${exponent < 0 ? "-" : "+"}${String(Math.abs(exponent)).padStart(2, "0")}`;
  }
  const negative = mantissa.startsWith("-");
  const digits = mantissa.replace("-", "").replace(".", "");
  let s;
  if (exponent >= 0) {
    const whole = digits.length > exponent + 1 ? digits.slice(0, exponent + 1) : digits.padEnd(exponent + 1, "0");
    const frac = digits.length > exponent + 1 ? digits.slice(exponent + 1) : "0";
    s = `${whole}.${frac}`;
  } else s = `0.${"0".repeat(-exponent - 1)}${digits}`;
  return (negative ? "-" : "") + s;
}
function strRepr(s) {
  const quote = s.includes("'") && !s.includes('"') ? '"' : "'";
  let r = quote;
  for (const c of s) {
    if (c === quote || c === "\\") r += "\\" + c;
    else if (c === "\n") r += "\\n"; else if (c === "\t") r += "\\t"; else if (c === "\r") r += "\\r";
    else if (c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127) r += "\\x" + c.charCodeAt(0).toString(16).padStart(2, "0");
    else r += c;
  }
  return r + quote;
}
let reprDepth = new Set();
function $repr(x) {
  switch (typeof x) {
    case "string": return strRepr(x);
    case "bigint": return x.toString();
    case "number": return floatRepr(x);
    case "boolean": return x ? "True" : "False";
  }
  if (x === null || x === undefined) return "None";
  if (x instanceof PyObject) { const m = lookup(x.$cls, "__repr__"); return $call(m, [x]); }
  if (typeof x === "object" && (Array.isArray(x) || x instanceof Dict || x instanceof PySet)) {
    if (reprDepth.has(x)) return Array.isArray(x) ? "[...]" : "{...}";
    reprDepth.add(x);
    try {
      if (Array.isArray(x)) return `[${x.map($repr).join(", ")}]`;
      if (x instanceof Dict) return `{${[...x.map.values()].map(([k, v]) => `${$repr(k)}: ${$repr(v)}`).join(", ")}}`;
      return x.map.size === 0 ? "set()" : `{${x.items().map($repr).join(", ")}}`;
    } finally { reprDepth.delete(x); }
  }
  if (x instanceof Tuple) return x.items.length === 1 ? `(${$repr(x.items[0])},)` : `(${x.items.map($repr).join(", ")})`;
  if (x instanceof Range) return x.step === 1n ? `range(${x.start}, ${x.stop})` : `range(${x.start}, ${x.stop}, ${x.step})`;
  if (x instanceof PyClass) return x.native ? `<class '${x.name}'>` : `<class '__main__.${x.name}'>`;
  if (x instanceof BoundMethod) return `<bound method ${$type(x.self).name}.${x.func.$name} of ${$repr(x.self)}>`;
  if (typeof x === "function") return x.$native && !x.$sig ? `<built-in function ${x.$name}>` : `<function ${x.$name} at 0x${id(x).toString(16).padStart(12, "0")}>`;
  if (typeof x.next === "function") return `<generator object at 0x${id(x).toString(16)}>`;
  return String(x);
}
function $str(x) {
  if (typeof x === "string") return x;
  if (x instanceof PyObject) return $call(lookup(x.$cls, "__str__"), [x]);
  return $repr(x);
}

// ---- truth, equality, hashing ------------------------------------------------------------------

function $bool(x) {
  switch (typeof x) {
    case "boolean": return x;
    case "bigint": return x !== 0n;
    case "number": return x !== 0;
    case "string": return x.length > 0;
  }
  if (x === null || x === undefined) return false;
  if (Array.isArray(x)) return x.length > 0;
  if (x instanceof Tuple) return x.items.length > 0;
  if (x instanceof Dict || x instanceof PySet) return x.map.size > 0;
  if (x instanceof Range) return rangeLength(x) > 0n;
  if (x instanceof PyObject) {
    const b = lookup(x.$cls, "__bool__"); if (b) return $bool($call(b, [x]));
    const l = lookup(x.$cls, "__len__"); if (l) return $call(l, [x]) !== 0n;
  }
  return true;
}
function $eq(a, b) {
  if (a === b) return true;
  if (isNumber(a) && isNumber(b)) { a = toNumeric(a); b = toNumeric(b); return typeof a === typeof b ? a === b : Number(a) === Number(b); }
  if (a instanceof PyObject || b instanceof PyObject) {
    if (a instanceof PyObject) { const m = lookup(a.$cls, "__eq__"); if (m && m !== object.dict.get("__eq__")) { const r = $call(m, [a, b]); if (r !== $NotImplemented) return $bool(r); } }
    if (b instanceof PyObject) { const m = lookup(b.$cls, "__eq__"); if (m && m !== object.dict.get("__eq__")) { const r = $call(m, [b, a]); if (r !== $NotImplemented) return $bool(r); } }
    return false;
  }
  if (Array.isArray(a) && Array.isArray(b) || a instanceof Tuple && b instanceof Tuple) {
    const x = Array.isArray(a) ? a : a.items, y = Array.isArray(b) ? b : b.items;
    return x.length === y.length && x.every((v, i) => $eq(v, y[i]));
  }
  if (a instanceof Dict && b instanceof Dict) {
    if (a.map.size !== b.map.size) return false;
    for (const [h, [, v]] of a.map) { const e = b.map.get(h); if (!e || !$eq(v, e[1])) return false; }
    return true;
  }
  if (a instanceof PySet && b instanceof PySet) return a.map.size === b.map.size && [...a.map.keys()].every(h => b.map.has(h));
  if (a instanceof Range && b instanceof Range) return $eq(rangeList(a), rangeList(b));
  return false;
}
function $key(x) {
  switch (typeof x) {
    case "string": return "s" + x;
    case "bigint": return "n" + x;
    case "boolean": return x ? "n1" : "n0";
    case "number": return Number.isInteger(x) ? "n" + BigInt(x) : "f" + x;
  }
  if (x === null || x === undefined) return "None";
  if (x instanceof Tuple) return "t(" + x.items.map($key).join(",") + ")";
  if (Array.isArray(x) || x instanceof Dict || x instanceof PySet) throw $error("TypeError", `unhashable type: '${$type(x).name}'`);
  if (x instanceof PyObject) {
    const h = lookup(x.$cls, "__hash__");
    if (h && h !== object.dict.get("__hash__")) return "h" + $call(h, [x]);
    if (lookup(x.$cls, "__eq__") !== object.dict.get("__eq__") && !x.$cls.dict.has("__hash__")) throw $error("TypeError", `unhashable type: '${x.$cls.name}'`);
  }
  return "o" + id(x);
}

// ---- operators ---------------------------------------------------------------------------------

function typeError(op, a, b) { return $error("TypeError", `unsupported operand type(s) for ${op}: '${$type(a).name}' and '${$type(b).name}'`); }
function dunder(a, b, name, rname, op) {
  if (a instanceof PyObject) { const m = lookup(a.$cls, name); if (m) { const r = $call(m, [a, b]); if (r !== $NotImplemented) return r; } }
  if (b instanceof PyObject) { const m = lookup(b.$cls, rname); if (m) { const r = $call(m, [b, a]); if (r !== $NotImplemented) return r; } }
  throw typeError(op, a, b);
}
function arithmetic(a, b, ints, floats, name, rname, op) {
  if (isNumber(a) && isNumber(b)) {
    a = toNumeric(a); b = toNumeric(b);
    return typeof a === "bigint" && typeof b === "bigint" ? ints(a, b) : floats(Number(a), Number(b));
  }
  return dunder(a, b, name, rname, op);
}
function $add(a, b) {
  if (typeof a === "string" && typeof b === "string") return a + b;
  if (Array.isArray(a) && Array.isArray(b)) return a.concat(b);
  if (a instanceof Tuple && b instanceof Tuple) return new Tuple(a.items.concat(b.items));
  if (typeof a === "string" && !(b instanceof PyObject)) throw $error("TypeError", `can only concatenate str (not "${$type(b).name}") to str`);
  if (Array.isArray(a) && !(b instanceof PyObject)) throw $error("TypeError", `can only concatenate list (not "${$type(b).name}") to list`);
  return arithmetic(a, b, (x, y) => x + y, (x, y) => x + y, "__add__", "__radd__", "+");
}
function $sub(a, b) {
  if (a instanceof PySet && b instanceof PySet) return new PySet(a.items().filter(x => !b.has(x)));
  return arithmetic(a, b, (x, y) => x - y, (x, y) => x - y, "__sub__", "__rsub__", "-");
}
function repeat(seq, n) {
  const count = Number(toIndex(n));
  if (typeof seq === "string") return count > 0 ? seq.repeat(count) : "";
  const items = Array.isArray(seq) ? seq : seq.items;
  const r = []; for (let i = 0; i < count; i++) r.push(...items);
  return Array.isArray(seq) ? r : new Tuple(r);
}
function $mul(a, b) {
  const seq = (x) => typeof x === "string" || Array.isArray(x) || x instanceof Tuple;
  if (seq(a) && (typeof b === "bigint" || typeof b === "boolean")) return repeat(a, b);
  if (seq(b) && (typeof a === "bigint" || typeof a === "boolean")) return repeat(b, a);
  return arithmetic(a, b, (x, y) => x * y, (x, y) => x * y, "__mul__", "__rmul__", "*");
}
function zero(division) { return $error("ZeroDivisionError", division); }
function $truediv(a, b) {
  return arithmetic(a, b, (x, y) => { if (y === 0n) throw zero("division by zero"); return Number(x) / Number(y); },
    (x, y) => { if (y === 0) throw zero("float division by zero"); return x / y; }, "__truediv__", "__rtruediv__", "/");
}
function floorInt(x, y) { if (y === 0n) throw zero("integer division or modulo by zero"); const q = x / y; return (x % y !== 0n && (x < 0n) !== (y < 0n)) ? q - 1n : q; }
function $floordiv(a, b) {
  return arithmetic(a, b, floorInt, (x, y) => { if (y === 0) throw zero("float floor division by zero"); return Math.floor(x / y); }, "__floordiv__", "__rfloordiv__", "//");
}
function modInt(x, y) { if (y === 0n) throw zero("integer division or modulo by zero"); const r = x % y; return (r !== 0n && (r < 0n) !== (y < 0n)) ? r + y : r; }
function $mod(a, b) {
  if (typeof a === "string") return percentFormat(a, b);
  return arithmetic(a, b, modInt, (x, y) => { if (y === 0) throw zero("float modulo"); const r = x - y * Math.floor(x / y); return r; }, "__mod__", "__rmod__", "%");
}
function $pow(a, b) {
  return arithmetic(a, b, (x, y) => y >= 0n ? x ** y : (x === 0n ? (() => { throw zero("0.0 cannot be raised to a negative power"); })() : Number(x) ** Number(y)),
    (x, y) => { if (x === 0 && y < 0) throw zero("0.0 cannot be raised to a negative power"); return x ** y; }, "__pow__", "__rpow__", "** or pow()");
}
function intOnly(a, b, f, name, rname, op) {
  if ((typeof a === "bigint" || typeof a === "boolean") && (typeof b === "bigint" || typeof b === "boolean")) {
    const r = f(toNumeric(a), toNumeric(b));
    return typeof a === "boolean" && typeof b === "boolean" && (op === "&" || op === "|" || op === "^") ? r !== 0n : r;
  }
  return dunder(a, b, name, rname, op);
}
function $lshift(a, b) { return intOnly(a, b, (x, y) => { if (y < 0n) throw $error("ValueError", "negative shift count"); return x << y; }, "__lshift__", "__rlshift__", "<<"); }
function $rshift(a, b) { return intOnly(a, b, (x, y) => { if (y < 0n) throw $error("ValueError", "negative shift count"); return x >> y; }, "__rshift__", "__rrshift__", ">>"); }
function $bitand(a, b) { if (a instanceof PySet && b instanceof PySet) return new PySet(a.items().filter(x => b.has(x))); return intOnly(a, b, (x, y) => x & y, "__and__", "__rand__", "&"); }
function $bitor(a, b) {
  if (a instanceof PySet && b instanceof PySet) return new PySet([...a.items(), ...b.items()]);
  if (a instanceof Dict && b instanceof Dict) { const d = new Dict(); for (const [, [k, v]] of a.map) d.set(k, v); for (const [, [k, v]] of b.map) d.set(k, v); return d; }
  return intOnly(a, b, (x, y) => x | y, "__or__", "__ror__", "|");
}
function $bitxor(a, b) {
  if (a instanceof PySet && b instanceof PySet) return new PySet([...a.items().filter(x => !b.has(x)), ...b.items().filter(x => !a.has(x))]);
  return intOnly(a, b, (x, y) => x ^ y, "__xor__", "__rxor__", "^");
}
function unary(x, name, op, f) {
  if (isNumber(x)) return f(toNumeric(x));
  if (x instanceof PyObject) { const m = lookup(x.$cls, name); if (m) return $call(m, [x]); }
  throw $error("TypeError", `bad operand type for unary ${op}: '${$type(x).name}'`);
}
function $neg(x) { return unary(x, "__neg__", "-", (v) => -v); }
function $pos(x) { return unary(x, "__pos__", "+", (v) => v); }
function $invert(x) { if (typeof x === "number") throw $error("TypeError", "bad operand type for unary ~: 'float'"); return unary(x, "__invert__", "~", (v) => ~v); }
function $not(x) { return !$bool(x); }

/** In-place operators: lists are extended in place, everything else as the plain operator. */
function $iadd(a, b) {
  if (Array.isArray(a)) { for (const x of $iter(b)) a.push(x); return a; }
  if (a instanceof PyObject) { const m = lookup(a.$cls, "__iadd__"); if (m) return $call(m, [a, b]); }
  return $add(a, b);
}

function order(a, b, op, name, rname) {
  if (isNumber(a) && isNumber(b)) { a = toNumeric(a); b = toNumeric(b); return typeof a === typeof b ? cmp(a, b, op) : cmp(Number(a), Number(b), op); }
  if (typeof a === "string" && typeof b === "string") return cmp(a, b, op);
  if ((Array.isArray(a) && Array.isArray(b)) || (a instanceof Tuple && b instanceof Tuple)) {
    const x = Array.isArray(a) ? a : a.items, y = Array.isArray(b) ? b : b.items;
    for (let i = 0; i < Math.min(x.length, y.length); i++) if (!$eq(x[i], y[i])) return order(x[i], y[i], op, name, rname);
    return cmp(x.length, y.length, op);
  }
  if (a instanceof PySet && b instanceof PySet) {
    const sub = (p, q) => p.items().every(v => q.has(v));
    return op === "<=" ? sub(a, b) : op === ">=" ? sub(b, a) : op === "<" ? sub(a, b) && a.map.size < b.map.size : sub(b, a) && b.map.size < a.map.size;
  }
  if (a instanceof PyObject) { const m = lookup(a.$cls, name); if (m) { const r = $call(m, [a, b]); if (r !== $NotImplemented) return $bool(r); } }
  if (b instanceof PyObject) { const m = lookup(b.$cls, rname); if (m) { const r = $call(m, [b, a]); if (r !== $NotImplemented) return $bool(r); } }
  throw $error("TypeError", `'${op}' not supported between instances of '${$type(a).name}' and '${$type(b).name}'`);
}
function cmp(a, b, op) { switch (op) { case "<": return a < b; case "<=": return a <= b; case ">": return a > b; default: return a >= b; } }
function $lt(a, b) { return order(a, b, "<", "__lt__", "__gt__"); }
function $le(a, b) { return order(a, b, "<=", "__le__", "__ge__"); }
function $gt(a, b) { return order(a, b, ">", "__gt__", "__lt__"); }
function $ge(a, b) { return order(a, b, ">=", "__ge__", "__le__"); }
function $ne(a, b) {
  if (a instanceof PyObject) { const m = lookup(a.$cls, "__ne__"); if (m && m !== object.dict.get("__ne__")) return $bool($call(m, [a, b])); }
  return !$eq(a, b);
}
function $in(x, container) {
  if (typeof container === "string") { if (typeof x !== "string") throw $error("TypeError", `'in <string>' requires string as left operand, not ${$type(x).name}`); return container.includes(x); }
  if (Array.isArray(container)) return container.some(v => $eq(v, x));
  if (container instanceof Tuple) return container.items.some(v => $eq(v, x));
  if (container instanceof Dict || container instanceof PySet) return container.has(x);
  if (container instanceof Range) { if (!isNumber(x)) return false; const v = toNumeric(x); return typeof v === "bigint" && rangeIndexOf(container, v) >= 0n; }
  if (container instanceof PyObject) { const m = lookup(container.$cls, "__contains__"); if (m) return $bool($call(m, [container, x])); }
  for (const v of $iter(container)) if ($eq(v, x)) return true;
  return false;
}
/** `a and b`, `a or b`: the result is one of the operands. */
function $and(a, b) { return $bool(a) ? b() : a; }
function $or(a, b) { return $bool(a) ? a : b(); }

// ---- iteration ---------------------------------------------------------------------------------

function rangeLength(r) {
  if (r.step > 0n) return r.stop > r.start ? (r.stop - r.start + r.step - 1n) / r.step : 0n;
  return r.start > r.stop ? (r.start - r.stop - r.step - 1n) / -r.step : 0n;
}
function rangeIndexOf(r, v) {
  if (r.step > 0n ? v < r.start || v >= r.stop : v > r.start || v <= r.stop) return -1n;
  return (v - r.start) % r.step === 0n ? (v - r.start) / r.step : -1n;
}
function* rangeIter(r) { for (let v = r.start; r.step > 0n ? v < r.stop : v > r.stop; v += r.step) yield v; }
function rangeList(r) { return [...rangeIter(r)]; }

function* objectIter(o) {
  const it = $callMethod(o, "__iter__", []);
  if (!(it instanceof PyObject) && it && typeof it.next === "function") { yield* it; return; } // e.g. a generator
  for (;;) {
    let v;
    try { v = $callMethod(it, "__next__", []); } catch (e) { if (e instanceof PyObject && $isinstance(e, $b.StopIteration)) return; throw e; }
    yield v;
  }
}
/** A JavaScript iterator over a Python iterable. */
function $iter(x) {
  if (Array.isArray(x)) return x[Symbol.iterator]();
  if (typeof x === "string") return x[Symbol.iterator]();
  if (x instanceof Tuple) return x.items[Symbol.iterator]();
  if (x instanceof Dict) return x.keys()[Symbol.iterator]();
  if (x instanceof PySet) return x.items()[Symbol.iterator]();
  if (x instanceof Range) return rangeIter(x);
  if (x instanceof PyObject && lookup(x.$cls, "__iter__")) return objectIter(x);
  if (x && typeof x.next === "function" && typeof x[Symbol.iterator] === "function") return x;
  throw $error("TypeError", `'${$type(x).name}' object is not iterable`);
}
function $list(x) { return [...$iter(x)]; }
function $unpack(value, count, star) {
  const items = $list(value);
  if (star < 0) {
    if (items.length !== count) throw $error("ValueError", items.length > count ? `too many values to unpack (expected ${count})` : `not enough values to unpack (expected ${count}, got ${items.length})`);
    return items;
  }
  if (items.length < count - 1) throw $error("ValueError", `not enough values to unpack (expected at least ${count - 1}, got ${items.length})`);
  const after = count - star - 1;
  return [...items.slice(0, star), items.slice(star, items.length - after), ...items.slice(items.length - after)];
}

// ---- attributes, items, calls -------------------------------------------------------------------

function bind(v, self, cls) {
  if (v instanceof Property) return $call(v.fget, [self]);
  if (v instanceof StaticMethod) return v.f;
  if (v instanceof ClassMethod) return new BoundMethod(cls, v.f);
  if (typeof v === "function" && !(v instanceof PyClass)) return new BoundMethod(self, v);
  return v;
}
function $getattr(o, name) {
  if (o instanceof PyObject) {
    const own = o.$dict.get(name);
    if (own !== undefined) return own;
    const v = lookup(o.$cls, name);
    if (v !== undefined) return bind(v, o, o.$cls);
    const ga = lookup(o.$cls, "__getattr__");
    if (ga) return $call(ga, [o, name]);
    throw $error("AttributeError", `'${o.$cls.name}' object has no attribute '${name}'`);
  }
  if (o instanceof PyClass) {
    if (name === "__name__") return o.name;
    if (name === "__mro__") return new Tuple(o.mro);
    const v = lookup(o, name);
    if (v === undefined) throw $error("AttributeError", `type object '${o.name}' has no attribute '${name}'`);
    if (v instanceof ClassMethod) return new BoundMethod(o, v.f);
    if (v instanceof StaticMethod) return v.f;
    return v;
  }
  if (o instanceof Super) {
    const mro = $type(o.self) === type ? o.self.mro : $type(o.self).mro;
    for (const c of mro.slice(mro.indexOf(o.cls) + 1)) {
      const v = c.dict.get(name);
      if (v !== undefined) return bind(v, o.self, $type(o.self));
    }
    throw $error("AttributeError", `'super' object has no attribute '${name}'`);
  }
  if (o instanceof BoundMethod && name === "__name__") return o.func.$name;
  if (typeof o === "function" && name === "__name__") return o.$name || "<lambda>";
  const v = lookup($type(o), name);
  if (v !== undefined) return bind(v, o, $type(o));
  throw $error("AttributeError", `'${$type(o).name}' object has no attribute '${name}'`);
}
function $setattr(o, name, value) {
  if (o instanceof PyObject) {
    const p = lookup(o.$cls, name);
    if (p instanceof Property) { if (!p.fset) throw $error("AttributeError", `property '${name}' of '${o.$cls.name}' object has no setter`); $call(p.fset, [o, value]); return; }
    o.$dict.set(name, value); return;
  }
  if (o instanceof PyClass) { o.dict.set(name, value); return; }
  throw $error("AttributeError", `'${$type(o).name}' object attribute '${name}' is read-only`);
}
function $delattr(o, name) {
  if (o instanceof PyObject && o.$dict.delete(name)) return;
  throw $error("AttributeError", name);
}
function $hasattr(o, name) { try { $getattr(o, name); return true; } catch (e) { if (e instanceof PyObject && $isinstance(e, $b.AttributeError)) return false; throw e; } }

function normalizeIndex(i, length, what) {
  let n = toIndex(i);
  if (n < 0n) n += BigInt(length);
  if (n < 0n || n >= BigInt(length)) throw $error("IndexError", `${what} index out of range`);
  return Number(n);
}
function sliceIndices(s, length) {
  const step = s.step === null ? 1n : toIndex(s.step);
  if (step === 0n) throw $error("ValueError", "slice step cannot be zero");
  const len = BigInt(length);
  const clamp = (v, lo, hi) => v < lo ? lo : v > hi ? hi : v;
  let start, stop;
  if (step > 0n) {
    start = s.lower === null ? 0n : (() => { let v = toIndex(s.lower); if (v < 0n) v += len; return clamp(v, 0n, len); })();
    stop = s.upper === null ? len : (() => { let v = toIndex(s.upper); if (v < 0n) v += len; return clamp(v, 0n, len); })();
  } else {
    start = s.lower === null ? len - 1n : (() => { let v = toIndex(s.lower); if (v < 0n) v += len; return clamp(v, -1n, len - 1n); })();
    stop = s.upper === null ? -1n : (() => { let v = toIndex(s.upper); if (v < 0n) v += len; return clamp(v, -1n, len - 1n); })();
  }
  const result = [];
  for (let i = start; step > 0n ? i < stop : i > stop; i += step) result.push(Number(i));
  return result;
}
class Slice { constructor(lower, upper, step) { this.lower = lower; this.upper = upper; this.step = step; } }
function $slice(lower, upper, step) { return new Slice(lower, upper, step); }
function $getitem(o, i) {
  if (i instanceof Slice) {
    if (typeof o === "string") return sliceIndices(i, o.length).map(k => o[k]).join("");
    if (Array.isArray(o)) return sliceIndices(i, o.length).map(k => o[k]);
    if (o instanceof Tuple) return new Tuple(sliceIndices(i, o.items.length).map(k => o.items[k]));
    if (o instanceof Range) { const l = rangeList(o); return sliceIndices(i, l.length).map(k => l[k]); }
  }
  if (Array.isArray(o)) return o[normalizeIndex(i, o.length, "list")];
  if (typeof o === "string") return o[normalizeIndex(i, o.length, "string")];
  if (o instanceof Tuple) return o.items[normalizeIndex(i, o.items.length, "tuple")];
  if (o instanceof Dict) { const v = o.get(i); if (v === undefined) throw $call($b.KeyError, [i]); return v; }
  if (o instanceof Range) { const n = normalizeIndex(i, Number(rangeLength(o)), "range object"); return o.start + BigInt(n) * o.step; }
  if (o instanceof PyObject) { const m = lookup(o.$cls, "__getitem__"); if (m) return $call(m, [o, i instanceof Slice ? new Tuple([i.lower, i.upper, i.step]) : i]); }
  throw $error("TypeError", `'${$type(o).name}' object is not subscriptable`);
}
function $setitem(o, i, v) {
  if (Array.isArray(o)) {
    if (i instanceof Slice) { const items = $list(v); const idx = sliceIndices(i, o.length); if (i.step === null || i.step === 1n) { const start = idx.length ? idx[0] : Math.min(Number(i.lower === null ? 0n : toIndex(i.lower)), o.length); o.splice(start, idx.length, ...items); } else idx.forEach((k, j) => o[k] = items[j]); return; }
    o[normalizeIndex(i, o.length, "list assignment")] = v; return;
  }
  if (o instanceof Dict) { o.set(i, v); return; }
  if (o instanceof PyObject) { const m = lookup(o.$cls, "__setitem__"); if (m) { $call(m, [o, i, v]); return; } }
  throw $error("TypeError", `'${$type(o).name}' object does not support item assignment`);
}
function $delitem(o, i) {
  if (Array.isArray(o)) { if (i instanceof Slice) { const idx = sliceIndices(i, o.length).sort((a, b) => b - a); for (const k of idx) o.splice(k, 1); } else o.splice(normalizeIndex(i, o.length, "list assignment"), 1); return; }
  if (o instanceof Dict) { if (!o.delete(i)) throw $call($b.KeyError, [i]); return; }
  if (o instanceof PyObject) { const m = lookup(o.$cls, "__delitem__"); if (m) { $call(m, [o, i]); return; } }
  throw $error("TypeError", `'${$type(o).name}' object doesn't support item deletion`);
}

/** Defines a Python function: [sig] lists the parameters, defaults (evaluated now), *args and **kwargs. */
function $def(name, f, sig) { f.$name = name; f.$sig = sig; return f; }

function bindArgs(f, args, kwargs) {
  const sig = f.$sig;
  const params = sig.params;
  const values = new Array(params.length).fill(undefined);
  const n = Math.min(args.length, params.length);
  for (let i = 0; i < n; i++) values[i] = args[i];
  let rest = null;
  if (args.length > params.length) {
    if (sig.varargs === null) throw $error("TypeError", `${f.$name}() takes ${params.length} positional argument${params.length === 1 ? "" : "s"} but ${args.length} ${args.length === 1 ? "was" : "were"} given`);
    rest = new Tuple(args.slice(params.length));
  } else if (sig.varargs !== null) rest = new Tuple([]);
  let extra = sig.kwargs !== null ? new Dict() : null;
  const kwonly = sig.kwonly || [];
  const kwvalues = new Array(kwonly.length).fill(undefined);
  const posonly = sig.posonly || 0;
  if (kwargs) for (const [k, v] of Object.entries(kwargs)) {
    const i = params.indexOf(k);
    const j = kwonly.indexOf(k);
    if (j >= 0) { kwvalues[j] = v; continue; }
    if (i < posonly && i >= 0 && !extra) throw $error("TypeError", `${f.$name}() got some positional-only arguments passed as keyword arguments: '${k}'`);
    if (i < posonly) { if (extra) { extra.set(k, v); continue; } throw $error("TypeError", `${f.$name}() got an unexpected keyword argument '${k}'`); }
    if (values[i] !== undefined) throw $error("TypeError", `${f.$name}() got multiple values for argument '${k}'`);
    values[i] = v;
  }
  const firstDefault = params.length - sig.defaults.length;
  for (let i = 0; i < params.length; i++) if (values[i] === undefined) {
    if (i >= firstDefault) values[i] = sig.defaults[i - firstDefault];
    else { const missing = params.slice(i).filter((p, j) => values[i + j] === undefined && i + j < firstDefault); throw $error("TypeError", `${f.$name}() missing ${missing.length} required positional argument${missing.length === 1 ? "" : "s"}: ${missing.map(p => `'${p}'`).join(" and ")}`); }
  }
  if (sig.varargs !== null) values.push(rest);
  for (let j = 0; j < kwonly.length; j++) {
    if (kwvalues[j] === undefined) {
      if (sig.kwdefaults && kwonly[j] in sig.kwdefaults) kwvalues[j] = sig.kwdefaults[kwonly[j]];
      else throw $error("TypeError", `${f.$name}() missing 1 required keyword-only argument: '${kwonly[j]}'`);
    }
    values.push(kwvalues[j]);
  }
  if (sig.kwargs !== null) values.push(extra);
  return values;
}
/** Calls any Python callable with positional arguments and keyword arguments (an object or null). */
function $call(f, args, kwargs) {
  if (typeof f === "function" && !(f instanceof PyClass)) {
    if (f.$sig) return f(...bindArgs(f, args, kwargs));
    if (f.$native) return f(args, kwargs || null);
    return f(...args);
  }
  if (f instanceof BoundMethod) return $call(f.func, [f.self, ...args], kwargs);
  if (f instanceof PyClass) {
    if (f.native) return f.native(args, kwargs || null);
    for (const c of f.mro) if (c.native && c !== object && !isSubclass(c, BaseException)) {
      const v = c.native(args, kwargs || null); return v; // subclasses of int, str, ... are not supported: their values are created
    }
    const o = new PyObject(f);
    const init = lookup(f, "__init__");
    const r = $call(init, [o, ...args], kwargs);
    if (r !== null && r !== undefined) throw $error("TypeError", "__init__() should return None");
    return o;
  }
  if (f instanceof PyObject) { const m = lookup(f.$cls, "__call__"); if (m) return $call(m, [f, ...args], kwargs); }
  throw $error("TypeError", `'${$type(f).name}' object is not callable`);
}
/** `o.name(args)` without creating a bound method. */
function $callMethod(o, name, args, kwargs) {
  if (!(o instanceof PyObject) || o.$dict.has(name)) return $call($getattr(o, name), args, kwargs);
  const v = lookup(o.$cls, name);
  if (typeof v === "function" && !(v instanceof PyClass)) return $call(v, [o, ...args], kwargs);
  return $call($getattr(o, name), args, kwargs);
}
/** A class: [body] returns the namespace (name -> value). */
function $class(name, bases, body) {
  const ns = body();
  const dict = new Map();
  for (const [k, v] of Object.entries(ns)) if (v !== undefined) dict.set(k, v);
  const cls = new PyClass(name, bases.length ? bases : [object], dict);
  for (const b of cls.bases) if (!(b instanceof PyClass)) throw $error("TypeError", "bases must be types");
  return cls;
}
function $super(cls, self) { return new Super(cls, self); }

// ---- formatting --------------------------------------------------------------------------------

/** format(value, spec): [[fill]align][sign][#][0][width][,][.precision][type] */
function $format(value, spec) {
  if (value instanceof PyObject) { const m = lookup(value.$cls, "__format__"); if (m) return $call(m, [value, spec]); if (spec === "") return $str(value); }
  if (spec === "") return $str(value);
  const m = /^(?:(.)?([<>^=]))?([+\- ])?(#)?(0)?(\d+)?([,_])?(?:\.(\d+))?([bcdeEfFgGnosxX%])?$/.exec(spec);
  if (!m) throw $error("ValueError", `Invalid format specifier '${spec}'`);
  let [, fill, align, sign, alt, zeroPad, width, grouping, precision, kind] = m;
  let s;
  const num = isNumber(value) ? toNumeric(value) : null;
  if (kind === undefined && num !== null) kind = typeof num === "bigint" ? "d" : (precision !== undefined ? "g" : null);
  const group = (digits) => grouping ? digits.replace(/\B(?=(\d{3})+(?!\d))/g, grouping) : digits;
  const signOf = (negative) => negative ? "-" : sign === "+" ? "+" : sign === " " ? " " : "";
  switch (kind) {
    case "d": case "n": { const v = toInt(num); s = signOf(v < 0n) + group((v < 0n ? -v : v).toString()); break; }
    case "b": case "o": case "x": case "X": {
      const v = toInt(num); const base = { b: 2, o: 8, x: 16, X: 16 }[kind];
      let digits = (v < 0n ? -v : v).toString(base); if (kind === "X") digits = digits.toUpperCase();
      s = signOf(v < 0n) + (alt ? "0" + kind : "") + digits; break;
    }
    case "f": case "F": case "%": {
      let v = Number(num); if (kind === "%") v *= 100;
      const p = precision === undefined ? 6 : Number(precision);
      const fixed = Math.abs(v).toFixed(p);
      const [whole, frac] = fixed.split(".");
      s = signOf(v < 0 || Object.is(v, -0)) + group(whole) + (frac !== undefined ? "." + frac : "") + (kind === "%" ? "%" : ""); break;
    }
    case "e": case "E": {
      const v = Number(num); const p = precision === undefined ? 6 : Number(precision);
      let [mant, exp] = Math.abs(v).toExponential(p).split("e");
      const e = Number(exp); s = signOf(v < 0) + mant + (kind === "E" ? "E" : "e") + (e < 0 ? "-" : "+") + String(Math.abs(e)).padStart(2, "0"); break;
    }
    case "g": case "G": {
      const v = Number(num); const p = precision === undefined ? 6 : Math.max(1, Number(precision));
      const e = v === 0 ? 0 : Math.floor(Math.log10(Math.abs(v)));
      if (e < -4 || e >= p) { let [mant, exp] = Math.abs(v).toExponential(p - 1).split("e"); if (!alt) mant = mant.replace(/\.?0+$/, ""); const x = Number(exp); s = signOf(v < 0) + mant + "e" + (x < 0 ? "-" : "+") + String(Math.abs(x)).padStart(2, "0"); }
      else { let f = Math.abs(v).toFixed(Math.max(0, p - 1 - e)); if (!alt && f.includes(".")) f = f.replace(/\.?0+$/, ""); s = signOf(v < 0) + f; }
      break;
    }
    case "c": s = String.fromCodePoint(Number(toInt(num))); break;
    case "s": case undefined: case null: s = num !== null && kind === null ? floatRepr(Number(num)) : $str(value); if (precision !== undefined && kind === "s") s = s.slice(0, Number(precision)); break;
    default: s = $str(value);
  }
  if (width !== undefined) {
    const w = Number(width);
    if (zeroPad && !align) { fill = "0"; align = "="; }
    fill = fill || " ";
    if (!align) align = num !== null ? ">" : "<";
    if (s.length < w) {
      const pad = w - s.length;
      if (align === "<") s = s + fill.repeat(pad);
      else if (align === ">") s = fill.repeat(pad) + s;
      else if (align === "^") s = fill.repeat(Math.floor(pad / 2)) + s + fill.repeat(pad - Math.floor(pad / 2));
      else { const signChar = /^[+\- ]/.test(s) ? s[0] : ""; const prefix = /^[+\- ]?0[bBoOxX]/.test(s) ? s.slice(0, signChar.length + 2) : signChar; s = prefix + fill.repeat(pad) + s.slice(prefix.length); }
    }
  }
  return s;
}
function percentFormat(format, values) {
  const items = values instanceof Tuple ? values.items : [values];
  let i = 0;
  const r = format.replace(/%([-+ 0#]*)(\d+)?(?:\.(\d+))?([sdrifeExXgG%])/g, (all, flags, width, precision, kind) => {
    if (kind === "%") return "%";
    const v = items[i++];
    let spec = (flags.includes("-") ? "<" : "") + (flags.includes("+") ? "+" : "") + (flags.includes("0") ? "0" : "") + (width || "") + (precision !== undefined ? "." + precision : "");
    if (kind === "s") return $format($str(v), spec);
    if (kind === "r") return $format($repr(v), spec);
    if (kind === "i") kind = "d";
    return $format(kind === "d" ? toInt(v) : v, spec + kind);
  });
  if (i < items.length) throw $error("TypeError", "not all arguments converted during string formatting");
  return r;
}

// ---- builtin functions -------------------------------------------------------------------------

const nb = (name, f) => builtin(name, native(name, f));
nb("print", (args, kw) => {
  const sep = kw && kw.sep !== undefined && kw.sep !== null ? kw.sep : " ";
  const end = kw && kw.end !== undefined && kw.end !== null ? kw.end : "\n";
  $out(args.map($str).join(sep) + end);
  return null;
});
nb("len", ([x]) => {
  if (typeof x === "string" || Array.isArray(x)) return BigInt(x.length);
  if (x instanceof Tuple) return BigInt(x.items.length);
  if (x instanceof Dict || x instanceof PySet) return BigInt(x.map.size);
  if (x instanceof Range) return rangeLength(x);
  if (x instanceof PyObject) { const m = lookup(x.$cls, "__len__"); if (m) return $call(m, [x]); }
  throw $error("TypeError", `object of type '${$type(x).name}' has no len()`);
});
nb("repr", ([x]) => $repr(x));
nb("abs", ([x]) => { if (isNumber(x)) { const v = toNumeric(x); return v < 0 ? -v : v; } return $callMethod(x, "__abs__", []); });
nb("isinstance", ([x, c]) => $isinstance(x, c));
nb("issubclass", ([a, c]) => c instanceof Tuple ? c.items.some(k => isSubclass(a, k)) : isSubclass(a, c));
nb("iter", ([x]) => $iter(x));
nb("next", ([it, ...d]) => { const r = it.next(); if (r.done) { if (d.length) return d[0]; throw $error("StopIteration"); } return r.value; });
nb("hasattr", ([o, n]) => $hasattr(o, n));
nb("getattr", ([o, n, ...d]) => { if (d.length) { try { return $getattr(o, n); } catch (e) { if (e instanceof PyObject && $isinstance(e, $b.AttributeError)) return d[0]; throw e; } } return $getattr(o, n); });
nb("setattr", ([o, n, v]) => { $setattr(o, n, v); return null; });
nb("callable", ([x]) => typeof x === "function" || x instanceof BoundMethod || x instanceof PyClass || (x instanceof PyObject && !!lookup(x.$cls, "__call__")));
nb("id", ([x]) => BigInt(id(x)));
nb("hash", ([x]) => { const k = $key(x); return /^n-?\d+$/.test(k) ? BigInt(k.slice(1)) : BigInt([...k].reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)); });
nb("chr", ([x]) => String.fromCodePoint(Number(toIndex(x))));
nb("ord", ([x]) => { if (typeof x !== "string" || [...x].length !== 1) throw $error("TypeError", "ord() expected a character"); return BigInt(x.codePointAt(0)); });
nb("divmod", ([a, b]) => new Tuple([$floordiv(a, b), $mod(a, b)]));
nb("pow", ([a, b, m]) => { if (m !== undefined) { let r = 1n, base = modInt(toIndex(a), toIndex(m)), e = toIndex(b); while (e > 0n) { if (e & 1n) r = r * base % toIndex(m); base = base * base % toIndex(m); e >>= 1n; } return r; } return $pow(a, b); });
nb("round", ([x, n]) => {
  if (typeof x === "bigint") return x;
  const v = Number(toNumeric(x));
  if (n === undefined || n === null) {
    const f = Math.floor(v), d = v - f;
    const r = d > 0.5 ? f + 1 : d < 0.5 ? f : (f % 2 === 0 ? f : f + 1);
    return BigInt(r);
  }
  const p = 10 ** Number(n);
  const scaled = v * p, f = Math.floor(scaled), d = scaled - f;
  const r = Math.abs(d - 0.5) < 1e-9 ? (f % 2 === 0 ? f : f + 1) : Math.round(scaled);
  return Number((r / p).toFixed(Math.max(0, Number(n))));
});
function keyOf(kw) { return kw && kw.key !== undefined && kw.key !== null ? (x) => $call(kw.key, [x]) : (x) => x; }
function extreme(name, args, kw, better) {
  const items = args.length === 1 ? $list(args[0]) : args;
  if (items.length === 0) { if (kw && kw.default !== undefined) return kw.default; throw $error("ValueError", `${name}() arg is an empty sequence`); }
  const key = keyOf(kw);
  let best = items[0], bestKey = key(best);
  for (const x of items.slice(1)) { const k = key(x); if (better(k, bestKey)) { best = x; bestKey = k; } }
  return best;
}
nb("min", (args, kw) => extreme("min", args, kw, (a, b) => $lt(a, b)));
nb("max", (args, kw) => extreme("max", args, kw, (a, b) => $gt(a, b)));
nb("sum", ([it, start]) => { let s = start === undefined ? 0n : start; for (const x of $iter(it)) s = $add(s, x); return s; });
function sortList(items, kw) {
  const key = keyOf(kw);
  const keyed = items.map(x => [key(x), x]);
  const reverse = kw && $bool(kw.reverse);
  keyed.sort((a, b) => reverse ? ($lt(b[0], a[0]) ? -1 : $lt(a[0], b[0]) ? 1 : 0) : ($lt(a[0], b[0]) ? -1 : $lt(b[0], a[0]) ? 1 : 0));
  return keyed.map(e => e[1]);
}
nb("sorted", ([it], kw) => sortList($list(it), kw));
nb("reversed", ([x]) => { const items = x instanceof Range ? rangeList(x) : $list(x); return items.reverse()[Symbol.iterator](); });
nb("enumerate", function* ([it, start]) { let i = start === undefined ? 0n : start; for (const x of $iter(it)) { yield new Tuple([i, x]); i += 1n; } });
nb("zip", function* (args) { const its = args.map($iter); for (;;) { const r = its.map(i => i.next()); if (r.some(x => x.done)) return; yield new Tuple(r.map(x => x.value)); } });
nb("map", function* ([f, ...its]) { const iters = its.map($iter); for (;;) { const r = iters.map(i => i.next()); if (r.some(x => x.done)) return; yield $call(f, r.map(x => x.value)); } });
nb("filter", function* ([f, it]) { for (const x of $iter(it)) if (f === null ? $bool(x) : $bool($call(f, [x]))) yield x; });
nb("any", ([it]) => { for (const x of $iter(it)) if ($bool(x)) return true; return false; });
nb("all", ([it]) => { for (const x of $iter(it)) if (!$bool(x)) return false; return true; });
nb("format", ([v, spec]) => $format(v, spec === undefined ? "" : spec));
nb("input", () => { throw $error("RuntimeError", "input() is not supported"); });
builtin("property", nativeClass("property", [object], {
  setter: ([p, f]) => new Property(p.fget, f),
  getter: ([p, f]) => new Property(f, p.fset),
}, ([fget, fset]) => new Property(fget, fset || null)));
builtin("staticmethod", nativeClass("staticmethod", [object], {}, ([f]) => new StaticMethod(f)));
builtin("classmethod", nativeClass("classmethod", [object], {}, ([f]) => new ClassMethod(f)));
builtin("super", nativeClass("super", [object], {}, ([cls, self]) => new Super(cls, self)));
builtin("NotImplemented", $NotImplemented);

// ---- str, list, tuple, dict, set ---------------------------------------------------------------

function checkStr(x, what) { if (typeof x !== "string") throw $error("TypeError", `${what} must be str, not ${$type(x).name}`); return x; }
const whitespace = /[ \t\n\r\f\v]/;
function strip(s, chars, left, right) {
  const set = chars === undefined || chars === null ? null : chars;
  const is = (c) => set === null ? whitespace.test(c) : set.includes(c);
  let a = 0, b = s.length;
  if (left) while (a < b && is(s[a])) a++;
  if (right) while (b > a && is(s[b - 1])) b--;
  return s.slice(a, b);
}
const str = nativeClass("str", [object], {
  upper: ([s]) => s.toUpperCase(), lower: ([s]) => s.toLowerCase(),
  capitalize: ([s]) => s ? s[0].toUpperCase() + s.slice(1).toLowerCase() : s,
  title: ([s]) => s.replace(/[A-Za-z]+/g, w => w[0].toUpperCase() + w.slice(1).toLowerCase()),
  swapcase: ([s]) => [...s].map(c => c === c.toUpperCase() ? c.toLowerCase() : c.toUpperCase()).join(""),
  strip: ([s, c]) => strip(s, c, true, true), lstrip: ([s, c]) => strip(s, c, true, false), rstrip: ([s, c]) => strip(s, c, false, true),
  split: ([s, sep, max]) => {
    const limit = max === undefined || max === null ? -1 : Number(max);
    if (sep === undefined || sep === null) {
      const parts = []; let rest = s.replace(/^[ \t\n\r\f\v]+/, "");
      while (rest.length) { if (limit >= 0 && parts.length === limit) { parts.push(rest.replace(/[ \t\n\r\f\v]+$/, "")); break; } const m = /[ \t\n\r\f\v]+/.exec(rest); if (!m) { parts.push(rest); break; } parts.push(rest.slice(0, m.index)); rest = rest.slice(m.index + m[0].length); }
      return parts;
    }
    if (sep === "") throw $error("ValueError", "empty separator");
    const parts = s.split(sep);
    return limit >= 0 && parts.length > limit + 1 ? [...parts.slice(0, limit), parts.slice(limit).join(sep)] : parts;
  },
  splitlines: ([s]) => s.split(/\r?\n/).filter((l, i, a) => i < a.length - 1 || l !== ""),
  join: ([s, it]) => $list(it).map(x => checkStr(x, "sequence item")).join(s),
  replace: ([s, a, b, n]) => { if (n === undefined) return s.split(a).join(b); let r = s, count = Number(n), out = ""; while (count-- > 0) { const i = r.indexOf(a); if (i < 0) break; out += r.slice(0, i) + b; r = r.slice(i + a.length); } return out + r; },
  startswith: ([s, p]) => p instanceof Tuple ? p.items.some(x => s.startsWith(x)) : s.startsWith(p),
  endswith: ([s, p]) => p instanceof Tuple ? p.items.some(x => s.endsWith(x)) : s.endsWith(p),
  find: ([s, x, start]) => BigInt(s.indexOf(x, start === undefined ? 0 : Number(start))),
  rfind: ([s, x]) => BigInt(s.lastIndexOf(x)),
  index: ([s, x]) => { const i = s.indexOf(x); if (i < 0) throw $error("ValueError", "substring not found"); return BigInt(i); },
  count: ([s, x]) => BigInt(x === "" ? s.length + 1 : s.split(x).length - 1),
  isdigit: ([s]) => /^\d+$/.test(s), isalpha: ([s]) => /^[A-Za-z]+$/.test(s), isalnum: ([s]) => /^[A-Za-z0-9]+$/.test(s),
  isspace: ([s]) => /^\s+$/.test(s), isupper: ([s]) => /[A-Z]/.test(s) && s === s.toUpperCase(), islower: ([s]) => /[a-z]/.test(s) && s === s.toLowerCase(),
  zfill: ([s, w]) => { const n = Number(w); const sign = /^[+-]/.test(s) ? s[0] : ""; const body = s.slice(sign.length); return sign + body.padStart(n - sign.length, "0"); },
  center: ([s, w, f]) => $format(s, `${f || " "}^${w}`), ljust: ([s, w, f]) => $format(s, `${f || " "}<${w}`), rjust: ([s, w, f]) => $format(s, `${f || " "}>${w}`),
  format: (args, kw) => {
    const [s, ...pos] = args; let auto = 0;
    return s.replace(/\{\{|\}\}|\{([^{}:!]*)(?:!([rs]))?(?::([^{}]*))?\}/g, (all, field, conv, spec) => {
      if (all === "{{") return "{"; if (all === "}}") return "}";
      let v = field === "" ? pos[auto++] : /^\d+$/.test(field) ? pos[Number(field)] : kw[field];
      if (conv === "r") v = $repr(v); else if (conv === "s") v = $str(v);
      return $format(v, spec || "");
    });
  },
  encode: ([s]) => s,
}, ([x]) => x === undefined ? "" : $str(x));
const list = nativeClass("list", [object], {
  append: ([l, x]) => { l.push(x); return null; },
  extend: ([l, it]) => { for (const x of $iter(it)) l.push(x); return null; },
  insert: ([l, i, x]) => { let n = Number(toIndex(i)); if (n < 0) n = Math.max(0, l.length + n); l.splice(Math.min(n, l.length), 0, x); return null; },
  pop: ([l, i]) => { if (l.length === 0) throw $error("IndexError", "pop from empty list"); const n = i === undefined ? l.length - 1 : normalizeIndex(i, l.length, "pop"); return l.splice(n, 1)[0]; },
  remove: ([l, x]) => { const i = l.findIndex(v => $eq(v, x)); if (i < 0) throw $error("ValueError", "list.remove(x): x not in list"); l.splice(i, 1); return null; },
  index: ([l, x]) => { const i = l.findIndex(v => $eq(v, x)); if (i < 0) throw $error("ValueError", `${$repr(x)} is not in list`); return BigInt(i); },
  count: ([l, x]) => BigInt(l.filter(v => $eq(v, x)).length),
  sort: ([l], kw) => { const s = sortList(l, kw); l.splice(0, l.length, ...s); return null; },
  reverse: ([l]) => { l.reverse(); return null; },
  copy: ([l]) => [...l], clear: ([l]) => { l.length = 0; return null; },
}, ([it]) => it === undefined ? [] : $list(it));
const tuple = nativeClass("tuple", [object], {
  index: ([t, x]) => { const i = t.items.findIndex(v => $eq(v, x)); if (i < 0) throw $error("ValueError", "tuple.index(x): x not in tuple"); return BigInt(i); },
  count: ([t, x]) => BigInt(t.items.filter(v => $eq(v, x)).length),
}, ([it]) => new Tuple(it === undefined ? [] : $list(it)));
const dict = nativeClass("dict", [object], {
  get: ([d, k, def]) => { const v = d.get(k); return v === undefined ? (def === undefined ? null : def) : v; },
  keys: ([d]) => d.keys(), values: ([d]) => [...d.map.values()].map(e => e[1]),
  items: ([d]) => [...d.map.values()].map(([k, v]) => new Tuple([k, v])),
  pop: ([d, k, def]) => { const v = d.get(k); if (v === undefined) { if (def !== undefined) return def; throw $call($b.KeyError, [k]); } d.delete(k); return v; },
  setdefault: ([d, k, def]) => { const v = d.get(k); if (v !== undefined) return v; const x = def === undefined ? null : def; d.set(k, x); return x; },
  update: ([d, other], kw) => { if (other instanceof Dict) for (const [, [k, v]] of other.map) d.set(k, v); else if (other !== undefined) for (const p of $iter(other)) { const [k, v] = $list(p); d.set(k, v); } if (kw) for (const [k, v] of Object.entries(kw)) d.set(k, v); return null; },
  copy: ([d]) => { const c = new Dict(); for (const [, [k, v]] of d.map) c.set(k, v); return c; },
  clear: ([d]) => { d.map.clear(); return null; },
}, ([it], kw) => { const d = new Dict(); if (it instanceof Dict) for (const [, [k, v]] of it.map) d.set(k, v); else if (it !== undefined) for (const p of $iter(it)) { const [k, v] = $list(p); d.set(k, v); } if (kw) for (const [k, v] of Object.entries(kw)) d.set(k, v); return d; });
const set = nativeClass("set", [object], {
  add: ([s, x]) => { s.add(x); return null; },
  remove: ([s, x]) => { if (!s.delete(x)) throw $call($b.KeyError, [x]); return null; },
  discard: ([s, x]) => { s.delete(x); return null; },
  union: ([s, ...o]) => new PySet([...s.items(), ...o.flatMap($list)]),
  intersection: ([s, o]) => { const other = new PySet($list(o)); return new PySet(s.items().filter(x => other.has(x))); },
  difference: ([s, o]) => { const other = new PySet($list(o)); return new PySet(s.items().filter(x => !other.has(x))); },
  issubset: ([s, o]) => { const other = new PySet($list(o)); return s.items().every(x => other.has(x)); },
  pop: ([s]) => { const x = s.items()[0]; if (x === undefined) throw $error("KeyError", "pop from an empty set"); s.delete(x); return x; },
  copy: ([s]) => new PySet(s.items()),
}, ([it]) => new PySet(it === undefined ? [] : $list(it)));
nativeClass("frozenset", [set], {}, ([it]) => new PySet(it === undefined ? [] : $list(it)));

/** Runs the module; an uncaught exception is reported like CPython does (on stderr, exit status 1). */
function $run(main) {
  try { main(); }
  catch (e) {
    const x = $wrap(e);
    const message = $str(x);
    const text = `Traceback (most recent call last):\n${$type(x).name}${message ? ": " + message : ""}\n`;
    if (typeof process !== "undefined") { process.stderr.write(text); process.exitCode = 1; } else console.error(text);
  }
}
