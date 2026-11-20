/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative clean-up of extracted text. Port of the pilot's {@code phase0/cleanup.py} ("cleanup-v1"), step order
 * unchanged: 1990s accent fix, soft hyphens and ligatures, PDF running headers/footers (needs form feeds between
 * pages), line-break hyphens (Hunspell decides), contents lines, the PIM DIA imprint line, reference lists (PDF),
 * reflow (PDF), repeated paragraphs. Every change is counted in {@link Result#log()}.
 */
public class TextCleaner {

    public static final String VERSION = "cleanup-v1";

    private static final Pattern OLD_ACCENTS = Pattern.compile("[őűŐŰ]");
    private static final Pattern REF_HEADING = Pattern.compile(
        "^\\s*(\\d{1,2}\\.\\s*)?(Irodalom|Felhasznált irodalom|Irodalomjegyzék|Hivatkozások|Források|References"
            + "|Bibliography|Literature)\\s*$", PyText.UNI | Pattern.CASE_INSENSITIVE);
    private static final Pattern TOC_LINE = Pattern.compile("\\.{5,}\\s*\\d{1,4}\\s*$|\\s\\.\\s\\.\\s\\.\\s.*\\d{1,4}\\s*$",
        PyText.UNI);
    private static final Pattern TOC_HEADING = Pattern.compile(
        "\\s*(Tartalom|Tartalomjegyzék|Tartalomjegyzék / Contents|Contents)\\s*", PyText.UNI | Pattern.CASE_INSENSITIVE);
    private static final Pattern DIA_IMPRINT =
        Pattern.compile("^Digitális Irodalmi Akadémia © Petőfi Irodalmi Múzeum.*$", Pattern.MULTILINE);
    private static final Pattern YEAR = Pattern.compile("\\b(1[6-9]|20)\\d{2}\\b", PyText.UNI);
    private static final Pattern BRACKET_REF = Pattern.compile("\\[\\d+\\]", PyText.UNI);
    private static final Pattern DIGITS = Pattern.compile("\\d+", PyText.UNI);
    private static final Pattern PAGE_NUMBER = Pattern.compile("\\d{1,4}", PyText.UNI);
    private static final Pattern HYPHEN = Pattern.compile("(" + PyText.WORDCHAR + "+)-\\n[ \\t]*(" + PyText.WORDCHAR
        + "+)", PyText.UNI);
    private static final Pattern PARA_SPLIT = Pattern.compile("\\n\\s*\\n", PyText.UNI);
    private static final Pattern REFLOW = Pattern.compile("[ \\t]*\\n[ \\t]*");
    private static final Pattern LETTER = Pattern.compile(PyText.LETTER, PyText.UNI);
    private static final Map<String, String> LIGATURES = new LinkedHashMap<>();

    static {
        LIGATURES.put("ﬁ", "fi");
        LIGATURES.put("ﬂ", "fl");
        LIGATURES.put("ﬀ", "ff");
        LIGATURES.put("ﬃ", "ffi");
        LIGATURES.put("ﬄ", "ffl");
    }

    private final Speller speller;

    public TextCleaner(Speller speller) {
        this.speller = speller;
    }

    /**
     * @param raw   extracted text; PDF text may contain form feeds between pages
     * @param title the item's title (gets the same accent fix)
     * @param pdf   the source is a PDF: running headers, reference lists and reflow apply
     * @param dia   the PIM DIA imprint line is removed (harmless elsewhere: it only matches that line)
     */
    public Result clean(String raw, String title, boolean pdf, boolean dia) {
        Map<String, Integer> log = new HashMap<>();
        String text = fixEncoding(raw, log);
        text = fixCharacters(text, log);
        if (pdf) {
            text = stripPdfHeaders(text, log);
        }
        text = joinLineHyphens(text, log);
        text = dropToc(text, log);
        if (dia) {
            text = dropDiaImprint(text, log);
        }
        if (pdf) {
            text = dropReferences(text, log);
            text = reflow(text);
        }
        List<String> paras = new ArrayList<>();
        for (String p : PARA_SPLIT.split(text, -1)) {
            String s = TextUtil.pyStrip(p);
            if (!s.isEmpty()) {
                paras.add(s);
            }
        }
        paras = dropRepeated(paras, log);
        String cleanTitle = title == null ? "" : title;
        if (log.containsKey("encoding_fixed_chars")) {
            cleanTitle = Normalizer.normalize(translateAccents(cleanTitle), Normalizer.Form.NFC);
        }
        return new Result(String.join("\n\n", paras), cleanTitle, log);
    }

    static String translateAccents(String s) {
        return s.replace('õ', 'ő').replace('û', 'ű').replace('Õ', 'Ő').replace('Û', 'Ű');
    }

    private String fixEncoding(String text, Map<String, Integer> log) {
        boolean has = text.indexOf('õ') >= 0 || text.indexOf('û') >= 0 || text.indexOf('Õ') >= 0
            || text.indexOf('Û') >= 0;
        if (has && !OLD_ACCENTS.matcher(text).find()) {
            int n = 0;
            for (char c : text.toCharArray()) {
                if ("õûÕÛ".indexOf(c) >= 0) {
                    n++;
                }
            }
            log.put("encoding_fixed_chars", n);
            return translateAccents(text);
        }
        return text;
    }

    private String fixCharacters(String text, Map<String, Integer> log) {
        if (text.indexOf('­') >= 0) {
            log.put("soft_hyphens", count(text, "­"));
            text = text.replace("­", "");
        }
        int lig = 0;
        for (Map.Entry<String, String> e : LIGATURES.entrySet()) {
            if (text.contains(e.getKey())) {
                lig += count(text, e.getKey());
                text = text.replace(e.getKey(), e.getValue());
            }
        }
        if (lig > 0) {
            log.put("ligatures", lig);
        }
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }

    private static String norm(String line) {
        return DIGITS.matcher(TextUtil.pyStrip(line)).replaceAll("#");
    }

    private String stripPdfHeaders(String text, Map<String, Integer> log) {
        String[] pages = text.split("\f", -1);
        if (pages.length < 3) {
            return text.replace("\f", "\n");
        }
        Map<String, Integer> edge = new HashMap<>();
        for (String p : pages) {
            List<String> lines = new ArrayList<>();
            for (String l : p.split("\n", -1)) {
                if (!TextUtil.pyStrip(l).isEmpty()) {
                    lines.add(l);
                }
            }
            List<String> edgeLines = new ArrayList<>(lines.subList(0, Math.min(2, lines.size())));
            edgeLines.addAll(lines.subList(Math.max(0, lines.size() - 2), lines.size()));
            for (String l : edgeLines) {
                edge.merge(norm(l), 1, Integer::sum);
            }
        }
        double threshold = Math.max(3, pages.length * 0.3);
        Set<String> repeated = new HashSet<>();
        for (Map.Entry<String, Integer> e : edge.entrySet()) {
            if (e.getValue() >= threshold && PyText.len(e.getKey()) < 120) {
                repeated.add(e.getKey());
            }
        }
        int removed = 0;
        List<String> outPages = new ArrayList<>();
        for (String p : pages) {
            String[] lines = p.split("\n", -1);
            List<Integer> nonempty = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (!TextUtil.pyStrip(lines[i]).isEmpty()) {
                    nonempty.add(i);
                }
            }
            Set<Integer> edges = new HashSet<>(nonempty.subList(0, Math.min(2, nonempty.size())));
            edges.addAll(nonempty.subList(Math.max(0, nonempty.size() - 2), nonempty.size()));
            List<String> keep = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                String s = TextUtil.pyStrip(lines[i]);
                if (edges.contains(i) && (repeated.contains(norm(lines[i])) || PAGE_NUMBER.matcher(s).matches())) {
                    removed++;
                    continue;
                }
                keep.add(lines[i]);
            }
            outPages.add(String.join("\n", keep));
        }
        log.put("pdf_header_footer_lines", removed);
        return String.join("\n", outPages);
    }

    private String joinLineHyphens(String text, Map<String, Integer> log) {
        Matcher m = HYPHEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int joined = 0;
        int kept = 0;
        while (m.find()) {
            String left = m.group(1);
            String right = m.group(2);
            String rep;
            if (speller.known(left + right) && !speller.known(left + "-" + right)) {
                rep = left + right;
                joined++;
            } else {
                rep = left + "-" + right;
                kept++;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        if (joined + kept > 0) {
            log.put("line_hyphens_joined", joined);
            log.put("line_hyphens_kept", kept);
        }
        return sb.toString();
    }

    private String dropToc(String text, Map<String, Integer> log) {
        List<String> keep = new ArrayList<>();
        int removed = 0;
        for (String l : text.split("\n", -1)) {
            if (TOC_LINE.matcher(l).find() || TOC_HEADING.matcher(l).matches()) {
                removed++;
                continue;
            }
            keep.add(l);
        }
        if (removed > 0) {
            log.put("toc_lines", removed);
        }
        return String.join("\n", keep);
    }

    private String dropDiaImprint(String text, Map<String, Integer> log) {
        Matcher m = DIA_IMPRINT.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        if (n > 0) {
            log.put("dia_imprint_lines", n);
        }
        return DIA_IMPRINT.matcher(text).replaceAll("");
    }

    private static boolean isProse(String line) {
        String s = TextUtil.pyStrip(line);
        return PyText.len(s) >= 60 && !YEAR.matcher(s).find() && !BRACKET_REF.matcher(s).lookingAt();
    }

    /** From a line that is only a reference heading to the end, or to the first run of four prose lines. */
    private String dropReferences(String text, Map<String, Integer> log) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        int lists = 0;
        int removedChars = 0;
        int i = 0;
        while (i < lines.length) {
            if (REF_HEADING.matcher(lines[i]).lookingAt() && REF_HEADING.matcher(lines[i]).matches()) {
                int j = i + 1;
                int proseRun = 0;
                int lastRef = i;
                while (j < lines.length) {
                    if (TextUtil.pyStrip(lines[j]).isEmpty()) {
                        j++;
                        continue;
                    }
                    if (isProse(lines[j])) {
                        proseRun++;
                        if (proseRun >= 4) {
                            break;
                        }
                    } else {
                        proseRun = 0;
                        lastRef = j;
                    }
                    j++;
                }
                int nonEmpty = 0;
                for (int b = i; b <= lastRef; b++) {
                    if (!TextUtil.pyStrip(lines[b]).isEmpty()) {
                        nonEmpty++;
                    }
                }
                if (nonEmpty >= 3) {
                    lists++;
                    for (int b = i; b <= lastRef; b++) {
                        removedChars += PyText.len(lines[b]) + 1;
                    }
                    i = lastRef + 1;
                    continue;
                }
            }
            out.add(lines[i]);
            i++;
        }
        if (lists > 0) {
            log.put("reference_lists", lists);
            log.put("reference_chars", removedChars);
        }
        return String.join("\n", out);
    }

    private static String reflow(String text) {
        List<String> out = new ArrayList<>();
        for (String p : PARA_SPLIT.split(text, -1)) {
            if (!TextUtil.pyStrip(p).isEmpty()) {
                out.add(TextUtil.pyStrip(REFLOW.matcher(p).replaceAll(" ")));
            }
        }
        return String.join("\n\n", out);
    }

    private static List<String> dropRepeated(List<String> paras, Map<String, Integer> log) {
        List<String> out = new ArrayList<>();
        for (String p : paras) {
            if (!out.isEmpty() && p.equals(out.get(out.size() - 1)) && letters(p) >= 2) {
                log.merge("repeated_paragraphs", 1, Integer::sum);
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static int letters(String s) {
        Matcher m = LETTER.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** The cleaned text (paragraphs separated by a blank line), the cleaned title and what was changed. */
    public record Result(String text, String title, Map<String, Integer> log) {
    }
}
