package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagTest {
    @Test
    void trueIsOne() {
        assertEquals(1, new Flag().asNumber(true));
    }
}
