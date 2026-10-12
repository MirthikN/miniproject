import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Server {
    static int currentDay = -1, interval = -1, seqCounter = 0, maintCounter = 0;
    static final ArrayList<WaterSource> sources = new ArrayList<>();
    static final HashMap<String, WaterSource> sourceMap = new HashMap<>();
    static final ArrayList<DistributionArea> areaList = new ArrayList<>();
    static final HashMap<String, DistributionArea> areaMap = new HashMap<>();
    static final ArrayList<WaterRequest> requestHistory = new ArrayList<>();
    static final HashMap<String, WaterRequest> requestMap = new HashMap<>();
    static final ArrayDeque<MaintenanceRequest> maintenance = new ArrayDeque<>();
    static final WaterNetwork network = new WaterNetwork();
    static final WaterAllocationPolicy policy = new WaterAllocationPolicy();

    static class ApiError extends RuntimeException { ApiError(String m) { super(m); } }

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
            send(ex, 400, "{\"error\":" + q(e.getMessage()) + "}", "application/json");
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
    static void api(HttpExchange ex, String p) throws IOException {
        String m = ex.getRequestMethod();
        Map<String, String> body = m.equals("POST")
                ? json(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)) : new HashMap<>();
        Map<String, String> qs = query(ex.getRequestURI().getRawQuery());
        String[] s = p.substring(1).split("/");
        String out;
        switch (s[0]) {
            case "sources":     out = m.equals("POST") ? addSource(body) : arr(sources, Server::jSource); break;
            case "areas":       out = m.equals("POST") ? addArea(body) : arr(areaList, Server::jArea); break;
            case "pipelines":
                if (s.length == 1) out = m.equals("POST") ? addPipeline(body) : arr(network.getPipelineList(), Server::jPipe);
                else if (m.equals("DELETE")) out = removePipeline(s[1]);
                else if (s.length == 3 && s[2].equals("fail")) out = fail(s[1], body);
                else if (s.length == 3 && s[2].equals("repair")) out = repair(s[1], body);
                else throw new ApiError("Unknown pipeline action.");
                break;
            case "requests":    out = m.equals("POST") ? addRequest(body) : listRequests(qs.get("areaId")); break;
            case "distribute":  out = distribute(); break;
            case "connectivity": out = "{\"disconnected\":" + arr(network.getAffectedAreas(areaIds(), sourceIds()), Server::q) + "}"; break;
            case "history":     out = arr(history(qs.get("areaId")), Server::q); break;
            case "maintenance": out = arr(maintenance, Server::jMaint); break;
            case "interval":    out = setInterval(body); break;
            case "day":         out = setDay(body); break;
            default: throw new ApiError("Unknown endpoint: " + p);
        }
        if (!m.equals("GET")) persist();
        send(ex, 200, out, "application/json");
    }

    // ---------- actions (same rules as Main.java) ----------
    static final String OK = "{\"ok\":true}";

    static void ready() {
        if (currentDay < 0) throw new ApiError("The current day must be set first (municipal page, Set day).");
        if (interval < 1) throw new ApiError("The dam user must set the normal supply interval first.");
    }
    static boolean idUsed(String id) { return sourceMap.containsKey(id) || areaMap.containsKey(id); }

    static String setDay(Map<String, String> b) {
        int d = num(b, "day", 0);
        for (DistributionArea a : areaList)
            if (a.getLastSupplyDay() > d)
                throw new ApiError("Rejected: area " + a.getAreaId() + " has last supply day " + a.getLastSupplyDay() + " (later than " + d + ").");
        currentDay = d;
        return OK;
    }
    static String setInterval(Map<String, String> b) { interval = num(b, "interval", 1); return OK; }

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

    static String fail(String pid, Map<String, String> b) {
        Pipeline p = network.getPipeline(pid);
        if (p == null) throw new ApiError("Rejected: pipeline not found.");
        String by = b.get("reportedBy");
        if (by != null && !by.isEmpty() && !p.getFromId().equals(by) && !p.getToId().equals(by))
            throw new ApiError("Rejected: this pipeline is not connected to your area (" + by + ").");
        String reason = req(b, "reason");
        if (!network.failPipeline(pid, reason)) throw new ApiError("Rejected: pipeline is not WORKING.");
        maintenance.add(new MaintenanceRequest("M" + (++maintCounter), pid, "Failure: " + reason));
        return "{\"ok\":true,\"disconnected\":" + arr(network.getAffectedAreas(areaIds(), sourceIds()), Server::q) + "}";
    }

    static String repair(String pid, Map<String, String> b) {
        String details = req(b, "details");
        if (!network.repairPipeline(pid, details)) throw new ApiError("Rejected: pipeline not found or not FAILED.");
        for (MaintenanceRequest m : maintenance)
            if (m.getPipelineId().equals(pid) && m.getStatus() == MaintenanceRequest.Status.OPEN) m.complete();
        MaintenanceRequest done = new MaintenanceRequest("M" + (++maintCounter), pid, "Repair: " + details);
        done.complete();
        maintenance.add(done);
        return OK;
    }

    static String addRequest(Map<String, String> b) {
        ready();
        DistributionArea a = areaMap.get(req(b, "areaId"));
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
                .filter(r -> areaId == null || r.getAreaId().equals(areaId)).collect(Collectors.toList());
        return arr(l, Server::jReq);
    }

    static String distribute() {
        ready();
        if (!policy.hasQueuedRequests()) throw new ApiError("No requests waiting in the queue.");
        policy.processAll(sources, areaMap, network, currentDay);
        return OK;
    }

    static List<String> history(String areaId) {
        if (areaId == null) return policy.getAllocationHistory();
        List<String> out = new ArrayList<>();
        for (String line : policy.getAllocationHistory())
            for (WaterRequest r : requestHistory)
                if (r.getAreaId().equals(areaId) && (line.startsWith(r.getRequestId() + " ") || line.startsWith(r.getRequestId() + ":"))) {
                    out.add(line); break;
                }
        return out;
    }

    static ArrayList<String> sourceIds() { ArrayList<String> l = new ArrayList<>(); for (WaterSource s : sources) l.add(s.getSourceId()); return l; }
    static ArrayList<String> areaIds() { ArrayList<String> l = new ArrayList<>(); for (DistributionArea a : areaList) l.add(a.getAreaId()); return l; }

    // ---------- database ----------
    static void persist() {
        try {
            DB.save(sources, areaList, network.getPipelineList(), requestHistory, maintenance,
                    policy.getAllocationHistory(), currentDay, interval, seqCounter, maintCounter);
        } catch (Exception e) { System.out.println("Could not save to MySQL: " + e.getMessage()); }
    }

    static int n(Object o) { return ((Number) o).intValue(); }

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
            for (Object[] r : DB.rows("SELECT request_id,area_id,required_water,emergency,seq_no,days_since_supply,priority,allocated,unmet,status FROM water_request ORDER BY seq_no")) {
                DistributionArea a = areaMap.get((String) r[1]);
                boolean em = r[3] instanceof Boolean ? (Boolean) r[3] : n(r[3]) != 0;
                WaterRequest q = new WaterRequest((String) r[0], a, n(r[2]), em, n(r[4]), 0);
                q.restore(n(r[5]), WaterRequest.Priority.valueOf((String) r[6]), n(r[7]), n(r[8]), WaterRequest.Status.valueOf((String) r[9]));
                requestHistory.add(q); requestMap.put(q.getRequestId(), q);
                if (q.getStatus() == WaterRequest.Status.PENDING) policy.requeue(q);
            }
            for (Object[] r : DB.rows("SELECT maint_id,pipeline_id,issue,status FROM maintenance_request ORDER BY ord")) {
                MaintenanceRequest m = new MaintenanceRequest((String) r[0], (String) r[1], (String) r[2]);
                if (r[3].equals("COMPLETED")) m.complete();
                maintenance.add(m);
            }
            for (Object[] r : DB.rows("SELECT message FROM allocation_history ORDER BY id")) policy.getAllocationHistory().add((String) r[0]);
            for (Object[] r : DB.rows("SELECT current_day,supply_interval,seq_counter,maint_counter FROM app_state")) {
                currentDay = n(r[0]); interval = n(r[1]); seqCounter = n(r[2]); maintCounter = n(r[3]);
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
                + kv("allocatedWater", r.getAllocatedWater()) + "," + kv("unmetWater", r.getUnmetWater()) + "," + kv("status", r.getStatus().name()) + "}";
    }
    static String jMaint(MaintenanceRequest m) {
        return "{" + kv("maintenanceId", m.getMaintenanceId()) + "," + kv("pipelineId", m.getPipelineId()) + ","
                + kv("issue", m.getIssue()) + "," + kv("status", m.getStatus().name()) + "}";
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
