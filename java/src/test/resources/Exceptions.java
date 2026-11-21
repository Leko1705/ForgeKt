class InsufficientFunds extends Exception {
    final int missing;

    InsufficientFunds(int missing) {
        super("missing " + missing);
        this.missing = missing;
    }
}

class Account {
    private int balance;

    Account(int balance) { this.balance = balance; }

    void withdraw(int amount) throws InsufficientFunds {
        if (amount > balance) throw new InsufficientFunds(amount - balance);
        balance -= amount;
    }

    int balance() { return balance; }
}

public class Exceptions {
    static String log = "";

    static int parse(int code) {
        try {
            if (code < 0) throw new IllegalArgumentException("negative: " + code);
            if (code == 0) throw new IllegalStateException("zero");
            return code * 2;
        } catch (IllegalArgumentException | IllegalStateException e) {
            log += "[" + e.getMessage() + "]";
            return -1;
        } finally {
            log += ".";
        }
    }

    public static void main(String[] args) {
        Account account = new Account(100);
        try {
            account.withdraw(30);
            account.withdraw(100);
            System.out.println("not reached");
        } catch (InsufficientFunds e) {
            System.out.println("failed: " + e.getMessage() + " (" + e.missing + ")");
        } finally {
            System.out.println("balance " + account.balance());
        }
        System.out.println(parse(21) + " " + parse(-5) + " " + parse(0));
        System.out.println("log " + log);
        try {
            try {
                throw new RuntimeException("inner");
            } finally {
                System.out.println("inner finally");
            }
        } catch (RuntimeException e) {
            System.out.println("outer caught " + e.getMessage());
        }
        int[] data = new int[3];
        int i = 0;
        while (true) {
            if (i == 3) break;
            data[i] = i + 1;
            i++;
        }
        System.out.println("data " + data[0] + data[1] + data[2]);
        throw new UnsupportedOperationException("done");
    }
}
