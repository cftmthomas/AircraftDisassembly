package org.adsp.models;

import ilog.concert.*;
import ilog.concert.cppimpl.IloAlgorithm;
import ilog.cp.IloCP;
import org.adsp.datamodel.*;
import org.adsp.tools.JsonReader;
import org.adsp.tools.JsonWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CP Optimizer model for the Aircraft Disassembly Scheduling Problem.
 */
public class CPModel {
    //Parameters:
    private boolean silent = false;
    private String outputPath = "output/default/";
    private boolean startSol;
    private Consumer<Solution> onSolution = (Solution) -> {
    };
    private long searchStart = System.nanoTime();
    private double timeLimit = 60;
    private int workers = 4;
    private IloCP.ParameterValues searchType = IloCP.ParameterValues.Auto;
    private boolean balanceActive = true;
    private boolean occupancyActive = true;
    private boolean requirementsActive = true;

    //Data:
    private final Instance instance;
    private final int nResources;
    private final int nLocations;
    private final int nOperations;

    //Model:
    private IloCP cp; //Cp solver

    private IloCumulFunctionExpr[] locUsage; //Occupancy of locations.

    private IloIntervalVar[] operations; //Main Activities.
    private IntervalVarList[] resourceActivities; //Optional activities.

    private final IntExprList ends = new IntExprList(); //End time of activities. Used for makespan objective.
    private final IntExprList costs = new IntExprList(); //Costs of optional activities. Used for cost objective.

    //Cumulative differences of mass between balance zones:
    //Note that the range is shifted to avoid negative cumulative values:
    //The range is between 0 and 2 * the maximum mass difference.
    //The equilibrium value is equal to the maximum mass difference.
    private IloCumulFunctionExpr diffAF;
    private IloCumulFunctionExpr diffLR;

    //Objectives:
    private IloIntExpr makespan; //Main objective: minimize objective.
    private IloIntExpr cost; //Secondary objective: minimize costs.

    //Solution management:
    private IloSolution currentSol;
    private Solution lastSol;
    private final ArrayList<LogEntry> log = new ArrayList<>();

    public CPModel(Instance instance) {

        //Preparing data:
        this.instance = instance;
        nResources = instance.resources().length;
        nLocations = instance.locations().length;
        nOperations = instance.operations().length;

        cp = new IloCP();
    }

