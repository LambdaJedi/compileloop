package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TagTest {
    @Test
    void wrapsWithBold() {
        assertEquals("<b>hello</b>", new Tag().wrap("hello"));
    }
}
