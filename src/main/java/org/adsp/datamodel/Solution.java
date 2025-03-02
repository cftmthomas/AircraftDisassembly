package org.adsp.datamodel;

import org.adsp.tools.JsonWriter;

public record Solution(Instance instance, Activity[] activities, Assignment[] assignments, int[] objective) {
    public String toString(){
        return JsonWriter.objectToString(this);
    }
}
