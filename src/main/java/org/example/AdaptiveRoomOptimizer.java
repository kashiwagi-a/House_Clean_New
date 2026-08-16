package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * 適応型清掃管理最適化システム(部屋タイプ別割り振り対応版)
 * ★改善版: シングル・ツイン別の部屋割り振りに対応
 */
public class AdaptiveRoomOptimizer {

    private static final Logger LOGGER = Logger.getLogger(AdaptiveRoomOptimizer.class.getName());

    /**
     * 大浴場清掃タイプ
     */
    public enum BathCleaningType {
        NONE("なし", 0),
        NORMAL("大浴場清掃", 4),
        WITH_DRAINING("湯抜きあり", 5);

        public final String displayName;
        public final int reduction;

        BathCleaningType(String displayName, int reduction) {
            this.displayName = displayName;
            this.reduction = reduction;
        }
    }

    /**
     * ★追加: 備品発注担当の部屋数削減数（通常清掃スタッフの基本室数からの減算量）
     * 大浴場清掃（BathCleaningType.reduction）と同じ「削減」の仕組みで使用する。
     * 大浴場清掃とは排他（同一スタッフが両方になることはない）。
     */
    public static final int SUPPLIES_ORDER_REDUCTION = 6;

    /**
     * フロア情報クラス
     */
    public static class FloorInfo {
        public final int floorNumber;
        public final Map<String, Integer> roomCounts;
        public final int ecoRooms;
        public final boolean isMainBuilding;
        public final int checkoutRooms;   // ★追加: アウト清掃(roomStatus="2")室数
        public final int stayoverRooms;   // ★追加: 連泊(roomStatus="3")室数

        // ★追加: checkout/stayover情報付きコンストラクタ
        public FloorInfo(int floorNumber, Map<String, Integer> roomCounts, int ecoRooms, boolean isMainBuilding,
                         int checkoutRooms, int stayoverRooms) {
            this.floorNumber = floorNumber;
            this.roomCounts = new HashMap<>(roomCounts);
            if (!this.roomCounts.containsKey("D")) {
                this.roomCounts.put("D", 0);
            }
            this.ecoRooms = ecoRooms;
            this.isMainBuilding = isMainBuilding;
            this.checkoutRooms = checkoutRooms;
            this.stayoverRooms = stayoverRooms;
        }

        // ★後方互換コンストラクタ（checkout/stayover情報なし）
        public FloorInfo(int floorNumber, Map<String, Integer> roomCounts, int ecoRooms, boolean isMainBuilding) {
            this(floorNumber, roomCounts, ecoRooms, isMainBuilding, 0, 0);
        }

        public int getTotalNormalRooms() {
            return roomCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * スタッフ拡張情報クラス
     */
    public static class ExtendedStaffInfo {
        public final FileProcessor.Staff staff;
        public final BathCleaningType bathCleaningType;
        public final boolean isLinenClosetCleaning;
        public final int linenClosetFloorCount;
        // ★追加: 備品発注担当フラグ
        public final boolean isSuppliesOrder;

        public ExtendedStaffInfo(FileProcessor.Staff staff,
                                 BathCleaningType bathType) {
            this(staff, bathType, false, 0, false);
        }

        public ExtendedStaffInfo(FileProcessor.Staff staff,
                                 BathCleaningType bathType,
                                 boolean isLinenClosetCleaning,
                                 int linenClosetFloorCount) {
            this(staff, bathType, isLinenClosetCleaning, linenClosetFloorCount, false);
        }

        // ★追加: 備品発注担当フラグ付きコンストラクタ
        public ExtendedStaffInfo(FileProcessor.Staff staff,
                                 BathCleaningType bathType,
                                 boolean isLinenClosetCleaning,
                                 int linenClosetFloorCount,
                                 boolean isSuppliesOrder) {
            this.staff = staff;
            this.bathCleaningType = bathType;
            this.isLinenClosetCleaning = isLinenClosetCleaning;
            this.linenClosetFloorCount = linenClosetFloorCount;
            this.isSuppliesOrder = isSuppliesOrder;
        }
    }

    /**
     * ポイント制約クラス(業者判定とフロア制限に使用)
     */
    public static class PointConstraint {
        public final String staffName;
        public final String constraintType;

        public PointConstraint(String staffName, String constraintType) {
            this.staffName = staffName;
            this.constraintType = constraintType;
        }
    }

    /**
     * 部屋割り当てクラス
     */
    public static class RoomAllocation {
        public final Map<String, Integer> roomCounts;
        public final int ecoRooms;

        public RoomAllocation(Map<String, Integer> roomCounts, int ecoRooms) {
            this.roomCounts = new HashMap<>(roomCounts);
            if (!this.roomCounts.containsKey("D")) {
                this.roomCounts.put("D", 0);
            }
            this.ecoRooms = ecoRooms;
        }

        public int getTotalRooms() {
            return roomCounts.values().stream().mapToInt(Integer::intValue).sum() + ecoRooms;
        }
    }

    /**
     * スタッフ割り当て結果クラス
     */
    public static class StaffAssignment {
        public final FileProcessor.Staff staff;
        public final List<Integer> floors;
        public final Map<Integer, RoomAllocation> mainBuildingAssignments;
        public final Map<Integer, RoomAllocation> annexBuildingAssignments;
        public final Map<Integer, RoomAllocation> roomsByFloor;
        public final BathCleaningType bathCleaningType;
        public final boolean isLinenClosetCleaning;
        public final int linenClosetFloorCount;
        // ★追加: 備品発注担当フラグ
        public final boolean isSuppliesOrder;
        // ★★追加: 具体的なリネン庫担当フロア（後処理で割り当て）
        private List<Integer> linenClosetFloors;
        // ★ECOスワップ: リネン庫成立に関する通知メッセージ（buildLinenFloorWarningsで表示）
        private final List<String> linenNotes = new ArrayList<>();

        public StaffAssignment(FileProcessor.Staff staff,
                               Map<Integer, RoomAllocation> mainAssignments,
                               Map<Integer, RoomAllocation> annexAssignments,
                               BathCleaningType bathType) {
            this(staff, mainAssignments, annexAssignments, bathType, false, 0);
        }

        public StaffAssignment(FileProcessor.Staff staff,
                               Map<Integer, RoomAllocation> mainAssignments,
                               Map<Integer, RoomAllocation> annexAssignments,
                               BathCleaningType bathType,
                               boolean isLinenClosetCleaning) {
            this(staff, mainAssignments, annexAssignments, bathType, isLinenClosetCleaning, 0);
        }

        public StaffAssignment(FileProcessor.Staff staff,
                               Map<Integer, RoomAllocation> mainAssignments,
                               Map<Integer, RoomAllocation> annexAssignments,
                               BathCleaningType bathType,
                               boolean isLinenClosetCleaning,
                               int linenClosetFloorCount) {
            this(staff, mainAssignments, annexAssignments, bathType,
                    isLinenClosetCleaning, linenClosetFloorCount, false);
        }

        // ★追加: 備品発注担当フラグ付きコンストラクタ（マスター）
        public StaffAssignment(FileProcessor.Staff staff,
                               Map<Integer, RoomAllocation> mainAssignments,
                               Map<Integer, RoomAllocation> annexAssignments,
                               BathCleaningType bathType,
                               boolean isLinenClosetCleaning,
                               int linenClosetFloorCount,
                               boolean isSuppliesOrder) {
            this.staff = staff;
            this.mainBuildingAssignments = new HashMap<>(mainAssignments);
            this.annexBuildingAssignments = new HashMap<>(annexAssignments);
            this.bathCleaningType = bathType;
            this.isLinenClosetCleaning = isLinenClosetCleaning;
            this.linenClosetFloorCount = linenClosetFloorCount;
            this.isSuppliesOrder = isSuppliesOrder;
            this.linenClosetFloors = new ArrayList<>();

            Set<Integer> allFloors = new HashSet<>();
            allFloors.addAll(mainAssignments.keySet());
            allFloors.addAll(annexAssignments.keySet());
            this.floors = new ArrayList<>(allFloors);
            Collections.sort(this.floors);

            this.roomsByFloor = new HashMap<>();
            this.roomsByFloor.putAll(mainAssignments);
            this.roomsByFloor.putAll(annexAssignments);
        }

        public int getTotalRooms() {
            return mainBuildingAssignments.values().stream().mapToInt(a -> a.getTotalRooms()).sum() +
                    annexBuildingAssignments.values().stream().mapToInt(a -> a.getTotalRooms()).sum();
        }

        public Map<Integer, RoomAllocation> getRoomsByFloor() {
            return new HashMap<>(roomsByFloor);
        }

        // ★★追加: リネン庫担当フロアのgetter/setter
        public List<Integer> getLinenClosetFloors() {
            return new ArrayList<>(linenClosetFloors);
        }

        public void setLinenClosetFloors(List<Integer> floors) {
            this.linenClosetFloors = floors != null ? new ArrayList<>(floors) : new ArrayList<>();
        }

        // ★★追加: リネン庫担当フロアの表示用テキスト
        public String getLinenClosetFloorsDisplay() {
            if (linenClosetFloors == null || linenClosetFloors.isEmpty()) {
                return "";
            }
            List<Integer> sorted = new ArrayList<>(linenClosetFloors);
            Collections.sort(sorted);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(",");
                int f = sorted.get(i);
                if (f > 20) {
                    sb.append("別館").append(f - 20).append("F");
                } else {
                    sb.append(f).append("F");
                }
            }
            return sb.toString();
        }

        // ============================================================
        // ★ECOスワップ: 階割り当てのミューテータ
        //   mainBuildingAssignments / annexBuildingAssignments / roomsByFloor / floors は
        //   コンストラクタで導出関係にあるため、必ずこのメソッド経由で同時更新する。
        // ============================================================

        /**
         * ★ECOスワップ: 指定階の割り当てを差し替える。
         * alloc が null または合計0室なら階ごと削除する。
         * @param isMain true=本館側マップ、false=別館側マップ（階番号規約と一致させること）
         */
        public void putAllocation(int floor, boolean isMain, RoomAllocation alloc) {
            Map<Integer, RoomAllocation> side = isMain ? mainBuildingAssignments : annexBuildingAssignments;
            if (alloc == null || alloc.getTotalRooms() <= 0) {
                side.remove(floor);
                roomsByFloor.remove(floor);
                floors.remove(Integer.valueOf(floor));
            } else {
                side.put(floor, alloc);
                roomsByFloor.put(floor, alloc);
                if (!floors.contains(floor)) {
                    floors.add(floor);
                    Collections.sort(floors);
                }
            }
        }

        /** ★ECOスワップ: 指定階の現在の割り当てを返す（未担当ならnull） */
        public RoomAllocation getAllocation(int floor) {
            return roomsByFloor.get(floor);
        }

        /** ★ECOスワップ: 指定階を本館側マップで保持しているか */
        public boolean holdsFloorInMain(int floor) {
            return mainBuildingAssignments.containsKey(floor);
        }

        /** ★ECOスワップ: リネン庫成立の通知メッセージを追加 */
        public void addLinenNote(String note) {
            if (note != null && !note.isEmpty()) {
                linenNotes.add(note);
            }
        }

        /** ★ECOスワップ: リネン庫成立の通知メッセージ一覧 */
        public List<String> getLinenNotes() {
            return new ArrayList<>(linenNotes);
        }

        /** ★ECOスワップ: 通知メッセージをクリア（再計算時の重複防止） */
        public void clearLinenNotes() {
            linenNotes.clear();
        }

        /**
         * ★追加: ディープコピーを作成
         * 複数解生成時にオブジェクト共有による副作用を防ぐため
         */
        public StaffAssignment deepCopy() {
            Map<Integer, RoomAllocation> mainCopy = new HashMap<>();
            for (Map.Entry<Integer, RoomAllocation> entry : mainBuildingAssignments.entrySet()) {
                mainCopy.put(entry.getKey(), new RoomAllocation(
                        new HashMap<>(entry.getValue().roomCounts),
                        entry.getValue().ecoRooms
                ));
            }

            Map<Integer, RoomAllocation> annexCopy = new HashMap<>();
            for (Map.Entry<Integer, RoomAllocation> entry : annexBuildingAssignments.entrySet()) {
                annexCopy.put(entry.getKey(), new RoomAllocation(
                        new HashMap<>(entry.getValue().roomCounts),
                        entry.getValue().ecoRooms
                ));
            }

            StaffAssignment copy = new StaffAssignment(this.staff, mainCopy, annexCopy, this.bathCleaningType, this.isLinenClosetCleaning, this.linenClosetFloorCount, this.isSuppliesOrder);
            copy.setLinenClosetFloors(this.linenClosetFloors);
            copy.linenNotes.addAll(this.linenNotes);  // ★ECOスワップ: 通知もコピー
            return copy;
        }
    }

