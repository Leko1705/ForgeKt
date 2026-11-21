public class Basics {
    static int counter = 10;

    static int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

    static long factorial(int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) result *= i;
        return result;
    }

    public static void main(String[] args) {
        System.out.println("fib(15) = " + fib(15));
        System.out.println("20! = " + factorial(20));
        int x = 7, y = 2;
        System.out.println(x / y + " " + x % y + " " + (double) x / y);
        System.out.println(1 + 2 + "3" + 4 + 5);
        double d = 1.5;
        d += 1;
        System.out.println("d = " + d);
        int[] squares = new int[5];
        for (int i = 0; i < squares.length; i++) squares[i] = i * i;
        int sum = 0;
        for (int s : squares) sum += s;
        System.out.println("sum of squares = " + sum);
        int[] primes = {2, 3, 5, 7};
        System.out.println("primes: " + primes.length + ", last " + primes[primes.length - 1]);
        outer:
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                if (j == 3) continue outer;
                if (i == 3) break outer;
                counter++;
            }
        }
        System.out.println("counter = " + counter);
        int k = 0;
        do { k++; if (k % 2 == 0) continue; } while (k < 5);
        System.out.println("k = " + k);
        boolean flag = x > y && !(y > 5);
        System.out.println("flag = " + flag + ", max = " + Math.max(3, 9) + ", " + Math.max(2.5, 1.0) + ", abs = " + Math.abs(-4L));
        System.out.println(x > 5 ? "big" : "small");
        int bits = (x << 3) | (y & 1) ^ 3;
        System.out.println("bits = " + bits);
    }
}
