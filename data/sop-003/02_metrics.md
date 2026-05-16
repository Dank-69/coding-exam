# 二、监控指标

前端监控体系包括以下核心指标。性能指标方面：LCP(Largest Contentful Paint)需控制在2.5秒以内,FID(First Input Delay)需控制在100毫秒以内,CLS(Cumulative Layout Shift)需控制在0.1以内。这三项是Google Core Web Vitals的核心指标,直接影响SEO排名。稳定性指标方面：JavaScript错误率不超过千分之一,接口调用成功率不低于百分之九十九点五,资源加载失败率不超过千分之五。业务指标方面：页面PV/UV的分钟级波动,核心页面的跳出率,关键按钮的点击率。所有指标通过自研的前端监控SDK采集,上报到日志平台后通过Grafana展示。告警规则配置在AlertManager中,支持按页面、浏览器、地域等维度细分。
