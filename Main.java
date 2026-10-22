import java.util.*;

public class Main {
    private static final Scanner sc = new Scanner(System.in);

    private static int currentDay = -1;
    private static int interval = -1;
    private static int seqCounter = 0;
    private static int maintCounter = 0;

    private static final ArrayList<WaterSource> sources = new ArrayList<>();
    private static final HashMap<String, WaterSource> sourceMap = new HashMap<>();
    private static final ArrayList<DistributionArea> areaList = new ArrayList<>();
    private static final HashMap<String, DistributionArea> areaMap = new HashMap<>();
    private static final ArrayList<WaterRequest> requestHistory = new ArrayList<>();
    private static final HashMap<String, WaterRequest> requestMap = new HashMap<>();
    private static final ArrayDeque<MaintenanceRequest> maintenance = new ArrayDeque<>();
    // Open maintenance requests: priority 1 (institutional) before priority 2 (residential), then first come first served.
    private static final PriorityQueue<MaintenanceRequest> maintQueue = new PriorityQueue<>(
            Comparator.comparingInt(MaintenanceRequest::getPriority)
                      .thenComparingInt(MaintenanceRequest::getSeqNo));

    private static final WaterNetwork network = new WaterNetwork();
    private static final WaterAllocationPolicy policy = new WaterAllocationPolicy();

    public static void main(String[] args) {
        while (true) {
            System.out.println("\n========================================");
            System.out.println("SMART WATER DISTRIBUTION MANAGEMENT");
            System.out.println("========================================");
            System.out.println("Select user type:");
            System.out.println(" 1. Dam User");
            System.out.println(" 2. Municipal User");
            System.out.println(" 3. Exit");
            System.out.println("========================================");
            int role = readInt("Enter choice: ", 1);
            switch (role) {
                case 1: damMenu(); break;
                case 2: municipalMenu(); break;
                case 3:
                    sc.close();
                    System.out.println("Goodbye.");
                    return;
                default: System.out.println("Invalid choice. Please enter 1, 2 or 3.");
            }
        }
    }

    private static void damMenu() {
        while (true) {
            System.out.println("\n========================================");
            System.out.println("DAM USER MENU");
            System.out.println("========================================");
            System.out.println(" 1. Set Normal Supply Interval");
            System.out.println(" 2. Add Water Source");
            System.out.println(" 3. Add Distribution Area");
            System.out.println(" 4. Add Pipeline");
            System.out.println(" 5. Remove Pipeline");
            System.out.println(" 6. View Water Source Status");
            System.out.println(" 7. View Distribution Area Status");
            System.out.println(" 8. View Pipeline Status");
            System.out.println(" 9. View Water Requests");
            System.out.println("10. Check Area Connectivity");
            System.out.println("11. Distribute Water");
            System.out.println("12. View Allocation History");
            System.out.println("13. Repair Pipeline");
            System.out.println("14. View Maintenance Requests");
            System.out.println("15. Back to User Selection");
            System.out.println("========================================");
            int choice = readInt("Enter choice: ", 1);
            switch (choice) {
                case 1: setInterval(); break;
                case 2: addSource(); break;
                case 3: addArea(); break;
                case 4: addPipeline(); break;
                case 5: removePipeline(); break;
                case 6: viewSources(); break;
                case 7: viewAreas(); break;
                case 8: viewPipelines(); break;
                case 9: viewRequests(); break;
                case 10: checkConnectivity(); break;
                case 11: distribute(); break;
                case 12: viewHistory(); break;
                case 13: repairPipeline(); break;
                case 14: viewMaintenance(); break;
                case 15: return;
                default: System.out.println("Invalid choice. Please enter a number from 1 to 15.");
            }
        }
    }

