package example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorTest {
    @Test
    void adds() {
        assertEquals(4, new Calculator().add(2, 2));
    }
}
