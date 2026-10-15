package bench;

public class Clamp {
    public int clamp(int value, int max) {
        if (value > max) {
            return max;
        }
        return value;
    }
}
