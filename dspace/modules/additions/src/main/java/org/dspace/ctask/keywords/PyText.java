/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * String helpers with Python 3 semantics. The keyword suggestion pipeline was developed and evaluated in Python;
 * these keep the Java port byte-for-byte equivalent where Java's defaults differ (code-point lengths, Unicode
 * whitespace splitting, {@code str.isupper()}, Python {@code re} character classes).
 */
public final class PyText {

    /** Python {@code [^\W\d_]}: letters, plus numeric characters that are not decimal digits (e.g. superscripts). */
    public static final String LETTER = "[\\p{L}\\p{Nl}\\p{No}]";
    /** Python {@code \w}: letters, all numerics, underscore. */
    public static final String WORDCHAR = "[\\p{L}\\p{N}_]";
    /** Flags that make Java's \s and friends Unicode-aware, as Python's are. */
    public static final int UNI = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE;

    private static final Pattern WS = Pattern.compile("\\s+", UNI);

    private PyText() {
    }

    /** {@code len(s)}: number of code points. */
    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** {@code s.split()}: split on runs of Unicode whitespace, no empty strings. */
    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String w : WS.split(s)) {           // not String.strip(): Python also treats U+00A0 as whitespace
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /** {@code s.strip(chars)} for an explicit character set. */
    public static String strip(String s, String chars) {
        int a = 0;
        int b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    /** {@code s.rstrip(chars)}. */
    public static String rstrip(String s, String chars) {
        int b = s.length();
        while (b > 0 && chars.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(0, b);
    }

    /** {@code s.isupper()}: at least one cased character, and every cased character is upper case. */
    public static boolean isUpper(String s) {
        boolean cased = false;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isLowerCase(c) || Character.isTitleCase(c)) {
                return false;
            }
            if (Character.isUpperCase(c)) {
                cased = true;
            }
        }
        return cased;
    }

    /** {@code s.islower()}. */
    public static boolean isLower(String s) {
        boolean cased = false;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isUpperCase(c) || Character.isTitleCase(c)) {
                return false;
            }
            if (Character.isLowerCase(c)) {
                cased = true;
            }
        }
        return cased;
    }

    /** {@code s.isalpha()}. */
    public static boolean isAlpha(String s) {
        if (s.isEmpty()) {
            return false;
        }
        return s.codePoints().allMatch(Character::isLetter);
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    public static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /** {@code s[:1].upper() + s[1:]} (first code point). */
    public static String capitalizeFirst(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int n = Character.charCount(s.codePointAt(0));
        return upper(s.substring(0, n)) + s.substring(n);
    }

    /** First n code points ({@code s[:n]}). */
    public static String head(String s, int n) {
        if (len(s) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** Last code point as a string, or "" ({@code s[-1]}). */
    public static String lastChar(String s) {
        if (s.isEmpty()) {
            return "";
        }
        int c = s.codePointBefore(s.length());
        return new String(Character.toChars(c));
    }

    /** First code point as a string, or "" ({@code s[0]}). */
    public static String firstChar(String s) {
        if (s.isEmpty()) {
            return "";
        }
        return new String(Character.toChars(s.codePointAt(0)));
    }
}
