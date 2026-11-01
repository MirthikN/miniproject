/**
 * A repair ticket, opened automatically when a pipeline fails and completed when the pipeline is repaired.
 *
 * Urgency score (higher = fix first):
 *   score = (institution cut off ? INSTITUTION_BONUS : 0) + peopleWithoutWater + AGING_PER_DAY * daysOpen
 *
 *  - peopleWithoutWater : population of every area that has NO working route to any source because of this pipeline
 *                         (worked out with the BFS in WaterNetwork when the ticket is opened)
 *  - institution bonus  : hospitals/institutions always outrank residential-only tickets
 *  - aging              : every day a ticket waits adds AGING_PER_DAY, so small tickets cannot starve forever
 */
public class MaintenanceRequest {
    public enum Status { OPEN, IN_PROGRESS, COMPLETED }

    public static final int INSTITUTION_BONUS = 100000;
    public static final int AGING_PER_DAY = 200;

    private final String maintenanceId;
    private final String pipelineId;
    private final String issue;
    private final String reportedBy;          // area id, or "DAM" when the dam operator reported it
    private final int openedDay;
    private final int affectedPopulation;     // people who lose water while this pipeline is down
    private final boolean institutionAffected;
    private Status status = Status.OPEN;
    private int startedDay = -1;
    private int closedDay = -1;
    private String resolution = "";

    public MaintenanceRequest(String maintenanceId, String pipelineId, String issue, String reportedBy,
                              int openedDay, int affectedPopulation, boolean institutionAffected) {
        this.maintenanceId = maintenanceId;
        this.pipelineId = pipelineId;
        this.issue = issue;
        this.reportedBy = reportedBy == null ? "" : reportedBy;
        this.openedDay = openedDay;
        this.affectedPopulation = affectedPopulation;
        this.institutionAffected = institutionAffected;
    }

    public String getMaintenanceId() { return maintenanceId; }
    public String getIssue() { return issue; }
    public String getPipelineId() { return pipelineId; }
    public String getReportedBy() { return reportedBy; }
    public Status getStatus() { return status; }
    public int getOpenedDay() { return openedDay; }
    public int getStartedDay() { return startedDay; }
    public int getClosedDay() { return closedDay; }
    public String getResolution() { return resolution; }
    public int getAffectedPopulation() { return affectedPopulation; }
    public boolean isInstitutionAffected() { return institutionAffected; }
    public boolean isActive() { return status != Status.COMPLETED; }
    public int getSeqNo() { return Integer.parseInt(maintenanceId.substring(1)); }  // "M5" -> 5

    public int daysOpen(int currentDay) { return Math.max(0, currentDay - openedDay); }

    public long score(int currentDay) {
        return (institutionAffected ? INSTITUTION_BONUS : 0L) + affectedPopulation
                + (long) AGING_PER_DAY * daysOpen(currentDay);
    }

    public void start(int day) { status = Status.IN_PROGRESS; startedDay = day; }

    public void complete(int day, String details) {
        status = Status.COMPLETED;
        closedDay = day;
        resolution = details == null ? "" : details;
    }

    public void restore(Status s, int startedDay, int closedDay, String resolution) {
        this.status = s; this.startedDay = startedDay; this.closedDay = closedDay;
        this.resolution = resolution == null ? "" : resolution;
    }

    public void displayDetails(int currentDay) {
        System.out.println("Maintenance " + maintenanceId + " | Score: " + score(currentDay)
                + " | Pipeline: " + pipelineId + " | " + issue + " | Reported by: " + reportedBy
                + " | Opened day " + openedDay + " | People affected: " + affectedPopulation
                + (institutionAffected ? " (institution)" : "") + " | " + status);
    }
}