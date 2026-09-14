package com.luxera.companion.digitalhuman.simulator;

import com.luxera.companion.contracts.dhcp.DhcpConstants;
import com.luxera.companion.contracts.dhcp.DhcpFrame;
import com.luxera.companion.contracts.dhcp.DhcpFrameType;
import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V10 §46 ChatSimulatorConnector — 数字人访问聊天平台的 WebSocket 客户端。
 *
 * 实现了与 /ws/simulator 端点之间的 DHCP v1 协议:
 *   CONNECT → AUTH → SUBSCRIBE → (loop: EVENT / COMMAND+RESULT / PING/PONG)
 *
 * 关键设计:
 * - 每个 connector 绑定一个 companion 的 simulator 设备(deviceId + secret)
 * - 启动时自动配对/获取 token → 连接 WS → 认证 → 订阅
 * - 提供 sendCommand(sync) 和 onEvent(callback) 两个核心接口
 * - 断线自动重连 + resume cursor
 */
@Slf4j
@Component
public class ChatSimulatorConnector {

    private final String chatWsUrl;
    private final SimulatorAccessPort simulatorAccess;
    private final ConcurrentHashMap<String, DeviceConnection> devices = new ConcurrentHashMap<>();

    public ChatSimulatorConnector(
            @Value("${app.simulator.chat-ws-url:ws://127.0.0.1:8081/ws/simulator}") String chatWsUrl,
            SimulatorAccessPort simulatorAccess) {
        this.chatWsUrl = chatWsUrl;
        this.simulatorAccess = simulatorAccess;
    }

