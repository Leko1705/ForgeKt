class Animal:
    kingdom = "animalia"
    count = 0

    def __init__(self, name, sound="..."):
        self.name = name
        self.sound = sound
        Animal.count += 1

    def speak(self):
        return f"{self.name} says {self.sound}"

    def __str__(self):
        return f"{type(self).__name__}({self.name})"

    def __repr__(self):
        return f"<{self.name}>"


class Dog(Animal):
    def __init__(self, name):
        super().__init__(name, "woof")
        self.tricks = []

    def learn(self, trick):
        self.tricks.append(trick)
        return self

    def speak(self):
        return super().speak() + "!"


class Cat(Animal):
    def speak(self):
        return "purr"


pets = [Dog("Rex"), Cat("Tom", "meow"), Animal("Generic")]
for p in pets:
    print(p, p.speak(), isinstance(p, Animal), isinstance(p, Dog))
print(Animal.count, Dog.kingdom, pets[0].kingdom, pets)
print(pets[0].learn("sit").learn("roll").tricks)


class Vector:
    def __init__(self, x, y):
        self.x, self.y = x, y

    def __add__(self, other):
        return Vector(self.x + other.x, self.y + other.y)

    def __mul__(self, k):
        return Vector(self.x * k, self.y * k)

    def __eq__(self, other):
        return isinstance(other, Vector) and (self.x, self.y) == (other.x, other.y)

    def __lt__(self, other):
        return (self.x ** 2 + self.y ** 2) < (other.x ** 2 + other.y ** 2)

    def __len__(self):
        return 2

    def __getitem__(self, i):
        return (self.x, self.y)[i]

    def __iter__(self):
        yield self.x
        yield self.y

    def __repr__(self):
        return f"Vector({self.x}, {self.y})"


v = Vector(1, 2) + Vector(3, 4) * 2
print(v, v == Vector(7, 10), v != Vector(0, 0), len(v), v[1], list(v), sorted([Vector(3, 3), Vector(1, 0), Vector(2, 1)]))
x, y = v
print(x, y)


class Temperature:
    def __init__(self, celsius):
        self._celsius = celsius

    @property
    def fahrenheit(self):
        return self._celsius * 9 / 5 + 32

    @fahrenheit.setter
    def fahrenheit(self, value):
        self._celsius = (value - 32) * 5 / 9

    @staticmethod
    def freezing():
        return Temperature(0)

    @classmethod
    def boiling(cls):
        return cls(100)


t = Temperature(25)
print(t.fahrenheit)
t.fahrenheit = 212
print(t._celsius, Temperature.freezing().fahrenheit, Temperature.boiling().fahrenheit)


class A:
    def who(self):
        return "A"


class B(A):
    def who(self):
        return "B" + super().who()


class C(A):
    def who(self):
        return "C" + super().who()


class D(B, C):
    def who(self):
        return "D" + super().who()


print(D().who(), [k.__name__ for k in D.__mro__])


def logged(f):
    def wrapper(*args):
        print("calling", f.__name__, args)
        return f(*args)
    return wrapper


@logged
def add(a, b):
    return a + b


print(add(2, 3))


class Counter:
    def __init__(self):
        self.n = 0

    def __call__(self):
        self.n += 1
        return self.n


c = Counter()
c(); c()
print(c(), callable(c), hasattr(c, "n"), getattr(c, "missing", "default"))
