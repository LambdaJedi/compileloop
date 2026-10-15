package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChooserTest {
    @Test
    void picksSecondWhenNotFirst() {
        assertEquals(7, new Chooser().pick(false, 3, 7));
    }
}
