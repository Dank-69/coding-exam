# 六、工具与命令参考

DBA日常运维工具集：数据库监控使用PMM平台，地址为内部监控门户。慢查询分析使用pt-query-digest工具解析慢查询日志。在线DDL使用pt-online-schema-change或gh-ost工具，避免锁表。数据校验使用pt-table-checksum和pt-table-sync。备份管理使用XtraBackup进行物理备份，mysqldump用于逻辑备份。Redis运维使用redis-cli和RedisInsight可视化工具。MongoDB运维使用mongosh和MongoDB Compass。所有操作需通过堡垒机登录，操作审计日志保留一百八十天。 本文档由DBA团队维护，如有疑问请联系：dba-oncall@company.com
