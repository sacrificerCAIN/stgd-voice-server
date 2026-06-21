package com.stgd.voice.server.component;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.entity.IpBlacklist;
import com.stgd.voice.mapper.IpBlacklistMapper;
import com.stgd.voice.util.JsonUtil;
import com.stgd.voice.ws.SystemLogPublisher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 黑名单 IP 管理器：
 *   1) 启动时把数据库中 ip_blacklist 全部加载到内存 Set（O(1) 查询）
 *   2) 提供添加/移除接口（同步数据库 + 内存）
 *   3) 提供 isBlack(Channel) / isBlack(ip) 查询
 *   4) 添加时自动强制断开已在线的该 IP 连接
 */
@Component
public class IpBlacklistManager {

    private static final Set<String> BLACKLIST_SET = ConcurrentHashMap.newKeySet();

    @Resource
    private IpBlacklistMapper mapper;

    @Resource
    private ConnectManager connectManager;

    @Resource
    private SystemLogPublisher logPublisher;

    /** 首次被调用或由 NettyMain.init() 显式初始化时，从数据库加载全量黑名单到内存 */
    public synchronized void init() {
        System.out.println("正在初始化黑名单 IP 列表...");
        BLACKLIST_SET.clear();
        List<IpBlacklist> list;
        try {
            list = mapper.selectAll();
        } catch (Exception e) {
            System.err.println("黑名单初始化失败（可能表未创建）：" + e.getMessage());
            return;
        }
        if (list == null || list.isEmpty()) {
            System.out.println("黑名单 IP 列表初始化完成：空");
            return;
        }
        for (IpBlacklist item : list) {
            if (item == null) continue;
            String ip = normalizeIp(item.getIp());
            if (ip != null) BLACKLIST_SET.add(ip);
        }
        System.out.println("黑名单 IP 列表初始化完成：共 " + BLACKLIST_SET.size() + " 条");
    }

    /** 返回当前内存中所有黑名单 IP（用于 dashboard 回显） */
    public List<IpBlacklist> listAll() {
        List<IpBlacklist> result = new ArrayList<>();
        List<IpBlacklist> dbList;
        try {
            dbList = mapper.selectAll();
        } catch (Exception e) {
            return result;
        }
        if (dbList == null) return result;
        // 保证按照 id 倒序，便于管理员查看最新条目
        dbList.sort((a, b) -> {
            if (a == null || b == null) return 0;
            Integer idA = a.getId() == null ? 0 : a.getId();
            Integer idB = b.getId() == null ? 0 : b.getId();
            return Integer.compare(idB, idA);
        });
        return dbList;
    }

    /** 判断一个 IP 是否已被加入黑名单 */
    public boolean isBlack(String ip) {
        String norm = normalizeIp(ip);
        return norm != null && BLACKLIST_SET.contains(norm);
    }

    /** 从 Netty Channel 读取 IP 并判断是否被加入黑名单 */
    public boolean isBlack(Channel channel) {
        if (channel == null) return false;
        return isBlack(getChannelIp(channel));
    }

    /** 从 Netty ChannelHandlerContext 读取 IP 并判断是否被加入黑名单 */
    public boolean isBlack(ChannelHandlerContext ctx) {
        return ctx != null && isBlack(ctx.channel());
    }

    /** 从 HttpServletRequest 获取客户端 IP 并判断（用于普通 Web 请求） */
    public boolean isBlack(javax.servlet.http.HttpServletRequest request) {
        if (request == null) return false;
        String ip = getRequestIp(request);
        return isBlack(ip);
    }

    /**
     * 添加一个 IP 到黑名单：
     *   - 数据库写入（若已存在则返回失败，便于前端提示）
     *   - 同时加入内存 Set
     *   - 自动强制断开当前在线、来源 IP 等于该 IP 的所有连接
     *
     * 返回值：{ success, message, insertedId, kickedCount }
     */
    public synchronized Map<String, Object> addBlacklist(String ip, String remark) {
        Map<String, Object> resp = new HashMap<>();
        String normIp = normalizeIp(ip);
        if (normIp == null) {
            resp.put("success", false);
            resp.put("message", "IP 不能为空或格式非法");
            return resp;
        }
        // 数据库唯一性检查
        try {
            List<IpBlacklist> exists = mapper.selectByIp(normIp);
            if (exists != null && !exists.isEmpty()) {
                resp.put("success", false);
                resp.put("message", "该 IP 已在黑名单中");
                return resp;
            }
        } catch (Exception ignore) {}

        IpBlacklist entity = new IpBlacklist();
        entity.setIp(normIp);
        entity.setRemark(remark);
        entity.setCreateTime(System.currentTimeMillis());
        int inserted;
        try {
            inserted = mapper.insert(entity);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "写入数据库失败：" + e.getMessage());
            return resp;
        }
        if (inserted <= 0) {
            resp.put("success", false);
            resp.put("message", "写入数据库失败");
            return resp;
        }
        BLACKLIST_SET.add(normIp);

