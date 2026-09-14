package com.luxera.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.RiskLevel;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 数字人模块测试自己用的小应用 —— <b>刻意做成真的能下完一局</b>, 不做返回
 * {@code Optional.empty()} 的空壳。
 *
 * <p>理由: {@code AgentApplicationFlow} 的三条核心行为(轮到我才动、不解析应用状态、
 * 空 pendingActions 就不问 LLM)都只有在"对面真的有一个应用"时才被走到。一个空 mock 会让
 * 这些逻辑悄悄腐烂而所有断言仍然全绿 —— 那正是这次重构要根除的病。
 *
 * <p>它只依赖 contracts, 不认识 application-platform 一行代码: 这就是"数字人平台在没有应用
 * 平台的情况下也能独立启动、独立测试"这件事的证据。真实应用(tictactoe / gomoku / reminder)
 * 住在 application-platform 里。
 */
public class InMemoryGameApplication implements ApplicationRuntimePort {

    private static final String APP_ID = "in-memory-game";
    private static final String VERSION = "1.0.0";
    private static final String CAPABILITY = "game.play";
    private static final String ACTION_CREATE = "game.create";
    private static final String ACTION_STATE = "game.state";
    private static final String ACTION_MAKE_MOVE = "game.make_move";

    /** 合成会话的容纳上限 —— 与真实平台侧同一个缺省值, 让"满了"这件事在这里也能被走到。 */
    private static final int MAX_PARTICIPANTS = 8;

    private final ObjectMapper mapper;
    private final Map<String, ObjectNode> sessions = new ConcurrentHashMap<>();
    private final List<String> sessionsOpened = new CopyOnWriteArrayList<>();
    /** sessionId → 在场的人 (principalId)。R13 起这个双胞胎要真的记得谁在场。 */
    private final Map<String, Set<String>> participants = new ConcurrentHashMap<>();

