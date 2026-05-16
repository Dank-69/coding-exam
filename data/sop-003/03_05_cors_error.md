# 场景五：接口跨域(CORS)错误

浏览器控制台出现CORS错误时,首先确认是哪个接口触发了跨域限制。检查接口服务的Access-Control-Allow-Origin响应头配置是否包含当前域名。如果是预检请求(OPTIONS)失败,检查服务端是否正确处理了OPTIONS方法。新增接口或接口域名变更时最容易出现此问题。临时解决方案：通过Nginx反向代理将接口请求转为同域请求。对于需要携带Cookie的跨域请求,需要同时设置Access-Control-Allow-Credentials为true,且Allow-Origin不能使用通配符*。
