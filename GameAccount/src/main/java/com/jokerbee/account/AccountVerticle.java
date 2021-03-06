package com.jokerbee.account;

import com.jokerbee.cache.CacheManager;
import com.jokerbee.cache.RedisClient;
import com.jokerbee.db.entity.impl.AccountEntity;
import com.jokerbee.db.manager.DBManager;
import com.jokerbee.support.GameConstant;
import com.jokerbee.support.MessageCode;
import com.jokerbee.util.RandomUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 玩家管理器;
 *
 * @author: Joker
 * @date: Created in 2020/10/31 1:07
 * @version: 1.0
 */
public class AccountVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger("Account");

    private final List<MessageConsumer<?>> list = new ArrayList<>();

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("start account service");
        MessageConsumer<JsonObject> c1 = vertx.eventBus().consumer(GameConstant.API_ACCOUNT_BIND, this::accountBind);
        list.add(c1);
        MessageConsumer<JsonObject> c2 = vertx.eventBus().consumer(GameConstant.API_ACCOUNT_UNBIND, this::accountUnbind);
        list.add(c2);
        startPromise.complete();
    }

    /**
     * 绑定账号和Player;
     */
    private void accountBind(Message<JsonObject> msg) {
        JsonObject body = msg.body();
        String account = body.getString("account");
        String password = body.getString("password");

        invalidAccount(account, password)
                .onFailure(err -> msg.fail(1, err.getMessage()))
                .onSuccess(pid -> {
                    logger.info("account bind start, account:{}, playerId:{}", account, pid);
                    String requireId = RandomUtil.getRandom(100000, 999999) + "";
                    if (!lock(account, requireId)) {
                        msg.fail(1, "already in bind");
                        return;
                    }
                    String serverId = getServerId(account);
                    if (serverId == null) {
                        logger.info("create account player:{}", account);
                        vertx.eventBus().<String>request(GameConstant.API_CREATE_PLAYER, account, res -> onCreatePlayer(account, res, msg, requireId));
                    } else {
                        tellPlayerDisconnect(account, serverId, msg, requireId);
                    }
                });
    }

    /**
     * 账号有效性检查;
     *
     * @return 账号唯一id;
     */
    private Future<Long> invalidAccount(String account, String password) {
        return Future.future(prom -> {
            if (StringUtils.isEmpty(account) || StringUtils.isEmpty(password)) {
                prom.fail("invalid account");
                return;
            }
            queryAccountEntity(account, password)
                    .compose(ae -> {
                        if (!ae.getPassword().equals(password)) {
                            return Future.failedFuture("password error");
                        }
                        return Future.<Long>future(prom2 -> prom2.complete(ae.getId()));
                    })
                    .onComplete(prom);
        });
    }

    /**
     * 通知创建玩家对象反馈;
     *
     */
    private void onCreatePlayer(String account, AsyncResult<Message<String>> res, Message<JsonObject> msg, String requireId) {
        if (res.succeeded()) {
            String serverId = res.result().body();
            setServerId(account, serverId);
            msg.reply(serverId);
            logger.info("create account player success:{}, server:{}", account, serverId);
        } else {
            msg.fail(1, res.cause().getMessage());
            logger.info("create account player failed:{}.", account, res.cause());
        }
        unlock(account, requireId);
    }

    private void tellPlayerDisconnect(String account, String serverId, Message<JsonObject> msg, String requireId) {
        logger.info("account player disconnect:{}", account);
        JsonObject serverMsg = new JsonObject().put("type", MessageCode.AP_ACCOUNT_DISCONNECT).put("account", account);
        vertx.eventBus().<String>request(GameConstant.API_SERVER_TITLE + serverId, serverMsg, res -> {
            if (res.succeeded()) {
                msg.reply(serverId);
                logger.info("account player disconnect success:{}", account);
                unlock(account, requireId);
            } else {
                if (res.cause().getMessage().contains("No handlers for address")) {
                    logger.info("server not found, create player: {}", account);
                    vertx.eventBus().<String>request(GameConstant.API_CREATE_PLAYER, account, res2 -> onCreatePlayer(account, res2, msg, requireId));
                } else {
                    logger.error("account player disconnect failed:{}", account, res.cause());
                    msg.fail(1, res.cause().getMessage());
                    unlock(account, requireId);
                }
            }
        });
    }

    /**
     * 解绑账号和Player, 会销毁Player对象;
     */
    private void accountUnbind(Message<JsonObject> msg) {
        JsonObject json = msg.body();
        String account = json.getString("account");
        String handlerId = json.getString("handlerId");
        String serverId = getServerId(account);
        if (serverId == null) {
            msg.reply("");
        } else {
            JsonObject serverMsg = new JsonObject().put("type", MessageCode.AP_ACCOUNT_DESTROY).put("account", account).put("handlerId", handlerId);
            vertx.eventBus().<String>request(GameConstant.API_SERVER_TITLE + serverId, serverMsg, res -> {
                if (res.succeeded()) {
                    removeServerId(account);
                    logger.error("account unbind serverId success:{}", account);
                } else {
                    logger.error("account unbind serverId failed:{}", res.cause().getMessage());
                }
                msg.reply("");
            });
        }
    }

    private Future<AccountEntity> queryAccountEntity(String account, String password) {
        return Future.future(prom1 -> vertx.executeBlocking(prom2 -> {
            List<AccountEntity> list = DBManager.getInstance().query(AccountEntity.class, "from AccountEntity where account='" + account + "'");
            AccountEntity accountEntity;
            if (list == null || list.size() <= 0) {
                accountEntity = new AccountEntity();
                accountEntity.setAccount(account);
                accountEntity.setPassword(password);
                DBManager.getInstance().insert(accountEntity);
            } else {
                accountEntity = list.get(0);
            }
            prom2.complete(accountEntity);
        }, prom1));
    }

    private String getServerId(String account) {
        RedisClient redis = CacheManager.getInstance().redis();
        return redis.hget(GameConstant.REDIS_ACCOUNT_SERVER, account);
    }

    private void removeServerId(String account) {
        RedisClient redis = CacheManager.getInstance().redis();
        redis.hdel(GameConstant.REDIS_ACCOUNT_SERVER, account);
    }

    private void setServerId(String account, String serverId) {
        RedisClient redis = CacheManager.getInstance().redis();
        redis.hset(GameConstant.REDIS_ACCOUNT_SERVER, account, serverId);
    }

    private boolean lock(String account, String requireId) {
        RedisClient redis = CacheManager.getInstance().redis();
        return redis.lock(account, requireId, 5000);
    }

    private void unlock(String account, String requireId) {
        RedisClient redis = CacheManager.getInstance().redis();
        redis.unlock(account, requireId);
    }

    @Override
    public void stop() {
        list.forEach(MessageConsumer::unregister);
        logger.info("close player service");
    }
}
