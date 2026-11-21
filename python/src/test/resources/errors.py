class AppError(Exception):
    pass


class NotFound(AppError):
    def __init__(self, key):
        super().__init__(f"missing {key}")
        self.key = key


def lookup(table, key):
    if key not in table:
        raise NotFound(key)
    return table[key]


table = {"a": 1}
for key in ["a", "b"]:
    try:
        value = lookup(table, key)
    except NotFound as e:
        print("not found:", e, e.key, isinstance(e, AppError))
    else:
        print("found", value)
    finally:
        print("checked", key)


def risky(n):
    try:
        if n == 0:
            return 1 / n
        if n == 1:
            return [][n]
        if n == 2:
            return {}["x"]
        if n == 3:
            return "a" + n
        return int("x")
    except ZeroDivisionError as e:
        return f"ZeroDivisionError: {e}"
    except (IndexError, KeyError) as e:
        return f"{type(e).__name__}: {e}"
    except Exception as e:
        return f"{type(e).__name__}: {e}"


for n in range(5):
    print(risky(n))


def order():
    log = []
    try:
        try:
            log.append("try")
            raise ValueError("inner")
        except ValueError:
            log.append("except")
            raise
        finally:
            log.append("finally")
    except ValueError as e:
        log.append(f"outer {e}")
    return log


print(order())


def fib():
    a, b = 0, 1
    while True:
        yield a
        a, b = b, a + b


def take(n, it):
    for i, x in enumerate(it):
        if i >= n:
            return
        yield x


def chain(*iterables):
    for it in iterables:
        yield from it


print(list(take(10, fib())), list(chain([1, 2], "ab", range(3))))
g = take(2, fib())
print(next(g), next(g), next(g, "done"))
try:
    next(g)
except StopIteration:
    print("stopped")

try:
    assert 1 + 1 == 3, "math is broken"
except AssertionError as e:
    print("assertion:", e)

try:
    raise KeyError("k")
except LookupError as e:
    print(repr(e), str(e))

print("before the end")
raise RuntimeError("the end")
