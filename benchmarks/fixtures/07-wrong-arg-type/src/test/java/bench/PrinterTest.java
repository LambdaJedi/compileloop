package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PrinterTest {
    @Test
    void repeatsThreeTimes() {
        assertEquals("hi hi hi ", new Printer().banner("hi "));
    }
}
