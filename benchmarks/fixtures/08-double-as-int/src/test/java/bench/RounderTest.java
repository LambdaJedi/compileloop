package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RounderTest {
    @Test
    void halvesEvenNumbers() {
        assertEquals(4, new Rounder().half(8));
    }
}