    private static void municipalMenu() {
        if (areaList.isEmpty()) {
            System.out.println("No areas have been added yet. The dam user must add an area first.");
            return;
        }
        String aid = readNonEmpty("Enter your Area ID: ");
        DistributionArea myArea = areaMap.get(aid);
        if (myArea == null) {
            System.out.println("Area ID not found. Please check with the dam user.");
            return;
        }
        while (true) {
            System.out.println("\n========================================");
            System.out.println("MUNICIPAL USER MENU");
            System.out.println("Area: " + myArea.getAreaId() + " - " + myArea.getAreaName());
            System.out.println("========================================");
            System.out.println(" 1. Set Current Day");
            System.out.println(" 2. Create Water Request");
            System.out.println(" 3. Report Pipeline Failure");
            System.out.println(" 4. View Water Source Status");
            System.out.println(" 5. View Allocation History");
            System.out.println(" 6. Back to User Selection");
            System.out.println("========================================");
            int choice = readInt("Enter choice: ", 1);
            switch (choice) {
                case 1: setCurrentDay(); break;
                case 2: createRequest(myArea); break;
                case 3: reportFailure(myArea); break;
                case 4: viewSources(); break;
                case 5: viewHistory(myArea); break;
                case 6: return;
                default: System.out.println("Invalid choice. Please enter a number from 1 to 6.");
            }
        }
    }

    private static String readLine(String prompt) {
        System.out.print(prompt);
        if (!sc.hasNextLine()) {
            System.out.println("\nInput ended. Exiting.");
            System.exit(0);
        }
        return sc.nextLine().trim();
    }

    private static String readNonEmpty(String prompt) {
        while (true) {
            String s = readLine(prompt);
            if (!s.isEmpty()) return s;
            System.out.println("Input cannot be empty.");
        }
    }

    private static int readInt(String prompt, int min) {
        while (true) {
            try {
                int v = Integer.parseInt(readLine(prompt));
                if (v < min) { System.out.println("Value must be at least " + min + "."); continue; }
                return v;
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid whole number.");
            }
        }
    }

    private static boolean readYesNo(String prompt) {
        while (true) {
            String s = readLine(prompt).toUpperCase();
            if (s.equals("YES")) return true;
            if (s.equals("NO")) return false;
            System.out.println("Please type YES or NO.");
        }
    }

    private static boolean ready() {
        if (currentDay < 0) { System.out.println("Municipal user must set the current day first."); return false; }
        if (interval < 1) { System.out.println("Dam user must set the normal supply interval first."); return false; }
        return true;
    }

    private static void setCurrentDay() {
        int d = readInt("Current day number: ", 0);
        for (DistributionArea a : areaList) {
            if (a.getLastSupplyDay() > d) {
                System.out.println("Rejected: area " + a.getAreaId() + " has last supply day " + a.getLastSupplyDay() + " (later than " + d + ").");
                return;
            }
        }
        currentDay = d;
        System.out.println("Current day set to " + currentDay + ".");
    }

    private static void setInterval() {
        interval = readInt("Normal supply interval (days, >= 1): ", 1);
        System.out.println("Interval set to " + interval + " days.");
    }

    private static boolean idUsed(String id) {
        return sourceMap.containsKey(id) || areaMap.containsKey(id);
    }

    private static void addSource() {
        String id = readNonEmpty("Source ID: ");
        if (idUsed(id)) { System.out.println("Rejected: ID already used by a source or area."); return; }
        String name = readNonEmpty("Source name: ");
        int total = readInt("Total available water (L): ", 0);
        WaterSource s = new WaterSource(id, name, total);
        sources.add(s);
        sourceMap.put(id, s);
        network.addLocation(id);
        System.out.println("Source added.");
    }

    private static void addArea() {
        String id = readNonEmpty("Area ID: ");
        if (idUsed(id)) { System.out.println("Rejected: ID already used by a source or area."); return; }
        String name = readNonEmpty("Area name: ");
        String type;
        while (true) {
            type = readLine("Area type (INSTITUTIONAL / RESIDENTIAL): ").toUpperCase();
            if (type.equals("INSTITUTIONAL") || type.equals("RESIDENTIAL")) break;
            System.out.println("Type must be INSTITUTIONAL or RESIDENTIAL.");
        }
        int pop = readInt("Population: ", 0);
        int demand = readInt("Normal daily water requirement (L): ", 0);
        DistributionArea a = type.equals("INSTITUTIONAL")
                ? new CriticalInstitution(id, name, pop, demand, -1)
                : new ResidentialArea(id, name, pop, demand, -1);
        areaList.add(a);
        areaMap.put(id, a);
        network.addLocation(id);
        System.out.println("Area added.");
    }

