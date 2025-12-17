package org.example;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class FileProcessor {
    private static final Logger LOGGER = Logger.getLogger(FileProcessor.class.getName());

    // ★追加: 部屋状態情報を保存するマップ
    private static final Map<String, String> ROOM_STATUS_MAP = new HashMap<>();

    // CleaningDataクラスを内部クラスとして定義（修正版）
    public static class CleaningData {
        public final List<Room> mainRooms;
        public final List<Room> annexRooms;
        public final List<Room> ecoRooms;
        public final List<Room> brokenRooms;
        public final List<Room> roomsToClean;  // 清掃対象の全部屋リスト
        public final int totalMainRooms;
        public final int totalAnnexRooms;
        public final int totalBrokenRooms;
        public final List<String> ecoWarnings;  // ★追加: エコ清掃警告リスト

        public CleaningData(List<Room> mainRooms, List<Room> annexRooms,
                            List<Room> ecoRooms, List<Room> brokenRooms, List<String> ecoWarnings) {
            this.mainRooms = mainRooms;
            this.annexRooms = annexRooms;
            this.ecoRooms = ecoRooms;
            this.brokenRooms = brokenRooms;
            this.ecoWarnings = ecoWarnings != null ? ecoWarnings : new ArrayList<>();

            // 清掃対象の全部屋リストを作成
            this.roomsToClean = new ArrayList<>();
            this.roomsToClean.addAll(mainRooms);
            this.roomsToClean.addAll(annexRooms);

            this.totalMainRooms = mainRooms.size();
            this.totalAnnexRooms = annexRooms.size();
            this.totalBrokenRooms = brokenRooms.size();
        }

        // 後方互換性のためのコンストラクタ
        public CleaningData(List<Room> mainRooms, List<Room> annexRooms,
                            List<Room> ecoRooms, List<Room> brokenRooms) {
            this(mainRooms, annexRooms, ecoRooms, brokenRooms, new ArrayList<>());
        }
    }

    // ★修正版Roomクラス（部屋状態情報追加）
    public static class Room {
        public final String roomNumber;
        public final String roomType;
        public final boolean isEcoClean;
        public final boolean isEco;  // エコ清掃フラグ（RoomNumberAssigner用）
        public final boolean isBroken;
        public final int floor;
        public final String building;  // 建物情報
        public final String roomStatus;  // ★追加: 部屋状態（連泊/チェックアウト）

        public Room(String roomNumber, String roomType, boolean isEcoClean, boolean isBroken, String roomStatus) {
            this.roomNumber = roomNumber;
            this.roomType = roomType;
            this.isEcoClean = isEcoClean;
            this.isEco = isEcoClean;  // isEcoCleanと同じ値を設定
            this.isBroken = isBroken;
            this.floor = extractFloor(roomNumber);
            this.building = isAnnexRoom(roomNumber) ? "別館" : "本館";
            this.roomStatus = roomStatus;  // ★追加: 部屋状態を保存
        }

        // 後方互換性のためのコンストラクタ
        public Room(String roomNumber, String roomType, boolean isEcoClean, boolean isBroken) {
            this(roomNumber, roomType, isEcoClean, isBroken, "");
        }

        /**
         * ★追加: 部屋状態の表示形式取得
         */
        public String getStatusDisplay() {
            switch (roomStatus) {
                case "2": return "チェックアウト";
                case "3": return "連泊";
                case "4": return "清掃要";  // ★追加: 状態4
                default: return roomStatus.isEmpty() ? "不明" : roomStatus;
            }
        }

        @Override
        public String toString() {
            return roomNumber + " (" + roomType + ", " + floor + "階" +
                    (isEcoClean ? ", エコ清掃" : "") +
                    (isBroken ? ", 故障" : "") +
                    ", " + building +
                    (!roomStatus.isEmpty() ? ", " + getStatusDisplay() : "") + ")";
        }
    }

    /**
     * ★追加: 部屋状態情報を取得する静的メソッド
     */
    public static String getRoomStatus(String roomNumber) {
        return ROOM_STATUS_MAP.getOrDefault(roomNumber, "");
    }

    // ★修正: 部屋番号から階数を抽出するメソッド（別館対応）
    private static int extractFloor(String roomNumber) {
        try {
            // 数字以外を削除
            String numericPart = roomNumber.replaceAll("[^0-9]", "");

            // 3桁の数字（本館1-9階）
            if (numericPart.length() == 3) {
                return Integer.parseInt(numericPart.substring(0, 1));
            }
            // 4桁の数字で10で始まる場合（本館10階）
            else if (numericPart.length() == 4 && numericPart.startsWith("10")) {
                return 10;
            }
            // 4桁の数字（別館）- 最初の2桁を階数として使用
            else if (numericPart.length() == 4) {
                return Integer.parseInt(numericPart.substring(0, 2));
            }

            // パターンに一致しない場合は0を返す
            return 0;
        } catch (Exception e) {
            LOGGER.warning("階数の抽出に失敗しました: " + roomNumber);
            return 0;
        }
    }

    // ★修正: ファイルから部屋データを読み込むメソッド（対象日付指定版）
    public static CleaningData processRoomFile(File file, LocalDate targetDate) {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".csv")) {
            return processCsvRoomFile(file, targetDate);
        } else {
            throw new IllegalArgumentException("サポートされていないファイル形式です: " + fileName);
        }
    }

    // 後方互換性のため（対象日=今日）
    public static CleaningData processRoomFile(File file) {
        return processRoomFile(file, LocalDate.now());
    }

    // ★部屋状態情報完全対応 + デバッグログ強化版 + 対象日付対応 + エコ清掃検証機能追加
    private static CleaningData processCsvRoomFile(File file, LocalDate targetDate) {
        List<Room> mainRooms = new ArrayList<>();
        List<Room> annexRooms = new ArrayList<>();
        List<Room> ecoRooms = new ArrayList<>();
        List<Room> brokenRooms = new ArrayList<>();
        List<String> ecoWarnings = new ArrayList<>();  // ★追加: 警告リスト

        // ★追加: 全部屋のマップを作成（部屋番号 → Room オブジェクト）
        Map<String, Room> allRoomsMap = new HashMap<>();

        Map<String, List<String>> ecoRoomMap = loadEcoRoomInfo(targetDate);

        // 部屋状態マップをクリア
        ROOM_STATUS_MAP.clear();

        // 選択された故障部屋を取得
        Set<String> selectedBrokenRooms = getSelectedBrokenRooms();

        // ★デバッグ用カウンター
        int totalLinesProcessed = 0;
        int emptyRoomNumbers = 0;
        int brokenRoomsSkipped = 0;
        int cleaningStatusSkipped = 0;
        int successfullyAdded = 0;

        if (!selectedBrokenRooms.isEmpty()) {
            LOGGER.info("清掃対象として選択された故障部屋: " + selectedBrokenRooms.size() + "室");
            selectedBrokenRooms.forEach(room -> LOGGER.info("  - " + room));
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;

            LOGGER.info("CSVファイルの読み込みを開始: " + file.getAbsolutePath());

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // ヘッダー行をスキップ
                if (lineNumber <= 0) continue;

                totalLinesProcessed++;

                String[] parts = line.split(",");

                // 必要な列が存在しない場合はスキップ
                if (parts.length < 7) {
                    LOGGER.warning("行 " + lineNumber + " は列数が足りません: " + parts.length);
                    continue;
                }

                // 部屋番号は2列目（インデックス1）
                String roomNumber = parts.length > 1 ? parts[1].trim() : "";

                // 部屋番号が空の場合はスキップ
                if (roomNumber.isEmpty()) {
                    emptyRoomNumbers++;
                    LOGGER.info("★除外: 行 " + lineNumber + " は部屋番号が空です");
                    continue;
                }

                // 部屋タイプは3列目（インデックス2）
                String roomTypeCode = parts.length > 2 ? parts[2].trim() : "";

                // 故障状態は6列目（インデックス5）
                String brokenStatus = parts.length > 5 ? parts[5].trim() : "";
                boolean isBroken = brokenStatus.equals("1");

                // ★重要: 清掃状態は7列目（インデックス6）
                String roomStatus = parts.length > 6 ? parts[6].trim() : "";

                // ★追加: 部屋状態をマップに保存
                ROOM_STATUS_MAP.put(roomNumber, roomStatus);

                // 故障部屋の処理ロジック
                boolean wasOriginallyBroken = isBroken;
                if (isBroken) {
                    // 故障部屋リストに追加（記録用）
                    Room brokenRoom = new Room(roomNumber, determineRoomType(roomTypeCode), false, true, roomStatus);
                    brokenRooms.add(brokenRoom);

                    // 選択された故障部屋は清掃対象として処理を続行
                    if (selectedBrokenRooms.contains(roomNumber)) {
                        LOGGER.info("故障部屋を清掃対象として処理: " + roomNumber);
                        // 清掃対象として続行するため故障フラグをfalseに変更
                        isBroken = false;
                    } else {
                        brokenRoomsSkipped++;
                        LOGGER.info("★除外: 故障部屋をスキップ: " + roomNumber + " (状態: " + roomStatus + ")");
                        continue;
                    }
                }

                // ★修正: 清掃状態による正しい除外処理（2, 3, 4のみ清掃対象）
                if (!wasOriginallyBroken || selectedBrokenRooms.contains(roomNumber)) {
                    // 選択された故障部屋の場合は清掃状態に関係なく処理
                    if (!selectedBrokenRooms.contains(roomNumber) &&
                            !roomStatus.equals("2") && !roomStatus.equals("3") && !roomStatus.equals("4")) {
                        cleaningStatusSkipped++;
                        LOGGER.info("★除外: 清掃不要の部屋をスキップ: " + roomNumber + " (状態: " + roomStatus + ")");
                        continue;
                    }
                }

                // 部屋タイプをコードから判定
                String roomType = determineRoomType(roomTypeCode);

                // ★修正: エコ清掃かどうかの判定（対象日付を使用）
                boolean isEcoClean = false;
                String targetDateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

                if (ecoRoomMap.containsKey(targetDateStr)) {
                    isEcoClean = ecoRoomMap.get(targetDateStr).contains(roomNumber);
                    if (isEcoClean) {
                        LOGGER.info("エコ清掃部屋: " + roomNumber + " (日付: " + targetDateStr + ")");
                    }
                }

                // ★修正: 部屋状態情報を含むRoomオブジェクトを作成
                Room room = new Room(roomNumber, roomType, isEcoClean, isBroken, roomStatus);

                // ★追加: 全部屋マップに追加
                allRoomsMap.put(roomNumber, room);

                // 別館か本館かを判定して対応するリストに追加
                if (isAnnexRoom(roomNumber)) {
                    LOGGER.info("★成功: 別館部屋を追加: " + roomNumber + " (状態: " + roomStatus + ")" +
                            (selectedBrokenRooms.contains(roomNumber) ? " (選択された故障部屋)" : ""));
                    annexRooms.add(room);
                    successfullyAdded++;
                } else {
                    LOGGER.info("★成功: 本館部屋を追加: " + roomNumber + " (状態: " + roomStatus + ")" +
                            (selectedBrokenRooms.contains(roomNumber) ? " (選択された故障部屋)" : ""));
                    mainRooms.add(room);
                    successfullyAdded++;
                }

                // エコ清掃の場合はエコリストにも追加
                if (isEcoClean) {
                    ecoRooms.add(room);
                }
            }

            // ★追加: エコ清掃情報の検証
            String targetDateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            if (ecoRoomMap.containsKey(targetDateStr)) {
                List<String> ecoRoomNumbers = ecoRoomMap.get(targetDateStr);

                LOGGER.info("\n=== エコ清掃情報の検証開始 ===");
                LOGGER.info("対象日: " + targetDateStr);
                LOGGER.info("データベースのエコ部屋数: " + ecoRoomNumbers.size() + "室");

                int notFoundCount = 0;
                int checkoutEcoCount = 0;

                for (String ecoRoomNumber : ecoRoomNumbers) {
                    // 検証1: 部屋データに存在するか
                    if (!allRoomsMap.containsKey(ecoRoomNumber)) {
                        String warning = String.format("【警告】エコ清掃対象の部屋 %s が部屋データに存在しません", ecoRoomNumber);
                        ecoWarnings.add(warning);
                        LOGGER.warning(warning);
                        notFoundCount++;
                        continue;
                    }

                    // 検証2: チェックアウト（状態2）なのにエコ清掃対象か
                    Room room = allRoomsMap.get(ecoRoomNumber);
                    if ("2".equals(room.roomStatus)) {
                        String warning = String.format("【警告】部屋 %s はチェックアウト（状態2）ですがエコ清掃対象になっています",
                                ecoRoomNumber);
                        ecoWarnings.add(warning);
                        LOGGER.warning(warning);
                        checkoutEcoCount++;
                    }
                }

                LOGGER.info("=== エコ清掃検証結果 ===");
                LOGGER.info("部屋データに存在しないエコ部屋: " + notFoundCount + "室");
                LOGGER.info("チェックアウトなのにエコ清掃: " + checkoutEcoCount + "室");
                LOGGER.info("警告総数: " + ecoWarnings.size() + "件");

                if (ecoWarnings.isEmpty()) {
                    LOGGER.info("✓ エコ清掃情報に問題はありません");
                } else {
                    LOGGER.warning("! エコ清掃情報に " + ecoWarnings.size() + " 件の警告があります");
                }
            }

            // ★詳細統計の出力
            LOGGER.info("=== CSVファイル処理統計 ===");
            LOGGER.info("総処理行数（ヘッダー除く）: " + totalLinesProcessed);
            LOGGER.info("除外理由別統計:");
            LOGGER.info("  - 部屋番号が空: " + emptyRoomNumbers + "行");
            LOGGER.info("  - 故障部屋（未選択）: " + brokenRoomsSkipped + "室");
            LOGGER.info("  - 清掃状態による除外: " + cleaningStatusSkipped + "室");
            LOGGER.info("成功追加: " + successfullyAdded + "室");
            LOGGER.info("合計除外: " + (emptyRoomNumbers + brokenRoomsSkipped + cleaningStatusSkipped) + "室");

            // ★改善版: エコ清掃の本館・別館を分けて表示
            int ecoMainRooms = 0;
            int ecoAnnexRooms = 0;
            for (Room room : ecoRooms) {
                if (isAnnexRoom(room.roomNumber)) {
                    ecoAnnexRooms++;
                } else {
                    ecoMainRooms++;
                }
            }

            // 通常清掃の計算
            int normalMainRooms = mainRooms.size() - ecoMainRooms;
            int normalAnnexRooms = annexRooms.size() - ecoAnnexRooms;

            // 結果の確認
            LOGGER.info("読み込み完了: 行数=" + lineNumber);
            LOGGER.info("=== 清掃部屋詳細統計 ===");
            LOGGER.info(String.format("【本館】合計=%d室 (通常清掃=%d室, エコ清掃=%d室)",
                    mainRooms.size(), normalMainRooms, ecoMainRooms));
            LOGGER.info(String.format("【別館】合計=%d室 (通常清掃=%d室, エコ清掃=%d室)",
                    annexRooms.size(), normalAnnexRooms, ecoAnnexRooms));
            LOGGER.info(String.format("【総合計】%d室 (通常清掃=%d室, エコ清掃=%d室, 故障=%d室)",
                    mainRooms.size() + annexRooms.size(),
                    normalMainRooms + normalAnnexRooms,
                    ecoMainRooms + ecoAnnexRooms,
                    brokenRooms.size()));

            // エコデータベース情報の表示
            if (!ecoRoomMap.isEmpty()) {
                LOGGER.info("★エコ清掃データベースから読み込まれた情報:");
                int totalEcoFromDb = ecoRoomMap.values().stream()
                        .mapToInt(List::size)
                        .sum();
                LOGGER.info("  データベース内エコ部屋総数: " + totalEcoFromDb + "室");
                LOGGER.info("  対象日のエコ部屋数: " + ecoRooms.size() + "室");
            } else {
                LOGGER.info("★エコ清掃データベースが設定されていないか、データが見つかりませんでした");
            }

            // 選択された故障部屋の処理結果をログ出力
            if (!selectedBrokenRooms.isEmpty()) {
                long processedBrokenRooms = selectedBrokenRooms.stream()
                        .mapToLong(roomNumber -> {
                            boolean inMain = mainRooms.stream().anyMatch(r -> r.roomNumber.equals(roomNumber));
                            boolean inAnnex = annexRooms.stream().anyMatch(r -> r.roomNumber.equals(roomNumber));
                            if (inMain || inAnnex) {
                                LOGGER.info("故障部屋が清掃対象に追加されました: " + roomNumber +
                                        " (" + (inMain ? "本館" : "別館") + ")");
                            }
                            return inMain || inAnnex ? 1 : 0;
                        }).sum();
                LOGGER.info("選択された故障部屋のうち清掃対象に追加された部屋: " + processedBrokenRooms + "室");

                if (processedBrokenRooms != selectedBrokenRooms.size()) {
                    LOGGER.warning("一部の選択された故障部屋が清掃対象に追加されませんでした。CSVデータを確認してください。");
                }
            }

            // 部屋が一つも読み込まれなかった場合は警告
            if (mainRooms.isEmpty() && annexRooms.isEmpty()) {
                LOGGER.warning("読み込まれた部屋データが0件です。CSVフォーマットを確認してください。");
            }

            // ★追加: 部屋状態統計の出力
            Map<String, Integer> statusCounts = new HashMap<>();
            ROOM_STATUS_MAP.values().forEach(status ->
                    statusCounts.merge(status, 1, Integer::sum));

            LOGGER.info("部屋状態統計:");
            statusCounts.forEach((status, count) -> {
                String statusDisplay;
                switch (status) {
                    case "2": statusDisplay = "チェックアウト"; break;
                    case "3": statusDisplay = "連泊"; break;
                    case "4": statusDisplay = "時間延長"; break;
                    default: statusDisplay = status.isEmpty() ? "不明" : status; break;
                }
                LOGGER.info("  " + statusDisplay + ": " + count + "室");
            });

            // ★重要: 状態2,3,4の部屋のみが清掃対象として処理されることをログ出力
            int totalProcessedRooms = mainRooms.size() + annexRooms.size();
            LOGGER.info("★修正完了: 清掃対象（状態2,3,4）" + totalProcessedRooms + "室を処理しました");
            LOGGER.info("  - 本館: " + mainRooms.size() + "室");
            LOGGER.info("  - 別館: " + annexRooms.size() + "室");
            LOGGER.info("  - 故障部屋（清掃対象外）: " + brokenRooms.size() + "室");
            LOGGER.info("  - エコ清掃: " + ecoRooms.size() + "室");
            LOGGER.info("  - 清掃対象条件: 状態2（チェックアウト）、3（連泊）、4（清掃要）");

            return new CleaningData(mainRooms, annexRooms, ecoRooms, brokenRooms, ecoWarnings);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "部屋データの読み込み中にエラーが発生しました", e);
            throw new RuntimeException("部屋データの読み込みに失敗しました", e);
        }
    }

    /**
     * 選択された故障部屋の部屋番号セットを取得
     */
    private static Set<String> getSelectedBrokenRooms() {
        Set<String> selectedRooms = new HashSet<>();

        String selectedBrokenRoomsStr = System.getProperty("selectedBrokenRooms");
        if (selectedBrokenRoomsStr != null && !selectedBrokenRoomsStr.trim().isEmpty()) {
            LOGGER.info("システムプロパティから故障部屋選択情報を取得: " + selectedBrokenRoomsStr);
            String[] roomNumbers = selectedBrokenRoomsStr.split(",");
            for (String roomNumber : roomNumbers) {
                if (!roomNumber.trim().isEmpty()) {
                    selectedRooms.add(roomNumber.trim());
                }
            }
        } else {
            LOGGER.info("選択された故障部屋はありません");
        }

        return selectedRooms;
    }

    // パターンからルームタイプを決定するメソッド（更新版）
    private static String determineRoomType(String roomTypeCode) {
        // シングルルーム判定
        if (roomTypeCode.equals("S") ||
                roomTypeCode.equals("NS") ||
                roomTypeCode.equals("ANS") ||
                roomTypeCode.equals("ABF") ||
                roomTypeCode.equals("AKS")) {
            return "S";
        }
        // ダブルルーム判定
        else if (roomTypeCode.equals("D") ||
                roomTypeCode.equals("ND") ||
                roomTypeCode.equals("AND")) {
            return "D";
        }
        // ツインルーム判定
        else if (roomTypeCode.equals("T") ||
                roomTypeCode.equals("NT") ||
                roomTypeCode.equals("ANT") ||
                roomTypeCode.equals("ADT")) {
            return "T";
        }
        // FDルーム判定
        else if (roomTypeCode.equals("FD") ||
                roomTypeCode.equals("NFD")) {
            return "FD";
        }
        // 不明な場合はそのまま返す
        else {
            return roomTypeCode;
        }
    }

    // ★エコ清掃情報をデータベースから読み込む（対象日付指定版）
    private static Map<String, List<String>> loadEcoRoomInfo(LocalDate targetDate) {
        Map<String, List<String>> ecoRoomsByDate = new HashMap<>();

        String dbPath = System.getProperty("ecoDataFile");
        if (dbPath == null || dbPath.isEmpty()) {
            LOGGER.warning("エコ清掃情報データベースが設定されていません");
            return ecoRoomsByDate;
        }

        File dbFile = new File(dbPath);
        if (!dbFile.exists()) {
            LOGGER.warning("エコ清掃情報データベースが見つかりません: " + dbFile.getAbsolutePath());
            return ecoRoomsByDate;
        }

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            Class.forName("org.sqlite.JDBC");
            String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            conn = DriverManager.getConnection(jdbcUrl);

            LOGGER.info("エコ清掃データベースに接続しました: " + dbFile.getAbsolutePath());

            // ★診断1: データベース内の全cleaning_statusの値を確認
            LOGGER.info("=== データベース診断開始 ===");
            PreparedStatement diagStmt = conn.prepareStatement(
                    "SELECT DISTINCT cleaning_status FROM cleaning_schedule ORDER BY cleaning_status");
            ResultSet diagRs = diagStmt.executeQuery();

            LOGGER.info("データベース内の全cleaning_status値:");
            while (diagRs.next()) {
                String status = diagRs.getString("cleaning_status");
                LOGGER.info("  - [" + (status == null ? "NULL" : status) + "]");
            }
            diagRs.close();
            diagStmt.close();

            // ★診断2: 対象日付のデータを確認
            String targetDateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            LOGGER.info("検索対象日: " + targetDateStr);

            PreparedStatement todayStmt = conn.prepareStatement(
                    "SELECT COUNT(*) as cnt FROM cleaning_schedule WHERE cleaning_date = ?");
            todayStmt.setString(1, targetDateStr);
            ResultSet todayRs = todayStmt.executeQuery();
            if (todayRs.next()) {
                LOGGER.info("対象日のレコード数: " + todayRs.getInt("cnt"));
            }
            todayRs.close();
            todayStmt.close();

            // ★修正版: 全てのcleaning_statusを取得（'×'と'エコドア'に限定しない）
            String sql = "SELECT room_number, cleaning_date, cleaning_status " +
                    "FROM cleaning_schedule " +
                    "WHERE cleaning_status IS NOT NULL AND cleaning_status != '' " +
                    "AND cleaning_status != 'C/O' " +  // チェックアウトは除外
                    "ORDER BY cleaning_date, room_number";

            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();

            int totalEcoRecords = 0;
            Map<String, Integer> statusCount = new HashMap<>();

            while (rs.next()) {
                String roomNumber = rs.getString("room_number");
                String cleaningDate = rs.getString("cleaning_date");
                String cleaningStatus = rs.getString("cleaning_status");

                // ステータスのカウント
                statusCount.merge(cleaningStatus, 1, Integer::sum);

                // エコ清掃の判定条件を緩和
                boolean isEco = cleaningStatus.equals("×") ||
                        cleaningStatus.equals("エコドア") ||
                        cleaningStatus.toLowerCase().contains("eco") ||
                        cleaningStatus.toLowerCase().contains("エコ");

                if (isEco) {
                    ecoRoomsByDate.computeIfAbsent(cleaningDate, k -> new ArrayList<>()).add(roomNumber);
                    totalEcoRecords++;
                    LOGGER.fine(String.format("エコ部屋: %s (%s) - ステータス: %s",
                            roomNumber, cleaningDate, cleaningStatus));
                }
            }

            LOGGER.info("=== エコデータベース読み込み結果 ===");
            LOGGER.info(String.format("エコ清掃データベースから読み込み完了: %d件のエコ清掃記録", totalEcoRecords));
            LOGGER.info(String.format("対象日数: %d日", ecoRoomsByDate.size()));

            // ステータスごとの件数を表示
            LOGGER.info("cleaning_statusごとの件数:");
            statusCount.forEach((status, count) ->
                    LOGGER.info(String.format("  [%s]: %d件", status, count)));

            // 各日付のエコ部屋数を表示
            LOGGER.info("日付別エコ部屋数:");
            ecoRoomsByDate.forEach((date, rooms) -> {
                LOGGER.info(String.format("  %s: %d部屋 (例: %s)", date, rooms.size(),
                        rooms.size() > 0 ? rooms.get(0) + (rooms.size() > 1 ? ", " + rooms.get(1) + "..." : "") : ""));
            });

        } catch (ClassNotFoundException e) {
            LOGGER.severe("SQLiteドライバが見つかりません。sqlite-jdbc-x.x.x.jarをクラスパスに追加してください");
            LOGGER.severe("エラー詳細: " + e.getMessage());
        } catch (SQLException e) {
            LOGGER.severe("データベース読み込み中にエラーが発生しました: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) rs.close();
                if (pstmt != null) pstmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                LOGGER.warning("リソースのクローズ中にエラーが発生しました: " + e.getMessage());
            }
        }

        return ecoRoomsByDate;
    }

    // 部屋番号から別館かどうかを判定するメソッド
    private static boolean isAnnexRoom(String roomNumber) {
        // 数字以外を削除
        String numericPart = roomNumber.replaceAll("[^0-9]", "");

        // 3桁の数字は本館
        if (numericPart.length() == 3) {
            return false;
        }
        // 4桁の数字で10で始まる場合も本館
        else if (numericPart.length() == 4 && numericPart.startsWith("10")) {
            return false;
        }
        // それ以外の4桁の数字は別館
        else if (numericPart.length() == 4) {
            return true;
        }

        // 上記のいずれにも当てはまらない場合はデフォルトで本館とする
        return false;
    }

    // スタッフシフト情報を取得するメソッド
    public static List<Staff> getAvailableStaff(File file, LocalDate date) {
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            List<Staff> availableStaff = new ArrayList<>();

            // 日付が3行目、7列目から始まる
            Row dateRow = sheet.getRow(2);
            int targetColumn = findDateColumnInShiftFile(dateRow, date);

            if (targetColumn == -1) {
                LOGGER.warning("対象日(" + date + ")の列が見つかりませんでした。デフォルトの列を使用します。");
                targetColumn = 6 + (date.getDayOfMonth() - 1); // G列から開始
            }

            LOGGER.info("対象日の列: " + targetColumn);

            // スタッフは6行目から52行目まで
            for (int i = 5; i <= 51; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // F列(5)にスタッフ名
                Cell nameCell = row.getCell(5);
                Cell shiftCell = row.getCell(targetColumn);

                String staffName = getCellValueAsString(nameCell).trim();
                String shiftValue = getCellValueAsString(shiftCell).trim();

                // デバッグ用ログ
                LOGGER.info(String.format("行 %d: 名前=%s, シフト値=%s",
                        i+1, staffName, shiftValue));

                // スタッフ名が存在し、シフト欄が空白の場合にスタッフを追加
                if (!staffName.isEmpty() && isAvailableShift(shiftValue)) {
                    LOGGER.info("利用可能なスタッフを追加: " + staffName);
                    availableStaff.add(new Staff(staffName, staffName));
                }
            }

            if (availableStaff.isEmpty()) {
                LOGGER.warning("利用可能なスタッフが見つかりませんでした。サンプルスタッフを追加します。");
                for (int i = 1; i <= 6; i++) {
                    availableStaff.add(new Staff("スタッフ" + i, "スタッフ" + i));
                }
            } else {
                LOGGER.info("検出された利用可能なスタッフ数: " + availableStaff.size());
                availableStaff.forEach(staff ->
                        LOGGER.info("スタッフ: " + staff.id + " (" + staff.name + ")"));
            }

            return availableStaff;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "スタッフデータの処理中にエラーが発生しました", e);
            // エラーの場合もサンプルスタッフを返す
            List<Staff> sampleStaff = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                sampleStaff.add(new Staff("スタッフ" + i, "スタッフ" + i));
            }
            LOGGER.info("エラーのためサンプルスタッフを使用します");
            return sampleStaff;
        }
    }

    // スタッフシフトファイルで日付列を検索
    private static int findDateColumnInShiftFile(Row dateRow, LocalDate targetDate) {
        if (dateRow == null) return -1;

        int targetDay = targetDate.getDayOfMonth();
        LOGGER.info("検索対象の日: " + targetDay);

        // G列(6)からAH列(33)まで
        for (int i = 6; i <= 33; i++) {
            Cell cell = dateRow.getCell(i);
            if (cell == null) continue;

            String cellValue = getCellValueAsString(cell).trim();
            LOGGER.info("列 " + i + " の値: " + cellValue);

            try {
                // 数値として解析できる場合
                int dayValue = Integer.parseInt(cellValue);
                if (dayValue == targetDay) {
                    LOGGER.info("対象日の列を発見: " + i);
                    return i;
                }
            } catch (NumberFormatException e) {
                // 解析できない場合はスキップ
            }
        }

        LOGGER.warning("対象日の列が見つかりませんでした");
        return -1;
    }

    private static boolean isAvailableShift(String shiftValue) {
        // シフト欄が空白の場合のみ利用可能とする
        return shiftValue.isEmpty() || shiftValue.equals(" ");
    }

    private static String getCellValueAsString(Cell cell) {
        if (cell == null) return "";

        try {
            switch (cell.getCellType()) {
                case STRING:
                    return cell.getStringCellValue();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue().toString();
                    } else {
                        // 数値を文字列に変換（小数点以下がある場合は除去）
                        double value = cell.getNumericCellValue();
                        if (value == (int) value) {
                            return String.valueOf((int) value);
                        } else {
                            return String.valueOf(value);
                        }
                    }
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                case FORMULA:
                    try {
                        return cell.getStringCellValue();
                    } catch (IllegalStateException e) {
                        try {
                            return String.valueOf(cell.getNumericCellValue());
                        } catch (IllegalStateException e2) {
                            return cell.getCellFormula();
                        }
                    }
                case BLANK:
                    return "";
                default:
                    return "";
            }
        } catch (Exception e) {
            LOGGER.warning("セル値の取得中にエラーが発生しました: " + e.getMessage());
            return "";
        }
    }

    // Staff内部クラス
    public static class Staff {
        public final String id;
        public final String name;

        public Staff(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}