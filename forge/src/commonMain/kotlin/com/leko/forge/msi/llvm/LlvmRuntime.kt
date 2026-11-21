package com.leko.forge.msi.llvm

/** The C runtime the generated LLVM IR is linked with. Compile both: `clang module.ll msi_runtime.c`. */
public val LLVM_RUNTIME_C: String = """
// MSI runtime for the LLVM backend. Every MSI value is one 64 bit word; heap objects start with a type id.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>

typedef int64_t w;
enum { T_STR = 1, T_ARRAY = 2, T_FN = 3 };

void msi_die(const char* message) { fprintf(stderr, "MSI runtime error: %s\n", message); exit(70); }

// ---- memory: every object is registered. A hidden word before each object holds its registry index.
// Owned and value objects ("linear") are freed explicitly by generated code; all others are collected
// by a conservative mark and sweep collector that scans the stack, the registered roots and the heap.
typedef struct { w* object; w words; char linear; char marked; } Entry;
static Entry* entries = 0;
static w count = 0, capacity = 0, since_collection = 0, threshold = 100000, live_linear = 0;
static w* roots[4096];
static int root_count = 0;
static w thrown = 0;
void msi_gc_collect(void);

static w* allocate_object(w words, char linear) {
    if (++since_collection >= threshold) msi_gc_collect();
    w* block = calloc((size_t) words + 1, 8);
    if (!block) msi_die("out of memory");
    if (count == capacity) {
        capacity = capacity ? capacity * 2 : 1024;
        entries = realloc(entries, (size_t) capacity * sizeof(Entry));
        if (!entries) msi_die("out of memory");
    }
    block[0] = count;
    entries[count++] = (Entry) { block + 1, words, linear, 0 };
    if (linear) live_linear++;
    return block + 1;
}

static void unregister(w index) {
    entries[index] = entries[--count];
    if (index < count) entries[index].object[-1] = index;
}

static w* allocate(w words) { return allocate_object(words, 0); }
w msi_alloc(w words) { return (w) allocate(words); }
w msi_alloc_linear(w words) { return (w) allocate_object(words, 1); }
void msi_free_linear(w object) {
    if (object == 0) return;
    w* p = (w*) object;
    unregister(p[-1]);
    free(p - 1);
    live_linear--;
}
void msi_gc_root(w* root) { if (root_count == 4096) msi_die("too many roots"); roots[root_count++] = root; }
w msi_live_objects(void) { return count; }
w msi_live_linear(void) { return live_linear; }

// sorted view of the objects, for finding the object an (interior) pointer points into
static w* sorted = 0;
static int compare_entries(const void* a, const void* b) {
    w x = (w) entries[*(const w*) a].object, y = (w) entries[*(const w*) b].object;
    return x < y ? -1 : x > y;
}
static w find(w value) {
    w lo = 0, hi = count - 1;
    while (lo <= hi) {
        w mid = (lo + hi) / 2;
        Entry* e = &entries[sorted[mid]];
        if (value < (w) e->object) hi = mid - 1;
        else if (value >= (w) (e->object + (e->words ? e->words : 1))) lo = mid + 1;
        else return sorted[mid];
    }
    return -1;
}
static w* stack = 0;
static w stack_size = 0, stack_capacity = 0;
static void mark(w value) {
    w i = find(value);
    if (i < 0 || entries[i].marked) return;
    entries[i].marked = 1;
    if (stack_size == stack_capacity) { stack_capacity = stack_capacity ? stack_capacity * 2 : 1024; stack = realloc(stack, (size_t) stack_capacity * 8); }
    stack[stack_size++] = i;
}
static void scan(const void* from, const void* to) {
    for (const char* p = from; p + 8 <= (const char*) to; p += 8) { w value; memcpy(&value, p, 8); mark(value); }
}
extern void* __libc_stack_end;
typedef struct Handler Handler;
static Handler* handlers;
static void scan_handlers(void);

void msi_gc_collect(void) {
    since_collection = 0;
    if (count == 0) return;
    jmp_buf registers;
    setjmp(registers); // spills the registers onto the stack
    volatile w here = 0;
    sorted = realloc(sorted, (size_t) count * 8);
    for (w i = 0; i < count; i++) { sorted[i] = i; entries[i].marked = 0; }
    qsort(sorted, (size_t) count, 8, compare_entries);
    scan((const void*) &here, __libc_stack_end);
    scan(&registers, (const char*) &registers + sizeof registers);
    for (int i = 0; i < root_count; i++) mark(*roots[i]);
    mark(thrown);
    scan_handlers();
    for (w i = 0; i < count; i++) if (entries[i].linear) mark((w) entries[i].object); // owned objects keep what they point to
    while (stack_size > 0) { Entry* e = &entries[stack[--stack_size]]; scan(e->object, e->object + e->words); }
    for (w i = 0; i < count;) {
        if (!entries[i].marked && !entries[i].linear) { w* block = entries[i].object - 1; unregister(i); free(block); }
        else i++;
    }
    if (threshold < count * 2) threshold = count * 2;
}

// ---- access checks: readers and writers per object
typedef struct { w object; w readers; w writing; } AccessEntry;
static AccessEntry* access_entries = 0;
static w access_count = 0, access_capacity = 0;
void msi_access_begin(w object, w mutable_) {
    AccessEntry* a = 0;
    for (w i = 0; i < access_count; i++) if (access_entries[i].object == object) a = &access_entries[i];
    if (!a) {
        if (access_count == access_capacity) { access_capacity = access_capacity ? access_capacity * 2 : 16; access_entries = realloc(access_entries, (size_t) access_capacity * sizeof(AccessEntry)); }
        a = &access_entries[access_count++];
        *a = (AccessEntry) { object, 0, 0 };
    }
    if (a->writing || (mutable_ && a->readers > 0)) msi_die(a->writing ? "simultaneous access: it is already being modified" : "simultaneous access: it is already being read");
    if (mutable_) a->writing = 1; else a->readers++;
}
void msi_access_end(w object, w mutable_) {
    for (w i = 0; i < access_count; i++) if (access_entries[i].object == object) {
        if (mutable_) access_entries[i].writing = 0; else access_entries[i].readers--;
        if (!access_entries[i].writing && access_entries[i].readers == 0) access_entries[i] = access_entries[--access_count];
        return;
    }
}

// ---- strings: [T_STR, length, bytes..., 0]
w msi_str(const char* bytes, w length) {
    w* p = allocate(2 + (length + 8) / 8);
    p[0] = T_STR; p[1] = length;
    memcpy(&p[2], bytes, (size_t) length);
    return (w) p;
}
const char* msi_cstr(w s) { return (const char*) &((w*) s)[2]; }
w msi_str_length(w s) { return ((w*) s)[1]; }
w msi_concat(w a, w b) {
    w la = msi_str_length(a), lb = msi_str_length(b);
    char* buffer = malloc((size_t) (la + lb + 1));
    memcpy(buffer, msi_cstr(a), (size_t) la); memcpy(buffer + la, msi_cstr(b), (size_t) lb);
    w s = msi_str(buffer, la + lb); free(buffer); return s;
}
w msi_str_eq(w a, w b) { return msi_str_length(a) == msi_str_length(b) && memcmp(msi_cstr(a), msi_cstr(b), (size_t) msi_str_length(a)) == 0; }
w msi_str_cmp(w a, w b) {
    w la = msi_str_length(a), lb = msi_str_length(b);
    int c = memcmp(msi_cstr(a), msi_cstr(b), (size_t) (la < lb ? la : lb));
    if (c != 0) return c < 0 ? -1 : 1;
    return la < lb ? -1 : la > lb ? 1 : 0;
}
w msi_i64_str(w v) { char b[32]; int n = snprintf(b, sizeof b, "%lld", (long long) v); return msi_str(b, n); }
w msi_bool_str(w v) { return v ? msi_str("true", 4) : msi_str("false", 5); }

/** Like Kotlin's Double.toString on the JVM: plain between 1e-3 and 1e7, else scientific (1.0E7). */
w msi_f64_str(w bits) {
    double d; memcpy(&d, &bits, 8);
    char b[64];
    if (isnan(d)) return msi_str("NaN", 3);
    if (isinf(d)) return d > 0 ? msi_str("Infinity", 8) : msi_str("-Infinity", 9);
    double a = fabs(d);
    if (d == 0 || (a >= 1e-3 && a < 1e7)) {
        for (int p = 1; p <= 17; p++) { snprintf(b, sizeof b, "%.*f", p, d); if (strtod(b, 0) == d) break; }
        if (d == 0 && signbit(d)) strcpy(b, "-0.0");
        return msi_str(b, (w) strlen(b));
    }
    char m[64];
    for (int p = 0; p <= 16; p++) { snprintf(m, sizeof m, "%.*e", p, d); if (strtod(m, 0) == d) break; }
    char* e = strchr(m, 'e'); *e = 0;
    int exponent = atoi(e + 1);
    snprintf(b, sizeof b, strchr(m, '.') ? "%sE%d" : "%s.0E%d", m, exponent);
    return msi_str(b, (w) strlen(b));
}

// ---- arrays: [T_ARRAY, length, elements...]
w msi_array_new(w length, w zero) {
    if (length < 0) msi_die("invalid array size");
    w* p = allocate(2 + length);
    p[0] = T_ARRAY; p[1] = length;
    for (w i = 0; i < length; i++) p[2 + i] = zero;
    return (w) p;
}
w* msi_array_slot(w array, w index) {
    if (array == 0) msi_die("uninitialized array");
    w* p = (w*) array;
    if (index < 0 || index >= p[1]) msi_die("array index out of bounds");
    return &p[2 + index];
}
w msi_array_length(w array) { if (array == 0) msi_die("uninitialized array"); return ((w*) array)[1]; }
w msi_check(w object) { if (object == 0) msi_die("uninitialized value"); return object; }

// ---- arithmetic with defined results where C has none
w msi_div64(w a, w b) { if (b == 0) msi_die("division by zero"); if (b == -1) return (w) (0 - (uint64_t) a); return a / b; }
w msi_rem64(w a, w b) { if (b == 0) msi_die("division by zero"); if (b == -1) return 0; return a % b; }
w msi_div32(w a, w b) { int32_t x = (int32_t) a, y = (int32_t) b; if (y == 0) msi_die("division by zero"); if (y == -1) return (int32_t) (0 - (uint32_t) x); return x / y; }
w msi_rem32(w a, w b) { int32_t x = (int32_t) a, y = (int32_t) b; if (y == 0) msi_die("division by zero"); if (y == -1) return 0; return x % y; }
w msi_f2l(w bits) { double d; memcpy(&d, &bits, 8); if (isnan(d)) return 0; if (d >= 9223372036854775807.0) return INT64_MAX; if (d <= -9223372036854775808.0) return INT64_MIN; return (w) d; }
w msi_f2i(w bits) { double d; memcpy(&d, &bits, 8); if (isnan(d)) return 0; if (d >= 2147483647.0) return INT32_MAX; if (d <= -2147483648.0) return INT32_MIN; return (int32_t) d; }

// ---- exceptions: a stack of setjmp handlers
struct Handler { jmp_buf buffer; struct Handler* previous; };
static Handler* handlers = 0;
static void scan_handlers(void) { for (Handler* h = handlers; h; h = h->previous) scan(&h->buffer, (const char*) &h->buffer + sizeof h->buffer); }
void* msi_try_push(void) { Handler* h = malloc(sizeof(Handler)); h->previous = handlers; handlers = h; return h->buffer; }
void msi_try_pop(void) { Handler* h = handlers; handlers = h->previous; free(h); }
void msi_throw(w value) {
    thrown = value;
    if (!handlers) { fprintf(stderr, "uncaught MSI exception\n"); exit(71); }
    Handler* h = handlers; handlers = h->previous;   // the handler is used up (not freed: its buffer is jumped to)
    longjmp(h->buffer, 1);
}
w msi_thrown(void) { return thrown; }
w msi_type_of(w object) { return object == 0 ? 0 : ((w*) object)[0]; }
""".trimStart()
