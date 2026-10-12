public class Pipeline {
    public enum Status { WORKING, FAILED }

    private final String pipelineId;
    private final String fromId;
    private final String toId;
    private Status status;
    private String lastNote = "-";

    public Pipeline(String pipelineId, String fromId, String toId) {
        this.pipelineId = pipelineId;
        this.fromId = fromId;
        this.toId = toId;
        this.status = Status.WORKING;
    }

    public String getPipelineId() { return pipelineId; }
    public String getFromId() { return fromId; }
    public String getToId() { return toId; }
    public Status getStatus() { return status; }
    public String getLastNote() { return lastNote; }
    public void restore(boolean failed, String note) { status = failed ? Status.FAILED : Status.WORKING; lastNote = note; }
    public boolean isWorking() { return status == Status.WORKING; }

    public void markFailed(String reason) { status = Status.FAILED; lastNote = "Failed: " + reason; }
    public void repair(String details) { status = Status.WORKING; lastNote = "Repaired: " + details; }

    public void displayDetails() {
        System.out.println("Pipeline " + pipelineId + " | " + fromId + " <-> " + toId
                + " | " + status + " | " + lastNote);
    }
}
