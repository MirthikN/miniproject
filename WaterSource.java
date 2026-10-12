public class WaterSource {
    private final String sourceId;
    private final String sourceName;
    private final int totalCapacity;
    private int remainingWater;

    public WaterSource(String sourceId, String sourceName, int totalCapacity) {
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.totalCapacity = totalCapacity;
        this.remainingWater = totalCapacity;
    }

    public String getSourceId() { return sourceId; }
    public String getSourceName() { return sourceName; }
    public int getTotalCapacity() { return totalCapacity; }
    public int getRemainingWater() { return remainingWater; }

    public void addWater(int litres) {
        if (litres > 0) remainingWater = Math.min(totalCapacity, remainingWater + litres);
    }

    public int allocateWater(int requested) {
        int given = Math.min(Math.max(requested, 0), remainingWater);
        remainingWater -= given;
        return given;
    }

    public void resetDailySupply() { remainingWater = totalCapacity; }

    public void displayDetails() {
        System.out.println("Source " + sourceId + " | " + sourceName + " | Total: " + totalCapacity
                + " L | Remaining: " + remainingWater + " L");
    }
}
