# 四、升级流程

SRE值班遇到以下情况需立即升级：一、Kubernetes控制平面组件（API Server、Etcd、Controller Manager）不可用；二、多可用区同时故障；三、核心Ingress网关完全不可用；四、数据中心网络分区。升级路径：值班SRE到SRE团队负责人到基础架构VP。P0级基础设施故障需在三分钟内升级，因为基础设施故障通常影响所有上层服务。
