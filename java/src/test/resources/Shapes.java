interface Shape {
    double area();
    String name();
}

abstract class Base implements Shape {
    private static int created = 0;
    protected final int id;

    Base() {
        created++;
        id = created;
    }

    public String name() { return "shape"; }

    public String toString() { return name() + "#" + id + " with area " + area(); }

    static int count() { return created; }
}

class Circle extends Base {
    private final double r;

    Circle(double r) {
        super();
        this.r = r;
    }

    public double area() { return 3.0 * r * r; }

    public String name() { return "circle"; }
}

class Rect extends Base {
    double w, h;

    Rect(double w, double h) {
        this.w = w;
        this.h = h;
    }

    Rect(double side) { this(side, side); }

    public double area() { return w * h; }
}

class Square extends Rect {
    Square(double side) { super(side); }

    public String name() { return "square"; }
}

public class Shapes {
    public static void main(String[] args) {
        Shape[] shapes = { new Circle(1.0), new Rect(2.0, 3.0), new Square(2.0) };
        double total = 0;
        for (Shape s : shapes) {
            System.out.println(s);
            total += s.area();
        }
        System.out.println("total area " + total);
        System.out.println("created " + Base.count());
        Shape s = shapes[2];
        if (s instanceof Rect) {
            Rect r = (Rect) s;
            System.out.println("a rect of width " + r.w);
        }
        System.out.println(shapes[0] instanceof Rect);
        try {
            Rect wrong = (Rect) shapes[0];
            System.out.println("not reached " + wrong);
        } catch (ClassCastException e) {
            System.out.println("caught a ClassCastException");
        }
    }
}
