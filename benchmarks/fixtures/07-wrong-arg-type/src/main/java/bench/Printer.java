package bench;

public class Printer {
    public String repeat(String s, int times) {
        return s.repeat(times);
    }

    public String banner(String title) {
        return repeat(title, "3");
    }
}
