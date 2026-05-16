# 五、禁止操作

以下操作严格禁止：一、在生产集群直接执行kubectl delete node；二、未经审批修改生产环境的Terraform配置并apply；三、在高峰期进行Kubernetes版本升级；四、直接修改Etcd数据；五、删除生产环境的命名空间（namespace）；六、在未确认影响范围的情况下修改NetworkPolicy；七、关闭生产环境的监控告警。
