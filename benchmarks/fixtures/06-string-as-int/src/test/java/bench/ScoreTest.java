package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoreTest {
    @Test
    void totalsTwoNumbers() {
        assertEquals(7, new Score().total(3, 4));
    }
}
