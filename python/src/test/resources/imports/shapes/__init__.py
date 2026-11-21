from .circle import Circle
from .square import Square
from . import base


def total_area(shapes):
    return sum(s.area() for s in shapes)


__all__ = ["Circle", "Square", "total_area", "base"]
