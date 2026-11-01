import java.util.*;

public class WaterNetwork {
    private final HashMap<String, ArrayList<String>> adjacency = new HashMap<>();
    private final HashMap<String, Pipeline> pipelines = new HashMap<>();
    private final HashMap<String, String> pairToPipeline = new HashMap<>();
    private final ArrayList<Pipeline> pipelineList = new ArrayList<>();

    public void addLocation(String id) { adjacency.putIfAbsent(id, new ArrayList<>()); }

    public boolean hasLocation(String id) { return adjacency.containsKey(id); }
    public boolean hasPipeline(String id) { return pipelines.containsKey(id); }
    public boolean connectionExists(String a, String b) { return pairToPipeline.containsKey(a + "-" + b); }
    public ArrayList<Pipeline> getPipelineList() { return pipelineList; }
    public Pipeline getPipeline(String id) { return pipelines.get(id); }

    public void addConnection(String pipelineId, String fromId, String toId) {
        Pipeline p = new Pipeline(pipelineId, fromId, toId);
        pipelines.put(pipelineId, p);
        pipelineList.add(p);
        adjacency.get(fromId).add(toId);
        adjacency.get(toId).add(fromId);
        pairToPipeline.put(fromId + "-" + toId, pipelineId);
        pairToPipeline.put(toId + "-" + fromId, pipelineId);
    }

    public boolean removeConnection(String pipelineId) {
        Pipeline p = pipelines.remove(pipelineId);
        if (p == null) return false;
        pipelineList.remove(p);
        adjacency.get(p.getFromId()).remove(p.getToId());
        adjacency.get(p.getToId()).remove(p.getFromId());
        pairToPipeline.remove(p.getFromId() + "-" + p.getToId());
        pairToPipeline.remove(p.getToId() + "-" + p.getFromId());
        return true;
    }

    public boolean isReachable(String sourceId, String areaId) {
        return isReachable(sourceId, areaId, null);
    }

    /** BFS over working pipelines; the pipeline named in assumeWorking (if any) is treated as working. */
    public boolean isReachable(String sourceId, String areaId, String assumeWorking) {
        if (!adjacency.containsKey(sourceId) || !adjacency.containsKey(areaId)) return false;
        ArrayDeque<String> queue = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        queue.add(sourceId);
        visited.add(sourceId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(areaId)) return true;
            for (String next : adjacency.get(current)) {
                if (visited.contains(next)) continue;
                Pipeline p = pipelines.get(pairToPipeline.get(current + "-" + next));
                if (p == null || !(p.isWorking() || p.getPipelineId().equals(assumeWorking))) continue;
                visited.add(next);
                queue.add(next);
            }
        }
        return false;
    }

    public boolean failPipeline(String id, String reason) {
        Pipeline p = pipelines.get(id);
        if (p == null || !p.isWorking()) return false;
        p.markFailed(reason);
        return true;
    }

    public boolean repairPipeline(String id, String details) {
        Pipeline p = pipelines.get(id);
        if (p == null || p.isWorking()) return false;
        p.repair(details);
        return true;
    }

    public ArrayList<String> getAffectedAreas(ArrayList<String> areaIds, ArrayList<String> sourceIds) {
        ArrayList<String> affected = new ArrayList<>();
        for (String a : areaIds) {
            boolean ok = false;
            for (String s : sourceIds) {
                if (isReachable(s, a)) { ok = true; break; }
            }
            if (!ok) affected.add(a);
        }
        return affected;
    }

    /** Areas that are cut off from every source now, but would be reachable if this one pipeline were working. */
    public ArrayList<String> getAreasCutBy(String pipelineId, ArrayList<String> areaIds, ArrayList<String> sourceIds) {
        ArrayList<String> cut = new ArrayList<>();
        for (String a : areaIds) {
            boolean reachableNow = false, reachableIfFixed = false;
            for (String s : sourceIds) {
                if (isReachable(s, a)) { reachableNow = true; break; }
                if (isReachable(s, a, pipelineId)) reachableIfFixed = true;
            }
            if (!reachableNow && reachableIfFixed) cut.add(a);
        }
        return cut;
    }
}