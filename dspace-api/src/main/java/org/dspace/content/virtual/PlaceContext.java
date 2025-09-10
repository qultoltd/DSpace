/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.virtual;

import java.util.HashMap;
import java.util.Map;

public class PlaceContext {

    private final Map<String, Integer> placeTracker = new HashMap<>();

    public int getNextPlaceFor(String metadataFieldName) {

        int currentPlace = placeTracker.getOrDefault(metadataFieldName, 0);
        placeTracker.put(metadataFieldName, currentPlace + 1);
        return currentPlace;
    }
}
