/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Token counter for Qwen3 (byte-level BPE), read from the model's {@code tokenizer.json}. Only counts tokens: the
 * excerpt is cut to a token budget, and the budget was tested with Qwen's own tokenizer, so the count must be exact
 * (checked against the Python {@code tokenizers} library on the pilot documents).
 *
 * <p>Steps, as in {@code tokenizer.json}: added (special) tokens split off first; NFC normalisation; the
 * pre-tokeniser regex ("Isolated" split); GPT-2 byte-to-unicode mapping; BPE merges by rank.
 */
public class QwenTokenizer {

    /** The Qwen2/3 pre-tokeniser pattern from tokenizer.json, with Unicode-aware \s as in the original (Oniguruma). */
    private static final String PRE = "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}"
        + "| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+";

    private final Pattern pre = Pattern.compile(PRE, Pattern.UNICODE_CHARACTER_CLASS);
    private final Map<String, Integer> ranks = new HashMap<>();
    private final List<String> added = new ArrayList<>();
    private final char[] byteChar = new char[256];
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    public QwenTokenizer(InputStream tokenizerJson) throws IOException {
        JsonNode root = new ObjectMapper().readTree(tokenizerJson);
        for (JsonNode m : root.get("model").get("merges")) {
            String pair = m.isArray() ? m.get(0).asText() + " " + m.get(1).asText() : m.asText();
            ranks.putIfAbsent(pair, ranks.size());
        }
        for (JsonNode a : root.path("added_tokens")) {
            added.add(a.get("content").asText());
        }
        added.sort((x, y) -> y.length() - x.length());
        // GPT-2 bytes_to_unicode
        List<Integer> bs = new ArrayList<>();
        for (int b = '!'; b <= '~'; b++) {
            bs.add(b);
        }
        for (int b = 0xA1; b <= 0xAC; b++) {
            bs.add(b);
        }
        for (int b = 0xAE; b <= 0xFF; b++) {
            bs.add(b);
        }
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (bs.contains(b)) {
                byteChar[b] = (char) b;
            } else {
                byteChar[b] = (char) (256 + n++);
            }
        }
    }

    /** Loads the tokenizer from a classpath resource; a ".gz" name is decompressed. */
    public static QwenTokenizer fromResource(String name) throws IOException {
        InputStream in = QwenTokenizer.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("resource not found: " + name);
        }
        try (InputStream s = name.endsWith(".gz") ? new GZIPInputStream(in) : in) {
            return new QwenTokenizer(s);
        }
    }

    /** Number of tokens of the text (no special tokens added). */
    public int count(String text) {
        int total = 0;
        int start = 0;
        while (start < text.length()) {
            int best = -1;
            String bestTok = null;
            for (String a : added) {
                int i = text.indexOf(a, start);
                if (i >= 0 && (best < 0 || i < best)) {
                    best = i;
                    bestTok = a;
                }
            }
            if (best < 0) {
                total += countPlain(text.substring(start));
                break;
            }
            total += countPlain(text.substring(start, best)) + 1;
            start = best + bestTok.length();
        }
        return total;
    }

    private int countPlain(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC);
        Matcher m = pre.matcher(norm);
        int total = 0;
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {       // "Isolated" split keeps unmatched text as its own piece
                total += piece(norm.substring(last, m.start()));
            }
            total += piece(m.group());
            last = m.end();
        }
        if (last < norm.length()) {
            total += piece(norm.substring(last));
        }
        return total;
    }

    private int piece(String p) {
        if (p.isEmpty()) {
            return 0;
        }
        Integer c = cache.get(p);
        if (c == null) {
            c = bpe(p);
            if (cache.size() < 500_000) {
                cache.put(p, c);
            }
        }
        return c;
    }

    private int bpe(String p) {
        byte[] bytes = p.getBytes(StandardCharsets.UTF_8);
        List<String> sym = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
            sym.add(String.valueOf(byteChar[b & 0xFF]));
        }
        while (sym.size() > 1) {
            int bestRank = Integer.MAX_VALUE;
            int bestIdx = -1;
            for (int i = 0; i < sym.size() - 1; i++) {
                Integer r = ranks.get(sym.get(i) + " " + sym.get(i + 1));
                if (r != null && r < bestRank) {
                    bestRank = r;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            // merge every occurrence of the best pair, left to right
            String a = sym.get(bestIdx);
            String b = sym.get(bestIdx + 1);
            List<String> next = new ArrayList<>(sym.size());
            for (int i = 0; i < sym.size(); ) {
                if (i < sym.size() - 1 && sym.get(i).equals(a) && sym.get(i + 1).equals(b)) {
                    next.add(a + b);
                    i += 2;
                } else {
                    next.add(sym.get(i));
                    i++;
                }
            }
            sym = next;
        }
        return sym.size();
    }
}
