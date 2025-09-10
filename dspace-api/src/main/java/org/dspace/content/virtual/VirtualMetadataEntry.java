/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.virtual;

import java.util.Objects;

public class VirtualMetadataEntry {

    private String value;
    private String language;

    public VirtualMetadataEntry(String value) {
        this.value = value;
    }

    public VirtualMetadataEntry(String value, String language) {
        this.value = value;
        this.language = language;
    }

    public String getValue() {
        return value;
    }

    public String getLanguage() {
        return language;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final VirtualMetadataEntry that = (VirtualMetadataEntry) o;
        return Objects.equals(value, that.value) && Objects.equals(language, that.language);
    }

    @Override
    public int hashCode() {
        int result = getValue().hashCode();
        result = 31 * result + Objects.hashCode(getLanguage());
        return result;
    }
}

