public class CriticalInstitution extends DistributionArea {
    public CriticalInstitution(String id, String name, int population, int demand, int lastSupplyDay) {
        super(id, name, population, demand, lastSupplyDay);
    }
    @Override public String getType() { return "INSTITUTIONAL"; }
}