    public InMemoryGameApplication(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ─────────────────────────── ApplicationRuntimePort ───────────────────────────

    @Override
    public List<CapabilityView> capabilities() {
        return List.of(new CapabilityView(CAPABILITY, "对弈", "回合制棋类对弈", "game"));
    }

    @Override
    public List<ApplicationView> applicationsFor(String capabilityId) {
        if (!CAPABILITY.equals(capabilityId)) return List.of();
        return List.of(new ApplicationView(APP_ID, VERSION, "内存棋局",
                "数字人模块测试自带的极简棋局", "game", List.of(CAPABILITY)));
    }

    @Override
    public List<ActionSpec> actionsOf(String applicationId) {
        return APP_ID.equals(applicationId) ? actions() : List.of();
    }

    /**
     * 内存参考应用没有会话表可写 —— 它对"开过没有"这件事没有意见, 所以只记一笔调用痕迹。
     * {@code AgentApplicationFlowTest} 之外的用例不会碰到它。
     */
    @Override
    public String ensureSession(String applicationId, InvocationContext ctx) {
        String who = ctx == null ? "?" : ctx.principalId();
        String id = "mem-session:" + applicationId + ":" + who;
        sessionsOpened.add(id);
        // R13: 光记一笔不够了 —— SessionResolver 会接着问"我在不在里面", 而一个说"你没在里面"
        // 的双胞胎会让 enter() 每次都新建一场。所以这里顺手把开它的人记成在场的人。
        if (who != null) participants.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(who);
        return id;
    }

    /** 测试可读: 谁在什么时候要求过会话。 */
    public List<String> sessionsOpened() {
        return List.copyOf(sessionsOpened);
    }

    // ─────────────────────────── 参与 (R13) ───────────────────────────
    //
    // 这一组刻意做成"真的记得谁在场": SessionResolver 的四条策略里有两条要读参与者行, 而一个
    // 一律回空列表的双胞胎会让那两条永远走不到, 于是它们腐烂在这里、测试却全绿。

    @Override
    public List<SessionRef> sessionsOf(String applicationId, InvocationContext ctx) {
        String who = ctx == null ? null : ctx.principalId();
        return sessionsOpened.stream()
                .filter(id -> id.startsWith("mem-session:" + applicationId + ":"))
                .distinct()
                .map(id -> ref(id, who))
                .toList();
    }

    @Override
    public String joinByInvitation(String token, InvocationContext ctx) {
        // 这个双胞胎没有票 —— 邀请是 application-platform 的事, 而它一行都不认识。
        // 抛而不是装作成功: 一个"什么票都收"的双胞胎会让"数字人必须兑票才能进场"这条性质
        // 在测试里消失, 而那正是 R10 的全部意义。
        throw new IllegalStateException("UNKNOWN_INVITATION: 内存参考应用不发邀请");
    }

    @Override
    public void joinSession(String sessionId, InvocationContext ctx) {
        String who = ctx == null ? null : ctx.principalId();
        if (sessionId == null || who == null) throw new IllegalArgumentException("sessionId/principalId 缺失");
        Set<String> inside = participants.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        if (inside.contains(who)) return;   // 幂等: 已经在里面就什么都不做
        if (inside.size() >= MAX_PARTICIPANTS) throw new IllegalStateException("SESSION_FULL: 人满了");
        inside.add(who);
    }

    @Override
    public void leaveSession(String sessionId, InvocationContext ctx) {
        String who = ctx == null ? null : ctx.principalId();
        Set<String> inside = participants.get(sessionId);
        if (inside == null || who == null || !inside.remove(who)) {
            throw new IllegalStateException("NOT_A_PARTICIPANT: 本来就不在场");
        }
    }

    private SessionRef ref(String sessionId, String who) {
        Set<String> inside = participants.getOrDefault(sessionId, Set.of());
        // 合成会话一律 OPEN: ensureSession 建出来的那一场没有别人, 也就没有"要不要邀请"的问题
        return new SessionRef(sessionId, APP_ID, "ACTIVE", "UNLISTED", "OPEN",
                inside.size(), MAX_PARTICIPANTS, who != null && inside.contains(who),
                PrincipalType.AGENT.name(), who, null);
    }

    @Override
    public Optional<ResourceView> read(String resourceUri) {
        ObjectNode state = sessions.get(roomIdOf(resourceUri));
        return state == null ? Optional.empty() : Optional.of(view(resourceUri, state));
    }

    /** 轮到 O 且局未终 ⇒ 只有"落子"一件可做。应用说了算, 数字人不判断。 */
    @Override
    public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) {
        ObjectNode state = sessions.get(roomIdOf(resourceUri));
        if (state == null) return List.of();
        if (!"O".equals(state.path("turn").asText()) || !state.path("winner").asText().isEmpty()) {
            return List.of();
        }
        return actions().stream().filter(a -> ACTION_MAKE_MOVE.equals(a.actionId())).toList();
    }

