import java.util.*;

/**
 * Priority-queue based allocation with a fair-share formula for shortages.
 *
 * Requests are served tier by tier (CRITICAL, then HIGH, then NORMAL). Inside a tier, requests that can reach the
 * same group of sources share one pool of water. If that pool covers everyone, everyone gets their full need.
 * Otherwise the pool is divided with the minimum-guarantee + fair-share formula:
 *
 *     floor_i = min(need_i, population_i * L_MIN)                       (basic-needs guarantee)
 *     leftover = pool - sum(floor_i)
 *     share_i  = leftover * (need_i - floor_i) / sum(need_j - floor_j)  (proportional to the remaining gap)
 *     give_i   = floor_i + share_i
 *
 * If even the floors do not fit, the floors are scaled down proportionally:  give_i = pool * floor_i / sum(floor).
 * Water is drawn from every reachable source in turn, so a request is never limited to one source.
 */
public class WaterAllocationPolicy {
    public static final int DEFAULT_L_MIN = 20;   // litres per person guaranteed first

    private final PriorityQueue<WaterRequest> queue = new PriorityQueue<>(
            Comparator.comparingInt((WaterRequest r) -> r.getPriority().getRank())
                      .thenComparingInt(WaterRequest::getSequenceNumber));
    private final ArrayList<HistoryEntry> allocationHistory = new ArrayList<>();

    public ArrayList<HistoryEntry> getAllocationHistory() { return allocationHistory; }
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

    public void processAll(ArrayList<WaterSource> sources, HashMap<String, DistributionArea> areas,
                           WaterNetwork network, int currentDay) {
        processAll(sources, areas, network, currentDay, DEFAULT_L_MIN);
    }

    public void processAll(ArrayList<WaterSource> sources, HashMap<String, DistributionArea> areas,
                           WaterNetwork network, int currentDay, int lMin) {
        if (queue.isEmpty()) { System.out.println("No requests waiting in the queue."); return; }
        ArrayList<WaterRequest> batch = new ArrayList<>();
        while (!queue.isEmpty()) batch.add(queue.poll());          // already in priority, then arrival order
        ArrayList<WaterRequest> deferred = new ArrayList<>();

        for (WaterRequest.Priority tier : WaterRequest.Priority.values()) {
            LinkedHashMap<String, ArrayList<WaterRequest>> groups = new LinkedHashMap<>();
            HashMap<String, ArrayList<WaterSource>> pools = new HashMap<>();
            for (WaterRequest r : batch) {
                if (r.getPriority() != tier) continue;
                DistributionArea area = areas.get(r.getAreaId());
                if (area == null) {
                    r.markRejected();
                    log(currentDay, r, r.getRequestId() + ": REJECTED (area does not exist)");
                    continue;
                }
                ArrayList<WaterSource> reach = new ArrayList<>();
                StringBuilder key = new StringBuilder();
                for (WaterSource s : sources) {
                    if (network.isReachable(s.getSourceId(), area.getAreaId())) {
                        reach.add(s);
                        key.append(s.getSourceId()).append(',');
                    }
                }
                if (reach.isEmpty()) {
                    log(currentDay, r, r.getRequestId() + " [" + r.getPriority() + "]: PENDING (area disconnected)");
                    System.out.println("Request " + r.getRequestId() + ": area is disconnected, stays PENDING.");
                    deferred.add(r);
                    continue;
                }
                groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(r);
                pools.putIfAbsent(key.toString(), reach);
            }
            for (Map.Entry<String, ArrayList<WaterRequest>> g : groups.entrySet())
                serveGroup(tier, g.getValue(), pools.get(g.getKey()), areas, currentDay, lMin, deferred);
        }
        queue.addAll(deferred);
    }

    /** Divides the water of one pool between the requests of one tier. */
    private void serveGroup(WaterRequest.Priority tier, ArrayList<WaterRequest> reqs, ArrayList<WaterSource> pool,
                            HashMap<String, DistributionArea> areas, int day, int lMin, ArrayList<WaterRequest> deferred) {
        long available = 0, totalNeed = 0;
        for (WaterSource s : pool) available += s.getRemainingWater();
        for (WaterRequest r : reqs) totalNeed += r.getRequiredWater();
        int n = reqs.size();
        int[] give = computeShares(reqs, available, totalNeed, lMin);

        for (int i = 0; i < n; i++) {
            WaterRequest r = reqs.get(i);
            DistributionArea area = areas.get(r.getAreaId());
            if (give[i] <= 0) {
                log(day, r, r.getRequestId() + " [" + r.getPriority() + "]: PENDING (no water in source)");
                System.out.println("Request " + r.getRequestId() + ": connected sources have no water left, stays PENDING.");
                deferred.add(r);
                continue;
            }
            int left = give[i];
            StringBuilder used = new StringBuilder();
            for (WaterSource s : pool) {
                if (left == 0) break;
                int took = s.allocateWater(Math.min(left, s.getRemainingWater()));
                if (took > 0) {
                    left -= took;
                    if (used.length() > 0) used.append(", ");
                    used.append(s.getSourceName());
                }
            }
            r.allocate(give[i], day);
            area.updateLastSupply(day, give[i]);
            log(day, r, r.getRequestId() + " [" + r.getPriority() + "] area " + area.getAreaName()
                    + " from " + used + ": " + give[i] + " L of " + r.getRequiredWater() + " L, " + r.getStatus());
            printResult(r, area, used.toString());
        }
    }

    /** The minimum-guarantee + fair-share formula. Returns the litres each request gets (same order as reqs). */
    static int[] computeShares(ArrayList<WaterRequest> reqs, long available, long totalNeed, int lMin) {
        int n = reqs.size();
        int[] give = new int[n];
        if (available <= 0) return give;                     // nothing left: everybody gets 0
        if (available >= totalNeed) {                        // enough for everybody: full need
            for (int i = 0; i < n; i++) give[i] = reqs.get(i).getRequiredWater();
            return give;
        }
        int[] floor = new int[n];
        long sumFloor = 0;
        for (int i = 0; i < n; i++) {
            WaterRequest r = reqs.get(i);
            floor[i] = (int) Math.min(r.getRequiredWater(), (long) r.getPopulation() * lMin);
            sumFloor += floor[i];
        }
        long used = 0;
        if (sumFloor >= available) {                         // not even the floors fit: scale them down
            for (int i = 0; i < n; i++) { give[i] = (int) (available * floor[i] / sumFloor); used += give[i]; }
            long rem = available - used;
            for (int i = 0; i < n && rem > 0; i++) {         // hand out litres lost to rounding
                int add = (int) Math.min(rem, floor[i] - give[i]);
                give[i] += add; rem -= add;
            }
        } else {
            long leftover = available - sumFloor;
            long sumGap = totalNeed - sumFloor;              // > leftover, because available < totalNeed
            for (int i = 0; i < n; i++) {
                long gap = reqs.get(i).getRequiredWater() - floor[i];
                int share = (int) (leftover * gap / sumGap);
                give[i] = floor[i] + share; used += share;
            }
            long rem = leftover - used;
            for (int i = 0; i < n && rem > 0; i++) {
                int add = (int) Math.min(rem, reqs.get(i).getRequiredWater() - give[i]);
                give[i] += add; rem -= add;
            }
        }
        return give;
    }

    private void log(int day, WaterRequest r, String message) {
        allocationHistory.add(new HistoryEntry(day, r.getRequestId(), r.getAreaId(), message));
    }

    private void printResult(WaterRequest r, DistributionArea area, String sourcesUsed) {
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
        System.out.println("Sources Used    : " + sourcesUsed);
        System.out.println("========================================");
    }
}