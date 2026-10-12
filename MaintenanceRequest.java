public class MaintenanceRequest {
    public enum Status { OPEN, COMPLETED }

    private final String maintenanceId;
    private final String pipelineId;
    private final String issue;
    private Status status = Status.OPEN;

    public MaintenanceRequest(String maintenanceId, String pipelineId, String issue) {
        this.maintenanceId = maintenanceId;
        this.pipelineId = pipelineId;
        this.issue = issue;
    }

    public String getMaintenanceId() { return maintenanceId; }
    public String getIssue() { return issue; }
    public String getPipelineId() { return pipelineId; }
    public Status getStatus() { return status; }
    public void complete() { status = Status.COMPLETED; }

    public void displayDetails() {
        System.out.println("Maintenance " + maintenanceId + " | Pipeline: " + pipelineId
                + " | " + issue + " | " + status);
    }
}
