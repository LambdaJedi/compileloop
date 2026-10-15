package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ListTest {
    @Test
    void hasThreeItems() {
        assertEquals(3, new List().size());
    }
}
