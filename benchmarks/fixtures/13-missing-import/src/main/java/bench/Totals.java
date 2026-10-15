package bench;

public class Totals {
    public int sum(int a, int b) {
        return Math.addExact(a, b);
    }

    public List<Integer> empty() {
        return new ArrayList<>();
    }
}
