/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tartarus.snowball.ext.HungarianStemmer;

/**
 * Paragraphs, headings, sentences and Snowball stems on plain text. Port of the pilot's {@code phase2/textutil.py};
 * behaviour must stay identical (checked by the parity tests).
 */
public final class TextUtil {

    /** Python {@code [^\W\d_]+(?:-[^\W\d_]+)*}. */
    public static final Pattern WORD =
        Pattern.compile(PyText.LETTER + "+(?:-" + PyText.LETTER + "+)*", PyText.UNI);

    public static final Set<String> ABBREV = Set.of("p", "pp", "pl", "ld", "uo", "vö", "bp", "stb", "ill", "ún", "kb",
        "ff", "sz", "old", "ford", "szerk", "kiad", "évf", "ny", "dr", "id", "ifj", "özv", "gr", "br", "szt", "kr", "e",
        "u", "i", "ii", "vol", "no", "ed", "eds", "et", "al", "cf", "vs", "jan", "febr", "márc", "ápr", "jún", "júl",
        "aug", "szept", "okt", "nov", "dec", "fig", "tab", "ábr", "tbl");

    private static final Pattern PARA_SPLIT = Pattern.compile("\\n\\s*\\n", PyText.UNI);
    private static final Pattern NEWLINES = Pattern.compile("\\s*\\n\\s*", PyText.UNI);
    private static final Pattern LETTERS4 = Pattern.compile(PyText.LETTER + "{4,}", PyText.UNI);
    private static final Pattern CAPTION =
        Pattern.compile("^\\d*\\.?\\s*(ábra|táblázat|kép|tábla|melléklet)\\b", PyText.UNI | Pattern.CASE_INSENSITIVE);
    /** Sentence boundary: after . ! ? …, optional closing quote/bracket, whitespace, then an upper-case start. */
    private static final Pattern SPLIT = Pattern.compile(
        "(?<=[.!?…])[\"”»)]?\\s+(?=[„\"«(\\[–—-]?\\s?[A-ZÁÉÍÓÖŐÚÜŰ])", PyText.UNI);

    private static final ThreadLocal<HungarianStemmer> STEMMER = ThreadLocal.withInitial(HungarianStemmer::new);

    private TextUtil() {
    }

    /** Non-empty paragraphs, split on blank lines (Python {@code re.split(r"\n\s*\n")}). */
    public static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String p : PARA_SPLIT.split(text, -1)) {
            if (!pyStrip(p).isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** A heading in plain text: one short line, no sentence punctuation at the end, followed by a longer paragraph. */
    public static boolean isHeading(String par, String next) {
        String p = pyStrip(par);
        if (p.contains("\n") || PyText.len(p) > 90 || PyText.split(p).size() > 12 || PyText.len(p) < 2) {
            return false;
        }
        if (".,;:!?…»\"”'".contains(PyText.lastChar(p)) || "–-—„\"»".contains(PyText.firstChar(p))) {
            return false;
        }
        if (!LETTERS4.matcher(p).find() || CAPTION.matcher(p).find()) {
            return false;
        }
        return next != null && PyText.len(next) > PyText.len(p);
    }

    /** Paragraphs, with long ones (over 225 words) cut into groups of sentences of about 150 words. */
    public static List<Block> blocks(String text) {
        int maxWords = 150;
        List<Block> out = new ArrayList<>();
        List<String> paras = paragraphs(text);
        for (int i = 0; i < paras.size(); i++) {
            String p = paras.get(i);
            if (PyText.split(p).size() <= maxWords * 1.5) {
                out.add(new Block(i, p));
                continue;
            }
            List<String> cur = new ArrayList<>();
            for (String s : sentences(p)) {
                cur.add(s);
                if (PyText.split(String.join(" ", cur)).size() >= maxWords) {
                    out.add(new Block(i, String.join(" ", cur)));
                    cur = new ArrayList<>();
                }
            }
            if (!cur.isEmpty()) {
                out.add(new Block(i, String.join(" ", cur)));
            }
        }
        return out;
    }

    /** Regex sentence splitter with an abbreviation guard. */
    public static List<String> sentences(String par) {
        String text = pyStrip(NEWLINES.matcher(par).replaceAll(" "));
        List<String> out = new ArrayList<>();
        int start = 0;
        Matcher m = SPLIT.matcher(text);
        while (m.find()) {
            List<String> before = PyText.split(PyText.rstrip(text.substring(start, m.start()), ".!?…\"”»)"));
            String last = before.isEmpty() ? "" : PyText.lower(before.get(before.size() - 1));
            if (ABBREV.contains(last) || (PyText.len(last) == 1 && PyText.isAlpha(last))) {
                continue;
            }
            out.add(pyStrip(text.substring(start, m.start())));
            start = m.end();
        }
        String rest = pyStrip(text.substring(start));
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }

    /**
     * Snowball Hungarian stems of the words (lower-cased). Lucene 8.11's generated HungarianStemmer comes from the old
     * Latin-1 Snowball script, which writes ő and ű as õ and û (its suffix table has "bõl", not "ből"), so the word is
     * mapped to that spelling before stemming and back afterwards. This matches the current Snowball (PyStemmer)
     * used in the pilot; the parity test checks it on the pilot's vocabulary.
     */
    public static List<String> stems(String text) {
        HungarianStemmer st = STEMMER.get();
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            st.setCurrent(PyText.lower(m.group()).replace('ő', 'õ').replace('ű', 'û'));
            st.stem();
            out.add(st.getCurrent().replace('õ', 'ő').replace('û', 'ű'));
        }
        return out;
    }

    /** Python {@code s.strip()}: Unicode whitespace on both ends. */
    public static String pyStrip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isPySpace(s.charAt(a))) {
            a++;
        }
        while (b > a && isPySpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    static boolean isPySpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u001c' || c == '\u001d'
            || c == '\u001e' || c == '\u001f' || c == '\u0085';
    }

    /** A paragraph-level unit of text; {@code para} is its paragraph (or block) number, -1 for the contents list. */
    public record Block(int para, String text) {
    }
}
