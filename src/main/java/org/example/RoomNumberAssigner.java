package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * 部屋番号自動割り当て機能
 * スタッフ割り当て結果に具体的な部屋番号を割り当てる
 *
 * ★修正: リネン庫担当フロアのみ部屋番号を大きい順に割り当て
 * （リネン庫担当でないフロアは通常の昇順を維持）
 */
public class RoomNumberAssigner {
    private static final Logger LOGGER = Logger.getLogger(RoomNumberAssigner.class.getName());

    private final FileProcessor.CleaningData cleaningData;

    public RoomNumberAssigner(FileProcessor.CleaningData cleaningData) {
        this.cleaningData = cleaningData;
    }

    /**
     * 詳細部屋割り当てを実行
     * @param assignments スタッフ割り当て結果
     * @return スタッフ名をキーとした部屋リストのマップ
     */
    public Map<String, List<FileProcessor.Room>> assignDetailedRooms(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments) {

        Map<String, List<FileProcessor.Room>> result = new HashMap<>();

        // 清掃対象の全部屋をプール
        List<FileProcessor.Room> availableRooms = new ArrayList<>(cleaningData.roomsToClean);

        LOGGER.info("部屋番号割り当て開始。利用可能部屋数: " + availableRooms.size());

        // 各スタッフの割り当てに対して具体的な部屋を割り当て
        for (AdaptiveRoomOptimizer.StaffAssignment assignment : assignments) {
            // ★修正: 大浴清掃タイプを渡す
            List<FileProcessor.Room> assignedRooms = assignRoomsToStaff(
                    assignment, availableRooms, assignment.bathCleaningType);
            result.put(assignment.staff.name, assignedRooms);

            // 割り当てた部屋を利用可能プールから削除
            availableRooms.removeAll(assignedRooms);

            LOGGER.info(String.format("%s に %d室を割り当て。残り: %d室",
                    assignment.staff.name, assignedRooms.size(), availableRooms.size()));
        }

        // ★新規: 未割り当てに連泊部屋が残っている場合、割り当て済みの非連泊部屋と入れ替えて救済
        // （連泊部屋は必ず清掃が必要なため、未割り当てに残すのはチェックアウト等の部屋のみとする）
        rescueStayoverRooms(assignments, result, availableRooms);

        if (!availableRooms.isEmpty()) {
            long stayoverLeft = availableRooms.stream()
                    .filter(r -> "3".equals(r.roomStatus)).count();
            if (stayoverLeft > 0) {
                LOGGER.warning("未割り当て部屋が残っています: " + availableRooms.size()
                        + "室（うち連泊: " + stayoverLeft + "室 ※同一階に入れ替え可能な非連泊部屋がありませんでした）");
            } else {
                LOGGER.warning("未割り当て部屋が残っています: " + availableRooms.size() + "室（連泊部屋なし）");
            }
        }

        return result;
    }

