package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AdderTest {
    @Test
    void adds() {
        assertEquals(4, new Adder().add(2, 2));
    }
}