    /**
     * ★追加: ツイン換算計算（2部屋=3換算、3部屋=5換算、それ以降+1）
     * NormalRoomDistributionDialog.StaffDistribution.calculateTwinConversion と同一ロジック
     */
    public static double calculateTwinConversion(int twinRooms) {
        if (twinRooms == 0) return 0.0;
        if (twinRooms == 1) return 1.0;
        if (twinRooms == 2) return 3.0;
        if (twinRooms == 3) return 5.0;
        if (twinRooms == 4) return 6.0;
        if (twinRooms == 5) return 8.0;
        if (twinRooms == 6) return 10.0;
        if (twinRooms == 7) return 11.0;
        if (twinRooms == 8) return 12.0;
        return 12.0 + (twinRooms - 8);
    }

    /**
     * ★追加: 換算スコア計算（部屋数指定版）
     * スコア = 通常室×1 + ツイン換算 + ECO室×0.2 + リネン庫フロア×0.4
     */
    public static double calculateConvertedScore(
            int normalRooms, int twinRooms, int ecoRooms, int linenFloors) {
        return normalRooms
                + calculateTwinConversion(twinRooms)
                + ecoRooms * 0.2
                + linenFloors * 0.4;
    }

    /**
     * ★追加: 換算スコア計算（StaffAssignment版）
     * StaffAssignment から通常室・ツイン・ECO・リネン庫フロア数を集計してスコアを返す
     */
    public static double calculateConvertedScore(StaffAssignment sa) {
        int normalRooms = 0;
        int twinRooms = 0;
        int ecoRooms = 0;

        for (RoomAllocation alloc : sa.mainBuildingAssignments.values()) {
            for (Map.Entry<String, Integer> e : alloc.roomCounts.entrySet()) {
                String rt = e.getKey();
                if ("T".equals(rt) || "TW".equals(rt) || "ツイン".equals(rt)) {
                    twinRooms += e.getValue();
                } else {
                    normalRooms += e.getValue();
                }
            }
            ecoRooms += alloc.ecoRooms;
        }
        for (RoomAllocation alloc : sa.annexBuildingAssignments.values()) {
            for (Map.Entry<String, Integer> e : alloc.roomCounts.entrySet()) {
                String rt = e.getKey();
                if ("T".equals(rt) || "TW".equals(rt) || "ツイン".equals(rt)) {
                    twinRooms += e.getValue();
                } else {
                    normalRooms += e.getValue();
                }
            }
            ecoRooms += alloc.ecoRooms;
        }

        int linenFloors = sa.isLinenClosetCleaning ? sa.linenClosetFloorCount : 0;
        return calculateConvertedScore(normalRooms, twinRooms, ecoRooms, linenFloors);
    }

    // ★★リネン庫対象階: 「リネン庫フロア設定」で選択された対象階（未販売階を含む）
    //   null = 未設定（従来動作: スタッフの清掃担当フロアのみを候補とする）
    //   非null = 対象階のみを候補とし、清掃担当外の対象階はフェーズ2で割り振る
    private static Set<Integer> linenTargetFloors = null;

    /**
     * ★★追加: リネン庫対象階を設定する（nullで従来動作に戻る）
     */
    public static void setLinenTargetFloors(Set<Integer> floors) {
        linenTargetFloors = (floors != null) ? new HashSet<>(floors) : null;
    }

    /**
     * ★★追加: 現在のリネン庫対象階を取得（未設定の場合はnull）
     */
    public static Set<Integer> getLinenTargetFloors() {
        return linenTargetFloors != null ? new HashSet<>(linenTargetFloors) : null;
    }

    /**
     * ★★追加: リネン庫割り当て結果の通知メッセージを構築する
     * ・清掃担当外のフロア（未販売階など）をリネン庫担当した場合の通知
     * ・要求階数に対して割り当てが不足した場合の警告
     * ログ出力・処理完了時のダイアログ表示に使用する。
     */
    public static List<String> buildLinenFloorWarnings(List<StaffAssignment> assignments) {
        List<String> warns = new ArrayList<>();
        if (assignments == null) return warns;
        for (StaffAssignment a : assignments) {
            if (!a.isLinenClosetCleaning || a.linenClosetFloorCount <= 0) continue;
            List<Integer> linen = a.getLinenClosetFloors();
            Set<Integer> cleaning = new HashSet<>(a.floors);
            List<Integer> external = new ArrayList<>();
            for (int f : linen) {
                if (!cleaning.contains(f)) external.add(f);
            }
            if (!external.isEmpty()) {
                Collections.sort(external);
                Set<Integer> total = new HashSet<>(cleaning);
                total.addAll(linen);
                warns.add(String.format("%s: 清掃担当外の%sをリネン庫担当します（移動フロア合計%d階）",
                        a.staff.name, formatFloorListForDisplay(external), total.size()));
            }
            if (linen.size() < a.linenClosetFloorCount) {
                warns.add(String.format("%s: リネン庫フロアが%d階分不足しています（要求%d階・割当%d階）",
                        a.staff.name, a.linenClosetFloorCount - linen.size(),
                        a.linenClosetFloorCount, linen.size()));
            }
        }
        // ★ECOスワップ: ECOのみの担当階として成立させた場合の通知を追加
        for (StaffAssignment a : assignments) {
            if (a.isLinenClosetCleaning) {
                warns.addAll(a.getLinenNotes());
            }
        }
        return warns;
    }

    /** ★★追加: フロア番号リストの表示用整形（別館は内部値+20を実階に変換） */
    private static String formatFloorListForDisplay(List<Integer> floors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < floors.size(); i++) {
            if (i > 0) sb.append(",");
            int f = floors.get(i);
            if (f > 20) {
                sb.append("別館").append(f - 20).append("F");
            } else {
                sb.append(f).append("F");
            }
        }
        return sb.toString();
    }

    // ============================================================
    // ★ECOスワップ: 清掃担当外リネン階の「ECOのみ担当階」成立処理
    //   リネン庫担当人数がフロア数を超える等で、清掃担当外の階にリネン庫が
    //   割り当てられた場合、その階のECOを保持スタッフから同数交換で移し、
    //   「ECOのみの担当階」としてリネン庫担当を成立させる。
    //   同一階内の持ち主変更＋同数交換のため、階別ECO合計・各人のECO総数・
    //   換算スコアはすべて不変。通常清掃部屋には一切触れない。
    // ============================================================

    /** ★ECOスワップ: 別館の内部階番号か（表示規約 f>20=別館 と一致） */
    private static boolean isAnnexFloorNumber(int floor) {
        return floor > 20;
    }

    /** ★ECOスワップ: スワップ計画 */
    private static class EcoSwapPlan {
        final StaffAssignment receiver;          // L: 清掃担当外リネン階を持つスタッフ
        final StaffAssignment donor;             // D: F階のECOを差し出すスタッフ
        final int floor;                         // F: 対象リネン階
        final boolean isMainFloor;               // Fの所属マップ（ドナーの保持側に一致）
        final int k;                             // 交換室数
        final Map<Integer, Integer> returnPlan;  // 見返り: Lの担当階 → 室数

        EcoSwapPlan(StaffAssignment receiver, StaffAssignment donor, int floor,
                    boolean isMainFloor, int k, Map<Integer, Integer> returnPlan) {
            this.receiver = receiver;
            this.donor = donor;
            this.floor = floor;
            this.isMainFloor = isMainFloor;
            this.k = k;
            this.returnPlan = returnPlan;
        }
    }

