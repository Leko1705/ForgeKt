VERSION = "1.2"


def clamp(x, low, high):
    return max(low, min(x, high))


def counter():
    n = 0

    def step():
        nonlocal n
        n += 1
        return n
    return step