    public void init() {
        int[] workerIndices = instance.workerIds();
        int nWorkers = workerIndices.length;

        //Initializing solver and model:
        try {
            diffAF = cp.cumulFunctionExpr();
            diffLR = cp.cumulFunctionExpr();
            diffAF = cp.sum(diffAF, cp.step(0, instance.balanceAF()));
            diffLR = cp.sum(diffLR, cp.step(0, instance.balanceLR()));

            locUsage = new IloCumulFunctionExpr[nLocations];
            Arrays.fill(locUsage, cp.cumulFunctionExpr());

            //Initializing main activities:
            operations = new IloIntervalVar[nOperations];
            for (int i = 0; i < nOperations; i++) {
                operations[i] = cp.intervalVar("O[" + i + "]");
            }
            for (int i = 0; i < nOperations; i++) {
                //Creating interval var
                Operation op = instance.operations()[i];
                IloIntervalVar act = operations[i];
                act.setSizeMin(op.duration());
                act.setSizeMax(op.duration());
                ends.add(cp.endOf(act));

                //Setting up mass impact:
                if (balanceActive && op.mass() > 0) {
                    if (instance.isForward(i)) diffAF = cp.sum(diffAF, cp.stepAtStart(act, op.mass()));
                    if (instance.isAft(i)) diffAF = cp.diff(diffAF, cp.stepAtStart(act, op.mass()));
                    if (instance.isRight(i)) diffLR = cp.sum(diffLR, cp.stepAtStart(act, op.mass()));
                    if (instance.isLeft(i)) diffLR = cp.diff(diffLR, cp.stepAtStart(act, op.mass()));
                }

                //setting up occupancy consumption:
                if (occupancyActive)
                    locUsage[op.location()] = cp.sum(locUsage[op.location()], cp.pulse(act, op.occupancy()));

                //setting up precedences and priorities:
                for (int j : op.precedences()) {
                    cp.add(cp.endBeforeStart(operations[j], act));
                }
            }

            //Initializing optional activities:
            resourceActivities = new IntervalVarList[nResources];
            for (int r = 0; r < nResources; r++) {
                resourceActivities[r] = new IntervalVarList();
            }

            //Allocating resources to operations:
            for (int i = 0; i < nOperations; i++) {
                Operation op = instance.operations()[i];
                IloIntervalVar[] workerActivities = new IloIntervalVar[nWorkers]; //Workers optional activities
                Map<String, Integer> skillCount = new HashMap<>(); //Skills needed

                //Reading requirements
                if (requirementsActive) {
                    for (Requirement req : op.requirements()) {
                        switch (req.item()) {
                            case "B1" -> {
                                skillCount.put("B1", req.quantity());
                            }
                            case "B2" -> {
                                skillCount.put("B2", req.quantity());
                            }
                        }
                    }
                }

                //Creating workers optional vars:
                for (int j = 0; j < nWorkers; j++) {
                    int idx = workerIndices[j];
                    IloIntervalVar optionalAct = cp.intervalVar(op.duration(), "R[" + i + "," + idx + "]");
                    optionalAct.setOptional();
                    workerActivities[j] = optionalAct;
                    resourceActivities[j].add(optionalAct);
                    costs.add(cp.prod(cp.presenceOf(optionalAct), op.duration() * instance.resources()[idx].cost()));
                }
                cp.add(cp.alternative(operations[i], workerActivities, op.occupancy()));

                //Setting skill presence constraints:
                if (requirementsActive) {
                    if (!silent) System.out.println("Setting up requirements constraints for operation " + i);
                    for (Map.Entry<String, Integer> entry : skillCount.entrySet()) {
                        String skill = entry.getKey();
                        IntervalVarList skillCompatible = new IntervalVarList();
                        for (int j = 0; j < workerActivities.length; j++) {
                            if (instance.resources()[workerIndices[j]].hasSkill(skill)) {
                                skillCompatible.add(workerActivities[j]);
                            }
                        }
                        //Number of workers on the activity with the required skill must be >= requirement
                        cp.add(cp.alternative(operations[i], skillCompatible.toArray(), cp.intVar(entry.getValue(), op.occupancy())));
                    }
                }
            }

            for (int r = 0; r < nResources; r++) {
                //Adding unavailability activities:
                Resource res = instance.resources()[r];
                for (int u = 0; u < res.unavailable().length; u++) {
                    IloIntervalVar unav = cp.intervalVar("U[" + r + ":" + res.unavailable()[u].start() + ";" + res.unavailable()[u].start() + "]");
                    unav.setStartMin(res.unavailable()[u].start());
                    unav.setStartMax(res.unavailable()[u].start());
                    unav.setEndMin(res.unavailable()[u].end());
                    unav.setEndMax(res.unavailable()[u].end());
                    resourceActivities[r].add(unav);
                }
                //Creating seqVar and adding noOverlap constraint
                IloIntervalSequenceVar seq = cp.intervalSequenceVar(resourceActivities[r].toArray(), "S[" + r + "]");
                cp.add(cp.noOverlap(seq));
            }

            //Adding balance constraints:
            if (balanceActive) {
                if (!silent) System.out.println("Setting up balance constraints");
                cp.add(cp.le(diffAF, instance.balanceAF() * 2));
                cp.add(cp.ge(diffAF, 0));
                cp.add(cp.le(diffLR, instance.balanceLR() * 2));
                cp.add(cp.ge(diffLR, 0));
            }

            //Adding occupancy constraints:
            if (occupancyActive) {
                if (!silent) System.out.println("Setting up occupancy constraints");
                for (int l = 0; l < instance.locations().length; l++) {
                    cp.add(cp.le(locUsage[l], instance.locations()[l].capacity()));
                }
            }

            //Setting objectives:
            makespan = cp.max(ends.toArray()); //Primary objective: minimize objective
            cp.add(cp.le(makespan, instance.maxTime()));

            cost = cp.sum(costs.toArray()); //Secondary objective: minimize costs
        } catch (IloException e) {
            close();
            throw new RuntimeException(e);
        }
    }

