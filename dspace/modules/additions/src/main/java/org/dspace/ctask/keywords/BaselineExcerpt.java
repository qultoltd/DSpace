/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The baseline excerpt the keyword model reads when the whole text does not fit its budget: a contents list of the
 * headings, the opening of the text, the first sentences after headings, and the most term-rich paragraph of each of
 * eight parts of the rest. Port of {@code baseline()} in the pilot's {@code phase2/evidence.py} (version "baseline-v1"),
 * the excerpt that was evaluated. Token counts use Qwen's own tokenizer, so the excerpt is identical to the tested one.
 */
public class BaselineExcerpt {

    public static final String VERSION = "baseline-v1";
    public static final String SEP = "\n[…]\n";

    private final QwenTokenizer tok;
    private final BackgroundIdf idf;
    private final int sepTokens;

    public BaselineExcerpt(QwenTokenizer tok, BackgroundIdf idf) {
        this.tok = tok;
        this.idf = idf;
        this.sepTokens = tok.count(SEP);
    }

    /** The excerpt: whole text if it fits the budget, otherwise the selected parts in document order. */
    public Result select(String cleanText, int budget) {
        if (tok.count(cleanText) <= budget) {
            List<TextUtil.Block> parts = new ArrayList<>();
            List<String> paras = TextUtil.paragraphs(cleanText);
            for (int i = 0; i < paras.size(); i++) {
                parts.add(new TextUtil.Block(i, paras.get(i)));
            }
            return new Result(cleanText, parts, tok.count(cleanText), true);
        }
        List<String> paras = new ArrayList<>();
        for (TextUtil.Block b : TextUtil.blocks(cleanText)) {
            paras.add(b.text());                       // "para" below = block number
        }
        int n = paras.size();
        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (TextUtil.isHeading(paras.get(i), i + 1 < n ? paras.get(i + 1) : null)) {
                heads.add(i);
            }
        }
        Set<Integer> headSet = new HashSet<>(heads);
        int parMax = Math.min(350, budget / 6);
        List<TextUtil.Block> parts = new ArrayList<>();

