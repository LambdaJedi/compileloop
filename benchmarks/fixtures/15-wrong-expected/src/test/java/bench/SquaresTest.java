package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SquaresTest {
    @Test
    void fiveSquared() {
        assertEquals(20, new Squares().of(5));
    }
}
