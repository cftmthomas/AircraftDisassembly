package org.adsp.models;

import ilog.concert.*;
import ilog.cplex.IloCplex;
import org.adsp.datamodel.*;
import org.adsp.tools.JsonReader;
import org.adsp.tools.JsonWriter;

import java.util.ArrayList;
import java.util.Arrays;

public class MIPModelImproved {

    //Parameters:
    private boolean silent = false;
    private String outputPath = "output/default/";
    private boolean balanceActive = true;
    private boolean occupancyActive = true;
    private boolean requirementsActive = true;
    private CustomCallback callback;

    //Data:
    private final Instance instance;
    private final int nResources;
    private final int nLocations;
    private final int nOperations;
    private final int nTasks;
    private final Assignment[] unavailabilities;

    //Model:
    private IloCplex cplex;

    //Variables:
    private IloIntVar[][] tskProcess; //task processing binary variables (z_ie)
    private IloIntVar[] tskProcess0;
    private IloIntVar[][] tskStart; //Task start binary variables (s_ie)
    private IloIntVar[][] tskEnd; //Task end continuous variables (e_ie)
    private IloIntVar[] balanceAF; //Aft - Forward balance variables
    private IloIntVar[] balanceLR; //Left - Right balance variables
    private IloIntVar balanceAF0;
    private IloIntVar balanceLR0;
    private IloIntVar[] time; //event times (t_e)
    private IloIntVar mksp; //Makespan (C_max)
    private IloIntVar[][] assign; //Assignment variables (x_ij)
    private IloIntVar[][][] eventAssign; //Event assignment variables (y_ije)
    public Solution bestSol;

    public MIPModelImproved(Instance instance) {
        //Preparing data:
        this.instance = instance;
        nResources = instance.resources().length;
        nLocations = instance.locations().length;
        nOperations = instance.operations().length;

        //Gathering unavailabilities:
        ArrayList<Assignment> unavs = new ArrayList<>();
        for (Resource res : instance.resources()) {
            for (TimeWindow tw : res.unavailable()) {
                unavs.add(new Assignment(res.id(), -1, tw.start(), tw.end()));
            }
        }
        unavailabilities = unavs.toArray(new Assignment[0]);
        nTasks = nOperations + unavailabilities.length;

        try {
            cplex = new IloCplex();
        } catch (IloException e) {
            System.err.println("Concert exception caught '" + e + "' caught");
            System.exit(-1);
        }
    }

