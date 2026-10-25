package org.example;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * ★エコ変更: エコ清掃データベース（cleaning_schedule）への書き戻し。
 * 「清掃割り当て調整」画面で通常清掃→エコ清掃に変更した部屋を、対象日の状態「×」として保存し、
 * 取り消し時は変更前の状態（レコード無しの場合は行の削除）に戻す。
 */
final class EcoDatabaseWriter {

    /** エコ清掃を表す状態値 */
    static final String ECO_STATUS = "×";

    private EcoDatabaseWriter() {
    }

    /** メイン画面で選択されたエコ清掃データベース（未設定・存在しない場合は null） */
    static File getDatabaseFile() {
        String dbPath = System.getProperty("ecoDataFile");
        if (dbPath == null || dbPath.isEmpty()) {
            return null;
        }
        File dbFile = new File(dbPath);
        return dbFile.exists() ? dbFile : null;
    }

    /**
     * 対象日の指定部屋をエコ清掃（×）にする。
     *
     * @return 部屋番号 → 変更前の状態（レコードが無かった場合は null）
     */
    static Map<String, String> markEco(File dbFile, LocalDate date, Collection<String> roomNumbers)
            throws SQLException {
        Map<String, String> previous = new HashMap<>();
        if (roomNumbers.isEmpty()) {
            return previous;
        }
        String dateStr = toDateString(date);

        try (Connection conn = open(dbFile)) {
            conn.setAutoCommit(false);
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT cleaning_status FROM cleaning_schedule WHERE room_number = ? AND cleaning_date = ? LIMIT 1");
                 PreparedStatement update = conn.prepareStatement(
                         "UPDATE cleaning_schedule SET cleaning_status = ? WHERE room_number = ? AND cleaning_date = ?");
                 PreparedStatement insert = conn.prepareStatement(
                         "INSERT INTO cleaning_schedule (room_number, cleaning_date, cleaning_status) VALUES (?, ?, ?)")) {

                for (String roomNumber : roomNumbers) {
                    select.setString(1, roomNumber);
                    select.setString(2, dateStr);
                    boolean exists;
                    String status = null;
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                        if (exists) {
                            status = rs.getString(1);
                        }
                    }

                    if (exists) {
                        update.setString(1, ECO_STATUS);
                        update.setString(2, roomNumber);
                        update.setString(3, dateStr);
                        update.executeUpdate();
                    } else {
                        insert.setString(1, roomNumber);
                        insert.setString(2, dateStr);
                        insert.setString(3, ECO_STATUS);
                        insert.executeUpdate();
                    }
                    previous.put(roomNumber, status);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return previous;
    }

    /**
     * 対象日の指定部屋を変更前の状態に戻す。
     *
     * @param previousStatus 部屋番号 → 変更前の状態（null ならエコ変更で追加した行を削除）
     */
    static void restore(File dbFile, LocalDate date, Map<String, String> previousStatus) throws SQLException {
        if (previousStatus.isEmpty()) {
            return;
        }
        String dateStr = toDateString(date);

        try (Connection conn = open(dbFile)) {
            conn.setAutoCommit(false);
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE cleaning_schedule SET cleaning_status = ? WHERE room_number = ? AND cleaning_date = ?");
                 PreparedStatement delete = conn.prepareStatement(
                         "DELETE FROM cleaning_schedule WHERE room_number = ? AND cleaning_date = ?")) {

                for (Map.Entry<String, String> entry : previousStatus.entrySet()) {
                    if (entry.getValue() == null) {
                        delete.setString(1, entry.getKey());
                        delete.setString(2, dateStr);
                        delete.executeUpdate();
                    } else {
                        update.setString(1, entry.getValue());
                        update.setString(2, entry.getKey());
                        update.setString(3, dateStr);
                        update.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private static Connection open(File dbFile) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLiteドライバが見つかりません", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    private static String toDateString(LocalDate date) {
        return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
