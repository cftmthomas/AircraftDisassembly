# Aircraft Disassembly
Repository containing the code, instances and results for the article "Solving the Aircraft Disassembly Scheduling Problem" submitted at the European Journal of Operational Research.

## Content

The repository is organized as such:

- The **data** folder contains the anonymized instance used for the experiments. See the **Instances** section for more details about the instance files structure.
- The experimental results are contained in the **results** folder. See the corresponding section for more details.
- The **src** folder contains the source code. It is organized as such:
    - The **models** package contains the CP and MIP models.
    - The **datamodel** package contains all the data objects that are manipulated in the model.
    - The **tools** package contains several utility classes.

## Instances

Instances are encoded in json files. An instance file contains the following fields:

- `id` contains a unique identifier string unique to the instance.
- `name` contains the name of the instance.
- `version` contains a version number for the instance format.
- `maxTime` contains an integer indicating the global time horizon H.
- `balanceAF` and `balanceLR`contain integers corresponding to the maximum difference of mass allowed on the two balance axis
- `resources` contains an ordered list of resources available for the tasks. Each resource has the following fields:
    - `id` is a unique integer id corresponding to the index of the resource.
    - `name` contains the name of the resource.
    - `categoris` is a list of skills that the resource has. each of the is a String.
    - `unavailable` is a list of time windows corresponding to the periods when the resource is unavailable. Each time window is encoded as a pair of two integers corresponding to the start and end of the unavailablity period.
    - `cost` is an integer that encodes the cost per time period of the resource.
- `locations` contains an ordered list of locations. Each location contains the following fields:
    - `id` a numerical unique id corresponding to the index of the location.
    - `name` the name of the location.
    - `zone` the zone in which this location is situated. For locations that are not situated in one of the 4 balance zones, this field may either contain the value "CENTER" or be empty.
    - `capacity` the capacity of this location that corresponds to the maximum number of technicians that can work there at the same time.
- `operations` contains an ordered list of operations. Each operation has the following fields:
    - `id` a unique numerical id corresponding to the index of the operation in the list.
    - `name` the name of the operation
    - `card` an ATA jobcard number encoded as a string. For anonymous instances, it is replaced by the id.
    - `duration` the duration of the operation.
    - `location` a location id that refers to the location where the operation take place.
    - `occupancy` the occupancy which corresponds to the total number of technicians that are needed for the operation.
    - `mass` the mass removed during the operation.
    - `requirements` a list of requirements that are needed for the operation. Each requirement consists in two fields:
        - `item`which which is the skill needed for the requirement.
        - `quantity` a quantity of resources needed for this requirement.
    - `precedences` contains a list of operation ids corresponding to the preceding operations that must be finished before starting this operation.

There are 16 instances. The instance **B737NG600-1454** is the full instance that contains the whole set of operations. All the other instances contain a subset of operations.

## Results

Experiments are in the **results** folder:

Each folder contains the results of one model on one variation of the problem.

For all folders, the results are separated into two kind of files: `Solutions` and `Logs`. Solution files contain the final best solution found at the end of the search. Log files contain the search log that records the evolution of the search. These files are also json files. They have the following format:

- `Solution` files contain the following fields:
    - `instance` contains the whole instance that the solution solves.
    - `activities` is a list indicating how each operation is scheduled. Each entry in the list correspond to an operation. It contains the following fields:
        - `operation` which is an operation id.
        - `start` which indicates the time at which the operation starts.
        - `end` which indicates the time at which the operation ends.
    - `assignments` is a list of all the assignations during the planing period. Each assignation is characterised by the following fields:
        - `resource` the resource which is assigned.
        - `operation` the operation at which the resource is assigned.
        - `requirement` the requirement of the operation that the assignation satisfys.
        - `start` the start of the assignation.
        - `end` the end of the assignation.
- `Log` files contain the following fields:
    - `instance` a string that containts the name of the instance on which the run was done.
    - `objectiveBound` contains an array of the lower bound found for each objective. The first entry correspond to the makespan.
    - `log` is an ordered list that contains the history of the search in terms of solutions found. Each entry in the list correspond to an evolution during the search (new solution found, solution proved optimal or end of search). Entries have the following fields:
        - `time` which indicates the search time at which the solution has been found.
        - `objective` which is an array containing the objective value(s) of the solution. The first entry correspond to the makespan.
        - `optimal` which is an array of boolean indicating if the solver has proven the solution optimal in regards to the objective.

In addition to the solution and log files for each instances, the excel file **results.xlsx** contains a general table of all the results.

## How to run

Before compiling or running the code, you must install [CP Optimizer 22.1.1](https://www.ibm.com/docs/en/icos/22.1.1?topic=cp-optimizer) or a subsequent version. Follow the installation instructions bundled with the download to set up correctly the program on your machine. Do not forget to set up the correct environmental variables.

The project uses [maven](https://maven.apache.org/) to manage the libraries needed for the model.
In order to make the CP Optimizer libraries available for maven, they have been imported as jars in the **libs** folder.

The `Launcher.java` class is used to launch one of the models with the following arguments:

```bash
<path/to/instance> <model> [options]
```

Possible options are:

- `-st` a flag that indicates that a solution file must be used as starting point for the search. In this case, the `<path/to/instance>` argument must point to a solution file instead of an instance file.
- `-sil` silent flag. Deactivates printing of messages on the standard output.
- `-t <time-limit>` sets the time limit (in seconds) of the search.
- `-n <n-workers>` sets the number of workers that are used in parallel for the search. The default value is 1.
- `-out <output/path>` sets the output path which corresponds to the folder where the log and solution files will be written at the end of the search.
- `-nobal` deactivates the balance constraints.
- `-noocc` deactivates the locations capacity constraints.
- `-noreq` deactivates the requirements constraints.

The `<model>` argument is mandatory and indicates which model will be run. Its possible values are:

- **CPModel** the CP Optimizer model.
- **MIPModel** the MIP model.