    /**
     * ★ECOスワップ: スタッフLが差し出せるECOのプールを収集する（担当階→差し出し可能室数）。
     * L自身のリネン階かつ通常室0の階は1室残す（自分のリネン階を空にして新たな担当外を生まないため）。
     */
    private static Map<Integer, Integer> collectOfferPool(StaffAssignment l, int excludeFloor) {
        Map<Integer, Integer> pool = new LinkedHashMap<>();
        List<Integer> lFloors = new ArrayList<>(l.floors);
        Collections.sort(lFloors);
        Set<Integer> ownLinen = new HashSet<>(l.getLinenClosetFloors());
        for (int g : lFloors) {
            if (g == excludeFloor) continue;
            RoomAllocation alloc = l.getAllocation(g);
            if (alloc == null || alloc.ecoRooms <= 0) continue;
            int normal = alloc.getTotalRooms() - alloc.ecoRooms;
            int reserve = (ownLinen.contains(g) && normal == 0) ? 1 : 0;
            int available = alloc.ecoRooms - reserve;
            if (available > 0) {
                pool.put(g, available);
            }
        }
        return pool;
    }

    /**
     * ★ECOスワップ: スタッフLの清掃担当外リネン階Fが、ECOスワップで成立可能かを判定する。
     * フェーズ2の割り当て優先度にも使用。
     */
    public static boolean canResolveByEcoSwap(StaffAssignment l, int floor,
                                              List<StaffAssignment> assignments) {
        boolean donorExists = false;
        for (StaffAssignment d : assignments) {
            if (d == l) continue;
            RoomAllocation alloc = d.getAllocation(floor);
            if (alloc == null || alloc.ecoRooms <= 0) continue;
            int kWant = d.getLinenClosetFloors().contains(floor)
                    ? alloc.ecoRooms - 1 : alloc.ecoRooms;
            if (kWant >= 1) {
                donorExists = true;
                break;
            }
        }
        if (!donorExists) return false;
        return !collectOfferPool(l, floor).isEmpty();
    }