    public void close() {
        if (cp != null) cp.end();
    }

    private void processSol() throws IloException {
        double currentSearchTime = (double) timeElapsed() / 1000000000;

        //Processing sol:
        Activity[] activities = new Activity[nOperations];
        for (int i = 0; i < nOperations; i++) {
            activities[i] = new Activity(i, cp.getStart(operations[i]), cp.getEnd(operations[i]));
        }
        ArrayList<Assignment> assignments = new ArrayList<>();
        for (int r = 0; r < nResources; r++) {
            for (IloIntervalVar resAct : resourceActivities[r]) {
                String actName = resAct.getName();
                if (actName.startsWith("R") && cp.isPresent(resAct)) {
                    int op = Integer.parseInt(actName.substring(2, actName.length() - 1).split(",")[0]);
                    assignments.add(new Assignment(r, op, cp.getStart(resAct), cp.getEnd(resAct)));
                }
            }
        }
        Solution sol = new Solution(instance, activities, assignments.toArray(new Assignment[0]), new int[]{(int) cp.getValue(makespan), (int) cp.getValue(cost)});

        //Logging sol:
        log.add(new LogEntry(currentSearchTime, sol.objective(), new boolean[]{cp.getObjGap() == 0}));

        //Printing sol:
        if (!silent) {
            System.out.println("new solution found at " + currentSearchTime);
            System.out.println("Makespan \t: " + sol.objective()[0]);
            System.out.println("Cost \t: " + sol.objective()[1]);
//            JsonWriter.printSolution(sol);
        }

        //Saving sol:
        lastSol = sol;

        //Saving current sol:
        currentSol = cp.solution();
        for (IloIntervalVar act : operations) {
            if (cp.isPresent(act)) {
                currentSol.setPresent(act);
                currentSol.setStart(act, cp.getStart(act));
            } else {
                currentSol.setAbsent(act);
            }
        }
        for (IntervalVarList acts : resourceActivities) {
            for (IloIntervalVar act : acts) {
                if (cp.isPresent(act)) {
                    currentSol.setPresent(act);
                    currentSol.setStart(act, cp.getStart(act));
                    currentSol.setEnd(act, cp.getEnd(act));
                } else {
                    currentSol.setAbsent(act);
                }
            }
        }
        onSolution.accept(sol);
    }

    private void assignStartSol(Solution sol) {
        try {
            currentSol = cp.solution();
            for (Activity act : sol.activities()) {
                currentSol.setPresent(operations[act.operation()]);
                currentSol.setStart(operations[act.operation()], act.start());
            }
            for (Assignment ass : sol.assignments()) {
                IloIntervalVar var = getResourceAct(ass.operation(), ass.requirement(), ass.resource());
                if (var != null) {
                    currentSol.setPresent(var);
                    currentSol.setStart(var, ass.start());
                    currentSol.setEnd(var, ass.end());
                }
            }
            cp.add(cp.le(makespan, sol.objective()[0]));
            cp.add(cp.le(cost, sol.objective()[1]));
            cp.setStartingPoint(currentSol);
        } catch (IloException e) {
            throw new RuntimeException(e);
        }
    }

    //Not optimal. If performances are needed, consider maintaining a map of activities by name.
    private IloIntervalVar getResourceAct(int op, int req, int res) {
        if (res < 0 || res >= resourceActivities.length) return null;
        String name = "R[" + op + "," + req + "," + res + "]";
        for (IloIntervalVar act : resourceActivities[res]) {
            if (act.getName().equals(name)) return act;
        }
        return null;
    }

    private void performSearch() {
        try {
            cp.startNewSearch();
            while (cp.next()) {
                processSol();
            }
            cp.endSearch();
        } catch (IloException e) {
            close();
            throw new RuntimeException(e);
        }
    }

