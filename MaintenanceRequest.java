public class MaintenanceRequest {
    public enum Status { OPEN, COMPLETED }

    private final String maintenanceId;
    private final String pipelineId;
    private final String issue;
    private Status status = Status.OPEN;
    private int priority = 2;   // 1 = institutional (first), 2 = residential (second)

    public MaintenanceRequest(String maintenanceId, String pipelineId, String issue) {
        this.maintenanceId = maintenanceId;
        this.pipelineId = pipelineId;
        this.issue = issue;
    }

    public String getMaintenanceId() { return maintenanceId; }
    public String getIssue() { return issue; }
    public String getPipelineId() { return pipelineId; }
    public Status getStatus() { return status; }
    public int getPriority() { return priority; }
    public void setPriority(int p) { priority = p; }
    public int getSeqNo() { return Integer.parseInt(maintenanceId.substring(1)); }  // "M5" -> 5
    public void complete() { status = Status.COMPLETED; }

    public void displayDetails() {
        System.out.println("Maintenance " + maintenanceId + " | Priority: " + priority + " | Pipeline: " + pipelineId
                + " | " + issue + " | " + status);
    }
}