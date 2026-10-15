package bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PalindromeTest {
    @Test
    void detectsNonPalindrome() {
        assertFalse(new Palindrome().isPalindrome("hello"));
    }

    @Test
    void detectsPalindrome() {
        assertTrue(new Palindrome().isPalindrome("racecar"));
    }
}
