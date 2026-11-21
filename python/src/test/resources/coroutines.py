import asyncio
from contextlib import asynccontextmanager

log = []


async def fetch(name, delay):
    await asyncio.sleep(delay)
    log.append(name)
    return f"{name}:{delay}"


async def ticker(n):
    for i in range(n):
        await asyncio.sleep(0)
        yield i * i


class Connection:
    def __init__(self, name):
        self.name = name
        self.open = False

    async def __aenter__(self):
        await asyncio.sleep(0)
        self.open = True
        print("open", self.name)
        return self

    async def __aexit__(self, kind, value, traceback):
        self.open = False
        print("close", self.name, kind.__name__ if kind else None)
        return kind is KeyError


class Countdown:
    def __init__(self, start):
        self.current = start

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self.current <= 0:
            raise StopAsyncIteration
        self.current -= 1
        return self.current + 1


@asynccontextmanager
async def section(title):
    print(f"<{title}>")
    try:
        yield title.upper()
    finally:
        print(f"</{title}>")


async def failing():
    await asyncio.sleep(0)
    raise RuntimeError("boom")


async def main():
    results = await asyncio.gather(fetch("a", 0.02), fetch("b", 0.0), fetch("c", 0.01))
    print(results, log)
    squares = [x async for x in ticker(5)]
    evens = {x async for x in ticker(6) if x % 2 == 0}
    print(squares, sorted(evens))
    async for n in Countdown(3):
        print("countdown", n)
    else:
        print("liftoff")
    async with Connection("db") as c, section("work") as title:
        print(c.open, title)
    async with Connection("cache"):
        raise KeyError("suppressed")
    try:
        await failing()
    except RuntimeError as e:
        print("caught", e)
    task = asyncio.create_task(fetch("task", 0))
    print(await task, task.done())
    results = await asyncio.gather(failing(), fetch("d", 0), return_exceptions=True)
    print([type(r).__name__ for r in results])
    print(await (lambda: fetch("lambda", 0))())
    queue = asyncio.Queue()

    async def producer():
        for i in range(3):
            await queue.put(i)
        await queue.put(None)

    async def consumer():
        total = 0
        while (item := await queue.get()) is not None:
            total += item
        return total

    _, total = await asyncio.gather(producer(), consumer())
    print("total", total)
    try:
        async with asyncio.timeout(0.01):
            await asyncio.sleep(1)
    except TimeoutError:
        print("timed out")
    return len(log)


print("done", asyncio.run(main()))
print(asyncio.iscoroutinefunction(main), type(ticker(1)).__name__)
