# Auto ADB Client

## lnd playground

`lnd-playground` 是一个独立的 JVM 调试模块, 用来排查 `lnd` 接入是否正常. 它不依赖 Android 运行时, 可以直接在桌面环境里运行, 适合快速验证过滤条件, `reachability_scopes`, `watch` 事件和节点注册结果.

模块入口位于 [lnd-playground/src/main/java/com/azazo1/auto_adb_wl_client/lndplayground/Main.java](./lnd-playground/src/main/java/com/azazo1/auto_adb_wl_client/lndplayground/Main.java).

如果项目根目录存在 `.env`, `lnd-playground` 会默认尝试读取这些变量:

- `AUTO_ADB_WL_LND_BASE_URL`
- `AUTO_ADB_WL_LND_BEARER_TOKEN`
- `AUTO_ADB_WL_LND_DISCOVERY_DOMAIN`
- `AUTO_ADB_WL_LND_SERVICE_NAME`

命令行参数优先级更高, 会覆盖 `.env` 里的默认值.

### 构建

```powershell
./gradlew.bat :lnd-playground:build
```

### 查看帮助

```powershell
./gradlew.bat :lnd-playground:run --args="--help"
```

### 常用命令

查看本机 `reachability_scopes`:

```powershell
./gradlew.bat :lnd-playground:run --args="scopes --server-url http://127.0.0.1:8765"
```

查看发现结果, 同时比较 plain discover 和 auto-scope discover:

```powershell
./gradlew.bat :lnd-playground:run --args="discover --server-url http://127.0.0.1:8765 --service _http._tcp"
```

如果怀疑是 `discovery_domain` 不匹配:

```powershell
./gradlew.bat :lnd-playground:run --args="discover --server-url http://127.0.0.1:8765 --service _http._tcp --discovery-domain office-a"
```

如果怀疑是 auto scope overlap 把节点过滤掉:

```powershell
./gradlew.bat :lnd-playground:run --args="discover --server-url http://127.0.0.1:8765 --service _http._tcp --no-auto-scope-overlap"
```

持续观察 `watch` 事件:

```powershell
./gradlew.bat :lnd-playground:run --args="watch --server-url http://127.0.0.1:8765 --service _http._tcp"
```

验证单次注册:

```powershell
./gradlew.bat :lnd-playground:run --args="announce-once --server-url http://127.0.0.1:8765 --service _http._tcp --node-id test-node --display-name test-node --port 21300"
```

持续注册并手动停止:

```powershell
./gradlew.bat :lnd-playground:run --args="announce-loop --server-url http://127.0.0.1:8765 --service _http._tcp --node-id test-node --display-name test-node --port 21300"
```

### 输出内容

`lnd-playground` 会输出这些关键信息:

- 实际生效的 `serverUrl`, `service`, `discoveryDomain`, `tags`, `filterScopes`
- 本机 `reachability_scopes`
- `discover` 的 plain 结果和 auto-scope 结果
- 每个节点的 `nodeId`, `displayName`, `service`, `lanAddrs`, `reachabilityScopes`, `tags`, `metadata`, `lease`
- `watch` 下的 `snapshot`, `upsert`, `remove`, `reset`, `keepalive`
- `announce` 前解析出的最终 `lan_addrs` 和 `reachability_scopes`

### 常见排查顺序

推荐先按这个顺序排查:

1. 跑 `scopes`, 确认当前机器本机 scope 是什么.
2. 跑 `discover --no-auto-scope-overlap`, 看服务端是否本来就有节点.
3. 跑默认 `discover`, 对比是否是 auto scope overlap 把节点过滤掉.
4. 如果需要, 再跑 `watch`, 看节点上线, 续租和移除事件是否正常到达.

如果第 2 步有节点, 第 3 步没有节点, 一般就是 `reachability_scopes overlap` 不成立.
如果第 2 步和第 3 步都没有节点, 一般要继续检查 `service`, `discovery_domain`, `bearer token` 或服务端注册链路.
