package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BoxTest {
    @Test
    void holdsOneNumber() {
        assertEquals(1, new Box().numbers().size());
    }
}
