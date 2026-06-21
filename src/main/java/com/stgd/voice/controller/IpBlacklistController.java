package com.stgd.voice.controller;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.server.component.IpBlacklistManager;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * IP 黑名单管理接口（仅 super 管理员可操作）。
 */
@Controller
@RequestMapping("/blacklist/")
public class IpBlacklistController {

    @Resource
    private IpBlacklistManager manager;

    /** 只有登录过的管理员才能操作黑名单，其中写操作仅限 super。 */
    private boolean isNotAdmin(HttpSession session) {
        if (session == null) return true;
        String username = (String) session.getAttribute("username");
        return !StringUtils.isNotBlank(username);
    }

    private boolean isNotSuper(HttpSession session) {
        if (session == null) return true;
        String username = (String) session.getAttribute("username");
        return !"super".equals(username);
    }

    /** 列出所有黑名单 IP */
    @PostMapping("list")
    @ResponseBody
    public Map<String, Object> list(HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        HttpSession session = request.getSession(false);
        if (isNotAdmin(session)) {
            resp.put("success", false);
            resp.put("message", "未登录，禁止操作");
            return resp;
        }
        try {
            resp.put("success", true);
            resp.put("list", manager.listAll());
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", e.getMessage());
        }
        return resp;
    }

    /**
     * 添加 IP 到黑名单：
     *   JSON 入参 { ip, remark }
     */
    @PostMapping("add")
    @ResponseBody
    public Map<String, Object> add(@RequestBody(required = false) Map<String, Object> payload,
                                   HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        HttpSession session = request.getSession(false);
        if (isNotSuper(session)) {
            resp.put("success", false);
            resp.put("message", "仅 super 管理员可添加黑名单");
            return resp;
        }
        if (payload == null) {
            resp.put("success", false);
            resp.put("message", "参数不能为空");
            return resp;
        }
        Object ipObj = payload.get("ip");
        String ip = ipObj == null ? null : ipObj.toString().trim();
        if (ip == null || ip.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "IP 不能为空");
            return resp;
        }
        Object remarkObj = payload.get("remark");
        String remark = remarkObj == null ? "" : remarkObj.toString().trim();
        try {
            return manager.addBlacklist(ip, remark);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return resp;
        }
    }

    /** 删除黑名单：JSON { id } */
    @PostMapping("remove")
    @ResponseBody
    public Map<String, Object> remove(@RequestBody(required = false) Map<String, Object> payload,
                                      HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        HttpSession session = request.getSession(false);
        if (isNotSuper(session)) {
            resp.put("success", false);
            resp.put("message", "仅 super 管理员可移除黑名单");
            return resp;
        }
        if (payload == null || payload.get("id") == null) {
            resp.put("success", false);
            resp.put("message", "id 不能为空");
            return resp;
        }
        Integer id;
        try {
            Object o = payload.get("id");
            if (o instanceof Number) {
                id = ((Number) o).intValue();
            } else {
                id = Integer.parseInt(o.toString().trim());
            }
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "id 非法");
            return resp;
        }
        try {
            return manager.removeBlacklist(id);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return resp;
        }
    }

    /** 强制踢下线指定 IP 的所有在线连接（与黑名单解耦，可单独调用）。入参：{ ip, reason } */
    @PostMapping("kickByIp")
    @ResponseBody
    public Map<String, Object> kickByIp(@RequestBody(required = false) Map<String, Object> payload,
                                        HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        HttpSession session = request.getSession(false);
        if (isNotSuper(session)) {
            resp.put("success", false);
            resp.put("message", "仅 super 管理员可强制踢下线");
            return resp;
        }
        if (payload == null || payload.get("ip") == null) {
            resp.put("success", false);
            resp.put("message", "IP 不能为空");
            return resp;
        }
        String ip = payload.get("ip").toString().trim();
        String reason = payload.get("reason") == null ? "管理员强制踢下线"
                : payload.get("reason").toString();
        int kicked = manager.kickByIp(ip, reason);
        resp.put("success", true);
        resp.put("kickedCount", kicked);
        resp.put("message", "已下线 " + kicked + " 个连接");
        return resp;
    }

    /** 判断当前请求方 IP 是否被拉黑：返回 { black: true/false, ip } */
    @PostMapping("checkMe")
    @ResponseBody
    public Map<String, Object> checkMe(HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        String ip = IpBlacklistManager.getRequestIp(request);
        resp.put("ip", ip);
        resp.put("black", manager.isBlack(ip));
        return resp;
    }

    /** 重新从数据库加载黑名单到内存（数据库被外部修改时使用） */
    @PostMapping("reload")
    @ResponseBody
    public Map<String, Object> reload(HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        HttpSession session = request.getSession(false);
        if (isNotSuper(session)) {
            resp.put("success", false);
            resp.put("message", "仅 super 管理员可重新加载");
            return resp;
        }
        manager.init();
        resp.put("success", true);
        resp.put("message", "已重新加载黑名单");
        return resp;
    }
}