    /** ★ECOスワップ: スタッフDの既存担当階から階gまでの最小階差 */
    private static int minFloorDistance(StaffAssignment d, int g) {
        int min = Integer.MAX_VALUE;
        for (int c : d.floors) {
            min = Math.min(min, Math.abs(c - g));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /**
     * ★ECOスワップ: スワップ計画を立てる。成立不能ならnull。
     * ドナー選定: 見返り配置でドナーに増える新規階数の最小化 > Fから完全撤退できる（階数減）を優遇
     *             > より多く移せる方を優遇 > 名前順（決定性）
     */
    private static EcoSwapPlan planEcoSwap(StaffAssignment l, int floor,
                                           List<StaffAssignment> assignments) {
        Map<Integer, Integer> pool = collectOfferPool(l, floor);
        int poolTotal = pool.values().stream().mapToInt(Integer::intValue).sum();
        if (poolTotal < 1) return null;

        // ドナー候補を名前順に列挙（決定性）
        List<StaffAssignment> donors = new ArrayList<>();
        for (StaffAssignment d : assignments) {
            if (d == l) continue;
            RoomAllocation alloc = d.getAllocation(floor);
            if (alloc != null && alloc.ecoRooms > 0) {
                donors.add(d);
            }
        }
        if (donors.isEmpty()) return null;
        donors.sort(Comparator.comparing(a -> a.staff.name));

        EcoSwapPlan best = null;
        long bestScore = Long.MAX_VALUE;
        for (int di = 0; di < donors.size(); di++) {
            final StaffAssignment d = donors.get(di);
            RoomAllocation allocF = d.getAllocation(floor);
            int kWant = allocF.ecoRooms;
            if (d.getLinenClosetFloors().contains(floor)) {
                kWant = allocF.ecoRooms - 1;  // 防御: ドナー自身のリネン階からは追い出さない
            }
            if (kWant < 1) continue;
            int k = Math.min(kWant, poolTotal);

            // 見返り配布計画: (1)Dの既清掃階 → (2)Dの担当建物と同じ建物 → (3)Dの既存階との最小階差
            final boolean dHasMain = !d.mainBuildingAssignments.isEmpty();
            final boolean dHasAnnex = !d.annexBuildingAssignments.isEmpty();
            List<Integer> poolFloors = new ArrayList<>(pool.keySet());
            poolFloors.sort(Comparator
                    .comparingInt((Integer g) -> d.roomsByFloor.containsKey(g) ? 0 : 1)
                    .thenComparingInt(g -> (isAnnexFloorNumber(g) ? dHasAnnex : dHasMain) ? 0 : 1)
                    .thenComparingInt(g -> minFloorDistance(d, g))
                    .thenComparingInt(g -> g));
            Map<Integer, Integer> returnPlan = new LinkedHashMap<>();
            int remain = k;
            int newFloorsForD = 0;
            for (int g : poolFloors) {
                if (remain <= 0) break;
                int take = Math.min(pool.get(g), remain);
                returnPlan.put(g, take);
                remain -= take;
                if (!d.roomsByFloor.containsKey(g)) {
                    newFloorsForD++;
                }
            }
            if (remain > 0) continue;  // 防御（k <= poolTotal のため通常起こらない）

            int normalOnF = allocF.getTotalRooms() - allocF.ecoRooms;
            boolean dLeavesF = (normalOnF == 0 && allocF.ecoRooms == k);

            long score = (long) newFloorsForD * 10_000L
                    - (dLeavesF ? 1_000L : 0L)
                    - (long) k * 10L
                    + di;
            if (score < bestScore) {
                bestScore = score;
                best = new EcoSwapPlan(l, d, floor, d.holdsFloorInMain(floor), k, returnPlan);
            }
        }
        return best;
    }

    /** ★ECOスワップ: 計画を実行する（同数交換・同一階内の持ち主変更のみ） */
    private static void executeEcoSwap(EcoSwapPlan plan) {
        StaffAssignment l = plan.receiver;
        StaffAssignment d = plan.donor;
        int f = plan.floor;

        // F階: D → L（k室）
        RoomAllocation allocDF = d.getAllocation(f);
        d.putAllocation(f, plan.isMainFloor,
                new RoomAllocation(allocDF.roomCounts, allocDF.ecoRooms - plan.k));
        RoomAllocation existingLF = l.getAllocation(f);
        Map<String, Integer> lCountsOnF = existingLF != null
                ? existingLF.roomCounts : Collections.emptyMap();
        int lEcoOnF = existingLF != null ? existingLF.ecoRooms : 0;
        l.putAllocation(f, plan.isMainFloor,
                new RoomAllocation(lCountsOnF, lEcoOnF + plan.k));

        // 見返り: L → D（合計k室）
        for (Map.Entry<Integer, Integer> e : plan.returnPlan.entrySet()) {
            int g = e.getKey();
            int take = e.getValue();
            boolean gIsMain = l.holdsFloorInMain(g);
            RoomAllocation allocLG = l.getAllocation(g);
            l.putAllocation(g, gIsMain,
                    new RoomAllocation(allocLG.roomCounts, allocLG.ecoRooms - take));
            RoomAllocation allocDG = d.getAllocation(g);
            if (allocDG == null) {
                d.putAllocation(g, gIsMain, new RoomAllocation(Collections.emptyMap(), take));
            } else {
                d.putAllocation(g, d.holdsFloorInMain(g),
                        new RoomAllocation(allocDG.roomCounts, allocDG.ecoRooms + take));
            }
        }
    }

    /**
     * ★ECOスワップ: 清掃担当外リネン階をECOスワップで成立させる後処理のエントリポイント。
     * assignLinenClosetFloors の「最終実行の直後」に呼ぶこと
     * （assignLinenClosetFloors は複数解パスで2回実行されるため、内部には入れない）。
     * スワップ不能な階は何もしない → 従来どおり buildLinenFloorWarnings の警告が出る。
     */
    public static void resolveExternalLinenFloorsWithEco(List<StaffAssignment> assignments) {
        if (assignments == null || assignments.isEmpty()) return;
        for (StaffAssignment a : assignments) {
            a.clearLinenNotes();
        }

        List<StaffAssignment> linenStaff = new ArrayList<>();
        for (StaffAssignment a : assignments) {
            if (a.isLinenClosetCleaning && !a.getLinenClosetFloors().isEmpty()) {
                linenStaff.add(a);
            }
        }
        if (linenStaff.isEmpty()) return;
        linenStaff.sort(Comparator.comparing(a -> a.staff.name));

        for (StaffAssignment l : linenStaff) {
            List<Integer> lf = l.getLinenClosetFloors();
            Collections.sort(lf);
            for (int f : lf) {
                if (l.roomsByFloor.containsKey(f)) continue;  // 清掃担当階なら成立済み
                String floorDisp = formatFloorListForDisplay(Collections.singletonList(f));
                EcoSwapPlan plan = planEcoSwap(l, f, assignments);
                if (plan == null) {
                    LOGGER.info(String.format(
                            "ECOスワップ: %s の清掃担当外リネン階 %s は成立不可（ECO保有者なし/交換用ECOなし）",
                            l.staff.name, floorDisp));
                    continue;
                }
                executeEcoSwap(plan);
                String note = String.format(
                        "%s: %s はECO%d室のみの担当階としてリネン庫を担当します（%s とECOを同数交換）",
                        l.staff.name, floorDisp, plan.k, plan.donor.staff.name);
                l.addLinenNote(note);
                LOGGER.info("ECOスワップ: " + note);
            }
        }
    }

    /**
     * ★★追加: リネン庫担当フロアの割り当て後処理
     * 各リネン庫担当スタッフの清掃担当フロアから、重複なくリネン庫フロアを割り振る
     * ★★変更: リネン庫対象階（linenTargetFloors）が設定されている場合は2段階方式:
     *   フェーズ1: 自分の清掃担当階∩対象階の範囲で二部マッチング（従来ロジック・フロア数は増えない）
     *   フェーズ2: 残った対象階（未販売階など）を、合計フロア数ができるだけ2階以内に収まるよう
     *              優先順位を付けて割り振る（収まらない場合は許容し、通知メッセージで知らせる）
     * 対象階が未設定（null）の場合は従来動作のまま。
     */
    public static void assignLinenClosetFloors(List<StaffAssignment> assignments) {
        // リネン庫担当スタッフを抽出
        List<StaffAssignment> linenStaff = new ArrayList<>();
        for (StaffAssignment a : assignments) {
            if (a.isLinenClosetCleaning && a.linenClosetFloorCount > 0) {
                linenStaff.add(a);
            }
        }
        if (linenStaff.isEmpty()) return;

        // ★★追加: 対象階のスナップショット（処理中の変更を防ぐ）
        final Set<Integer> targetFloors = (linenTargetFloors != null)
                ? new HashSet<>(linenTargetFloors) : null;

        LOGGER.info("=== リネン庫担当フロア割り当て開始 ===");
        if (targetFloors != null) {
            List<Integer> sortedTarget = new ArrayList<>(targetFloors);
            Collections.sort(sortedTarget);
            LOGGER.info("リネン庫対象階: " + formatFloorListForDisplay(sortedTarget)
                    + "（" + targetFloors.size() + "階）");
        }

        // --- 二部マッチングによる最適割り当て ---
        // スタッフが複数フロアを要求する場合に備え、スロット方式を採用
        // 例: スタッフAが2フロア必要 → スロット(A,0), (A,1) を作成

        // 全候補フロアを収集し、インデックス化
        // ★★変更: 対象階設定時は「清掃担当階∩対象階」に加え、全対象階（未販売階等）も候補に含める
        Set<Integer> allFloorSet = new LinkedHashSet<>();
        for (StaffAssignment staff : linenStaff) {
            for (int f : staff.floors) {
                if (targetFloors == null || targetFloors.contains(f)) {
                    allFloorSet.add(f);
                }
            }
        }
        if (targetFloors != null) {
            allFloorSet.addAll(targetFloors);
        }
        List<Integer> allFloors = new ArrayList<>(allFloorSet);
        Collections.sort(allFloors);
        Map<Integer, Integer> floorToIndex = new HashMap<>();
        for (int i = 0; i < allFloors.size(); i++) {
            floorToIndex.put(allFloors.get(i), i);
        }
        int numFloors = allFloors.size();

        // スロットを構築: (スタッフインデックス, スロット番号) → スロット通し番号
        List<int[]> slots = new ArrayList<>(); // [staffIdx, slotNum]
        List<List<Integer>> slotToFloorIndices = new ArrayList<>(); // 各スロットが接続可能なフロアインデックス

        for (int si = 0; si < linenStaff.size(); si++) {
            StaffAssignment staff = linenStaff.get(si);
            Set<Integer> staffFloorIndices = new HashSet<>();
            for (int floor : staff.floors) {
                Integer idx = floorToIndex.get(floor);
                if (idx != null) staffFloorIndices.add(idx);
            }
            List<Integer> sortedFloorIndices = new ArrayList<>(staffFloorIndices);
            Collections.sort(sortedFloorIndices);

            for (int k = 0; k < staff.linenClosetFloorCount; k++) {
                slots.add(new int[]{si, k});
                slotToFloorIndices.add(sortedFloorIndices);
            }
        }
        int numSlots = slots.size();

        // 二部マッチング: スロット → フロア（増加パス法 / Hungarian method）
        int[] slotMatch = new int[numSlots];   // slotMatch[slot] = マッチしたフロアインデックス (-1 = 未マッチ)
        int[] floorMatch = new int[numFloors]; // floorMatch[floor] = マッチしたスロット番号 (-1 = 未マッチ)
        Arrays.fill(slotMatch, -1);
        Arrays.fill(floorMatch, -1);

        for (int s = 0; s < numSlots; s++) {
            // 各スロットについて増加パスを探索
            boolean[] visited = new boolean[numFloors];
            augment(s, slotToFloorIndices, slotMatch, floorMatch, visited);
        }

        // マッチング結果を各スタッフに反映
        // まずスタッフごとに割り当てられたフロアを集める
        Map<Integer, List<Integer>> staffIdxToFloors = new HashMap<>();
        for (int s = 0; s < numSlots; s++) {
            int staffIdx = slots.get(s)[0];
            if (slotMatch[s] >= 0) {
                staffIdxToFloors.computeIfAbsent(staffIdx, k -> new ArrayList<>())
                        .add(allFloors.get(slotMatch[s]));
            }
        }

        // ★★追加: フェーズ2（対象階設定時のみ）
        // フェーズ1で埋まらなかった残りの対象階（未販売階・他スタッフの清掃階など）を、
        // リネン庫階数に残り枠があるスタッフへ割り振る。
        // 優先順位: (1)清掃担当階＋リネン庫階の合計が2階以内に収まる組み合わせを最優先
        //           (2)同条件なら合計フロア数が少なくなる組み合わせ
        //           (3)同条件なら清掃担当階に近い階（|階差|が最小）
        // どうしても2階に収まらない場合は許容する（エラーにしない）。
        if (targetFloors != null) {
            // 残り対象階（フェーズ1で未マッチのフロア）
            List<Integer> remainingFloors = new ArrayList<>();
            for (int fi = 0; fi < numFloors; fi++) {
                if (floorMatch[fi] < 0) {
                    remainingFloors.add(allFloors.get(fi));
                }
            }
            Collections.sort(remainingFloors);

            // スタッフごとの充足数と現在のフロア集合（清掃担当階＋割当済みリネン庫階）
            int staffCount = linenStaff.size();
            int[] filled = new int[staffCount];
            List<Set<Integer>> currentFloorsPerStaff = new ArrayList<>();
            for (int si = 0; si < staffCount; si++) {
                List<Integer> phase1Floors = staffIdxToFloors.getOrDefault(si, new ArrayList<>());
                filled[si] = phase1Floors.size();
                Set<Integer> cf = new HashSet<>(linenStaff.get(si).floors);
                cf.addAll(phase1Floors);  // フェーズ1割当は清掃担当階の部分集合だが念のため
                currentFloorsPerStaff.add(cf);
            }

            while (!remainingFloors.isEmpty()) {
                int bestStaff = -1;
                int bestFloor = -1;
                long bestScore = Long.MAX_VALUE;

                for (int si = 0; si < staffCount; si++) {
                    if (filled[si] >= linenStaff.get(si).linenClosetFloorCount) continue;
                    StaffAssignment cand = linenStaff.get(si);
                    Set<Integer> cf = currentFloorsPerStaff.get(si);
                    for (int f : remainingFloors) {
                        int resulting = cf.contains(f) ? cf.size() : cf.size() + 1;
                        int over = Math.max(0, resulting - 2);
                        int dist = 0;
                        if (!cf.isEmpty()) {
                            int minDist = Integer.MAX_VALUE;
                            for (int c : cf) {
                                minDist = Math.min(minDist, Math.abs(c - f));
                            }
                            dist = minDist;
                        }
                        // ★ECOスワップ: 清掃担当外階でもECOスワップで成立可能な組を優先
                        boolean external = !cand.roomsByFloor.containsKey(f);
                        boolean swapFeasible = !external || canResolveByEcoSwap(cand, f, assignments);
                        // ★ECOスワップ: 同一建物のスタッフを優先
                        boolean sameBuilding = isAnnexFloorNumber(f)
                                ? !cand.annexBuildingAssignments.isEmpty()
                                : !cand.mainBuildingAssignments.isEmpty();
                        // 2フロア超過回避 → ECO成立可能 → 合計フロア数 → 同一建物 → 近さ の順で評価
                        long score = (long) over * 1_000_000_000L
                                + (swapFeasible ? 0L : 100_000_000L)
                                + (long) resulting * 1_000_000L
                                + (sameBuilding ? 0L : 10_000L)
                                + dist;
                        if (score < bestScore) {
                            bestScore = score;
                            bestStaff = si;
                            bestFloor = f;
                        }
                    }
                }

                if (bestStaff < 0) {
                    // 受け手なし（全スタッフの階数枠が充足済み）: 残り対象階にはリネン庫担当なし
                    LOGGER.info("リネン庫担当の残り枠がないため、以下の対象階にはリネン庫担当を割り当てません: "
                            + formatFloorListForDisplay(remainingFloors));
                    break;
                }

                filled[bestStaff]++;
                currentFloorsPerStaff.get(bestStaff).add(bestFloor);
                staffIdxToFloors.computeIfAbsent(bestStaff, k -> new ArrayList<>()).add(bestFloor);
                remainingFloors.remove(Integer.valueOf(bestFloor));

                StaffAssignment bs = linenStaff.get(bestStaff);
                boolean isExternal = !bs.floors.contains(bestFloor);
                LOGGER.info(String.format("  フェーズ2: %s に %s を割り当て%s（合計%dフロア）",
                        bs.staff.name,
                        formatFloorListForDisplay(Collections.singletonList(bestFloor)),
                        isExternal ? "（清掃担当外）" : "",
                        currentFloorsPerStaff.get(bestStaff).size()));
            }
        }

        for (int si = 0; si < linenStaff.size(); si++) {
            StaffAssignment staff = linenStaff.get(si);
            List<Integer> assigned = staffIdxToFloors.getOrDefault(si, new ArrayList<>());
            Collections.sort(assigned);
            staff.setLinenClosetFloors(assigned);

            int needed = staff.linenClosetFloorCount;
            LOGGER.info(String.format("  %s: リネン庫担当フロア = %s（要求: %d階、割当: %d階）",
                    staff.staff.name, staff.getLinenClosetFloorsDisplay(),
                    needed, assigned.size()));

            if (assigned.size() < needed) {
                LOGGER.warning(String.format("  警告: %s のリネン庫フロアが不足しています（要求: %d、割当: %d）",
                        staff.staff.name, needed, assigned.size()));
            }
        }

        LOGGER.info("=== リネン庫担当フロア割り当て完了 ===");
    }

    /**
     * 二部マッチングの増加パス探索（DFS）
     * スロットsから未マッチのフロアへの増加パスを見つけ、マッチングを更新する
     *
     * @return 増加パスが見つかった場合true
     */
    private static boolean augment(int slot, List<List<Integer>> slotToFloorIndices,
                                   int[] slotMatch, int[] floorMatch, boolean[] visited) {
        for (int floorIdx : slotToFloorIndices.get(slot)) {
            if (visited[floorIdx]) continue;
            visited[floorIdx] = true;

            // このフロアが未マッチ、または既マッチ先スロットから別の増加パスが見つかる場合
            if (floorMatch[floorIdx] < 0 || augment(floorMatch[floorIdx], slotToFloorIndices, slotMatch, floorMatch, visited)) {
                slotMatch[slot] = floorIdx;
                floorMatch[floorIdx] = slot;
                return true;
            }
        }
        return false;
    }

    /**
     * 適応型設定クラス
     */
    public static class AdaptiveLoadConfig {
        public final List<FileProcessor.Staff> staff;
        public final List<ExtendedStaffInfo> extendedStaffInfo;
        public final Map<String, BathCleaningType> bathAssignments;
        public final Map<String, PointConstraint> pointConstraints;
        public final Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution;

        public AdaptiveLoadConfig(List<FileProcessor.Staff> staff,
                                  List<ExtendedStaffInfo> extendedInfo,
                                  Map<String, BathCleaningType> bathAssignments,
                                  Map<String, PointConstraint> pointConstraints,
                                  Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution) {
            this.staff = new ArrayList<>(staff);
            this.extendedStaffInfo = new ArrayList<>(extendedInfo);
            this.bathAssignments = new HashMap<>(bathAssignments);
            this.pointConstraints = new HashMap<>(pointConstraints);
            this.roomDistribution = roomDistribution != null ? new HashMap<>(roomDistribution) : null;
        }

        public static AdaptiveLoadConfig createAdaptiveConfigWithBathSelection(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType,
                List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution) {

            List<ExtendedStaffInfo> extendedInfo = new ArrayList<>();
            Map<String, BathCleaningType> bathAssignments = new HashMap<>();
            Map<String, PointConstraint> pointConstraints = new HashMap<>();

            for (FileProcessor.Staff staff : availableStaff) {
                BathCleaningType staffBathType = BathCleaningType.NONE;
                // ★追加: 備品発注担当フラグ（StaffPointConstraintから取得）
                boolean isSuppliesOrder = false;

                for (RoomAssignmentApplication.StaffPointConstraint constraint : staffConstraints) {
                    if (!constraint.staffName.equals(staff.name)) continue;

                    if (constraint.isBathCleaningStaff) {
                        staffBathType = bathType;
                    }
                    // ★追加: 備品発注担当
                    if (constraint.isSuppliesOrderStaff) {
                        isSuppliesOrder = true;
                    }

                    if (constraint.lowerMinLimit > 0 || constraint.lowerMaxLimit > 0 || constraint.upperLimit > 0) {
                        pointConstraints.put(staff.name, new PointConstraint(
                                staff.name, constraint.constraintType.displayName));
                    }
                }

                // ★★追加: リネン庫清掃情報をroomDistributionから取得
                boolean isLinenCleaning = false;
                int linenFloorCount = 0;
                if (roomDistribution != null) {
                    NormalRoomDistributionDialog.StaffDistribution dist = roomDistribution.get(staff.name);
                    if (dist != null && dist.isLinenClosetCleaning) {
                        isLinenCleaning = true;
                        linenFloorCount = dist.linenClosetFloorCount;
                    }
                    // ★追加: roomDistribution側にも備品発注フラグがあれば反映（再最適化時の保持用）
                    if (dist != null && dist.isSuppliesOrder) {
                        isSuppliesOrder = true;
                    }
                }

                extendedInfo.add(new ExtendedStaffInfo(staff, staffBathType, isLinenCleaning, linenFloorCount, isSuppliesOrder));
                bathAssignments.put(staff.name, staffBathType);
            }

            LOGGER.info(String.format("大浴場清掃担当設定完了: %d人",
                    bathAssignments.values().stream().mapToInt(t -> t != BathCleaningType.NONE ? 1 : 0).sum()));

            return new AdaptiveLoadConfig(availableStaff, extendedInfo, bathAssignments, pointConstraints, roomDistribution);
        }

        public static AdaptiveLoadConfig createAdaptiveConfigWithBathSelection(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType,
                List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints) {
            return createAdaptiveConfigWithBathSelection(
                    availableStaff, totalRooms, mainBuildingRooms, annexBuildingRooms,
                    bathType, staffConstraints, null);
        }
    }

    /**
     * 適応型最適化エンジン(部屋タイプ別割り振り対応版)
     */
    public static class AdaptiveOptimizer {
        private final List<FloorInfo> floors;
        private final AdaptiveLoadConfig config;

        public AdaptiveOptimizer(List<FloorInfo> floors, AdaptiveLoadConfig config) {
            this.floors = new ArrayList<>(floors);
            this.config = config;
        }

        // ============================================================
// AdaptiveOptimizer クラス内の optimize メソッド（CP-SAT統合版・完全版）
// ============================================================

        /**
         * 最適化実行メソッド（CP-SAT統合版）
         *
         * 1. CP-SATソルバーを優先的に使用
         * 2. CP-SATが失敗した場合は従来の方法にフォールバック
         * 3. エラーハンドリングを強化
         *
         * ★使用方法:
         * AdaptiveOptimizerクラスの既存のoptimizeメソッドを
         * このコードで置き換えてください。
         */
        /**
         * 1. CP-SATソルバーのみを使用
         * 2. 貪欲法フォールバックを削除
         * 3. 解が見つからない場合はエラーをスロー
         */
        public OptimizationResult optimize(LocalDate targetDate) {
            LOGGER.info("=== 適応型最適化を開始 ===");

            // 部屋割り振り設定の必須チェック
            if (config.roomDistribution == null || config.roomDistribution.isEmpty()) {
                throw new IllegalStateException(
                        "部屋割り振り設定が必須です。通常清掃部屋割り振りダイアログで設定してください。");
            }

            // 建物データの分離（本館・別館）
            BuildingData buildingData = separateBuildings(floors);

            // CP-SATソルバーを使用した最適化
            List<StaffAssignment> assignments;

            try {
                LOGGER.info("CP-SATソルバーを使用した最適化を開始します");
                assignments = RoomAssignmentCPSATOptimizer.optimize(buildingData, config);
                LOGGER.info("CP-SATソルバーによる最適化が成功しました");

            } catch (NoClassDefFoundError e) {
                // OR-Toolsライブラリが見つからない場合
                String errorMsg = "OR-Toolsライブラリが見つかりません。\n" +
                        "pom.xmlにortools-javaの依存関係を追加してください。";
                LOGGER.severe(errorMsg);
                throw new RuntimeException(errorMsg, e);

            } catch (UnsatisfiedLinkError e) {
                // ネイティブライブラリのロードに失敗した場合
                String errorMsg = "OR-Toolsのネイティブライブラリのロードに失敗しました。\n" +
                        "詳細: " + e.getMessage();
                LOGGER.severe(errorMsg);
                throw new RuntimeException(errorMsg, e);

            } catch (RuntimeException e) {
                // CP-SATで解が見つからなかった場合（そのまま再スロー）
                LOGGER.severe("最適化に失敗しました: " + e.getMessage());
                throw e;

            } catch (Exception e) {
                // その他の予期しないエラー
                String errorMsg = "最適化中に予期しないエラーが発生しました: " + e.getMessage();
                LOGGER.severe(errorMsg);
                e.printStackTrace();
                throw new RuntimeException(errorMsg, e);
            }

            // ★★追加: リネン庫担当フロアを割り当て
            assignLinenClosetFloors(assignments);

            // ★ECOスワップ: 清掃担当外リネン階をECOのみの担当階として成立させる
            resolveExternalLinenFloorsWithEco(assignments);

            // 最適化結果を返す
            return new OptimizationResult(assignments, config, targetDate);
        }

        /**
         * ★追加: 複数解を返す最適化メソッド
         */
        public MultiOptimizationResult optimizeMultiple(LocalDate targetDate) {
            LOGGER.info("=== 適応型複数解最適化を開始 ===");

            // 部屋割り振り設定の必須チェック
            if (config.roomDistribution == null || config.roomDistribution.isEmpty()) {
                throw new IllegalStateException(
                        "部屋割り振り設定が必須です。通常清掃部屋割り振りダイアログで設定してください。");
            }

            // 建物データの分離（本館・別館）
            BuildingData buildingData = separateBuildings(floors);

            // CP-SATソルバーを使用した複数解最適化
            RoomAssignmentCPSATOptimizer.MultiSolutionResult multiResult;

            try {
                LOGGER.info("CP-SATソルバーを使用した複数解最適化を開始します");
                multiResult = RoomAssignmentCPSATOptimizer.optimizeMultipleSolutions(buildingData, config);
                LOGGER.info(String.format("CP-SATソルバーによる複数解最適化が成功しました（%d解）",
                        multiResult.totalSolutionCount));

            } catch (NoClassDefFoundError e) {
                String errorMsg = "OR-Toolsライブラリが見つかりません。\n" +
                        "pom.xmlにortools-javaの依存関係を追加してください。";
                LOGGER.severe(errorMsg);
                throw new RuntimeException(errorMsg, e);

            } catch (UnsatisfiedLinkError e) {
                String errorMsg = "OR-Toolsのネイティブライブラリのロードに失敗しました。\n" +
                        "詳細: " + e.getMessage();
                LOGGER.severe(errorMsg);
                throw new RuntimeException(errorMsg, e);

            } catch (RuntimeException e) {
                LOGGER.severe("最適化に失敗しました: " + e.getMessage());
                throw e;

            } catch (Exception e) {
                String errorMsg = "最適化中に予期しないエラーが発生しました: " + e.getMessage();
                LOGGER.severe(errorMsg);
                e.printStackTrace();
                throw new RuntimeException(errorMsg, e);
            }

            // 複数解の最適化結果を返す
            return new MultiOptimizationResult(multiResult, config, targetDate);
        }


// ============================================================
// 補足: 修正が必要な箇所（BuildingDataのアクセス修飾子）
// ============================================================
        /**
         *roomDistribution を使用した最適化(部屋タイプ別に厳密に割り振り)
         */
        private List<StaffAssignment> optimizeWithRoomDistribution(BuildingData buildingData) {
            // フロア別の利用可能な部屋データを構築
            Map<String, FloorRoomPool> roomPools = buildRoomPools(buildingData);

            // スタッフ別の割り当て状況を追跡
            Map<String, StaffAllocationTracker> staffTrackers = new HashMap<>();
            for (ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
                staffTrackers.put(staffInfo.staff.name, new StaffAllocationTracker(staffInfo, config));
            }

            // フェーズ1: 通常清掃部屋の割り振り(部屋タイプ別に厳密)
            LOGGER.info("=== フェーズ1: 通常清掃部屋の割り振り(部屋タイプ別) ===");
            boolean phase1Success = assignNormalRoomsByTypeStrict(buildingData, roomPools, staffTrackers);

            if (!phase1Success) {
                LOGGER.severe("エラー: 通常清掃部屋を設定値通りに割り振れませんでした");
            }

            // フェーズ2: エコ清掃部屋の割り振り(全部屋割り振り)
            LOGGER.info("=== フェーズ2: エコ清掃部屋の割り振り(全部屋) ===");
            boolean phase2Success = assignAllEcoRooms(buildingData, roomPools, staffTrackers);

            if (!phase2Success) {
                LOGGER.severe("エラー: エコ清掃部屋を全て割り振れませんでした");
            }

            // 結果の構築
            List<StaffAssignment> assignments = new ArrayList<>();
            for (StaffAllocationTracker tracker : staffTrackers.values()) {
                assignments.add(tracker.buildAssignment());
            }

            // 最終検証
            validateAssignments(assignments, buildingData);

            return assignments;
        }

        /**
         * フロア別の部屋プールを構築
         */
        private Map<String, FloorRoomPool> buildRoomPools(BuildingData buildingData) {
            Map<String, FloorRoomPool> pools = new HashMap<>();

            for (FloorInfo floor : buildingData.mainFloors) {
                String key = "本館_" + floor.floorNumber;
                pools.put(key, new FloorRoomPool(floor));
            }

            for (FloorInfo floor : buildingData.annexFloors) {
                String key = "別館_" + floor.floorNumber;
                pools.put(key, new FloorRoomPool(floor));
            }

            return pools;
        }

        /**
         * 部屋タイプ別の通常清掃部屋割り振り
         */
        private boolean assignNormalRoomsByTypeStrict(BuildingData buildingData,
                                                      Map<String, FloorRoomPool> roomPools,
                                                      Map<String, StaffAllocationTracker> staffTrackers) {

            boolean allSuccess = true;

            for (Map.Entry<String, StaffAllocationTracker> entry : staffTrackers.entrySet()) {
                String staffName = entry.getKey();
                StaffAllocationTracker tracker = entry.getValue();

                NormalRoomDistributionDialog.StaffDistribution dist = config.roomDistribution.get(staffName);
                if (dist == null) {
                    LOGGER.warning("スタッフ " + staffName + " の部屋割り振り設定が見つかりません");
                    continue;
                }

                LOGGER.info(String.format("%s: 本館S%d室+T%d室, 別館S%d室+T%d室を厳密に割り振ります",
                        staffName,
                        dist.mainSingleAssignedRooms, dist.mainTwinAssignedRooms,
                        dist.annexSingleAssignedRooms, dist.annexTwinAssignedRooms));

                // 本館シングル等の割り当て
                if (dist.mainSingleAssignedRooms > 0) {
                    int assigned = assignRoomsByType(
                            buildingData.mainFloors, roomPools, tracker,
                            dist.mainSingleAssignedRooms, true, RoomType.SINGLE);

                    if (assigned != dist.mainSingleAssignedRooms) {
                        LOGGER.severe(String.format("エラー: %s 本館シングル等 設定%d室に対し%d室しか割り振れませんでした",
                                staffName, dist.mainSingleAssignedRooms, assigned));
                        allSuccess = false;
                    }
                }

                // 本館ツインの割り当て
                if (dist.mainTwinAssignedRooms > 0) {
                    int assigned = assignRoomsByType(
                            buildingData.mainFloors, roomPools, tracker,
                            dist.mainTwinAssignedRooms, true, RoomType.TWIN);

                    if (assigned != dist.mainTwinAssignedRooms) {
                        LOGGER.severe(String.format("エラー: %s 本館ツイン 設定%d室に対し%d室しか割り振れませんでした",
                                staffName, dist.mainTwinAssignedRooms, assigned));
                        allSuccess = false;
                    }
                }

                // 別館シングル等の割り当て
                if (dist.annexSingleAssignedRooms > 0) {
                    int assigned = assignRoomsByType(
                            buildingData.annexFloors, roomPools, tracker,
                            dist.annexSingleAssignedRooms, false, RoomType.SINGLE);

                    if (assigned != dist.annexSingleAssignedRooms) {
                        LOGGER.severe(String.format("エラー: %s 別館シングル等 設定%d室に対し%d室しか割り振れませんでした",
                                staffName, dist.annexSingleAssignedRooms, assigned));
                        allSuccess = false;
                    }
                }

                // 別館ツインの割り当て
                if (dist.annexTwinAssignedRooms > 0) {
                    int assigned = assignRoomsByType(
                            buildingData.annexFloors, roomPools, tracker,
                            dist.annexTwinAssignedRooms, false, RoomType.TWIN);

                    if (assigned != dist.annexTwinAssignedRooms) {
                        LOGGER.severe(String.format("エラー: %s 別館ツイン 設定%d室に対し%d室しか割り振れませんでした",
                                staffName, dist.annexTwinAssignedRooms, assigned));
                        allSuccess = false;
                    }
                }

                int totalAssigned = tracker.getTotalNormalRooms();
                LOGGER.info(String.format("%s: 通常清掃 合計%d室割り振り完了(フロア数:%d)",
                        staffName, totalAssigned, tracker.getNormalFloorCount()));
            }

            return allSuccess;
        }

        /**
         * ★新規: 部屋タイプを指定した割り振り
         */
        private int assignRoomsByType(List<FloorInfo> floors,
                                      Map<String, FloorRoomPool> roomPools,
                                      StaffAllocationTracker tracker,
                                      int targetRooms,
                                      boolean isMainBuilding,
                                      RoomType roomType) {

            String buildingName = isMainBuilding ? "本館" : "別館";
            String roomTypeName = roomType == RoomType.SINGLE ? "シングル等" : "ツイン";
            int assigned = 0;
            int maxFloors = tracker.getMaxNormalFloors();
            int currentNormalFloors = tracker.getNormalFloorCount();

            for (FloorInfo floor : floors) {
                if (assigned >= targetRooms) break;

                // フロア制限チェック
                if (!tracker.isContractor() && currentNormalFloors >= maxFloors) {
                    LOGGER.warning(String.format("  %dF: 通常清掃フロア制限(%d階)に達しました",
                            floor.floorNumber, maxFloors));
                    break;
                }

                String poolKey = buildingName + "_" + floor.floorNumber;
                FloorRoomPool pool = roomPools.get(poolKey);

                if (pool == null) {
                    continue;
                }

                int available = pool.getRoomsAvailableByType(roomType);
                if (available == 0) {
                    continue;
                }

                int remaining = targetRooms - assigned;
                int toAssign = Math.min(remaining, available);

                Map<String, Integer> assignedRooms = pool.allocateRoomsByType(toAssign, roomType);
                int actualAssigned = assignedRooms.values().stream().mapToInt(Integer::intValue).sum();

                if (actualAssigned > 0) {
                    tracker.addNormalRooms(floor.floorNumber, assignedRooms, isMainBuilding);
                    assigned += actualAssigned;
                    currentNormalFloors = tracker.getNormalFloorCount();

                    LOGGER.info(String.format("  %dF: %s%d室割り振り(累計%d/%d室)",
                            floor.floorNumber, roomTypeName, actualAssigned, assigned, targetRooms));
                }
            }

            if (assigned < targetRooms) {
                LOGGER.warning(String.format("  %s%s: 目標%d室に対し%d室しか割り振れませんでした(不足%d室)",
                        buildingName, roomTypeName, targetRooms, assigned, targetRooms - assigned));
            }

            return assigned;
        }

        /**
         * 全エコ清掃部屋を確実に割り振る
         */
        private boolean assignAllEcoRooms(BuildingData buildingData,
                                          Map<String, FloorRoomPool> roomPools,
                                          Map<String, StaffAllocationTracker> staffTrackers) {

            // 全フロアのリストを作成
            List<FloorInfo> allFloors = new ArrayList<>();
            allFloors.addAll(buildingData.mainFloors);
            allFloors.addAll(buildingData.annexFloors);
            Collections.sort(allFloors, Comparator.comparingInt(f -> f.floorNumber));

            // スタッフのリストを作成
            List<String> staffNames = new ArrayList<>(staffTrackers.keySet());

            int totalEcoRooms = countTotalEcoRooms(buildingData);
            int assignedEcoRooms = 0;
            int staffIndex = 0;

            LOGGER.info(String.format("エコ清掃総数: %d室", totalEcoRooms));

            // 全エコ部屋を割り振るまでループ
            for (FloorInfo floor : allFloors) {
                String buildingName = floor.isMainBuilding ? "本館" : "別館";
                String poolKey = buildingName + "_" + floor.floorNumber;
                FloorRoomPool pool = roomPools.get(poolKey);

                if (pool == null || pool.getEcoRoomsAvailable() == 0) {
                    continue;
                }

                int floorEcoRooms = pool.getEcoRoomsAvailable();
                LOGGER.info(String.format("%s %dF: エコ%d室を割り振り中",
                        buildingName, floor.floorNumber, floorEcoRooms));

                // このフロアの全エコ部屋を割り振る
                while (pool.getEcoRoomsAvailable() > 0) {
                    boolean assigned = false;
                    int attempts = 0;

                    // 全スタッフを試行
                    while (attempts < staffNames.size()) {
                        String staffName = staffNames.get(staffIndex % staffNames.size());
                        StaffAllocationTracker tracker = staffTrackers.get(staffName);
                        staffIndex++;
                        attempts++;

                        // フロア制限チェック
                        if (!tracker.canAssignEcoFloor(floor.floorNumber)) {
                            continue;
                        }

                        // 1部屋割り当て
                        int allocatedEco = pool.allocateEcoRooms(1);
                        if (allocatedEco > 0) {
                            tracker.addEcoRooms(floor.floorNumber, allocatedEco, floor.isMainBuilding);
                            assigned = true;
                            assignedEcoRooms++;
                            break;
                        }
                    }

                    if (!assigned) {
                        LOGGER.severe(String.format("エラー: %dFのエコ部屋を割り振れるスタッフがいません(残り%d室)",
                                floor.floorNumber, pool.getEcoRoomsAvailable()));
                        return false;
                    }
                }

                LOGGER.info(String.format("  %dF: エコ%d室の割り振り完了", floor.floorNumber, floorEcoRooms));
            }

            LOGGER.info(String.format("エコ清掃割り振り完了: %d/%d室", assignedEcoRooms, totalEcoRooms));

            // 各スタッフの状況をログ出力
            for (Map.Entry<String, StaffAllocationTracker> entry : staffTrackers.entrySet()) {
                StaffAllocationTracker tracker = entry.getValue();
                LOGGER.info(String.format("  %s: 通常%d室, エコ%d室, フロア数%d",
                        entry.getKey(), tracker.getTotalNormalRooms(),
                        tracker.getTotalEcoRooms(), tracker.getFloorCount()));
            }

            return assignedEcoRooms == totalEcoRooms;
        }

        /**
         * 総エコ部屋数を計算
         */
        private int countTotalEcoRooms(BuildingData buildingData) {
            int total = 0;
            for (FloorInfo floor : buildingData.mainFloors) {
                total += floor.ecoRooms;
            }
            for (FloorInfo floor : buildingData.annexFloors) {
                total += floor.ecoRooms;
            }
            return total;
        }

        /**
         * 割り当て結果の検証
         */
        private void validateAssignments(List<StaffAssignment> assignments, BuildingData buildingData) {
            LOGGER.info("=== 割り当て結果の検証 ===");

            // 割り当てられた部屋を集計
            int totalAssignedNormal = 0;
            int totalAssignedEco = 0;

            for (StaffAssignment assignment : assignments) {
                for (RoomAllocation allocation : assignment.roomsByFloor.values()) {
                    totalAssignedNormal += allocation.roomCounts.values().stream()
                            .mapToInt(Integer::intValue).sum();
                    totalAssignedEco += allocation.ecoRooms;
                }
            }

            // 実際の部屋数
            int actualNormalRooms = buildingData.mainRoomCount + buildingData.annexRoomCount;
            int actualEcoRooms = countTotalEcoRooms(buildingData);

            LOGGER.info(String.format("通常清掃: 割り当て%d室 / 実際%d室",
                    totalAssignedNormal, actualNormalRooms));
            LOGGER.info(String.format("エコ清掃: 割り当て%d室 / 実際%d室",
                    totalAssignedEco, actualEcoRooms));

            if (totalAssignedNormal != actualNormalRooms) {
                LOGGER.severe(String.format("エラー: 通常清掃部屋数が一致しません(差分%d室)",
                        actualNormalRooms - totalAssignedNormal));
            }

            if (totalAssignedEco != actualEcoRooms) {
                LOGGER.severe(String.format("エラー: エコ清掃部屋数が一致しません(差分%d室)",
                        actualEcoRooms - totalAssignedEco));
            }

            if (totalAssignedNormal == actualNormalRooms && totalAssignedEco == actualEcoRooms) {
                LOGGER.info("✓ 全部屋の割り振りが完了しました");
            }
        }

        private BuildingData separateBuildings(List<FloorInfo> allFloors) {
            List<FloorInfo> mainFloors = allFloors.stream()
                    .filter(f -> f.isMainBuilding)
                    .sorted(Comparator.comparingInt(f -> f.floorNumber))
                    .collect(Collectors.toList());

            List<FloorInfo> annexFloors = allFloors.stream()
                    .filter(f -> !f.isMainBuilding)
                    .sorted(Comparator.comparingInt(f -> f.floorNumber))
                    .collect(Collectors.toList());

            int mainRoomCount = mainFloors.stream()
                    .mapToInt(f -> f.getTotalNormalRooms())
                    .sum();

            int annexRoomCount = annexFloors.stream()
                    .mapToInt(f -> f.getTotalNormalRooms())
                    .sum();

            return new BuildingData(mainFloors, annexFloors, mainRoomCount, annexRoomCount);
        }
    }

    /**
     * ★新規: 部屋タイプ列挙型
     */
    private enum RoomType {
        SINGLE,  // シングル等(T, NT, ANT, ADT以外の全て)
        TWIN     // ツイン(本館: T, NT / 別館: ANT, ADT)
    }

    /**
     * ★改善版: フロア部屋プールクラス(部屋タイプ別割り振り対応)
     */
    private static class FloorRoomPool {
        private final FloorInfo floor;
        private final Map<String, Integer> normalRoomsAvailable;
        private int ecoRoomsAvailable;

        FloorRoomPool(FloorInfo floor) {
            this.floor = floor;
            this.normalRoomsAvailable = new HashMap<>(floor.roomCounts);
            this.ecoRoomsAvailable = floor.ecoRooms;
        }

        int getNormalRoomsAvailable() {
            return normalRoomsAvailable.values().stream().mapToInt(Integer::intValue).sum();
        }

        int getEcoRoomsAvailable() {
            return ecoRoomsAvailable;
        }

        /**
         * ★新規: 部屋タイプ別の利用可能部屋数を取得
         */
        int getRoomsAvailableByType(RoomType roomType) {
            int count = 0;
            for (Map.Entry<String, Integer> entry : normalRoomsAvailable.entrySet()) {
                String type = entry.getKey();
                if (isRoomTypeMatch(type, roomType, floor.isMainBuilding)) {
                    count += entry.getValue();
                }
            }
            return count;
        }

        /**
         * ★新規: 部屋タイプコードが指定されたタイプに一致するか判定
         */
        private boolean isRoomTypeMatch(String roomTypeCode, RoomType roomType, boolean isMainBuilding) {
            if (roomType == RoomType.TWIN) {
                // 本館ツイン: T, NT
                // 別館ツイン: ANT, ADT
                if (isMainBuilding) {
                    return "T".equals(roomTypeCode) || "NT".equals(roomTypeCode);
                } else {
                    return "ANT".equals(roomTypeCode) || "ADT".equals(roomTypeCode);
                }
            } else {
                // シングル等: ツイン以外の全て
                if (isMainBuilding) {
                    return !"T".equals(roomTypeCode) && !"NT".equals(roomTypeCode);
                } else {
                    return !"ANT".equals(roomTypeCode) && !"ADT".equals(roomTypeCode);
                }
            }
        }

        /**
         * ★改善版: 部屋タイプを指定して通常部屋を割り当て
         */
        Map<String, Integer> allocateRoomsByType(int count, RoomType roomType) {
            Map<String, Integer> allocated = new HashMap<>();
            int remaining = count;

            // 指定された部屋タイプに一致する部屋タイプをフィルタリング
            List<String> matchingTypes = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : normalRoomsAvailable.entrySet()) {
                if (isRoomTypeMatch(entry.getKey(), roomType, floor.isMainBuilding) && entry.getValue() > 0) {
                    matchingTypes.add(entry.getKey());
                }
            }

            // 多い順にソート
            matchingTypes.sort((a, b) -> Integer.compare(
                    normalRoomsAvailable.get(b), normalRoomsAvailable.get(a)));

            // 各タイプから順に割り当て
            for (String type : matchingTypes) {
                if (remaining <= 0) break;

                int available = normalRoomsAvailable.get(type);
                int toAllocate = Math.min(remaining, available);

                if (toAllocate > 0) {
                    allocated.put(type, toAllocate);
                    normalRoomsAvailable.put(type, available - toAllocate);
                    remaining -= toAllocate;
                }
            }

            if (remaining > 0) {
                String roomTypeName = roomType == RoomType.SINGLE ? "シングル等" : "ツイン";
                LOGGER.warning(String.format("警告: %dF %s 要求%d室だが%d室しか割り当てられませんでした",
                        floor.floorNumber, roomTypeName, count, count - remaining));
            }

            return allocated;
        }

        /**
         * ★既存: エコ部屋を割り当て
         */
        int allocateEcoRooms(int count) {
            if (count > ecoRoomsAvailable) {
                LOGGER.warning(String.format("警告: %dF エコ要求%d室だが利用可能は%d室のみ",
                        floor.floorNumber, count, ecoRoomsAvailable));
                count = ecoRoomsAvailable;
            }

            ecoRoomsAvailable -= count;
            return count;
        }
    }

    /**
     * スタッフ割り当て追跡クラス
     */
    private static class StaffAllocationTracker {
        private final ExtendedStaffInfo staffInfo;
        private final AdaptiveLoadConfig config;
        private final Map<Integer, Map<String, Integer>> mainNormalRooms = new HashMap<>();
        private final Map<Integer, Map<String, Integer>> annexNormalRooms = new HashMap<>();
        private final Map<Integer, Integer> mainEcoRooms = new HashMap<>();
        private final Map<Integer, Integer> annexEcoRooms = new HashMap<>();

        StaffAllocationTracker(ExtendedStaffInfo staffInfo, AdaptiveLoadConfig config) {
            this.staffInfo = staffInfo;
            this.config = config;
        }

        void addNormalRooms(int floor, Map<String, Integer> rooms, boolean isMainBuilding) {
            if (isMainBuilding) {
                mainNormalRooms.merge(floor, new HashMap<>(rooms), (oldMap, newMap) -> {
                    Map<String, Integer> merged = new HashMap<>(oldMap);
                    for (Map.Entry<String, Integer> entry : newMap.entrySet()) {
                        merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                    return merged;
                });
            } else {
                annexNormalRooms.merge(floor, new HashMap<>(rooms), (oldMap, newMap) -> {
                    Map<String, Integer> merged = new HashMap<>(oldMap);
                    for (Map.Entry<String, Integer> entry : newMap.entrySet()) {
                        merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                    return merged;
                });
            }
        }

        void addEcoRooms(int floor, int count, boolean isMainBuilding) {
            if (isMainBuilding) {
                mainEcoRooms.merge(floor, count, Integer::sum);
            } else {
                annexEcoRooms.merge(floor, count, Integer::sum);
            }
        }

        int getTotalNormalRooms() {
            int total = 0;
            for (Map<String, Integer> rooms : mainNormalRooms.values()) {
                total += rooms.values().stream().mapToInt(Integer::intValue).sum();
            }
            for (Map<String, Integer> rooms : annexNormalRooms.values()) {
                total += rooms.values().stream().mapToInt(Integer::intValue).sum();
            }
            return total;
        }

        int getTotalEcoRooms() {
            return mainEcoRooms.values().stream().mapToInt(Integer::intValue).sum() +
                    annexEcoRooms.values().stream().mapToInt(Integer::intValue).sum();
        }

        int getFloorCount() {
            Set<Integer> floors = new HashSet<>();
            floors.addAll(mainNormalRooms.keySet());
            floors.addAll(annexNormalRooms.keySet());
            floors.addAll(mainEcoRooms.keySet());
            floors.addAll(annexEcoRooms.keySet());
            return floors.size();
        }

        int getNormalFloorCount() {
            Set<Integer> floors = new HashSet<>();
            floors.addAll(mainNormalRooms.keySet());
            floors.addAll(annexNormalRooms.keySet());
            return floors.size();
        }

        boolean isContractor() {
            PointConstraint constraint = config.pointConstraints.get(staffInfo.staff.name);
            return constraint != null && "業者制限".equals(constraint.constraintType);
        }

        int getMaxNormalFloors() {
            if (isContractor()) {
                return Integer.MAX_VALUE;
            }
            // 大浴清掃スタッフは通常清掃1階のみ
            return staffInfo.bathCleaningType != BathCleaningType.NONE ? 1 : 2;
        }

        boolean canAssignEcoFloor(int floor) {
            Set<Integer> allFloors = new HashSet<>();
            allFloors.addAll(mainNormalRooms.keySet());
            allFloors.addAll(annexNormalRooms.keySet());
            allFloors.addAll(mainEcoRooms.keySet());
            allFloors.addAll(annexEcoRooms.keySet());

            // 既に割り当てられているフロアなら常にOK
            if (allFloors.contains(floor)) {
                return true;
            }

            // 業者は制限なし
            if (isContractor()) {
                return true;
            }

            // 通常スタッフ: 合計2階まで
            return allFloors.size() < 2;
        }

        StaffAssignment buildAssignment() {
            Map<Integer, RoomAllocation> mainAssignments = new HashMap<>();
            Map<Integer, RoomAllocation> annexAssignments = new HashMap<>();

            // 本館の割り当て
            Set<Integer> mainFloors = new HashSet<>();
            mainFloors.addAll(mainNormalRooms.keySet());
            mainFloors.addAll(mainEcoRooms.keySet());

            for (Integer floor : mainFloors) {
                Map<String, Integer> rooms = mainNormalRooms.getOrDefault(floor, new HashMap<>());
                int eco = mainEcoRooms.getOrDefault(floor, 0);
                mainAssignments.put(floor, new RoomAllocation(rooms, eco));
            }

            // 別館の割り当て
            Set<Integer> annexFloors = new HashSet<>();
            annexFloors.addAll(annexNormalRooms.keySet());
            annexFloors.addAll(annexEcoRooms.keySet());

            for (Integer floor : annexFloors) {
                Map<String, Integer> rooms = annexNormalRooms.getOrDefault(floor, new HashMap<>());
                int eco = annexEcoRooms.getOrDefault(floor, 0);
                annexAssignments.put(floor, new RoomAllocation(rooms, eco));
            }

            return new StaffAssignment(staffInfo.staff, mainAssignments, annexAssignments,
                    staffInfo.bathCleaningType, staffInfo.isLinenClosetCleaning, staffInfo.linenClosetFloorCount,
                    staffInfo.isSuppliesOrder);
        }
    }

    /**
     * 建物データクラス
     */
    public static class BuildingData  {
        final List<FloorInfo> mainFloors;
        final List<FloorInfo> annexFloors;
        final int mainRoomCount;
        final int annexRoomCount;

        BuildingData(List<FloorInfo> mainFloors, List<FloorInfo> annexFloors,
                     int mainRoomCount, int annexRoomCount) {
            this.mainFloors = mainFloors;
            this.annexFloors = annexFloors;
            this.mainRoomCount = mainRoomCount;
            this.annexRoomCount = annexRoomCount;
        }
    }

    /**
     * 最適化結果クラス
     */
    public static class OptimizationResult {
        public final List<StaffAssignment> assignments;
        public final AdaptiveLoadConfig config;
        public final LocalDate targetDate;

        public OptimizationResult(List<StaffAssignment> assignments,
                                  AdaptiveLoadConfig config,
                                  LocalDate targetDate) {
            this.assignments = new ArrayList<>(assignments);
            this.config = config;
            this.targetDate = targetDate;
        }

        public void printDetailedSummary() {
            System.out.println("\n=== 最適化結果サマリー ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("スタッフ数: %d人\n", assignments.size());

            int totalRooms = 0;

            for (StaffAssignment assignment : assignments) {
                totalRooms += assignment.getTotalRooms();

                System.out.printf("\n%s (%s):\n",
                        assignment.staff.name,
                        assignment.bathCleaningType.displayName);
                System.out.printf("  部屋数: %d室\n", assignment.getTotalRooms());
                System.out.printf("  担当フロア: %s\n", assignment.floors);
            }

            System.out.printf("\n総計:\n");
            System.out.printf("  部屋数: %d室\n", totalRooms);
        }
    }

    /**
     * ★追加: 複数解を保持する最適化結果クラス
     */
    public static class MultiOptimizationResult {
        public final RoomAssignmentCPSATOptimizer.MultiSolutionResult multiSolutionResult;
        public final AdaptiveLoadConfig config;
        public final LocalDate targetDate;
        public final int totalSolutionCount;
        private int currentIndex;

        public MultiOptimizationResult(
                RoomAssignmentCPSATOptimizer.MultiSolutionResult multiSolutionResult,
                AdaptiveLoadConfig config,
                LocalDate targetDate) {
            this.multiSolutionResult = multiSolutionResult;
            this.config = config;
            this.targetDate = targetDate;
            this.totalSolutionCount = multiSolutionResult.totalSolutionCount;
            this.currentIndex = 0;

            // ★★追加: 各解のリネン庫担当フロアを割り当て
            for (int i = 0; i < totalSolutionCount; i++) {
                RoomAssignmentCPSATOptimizer.OptimizationResultWithUnassigned result =
                        multiSolutionResult.getSolution(i);
                if (result != null && result.assignments != null) {
                    assignLinenClosetFloors(result.assignments);
                    // ★ECOスワップ: 清掃担当外リネン階をECOのみの担当階として成立させる
                    // （assignLinenClosetFloors の最終実行の直後に呼ぶこと）
                    resolveExternalLinenFloorsWithEco(result.assignments);
                }
            }
        }

        /**
         * 指定インデックスの解を取得
         */
        public List<StaffAssignment> getAssignments(int index) {
            RoomAssignmentCPSATOptimizer.OptimizationResultWithUnassigned result =
                    multiSolutionResult.getSolution(index);
            return result != null ? result.assignments : new ArrayList<>();
        }

        /**
         * 現在の解を取得
         */
        public List<StaffAssignment> getCurrentAssignments() {
            return getAssignments(currentIndex);
        }

        /**
         * 指定インデックスの未割当情報を取得
         */
        public RoomAssignmentCPSATOptimizer.UnassignedRooms getUnassignedRooms(int index) {
            RoomAssignmentCPSATOptimizer.OptimizationResultWithUnassigned result =
                    multiSolutionResult.getSolution(index);
            return result != null ? result.unassignedRooms : new RoomAssignmentCPSATOptimizer.UnassignedRooms();
        }

        /**
         * 現在のインデックスを取得
         */
        public int getCurrentIndex() {
            return currentIndex;
        }

        /**
         * 現在のインデックスを設定
         */
        public void setCurrentIndex(int index) {
            if (index >= 0 && index < totalSolutionCount) {
                this.currentIndex = index;
            }
        }

        /**
         * 複数解があるかどうか
         */
        public boolean hasMultipleSolutions() {
            return totalSolutionCount > 1;
        }

        /**
         * 従来互換: 最初の解をOptimizationResultとして取得
         */
        public OptimizationResult toSingleResult() {
            return new OptimizationResult(getAssignments(0), config, targetDate);
        }

        /**
         * 指定インデックスの解をOptimizationResultとして取得
         */
        public OptimizationResult toSingleResult(int index) {
            return new OptimizationResult(getAssignments(index), config, targetDate);
        }

        public void printDetailedSummary() {
            System.out.println("\n=== 複数解最適化結果サマリー ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("生成された解の数: %d\n", totalSolutionCount);

            for (int i = 0; i < totalSolutionCount; i++) {
                List<StaffAssignment> assignments = getAssignments(i);
                int totalRooms = assignments.stream().mapToInt(StaffAssignment::getTotalRooms).sum();
                RoomAssignmentCPSATOptimizer.UnassignedRooms unassigned = getUnassignedRooms(i);

                System.out.printf("\n--- 解 %d/%d ---\n", i + 1, totalSolutionCount);
                System.out.printf("  スタッフ数: %d人\n", assignments.size());
                System.out.printf("  総部屋数: %d室\n", totalRooms);
                System.out.printf("  未割当: %d室\n", unassigned.getTotalUnassigned());
            }
        }
    }
}