    /**
     * 特定のスタッフに部屋を割り当て
     * ★修正: リネン庫担当フロアのみ部屋番号を大きい順に割り当て
     * （リネン庫担当でないフロアは通常の昇順を維持）
     */
    private List<FileProcessor.Room> assignRoomsToStaff(
            AdaptiveRoomOptimizer.StaffAssignment assignment,
            List<FileProcessor.Room> availableRooms,
            AdaptiveRoomOptimizer.BathCleaningType bathCleaningType) {

        List<FileProcessor.Room> assignedRooms = new ArrayList<>();

        // ★修正: リネン庫担当フロアのみ部屋番号を大きい順に割り当て
        boolean isLinenClosetStaff = assignment.isLinenClosetCleaning;
        Set<Integer> linenFloorSet = new HashSet<>(assignment.getLinenClosetFloors());

        if (isLinenClosetStaff && !linenFloorSet.isEmpty()) {
            LOGGER.info(String.format("%s はリネン庫清掃スタッフのため、リネン庫担当フロア(%s)の部屋番号を大きい順に割り当てます",
                    assignment.staff.name, assignment.getLinenClosetFloorsDisplay()));
        }

        // 階ごとに処理
        for (int floor : assignment.floors) {
            AdaptiveRoomOptimizer.RoomAllocation allocation = assignment.roomsByFloor.get(floor);
            if (allocation == null) continue;

            // この階の利用可能な部屋を取得
            List<FileProcessor.Room> floorRooms = availableRooms.stream()
                    .filter(room -> room.floor == floor)
                    .collect(Collectors.toList());

            if (floorRooms.isEmpty()) {
                LOGGER.warning("階 " + floor + " に利用可能な部屋がありません");
                continue;
            }

            // ★修正: リネン庫担当フロアのみ部屋番号を大きい順にソート
            if (linenFloorSet.contains(floor)) {
                // リネン庫担当フロア: 部屋番号の数値部分で降順ソート（大きい順）
                floorRooms.sort((r1, r2) -> {
                    int num1 = extractRoomNumber(r1.roomNumber);
                    int num2 = extractRoomNumber(r2.roomNumber);
                    return Integer.compare(num2, num1); // 降順
                });
            } else {
                // 通常スタッフは部屋番号の昇順
                floorRooms.sort((r1, r2) -> {
                    int num1 = extractRoomNumber(r1.roomNumber);
                    int num2 = extractRoomNumber(r2.roomNumber);
                    return Integer.compare(num1, num2); // 昇順
                });
            }

            // 各部屋タイプについて割り当て（エコ部屋を除外）
            for (Map.Entry<String, Integer> entry : allocation.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                int count = entry.getValue();

                // ★修正: ソート済みのfloorRoomsから順番に取得
                List<FileProcessor.Room> typeRooms = new ArrayList<>();
                int assigned = 0;
                for (FileProcessor.Room room : floorRooms) {
                    if (assigned >= count) break;
                    if (!room.isEco && mapRoomType(room.roomType).equals(roomType)) {
                        typeRooms.add(room);
                        assigned++;
                    }
                }

                assignedRooms.addAll(typeRooms);
                floorRooms.removeAll(typeRooms);
            }

            // エコ部屋の割り当て（allocation.ecoRoomsの数だけ）
            int ecoCount = allocation.ecoRooms;
            List<FileProcessor.Room> ecoRooms = new ArrayList<>();
            int ecoAssigned = 0;
            for (FileProcessor.Room room : floorRooms) {
                if (ecoAssigned >= ecoCount) break;
                if (room.isEco) {
                    ecoRooms.add(room);
                    ecoAssigned++;
                }
            }

            if (!ecoRooms.isEmpty()) {
                // ★修正: エコ部屋もリネン庫担当フロアなら大きい順
                if (linenFloorSet.contains(floor)) {
                    ecoRooms.sort((r1, r2) -> {
                        int num1 = extractRoomNumber(r1.roomNumber);
                        int num2 = extractRoomNumber(r2.roomNumber);
                        return Integer.compare(num2, num1); // 降順
                    });
                }
                assignedRooms.addAll(ecoRooms);
                floorRooms.removeAll(ecoRooms);
                LOGGER.info(String.format("  %d階: エコ%d室を割り当て", floor, ecoRooms.size()));
            }
        }

        // ★修正: 最終ソート（リネン庫担当フロアのみ大きい順、それ以外は昇順）
        // ※救済処理（rescueStayoverRooms）後の再ソートでも使うため共通メソッド化（ロジックは従来と同一）
        sortAssignedRooms(assignment, assignedRooms);

        return assignedRooms;
    }

