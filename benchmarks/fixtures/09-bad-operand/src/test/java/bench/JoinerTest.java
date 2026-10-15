package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinerTest {
    @Test
    void joinsWithDash() {
        assertEquals("left-right", new Joiner().join("left", "right"));
    }
}
