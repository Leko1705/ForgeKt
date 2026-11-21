from math import pi

from .base import Shape


class Circle(Shape):
    def __init__(self, r):
        self.r = r

    def area(self):
        return pi * self.r ** 2

    def _scaled(self, factor):
        return Circle(self.r * factor)
