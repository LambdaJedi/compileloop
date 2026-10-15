package bench;

public class Counter {
    private int count = 0;

    public int increment() {
        return ++count;
    // missing closing brace for increment

    public int value() {
        return count;
    }
}
