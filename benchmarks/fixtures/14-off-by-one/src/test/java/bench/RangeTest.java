package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RangeTest {
    @Test
    void countsInclusive() {
        assertEquals(5, new Range().count(1, 5));
    }
}
