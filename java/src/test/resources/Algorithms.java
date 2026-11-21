public class Algorithms {
    static void sort(int[] a) {
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j + 1 < a.length - i; j++)
                if (a[j] > a[j + 1]) {
                    int t = a[j];
                    a[j] = a[j + 1];
                    a[j + 1] = t;
                }
    }

    static String join(int[] a) {
        String s = "";
        for (int i = 0; i < a.length; i++) s += (i > 0 ? "," : "") + a[i];
        return s;
    }

    static int[][] pascal(int n) {
        int[][] rows = new int[n][];
        for (int i = 0; i < n; i++) {
            rows[i] = new int[i + 1];
            rows[i][0] = 1;
            rows[i][i] = 1;
            for (int j = 1; j < i; j++) rows[i][j] = rows[i - 1][j - 1] + rows[i - 1][j];
        }
        return rows;
    }

    public static void main(String[] args) {
        int[] values = {5, 3, 9, 1, 7};
        sort(values);
        System.out.println(join(values));
        int[][] p = pascal(6);
        System.out.println(join(p[5]));
        int[][] grid = new int[3][4];
        grid[2][3] = 8;
        System.out.println(grid.length + "x" + grid[0].length + " " + grid[2][3]);
        long big = 1L << 40;
        System.out.println(big + " " + (int) big + " " + (big >> 38));
        System.out.println(2147483647 + 1);
    }
}
