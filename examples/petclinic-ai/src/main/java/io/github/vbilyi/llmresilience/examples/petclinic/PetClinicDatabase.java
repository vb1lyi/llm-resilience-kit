package io.github.vbilyi.llmresilience.examples.petclinic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * JDBC access to the SUT's Postgres database, for side-effect checks and the per-test reset.
 *
 * <p>The SUT seeds its data idempotently on every start ({@code db/postgres/data.sql}): 10 owners, 13 pets
 * and 4 visits with ids starting at 1. Its chatbot tools only ever <em>insert</em> owners and pets (spike Q5),
 * so deleting every row above the seed ids brings the database back to the seed state.
 */
public final class PetClinicDatabase {

    /** Highest seed id in {@code owners}. */
    public static final int SEED_OWNERS = 10;

    /** Highest seed id in {@code pets}. */
    public static final int SEED_PETS = 13;

    /** Highest seed id in {@code visits}. */
    public static final int SEED_VISITS = 4;

    private static final Set<String> TABLES = Set.of("owners", "pets", "visits", "vets", "specialties", "types");

    private final String jdbcUrl;
    private final String username;
    private final String password;

    PetClinicDatabase(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Opens a new connection; the caller closes it.
     *
     * @return a connection to the SUT database
     * @throws SQLException if the database is unreachable
     */
    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /**
     * Counts rows in one of the PetClinic tables.
     *
     * @param table table name, e.g. {@code owners}
     * @return the row count
     */
    public long count(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Unknown PetClinic table: " + table);
        }
        try (Connection c = openConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count rows in " + table, e);
        }
    }

    /** Deletes every row above the seed ids (children first), in one transaction. */
    public void resetToSeed() {
        try (Connection c = openConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM visits WHERE id > " + SEED_VISITS + " OR pet_id > " + SEED_PETS);
                s.executeUpdate("DELETE FROM pets WHERE id > " + SEED_PETS + " OR owner_id > " + SEED_OWNERS);
                s.executeUpdate("DELETE FROM owners WHERE id > " + SEED_OWNERS);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot reset the SUT database to its seed", e);
        }
    }
}
