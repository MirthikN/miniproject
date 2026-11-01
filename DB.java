import java.sql.*;
import java.util.*;

public class DB {
    // ---- change these three lines to match your MySQL ----
    static final String URL  = "jdbc:mysql://localhost:3306/water_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String USER = "root";
    static final String PASS = "mirthik2008";

    static boolean enabled = false;
    private static Connection con;

    static void connect() {
        try {
            con = DriverManager.getConnection(URL, USER, PASS);
            migrate();
            enabled = true;
            System.out.println("Connected to MySQL (water_db).");
        } catch (SQLException e) {
            System.out.println("MySQL not available: " + e.getMessage());
            System.out.println("Running WITHOUT saving. Check lib/ driver, schema.sql, and the user/password in DB.java.");
        }
    }

    // Adds the columns introduced by newer versions to an existing database, so old data keeps working.
    private static void migrate() throws SQLException {
        addColumn("water_request", "created_day", "INT NOT NULL DEFAULT 0");
        addColumn("water_request", "allocated_day", "INT NOT NULL DEFAULT -1");
        addColumn("maintenance_request", "reported_by", "VARCHAR(30) NULL");
        addColumn("maintenance_request", "opened_day", "INT NOT NULL DEFAULT 0");
        addColumn("maintenance_request", "started_day", "INT NOT NULL DEFAULT -1");
        addColumn("maintenance_request", "closed_day", "INT NOT NULL DEFAULT -1");
        addColumn("maintenance_request", "resolution", "VARCHAR(255) NULL");
        addColumn("maintenance_request", "affected_pop", "INT NOT NULL DEFAULT 0");
        addColumn("maintenance_request", "inst_affected", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumn("allocation_history", "day_no", "INT NOT NULL DEFAULT 0");
        addColumn("allocation_history", "request_id", "VARCHAR(30) NULL");
        addColumn("allocation_history", "area_id", "VARCHAR(30) NULL");
        addColumn("app_state", "start_date", "VARCHAR(10) NULL");
        addColumn("app_state", "l_min", "INT NOT NULL DEFAULT 20");
    }

    private static void addColumn(String table, String column, String definition) throws SQLException {
        List<Object[]> r = rows("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'");
        if (((Number) r.get(0)[0]).intValue() == 0) {
            try (Statement st = con.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
            System.out.println("Database updated: added " + table + "." + column);
        }
    }

    static List<Object[]> rows(String sql) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] r = new Object[n];
                for (int i = 0; i < n; i++) r[i] = rs.getObject(i + 1);
                out.add(r);
            }
        }
        return out;
    }

    private static void ins(String sql, Object... v) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < v.length; i++) ps.setObject(i + 1, v[i]);
            ps.executeUpdate();
        }
    }

    // Saves the whole current state in one transaction.
    static void save(List<WaterSource> sources, List<DistributionArea> areas, List<Pipeline> pipes,
                     List<WaterRequest> reqs, Collection<MaintenanceRequest> maint, List<HistoryEntry> history,
                     int day, int interval, int seq, int mseq, int lMin, String startDate) throws SQLException {
        if (!enabled) return;
        try {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                for (String t : new String[]{"water_source", "distribution_area", "pipeline", "water_request",
                        "maintenance_request", "allocation_history", "app_state"})
                    st.executeUpdate("DELETE FROM " + t);
            }
            int i = 0;
            for (WaterSource s : sources)
                ins("INSERT INTO water_source (source_id, source_name, total_capacity, remaining_water, ord) VALUES (?,?,?,?,?)",
                        s.getSourceId(), s.getSourceName(), s.getTotalCapacity(), s.getRemainingWater(), i++);
            i = 0;
            for (DistributionArea a : areas)
                ins("INSERT INTO distribution_area (area_id, area_name, area_type, population, daily_demand, last_supply_day,"
                        + " last_delivered, ord) VALUES (?,?,?,?,?,?,?,?)",
                        a.getAreaId(), a.getAreaName(), a.getType(), a.getPopulation(), a.getDailyDemand(),
                        a.getLastSupplyDay(), a.getLastDeliveredAmount(), i++);
            i = 0;
            for (Pipeline p : pipes)
                ins("INSERT INTO pipeline (pipeline_id, from_id, to_id, status, last_note, ord) VALUES (?,?,?,?,?,?)",
                        p.getPipelineId(), p.getFromId(), p.getToId(), p.getStatus().name(), p.getLastNote(), i++);
            for (WaterRequest r : reqs)
                ins("INSERT INTO water_request (request_id, area_id, required_water, emergency, seq_no, days_since_supply,"
                        + " priority, allocated, unmet, status, created_day, allocated_day) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        r.getRequestId(), r.getAreaId(), r.getRequiredWater(), r.isEmergency(), r.getSequenceNumber(),
                        r.getDaysSinceLastSupply(), r.getPriority().name(), r.getAllocatedWater(), r.getUnmetWater(),
                        r.getStatus().name(), r.getCreatedDay(), r.getAllocatedDay());
            i = 0;
            for (MaintenanceRequest m : maint)
                ins("INSERT INTO maintenance_request (maint_id, pipeline_id, issue, status, ord, reported_by, opened_day,"
                        + " started_day, closed_day, resolution, affected_pop, inst_affected) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        m.getMaintenanceId(), m.getPipelineId(), m.getIssue(), m.getStatus().name(), i++, m.getReportedBy(),
                        m.getOpenedDay(), m.getStartedDay(), m.getClosedDay(), m.getResolution(),
                        m.getAffectedPopulation(), m.isInstitutionAffected());
            for (HistoryEntry h : history)
                ins("INSERT INTO allocation_history (message, day_no, request_id, area_id) VALUES (?,?,?,?)",
                        h.getMessage(), h.getDay(), h.getRequestId(), h.getAreaId());
            ins("INSERT INTO app_state (id, current_day, supply_interval, seq_counter, maint_counter, start_date, l_min)"
                    + " VALUES (1,?,?,?,?,?,?)", day, interval, seq, mseq, startDate, lMin);
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(true);
        }
    }
}