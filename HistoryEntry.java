/** One line of the allocation history, tagged with the day it happened and the request/area it belongs to. */
public class HistoryEntry {
    private final int day;
    private final String requestId;
    private final String areaId;
    private final String message;

    public HistoryEntry(int day, String requestId, String areaId, String message) {
        this.day = day;
        this.requestId = requestId == null ? "" : requestId;
        this.areaId = areaId == null ? "" : areaId;
        this.message = message;
    }

    public int getDay() { return day; }
    public String getRequestId() { return requestId; }
    public String getAreaId() { return areaId; }
    public String getMessage() { return message; }

    @Override public String toString() { return message; }
}