package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DoublerTest {
    @Test
    void doubles() {
        assertEquals(10, new Doubler().twice(5));
    }
}
