"""Newer syntax: with, :=, / and * parameters, ** in displays, bytes, except*, class keywords."""
import contextlib
import dataclasses
from typing import NamedTuple


class Resource:
    def __init__(self, name, log):
        self.name, self.log = name, log

    def __enter__(self):
        self.log.append(f"enter {self.name}")
        return self.name.upper()

    def __exit__(self, kind, value, tb):
        self.log.append(f"exit {self.name} {kind.__name__ if kind else None}")
        return kind is ZeroDivisionError


log = []
with Resource("a", log) as a, Resource("b", log) as b:
    log.append(a + b)
with (Resource("c", log) as c,
      Resource("d", log)):
    1 / 0
print(log)

with contextlib.suppress(KeyError):
    {}["missing"]
with open(__file__) as f:
    size = len(f.read())
print(size > 100, f.closed)


def run(path):
    with contextlib.ExitStack() as stack:
        for i in range(3):
            stack.callback(print, "cleanup", i)
        if path:
            return "early"
    return "late"


print(run(True), run(False))

data = [3, 8, 1, 9, 4]
if (n := len(data)) > 3:
    print(f"{n} items")
print([y for x in data if (y := x * 2) > 5], y)
print(total := sum(data), total)


def area(width, height, /, *, unit="cm", precision=1):
    return f"{width * height:.{precision}f}{unit}"


def mixed(a, /, b, *args, c, d=4, **kw):
    return a, b, args, c, d, kw


print(area(2, 3), area(2.5, 4, unit="m", precision=2))
print(mixed(1, 2, 3, c=5), mixed(1, b=2, c=3, e=6))
for bad in (lambda: area(width=1, height=2), lambda: mixed(1, 2), lambda: area(1, 2, "mm")):
    try:
        bad()
    except TypeError as e:
        print("TypeError:", e)
kw_lambda = lambda x, *, scale=2: x * scale
print(kw_lambda(4), kw_lambda(4, scale=3))

defaults = {"color": "red", "size": 1}
custom = {**defaults, "size": 3, **{"shape": "round"}}
print(custom, {**{}, **custom} == custom)
print([*range(3), *"ab"], {*"aab", *[1]} == {"a", "b", 1})

raw = b"\x00ab\xff" + bytes([10, 65])
print(raw, len(raw), raw[1], raw.hex(), b"x" * 3, ...)
print(f"{n=}, {data[0] = }, {n=:>5}, {n!s:^7}|", f"{'nested'!r}")


def ellipsis_body(): ...


print(ellipsis_body(), Ellipsis is ...)

try:
    raise ExceptionGroup("several", [ValueError("v"), TypeError("t"), ValueError("w")])
except* ValueError as group:
    print("values:", [str(e) for e in group.exceptions])
except* TypeError as group:
    print("types:", [str(e) for e in group.exceptions])


class Registry:
    plugins = {}

    def __init_subclass__(cls, /, key=None, **kwargs):
        super().__init_subclass__(**kwargs)
        Registry.plugins[key or cls.__name__] = cls


class Json(Registry, key="json"):
    pass


class Xml(Registry):
    pass


print(sorted(Registry.plugins))


class Meta(type):
    def __new__(mcs, name, bases, ns, **kw):
        ns["tag"] = kw.get("tag", "none")
        return super().__new__(mcs, name, bases, ns)


class Tagged(metaclass=Meta, tag="hello"):
    pass


bases = (Json,)


class Child(*bases):
    pass


print(Tagged.tag, Child.__mro__[1].__name__)


@dataclasses.dataclass(frozen=True, order=True)
class Point:
    x: int
    y: int = 0

    def __add__(self, other: "Point") -> "Point":
        return Point(self.x + other.x, self.y + other.y)


class Pair(NamedTuple):
    left: str
    right: str = "?"


print(Point(1, 2) + Point(3), sorted([Point(2), Point(1, 5)]), Pair("a"))
match_like = {"status": 200, **{"body": "ok"}}
print(match_like.get("status"), (lambda **kw: kw)(**match_like))
print(divmod(-7, 2), 2 ** -1, -2 ** 2, (-2) ** 2, not 1 == 2, (1, 2) < (1, 3) < (2,))
print((lambda: (yield))().__class__.__name__, [i for i in range(10)][::3], "abc"[::-1])
