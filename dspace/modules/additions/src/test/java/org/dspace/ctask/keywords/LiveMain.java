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
import java.time.Duration;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Live run of the DSpace-independent pipeline against LM Studio, on pilot fixtures (pipeline.jsonl), by hand:
 * {@code LiveMain <pipeline.jsonl> <llm url> <id> [<id> ...]}. Prints the Java result next to the keywords the
 * pilot's Python run got for the same document.
 */
public final class LiveMain {
    private LiveMain() {
    }

    public static void main(String[] args) throws Exception {
        KeywordPipeline p = new KeywordPipeline(new LlmClient(args[1], "qwen3-4b-instruct-2507",
            Duration.ofSeconds(180)), KeywordPipeline.DEFAULT_BUDGET, 6);
        Set<String> ids = Set.of(java.util.Arrays.copyOfRange(args, 2, args.length));
        ObjectMapper om = new ObjectMapper();
        try (BufferedReader r = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonNode j = om.readTree(line);
                if (!ids.contains(j.get("id").asText())) {
                    continue;
                }
                long t0 = System.nanoTime();
                TextCleaner.Result c = p.clean(j.get("raw_text").asText(), j.get("title").asText(),
                    j.get("pdf").asBoolean());
                KeywordPipeline.Outcome o = p.suggest(c, "hu", j.get("literary").asBoolean());
                System.out.printf("%n%s  ok=%s  %.1f s  prompt %d tokens, excerpt %d tokens, whole=%s%n",
                    j.get("id").asText(), o.ok(), (System.nanoTime() - t0) / 1e9, o.answer().promptTokens(),
                    o.excerpt().tokens(), o.excerpt().whole());
                for (KeywordChecks.Checked k : o.keywords()) {
                    System.out.printf("  java   %s %s%n", k.keyword(), k.flags());
                }
                for (JsonNode ch : j.get("checks")) {
                    if (ch.get("system").asText().equals(o.excerpt().whole() ? "F" : "F-baseline")) {
                        ch.get("expected").forEach(k -> System.out.printf("  python %s %s%n",
                            k.get("keyword").asText(), k.get("flags")));
                    }
                }
            }
        }
    }
}
