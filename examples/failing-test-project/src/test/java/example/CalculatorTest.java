package example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorTest {
    @Test
    void adds() {
        // Intentionally wrong: 2 + 2 is 4, not 5.
        assertEquals(5, new Calculator().add(2, 2));
    }
}
