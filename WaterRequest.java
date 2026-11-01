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
    private final int createdDay;
    private int allocatedDay = -1;

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
        this.createdDay = currentDay;
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
    public int getCreatedDay() { return createdDay; }
    public int getAllocatedDay() { return allocatedDay; }

    public void setPriority(Priority p) { this.priority = p; }

    public void allocate(int amount, int day) {
        allocatedWater = amount;
        allocatedDay = day;
        unmetWater = requiredWater - amount;
        status = (unmetWater == 0) ? Status.ALLOCATED : Status.PARTIALLY_ALLOCATED;
    }

    public void restore(int days, Priority p, int allocated, int unmet, Status s, int allocDay) {
        daysSinceLastSupply = days; priority = p; allocatedWater = allocated; unmetWater = unmet; status = s;
        allocatedDay = allocDay;
    }

    public void markRejected() { status = Status.REJECTED; }

    public void displayRequest() {
        System.out.println("Request " + requestId + " | Area: " + areaId + " (" + areaType + ")"
                + " | Required: " + requiredWater + " L | Emergency: " + (emergency ? "YES" : "NO")
                + " | Priority: " + priority + " | Seq: " + sequenceNumber + " | Day: " + createdDay
                + " | Days since supply: " + daysSinceLastSupply + " | Population: " + population
                + " | Allocated: " + allocatedWater + " L | Unmet: " + unmetWater + " L | " + status);
    }
}