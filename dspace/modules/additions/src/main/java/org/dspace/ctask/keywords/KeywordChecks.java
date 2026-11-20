/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks on the model's keywords. Port of the pilot's {@code phase2/checks.py} ("checks-v1") without the "unknown
 * word" flag (it did not predict bad keywords), plus the genre/form filter of {@code phase3/ensemble.py} for
 * literary works. Duplicates (same Snowball stems), the cap and the form filter remove keywords; casing is repaired;
 * everything else is a flag for the cataloguer.
 *
 * <p>Flags: {@code not_in_text} (a stem of the keyword does not occur in the full text), {@code evidence_not_found} /
 * {@code evidence_loose} (the model's quote is not in the text it was given, verbatim / with 80 % of its words),
 * {@code capitalised_common}, {@code case_fixed}, {@code case_restored}.
 */
public class KeywordChecks {

    public static final String VERSION = "checks-v1";

    /** Genre and form words: what kind of work it is, not what it is about (phase3/ensemble.py FORM_WORDS). */
    public static final List<String> FORM_WORDS = List.of("regény", "kisregény", "regényes", "novella", "elbeszélés",
        "elbeszélések", "novellák", "vers", "versek", "verseskötet", "költemény", "költemények", "dráma", "színmű",
        "színdarab", "tragédia", "komédia", "vígjáték", "forgatókönyv", "mese", "mesék", "meseregény", "napló",
        "esszé", "esszék", "memoár", "visszaemlékezés", "önéletrajz", "útirajz", "levelek", "gyermekirodalom",
        "ifjúsági regény", "életrajzi regény", "történelmi regény", "kisprózák", "próza", "líra",
        "elbeszélő költemény", "szonett", "ballada", "eposz");

    private static final Pattern WS = Pattern.compile("\\s+", PyText.UNI);
    private static final Pattern NON_WORD = Pattern.compile("[^\\w\\s-]", PyText.UNI);
    private static final Pattern LETTERS = Pattern.compile(PyText.LETTER + "+", PyText.UNI);

    private final Speller speller;
    private final Set<List<String>> formStems = new HashSet<>();

    public KeywordChecks(Speller speller) {
        this.speller = speller;
        for (String w : FORM_WORDS) {
            formStems.add(TextUtil.stems(w));
        }
    }

    /**
     * @param keywords  the model's keywords in its order (keyword + evidence quote)
     * @param fullText  the whole cleaned text
     * @param givenText the text the model was given (excerpt or whole)
     * @param literary  apply the genre/form filter (literary works that are processed, i.e. not fiction)
     * @param max       at most this many keywords
     */
    public List<Checked> check(List<Raw> keywords, String fullText, String givenText, boolean literary, int max) {
        Set<String> fullStems = new HashSet<>(TextUtil.stems(fullText));
        Map<String, Integer> cases = caseCounts(fullText);
        List<Checked> out = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (Raw k : keywords) {
            String kw = TextUtil.pyStrip(WS.matcher(PyText.strip(k.keyword(), " .,;:\"'„”«»")).replaceAll(" "));
            if (kw.isEmpty()) {
                continue;
            }
            List<String> flags = new ArrayList<>();
            // copied headings in capitals (longer than 4 letters; acronyms such as WHO stay)
            List<String> words = PyText.split(kw);
            if (words.stream().anyMatch(w -> PyText.isUpper(w) && PyText.len(w) > 4)) {
                List<String> fixed = new ArrayList<>();
                for (String w : words) {
                    fixed.add(PyText.isUpper(w) && PyText.len(w) > 4 ? PyText.lower(w) : w);
                }
                kw = String.join(" ", fixed);
                flags.add("case_fixed");
            }
            // names the model wrote in lower case
            words = PyText.split(kw);
            List<String> restored = new ArrayList<>();
            for (String w : words) {
                List<String> parts = new ArrayList<>();
                for (String p : w.split("-", -1)) {
                    String cap = PyText.capitalizeFirst(p);
                    boolean restore = PyText.isLower(p) && PyText.len(p) >= 3 && !speller.known(p)
                        && prefixCount(cases, cap) > 2 * prefixCount(cases, p);
                    parts.add(restore ? cap : p);
                }
                restored.add(String.join("-", parts));
            }
            if (!String.join(" ", restored).equals(kw)) {
                kw = String.join(" ", restored);
                flags.add("case_restored");
            }
            List<String> key = TextUtil.stems(kw);
            if (key.isEmpty() || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            if (!fullStems.containsAll(key)) {
                flags.add("not_in_text");
            }
            if (givenText != null && k.evidence() != null) {
                String st = evidenceStatus(k.evidence(), givenText);
                if (st != null) {
                    flags.add(st);
                }
            }
            String first = PyText.split(kw).get(0);
            if (PyText.isUpper(PyText.firstChar(first)) && PyText.len(first) > 1) {
                int low = cases.getOrDefault(PyText.lower(first), 0);
                int cap = cases.getOrDefault(first, 0);
                if (low > cap) {
                    flags.add("capitalised_common");
                }
            }
            out.add(new Checked(kw, k.evidence(), flags));
            if (out.size() == max) {
                break;
            }
        }
        // genre/form words leave the list after the cap, as in the evaluated pipeline (phase3 form_filtered)
        if (literary) {
            out.removeIf(c -> formStems.contains(TextUtil.stems(c.keyword())));
        }
        return out;
    }

    static String normQuote(String s) {
        return TextUtil.pyStrip(WS.matcher(NON_WORD.matcher(PyText.lower(s)).replaceAll(" ")).replaceAll(" "));
    }

    /** null if the quote is in the text, otherwise evidence_loose or evidence_not_found. */
    static String evidenceStatus(String quote, String given) {
        String q = normQuote(quote);
        String t = normQuote(given);
        if (q.isEmpty()) {
            return "evidence_not_found";
        }
        if (t.contains(q)) {
            return null;
        }
        List<String> words = PyText.split(q);
        Set<String> tws = new HashSet<>(PyText.split(t));
        long hits = words.stream().filter(tws::contains).count();
        return hits >= 0.8 * words.size() ? "evidence_loose" : "evidence_not_found";
    }

    static Map<String, Integer> caseCounts(String text) {
        Map<String, Integer> c = new HashMap<>();
        Matcher m = LETTERS.matcher(text);
        while (m.find()) {
            c.merge(m.group(), 1, Integer::sum);
        }
        return c;
    }

    private static int prefixCount(Map<String, Integer> counts, String word) {
        int n = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey().startsWith(word)) {
                n += e.getValue();
            }
        }
        return n;
    }

    /** A keyword as the model returned it. */
    public record Raw(String keyword, String evidence) {
    }

    /** A keyword after the checks, with its flags. */
    public record Checked(String keyword, String evidence, List<String> flags) {
    }
}
