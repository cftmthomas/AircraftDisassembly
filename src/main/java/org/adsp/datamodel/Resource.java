package org.adsp.datamodel;

import org.adsp.tools.JsonWriter;

public record Resource(
        int id,
        String name,
        String[] categories, //Resource categories (include type and skills)
        TimeWindow[] unavailable,
        int cost
) {
    public String toString(){
        return JsonWriter.objectToString(this);
    }

    public boolean isWorker() {
        return true;
//        for(String cat : categories) if(cat.equals("WORKER")) return true;
//        return false;
    }

    public boolean hasSkill(String skill) {
        for(String cat : categories) if(cat.equals(skill)) return true;
        return false;
    }
}
