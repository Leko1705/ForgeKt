from_cache = {}


def fib(n):
    if n in from_cache:
        return from_cache[n]
    result = n if n < 2 else fib(n - 1) + fib(n - 2)
    from_cache[n] = result
    return result


print(f"fib(90) = {fib(90)}")


def sieve(limit):
    is_prime = [True] * (limit + 1)
    is_prime[0] = is_prime[1] = False
    for i in range(2, int(limit ** 0.5) + 1):
        if is_prime[i]:
            for j in range(i * i, limit + 1, i):
                is_prime[j] = False
    return [i for i, p in enumerate(is_prime) if p]


primes = sieve(50)
print(f"{len(primes)} primes below 50: {primes}")

factorial = 1
for i in range(1, 31):
    factorial *= i
print(f"30! = {factorial}, digits: {len(str(factorial))}")

matrix = [[1, 2, 3], [4, 5, 6]]
transposed = [[row[i] for row in matrix] for i in range(3)]
product = [[sum(a * b for a, b in zip(r, c)) for c in zip(*matrix)] for r in transposed]
print(transposed, product)

text = "the quick brown fox jumps over the lazy dog the end"
counts = {}
for word in text.split():
    counts[word] = counts.get(word, 0) + 1
top = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:3]
print(top)
for word, n in top:
    print(f"{word:>8}|{n:<3}|{n / len(text.split()):.2%}|{n:05d}")

people = [("ann", 31), ("bob", 25), ("cy", 31)]
people.sort(key=lambda p: (p[1], p[0]), reverse=True)
print(people, max(people, key=lambda p: p[1]), min(p[1] for p in people))
print(f"{3.14159:.2f} {255:x} {255:#b} {1234567:,} {'left':<6}| {'mid':^7} | {42!r} {'q'!r}")


def binary_search(xs, target):
    lo, hi = 0, len(xs) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        if xs[mid] == target:
            return mid
        elif xs[mid] < target:
            lo = mid + 1
        else:
            hi = mid - 1
    return -1


print([binary_search(primes, p) for p in (2, 23, 47, 4)])


def permutations(xs):
    if len(xs) <= 1:
        return [xs]
    return [[x] + p for i, x in enumerate(xs) for p in permutations(xs[:i] + xs[i + 1:])]


print(len(permutations([1, 2, 3, 4])), permutations([1, 2, 3])[:3])
words = ["banana", "Apple", "cherry"]
print(sorted(words), sorted(words, key=str.lower), " ".join(w.capitalize() for w in words))
print(2 ** 64, -(2 ** 63), 10 ** 20 // 3, 7.0 // 2, -7.5 % 2, 1 / 3)
