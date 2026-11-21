from .base import Shape


class Square(Shape):
    def __init__(self, side):
        self.side = side

    def area(self):
        return self.side * self.side

    def _scaled(self, factor):
        return Square(self.side * factor)
