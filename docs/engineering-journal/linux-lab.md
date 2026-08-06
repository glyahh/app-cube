# Linux 真实项目实验路线

目标不是背命令，而是能在本地把一个 Java + Redis + MySQL 项目启动、观察、定位和恢复。每次实验都保存一条命令和一条观察结论。

## Lab setup

在仓库根目录准备 Java 21、Docker Desktop 和项目依赖。不要把真实密钥写入仓库。若暂时没有完整数据库环境，可先用 Docker 启动 Redis，再对 Java 服务做静态配置检查。

```bash
docker run --name ai-lab-redis -p 6379:6379 -d redis:7
docker ps
docker logs --tail 50 ai-lab-redis
```

## Seven short sessions

| 天数 | 现场任务 | 只记这几个命令 | 证据 |
| --- | --- | --- | --- |
| 1 | 启动/停止 Redis | `docker ps`、`docker stop`、`docker start` | 容器状态变化 |
| 2 | 查服务和端口 | `Get-Process`、`Get-NetTCPConnection -LocalPort 8124` | PID 与端口 |
| 3 | 看日志定位启动失败 | `docker logs`、`Get-Content -Wait` | 首个异常和时间 |
| 4 | 看资源变化 | `docker stats`、任务管理器 | CPU/内存变化 |
| 5 | 验证 Redis 依赖 | `docker exec ai-lab-redis redis-cli ping` | `PONG` 或错误 |
| 6 | 模拟依赖故障 | 停 Redis 后请求项目 | 服务返回和日志 |
| 7 | 恢复与复盘 | 启动 Redis、重试请求 | 故障前后对比 |

## The diagnosis loop

```text
现象 -> 进程是否存在 -> 端口是否监听 -> 日志第一处异常
-> 依赖是否可达 -> 修改一个变量 -> 重试并记录结果
```

完成标准不是“记住命令”，而是能回答：哪个进程在监听、日志第一处异常是什么、依赖是否可达、恢复后请求是否成功。

