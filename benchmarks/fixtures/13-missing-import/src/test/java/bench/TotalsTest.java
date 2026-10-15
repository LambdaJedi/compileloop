package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TotalsTest {
    @Test
    void sums() {
        assertEquals(5, new Totals().sum(2, 3));
    }
}