        // 1. contents: up to 10 % of the budget, headings spread over the text
        int contentsBudget = budget / 10;
        if (!heads.isEmpty()) {
            List<String> uniq = new ArrayList<>(new LinkedHashSet<>(heads.stream().map(paras::get).toList()));
            int step = Math.max(1, uniq.size() / 25);
            List<String> lines = new ArrayList<>();
            int used = 0;
            for (int i = 0; i < uniq.size(); i += step) {
                String h = uniq.get(i);
                int t = tok.count(h) + 1;
                if (used + t > contentsBudget) {
                    break;
                }
                lines.add(h);
                used += t;
            }
            if (!lines.isEmpty()) {
                parts.add(new TextUtil.Block(-1, "Headings: " + String.join(" | ", lines)));
            }
        }
        // 2. opening: first body paragraphs up to 35 % of the budget
        int leadBudget = (int) (budget * 0.35);
        int used = 0;
        for (int i = 0; i < n; i++) {
            if (headSet.contains(i)) {
                continue;
            }
            String t = truncate(paras.get(i), parMax);
            int k = tok.count(t) + sepTokens;
            if (used + k > leadBudget) {
                break;
            }
            parts.add(new TextUtil.Block(i, t));
            used += k;
        }
        int leadEnd = !parts.isEmpty() && parts.get(parts.size() - 1).para() >= 0
            ? parts.get(parts.size() - 1).para() : 0;
        // 3. chapter openings: the first two sentences after each heading, spread, up to 20 %
        List<TextUtil.Block> openings = new ArrayList<>();
        for (int h : heads) {
            int j = h + 1;
            if (j < n && !headSet.contains(j) && j > leadEnd) {
                List<String> s = TextUtil.sentences(paras.get(j));
                openings.add(new TextUtil.Block(j, truncate(String.join(" ", s.subList(0, Math.min(2, s.size()))), 120)));
            }
        }
        if (!openings.isEmpty()) {
            int k = Math.max(1, (int) (budget * 0.20) / 70);
            double step = Math.max(1, (double) openings.size() / k);
            for (int x = 0; x < Math.min(k, openings.size()); x++) {
                parts.add(openings.get((int) (x * step)));
            }
        }
        // 4. term-rich paragraphs: the best paragraph of each of 8 equal parts after the opening
        Map<String, Double> w = tfidfWeights(paras);
        List<Integer> rest = new ArrayList<>();
        for (int i = leadEnd + 1; i < n; i++) {
            if (!headSet.contains(i) && PyText.len(paras.get(i)) > 200) {
                rest.add(i);
            }
        }
        if (!rest.isEmpty()) {
            for (List<Integer> chunk : arraySplit(rest, Math.min(8, rest.size()))) {
                int best = chunk.get(0);
                double bestScore = Double.NEGATIVE_INFINITY;
                for (int i : chunk) {
                    double sc = 0;
                    for (String s : new HashSet<>(TextUtil.stems(paras.get(i)))) {
                        sc += w.getOrDefault(s, 0.0);
                    }
                    sc /= Math.sqrt(Math.max(1, PyText.split(paras.get(i)).size()));
                    if (sc > bestScore) {                 // Python max(): the first of equal maxima wins
                        bestScore = sc;
                        best = i;
                    }
                }
                parts.add(new TextUtil.Block(best, truncate(paras.get(best), parMax)));
            }
        }
        return fit(parts, budget);
    }

    private Result fit(List<TextUtil.Block> parts, int budget) {
        List<TextUtil.Block> chosen = new ArrayList<>();
        int used = 0;
        for (TextUtil.Block p : parts) {
            int k = tok.count(p.text()) + sepTokens;
            if (used + k > budget) {
                continue;
            }
            chosen.add(p);
            used += k;
        }
        chosen.sort(Comparator.comparingInt(TextUtil.Block::para));   // stable, as Python's sort
        List<String> texts = chosen.stream().map(TextUtil.Block::text).toList();
        String text = String.join(SEP, texts);
        return new Result(text, chosen, tok.count(text), false);
    }

    /** The first sentences that fit maxTokens; at least one sentence, or the words cut down if needed. */
    String truncate(String text, int maxTokens) {
        if (tok.count(text) <= maxTokens) {
            return text;
        }
        List<String> out = new ArrayList<>();
        for (String s : TextUtil.sentences(text)) {
            List<String> tryOut = new ArrayList<>(out);
            tryOut.add(s);
            if (tok.count(String.join(" ", tryOut)) > maxTokens) {
                break;
            }
            out.add(s);
        }
        if (out.isEmpty()) {
            List<String> words = PyText.split(text);
            while (!words.isEmpty() && tok.count(String.join(" ", words)) > maxTokens) {
                words = words.subList(0, (int) (words.size() * 0.8));
            }
            return String.join(" ", words) + " …";
        }
        return String.join(" ", out);
    }

    private Map<String, Double> tfidfWeights(List<String> paras) {
        Map<String, Integer> tf = new HashMap<>();
        for (String p : paras) {
            for (String s : TextUtil.stems(p)) {
                tf.merge(s, 1, Integer::sum);
            }
        }
        Map<String, Double> w = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            if (PyText.len(e.getKey()) > 2) {
                w.put(e.getKey(), (1 + Math.log(e.getValue())) * idf.idf(e.getKey()));
            }
        }
        return w;
    }

    /** numpy.array_split: k chunks, the first (len % k) one element longer. */
    static List<List<Integer>> arraySplit(List<Integer> xs, int k) {
        List<List<Integer>> out = new ArrayList<>();
        int base = xs.size() / k;
        int extra = xs.size() % k;
        int i = 0;
        for (int c = 0; c < k; c++) {
            int size = base + (c < extra ? 1 : 0);
            out.add(xs.subList(i, i + size));
            i += size;
        }
        return out;
    }

    /** The excerpt, its parts (in document order) and its token count. */
    public record Result(String text, List<TextUtil.Block> parts, int tokens, boolean whole) {
    }
}