    private static void addPipeline() {
        String pid = readNonEmpty("Pipeline ID: ");
        if (network.hasPipeline(pid)) { System.out.println("Rejected: pipeline ID already exists."); return; }
        String from = readNonEmpty("From location ID: ");
        if (!network.hasLocation(from)) { System.out.println("Rejected: location does not exist."); return; }
        String to = readNonEmpty("To location ID: ");
        if (!network.hasLocation(to)) { System.out.println("Rejected: location does not exist."); return; }
        if (from.equals(to)) { System.out.println("Rejected: From and To must differ."); return; }
        if (sourceMap.containsKey(from) && sourceMap.containsKey(to)) {
            System.out.println("Rejected: a pipeline must involve at least one area."); return;
        }
        if (network.connectionExists(from, to)) { System.out.println("Rejected: these locations are already connected."); return; }
        network.addConnection(pid, from, to);
        System.out.println("Pipeline added (WORKING).");
    }

    private static void removePipeline() {
        String pid = readNonEmpty("Pipeline ID to remove: ");
        System.out.println(network.removeConnection(pid) ? "Pipeline removed." : "Rejected: pipeline not found.");
    }

    private static void createRequest(DistributionArea a) {
        if (!ready()) return;
        System.out.println("Creating a request for " + a.getAreaId() + " - " + a.getAreaName());
        String rid = readNonEmpty("Request ID: ");
        if (requestMap.containsKey(rid)) { System.out.println("Rejected: request ID already exists."); return; }
        int need = readInt("Required water (L, > 0): ", 1);
        boolean emergency = readYesNo("Emergency (YES/NO): ");
        WaterRequest r = new WaterRequest(rid, a, need, emergency, ++seqCounter, currentDay);
        policy.enqueue(r, a, interval);
        requestHistory.add(r);
        requestMap.put(rid, r);
        System.out.println("Request recorded. Priority assigned: " + r.getPriority());
    }

    private static void viewRequests() {
        if (requestHistory.isEmpty()) { System.out.println("No requests yet."); return; }
        for (WaterRequest r : requestHistory) r.displayRequest();
    }

    private static void distribute() {
        if (!ready()) return;
        policy.processAll(sources, areaMap, network, currentDay);
    }

