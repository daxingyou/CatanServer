package com.jokerbee.gateway;

import com.jokerbee.support.GameConstant;
import com.jokerbee.support.MessageCode;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gateway 连接对象;
 *
 * @author: Joker
 * @date: Created in 2020/11/3 16:05
 * @version: 1.0
 */
public class GatewayConnector {
    private static final Logger logger = LoggerFactory.getLogger("GatewayConnector");

    private final Vertx vertx;
    /** 链接的 socket */
    private final ServerWebSocket webSocket;

    /** 连接绑定的账号 */
    private String bindAccount;

    private MessageConsumer<String> consumer;

    private final AtomicBoolean closeTag = new AtomicBoolean(false);

    public GatewayConnector(Vertx vertx, ServerWebSocket webSocket) {
        this.vertx = vertx;
        this.webSocket = webSocket;
        registerHandler();
    }

    private void registerHandler() {
        final String handlerId = webSocket.textHandlerID();
        webSocket.handler(this::onMessage);
        webSocket.closeHandler(v -> close());
        webSocket.exceptionHandler(e -> logger.error("websocket cache exception:{}.", handlerId, e));
        logger.info("websocket connect: {}, remote:{}, handlerId:{}", webSocket.path(), webSocket.remoteAddress(), handlerId);
    }

    private void onMessage(Buffer buf) {
        // 已绑定, 直接发送
        if (bindAccount != null) {
            vertx.eventBus().send(bindAccount + GameConstant.API_TAIL_MESSAGE_DISPATCH, buf);
            return;
        }
        connectorBindAccount(buf);
    }

    private void connectorBindAccount(Buffer buf) {
        try {
            JsonObject message = buf.toJsonObject();
            int messageType = message.getInteger("type");
            if (messageType == MessageCode.CS_ACCOUNT_LOGIN) {
                String account = message.getString("account");
                String password = message.getString("password");
                JsonObject cMsg = new JsonObject().put("account", account).put("password", password);
                vertx.eventBus().<String>request(GameConstant.API_ACCOUNT_BIND, cMsg, res -> this.onBindResult(account, res));
            } else {
                logger.error("not bind account, cannot handle message.");
                invalidMessage();
            }
        } catch (Exception e) {
            logger.error("parse message failed:{}", e.getMessage());
        }
    }

    private void onBindResult(String account, AsyncResult<Message<String>> res) {
        if (res.succeeded()) {
            this.bindAccount = account;
            Future<Message<Object>> request = vertx.eventBus().request(account + GameConstant.API_TAIL_SOCKET_SWAP, webSocket.textHandlerID());
            request.onSuccess(m -> onSocketSwapSuccess()).onFailure(err -> {
                logger.error("gateway connector message client consumer failed.", err);
                close();
            });
        } else {
            logger.error("not bind account, error.", res.cause());
        }
    }

    private void onSocketSwapSuccess() {
        String address = webSocket.textHandlerID() + GameConstant.API_TAIL_MESSAGE_CLIENT;
        consumer = vertx.eventBus().consumer(address, msg -> {
            webSocket.writeTextMessage(msg.body());
            msg.reply(GameConstant.RESULT_SUCCESS);
        });
    }

    public String getHandlerId() {
        return webSocket.textHandlerID();
    }

    public String getBindAccount() {
        return bindAccount;
    }

    public void invalidMessage() {
        JsonObject msg = new JsonObject().put("type", MessageCode.SC_ERROR).put("msg", "invalidMessage");
        webSocket.writeTextMessage(msg.toString());
    }

    public void close() {
        if (!closeTag.compareAndSet(false, true)) {
            return;
        }
        ConnectorManager.getInstance().removeConnector(this);
        if (!webSocket.isClosed()) {
            webSocket.close();
        }
        if (consumer != null) {
            consumer.unregister();
        }
        logger.info("websocket close:{}, handlerId:{}", webSocket.remoteAddress(), webSocket.textHandlerID());
        if (StringUtils.isEmpty(bindAccount)) {
            return;
        }
        logger.info("destroy player bindAccount:{}", bindAccount);
        JsonObject json = new JsonObject().put("handlerId", webSocket.textHandlerID()).put("account", bindAccount);
        vertx.eventBus().send(GameConstant.API_ACCOUNT_UNBIND, json);
    }
}