    //Search on makespan objective
    public void makespanSearch() {
        searchStart = System.nanoTime();
        int makespanBound = instance.makespanLB();
        //int costBound = instance.costLB();
        int costBound = 0;
        try {
            IloObjective objective = cp.minimize(makespan);
            cp.add(objective);
            if (startSol) assignStartSol(lastSol);
            if (!silent) System.out.println("starting search on makespan objective");
            performSearch();
            int cpBound = (int) cp.getObjBound();
            if (cpBound > makespanBound) makespanBound = cpBound;
        } catch (IloException e) {
            close();
            throw new RuntimeException(e);
        }

        if (lastSol != null)
            log.add(new LogEntry((double) timeElapsed() / 1000000000, lastSol.objective(), new boolean[]{cp.getStatus() == IloAlgorithm.Status.Optimal}));
        String logFile = outputPath + "logs/" + instance.name() + ".json";
        if (!silent) System.out.println("Writing search log to file: " + logFile);
        JsonWriter.writeLogToFile(new Log(instance.name(), new int[]{makespanBound, costBound}, log.toArray(new LogEntry[0])), logFile);

        if (lastSol != null) {
            String solFile = outputPath + "solutions/" + instance.name() + ".json";
            if (!silent) System.out.println("Writing best solution to file: " + solFile);
            JsonWriter.writeSolutionToFile(lastSol, solFile);
        } else System.out.println("No solution found.");
    }

    private long timeElapsed() {
        return System.nanoTime() - searchStart;
    }

    public double getTimeLimit() {
        return timeLimit;
    }

    public void setTimeLimit(double timeLimit) {
        this.timeLimit = timeLimit;
        if (cp != null) {
            try {
                cp.setParameter(IloCP.DoubleParam.TimeLimit, timeLimit);
            } catch (IloException e) {
                close();
                throw new RuntimeException(e);
            }
        }
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
        if (cp != null) {
            try {
                cp.setParameter(IloCP.IntParam.Workers, workers);
            } catch (IloException e) {
                close();
                throw new RuntimeException(e);
            }
        }
    }

    public IloCP.ParameterValues getSearchType() {
        return searchType;
    }

    public void setSearchType(IloCP.ParameterValues searchType) {
        this.searchType = searchType;
        if (cp != null) {
            try {
                cp.setParameter(IloCP.IntParam.SearchType, searchType);
            } catch (IloException e) {
                close();
                throw new RuntimeException(e);
            }
        }
    }

    public boolean isSilent() {
        return silent;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
        if (cp != null) {
            if (silent) cp.setOut(null);
            else cp.setOut(System.out);
        }
    }

    public void setOnSolution(Consumer<Solution> onSolution) {
        this.onSolution = onSolution;
    }

    public boolean isStartSol() {
        return startSol;
    }

    public void setStartSol(boolean startSol) {
        this.startSol = startSol;
    }

    public void setSolution(Solution solution) {
        this.lastSol = solution;
    }

    public Solution getSolution() {
        return lastSol;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String path) {
        this.outputPath = path;
    }

    public void setBalanceActive(boolean balanceActive) {
        this.balanceActive = balanceActive;
    }

    public void setOccupancyActive(boolean occupancyActive) {
        this.occupancyActive = occupancyActive;
    }

    public void setRequirementsActive(boolean requirementsActive) {
        this.requirementsActive = requirementsActive;
    }

    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "data/json/xp/B737NG600-10.json";
        Instance instance = JsonReader.readInstanceFile(path);
        CPModel model = new CPModel(instance);
        if (args.length > 1) model.setTimeLimit(Integer.parseInt(args[1]));
        model.init();
        model.makespanSearch();
    }

    private class IntervalVarList extends ArrayList<IloIntervalVar> {
        public IloIntervalVar[] toArray() {
            return this.toArray(new IloIntervalVar[0]);
        }
    }

    private class IntExprList extends ArrayList<IloIntExpr> {
        public IloIntExpr[] toArray() {
            return this.toArray(new IloIntExpr[0]);
        }
    }
}