    private static ArrayList<String> sourceIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (WaterSource s : sources) ids.add(s.getSourceId());
        return ids;
    }

    private static ArrayList<String> areaIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (DistributionArea a : areaList) ids.add(a.getAreaId());
        return ids;
    }

    private static void checkConnectivity() {
        String aid = readNonEmpty("Area ID: ");
        if (!areaMap.containsKey(aid)) { System.out.println("Area does not exist."); return; }
        boolean any = false;
        for (WaterSource s : sources) {
            boolean ok = network.isReachable(s.getSourceId(), aid);
            System.out.println("  From " + s.getSourceId() + " (" + s.getSourceName() + "): " + (ok ? "REACHABLE" : "not reachable"));
            any = any || ok;
        }
        System.out.println(any ? "Area is connected." : "Area is currently disconnected.");
    }

    // Priority 1 if either end of the pipeline is an institutional area, otherwise 2.
    private static int maintPriority(String pid) {
        Pipeline p = network.getPipeline(pid);
        if (p == null) return 2;
        DistributionArea a = areaMap.get(p.getFromId()), b = areaMap.get(p.getToId());
        boolean inst = (a != null && a.getType().equals("INSTITUTIONAL"))
                    || (b != null && b.getType().equals("INSTITUTIONAL"));
        return inst ? 1 : 2;
    }

    private static void reportFailure(DistributionArea myArea) {
        String pid = readNonEmpty("Pipeline ID: ");
        Pipeline p = network.getPipeline(pid);
        if (p == null) { System.out.println("Rejected: pipeline not found."); return; }
        if (!p.getFromId().equals(myArea.getAreaId()) && !p.getToId().equals(myArea.getAreaId())) {
            System.out.println("Rejected: this pipeline is not connected to your area (" + myArea.getAreaId() + ").");
            return;
        }
        String reason = readNonEmpty("Failure reason: ");
        if (!network.failPipeline(pid, reason)) {
            System.out.println("Rejected: pipeline not found or not WORKING."); return;
        }
        MaintenanceRequest mr = new MaintenanceRequest("M" + (++maintCounter), pid, "Failure: " + reason);
        mr.setPriority(maintPriority(pid));
        maintenance.add(mr);
        maintQueue.add(mr);
        System.out.println("Pipeline " + pid + " marked FAILED. Maintenance request created.");
        ArrayList<String> affected = network.getAffectedAreas(areaIds(), sourceIds());
        System.out.println(affected.isEmpty() ? "All areas still have a path to a source."
                : "Areas now disconnected from every source: " + affected);
    }

    private static void repairPipeline() {
        String pid = readNonEmpty("Pipeline ID: ");
        String details = readNonEmpty("Repair details: ");
        if (!network.repairPipeline(pid, details)) {
            System.out.println("Rejected: pipeline not found or not FAILED."); return;
        }
        for (MaintenanceRequest m : maintenance)
            if (m.getPipelineId().equals(pid) && m.getStatus() == MaintenanceRequest.Status.OPEN) m.complete();
        maintQueue.removeIf(q -> q.getPipelineId().equals(pid));
        MaintenanceRequest done = new MaintenanceRequest("M" + (++maintCounter), pid, "Repair: " + details);
        done.complete();
        maintenance.add(done);
        System.out.println("Pipeline " + pid + " repaired (WORKING). Connectivity now:");
        ArrayList<String> affected = network.getAffectedAreas(areaIds(), sourceIds());
        for (DistributionArea a : areaList)
            System.out.println("  " + a.getAreaId() + " (" + a.getAreaName() + "): "
                    + (affected.contains(a.getAreaId()) ? "still disconnected" : "connected"));
    }

    private static void viewMaintenance() {
        if (maintenance.isEmpty()) { System.out.println("No maintenance requests."); return; }
        System.out.println("--- OPEN (most urgent first) ---");
        PriorityQueue<MaintenanceRequest> copy = new PriorityQueue<>(maintQueue);
        while (!copy.isEmpty()) copy.poll().displayDetails();
        System.out.println("--- COMPLETED ---");
        for (MaintenanceRequest m : maintenance)
            if (m.getStatus() == MaintenanceRequest.Status.COMPLETED) m.displayDetails();
    }

    private static void viewSources() {
        if (sources.isEmpty()) { System.out.println("No sources yet."); return; }
        for (WaterSource s : sources) s.displayDetails();
    }

    private static void viewAreas() {
        if (areaList.isEmpty()) { System.out.println("No areas yet."); return; }
        for (DistributionArea a : areaList) a.displayDetails(currentDay);
    }

    private static void viewPipelines() {
        if (network.getPipelineList().isEmpty()) { System.out.println("No pipelines yet."); return; }
        for (Pipeline p : network.getPipelineList()) p.displayDetails();
    }

    private static void viewHistory(DistributionArea myArea) {
        boolean found = false;
        for (String line : policy.getAllocationHistory()) {
            for (WaterRequest r : requestHistory) {
                if (r.getAreaId().equals(myArea.getAreaId())
                        && (line.startsWith(r.getRequestId() + " ") || line.startsWith(r.getRequestId() + ":"))) {
                    System.out.println(line);
                    found = true;
                    break;
                }
            }
        }
        if (!found) System.out.println("No allocation history for your area yet.");
    }

    private static void viewHistory() {
        if (policy.getAllocationHistory().isEmpty()) { System.out.println("No allocation history yet."); return; }
        for (String line : policy.getAllocationHistory()) System.out.println(line);
    }
}