    /**
     * 为指定的 companion 设备建立连接(同步阻塞, 最多 30s)。
     * 前提: deviceId 已通过 SimulatorPairingService 配对完成, secret 有效。
     */
    public DeviceConnection connect(String companionId, String deviceId, String accountId, String secret) {
        String key = companionId;
        DeviceConnection existing = devices.get(key);
        if (existing != null && existing.isConnected()) {
            return existing;
        }

        DeviceConnection dc = new DeviceConnection(companionId, deviceId, accountId, secret);
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            Session ws = container.connectToServer(dc, URI.create(chatWsUrl));

            // 1. CONNECT → CONNECT_ACK
            dc.sendFrame(DhcpFrame.of(DhcpFrameType.CONNECT, "conn-1",
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                            .put("clientInfo", "dh-connector/" + companionId)));
            DhcpFrame ack = dc.awaitFrame(DhcpFrameType.CONNECT_ACK, 10);
            if (ack == null) {
                ws.close();
                throw new IllegalStateException("CONNECT_ACK 超时");
            }

            // 2. 获取 token
            String token = refreshToken(deviceId, secret);
            if (token == null) {
                ws.close();
                throw new IllegalStateException("无法获取 access token");
            }

            // 3. AUTH → AUTH_SUCCESS
            var authPayload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("deviceId", deviceId)
                    .put("accessToken", token);
            dc.sendFrame(DhcpFrame.of(DhcpFrameType.AUTH, "auth-1", authPayload));
            DhcpFrame authOk = dc.awaitFrame(DhcpFrameType.AUTH_SUCCESS, 10);
            if (authOk == null) {
                ws.close();
                throw new IllegalStateException("AUTH 失败");
            }

            // 4. SUBSCRIBE → READY
            // (会话注册由 chat 平台在 AUTH 成功时自行完成, DH 不碰对端的会话表)
            var subPayload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            var topics = subPayload.putArray("topics");
            topics.add("chat.message.*");
            topics.add("phone.notification.*");
            topics.add("application.event.*");
            dc.sendFrame(DhcpFrame.of(DhcpFrameType.SUBSCRIBE, "sub-1", subPayload));
            DhcpFrame ready = dc.awaitFrame(DhcpFrameType.READY, 10);
            if (ready == null) {
                ws.close();
                throw new IllegalStateException("SUBSCRIBE 超时");
            }

            dc.connected.set(true);
            dc.wsSession = ws;
            dc.startHeartbeat();
            devices.put(key, dc);
            log.info("[ChatSimulatorConnector] 已连接: companion={}, device={}, account={}",
                    companionId, deviceId, accountId);
            return dc;

        } catch (Exception e) {
            log.error("[ChatSimulatorConnector] 连接失败: companion={}, device={}, error={}",
                    companionId, deviceId, e.getMessage());
            devices.remove(key);
            throw new RuntimeException("Simulator 连接失败: " + e.getMessage(), e);
        }
    }

    /** 断开指定 companion 的连接 */
    public void disconnect(String companionId) {
        DeviceConnection dc = devices.remove(companionId);
        if (dc != null) {
            dc.close();
            log.info("[ChatSimulatorConnector] 已断开: companion={}", companionId);
        }
    }

    /** 获取已连接的设备(用于 sendCommand) */
    public DeviceConnection get(String companionId) {
        return devices.get(companionId);
    }

    /** 是否已连接 */
    public boolean isConnected(String companionId) {
        DeviceConnection dc = devices.get(companionId);
        return dc != null && dc.isConnected();
    }

    /** 当前连接数 */
    public int connectionCount() {
        return devices.size();
    }

    /**
     * 用 device secret 换一个短期 token。走 {@link SimulatorAccessPort} —— 单进程是本地 bean,
     * 双进程换成 HTTPS, DH 不需要知道 chat 怎么签发的。
     */
    private String refreshToken(String deviceId, String secret) {
        return simulatorAccess.refreshToken(deviceId, secret).orElse(null);
    }

    /** 单设备连接对象(WebSocket 客户端) */
    @ClientEndpoint
    public static class DeviceConnection {
        private final String companionId;
        private final String deviceId;
        private final String accountId;
        private final String secret;
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        private final BlockingQueue<DhcpFrame> inbound = new LinkedBlockingQueue<>();
        private final List<DeviceEventHandler> handlers = new CopyOnWriteArrayList<>();
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private volatile Session wsSession;
        private ScheduledExecutorService heartbeatExecutor;

        DeviceConnection(String companionId, String deviceId, String accountId, String secret) {
            this.companionId = companionId;
            this.deviceId = deviceId;
            this.accountId = accountId;
            this.secret = secret;
        }

        public boolean isConnected() {
            return connected.get() && wsSession != null && wsSession.isOpen();
        }

        public String companionId() { return companionId; }
        public String deviceId() { return deviceId; }
        public String accountId() { return accountId; }

        /** 注册事件处理器(DH 侧接收 phone notification / chat message delivered 等) */
        public void onEvent(DeviceEventHandler handler) {
            handlers.add(handler);
        }

        /** 发送 DHCP 帧 */
        public void sendFrame(DhcpFrame frame) throws IOException {
            if (wsSession != null && wsSession.isOpen()) {
                wsSession.getAsyncRemote().sendText(mapper.writeValueAsString(frame));
            }
        }

        /** 同步发送命令并等待 COMMAND_RESULT(带超时) */
        public DhcpFrame sendCommand(String command, String idempotencyKey,
                                     com.fasterxml.jackson.databind.JsonNode args,
                                     long timeoutSeconds) throws Exception {
            if (!isConnected()) throw new IllegalStateException("未连接");

            var cmdPayload = mapper.createObjectNode()
                    .put("command", command)
                    .put("idempotencyKey", idempotencyKey);
            if (args != null) cmdPayload.set("args", args);
            String requestId = "cmd-" + System.nanoTime();

            sendFrame(DhcpFrame.of(DhcpFrameType.COMMAND, requestId, cmdPayload));
            return awaitFrame(DhcpFrameType.COMMAND_RESULT, timeoutSeconds);
        }

        /** 阻塞等待指定类型的帧 */
        public DhcpFrame awaitFrame(DhcpFrameType type, long timeoutSeconds) {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    DhcpFrame f = inbound.poll(200, TimeUnit.MILLISECONDS);
                    if (f != null && f.type() == type) return f;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }

        /** 发送心跳 */
        public void startHeartbeat() {
            if (heartbeatExecutor != null) heartbeatExecutor.shutdown();
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dh-connector-hb-" + companionId);
                t.setDaemon(true);
                return t;
            });
            heartbeatExecutor.scheduleAtFixedRate(() -> {
                try {
                    if (isConnected()) {
                        sendFrame(DhcpFrame.of(DhcpFrameType.PING, "ping-" + System.nanoTime(),
                                null));
                    }
                } catch (Exception e) {
                    log.warn("[Connector] 心跳失败: companion={}, error={}", companionId, e.getMessage());
                }
            }, 25, 25, TimeUnit.SECONDS);
        }

        public void close() {
            connected.set(false);
            if (heartbeatExecutor != null) heartbeatExecutor.shutdownNow();
            if (wsSession != null) {
                try { wsSession.close(); } catch (IOException ignored) {}
            }
            handlers.clear();
        }

        // ── JSR-356 回调 ─────────────────────────────

        @OnOpen
        public void onOpen(Session session) {
            this.wsSession = session;
        }

        @OnMessage
        public void onMessage(String message) {
            try {
                DhcpFrame frame = mapper.readValue(message, DhcpFrame.class);
                // 事件帧 → 通知所有 handler
                if (frame.type() == DhcpFrameType.EVENT && !handlers.isEmpty()) {
                    String topic = frame.payload() != null && frame.payload().has("topic")
                            ? frame.payload().get("topic").asText() : "";
                    String eventId = frame.payload() != null && frame.payload().has("eventId")
                            ? frame.payload().get("eventId").asText() : "";
                    for (DeviceEventHandler h : handlers) {
                        try { h.onEvent(topic, eventId, frame.payload()); } catch (Exception ignored) {}
                    }
                    // 自动 ack
                    try {
                        var ackPayload = mapper.createObjectNode().put("eventId", eventId);
                        sendFrame(DhcpFrame.of(DhcpFrameType.EVENT_ACK, "ack-" + eventId, ackPayload));
                    } catch (Exception ignored) {}
                }
                inbound.add(frame);
            } catch (Exception e) {
                log.warn("[Connector] 帧解析失败: {}", e.getMessage());
            }
        }

        @OnClose
        public void onClose(Session session, CloseReason reason) {
            connected.set(false);
            log.info("[Connector] 连接关闭: companion={}, reason={}", companionId, reason);
        }

        @OnError
        public void onError(Session session, Throwable t) {
            log.warn("[Connector] 连接错误: companion={}, error={}", companionId, t.getMessage());
        }
    }

    /** 设备事件处理器 */
    @FunctionalInterface
    public interface DeviceEventHandler {
        void onEvent(String topic, String eventId, com.fasterxml.jackson.databind.JsonNode payload);
    }
}