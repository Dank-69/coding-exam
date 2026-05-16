# 五、禁止操作

以下操作严格禁止：一、在生产Hadoop集群上执行format命令；二、未经确认直接删除HDFS上的数据目录；三、在业务高峰期重启Kafka Broker或Flink JobManager；四、手动修改Hive Metastore数据库中的元数据；五、在未通知下游的情况下修改数据表结构或字段含义；六、使用root权限直接操作ZooKeeper的znode数据；七、在生产集群上运行未经测试的大规模数据回刷任务。