    @Override
    public ActionResponse execute(ActionRequest request, InvocationContext ctx) {
        String action = request == null ? null : request.action();
        String uri = request == null ? null : request.target();
        if (action == null) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT, "INVALID_ARGUMENT", "缺少 action");
        }
        return switch (action) {
            case ACTION_CREATE -> create(uri, ctx);
            case ACTION_STATE -> read(uri)
                    .map(v -> ActionResponse.success(mapper.valueToTree(Map.of("uri", v.uri())), v))
                    .orElseGet(() -> notFound(uri));
            case ACTION_MAKE_MOVE -> makeMove(uri, request.input(),
                    ctx != null && ctx.principalType() == PrincipalType.AGENT);
            default -> ActionResponse.failure(ActionStatus.NOT_FOUND, "UNKNOWN_ACTION",
                    "没有这个动作: " + action);
        };
    }

    // ─────────────────────────── 棋局本身 ───────────────────────────

    private ActionResponse create(String uri, InvocationContext ctx) {
        String roomId = roomIdOf(uri);
        if (roomId == null) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT, "INVALID_ARGUMENT", "target 不是本应用的资源");
        }
        ObjectNode state = mapper.createObjectNode();
        state.putArray("board").add("").add("").add("").add("").add("").add("").add("").add("").add("");
        state.put("turn", "X");
        state.put("winner", "");
        sessions.put(roomId, state);
        return ActionResponse.success(mapper.valueToTree(Map.of("roomId", roomId)), view(uri, state));
    }

    private ActionResponse makeMove(String uri, JsonNode input, boolean byAgent) {
        String roomId = roomIdOf(uri);
        ObjectNode state = sessions.get(roomId);
        if (state == null) return notFound(uri);
        if (!state.path("winner").asText().isEmpty()) {
            return ActionResponse.failure(ActionStatus.STATE_CONFLICT, "GAME_OVER", "棋局已终");
        }
        int position = input == null ? -1 : input.path("position").asInt(-1);
        ArrayNode board = (ArrayNode) state.path("board");
        if (position < 0 || position > 8 || !board.path(position).asText("").isEmpty()) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT, "INVALID_MOVE", "非法落子位置");
        }
        // 行动的 principal 决定执什么子 —— 应用只认 principal, 不认"这是不是 agent"
        board.set(position, mapper.getNodeFactory().textNode(byAgent ? "O" : "X"));
        state.put("turn", byAgent ? "X" : "O");
        String winner = winnerOf(board);
        state.put("winner", winner);
        return ActionResponse.success(mapper.valueToTree(Map.of("position", position)), view(uri, state));
    }

    private static String winnerOf(ArrayNode board) {
        int[][] lines = {{0,1,2},{3,4,5},{6,7,8},{0,3,6},{1,4,7},{2,5,8},{0,4,8},{2,4,6}};
        for (int[] line : lines) {
            String a = board.path(line[0]).asText("");
            if (!a.isEmpty() && a.equals(board.path(line[1]).asText(""))
                    && a.equals(board.path(line[2]).asText(""))) {
                return a;
            }
        }
        for (JsonNode cell : board) if (cell.asText("").isEmpty()) return "";
        return "DRAW";
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 便于断言: 直接看棋盘。 */
    public String cellAt(String uri, int index) {
        ObjectNode state = sessions.get(roomIdOf(uri));
        return state == null ? null : state.path("board").path(index).asText("");
    }

    private ResourceView view(String uri, ObjectNode state) {
        long marks = 0;
        for (JsonNode cell : state.path("board")) if (!cell.asText("").isEmpty()) marks++;
        return new ResourceView(uri, "game.session", APP_ID, roomIdOf(uri), state.deepCopy(),
                marks, Instant.now(), "轮到你就落在空位上。");
    }

    private static ActionResponse notFound(String uri) {
        return ActionResponse.failure(ActionStatus.NOT_FOUND, "NOT_FOUND", "资源不存在: " + uri);
    }

    private List<ActionSpec> actions() {
        return List.of(
                new ActionSpec(ACTION_CREATE, APP_ID, CAPABILITY, "开一局", PermissionLevel.WRITE,
                        RiskLevel.LOW, AttentionPolicy.AWARE, null, null),
                new ActionSpec(ACTION_STATE, APP_ID, CAPABILITY, "读局面", PermissionLevel.READ,
                        RiskLevel.NONE, AttentionPolicy.SUBCONSCIOUS, null, null),
                new ActionSpec(ACTION_MAKE_MOVE, APP_ID, CAPABILITY, "落子", PermissionLevel.WRITE,
                        RiskLevel.LOW, AttentionPolicy.FOCUSED, null, "落在空位"));
    }

    public static String newRoomUri() {
        return "game://session/" + UUID.randomUUID();
    }

    private static String roomIdOf(String uri) {
        String prefix = "game://session/";
        if (uri == null || !uri.startsWith(prefix)) return null;
        String id = uri.substring(prefix.length()).trim();
        return id.isEmpty() ? null : id;
    }
}
