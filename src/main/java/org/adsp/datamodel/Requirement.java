package org.adsp.datamodel;

import org.adsp.tools.JsonWriter;

public record Requirement(String item, int quantity) {
    public String toString(){
        return JsonWriter.objectToString(this);
    }
}