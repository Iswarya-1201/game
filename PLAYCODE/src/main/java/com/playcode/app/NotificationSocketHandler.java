package com.playcode.app;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
// Lightweight socket endpoint for real-time connection status; REST polling remains a fallback.
public class NotificationSocketHandler extends TextWebSocketHandler {
    private static final Set<WebSocketSession> SESSIONS = new CopyOnWriteArraySet<>();
    @Override public void afterConnectionEstablished(WebSocketSession session) { SESSIONS.add(session); }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { SESSIONS.remove(session); }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if(session.isOpen()) session.sendMessage(new TextMessage("{\"type\":\"ack\",\"message\":\"connected\"}"));
    }
}
