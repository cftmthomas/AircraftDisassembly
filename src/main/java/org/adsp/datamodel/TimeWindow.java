package org.adsp.datamodel;

public record TimeWindow(int start, int end) {
    public TimeWindow(String window){
        this(Integer.parseInt(window.split(":")[0]), Integer.parseInt(window.split(":")[1]));
    }

    public int duration() {
        return end - start;
    }
}
