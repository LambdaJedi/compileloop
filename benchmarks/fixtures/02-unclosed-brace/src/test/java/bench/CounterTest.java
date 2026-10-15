package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterTest {
    @Test
    void startsAtZero() {
        assertEquals(0, new Counter().value());
    }
}
