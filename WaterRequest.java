public class WaterRequest {
    public enum Priority {
        CRITICAL(1), HIGH(2), NORMAL(3);
        private final int rank;
        Priority(int rank) { this.rank = rank; }
        public int getRank() { return rank; }
    }
    public enum Status { PENDING, ALLOCATED, PARTIALLY_ALLOCATED, REJECTED }

    private final String requestId;
    private final String areaId;
    private final int requiredWater;
    private final int population;
    private int daysSinceLastSupply;
    private final boolean emergency;
    private final String areaType;
    private Priority priority = Priority.NORMAL;
    private final int sequenceNumber;
    private int allocatedWater = 0;
    private int unmetWater;
    private Status status = Status.PENDING;

    public WaterRequest(String requestId, DistributionArea area, int requiredWater,
                        boolean emergency, int sequenceNumber, int currentDay) {
        this.requestId = requestId;
        this.areaId = area.getAreaId();
        this.requiredWater = requiredWater;
        this.population = area.getPopulation();
        this.daysSinceLastSupply = area.calculateDaysSinceSupply(currentDay);
        this.emergency = emergency;
        this.areaType = area.getType();
        this.sequenceNumber = sequenceNumber;
        this.unmetWater = requiredWater;
    }

    public String getRequestId() { return requestId; }
    public String getAreaId() { return areaId; }
    public int getRequiredWater() { return requiredWater; }
    public int getPopulation() { return population; }
    public int getDaysSinceLastSupply() { return daysSinceLastSupply; }
    public boolean isEmergency() { return emergency; }
    public String getAreaType() { return areaType; }
    public Priority getPriority() { return priority; }
    public int getSequenceNumber() { return sequenceNumber; }
    public int getAllocatedWater() { return allocatedWater; }
    public int getUnmetWater() { return unmetWater; }
    public Status getStatus() { return status; }

    public void setPriority(Priority p) { this.priority = p; }

    public void allocate(int amount) {
        allocatedWater = amount;
        unmetWater = requiredWater - amount;
        status = (unmetWater == 0) ? Status.ALLOCATED : Status.PARTIALLY_ALLOCATED;
    }

    public void restore(int days, Priority p, int allocated, int unmet, Status s) {
        daysSinceLastSupply = days; priority = p; allocatedWater = allocated; unmetWater = unmet; status = s;
    }

    public void markRejected() { status = Status.REJECTED; }

    public void displayRequest() {
        System.out.println("Request " + requestId + " | Area: " + areaId + " (" + areaType + ")"
                + " | Required: " + requiredWater + " L | Emergency: " + (emergency ? "YES" : "NO")
                + " | Priority: " + priority + " | Seq: " + sequenceNumber
                + " | Days since supply: " + daysSinceLastSupply + " | Population: " + population
                + " | Allocated: " + allocatedWater + " L | Unmet: " + unmetWater + " L | " + status);
    }
}
