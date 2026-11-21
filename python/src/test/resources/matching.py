class Point:
    def __init__(self, x, y):
        self.x = x
        self.y = y


def describe(value):
    match value:
        case 0 | 1:
            return "small number"
        case int(n) if n < 0:
            return f"negative {n}"
        case int() | float():
            return "number"
        case "":
            return "empty string"
        case str(s):
            return f"string of {len(s)}"
        case []:
            return "empty list"
        case [x]:
            return f"one item {x}"
        case [first, *rest]:
            return f"starts with {first}, {len(rest)} more"
        case (a, b):
            return f"pair {a} {b}"
        case {"type": "circle", "r": r}:
            return f"circle of radius {r}"
        case {"type": kind}:
            return f"some {kind}"
        case Point(x=0, y=0):
            return "origin"
        case Point(x=0, y=y) as p:
            return f"on the y axis at {y} ({type(p).__name__})"
        case Point(x=x, y=y) if x == y:
            return f"diagonal {x}"
        case None:
            return "nothing"
        case _:
            return "something else"


for v in [0, 1, -5, 7, 2.5, "", "hello", [], [9], [1, 2, 3], (4, 5), {"type": "circle", "r": 2},
          {"type": "square"}, Point(0, 0), Point(0, 3), Point(2, 2), Point(1, 2), None]:
    print(describe(v))

command = "go north"
match command.split():
    case ["go", direction]:
        print("going", direction)
    case ["quit"]:
        print("bye")
