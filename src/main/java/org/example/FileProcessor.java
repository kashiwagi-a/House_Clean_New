package org.example;  // com.example.roomassignment から変更

import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class FileProcessor {
    private static final Logger LOGGER = Logger.getLogger(FileProcessor.class.getName());

    // CleaningDataクラスを内部クラスとして定義
    public static class CleaningData {
        public final List<Room> mainRooms;
        public final List<Room> annexRooms;
        public final List<Room> ecoRooms;
        public final List<Room> brokenRooms;  // 故障部屋リストを追加
        public final int totalMainRooms;
        public final int totalAnnexRooms;
        public final int totalBrokenRooms;    // 故障部屋数を追加

        public CleaningData(List<Room> mainRooms, List<Room> annexRooms, List<Room> ecoRooms, List<Room> brokenRooms) {
            this.mainRooms = mainRooms;
            this.annexRooms = annexRooms;
            this.ecoRooms = ecoRooms;
            this.brokenRooms = brokenRooms;
            this.totalMainRooms = mainRooms.size();
            this.totalAnnexRooms = annexRooms.size();
            this.totalBrokenRooms = brokenRooms.size();
        }
    }

    // Roomクラスを内部クラスとして定義
    public static class Room {
        public final String roomNumber;
        public final String roomType;
        public final boolean isEcoClean;
        public final boolean isBroken;  // 故障フラグを追加
        public final int floor;

        public Room(String roomNumber, String roomType, boolean isEcoClean, boolean isBroken) {
            this.roomNumber = roomNumber;
            this.roomType = roomType;
            this.isEcoClean = isEcoClean;
            this.isBroken = isBroken;
            this.floor = extractFloor(roomNumber);
        }

        @Override
        public String toString() {
            return roomNumber + " (" + roomType + ", " + floor + "階" +
                    (isEcoClean ? ", エコ清掃" : "") +
                    (isBroken ? ", 故障" : "") + ")";
        }
    }

    // 部屋番号から階数を抽出するメソッド
    private static int extractFloor(String roomNumber) {
        try {
            // 数字以外を削除
            String numericPart = roomNumber.replaceAll("[^0-9]", "");

            // 3桁の数字（本館）
            if (numericPart.length() == 3) {
                return Character.getNumericValue(numericPart.charAt(0));
            }
            // 4桁の数字で10で始まる場合（本館）
            else if (numericPart.length() == 4 && numericPart.startsWith("10")) {
                return 10;
            }
            // 4桁の数字（別館）
            else if (numericPart.length() == 4) {
                return Character.getNumericValue(numericPart.charAt(1));
            }

            // パターンに一致しない場合は0を返す
            return 0;
        } catch (Exception e) {
            LOGGER.warning("階数の抽出に失敗しました: " + roomNumber);
            return 0;
        }
    }

    // ファイルから部屋データを読み込むメソッド
    public static CleaningData processRoomFile(File file) {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".csv")) {
            return processCsvRoomFile(file);
        } else {
            throw new IllegalArgumentException("サポートされていないファイル形式です: " + fileName);
        }
    }

    // CSVファイルから部屋データを読み込むメソッド
    private static CleaningData processCsvRoomFile(File file) {
        List<Room> mainRooms = new ArrayList<>();
        List<Room> annexRooms = new ArrayList<>();
        List<Room> ecoRooms = new ArrayList<>();
        List<Room> brokenRooms = new ArrayList<>();  // 故障部屋リストを追加
        Map<String, List<String>> ecoRoomMap = loadEcoRoomInfo();

        try (BufferedReader reader = new BufferedReader(new FileReader(file, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;

            LOGGER.info("CSVファイルの読み込みを開始: " + file.getAbsolutePath());

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // デバッグログ
                if (lineNumber <= 10) {
                    LOGGER.info("行 " + lineNumber + ": " + line);
                }

                // ヘッダー行をスキップ
                if (lineNumber <= 4) continue;

                String[] parts = line.split(",");

                // デバッグログ
                if (lineNumber <= 10) {
                    LOGGER.info("列数: " + parts.length);
                    for (int i = 0; i < parts.length; i++) {
                        LOGGER.info("列 " + i + ": " + parts[i]);
                    }
                }

                // 必要な列が存在しない場合はスキップ
                if (parts.length < 7) {
                    LOGGER.warning("行 " + lineNumber + " は列数が足りません: " + parts.length);
                    continue;
                }

                // 部屋番号は2列目（インデックス1）
                String roomNumber = parts.length > 1 ? parts[1].trim() : "";

                // 部屋タイプは3列目（インデックス2）
                String roomTypeCode = parts.length > 2 ? parts[2].trim() : "";

                // 故障状態は6列目（インデックス5）- 新規追加
                String brokenStatus = parts.length > 5 ? parts[5].trim() : "";
                boolean isBroken = brokenStatus.equals("1");

                // 清掃状態は7列目（インデックス6）
                String roomStatus = parts.length > 6 ? parts[6].trim() : "";

                // 部屋番号が空の場合はスキップ
                if (roomNumber.isEmpty()) {
                    LOGGER.fine("行 " + lineNumber + " は部屋番号が空です");
                    continue;
                }

                LOGGER.info("処理中の部屋: " + roomNumber + ", タイプ: " + roomTypeCode +
                        ", 故障: " + isBroken + ", 状態: " + roomStatus);

                // 故障部屋はスキップ（清掃対象外）
                if (isBroken) {
                    LOGGER.info("故障部屋をスキップ: " + roomNumber);
                    Room room = new Room(roomNumber, determineRoomType(roomTypeCode), false, true);
                    brokenRooms.add(room);
                    continue;
                }

                // 清掃が必要な部屋のみ処理（2=チェックアウト、3=連泊）
                if (!roomStatus.equals("2") && !roomStatus.equals("3")) {
                    LOGGER.info("清掃不要の部屋をスキップ: " + roomNumber + " (状態: " + roomStatus + ")");
                    continue;
                }

                // 部屋タイプをコードから判定
                String roomType = determineRoomType(roomTypeCode);

                // エコ清掃かどうかの判定
                boolean isEcoClean = false;
                LocalDate today = LocalDate.now();
                String todayStr = today.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

                if (ecoRoomMap.containsKey(todayStr)) {
                    isEcoClean = ecoRoomMap.get(todayStr).contains(roomNumber);
                    if (isEcoClean) {
                        LOGGER.info("エコ清掃部屋: " + roomNumber);
                    }
                }

                // 部屋オブジェクトを作成
                Room room = new Room(roomNumber, roomType, isEcoClean, false);

                // 別館か本館かを判定して対応するリストに追加
                if (isAnnexRoom(roomNumber)) {
                    LOGGER.info("別館部屋を追加: " + roomNumber);
                    annexRooms.add(room);
                } else {
                    LOGGER.info("本館部屋を追加: " + roomNumber);
                    mainRooms.add(room);
                }

                // エコ清掃の場合はエコリストにも追加
                if (isEcoClean) {
                    ecoRooms.add(room);
                }
            }

            // 結果の確認
            LOGGER.info("読み込み完了: 行数=" + lineNumber);
            LOGGER.info(String.format("読み込まれた部屋数: 本館=%d, 別館=%d, エコ清掃=%d, 故障=%d",
                    mainRooms.size(), annexRooms.size(), ecoRooms.size(), brokenRooms.size()));

            // 部屋が一つも読み込まれなかった場合は警告
            if (mainRooms.isEmpty() && annexRooms.isEmpty()) {
                LOGGER.warning("読み込まれた部屋データが0件です。CSVフォーマットを確認してください。");
            }

            return new CleaningData(mainRooms, annexRooms, ecoRooms, brokenRooms);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "部屋データの読み込み中にエラーが発生しました", e);
            throw new RuntimeException("部屋データの読み込みに失敗しました", e);
        }
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
        // ダブルルーム判定（新規追加）
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

    // エコ清掃情報をExcelファイルから読み込む
    private static Map<String, List<String>> loadEcoRoomInfo() {
        Map<String, List<String>> ecoRoomsByDate = new HashMap<>();

        // システムプロパティからEcoデータファイルのパスを取得
        String ecoFilePath = System.getProperty("ecoDataFile");
        if (ecoFilePath == null || ecoFilePath.isEmpty()) {
            LOGGER.warning("エコ清掃情報ファイルが設定されていません");
            return ecoRoomsByDate;
        }

        File ecoFile = new File(ecoFilePath);
        if (!ecoFile.exists()) {
            LOGGER.warning("エコ清掃情報ファイルが見つかりません: " + ecoFile.getAbsolutePath());
            return ecoRoomsByDate;
        }

        try (FileInputStream fis = new FileInputStream(ecoFile);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            // 日付列の位置を特定（4列目から）
            Map<Integer, String> dateColumns = new HashMap<>();
            for (int i = 3; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                if (cell != null) {
                    String dateStr = getCellValueAsString(cell);
                    if (!dateStr.isEmpty()) {
                        dateColumns.put(i, dateStr);
                        LOGGER.info("日付列検出: " + i + " = " + dateStr);
                    }
                }
            }

            // 各日付に対応する部屋のエコ状態を収集
            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                Cell roomCell = row.getCell(0); // 部屋番号は1列目
                if (roomCell == null) continue;

                String roomNumber = getCellValueAsString(roomCell).trim();
                if (roomNumber.isEmpty()) continue;

                // 各日付列について確認
                for (Map.Entry<Integer, String> entry : dateColumns.entrySet()) {
                    int columnIndex = entry.getKey();
                    String dateStr = entry.getValue();

                    Cell ecoCell = row.getCell(columnIndex);
                    String ecoStatus = getCellValueAsString(ecoCell).trim();

                    if (ecoStatus.equalsIgnoreCase("eco")) {
                        // この日のエコ部屋リストを取得または作成
                        ecoRoomsByDate.computeIfAbsent(dateStr, k -> new ArrayList<>())
                                .add(roomNumber);
                    }
                }
            }

            // デバッグログ
            for (Map.Entry<String, List<String>> entry : ecoRoomsByDate.entrySet()) {
                LOGGER.info(entry.getKey() + "のエコ部屋: " + entry.getValue().size() + "部屋");
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "エコ情報の読み込み中にエラーが発生しました: " + e.getMessage(), e);
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

            // スタッフは6行目から50行目まで
            for (int i = 5; i <= 49; i++) {
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

        // G列(6)からAH列(33)までスキャン
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