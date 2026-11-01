public abstract class DistributionArea {
    private final String areaId;
    private final String areaName;
    private final int population;
    private final int dailyDemand;
    private int lastSupplyDay;
    private int lastDeliveredAmount;

    public DistributionArea(String areaId, String areaName, int population, int dailyDemand, int lastSupplyDay) {
        this.areaId = areaId;
        this.areaName = areaName;
        this.population = population;
        this.dailyDemand = dailyDemand;
        this.lastSupplyDay = lastSupplyDay;
        this.lastDeliveredAmount = dailyDemand;
    }

    public String getAreaId() { return areaId; }
    public String getAreaName() { return areaName; }
    public int getPopulation() { return population; }
    public int getDailyDemand() { return dailyDemand; }
    public int getLastSupplyDay() { return lastSupplyDay; }
    public int getLastDeliveredAmount() { return lastDeliveredAmount; }

    public abstract String getType();

    public void updateLastSupply(int day, int delivered) {
        this.lastSupplyDay = day;
        this.lastDeliveredAmount = delivered;
    }

    public int calculateDaysSinceSupply(int currentDay) {
        return lastSupplyDay < 0 ? 0 : currentDay - lastSupplyDay;
    }

    public boolean isDue(int currentDay, int interval) {
        return calculateDaysSinceSupply(currentDay) >= interval;
    }

    public boolean hasSignificantShortage() { return lastDeliveredAmount < 0.5 * dailyDemand; }

    public void displayDetails(int currentDay) {
        System.out.println("Area " + areaId + " | " + areaName + " | " + getType()
                + " | Population: " + population + " | Daily demand: " + dailyDemand + " L"
                + " | Last supply day: " + (lastSupplyDay < 0 ? "Not supplied yet"
                    : lastSupplyDay + (currentDay >= 0 ? " (" + calculateDaysSinceSupply(currentDay) + " days ago)" : ""))
                + " | Last delivered: " + lastDeliveredAmount + " L");
    }
}