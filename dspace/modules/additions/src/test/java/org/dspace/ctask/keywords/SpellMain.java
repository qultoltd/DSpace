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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Agreement of Lucene's Hunspell with pyenchant (the pilot's checker): spell.jsonl {"w": word, "known": bool}. */
public final class SpellMain {
    private SpellMain() {
    }

    public static void main(String[] args) throws Exception {
        Speller sp;
        try (var a = Files.newInputStream(Path.of(args[1])); var d = Files.newInputStream(Path.of(args[2]))) {
            sp = new Speller(a, d);
        }
        ObjectMapper om = new ObjectMapper();
        int n = 0;
        int onlyLucene = 0;
        int onlyEnchant = 0;
        StringBuilder ex1 = new StringBuilder();
        StringBuilder ex2 = new StringBuilder();
        try (BufferedReader r = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonNode j = om.readTree(line);
                String w = j.get("w").asText();
                boolean e = j.get("known").asBoolean();
                boolean l = sp.known(w);
                n++;
                if (l && !e) {
                    onlyLucene++;
                    if (onlyLucene <= 15) {
                        ex1.append(w).append(' ');
                    }
                } else if (e && !l) {
                    onlyEnchant++;
                    if (onlyEnchant <= 15) {
                        ex2.append(w).append(' ');
                    }
                }
            }
        }
        System.out.printf("spell: %d words; known only to Lucene %d (%s); known only to enchant %d (%s)%n", n,
            onlyLucene, ex1, onlyEnchant, ex2);
    }
}