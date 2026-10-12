import java.util.*;

public class WaterAllocationPolicy {
    private final PriorityQueue<WaterRequest> queue = new PriorityQueue<>(
            Comparator.comparingInt((WaterRequest r) -> r.getPriority().getRank())
                      .thenComparingInt(WaterRequest::getSequenceNumber));
    private final ArrayList<String> allocationHistory = new ArrayList<>();

    public ArrayList<String> getAllocationHistory() { return allocationHistory; }
    public void requeue(WaterRequest r) { queue.add(r); }
    public boolean hasQueuedRequests() { return !queue.isEmpty(); }

    public WaterRequest.Priority determinePriority(WaterRequest r, DistributionArea a, int interval) {
        if (a.getType().equals("INSTITUTIONAL") || r.isEmergency()) return WaterRequest.Priority.CRITICAL;
        if (r.getDaysSinceLastSupply() > interval || a.hasSignificantShortage()) return WaterRequest.Priority.HIGH;
        return WaterRequest.Priority.NORMAL;
    }

    public void enqueue(WaterRequest r, DistributionArea a, int interval) {
        r.setPriority(determinePriority(r, a, interval));
        queue.add(r);
    }

    public WaterRequest processNext(ArrayList<WaterSource> sources, HashMap<String, DistributionArea> areas,
                                    WaterNetwork network, int currentDay) {
        WaterRequest r = queue.poll();
        if (r == null) return null;
        allocate(r, areas.get(r.getAreaId()), sources, network, currentDay);
        return r;
    }

    public void processAll(ArrayList<WaterSource> sources, HashMap<String, DistributionArea> areas,
                           WaterNetwork network, int currentDay) {
        if (queue.isEmpty()) { System.out.println("No requests waiting in the queue."); return; }
        ArrayList<WaterRequest> deferred = new ArrayList<>();
        while (!queue.isEmpty()) {
            WaterRequest r = processNext(sources, areas, network, currentDay);
            if (r.getStatus() == WaterRequest.Status.PENDING) deferred.add(r);
        }
        queue.addAll(deferred);
    }

    public void allocate(WaterRequest r, DistributionArea area, ArrayList<WaterSource> sources,
                         WaterNetwork network, int currentDay) {
        if (area == null) {
            r.markRejected();
            allocationHistory.add(r.getRequestId() + ": REJECTED (area does not exist)");
            System.out.println("Request " + r.getRequestId() + " rejected: area does not exist.");
            return;
        }
        boolean anyReachable = false;
        WaterSource chosen = null;
        for (WaterSource s : sources) {
            if (network.isReachable(s.getSourceId(), area.getAreaId())) {
                anyReachable = true;
                if (s.getRemainingWater() > 0) { chosen = s; break; }
            }
        }
        if (!anyReachable) {
            System.out.println("Request " + r.getRequestId() + " (" + r.getPriority() + "): "
                    + "Area is currently disconnected. Water cannot be allocated.");
            allocationHistory.add(r.getRequestId() + " [" + r.getPriority() + "]: PENDING (area disconnected)");
            return;
        }
        if (chosen == null) {
            System.out.println("Request " + r.getRequestId() + " (" + r.getPriority() + "): "
                    + "Connected source(s) have no water left. Request stays PENDING.");
            allocationHistory.add(r.getRequestId() + " [" + r.getPriority() + "]: PENDING (no water in source)");
            return;
        }
        int amount = chosen.allocateWater(r.getRequiredWater());
        r.allocate(amount);
        area.updateLastSupply(currentDay, amount);
        allocationHistory.add(r.getRequestId() + " [" + r.getPriority() + "] area " + area.getAreaName()
                + " from " + chosen.getSourceName() + ": " + amount + " L of " + r.getRequiredWater()
                + " L, " + r.getStatus());
        printResult(r, area, chosen);
    }

    private void printResult(WaterRequest r, DistributionArea area, WaterSource s) {
        System.out.println("========================================");
        System.out.println("WATER DISTRIBUTION RESULT");
        System.out.println("========================================");
        System.out.println("Request ID      : " + r.getRequestId());
        System.out.println("Area            : " + area.getAreaName());
        System.out.println("Priority        : " + r.getPriority());
        System.out.println("Required Water  : " + r.getRequiredWater() + " L");
        System.out.println("Allocated Water : " + r.getAllocatedWater() + " L");
        System.out.println("Unmet Water     : " + r.getUnmetWater() + " L");
        System.out.println("Status          : " + r.getStatus());
        System.out.println("Source Used     : " + s.getSourceName());
        System.out.println("Remaining Source Water: " + s.getRemainingWater() + " L");
        System.out.println("========================================");
    }
}
