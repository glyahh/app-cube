public class CalculatorTest {
    public static void main(String[] args) {
        if (Calculator.add(2, 3) != 5) {
            System.err.println("add failed");
            System.exit(1);
        }
        System.out.println("ok");
    }
}
