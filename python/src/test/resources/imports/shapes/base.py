class Shape:
    def area(self):
        raise NotImplementedError

    def scale(self, factor):
        if factor <= 0:
            raise ValueError(f"factor must be positive, not {factor}")
        return self._scaled(factor)