    /**
     * ★新規: スタッフの割り当て部屋リストを最終ソートする共通メソッド
     * （従来 assignRoomsToStaff 末尾にあったソートロジックをそのまま移設。動作は同一）
     * リネン庫担当フロアのみ部屋番号を大きい順、それ以外は昇順。
     */
    private void sortAssignedRooms(AdaptiveRoomOptimizer.StaffAssignment assignment,
                                   List<FileProcessor.Room> assignedRooms) {
        boolean isLinenClosetStaff = assignment.isLinenClosetCleaning;
        Set<Integer> linenFloorSet = new HashSet<>(assignment.getLinenClosetFloors());

        if (isLinenClosetStaff && !linenFloorSet.isEmpty()) {
            assignedRooms.sort((r1, r2) -> {
                // まず階で比較（昇順）
                int floorCompare = Integer.compare(r1.floor, r2.floor);
                if (floorCompare != 0) return floorCompare;
                // 同じ階の場合、リネン庫担当フロアなら降順、そうでなければ昇順
                int num1 = extractRoomNumber(r1.roomNumber);
                int num2 = extractRoomNumber(r2.roomNumber);
                if (linenFloorSet.contains(r1.floor)) {
                    return Integer.compare(num2, num1); // 降順
                } else {
                    return Integer.compare(num1, num2); // 昇順
                }
            });
        } else {
            // 通常スタッフ: 部屋番号順（昇順）
            assignedRooms.sort(Comparator.comparing(room -> room.roomNumber));
        }
    }

    /**
     * ★新規: 未割り当てに残った連泊部屋（roomStatus="3"）を救済する
     *
     * 連泊部屋は必ず清掃（割り当て）が必要なため、未割り当てプールに連泊部屋が
     * 残っている場合、割り当て済みの「非連泊」部屋と1対1で入れ替える。
     * 入れ替え条件:
     *   - 同一階であること（スタッフの担当階を変えないため）
     *   - エコ区分が同一であること（エコ⇔エコ、通常⇔通常のみ）
     *   - 通常部屋の場合は部屋タイプ（S/T等）も同一であること
     * これにより、スタッフごとの部屋数・担当階・部屋タイプ内訳・エコ数は一切変化しない。
     * 全部屋が割り当て済み、または未割り当てに連泊部屋がない場合は何もしない（従来動作のまま）。
     */
    private void rescueStayoverRooms(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            Map<String, List<FileProcessor.Room>> result,
            List<FileProcessor.Room> availableRooms) {

        // 未割り当てに残っている連泊部屋を抽出
        List<FileProcessor.Room> unassignedStayovers = availableRooms.stream()
                .filter(r -> "3".equals(r.roomStatus))
                .collect(Collectors.toList());

        if (unassignedStayovers.isEmpty()) {
            return; // 救済不要（従来動作のまま）
        }

        LOGGER.info("未割り当ての連泊部屋を検出: " + unassignedStayovers.size()
                + "室。割り当て済みの非連泊部屋との入れ替えを試みます");

        Set<String> modifiedStaffNames = new HashSet<>(); // 入れ替え後に再ソートが必要なスタッフ

        for (FileProcessor.Room stayover : unassignedStayovers) {
            String bestStaffName = null;
            int bestIndex = -1;
            int bestScore = Integer.MAX_VALUE;

            // 全スタッフの割り当て済み部屋から入れ替え候補を探す
            for (AdaptiveRoomOptimizer.StaffAssignment assignment : assignments) {
                List<FileProcessor.Room> staffRooms = result.get(assignment.staff.name);
                if (staffRooms == null) continue;

                for (int i = 0; i < staffRooms.size(); i++) {
                    FileProcessor.Room candidate = staffRooms.get(i);

                    // 連泊部屋同士の入れ替えは無意味なので対象外
                    if ("3".equals(candidate.roomStatus)) continue;
                    // 同一階のみ（担当階を変えない）
                    if (candidate.floor != stayover.floor) continue;
                    // エコ区分は必ず一致（エコ数を変えない）
                    if (candidate.isEco != stayover.isEco) continue;

                    boolean typeMatches = mapRoomType(candidate.roomType)
                            .equals(mapRoomType(stayover.roomType));
                    // 通常部屋は部屋タイプ一致が必須（タイプ内訳を変えない）
                    if (!candidate.isEco && !typeMatches) continue;

                    // スコア: タイプ一致を優先し、部屋番号が近いものを選ぶ
                    // （エコ部屋は元の割り当てロジック同様タイプ不問だが、一致する方を優先）
                    int score = (typeMatches ? 0 : 1_000_000)
                            + Math.abs(extractRoomNumber(candidate.roomNumber)
                            - extractRoomNumber(stayover.roomNumber));

                    if (score < bestScore) {
                        bestScore = score;
                        bestStaffName = assignment.staff.name;
                        bestIndex = i;
                    }
                }
            }

            if (bestStaffName != null) {
                List<FileProcessor.Room> staffRooms = result.get(bestStaffName);
                FileProcessor.Room replaced = staffRooms.get(bestIndex);

                // 入れ替え実行: 連泊部屋を割り当て、非連泊部屋を未割り当てへ戻す
                staffRooms.set(bestIndex, stayover);
                availableRooms.remove(stayover);
                availableRooms.add(replaced);
                modifiedStaffNames.add(bestStaffName);

                LOGGER.info(String.format("連泊部屋を救済: %s を %s に割り当て（%s は未割り当てへ）",
                        stayover.roomNumber, bestStaffName, replaced.roomNumber));
            } else {
                LOGGER.warning("連泊部屋 " + stayover.roomNumber
                        + " (" + stayover.floor + "階) の入れ替え候補が見つかりませんでした"
                        + "（同一階・同一区分の非連泊割り当て部屋なし）");
            }
        }

        // 入れ替えが発生したスタッフの部屋リストを再ソート（従来と同じソート規則を適用）
        for (AdaptiveRoomOptimizer.StaffAssignment assignment : assignments) {
            if (modifiedStaffNames.contains(assignment.staff.name)) {
                List<FileProcessor.Room> staffRooms = result.get(assignment.staff.name);
                if (staffRooms != null) {
                    sortAssignedRooms(assignment, staffRooms);
                }
            }
        }
    }

