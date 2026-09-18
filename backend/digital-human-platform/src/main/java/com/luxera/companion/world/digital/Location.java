package com.luxera.companion.world.digital;

import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §4.4 —— <b>一个地理坐标</b>: 经纬度 + 时区 + 一个给人看的标签。
 *
 * <h2>它为什么与 {@link Place} 是<b>两个</b>东西</h2>
 * 这是本包里最容易被合并掉、也最容易因此出问题的一处区分:
 * <table border="1">
 *   <tr><th></th><th>本 record: 坐标</th><th>{@link Place}: 地点</th></tr>
 *   <tr>
 *     <td>是什么</td>
 *     <td>一个<b>测量值</b>: "北纬 31.23, 东经 121.47"</td>
 *     <td>一个<b>世界对象</b>: "实验室", 有身份、有事件、可能还有一个 {@code Environment}</td>
 *   </tr>
 *   <tr>
 *     <td>会变吗</td>
 *     <td>会被<b>修正</b> —— "我们之前把实验室的坐标记错了 200 米"</td>
 *     <td><b>不会</b>。坐标修正之后"实验室"还是同一个地方, 它的 id 与历史事件都还指向它</td>
 *   </tr>
 *   <tr>
 *     <td>谁用它</td>
 *     <td>{@code EnvironmentProvider#query(Location, Instant)} —— 查天气要知道查哪儿</td>
 *     <td>{@code ObjectId}, 事件载荷, {@code object.placed} 的 from/to</td>
 *   </tr>
 * </table>
 * 合并成一个类的后果很具体: 一旦坐标被修正, 要么 {@code ObjectId} 跟着变(历史事件全部失联),
 * 要么 id 与坐标不一致(于是"这个 id 的坐标是什么"变成一个没有答案的问题)。
 *
 * <h2>为什么没有 {@code UNKNOWN} 哨兵值</h2>
 * 地理坐标里最诱人的哨兵是 {@code (0, 0)} —— 而它在几内亚湾。
 * 于是"我们不知道她在哪"会表现成"她在几内亚湾", 而这个错误坐标会被真的送去查天气、
 * 真的返回一份热带数据、真的让她觉得热。
 *
 * <p>所以本类型<b>没有</b> unknown: 不知道就没有 {@code Location},
 * 表达方式是 {@code Optional<Location>}。哨兵值的代价总是"在离它很远的地方冒出来"。
 *
 * @param latitude  纬度, {@code [-90, 90]}, 北为正
 * @param longitude 经度, {@code [-180, 180]}, 东为正
 * @param timezone   IANA 时区标识(如 {@code "Asia/Shanghai"})。
 *                   存在的理由: 环境数据与时区强相关 —— "今天几点日出"、"现在是白天还是夜里"
 *                   都由它决定, 而它<b>不能</b>从经纬度可靠地反推出来(同一个经度上的
 *                   行政时区可以差好几个小时)
 * @param label     给人看的标签(如 {@code "上海·张江"})。可以为空 —— 空表示"没起过名字",
 *                   而不是"这个地方不存在"
 */
public record Location(double latitude, double longitude, String timezone, String label) {

    /** 地球平均半径 —— 用于 haversine。用球面近似而不是椭球: 我们只做"两个地点隔多远"的量级判断。 */
    public static final double EARTH_RADIUS_KM = 6371.0;

    /** 没有给时区时的回退值。刻意用 UTC 而不是本机时区 —— 见 {@link #timezone()} 的说明。 */
    public static final String DEFAULT_TIMEZONE = "UTC";

    public Location {
        if (Double.isNaN(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException(
                    "纬度必须在 [-90, 90] 之间, 收到 " + latitude
                            + " —— 越界的坐标会在距离计算里静默地变成一个荒谬的答案");
        }
        if (Double.isNaN(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException(
                    "经度必须在 [-180, 180] 之间, 收到 " + longitude);
        }
        timezone = timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.trim();
        label = label == null || label.isBlank() ? null : label.trim();
    }

    // ─────────────────────────── 构造 ───────────────────────────

    /** 只有坐标 —— 标签留空。 */
    public static Location of(double latitude, double longitude) {
        return new Location(latitude, longitude, DEFAULT_TIMEZONE, null);
    }

    /** 坐标 + 时区。 */
    public static Location of(double latitude, double longitude, String timezone) {
        return new Location(latitude, longitude, timezone, null);
    }

    /** 坐标 + 时区 + 标签 —— 装配用的完整形式。 */
    public static Location of(double latitude, double longitude, String timezone, String label) {
        return new Location(latitude, longitude, timezone, label);
    }

    /** 只换标签 —— 坐标修正与改名是两件事, 所以这里是两个方法而不是一个 setter。 */
    public Location withLabel(String newLabel) {
        return new Location(latitude, longitude, timezone, newLabel);
    }

    /**
     * 坐标修正 —— <b>刻意是一个新对象</b>。
     *
     * <p>"我们把实验室的坐标记错了 200 米"应当产生一个新的 {@code Location},
     * 而 {@link Place} 那边只需要换掉它持有的那一个。若本类型可变,
     * 那次修正会同时改掉已经发出去的事件里记录的坐标 —— 而历史不该被修正。
     */
    public Location movedTo(double newLatitude, double newLongitude) {
        return new Location(newLatitude, newLongitude, timezone, label);
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /** 有名字吗 —— 与 {@link #label()} 的分工是"空值在类型层面被表达成这里的一个布尔"。 */
    public Optional<String> labelIfNamed() {
        return Optional.ofNullable(label);
    }

    /**
     * 与另一个坐标的球面距离(公里)。
     *
     * <p>用 haversine 而不是平面近似: 我们的坐标可能跨越半球(她出差、她老家),
     * 而平面近似在高纬度会显著高估距离 —— 那种错误不会报错, 只会让
     * "去最近的医院要多久"这类判断悄悄偏掉。
     */
    public double distanceKmTo(Location other) {
        Objects.requireNonNull(other, "要算距离必须给另一个坐标");
        double lat1 = Math.toRadians(latitude);
        double lat2 = Math.toRadians(other.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * 是不是同一个位置 —— 给一个容差。
     *
     * <p>容差不是可选项而是必需: 一个地点的手工录入坐标与气象数据中心的格点坐标
     * 永远不会完全相等, 而精确比较会让"她并没有移动"这条判断永远为假。
     */
    public boolean isSameSpotAs(Location other, double toleranceKm) {
        return other != null && distanceKmTo(other) <= Math.max(0.0, toleranceKm);
    }

    /**
     * 同城判断的默认容差(50 公里)——
     * 与"环境数据按区域抓取"这个事实对齐: 同一城市里换一个地点,
     * 天气不会变, 所以"换了个地方"这件事在<b>环境</b>意义下没有发生。
     */
    public static final double CITY_TOLERANCE_KM = 50.0;

    /** 是不是同一个城市量级的位置 —— 环境刷新用它判断"要不要换查询点"。 */
    public boolean isSameCityAs(Location other) {
        return isSameSpotAs(other, CITY_TOLERANCE_KM);
    }

    /** 一行摘要 —— 日志与诊断用。 */
    public String describe() {
        return (label == null ? "坐标" : label)
                + "(" + String.format("%.4f", latitude) + ", " + String.format("%.4f", longitude)
                + " " + timezone + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