    public void init() {
        //Initializing model:
        try {
            //Variables:
            tskProcess = new IloIntVar[nTasks][nTasks]; //Task processing variables (1 if task i is processed during event e)
            tskProcess0 = new IloIntVar[nTasks]; //Task processing variables for event 0.
            tskStart = new IloIntVar[nOperations][nTasks]; //Task start variables (1 if task i starts at event e)
            tskEnd = new IloIntVar[nOperations][nTasks]; // Task end variables (end time if task i is processed at event e)
            balanceAF = new IloIntVar[nTasks]; //Aft - Forward balance variables
            balanceLR = new IloIntVar[nTasks]; //Left - Right balance variables
            time = new IloIntVar[nTasks]; //Time variables (time at which event e happens)
            mksp = cplex.intVar(0, instance.maxTime(), "makespan"); //Makespan
            assign = new IloIntVar[nTasks][nResources]; //Assignation variables (1 if task i has resource j assigned to it)
            eventAssign = new IloIntVar[nTasks][nResources][nTasks]; //Event assignation variables (1 if task i has resource j assigned to it during event e)

            for (int i = 0; i < nTasks; i++) {
                for (int e = 0; e < nTasks; e++) {
                    tskProcess[i][e] = cplex.boolVar("z_" + i + "," + e);
                }
                tskProcess0[i] = cplex.intVar(0, 0, "z_" + i + "-1");

                for (int j = 0; j < nResources; j++) {
                    assign[i][j] = cplex.boolVar("x_" + i + "," + j);
                    for (int e = 0; e < nTasks; e++) {
                        eventAssign[i][j][e] = cplex.boolVar("y_" + i + "," + j + "," + e);
                    }
                }
            }

            for (int i = 0; i < nOperations; i++) {
                for (int e = 0; e < nTasks; e++) {
                    tskStart[i][e] = cplex.boolVar("s_" + i + "," + e);
                    tskEnd[i][e] = cplex.intVar(-2 * instance.maxTime(), instance.maxTime(), "e_" + i + "," + e);
                }
            }

            for (int e = 0; e < nTasks; e++) {
                time[e] = cplex.intVar(0, instance.maxTime(), "t_" + e);
                balanceAF[e] = cplex.intVar(-instance.balanceAF(), instance.balanceAF(), "b^af_" + e);
                balanceLR[e] = cplex.intVar(-instance.balanceLR(), instance.balanceLR(), "b^lr_" + e);
            }
            balanceAF0 = cplex.intVar(0, 0, "b^af_-1");
            balanceLR0 = cplex.intVar(0, 0, "b^lr_-1");

            //Constraints:
            //RCPSP
            for (int e = 1; e < nTasks; e++) {
                //Ensures that events are set in increasing order of time:
                cplex.add(cplex.ge(time[e], time[e-1]));
            }

            for (int i = 0; i < nTasks; i++) {
                int duration = i < nOperations ? instance.operations()[i].duration() : unavailabilities[i - nOperations].end() - unavailabilities[i - nOperations].start();

                //Ensures that each task is processed during at least 1 event:
                cplex.add(cplex.ge(cplex.sum(tskProcess[i]), 1));

                for (int e = 1; e < nTasks; e++) {
                    //Makes sure that tasks are processed as a contiguous block of events:
                    cplex.add(cplex.le(cplex.diff(cplex.sum(Arrays.copyOfRange(tskProcess[i], 0, e)), cplex.prod(e, cplex.diff(1, cplex.diff(tskProcess[i][e], tskProcess[i][e - 1])))), 0));

                    //Makes sure that tasks are processed as a contiguous block of events:
                    cplex.add(cplex.le(cplex.diff(cplex.sum(Arrays.copyOfRange(tskProcess[i], e, nTasks)), cplex.prod(nTasks - e, cplex.sum(1, cplex.diff(tskProcess[i][e], tskProcess[i][e - 1])))), 0));

                    for (int f = 0; f < e; f++) {
                        //Links task processing variables with time variables:
                        cplex.add(cplex.ge(cplex.diff(cplex.diff(time[e], time[f]), cplex.prod(duration, cplex.diff(cplex.diff(tskProcess[i][f], f > 0 ? tskProcess[i][f - 1] : tskProcess0[i]), cplex.diff(tskProcess[i][e], tskProcess[i][e - 1])))), -duration));
                    }
                }
            }

            for (int i = 0; i < nOperations; i++) {
                for (int e = 0; e < nTasks; e++) {
                    //Links the end time variables with the task processing and time variables:
                    //cplex.add(cplex.le(tskEnd[i][e], cplex.prod(instance.maxTime(), cplex.diff(tskProcess[i][e], e > 0 ? tskProcess[i][e - 1] : tskProcess0[i]))));

                    int di =  instance.operations()[i].duration();
                    // end[i][e] >= (time[e] + di) - H * (1-tskStart[i,e])
                    cplex.add(cplex.ge(tskEnd[i][e],
                            cplex.diff(cplex.sum(time[e], di),
                                    cplex.prod(instance.maxTime(), cplex.diff(1, tskStart[i][e])))));

                    //Links the makespan variable with the time variables:
                    cplex.add(cplex.ge(mksp, tskEnd[i][e]));

                    System.out.println("di "+di);
                    // mksp >= time[e] + tskStart[i,e] * di
                    // cplex.add(cplex.ge(mksp, cplex.sum(time[e],cplex.prod(tskStart[i][e],di))));

                }
            }

            if (occupancyActive) {
                if (!silent) System.out.println("Setting up occupancy constraints");
                for (int l = 0; l < nLocations; l++) {
                    for (int e = 0; e < nTasks; e++) {
                        IntExprList occupations = new IntExprList();
                        for (int i = 0; i < nOperations; i++)
                            if (instance.operations()[i].location() == l) {
                                occupations.add(cplex.prod(instance.operations()[i].occupancy(), tskProcess[i][e]));
                            }
                        //Occupancy constraint: limits the number of concurrent assignments at each location during each event:
                        cplex.add(cplex.le(cplex.sum(occupations.toArray()), instance.locations()[l].capacity()));
                    }
                }
            }

            for (int i = 0; i < nOperations; i++) {
                int[] precs = instance.operations()[i].precedences();
                for (int e = 0; e < nTasks; e++) {
                    IntExprList previousProcess = new IntExprList();
                    for (int x = 0; x <= e; x++) previousProcess.add(tskProcess[i][x]);
                    IloIntExpr[] previousProcessArray = previousProcess.toArray();

                    for (int p : precs) {
                        //Precedences constraint:
                        cplex.add(cplex.le(cplex.sum(tskProcess[p][e], cplex.diff(cplex.sum(previousProcessArray), cplex.prod(e, cplex.diff(1, tskProcess[p][e])))), 1));
                    }
                }
            }

            //Other constraints:
            if (balanceActive) {
                if (!silent) System.out.println("Setting up balance constraints");
                for (int e = 0; e < nTasks; e++) {
                    IntExprList starts = new IntExprList();

                    for (int i = 0; i < nOperations; i++) {

                        //Links task start variables with task processing variables:
                        cplex.add(cplex.ge(tskStart[i][e], cplex.diff(tskProcess[i][e], e > 0 ? tskProcess[i][e - 1] : tskProcess0[i])));

                        starts.add(tskStart[i][e]);
                    }

                    IntExprList aft = new IntExprList();
                    IntExprList forward = new IntExprList();
                    IntExprList left = new IntExprList();
                    IntExprList right = new IntExprList();
                    for (int i = 0; i < nOperations; i++) {
                        //Adds operations contribution to balance variables:
                        if (instance.isAft(i)) aft.add(cplex.prod(tskStart[i][e], instance.operations()[i].mass()));
                        if (instance.isForward(i)) forward.add(cplex.prod(tskStart[i][e], instance.operations()[i].mass()));
                        if (instance.isLeft(i)) left.add(cplex.prod(tskStart[i][e], instance.operations()[i].mass()));
                        if (instance.isRight(i)) right.add(cplex.prod(tskStart[i][e], instance.operations()[i].mass()));
                    }

                    //Sets up Aft - Forward balance:
                    cplex.add(cplex.eq(balanceAF[e], cplex.sum(e > 0 ? balanceAF[e - 1] : balanceAF0, cplex.diff(cplex.sum(aft.toArray()), cplex.sum(forward.toArray())))));

                    //Sets up Left - Right balance:
                    cplex.add(cplex.eq(balanceLR[e], cplex.sum(e > 0 ? balanceLR[e - 1] : balanceLR0, cplex.diff(cplex.sum(left.toArray()), cplex.sum(right.toArray())))));

                    //Constrains Aft - Forward balance:
                    cplex.add(cplex.ge(balanceAF[e], -instance.balanceAF()));
                    cplex.add(cplex.le(balanceAF[e], instance.balanceAF()));

                    //Constrains Left - Right balance:
                    cplex.add(cplex.ge(balanceLR[e], -instance.balanceLR()));
                    cplex.add(cplex.le(balanceLR[e], instance.balanceLR()));
                }
            }

            for (int i = 0; i < nOperations; i++) {
                // exactly one start for each task
                cplex.add(cplex.eq(cplex.sum(tskStart[i]),1));
            }

            for (int e = 0; e < nTasks; e++) {
                for (int i = 0; i < nTasks; i++) {
                    for (int j = 0; j < nResources; j++) {
                        // Links evnet assignment variables, task processing variables and assignment variables:
                        cplex.add(cplex.le(eventAssign[i][j][e], assign[i][j]));
                        cplex.add(cplex.le(eventAssign[i][j][e], tskProcess[i][e]));
                        cplex.add(cplex.ge(eventAssign[i][j][e], cplex.diff(cplex.sum(assign[i][j], tskProcess[i][e]), 1)));
                    }
                }

                for (int i = 0; i < nOperations; i++) {
                    IloIntVar[] tskEventAssign = new IloIntVar[nResources];
                    for (int j = 0; j < nResources; j++) {
                        tskEventAssign[j] = eventAssign[i][j][e];
                    }
                    // Ensures that the correct number of technicians is assigned to each task if it is processed:
                    cplex.add(cplex.eq(cplex.sum(tskEventAssign), cplex.prod(instance.operations()[i].occupancy(), tskProcess[i][e])));
                }
            }

            for (int j = 0; j < nResources; j++) {
                for (int e = 0; e < nTasks; e++) {
                    IloIntVar[] resEventAssign = new IloIntVar[nTasks];
                    for (int i = 0; i < nTasks; i++) {
                        resEventAssign[i] = eventAssign[i][j][e];
                    }
                    //Ensures that each technician is assigned to at most one task at each event:
                    cplex.add(cplex.le(cplex.sum(resEventAssign), 1));
                }
            }

            for (int i = 0; i < nTasks; i++) {
                for (int j = 0; j < nResources; j++) {
                    IloIntExpr resAssign = cplex.sum(eventAssign[i][j]);
                    //Links assignment variables and event assignment variables:
                    cplex.add(cplex.le(assign[i][j], resAssign));
                    cplex.add(cplex.ge(cplex.prod(nTasks, assign[i][j]), resAssign));

                    IloIntExpr nProcessDiff = cplex.diff(cplex.sum(tskProcess[i]), resAssign);
                    //Links assignment variables and event assignment variables:
                    cplex.add(cplex.le(cplex.diff(1, assign[i][j]), nProcessDiff));
                    cplex.add(cplex.ge(cplex.prod(nTasks, cplex.diff(1, assign[i][j])), nProcessDiff));
                }

                //Ensures that the correct number of technicians is assigned to each task:
                cplex.add(cplex.eq(cplex.sum(assign[i]), i < nOperations ? instance.operations()[i].occupancy() : 1)); //34
            }

            if (requirementsActive) {
                for (int i = 0; i < nOperations; i++) {
                    if (!silent) System.out.println("Setting up requirements constraints for operation " + i);
                    Requirement[] reqs = instance.operations()[i].requirements();
                    for (Requirement req : reqs) {
                        IntExprList compAssign = new IntExprList();
                        for (int j = 0; j < nResources; j++) {
                            if (Arrays.asList(instance.resources()[j].categories()).contains(req.item())) {
                                compAssign.add(assign[i][j]);
                            }
                        }
                        //Requirements constraint:
                        cplex.add(cplex.ge(cplex.sum(compAssign.toArray()), req.quantity())); //35
                    }
                }
            }

            //Unavailabilities:
            for (int u = 0; u < unavailabilities.length; u++) {
                Assignment unavailable = unavailabilities[u];
                //Assigning resource to dummy task representing unavailability:
                cplex.add(cplex.eq(assign[u + nOperations][unavailable.resource()], 1));

                for (int e = 0; e < nTasks; e++) {
                    //Setting start time windows of dummy unavailability task:
                    cplex.add(cplex.ge(time[e], cplex.prod(tskProcess[u + nOperations][e], unavailable.start())));
                    //Setting end time windows of dummy unavailability task:
                    IloIntExpr diff = cplex.diff(tskProcess[u + nOperations][e], e > 0 ? tskProcess[u + nOperations][e - 1] : tskProcess0[u + nOperations]);
                    cplex.add(cplex.le(time[e], cplex.sum(cplex.prod(unavailable.start(), diff), cplex.prod(instance.maxTime(), cplex.diff(1, diff)))));
                }
            }

            //Objective
            IloObjective objective = cplex.minimize(mksp);
            cplex.add(objective);
        } catch (IloException e) {
            System.err.println("Concert exception caught '" + e + "' caught");
            System.exit(-1);
        }
    }