        // 自动踢出当前在线的、同 IP 的连接
        int kicked = kickByIp(normIp, "管理员把该 IP 加入黑名单");

        resp.put("success", true);
        resp.put("id", entity.getId());
        resp.put("kickedCount", kicked);
        resp.put("message", "添加成功，已断开 " + kicked + " 个在线连接");
        if (logPublisher != null) {
            logPublisher.publish("blacklist", null, null,
                    "IP 黑名单新增：" + normIp + (remark != null && !remark.isEmpty() ? "（" + remark + "）" : "") + "，下线连接数 " + kicked);
        }
        return resp;
    }

    /**
     * 移除黑名单：
     *   - 从数据库删除
     *   - 从内存 Set 移除
     */
    public synchronized Map<String, Object> removeBlacklist(Integer id) {
        Map<String, Object> resp = new HashMap<>();
        if (id == null) {
            resp.put("success", false);
            resp.put("message", "id 不能为空");
            return resp;
        }
        IpBlacklist item;
        try {
            item = mapper.selectById(id);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "查询失败：" + e.getMessage());
            return resp;
        }
        if (item == null) {
            resp.put("success", false);
            resp.put("message", "找不到对应记录");
            return resp;
        }
        try {
            mapper.deleteById(id);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "删除数据库失败：" + e.getMessage());
            return resp;
        }
        String normIp = normalizeIp(item.getIp());
        if (normIp != null) BLACKLIST_SET.remove(normIp);
        resp.put("success", true);
        resp.put("ip", item.getIp());
        resp.put("message", "已从黑名单移除：" + item.getIp());
        if (logPublisher != null) {
            logPublisher.publish("blacklist", null, null,
                    "IP 黑名单移除：" + item.getIp());
        }
        return resp;
    }

    /** 根据 IP 强制断开当前在线的所有用户连接（管理员添加黑名单后自动触发，也可作为独立接口使用） */
    public int kickByIp(String ip, String reason) {
        String normIp = normalizeIp(ip);
        if (normIp == null || connectManager == null) return 0;
        if (reason == null) reason = "管理员强制下线";

        // 收集所有在线 wsId -> ip 的映射，然后关闭匹配的 Channel
        Collection<Channel> channels = connectManager.getAllWsChannels();
        if (channels == null || channels.isEmpty()) return 0;
        List<String> kickList = new ArrayList<>();
        for (Channel ch : channels) {
            if (ch == null || !ch.isActive()) continue;
            String curIp = normalizeIp(getChannelIp(ch));
            if (normIp.equals(curIp)) {
                String wsId = connectManager.getWsIdByChannel(ch);
                if (wsId != null) kickList.add(wsId);
            }
        }
        for (String wsId : kickList) {
            connectManager.kickWsUser(wsId);
        }
        return kickList.size();
    }

    // ===== 静态工具 =====

    /** 从 Netty Channel 获取 IP（去掉端口和可能的方括号，兼容 IPv4 / IPv6） */
    public static String getChannelIp(Channel channel) {
        if (channel == null) return null;
        try {
            java.net.SocketAddress sa = channel.remoteAddress();
            if (sa instanceof InetSocketAddress) {
                InetAddress ia = ((InetSocketAddress) sa).getAddress();
                if (ia != null) return ia.getHostAddress();
            }
            return sa == null ? null : sa.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 HttpServletRequest 获取客户端真实 IP（兼容反向代理场景） */
    public static String getRequestIp(javax.servlet.http.HttpServletRequest request) {
        if (request == null) return null;
        String[] headers = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP"
        };
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isEmpty() && !"unknown".equalsIgnoreCase(v)) {
                // X-Forwarded-For 可能是 "a,b,c"，取第一个
                int idx = v.indexOf(',');
                return (idx < 0 ? v : v.substring(0, idx)).trim();
            }
        }
        String remote = request.getRemoteAddr();
        // 从 "ip:port" 或 "[ipv6]:port" 里剥离端口（某些 servlet 容器可能带端口）
        return normalizeIp(remote);
    }

    /** 标准化 IP：去前后空格，去尾端口，去方括号，结果用于 Set 比较 */
    public static String normalizeIp(String ip) {
        if (ip == null) return null;
        String s = ip.trim();
        if (s.isEmpty()) return null;
        // 去端口： IPv6 形式 [xxxx]:port 或 IPv4 形式 x.x.x.x:port
        if (s.startsWith("[")) {
            int idx = s.indexOf(']');
            if (idx > 0) {
                return s.substring(1, idx).toLowerCase();
            }
        }
        // IPv4 或裸 IPv6：如果末尾有 ":数字" 去掉
        int lastColon = s.lastIndexOf(':');
        long colonCount = s.chars().filter(c -> c == ':').count();
        if (colonCount == 1 && lastColon > 0) {
            // IPv4:port
            return s.substring(0, lastColon);
        }
        return s.toLowerCase();
    }
}