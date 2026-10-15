package bench;

public class Palindrome {
    public boolean isPalindrome(String s) {
        return s.equals(Strings.reverse(s));
    }
}
