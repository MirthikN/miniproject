import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Server {
    static int currentDay = -1, interval = -1, seqCounter = 0, maintCounter = 0;
    static int lMin = WaterAllocationPolicy.DEFAULT_L_MIN;   // litres per person guaranteed first in a shortage
    static LocalDate startDate = null;                       // the calendar date that counts as Day 0
    static final ArrayList<WaterSource> sources = new ArrayList<>();
    static final HashMap<String, WaterSource> sourceMap = new HashMap<>();
    static final ArrayList<DistributionArea> areaList = new ArrayList<>();
    static final HashMap<String, DistributionArea> areaMap = new HashMap<>();
    static final ArrayList<WaterRequest> requestHistory = new ArrayList<>();
    static final HashMap<String, WaterRequest> requestMap = new HashMap<>();
    static final ArrayList<MaintenanceRequest> maintenance = new ArrayList<>();
    static final WaterNetwork network = new WaterNetwork();
    static final WaterAllocationPolicy policy = new WaterAllocationPolicy();

    static class ApiError extends RuntimeException {
        final int code;
        ApiError(String m) { this(m, 400); }
        ApiError(String m, int code) { super(m); this.code = code; }
    }

    public static void main(String[] args) throws Exception {
        DB.connect();
        restore();
        HttpServer srv = HttpServer.create(new InetSocketAddress(8080), 0);
        srv.createContext("/", Server::handle);
        srv.start();
        System.out.println("Server running. Open http://localhost:8080/index.html");
    }

    // ---------- HTTP plumbing ----------
    static synchronized void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (ex.getRequestMethod().equals("OPTIONS")) { send(ex, 204, "", "text/plain"); return; }
            if (path.startsWith("/api/")) api(ex, path.substring(4));
            else file(ex, path);
        } catch (ApiError e) {
            send(ex, e.code, "{\"error\":" + q(e.getMessage()) + "}", "application/json");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "{\"error\":" + q("Server error: " + e) + "}", "application/json");
        }
    }

    static void send(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        ex.close();
    }

    static void file(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        Path base = Paths.get("web").toAbsolutePath().normalize();
        Path f = base.resolve(path.substring(1)).normalize();
        if (!f.startsWith(base) || !Files.isRegularFile(f)) { send(ex, 404, "Not found", "text/plain"); return; }
        String n = f.toString();
        String type = n.endsWith(".html") ? "text/html" : n.endsWith(".css") ? "text/css"
                : n.endsWith(".js") ? "text/javascript" : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        byte[] b = Files.readAllBytes(f);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        ex.close();
    }

    // ---------- API routing ----------
    // The municipal pages pass their own area id as ?areaId=...; the dam console passes none and sees everything.
    static void api(HttpExchange ex, String p) throws IOException {
        String m = ex.getRequestMethod();
        Map<String, String> body = m.equals("POST")
                ? json(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)) : new HashMap<>();
        Map<String, String> qs = query(ex.getRequestURI().getRawQuery());
        String areaId = qs.get("areaId");
        String[] s = p.substring(1).split("/");

        String out;
        switch (s[0]) {
            case "config":       out = config(); break;
            case "sources":
                out = m.equals("POST") ? addSource(body) : arr(sources, Server::jSource);
                break;
            case "areas":
                if (s.length == 2) out = oneArea(s[1]);
                else out = m.equals("POST") ? addArea(body) : arr(areaList, Server::jArea);
                break;
            case "pipelines":
                if (s.length == 1) out = m.equals("POST") ? addPipeline(body) : arr(visiblePipelines(areaId), Server::jPipe);
                else if (m.equals("DELETE")) out = removePipeline(s[1]);
                else if (s.length == 3 && s[2].equals("fail")) out = fail(s[1], body);
                else if (s.length == 3 && s[2].equals("repair")) out = repair(s[1], body);
                else throw new ApiError("Unknown pipeline action.");
                break;
            case "requests":     out = m.equals("POST") ? addRequest(body) : listRequests(areaId); break;
            case "distribute":   out = distribute(); break;
            case "connectivity":
                out = "{\"disconnected\":" + arr(network.getAffectedAreas(areaIds(), sourceIds()), Server::q) + "}";
                break;
            case "history":      out = arr(historyLines(areaId), Server::q); break;
            case "maintenance":
                if (s.length == 3 && s[2].equals("start") && m.equals("POST")) out = startWork(s[1]);
                else out = arr(maintView(areaId), Server::jMaint);
                break;
            case "report":       out = report(areaId, qs); break;
            case "interval":     out = setInterval(body); break;
            case "lmin":         out = setLMin(body); break;
            case "startdate":    out = setStartDate(body); break;
            case "day":          out = setDay(body); break;
            default: throw new ApiError("Unknown endpoint: " + p, 404);
        }
        if (!m.equals("GET")) persist();
        send(ex, 200, out, "application/json");
    }

    static final String OK = "{\"ok\":true}";

    // ---------- dates and days ----------
    static LocalDate parseDate(String s, String what) {
        try { return LocalDate.parse(s.trim()); }
        catch (DateTimeParseException e) { throw new ApiError(what + " must be a valid date (YYYY-MM-DD)."); }
    }

    static String dateOf(int day) { return (startDate == null || day < 0) ? "" : startDate.plusDays(day).toString(); }

    static String dateLabel(int day) {
        if (day < 0) return "";
        return startDate == null ? "Day " + day : startDate.plusDays(day) + " (Day " + day + ")";
    }

    static void ready() {
        if (currentDay < 0) throw new ApiError("The current date must be set first (use Set current date).");
        if (interval < 1) throw new ApiError("The dam user must set the normal supply interval first.");
    }

    static void needDay() {
        if (currentDay < 0) throw new ApiError("The current date must be set first (use Set current date).");
    }

    static int latestRecordedDay() {
        int last = -1;
        for (WaterRequest r : requestHistory) last = Math.max(last, Math.max(r.getCreatedDay(), r.getAllocatedDay()));
        for (HistoryEntry e : policy.getAllocationHistory()) last = Math.max(last, e.getDay());
        for (MaintenanceRequest mr : maintenance)
            last = Math.max(last, Math.max(mr.getOpenedDay(), Math.max(mr.getStartedDay(), mr.getClosedDay())));
        for (DistributionArea a : areaList) last = Math.max(last, a.getLastSupplyDay());
        return last;
    }

    // The user enters a calendar date; the day number is worked out from the start date (Day 0).
    static String setDay(Map<String, String> b) {
        if (startDate == null) throw new ApiError("The dam operator must set the start date (Day 0) first.");
        LocalDate d = parseDate(req(b, "date"), "Date");
        long diff = ChronoUnit.DAYS.between(startDate, d);
        if (diff < 0) throw new ApiError("That date is before the start date (" + startDate + ").");
        int day = (int) diff;
        int last = latestRecordedDay();
        if (day < last)
            throw new ApiError("Rejected: activity is already recorded on " + dateLabel(last) + ", so the date cannot go back before it.");
        currentDay = day;
        return "{\"ok\":true," + kv("day", day) + "," + kv("date", d.toString()) + "}";
    }

    static String setStartDate(Map<String, String> b) {
        if (currentDay >= 0) throw new ApiError("The start date cannot be changed after the current date has been set.");
        startDate = parseDate(req(b, "startDate"), "Start date");
        return OK;
    }

    static String setInterval(Map<String, String> b) { interval = num(b, "interval", 1); return OK; }
    static String setLMin(Map<String, String> b) { lMin = num(b, "lMin", 0); return OK; }

    static String config() {
        return "{" + kv("startDate", startDate == null ? "" : startDate.toString())
                + "," + kv("currentDay", currentDay) + "," + kv("currentDate", dateOf(currentDay)) + ","
                + kv("interval", interval) + "," + kv("lMin", lMin) + "}";
    }

    // ---------- setup actions ----------
    static boolean idUsed(String id) { return sourceMap.containsKey(id) || areaMap.containsKey(id); }

    static String addSource(Map<String, String> b) {
        String id = req(b, "sourceId");
        if (idUsed(id)) throw new ApiError("Rejected: ID already used by a source or area.");
        WaterSource s = new WaterSource(id, req(b, "sourceName"), num(b, "totalCapacity", 0));
        sources.add(s); sourceMap.put(id, s); network.addLocation(id);
        return OK;
    }

    static String addArea(Map<String, String> b) {
        String id = req(b, "areaId");
        if (idUsed(id)) throw new ApiError("Rejected: ID already used by a source or area.");
        String name = req(b, "areaName"), type = req(b, "type").toUpperCase();
        if (!type.equals("INSTITUTIONAL") && !type.equals("RESIDENTIAL")) throw new ApiError("Type must be INSTITUTIONAL or RESIDENTIAL.");
        int pop = num(b, "population", 0), demand = num(b, "dailyDemand", 0);
        DistributionArea a = type.equals("INSTITUTIONAL") ? new CriticalInstitution(id, name, pop, demand, -1)
                : new ResidentialArea(id, name, pop, demand, -1);
        areaList.add(a); areaMap.put(id, a); network.addLocation(id);
        return OK;
    }

    static String addPipeline(Map<String, String> b) {
        String pid = req(b, "pipelineId"), from = req(b, "fromId"), to = req(b, "toId");
        if (network.hasPipeline(pid)) throw new ApiError("Rejected: pipeline ID already exists.");
        if (!network.hasLocation(from) || !network.hasLocation(to)) throw new ApiError("Rejected: location does not exist.");
        if (from.equals(to)) throw new ApiError("Rejected: From and To must differ.");
        if (sourceMap.containsKey(from) && sourceMap.containsKey(to)) throw new ApiError("Rejected: a pipeline must involve at least one area.");
        if (network.connectionExists(from, to)) throw new ApiError("Rejected: these locations are already connected.");
        network.addConnection(pid, from, to);
        return OK;
    }

    static String removePipeline(String id) {
        if (!network.removeConnection(id)) throw new ApiError("Rejected: pipeline not found.");
        return OK;
    }

    static String oneArea(String id) {
        DistributionArea a = areaMap.get(id);
        if (a == null) throw new ApiError("Area ID not found.");
        return jArea(a);
    }

    static boolean touches(Pipeline p, String areaId) {
        return p.getFromId().equals(areaId) || p.getToId().equals(areaId);
    }

    static List<Pipeline> visiblePipelines(String areaId) {
        if (areaId == null || areaId.isEmpty()) return network.getPipelineList();
        return network.getPipelineList().stream().filter(p -> touches(p, areaId)).collect(Collectors.toList());
    }

    // ---------- maintenance ----------
    static boolean ticketVisible(MaintenanceRequest m, String areaId) {
        if (areaId == null || areaId.isEmpty()) return true;
        if (m.getReportedBy().equals(areaId)) return true;
        Pipeline p = network.getPipeline(m.getPipelineId());
        return p != null && touches(p, areaId);
    }

    // Active tickets most urgent first (highest urgency score, then oldest), then finished tickets.
    static ArrayList<MaintenanceRequest> maintView(String areaId) {
        final int today = currentDay;
        PriorityQueue<MaintenanceRequest> pq = new PriorityQueue<>(
                Comparator.comparingLong((MaintenanceRequest x) -> -x.score(today))
                          .thenComparingInt(MaintenanceRequest::getSeqNo));
        for (MaintenanceRequest m : maintenance)
            if (m.isActive() && ticketVisible(m, areaId)) pq.add(m);
        ArrayList<MaintenanceRequest> list = new ArrayList<>();
        while (!pq.isEmpty()) list.add(pq.poll());
        for (MaintenanceRequest m : maintenance)
            if (!m.isActive() && ticketVisible(m, areaId)) list.add(m);
        return list;
    }

    static String fail(String pid, Map<String, String> b) {
        needDay();
        Pipeline p = network.getPipeline(pid);
        if (p == null) throw new ApiError("Rejected: pipeline not found.");
        String reporter = b.get("areaId");                       // sent by the municipal page; empty for the dam console
        boolean fromArea = reporter != null && !reporter.isEmpty();
        if (fromArea && !touches(p, reporter))
            throw new ApiError("Rejected: this pipeline is not connected to your area (" + reporter + ").");
        String reason = req(b, "reason");
        if (!network.failPipeline(pid, reason)) throw new ApiError("Rejected: pipeline is not WORKING.");
        // Who loses water because of THIS pipeline? (BFS: cut off now, reachable if this pipeline worked)
        int people = 0;
        boolean institution = false;
        for (String id : network.getAreasCutBy(pid, areaIds(), sourceIds())) {
            DistributionArea a = areaMap.get(id);
            people += a.getPopulation();
            if (a.getType().equals("INSTITUTIONAL")) institution = true;
        }
        MaintenanceRequest mr = new MaintenanceRequest("M" + (++maintCounter), pid, "Failure: " + reason,
                fromArea ? reporter : "DAM", currentDay, people, institution);
        maintenance.add(mr);
        return "{\"ok\":true,\"disconnected\":" + arr(network.getAffectedAreas(areaIds(), sourceIds()), Server::q)
                + "," + kv("ticket", mr.getMaintenanceId()) + "," + kv("peopleAffected", people) + "}";
    }

    static String startWork(String id) {
        needDay();
        for (MaintenanceRequest m : maintenance) {
            if (!m.getMaintenanceId().equals(id)) continue;
            if (m.getStatus() != MaintenanceRequest.Status.OPEN) throw new ApiError("Only OPEN tickets can be started.");
            m.start(currentDay);
            return OK;
        }
        throw new ApiError("Maintenance ticket not found.");
    }

    static String repair(String pid, Map<String, String> b) {
        needDay();
        String details = req(b, "details");
        if (!network.repairPipeline(pid, details)) throw new ApiError("Rejected: pipeline not found or not FAILED.");
        for (MaintenanceRequest m : maintenance)
            if (m.getPipelineId().equals(pid) && m.isActive()) m.complete(currentDay, details);
        return OK;
    }

    // ---------- requests and distribution ----------
    static String addRequest(Map<String, String> b) {
        ready();
        String areaId = req(b, "areaId");
        DistributionArea a = areaMap.get(areaId);
        if (a == null) throw new ApiError("Area does not exist.");
        int need = num(b, "requiredWater", 1);
        int n = requestMap.size() + 1;
        while (requestMap.containsKey("R" + n)) n++;
        WaterRequest r = new WaterRequest("R" + n, a, need, "true".equals(b.get("emergency")), ++seqCounter, currentDay);
        policy.enqueue(r, a, interval);
        requestHistory.add(r); requestMap.put(r.getRequestId(), r);
        return OK;
    }

    static String listRequests(String areaId) {
        List<WaterRequest> l = requestHistory.stream()
                .filter(r -> areaId == null || areaId.isEmpty() || r.getAreaId().equals(areaId)).collect(Collectors.toList());
        return arr(l, Server::jReq);
    }

    static String distribute() {
        ready();
        if (!policy.hasQueuedRequests()) throw new ApiError("No requests waiting in the queue.");
        policy.processAll(sources, areaMap, network, currentDay, lMin);
        return OK;
    }

    static List<String> historyLines(String areaId) {
        List<String> out = new ArrayList<>();
        for (HistoryEntry e : policy.getAllocationHistory())
            if (areaId == null || areaId.isEmpty() || e.getAreaId().equals(areaId))
                out.add(dateLabel(e.getDay()) + ": " + e.getMessage());
        return out;
    }

    static ArrayList<String> sourceIds() { ArrayList<String> l = new ArrayList<>(); for (WaterSource s : sources) l.add(s.getSourceId()); return l; }
    static ArrayList<String> areaIds() { ArrayList<String> l = new ArrayList<>(); for (DistributionArea a : areaList) l.add(a.getAreaId()); return l; }

    // ---------- day-wise report ----------
    static class Bucket { final ArrayList<String> reqs = new ArrayList<>(), allocs = new ArrayList<>(), maints = new ArrayList<>(); }

    static boolean areaOk(String filter, String areaId) {
        return filter == null || filter.isEmpty() || filter.equals(areaId);
    }

    static String report(String areaId, Map<String, String> qs) {
        String from = qs.get("from"), to = qs.get("to");
        boolean hasFrom = from != null && !from.isEmpty(), hasTo = to != null && !to.isEmpty();
        int fromDay = Integer.MIN_VALUE, toDay = Integer.MAX_VALUE;
        if (hasFrom || hasTo) {
            if (startDate == null) throw new ApiError("The start date has not been set yet, so dates cannot be used as a filter.");
            if (hasFrom) fromDay = (int) ChronoUnit.DAYS.between(startDate, parseDate(from, "From date"));
            if (hasTo) toDay = (int) ChronoUnit.DAYS.between(startDate, parseDate(to, "To date"));
        }
        TreeMap<Integer, Bucket> days = new TreeMap<>();
        for (WaterRequest r : requestHistory)
            if (areaOk(areaId, r.getAreaId())) days.computeIfAbsent(r.getCreatedDay(), k -> new Bucket()).reqs.add(jReq(r));
        for (HistoryEntry e : policy.getAllocationHistory())
            if (areaOk(areaId, e.getAreaId())) days.computeIfAbsent(e.getDay(), k -> new Bucket()).allocs.add(jHist(e));
        for (MaintenanceRequest m : maintenance) {
            if (!ticketVisible(m, areaId)) continue;
            days.computeIfAbsent(m.getOpenedDay(), k -> new Bucket()).maints.add(jMaintEvent(m, "OPENED", ""));
            if (m.getStartedDay() >= 0)
                days.computeIfAbsent(m.getStartedDay(), k -> new Bucket()).maints.add(jMaintEvent(m, "WORK STARTED", ""));
            if (m.getClosedDay() >= 0)
                days.computeIfAbsent(m.getClosedDay(), k -> new Bucket()).maints.add(jMaintEvent(m, "COMPLETED", m.getResolution()));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, Bucket> e : days.entrySet()) {
            int d = e.getKey();
            if (d < fromDay || d > toDay) continue;
            Bucket bk = e.getValue();
            out.add("{" + kv("day", d) + "," + kv("date", dateOf(d)) + ",\"requests\":[" + String.join(",", bk.reqs)
                    + "],\"allocations\":[" + String.join(",", bk.allocs) + "],\"maintenance\":[" + String.join(",", bk.maints) + "]}");
        }
        return "{" + kv("startDate", startDate == null ? "" : startDate.toString()) + ",\"days\":[" + String.join(",", out) + "]}";
    }

    // ---------- database ----------
    static void persist() {
        try {
            DB.save(sources, areaList, network.getPipelineList(), requestHistory, maintenance,
                    policy.getAllocationHistory(), currentDay, interval, seqCounter, maintCounter, lMin,
                    startDate == null ? null : startDate.toString());
        } catch (Exception e) { System.out.println("Could not save to MySQL: " + e.getMessage()); }
    }

    static int n(Object o) { return ((Number) o).intValue(); }
    static boolean flag(Object o) { return o instanceof Boolean ? (Boolean) o : n(o) != 0; }

    static void restore() {
        if (!DB.enabled) return;
        try {
            for (Object[] r : DB.rows("SELECT source_id,source_name,total_capacity,remaining_water FROM water_source ORDER BY ord")) {
                WaterSource s = new WaterSource((String) r[0], (String) r[1], n(r[2]));
                s.allocateWater(n(r[2]) - n(r[3]));
                sources.add(s); sourceMap.put(s.getSourceId(), s); network.addLocation(s.getSourceId());
            }
            for (Object[] r : DB.rows("SELECT area_id,area_name,area_type,population,daily_demand,last_supply_day,last_delivered FROM distribution_area ORDER BY ord")) {
                DistributionArea a = r[2].equals("INSTITUTIONAL")
                        ? new CriticalInstitution((String) r[0], (String) r[1], n(r[3]), n(r[4]), n(r[5]))
                        : new ResidentialArea((String) r[0], (String) r[1], n(r[3]), n(r[4]), n(r[5]));
                a.updateLastSupply(n(r[5]), n(r[6]));
                areaList.add(a); areaMap.put(a.getAreaId(), a); network.addLocation(a.getAreaId());
            }
            for (Object[] r : DB.rows("SELECT pipeline_id,from_id,to_id,status,last_note FROM pipeline ORDER BY ord")) {
                network.addConnection((String) r[0], (String) r[1], (String) r[2]);
                network.getPipeline((String) r[0]).restore(r[3].equals("FAILED"), (String) r[4]);
            }
            for (Object[] r : DB.rows("SELECT request_id,area_id,required_water,emergency,seq_no,days_since_supply,priority,allocated,unmet,status,created_day,allocated_day FROM water_request ORDER BY seq_no")) {
                DistributionArea a = areaMap.get((String) r[1]);
                WaterRequest q = new WaterRequest((String) r[0], a, n(r[2]), flag(r[3]), n(r[4]), n(r[10]));
                q.restore(n(r[5]), WaterRequest.Priority.valueOf((String) r[6]), n(r[7]), n(r[8]),
                        WaterRequest.Status.valueOf((String) r[9]), n(r[11]));
                requestHistory.add(q); requestMap.put(q.getRequestId(), q);
                if (q.getStatus() == WaterRequest.Status.PENDING) policy.requeue(q);
            }
            for (Object[] r : DB.rows("SELECT maint_id,pipeline_id,issue,status,reported_by,opened_day,started_day,closed_day,resolution,affected_pop,inst_affected FROM maintenance_request ORDER BY ord")) {
                MaintenanceRequest m = new MaintenanceRequest((String) r[0], (String) r[1], (String) r[2],
                        (String) r[4], n(r[5]), n(r[9]), flag(r[10]));
                m.restore(MaintenanceRequest.Status.valueOf((String) r[3]), n(r[6]), n(r[7]), (String) r[8]);
                maintenance.add(m);
            }
            for (Object[] r : DB.rows("SELECT message,day_no,request_id,area_id FROM allocation_history ORDER BY id")) {
                String msg = (String) r[0], rid = (String) r[2], aid = (String) r[3];
                if (rid == null || rid.isEmpty()) rid = msg.split("[ :]")[0];      // rows saved by the older version
                if (aid == null || aid.isEmpty()) { WaterRequest w = requestMap.get(rid); aid = w == null ? "" : w.getAreaId(); }
                policy.getAllocationHistory().add(new HistoryEntry(n(r[1]), rid, aid, msg));
            }
            for (Object[] r : DB.rows("SELECT current_day,supply_interval,seq_counter,maint_counter,start_date,l_min FROM app_state")) {
                currentDay = n(r[0]); interval = n(r[1]); seqCounter = n(r[2]); maintCounter = n(r[3]);
                if (r[4] != null && !((String) r[4]).isEmpty()) startDate = LocalDate.parse((String) r[4]);
                lMin = n(r[5]);
            }
            System.out.println("Loaded saved data from MySQL.");
        } catch (Exception e) { System.out.println("Could not load from MySQL: " + e.getMessage()); }
    }

    // ---------- JSON helpers ----------
    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", " ") + "\"";
    }
    static String kv(String k, String v) { return q(k) + ":" + q(v == null ? "" : v); }
    static String kv(String k, int v) { return q(k) + ":" + v; }
    static String kv(String k, long v) { return q(k) + ":" + v; }
    static String kv(String k, boolean v) { return q(k) + ":" + v; }
    static <T> String arr(Collection<T> c, Function<T, String> f) {
        return "[" + c.stream().map(f).collect(Collectors.joining(",")) + "]";
    }
    static String jSource(WaterSource s) {
        return "{" + kv("sourceId", s.getSourceId()) + "," + kv("sourceName", s.getSourceName()) + ","
                + kv("totalCapacity", s.getTotalCapacity()) + "," + kv("remainingWater", s.getRemainingWater()) + "}";
    }
    static String jArea(DistributionArea a) {
        return "{" + kv("areaId", a.getAreaId()) + "," + kv("areaName", a.getAreaName()) + "," + kv("type", a.getType()) + ","
                + kv("population", a.getPopulation()) + "," + kv("dailyDemand", a.getDailyDemand()) + ","
                + kv("lastSupplyDay", a.getLastSupplyDay()) + "," + kv("lastDeliveredAmount", a.getLastDeliveredAmount()) + "}";
    }
    static String jPipe(Pipeline p) {
        return "{" + kv("pipelineId", p.getPipelineId()) + "," + kv("fromId", p.getFromId()) + "," + kv("toId", p.getToId()) + ","
                + kv("status", p.getStatus().name()) + "," + kv("lastNote", p.getLastNote()) + "}";
    }
    static String jReq(WaterRequest r) {
        return "{" + kv("requestId", r.getRequestId()) + "," + kv("areaId", r.getAreaId()) + "," + kv("requiredWater", r.getRequiredWater()) + ","
                + kv("emergency", r.isEmergency()) + "," + kv("priority", r.getPriority().name()) + ","
                + kv("allocatedWater", r.getAllocatedWater()) + "," + kv("unmetWater", r.getUnmetWater()) + ","
                + kv("status", r.getStatus().name()) + "," + kv("requestedOn", dateLabel(r.getCreatedDay())) + "}";
    }
    static String jHist(HistoryEntry e) {
        return "{" + kv("requestId", e.getRequestId()) + "," + kv("areaId", e.getAreaId()) + "," + kv("message", e.getMessage()) + "}";
    }
    static String jMaint(MaintenanceRequest m) {
        return "{" + kv("maintenanceId", m.getMaintenanceId()) + "," + kv("pipelineId", m.getPipelineId()) + ","
                + kv("issue", m.getIssue()) + "," + kv("reportedBy", m.getReportedBy()) + ","
                + kv("status", m.getStatus().name()) + "," + kv("score", m.score(currentDay)) + ","
                + kv("peopleAffected", m.getAffectedPopulation()) + "," + kv("institution", m.isInstitutionAffected()) + ","
                + kv("daysOpen", m.daysOpen(currentDay)) + "," + kv("openedOn", dateLabel(m.getOpenedDay())) + ","
                + kv("startedOn", dateLabel(m.getStartedDay())) + "," + kv("closedOn", dateLabel(m.getClosedDay())) + ","
                + kv("resolution", m.getResolution()) + "}";
    }
    static String jMaintEvent(MaintenanceRequest m, String event, String note) {
        return "{" + kv("maintenanceId", m.getMaintenanceId()) + "," + kv("pipelineId", m.getPipelineId()) + ","
                + kv("event", event) + "," + kv("issue", m.getIssue()) + "," + kv("reportedBy", m.getReportedBy()) + ","
                + kv("note", note) + "}";
    }

    static String req(Map<String, String> b, String k) {
        String v = b.get(k);
        if (v == null || v.trim().isEmpty()) throw new ApiError(k + " is required.");
        return v.trim();
    }
    static int num(Map<String, String> b, String k, int min) {
        try {
            int v = Integer.parseInt(req(b, k));
            if (v < min) throw new ApiError(k + " must be at least " + min + ".");
            return v;
        } catch (NumberFormatException e) { throw new ApiError(k + " must be a whole number."); }
    }

    // reads a flat JSON object like {"a":"x","b":5,"c":true}
    static Map<String, String> json(String s) {
        Map<String, String> m = new HashMap<>();
        int i = 0, len = s.length();
        int[] e = new int[1];
        while (i < len) {
            int k = s.indexOf('"', i);
            if (k < 0) break;
            String key = str(s, k, e); i = e[0];
            int c = s.indexOf(':', i);
            if (c < 0) break;
            i = c + 1;
            while (i < len && s.charAt(i) == ' ') i++;
            if (i >= len) break;
            if (s.charAt(i) == '"') { m.put(key, str(s, i, e)); i = e[0]; }
            else { int j = i; while (j < len && ",}".indexOf(s.charAt(j)) < 0) j++; m.put(key, s.substring(i, j).trim()); i = j; }
        }
        return m;
    }
    static String str(String s, int start, int[] end) {
        StringBuilder b = new StringBuilder();
        int i = start + 1;
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\' && i < s.length()) { char d = s.charAt(i++); b.append(d == 'n' ? '\n' : d == 't' ? '\t' : d); }
            else b.append(c);
        }
        end[0] = i + 1;
        return b.toString();
    }
    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            m.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }
}