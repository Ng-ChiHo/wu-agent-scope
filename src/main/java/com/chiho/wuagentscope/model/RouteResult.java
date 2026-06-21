package com.chiho.wuagentscope.model;

/**
 * 路由分类结果
 *
 * @param route      路由目标：general / data_analyst
 * @param confidence 分类置信度：0.0 ~ 1.0
 * @param reason     分类原因说明
 * @author ChiHo
 */
public record RouteResult(String route, double confidence, String reason) {

    /** 默认路由（置信度不足时回退） */
    public static RouteResult defaultRoute() {
        return new RouteResult("general", 1.0, "默认路由");
    }
}
