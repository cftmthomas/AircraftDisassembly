package org.adsp.datamodel;

import org.adsp.tools.JsonWriter;

public record Operation(
        int id,
        String name,
        String card,
        int duration,
        int location,
        int occupancy,
        int mass,
        Requirement[] requirements,
        int[] precedences //Operations that must end before the start of this one
) {
    public String toString(){
        return JsonWriter.objectToString(this);
    }
}
