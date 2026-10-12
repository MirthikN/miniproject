public class ResidentialArea extends DistributionArea {
    public ResidentialArea(String id, String name, int population, int demand, int lastSupplyDay) {
        super(id, name, population, demand, lastSupplyDay);
    }
    @Override public String getType() { return "RESIDENTIAL"; }
}
