package com.stgd.voice.aspect;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.stgd.voice.annotation.RequireAdminLogin;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Component
public class AdminLoginAspect {

    @Around("@annotation(requireAdminLogin)")
    public Object around(ProceedingJoinPoint joinPoint, RequireAdminLogin requireAdminLogin) throws Throwable {
        // 获取request
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            Map<String,Object> resp = new HashMap<>();
            resp.put("success",false);
            resp.put("message","获取请求上下文失败");
            return resp;
        }
        HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
        HttpSession session = request.getSession(false);
        if (session == null) {
            Map<String,Object> resp = new HashMap<>();
            resp.put("success",false);
            resp.put("message","未登录，禁止操作");
            return resp;
        }
        String username = (String) session.getAttribute("username");
        if (!StringUtils.isNotBlank(username)) {
            Map<String,Object> resp = new HashMap<>();
            resp.put("success",false);
            resp.put("message","未登录，禁止操作");
            return resp;
        }
        // 校验通过，执行原方法
        return joinPoint.proceed();
    }
}
