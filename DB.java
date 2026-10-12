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
            enabled = true;
            System.out.println("Connected to MySQL (water_db).");
        } catch (SQLException e) {
            System.out.println("MySQL not available: " + e.getMessage());
            System.out.println("Running WITHOUT saving. Check lib/ driver, schema.sql, and the user/password in DB.java.");
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
                     List<WaterRequest> reqs, Collection<MaintenanceRequest> maint, List<String> history,
                     int day, int interval, int seq, int mseq) throws SQLException {
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
                ins("INSERT INTO water_source VALUES (?,?,?,?,?)", s.getSourceId(), s.getSourceName(),
                        s.getTotalCapacity(), s.getRemainingWater(), i++);
            i = 0;
            for (DistributionArea a : areas)
                ins("INSERT INTO distribution_area VALUES (?,?,?,?,?,?,?,?)", a.getAreaId(), a.getAreaName(), a.getType(),
                        a.getPopulation(), a.getDailyDemand(), a.getLastSupplyDay(), a.getLastDeliveredAmount(), i++);
            i = 0;
            for (Pipeline p : pipes)
                ins("INSERT INTO pipeline VALUES (?,?,?,?,?,?)", p.getPipelineId(), p.getFromId(), p.getToId(),
                        p.getStatus().name(), p.getLastNote(), i++);
            for (WaterRequest r : reqs)
                ins("INSERT INTO water_request VALUES (?,?,?,?,?,?,?,?,?,?)", r.getRequestId(), r.getAreaId(),
                        r.getRequiredWater(), r.isEmergency(), r.getSequenceNumber(), r.getDaysSinceLastSupply(),
                        r.getPriority().name(), r.getAllocatedWater(), r.getUnmetWater(), r.getStatus().name());
            i = 0;
            for (MaintenanceRequest m : maint)
                ins("INSERT INTO maintenance_request VALUES (?,?,?,?,?)", m.getMaintenanceId(), m.getPipelineId(),
                        m.getIssue(), m.getStatus().name(), i++);
            for (String h : history) ins("INSERT INTO allocation_history (message) VALUES (?)", h);
            ins("INSERT INTO app_state VALUES (1,?,?,?,?)", day, interval, seq, mseq);
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(true);
        }
    }
}
