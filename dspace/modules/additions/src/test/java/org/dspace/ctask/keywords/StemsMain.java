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
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parity of the Snowball Hungarian stemmer against PyStemmer: stems.jsonl {"w": word, "s": python stem}. */
public final class StemsMain {
    private StemsMain() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper om = new ObjectMapper();
        int n = 0;
        int bad = 0;
        try (BufferedReader r = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonNode j = om.readTree(line);
                List<String> got = TextUtil.stems(j.get("w").asText());
                String g = got.isEmpty() ? "" : got.get(0);
                n++;
                if (!g.equals(j.get("s").asText())) {
                    bad++;
                    if (bad <= 25) {
                        System.out.printf("%s python=%s java=%s%n", j.get("w").asText(), j.get("s").asText(), g);
                    }
                }
            }
        }
        System.out.printf("stems: %d words, %d differ (%.2f %%)%n", n, bad, 100.0 * bad / n);
    }
}