package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClampTest {
    @Test
    void clampsNegativeInputs() {
        assertEquals(0, new Clamp().clamp(-1, 100));
    }
}
