package org.adsp.datamodel;

import org.adsp.tools.JsonWriter;

import java.util.Arrays;

public record Instance(
        String id,
        String name,
        String version,
        int maxTime,
        int balanceAF,
        int balanceLR,
        Resource[] resources,
        Location[] locations,
        Operation[] operations
) {
    public int nResources(){
        return resources.length;
    }
    public int nLocations(){
        return locations.length;
    }
    public int nOperations(){
        return operations.length;
    }

    public boolean isForward(int o){
        return locations[operations[o].location()].isForward();
    }

    public boolean isAft(int o){
        return locations[operations[o].location()].isAft();
    }

    public boolean isRight(int o){
        return locations[operations[o].location()].isRight();
    }

    public boolean isLeft(int o){
        return locations[operations[o].location()].isLeft();
    }

    public int[] workerIds() {
        return Arrays.stream(resources)
                .filter(Resource::isWorker)
                .mapToInt(Resource::id)
                .toArray();
    }

    public int totalManPower() {
        int total = 0;
        for(Operation op : operations){
            total += op.duration() * op.occupancy();
        }
        return total;
    }

    private int computeEst(int i, int[] ests, boolean[] cached) {
        int est = 0;
        for(int p : operations()[i].precedences()){
            int pect = cached[p] ? ests[p] : computeEst(p, ests, cached) + operations()[p].duration();
            if(pect > est) est = pect;
        }
        ests[i] = est;
        cached[i] = true;
        return est;
    }

    public int makespanLB(){
        int[] ests = new int[operations().length];
        boolean[] cached = new boolean[operations().length];
        int maxEct = 0;
        for(int i = 0; i < ests.length; i++){
            int opEct = (cached[i] ? ests[i] : computeEst(i, ests, cached)) + operations()[i].duration();
            if(opEct > maxEct) maxEct = opEct;
        }
        return maxEct;
    }

    public int makespanUB(){
        return maxTime;
    }

//    public int costLB(){
//        int totalCost = 0;
//        Map<String, Integer> minCostPerCat = minCostPerCategory();
//        for(Operation op: operations()){
//            for(Requirement req: op.requirements()){
//                int minReqCost = Arrays.stream(req.category()).mapToInt((cat) -> minCostPerCat.getOrDefault(cat, 0)).min().orElse(0);
//                totalCost += minReqCost * req.quantity() * op.duration();
//            }
//        }
//        return totalCost;
//    }
//
//    private Map<String, Integer> minCostPerCategory(){
//        Map<String, Integer> minCostPerCat = new HashMap<>();
//        for(Resource res: requirements()){
//            String cat = res.category();
//            if(!minCostPerCat.containsKey(cat) || minCostPerCat.get(cat) > res.cost()){
//                minCostPerCat.put(cat, res.cost());
//            }
//        }
//        return minCostPerCat;
//    }
//
//    public int costUB(){
//        int totalCost = 0;
//        Map<String, Integer> maxCostPerCat = maxCostPerCategory();
//        for(Operation op: operations()){
//            for(Requirement req: op.requirements()){
//                int maxReqCost = Arrays.stream(req.category()).mapToInt((cat) -> maxCostPerCat.getOrDefault(cat, 0)).max().orElse(0);
//                totalCost += maxReqCost * req.quantity() * op.duration();
//            }
//        }
//        return totalCost;
//    }
//
//    private Map<String, Integer> maxCostPerCategory(){
//        Map<String, Integer> maxCostPerCat = new HashMap<>();
//        for(Resource res: requirements()){
//            String cat = res.category();
//            if(!maxCostPerCat.containsKey(cat) || maxCostPerCat.get(cat) < res.cost()){
//                maxCostPerCat.put(cat, res.cost());
//            }
//        }
//        return maxCostPerCat;
//    }

    public String toString(){
        return JsonWriter.objectToString(this);
    }
}
