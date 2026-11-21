# numbers
print(7 // 2, -7 // 2, 7 % 3, -7 % 3, 7 / 2, 2 ** 100, 2 ** -1)
print(1 + 2.5, 10 / 4, 3 * 1.5, round(2.5), round(3.5), abs(-4), divmod(17, 5))
print(0.1 + 0.2, 1e16, 1.5e-7, 100.0, float("3.25"), int("42") + 1, int(3.9))
print(True + True, 5 > 3 > 1, 1 < 2 > 5, 3 == 3.0, 7 & 3, 1 << 10, ~5)

# strings
s = "Hello, World"
print(s.upper(), s.lower(), s[0], s[-1], s[2:5], s[::-1], len(s))
print(s.split(", "), "-".join(["a", "b", "c"]), s.replace("l", "L"), s.find("World"))
print("  pad  ".strip() + "|", "abc" * 3, "x" in s, "W" in s, repr("it's"))
print("%s is %d years" % ("Ann", 7), "{} + {} = {}".format(1, 2, 3))

# lists, tuples, dicts, sets
xs = [3, 1, 4, 1, 5, 9, 2, 6]
xs.append(5)
print(xs, sorted(xs), sorted(xs, reverse=True), xs[1:4], xs[-2:], min(xs), max(xs), sum(xs))
xs.sort()
print(xs, xs.count(1), xs.index(4), xs.pop(), xs)
t = (1, "two", 3.0)
a, b, c = t
print(t, a, b, c, len(t), (5,), ())
first, *rest = [1, 2, 3, 4]
print(first, rest)
d = {"one": 1, "two": 2}
d["three"] = 3
print(d, d["two"], d.get("four", 0), list(d.keys()), list(d.values()), "one" in d, len(d))
for k, v in d.items():
    print(k, "->", v)
print({1, 2, 3} | {3, 4}, {1, 2, 3} & {2, 3, 4}, set(), sorted({5, 3, 1}))

# control flow
total = 0
for i in range(10):
    if i % 2 == 0:
        continue
    if i > 7:
        break
    total += i
else:
    print("not reached")
print("total", total)
n = 0
while n < 3:
    n += 1
else:
    print("while done", n)
for i in range(3):
    pass
else:
    print("for done", i)

# comprehensions
squares = [x * x for x in range(6) if x % 2 == 0]
pairs = [(x, y) for x in range(3) for y in range(x)]
print(squares, pairs, {x: x * x for x in range(4)}, {x % 3 for x in range(10)}, sum(x for x in range(101)))

# functions
def greet(name, greeting="Hello", *others, **options):
    return greeting + ", " + name + str(others) + str(sorted(options.items()))

print(greet("Ann"), greet("Bob", "Hi", 1, 2, loud=True))
print(greet(greeting="Hey", name="Cy"))

def counter():
    count = 0
    def increment():
        nonlocal count
        count += 1
        return count
    return increment

inc = counter()
inc(); inc()
print("counter", inc())

calls = 0
def track():
    global calls
    calls += 1

track(); track()
print("calls", calls)
print(list(map(lambda x: x * 2, [1, 2, 3])), list(filter(lambda x: x > 1, [1, 2, 3])), sorted(["bb", "a", "ccc"], key=len))
print(list(enumerate("ab", 1)), list(zip([1, 2], "xy")), any([0, 1]), all([1, 0]), list(reversed([1, 2, 3])))
x = None
print(x is None, x is not None, "yes" if x else "no", [] or "empty", 0 and 1, isinstance(3, int), isinstance(True, int))
