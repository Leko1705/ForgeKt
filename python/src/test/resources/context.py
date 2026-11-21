# with, :=, keyword-only and positional-only parameters, ** in dicts (also supported by the JavaScript backend)
class Tracker:
    def __init__(self, name, log, suppress=False):
        self.name, self.log, self.suppress = name, log, suppress

    def __enter__(self):
        self.log.append("enter " + self.name)
        return self

    def __exit__(self, kind, value, tb):
        self.log.append(f"exit {self.name} {None if kind is None else kind.__name__}")
        return self.suppress


log = []
with Tracker("outer", log) as o, Tracker("inner", log, suppress=True) as i:
    log.append(o.name + "+" + i.name)
    raise ValueError("hidden")
print(log)


def first_negative(values):
    for v in values:
        with Tracker(str(v), log):
            if v < 0:
                return v
    return None


log.clear()
print(first_negative([1, -2, 3]), log)
try:
    with Tracker("t", log):
        raise KeyError("k")
except KeyError as e:
    print("escaped", e, log[-1])

values = [4, 9, 2, 7]
if (count := len(values)) > 2:
    print("count", count)
print([d for v in values if (d := v * 10) > 30], d)
while (n := len(values)) > 2:
    values.pop()
print(values, n)


def scaled(x, /, factor=2, *, offset=0, **rest):
    return x * factor + offset + len(rest)


print(scaled(3), scaled(3, 4), scaled(3, offset=1), scaled(3, factor=1, offset=5, extra=1))
try:
    scaled(x=3)
except TypeError as e:
    print("TypeError")
try:
    (lambda *, k: k)()
except TypeError as e:
    print("TypeError:", e)

base = {"a": 1, "b": 2}
merged = {**base, "b": 3, **{"c": 4}}
print(merged, len({**{}}))
