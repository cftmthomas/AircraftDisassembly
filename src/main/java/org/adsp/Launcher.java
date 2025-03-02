package org.adsp;

import org.adsp.models.MIPModel;
import org.adsp.models.CPModel;
import org.adsp.datamodel.Instance;
import org.adsp.datamodel.Solution;
import org.adsp.tools.JsonReader;

public class Launcher {
    public static void main(String[] args){
        //Arguments: instance/sol model -st -sil -t [time limit] -n [n workers] -out [outputPath] -nobal -noocc -noreq
        //Reading parameters:
        if(args.length < 2){
            System.out.println("No instance file path or model provided!");
            return;
        }

        Solution sol = null;
        boolean startSol = false;
        boolean silent = false;
        boolean balanceActive = true;
        boolean occupancyActive = true;
        boolean requirementsActive = true;
        int timeLimit = Integer.MAX_VALUE;
        int nWorkers = 1;
        String out = "output/default/";

        String model = args[1];

        int i = 2;
        while(i < args.length){
            String arg = args[i];
            switch (arg){
                case "-st":
                    startSol = true;
                    i++;
                    break;
                case "-sil":
                    silent = true;
                    i++;
                    break;
                case "-t":
                    timeLimit = Integer.parseInt(args[i+1]);
                    i+=2;
                    break;
                case "-n":
                    nWorkers = Integer.parseInt(args[i+1]);
                    i+=2;
                    break;
                case "-out":
                    out = args[i+1];
                    i+=2;
                    break;
                case "-nobal":
                    balanceActive = false;
                    i++;
                    break;
                case "-noocc":
                    occupancyActive = false;
                    i++;
                    break;
                case "-noreq":
                    requirementsActive = false;
                    i++;
                    break;
                default:
                    System.out.println("Argument " + arg + " is not recognized and will be ignored.");
                    i++;
            }
        }
        if(startSol) sol = JsonReader.readSolutionFile(args[0]);
        Instance instance = startSol ? sol.instance() : JsonReader.readInstanceFile(args[0]);
        if(instance == null || instance.id().equals("error")){
            return;
        }
        if(!instance.version().equals("1.1")){
            System.out.println("Incompatible instance format! Instance version is " + instance.version() + ", must be 1.1");
            return;
        }

        switch(model){
            case "CPModel":
                CPModel solverAlt = new CPModel(instance);
                if(startSol){
                    solverAlt.setSolution(sol);
                    solverAlt.setStartSol(true);
                }
                if(timeLimit < Integer.MAX_VALUE) solverAlt.setTimeLimit(timeLimit);
                if(nWorkers != 4) solverAlt.setWorkers(nWorkers);
                if(silent) solverAlt.setSilent(silent);
                if(!balanceActive) solverAlt.setBalanceActive(balanceActive);
                if(!occupancyActive) solverAlt.setOccupancyActive(occupancyActive);
                if(!requirementsActive) solverAlt.setRequirementsActive(requirementsActive);
                solverAlt.setOutputPath(out);

                solverAlt.init();
                solverAlt.makespanSearch();
                solverAlt.close();
                break;
            case "MIPModel":
                MIPModel solverMIP = new MIPModel(instance);
                if(silent) solverMIP.setSilent(silent);
                if(!balanceActive) solverMIP.setBalanceActive(balanceActive);
                if(!occupancyActive) solverMIP.setOccupancyActive(occupancyActive);
                if(!requirementsActive) solverMIP.setRequirementsActive(requirementsActive);
                solverMIP.setOutputPath(out);

                solverMIP.init();
                solverMIP.solve(timeLimit);
                solverMIP.close();
                break;
            case "InstanceStats":
                System.out.println("Characteristics of instance " + instance.name());
                System.out.println("Number of operations " + instance.nOperations());
                System.out.println("Makespan lower bound " + instance.makespanLB());
                break;
        }
    }
}
