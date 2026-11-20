/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parity check of the Java port against the Python reference, on fixtures exported by the pilot
 * ({@code docs/llm-pilot/integration/export_parity.py} in the installer repository). The fixtures contain document
 * text and are not committed; this is run by hand, not by the build:
 *
 * <pre>java ... org.dspace.ctask.keywords.ParityMain tokens  &lt;dir with tokens.jsonl&gt; &lt;tokenizer.json&gt;</pre>
 */
public final class ParityMain {

    private ParityMain() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper om = new ObjectMapper();
        Path dir = Path.of(args[1]);
        if ("tokens".equals(args[0])) {
            QwenTokenizer tok;
            try (var in = Files.newInputStream(Path.of(args[2]))) {
                tok = new QwenTokenizer(in);
            }
            int n = 0;
            int bad = 0;
            long t0 = System.nanoTime();
            try (BufferedReader r = Files.newBufferedReader(dir.resolve("tokens.jsonl"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    JsonNode j = om.readTree(line);
                    String text = j.get("text").asText();
                    int want = j.get("tokens").asInt();
                    int got = tok.count(text);
                    n++;
                    if (got != want) {
                        bad++;
                        if (bad <= 10) {
                            System.out.printf("MISMATCH python=%d java=%d len=%d: %s%n", want, got, text.length(),
                                text.length() > 160 ? text.substring(0, 160) + "…" : text);
                        }
                    }
                }
            }
            System.out.printf("tokens: %d texts, %d mismatches, %.1f s%n", n, bad, (System.nanoTime() - t0) / 1e9);
        } else if ("pipeline".equals(args[0])) {
            // args: pipeline <fixture dir> <tokenizer.json> <background_v2.json> <hu_HU.aff> <hu_HU.dic>
            QwenTokenizer tok;
            BackgroundIdf idf;
            Speller sp;
            try (var a = Files.newInputStream(Path.of(args[2])); var b = Files.newInputStream(Path.of(args[3]));
                 var c = Files.newInputStream(Path.of(args[4])); var d = Files.newInputStream(Path.of(args[5]))) {
                tok = new QwenTokenizer(a);
                idf = new BackgroundIdf(b);
                sp = new Speller(c, d);
            }
            TextCleaner cleaner = new TextCleaner(sp);
            BaselineExcerpt excerpt = new BaselineExcerpt(tok, idf);
            KeywordChecks checks = new KeywordChecks(sp);
            int docs = 0;
            int cleanOk = 0;
            int titleOk = 0;
            int excerptOk = 0;
            int lists = 0;
            int listsOk = 0;
            long t0 = System.nanoTime();
            try (BufferedReader r = Files.newBufferedReader(dir.resolve("pipeline.jsonl"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    JsonNode j = om.readTree(line);
                    String id = j.get("id").asText();
                    docs++;
                    TextCleaner.Result cr = cleaner.clean(j.get("raw_text").asText(), j.get("title").asText(),
                        j.get("pdf").asBoolean(), j.get("dia").asBoolean());
                    String wantClean = j.get("clean_text").asText();
                    if (cr.text().equals(wantClean)) {
                        cleanOk++;
                    } else {
                        System.out.printf("CLEAN %s: %s%n", id, firstDiff(wantClean, cr.text()));
                    }
                    if (cr.title().equals(j.get("clean_title").asText())) {
                        titleOk++;
                    } else {
                        System.out.printf("TITLE %s: python=%s java=%s%n", id, j.get("clean_title").asText(),
                            cr.title());
                    }
                    // the excerpt from the expected clean text, so a clean-up difference does not hide in it
                    BaselineExcerpt.Result er = excerpt.select(wantClean, 2800);
                    JsonNode we = j.get("excerpt");
                    if (er.text().equals(we.get("text").asText()) && er.whole() == we.get("whole").asBoolean()
                        && er.tokens() == we.get("tokens").asInt()) {
                        excerptOk++;
                    } else {
                        System.out.printf("EXCERPT %s: whole %s/%s tokens %d/%d %s%n", id, we.get("whole").asBoolean(),
                            er.whole(), we.get("tokens").asInt(), er.tokens(),
                            firstDiff(we.get("text").asText(), er.text()));
                    }
                    for (JsonNode c : j.get("checks")) {
                        lists++;
                        List<KeywordChecks.Raw> raw = new ArrayList<>();
                        for (JsonNode k : c.get("raw")) {
                            raw.add(new KeywordChecks.Raw(k.get("keyword").asText(),
                                k.has("evidence") ? k.get("evidence").asText() : null));
                        }
                        List<KeywordChecks.Checked> got = checks.check(raw, wantClean, c.get("given").asText(),
                            j.get("literary").asBoolean(), 6);
                        List<String> gotS = new ArrayList<>();
                        for (KeywordChecks.Checked k : got) {
                            gotS.add(k.keyword() + " " + k.flags());
                        }
                        List<String> wantS = new ArrayList<>();
                        for (JsonNode k : c.get("expected")) {
                            List<String> fl = new ArrayList<>();
                            k.get("flags").forEach(x -> fl.add(x.asText()));
                            wantS.add(k.get("keyword").asText() + " " + fl);
                        }
                        if (gotS.equals(wantS)) {
                            listsOk++;
                        } else {
                            System.out.printf("CHECKS %s %s:%n  python %s%n  java   %s%n", id,
                                c.get("system").asText(), wantS, gotS);
                        }
                    }
                }
            }
            System.out.printf("pipeline: %d documents; clean text %d/%d; title %d/%d; excerpt %d/%d; checked lists "
                + "%d/%d; %.1f s%n", docs, cleanOk, docs, titleOk, docs, excerptOk, docs, listsOk, lists,
                (System.nanoTime() - t0) / 1e9);
        }
    }

    private static String firstDiff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int s = Math.max(0, i - 40);
        return String.format("lengths %d/%d, first difference at %d: python=«%s» java=«%s»", a.length(), b.length(), i,
            a.substring(s, Math.min(a.length(), i + 40)).replace("\n", "⏎"),
            b.substring(s, Math.min(b.length(), i + 40)).replace("\n", "⏎"));
    }
}