    /**
     * ★新規: 部屋番号から数値部分を抽出
     */
    private int extractRoomNumber(String roomNumber) {
        try {
            String numStr = roomNumber.replaceAll("[^0-9]", "");
            if (numStr.isEmpty()) return 0;
            return Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 部屋タイプのマッピング
     * FileProcessor.Roomの部屋タイプをAdaptiveRoomOptimizerの形式に変換
     */
    private String mapRoomType(String originalType) {
        switch (originalType.toUpperCase()) {
            case "S":
            case "NS":
            case "ANS":
            case "ABF":
            case "AKS":
            case "ZS":
                return "S";
            case "D":
            case "ND":
            case "AND":
            case "ZD":
                return "D";
            case "T":
            case "NT":
            case "ANT":
            case "ADT":
            case "ZT":
                return "T";
            case "FD":
            case "NFD":
                return "FD";
            default:
                return originalType;
        }
    }

    /**
     * 建物判定（本館/別館）
     */
    private boolean isMainBuilding(int floor) {
        return floor <= 10; // 10階以下は本館
    }

    /**
     * 建物判定（本館/別館）
     */
    private boolean isAnnexBuilding(int floor) {
        return floor > 10; // 11階以上は別館
    }

    /**
     * 近接する部屋のグループ化
     * 効率的な清掃ルートのために近接する部屋をグループ化
     */
    public List<List<FileProcessor.Room>> groupRoomsByProximity(List<FileProcessor.Room> rooms) {
        List<List<FileProcessor.Room>> groups = new ArrayList<>();

        // 階ごとにグループ化
        Map<Integer, List<FileProcessor.Room>> roomsByFloor = rooms.stream()
                .collect(Collectors.groupingBy(r -> r.floor));

        for (Map.Entry<Integer, List<FileProcessor.Room>> entry : roomsByFloor.entrySet()) {
            List<FileProcessor.Room> floorRooms = entry.getValue();

            // 部屋番号順にソート
            floorRooms.sort(Comparator.comparing(r -> r.roomNumber));

            // 連続する部屋をグループ化
            List<FileProcessor.Room> currentGroup = new ArrayList<>();
            String lastRoomNumber = "";

            for (FileProcessor.Room room : floorRooms) {
                if (currentGroup.isEmpty() || isAdjacentRoom(lastRoomNumber, room.roomNumber)) {
                    currentGroup.add(room);
                } else {
                    // 新しいグループを開始
                    if (!currentGroup.isEmpty()) {
                        groups.add(new ArrayList<>(currentGroup));
                    }
                    currentGroup.clear();
                    currentGroup.add(room);
                }
                lastRoomNumber = room.roomNumber;
            }

            // 最後のグループを追加
            if (!currentGroup.isEmpty()) {
                groups.add(currentGroup);
            }
        }

        return groups;
    }

    /**
     * 隣接する部屋かどうかの判定
     */
    private boolean isAdjacentRoom(String room1, String room2) {
        try {
            // 数字部分を抽出して比較
            String num1 = room1.replaceAll("[^0-9]", "");
            String num2 = room2.replaceAll("[^0-9]", "");

            if (num1.isEmpty() || num2.isEmpty()) return false;

            int n1 = Integer.parseInt(num1);
            int n2 = Integer.parseInt(num2);

            // 部屋番号の差が5以下であれば隣接とみなす
            return Math.abs(n2 - n1) <= 5;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * スタッフの移動距離を計算
     */
    public double calculateMovementDistance(List<FileProcessor.Room> rooms) {
        if (rooms.size() <= 1) return 0.0;

        double totalDistance = 0.0;
        FileProcessor.Room prevRoom = null;

        for (FileProcessor.Room room : rooms) {
            if (prevRoom != null) {
                totalDistance += calculateRoomDistance(prevRoom, room);
            }
            prevRoom = room;
        }

        return totalDistance;
    }

    /**
     * 2つの部屋間の距離を計算
     */
    private double calculateRoomDistance(FileProcessor.Room room1, FileProcessor.Room room2) {
        // 階が異なる場合は追加コスト
        if (room1.floor != room2.floor) {
            return 100.0 + Math.abs(room1.floor - room2.floor) * 20.0;
        }

        // 同じ階の場合は部屋番号の差で計算
        try {
            String num1 = room1.roomNumber.replaceAll("[^0-9]", "");
            String num2 = room2.roomNumber.replaceAll("[^0-9]", "");

            int n1 = Integer.parseInt(num1);
            int n2 = Integer.parseInt(num2);

            return Math.abs(n2 - n1) * 2.0; // 1部屋あたり2ポイントのコスト
        } catch (NumberFormatException e) {
            return 10.0; // デフォルト距離
        }
    }

    /**
     * 移動効率最適化
     * 担当部屋の順序を最適化して移動距離を最小化
     */
    public List<FileProcessor.Room> optimizeRoomOrder(List<FileProcessor.Room> rooms) {
        if (rooms.size() <= 2) return new ArrayList<>(rooms);

        // 簡単な最近隣法で最適化
        List<FileProcessor.Room> optimized = new ArrayList<>();
        Set<FileProcessor.Room> unvisited = new HashSet<>(rooms);

        // 開始点は最初の部屋
        FileProcessor.Room current = rooms.get(0);
        optimized.add(current);
        unvisited.remove(current);

        // 最近隣の部屋を順次選択
        while (!unvisited.isEmpty()) {
            FileProcessor.Room nearest = null;
            double minDistance = Double.MAX_VALUE;

            for (FileProcessor.Room candidate : unvisited) {
                double distance = calculateRoomDistance(current, candidate);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = candidate;
                }
            }

            if (nearest != null) {
                optimized.add(nearest);
                unvisited.remove(nearest);
                current = nearest;
            } else {
                // 残りの部屋をそのまま追加
                optimized.addAll(unvisited);
                break;
            }
        }

        return optimized;
    }
}