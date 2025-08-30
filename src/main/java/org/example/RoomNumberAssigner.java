package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * 部屋番号自動割り当て機能
 * スタッフ割り当て結果に具体的な部屋番号を割り当てる
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
            List<FileProcessor.Room> assignedRooms = assignRoomsToStaff(assignment, availableRooms);
            result.put(assignment.staff.name, assignedRooms);

            // 割り当てた部屋を利用可能プールから削除
            availableRooms.removeAll(assignedRooms);

            LOGGER.info(String.format("%s に %d室を割り当て。残り: %d室",
                    assignment.staff.name, assignedRooms.size(), availableRooms.size()));
        }

        if (!availableRooms.isEmpty()) {
            LOGGER.warning("未割り当て部屋が残っています: " + availableRooms.size() + "室");
        }

        return result;
    }

    /**
     * 特定のスタッフに部屋を割り当て
     */
    private List<FileProcessor.Room> assignRoomsToStaff(
            AdaptiveRoomOptimizer.StaffAssignment assignment,
            List<FileProcessor.Room> availableRooms) {

        List<FileProcessor.Room> assignedRooms = new ArrayList<>();

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

            // 各部屋タイプについて割り当て
            for (Map.Entry<String, Integer> entry : allocation.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                int count = entry.getValue();

                List<FileProcessor.Room> typeRooms = floorRooms.stream()
                        .filter(room -> mapRoomType(room.roomType).equals(roomType))
                        .limit(count)
                        .collect(Collectors.toList());

                assignedRooms.addAll(typeRooms);
                floorRooms.removeAll(typeRooms);
            }

            // エコ部屋の割り当て
            if (allocation.ecoRooms > 0) {
                List<FileProcessor.Room> ecoRooms = floorRooms.stream()
                        .filter(room -> room.isEco)
                        .limit(allocation.ecoRooms)
                        .collect(Collectors.toList());

                assignedRooms.addAll(ecoRooms);
            }
        }

        // 部屋番号順にソート
        assignedRooms.sort(Comparator.comparing(room -> room.roomNumber));

        return assignedRooms;
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
                return "S";
            case "D":
            case "ND":
            case "AND":
                return "D";
            case "T":
            case "NT":
            case "ANT":
            case "ADT":
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