    public void solve(int timeLimit) {
        //Solving:
        try {
            callback = new CustomCallback(silent);
            boolean optimal = false;
            cplex.use(callback);
            cplex.setParam(IloCplex.StringParam.Threads, 1);
            cplex.setParam(IloCplex.DoubleParam.TimeLimit, timeLimit);
            if (cplex.solve()) {
                optimal = processSol();
            }

            ArrayList<LogEntry> log = callback.getLog();
            if (bestSol != null)
                log.add(new LogEntry((double) callback.timeElapsed() / 1000000000, bestSol.objective(), new boolean[]{optimal}));
            String logFile = outputPath + "logs/" + instance.name() + ".json";
            if (!silent) System.out.println("Writing search log to file: " + logFile);
            JsonWriter.writeLogToFile(new Log(instance.name(), log.isEmpty() ? new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE} : log.get(log.size() - 1).objective(), log.toArray(new LogEntry[0])), logFile);

            if (bestSol != null) {
                String solFile = outputPath + "solutions/" + instance.name() + ".json";
                if (!silent) System.out.println("Writing best solution to file: " + solFile);
                JsonWriter.writeSolutionToFile(bestSol, solFile);
            } else System.out.println("No solution found.");
        } catch (IloException e) {
            throw new RuntimeException(e);
        }
    }

    public void close() {
        if (cplex != null) cplex.end();
    }

    private boolean processSol() throws IloException {
        //Processing sol:
        if (!silent) {
            System.out.println("Best solution found:");
            System.out.println("Makespan = " + cplex.getValue(mksp));
        }
        System.out.println("time of events:");
        for (int e = 0; e < nTasks; e++) {
            System.out.print(cplex.getValue(time[e])+",");
        }

        Activity[] activities = new Activity[nOperations];
        ArrayList<Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < nOperations; i++) {
            int taskStart = (int) cplex.getValue(mksp);
            for (int e = 0; e < nTasks; e++)
                if (cplex.getValue(tskProcess[i][e]) > 0 && cplex.getValue(time[e]) < taskStart)
                    taskStart = (int) cplex.getValue(time[e]);
            activities[i] = new Activity(i, taskStart, taskStart + instance.operations()[i].duration());
            if (!silent) {
                System.out.print("Task " + i + " : Start = " + activities[i].start() + ", End = " + activities[i].end());
            }

            if (!silent) {
                System.out.print(", Assignments = [");
            }
            for (int j = 0; j < nResources; j++) {
                if (cplex.getValue(assign[i][j]) > 0) {
                    assignments.add(new Assignment(j, i, activities[i].start(), activities[i].end()));
                    if (!silent) {
                        System.out.print(j + ", ");
                    }
                }
            }
            if (!silent) {
                System.out.println("]");
            }
        }

        bestSol = new Solution(instance, activities, assignments.toArray(new Assignment[0]), new int[]{(int) Math.round(cplex.getValue(mksp)), (int) Math.round(cplex.getMIPRelativeGap() * 100)});
        return cplex.getStatus() == IloCplex.Status.Optimal;
    }

    public boolean isSilent() {
        return silent;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
        if (cplex != null) {
            if (silent) cplex.setOut(null);
            else cplex.setOut(System.out);
        }
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
        String path = args.length > 0 ? args[0] : "data/json/example_paper.json";
        int timeLimit = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        Instance instance = JsonReader.readInstanceFile(path);
        MIPModelImproved model = new MIPModelImproved(instance);
        model.init();
        model.solve(timeLimit);
    }

    private static class CustomCallback extends IloCplex.MIPInfoCallback {
        private final boolean silent;
        private final long searchStart = System.nanoTime();
        private final ArrayList<LogEntry> currentLog = new ArrayList<>();
        private int bestObj = Integer.MAX_VALUE;

        public CustomCallback(boolean silent) {
            this.silent = silent;
        }

        public long timeElapsed() {
            return System.nanoTime() - searchStart;
        }

        public ArrayList<LogEntry> getLog() {
            return currentLog;
        }

        @Override
        protected void main() throws IloException {
            double currentSearchTime = (double) timeElapsed() / 1000000000;
            int obj = (int) Math.round(getIncumbentObjValue());

            if (obj >= 0 && obj < bestObj) {
                //Logging sol:
                int gap = (int) Math.round(getMIPRelativeGap() * 100);
                currentLog.add(new LogEntry(currentSearchTime, new int[]{obj, gap}, new boolean[]{gap == 0}));

                //Printing sol:
                if (!silent) {
                    System.out.println("new solution found at " + currentSearchTime);
                    System.out.println("Makespan \t: " + obj);
                }

                bestObj = obj;
            }
        }
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
