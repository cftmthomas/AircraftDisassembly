package org.adsp.datamodel;

public record Log(String instance, int[] objectiveBound, LogEntry[] log) {

    public int bestObjective(int o) {
        int bestObj = Integer.MAX_VALUE;
        for(LogEntry entry: log){
            if(entry.objective()[o] < bestObj) bestObj = entry.objective()[o];
        }
        return bestObj;
    }

    public int bestObjectiveAt(int o, int maxTime) {
        int bestObj = Integer.MAX_VALUE;
        for(LogEntry entry: log){
            if(entry.time() < maxTime && entry.objective()[o] < bestObj) bestObj = entry.objective()[o];
        }
        return bestObj;
    }

    public double timeToBestObjective(int o) {
        int bestObj = Integer.MAX_VALUE;
        double bestT = Integer.MAX_VALUE;
        for(LogEntry entry: log){
            if(entry.objective()[o] < bestObj || (entry.objective()[o] == bestObj && entry.time() < bestT)){
                bestObj = entry.objective()[o];
                bestT = entry.time();
            }
        }
        return bestT;
    }

    public double timeToBestObjectiveAt(int o, int maxTime) {
        int bestObj = Integer.MAX_VALUE;
        double bestT = Integer.MAX_VALUE;
        for(LogEntry entry: log){
            if(entry.time() < maxTime && entry.objective()[o] < bestObj || (entry.objective()[o] == bestObj && entry.time() < bestT)){
                bestObj = entry.objective()[o];
                bestT = entry.time();
            }
        }
        return bestT;
    }

    public double timeToOptimum(int o) {
        if(isOptimal(o)) return timeToLastSol();
        else return Integer.MAX_VALUE;
    }

    public boolean isOptimal(int o){
        return log.length > 0 && log[log.length - 1].optimal()[o];
    }

    public boolean isOptimalAt(int o, int maxTime){
        if(!isSolved()) return false;
        else {
            for(LogEntry entry : log){
                if(entry.time() > maxTime) return false;
                if(entry.optimal()[o]) return true;
            }
        }
        return false;
    }

    public boolean isSolved(){
        return log.length > 0 && log[0].objective().length > 0 && log[0].objective()[0] < Integer.MAX_VALUE;
    }

    public boolean isSolvedAt(int maxTime){
        if(!isSolved()) return false;
        else return log[0].time() <= maxTime;
    }

    public int firstObjective(int o){
        return log.length > 0 ? log[0].objective()[o] : Integer.MAX_VALUE;
    }

    public int lastObjective(int o){
        return log.length > 0 ? log[log.length-1].objective()[o] : Integer.MAX_VALUE;
    }

    public double timeToFirstSol() {
        return log.length > 0 ? log[0].time() : Integer.MAX_VALUE;
    }

    public double timeToLastSol() {
        return log.length > 0 ? log[log.length-1].time() : Integer.MAX_VALUE;
    }

    public static double gap(double obj, double bound){
        return (obj - bound) / bound;
    }

    public int bestObjectiveFrom(int o, double time) {
        int bestObj = Integer.MAX_VALUE;
        for(LogEntry entry: log) if(entry.time() >= time){
            if(entry.objective()[o] < bestObj) bestObj = entry.objective()[o];
        }
        return bestObj;
    }

    public double timeToBestObjectiveFrom(int o, double time) {
        int bestObj = Integer.MAX_VALUE;
        double bestT = Integer.MAX_VALUE;
        for(LogEntry entry: log) if(entry.time() >= time){
            if(entry.objective()[o] < bestObj || (entry.objective()[o] == bestObj && entry.time() < bestT)){
                bestObj = entry.objective()[o];
                bestT = entry.time();
            }
        }
        return bestT;
    }

    public int firstObjectiveFrom(int o, double time){
        for(LogEntry entry: log) if(entry.time() >= time) return entry.objective()[o];
        return Integer.MAX_VALUE;
    }

    private double primalGap(LogEntry entry, int bestObj) {
        int obj = entry.objective()[0];
        if(obj == 0) return 0;
        else if(bestObj * obj < 0) return 1;
        else return Math.abs(bestObj - obj) / (double) Math.max(Math.abs(bestObj), Math.abs(obj));
    }

    public double getPrimalIntegral(int bestObj, int maxTime){
        if(isSolved()) {
            double primalInt = 0;
            double previousGap = 1;
            double previousTime = 0;
            for (LogEntry entry : log){
                if(entry.time() > maxTime) break;
                primalInt += previousGap * (entry.time() - previousTime);
                previousGap = primalGap(entry, bestObj);
                previousTime = entry.time();
            }
            primalInt += previousGap * (maxTime - previousTime);
            return primalInt;
        }
        else return maxTime;
    }
}
