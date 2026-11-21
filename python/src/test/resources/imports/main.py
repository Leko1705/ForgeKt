"""A program of several modules: a package with relative imports, aliases, the standard library."""
from __future__ import annotations

import sys
import collections.abc
import helpers as h
from math import pi, sqrt as square_root
from shapes import Circle, Square, total_area
from shapes.base import Shape
import shapes.base


def describe(shapes: list[Shape]) -> str:
    from itertools import groupby
    names = sorted(type(s).__name__ for s in shapes)
    return ", ".join(f"{k}x{len(list(g))}" for k, g in groupby(names))


figures = [Circle(1), Square(2), Circle(0.5), Square(3)]
print(describe(figures))
print(f"total area: {total_area(figures):.3f}")
print(h.clamp(15, 0, 10), h.clamp(-3, 0, 10), h.VERSION)
print(round(square_root(2), 6), round(pi, 4))
print(isinstance(figures, collections.abc.Sequence), shapes.base.Shape is Shape)
print(h.counter.__module__, Circle.__module__, __name__)
print([name for name in sorted(sys.modules) if name.startswith("shapes")])
try:
    figures[0].scale(-1)
except ValueError as e:
    print("error:", e)
