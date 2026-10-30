package org.example;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * ★CSV共有: EcoRoomClean.py がCSV読込時にエコ清掃データベース（shared_csv テーブル）へ保存した
 * CSVの中身を読み出す。部屋状態CSV・予約CSV・予約詳細CSVを、こちらで選び直さずに使うためのもの。
 */
final class SharedCsvStore {

    /** 部屋状態CSV */
    static final String KIND_ROOM_STATUS = "room_status";
    /** 予約CSV（宿泊者名・プラン） */
    static final String KIND_YOYAKU = "yoyaku";
    /** 予約詳細CSV（C/I日・C/O日・泊数） */
    static final String KIND_YOYAKU_CO = "yoyaku_co";

    /** 共有されたCSV1件分 */
    static final class SharedCsv {
        final String kind;
        final String fileName;
        /** CSV 1行目1列目の日付（部屋状態CSVなら清掃日）。取れなければ null */
        final LocalDate csvDate;
        /** Eco側で読み込んだ日時（表示用の文字列） */
        final String importedAt;
        final String content;

        SharedCsv(String kind, String fileName, LocalDate csvDate, String importedAt, String content) {
            this.kind = kind;
            this.fileName = fileName;
            this.csvDate = csvDate;
            this.importedAt = importedAt;
            this.content = content;
        }
    }

    private SharedCsvStore() {
    }

    /**
     * データベースに保存された共有CSVを種別ごとに読み込む。
     * テーブルが無い（Eco側が古い・まだCSVを読み込んでいない）場合は空のマップを返す。
     */
    static Map<String, SharedCsv> load(File dbFile) throws SQLException {
        Map<String, SharedCsv> result = new HashMap<>();
        try (Connection conn = open(dbFile)) {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'shared_csv'");
                 ResultSet rs = check.executeQuery()) {
                if (!rs.next()) {
                    return result;
                }
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT kind, file_name, csv_date, imported_at, content FROM shared_csv");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String content = rs.getString("content");
                    if (content == null || content.isEmpty()) {
                        continue;
                    }
                    String kind = rs.getString("kind");
                    result.put(kind, new SharedCsv(kind, rs.getString("file_name"),
                            parseDate(rs.getString("csv_date")), rs.getString("imported_at"), content));
                }
            }
        }
        return result;
    }

    /**
     * 共有CSVを一時ファイル（UTF-8）に書き出す。
     * 既存の読込処理・各設定ダイアログはファイルを受け取るため、そのまま渡せる形にする。
     */
    static File writeTempFile(SharedCsv csv) throws IOException {
        File temp = File.createTempFile("shared_" + csv.kind + "_", ".csv");
        temp.deleteOnExit();
        Files.writeString(temp.toPath(), csv.content, StandardCharsets.UTF_8);
        return temp;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
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
}
