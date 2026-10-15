package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SquareTest {
    @Test
    void fourByFourArea() {
        assertEquals(16, new Square(4).area());
    }
}
