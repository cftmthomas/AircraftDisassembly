package org.adsp.datamodel;

public record LogEntry(double time, int[] objective, boolean[] optimal) {
}
