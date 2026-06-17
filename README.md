# Stgd Voice Server

一个基于 **Spring Boot + Netty + WebSocket** 的实时语音/聊天服务端。


- **页面（默认端口 8080）**：网页端控制台、聊天室
- **TCP（默认端口 6324）**：文本消息（聊天、房间、私聊、全服广播）
- **UDP（默认端口 6324）**：语音流转发（原始音频数据）
- **WebSocket `/ws/chat`**：浏览器客户端聊天通道，支持房间/私聊/WebRTC 信令/管理员房间管理
- **WebSocket `/ws/system-log`**：仪表盘实时事件日志
- **HTTP REST**：登录会话校验 / 房间 CRUD / 管理员 CRUD（`/login/*`、`/room/*`、`/adminUser/*`、`/api/protected/*`）
- **SQLite**：房间、管理员账户持久化（文件：`stgd_voice.db`，首次运行自动生成）

前端页面（在 `src/main/resources/static/` 下）：

| 页面 | 说明 |
|------|------|
| `index.html` | 登录入口 |
| `chat.html` | 聊天 + WebRTC 语音通话 |
| `dashboard.html` | 管理员仪表盘（房间管理、在线用户、系统日志） |

---

## 环境要求

- **JDK 8** 及以上
- **Maven 3.6+**
- 操作系统：Windows / Linux / macOS（Netty 跨平台）

## 快速启动

### 方式一：IDEA 直接运行

1. 用 IDEA 打开项目，等待 Maven 依赖下载完成。
2. 运行主类：`com.stgd.voice.StgdVoiceApplication`。
3. 启动成功后控制台会输出：
   ```
   TCP服务启动成功，端口：6324
   UDP服务启动成功，端口：6324
   项目启动成功,请访问控制台 http://localhost:8080
   ```

### 方式二：Maven 打包 + Jar 运行

```bash
# 1. 打包
mvn clean package -DskipTests

# 2. 运行（注意：jar 名以实际生成为准，通常为 StgdVoice-0.0.1-SNAPSHOT.jar）
java -jar target/StgdVoice-0.0.1-SNAPSHOT.jar
```

### 首次登录

管理员账户
用户名:super
密码:super

1. 用 SQLite 客户端插入一条记录到 `admin_user` 表（字段：`username`、`password`）。
2. 调用 `POST /adminUser/insertAdminUser` 接口插入（该接口当前无鉴权，生产环境请务必关闭）。

> `super` 是特殊用户名，拥有「添加/删除/修改房间」的权限（见 `RoomController` 与 `ChatEndpoint` 中的权限校验逻辑）。

---

## 配置文件 `application.yml`

```yaml
server:
  port: 8080          # HTTP / WebSocket 端口
  tcp-port: 6324      # TCP 文本消息端口
  udp-port: 6324      # UDP 语音端口
  servlet:
    session:
      timeout: 259200 # 会话有效期（秒，=3 天）
      cookie:
        http-only: true

spring:
  datasource:
    url: jdbc:sqlite:./stgd_voice.db   # SQLite 数据库文件路径
    driver-class-name: org.sqlite.JDBC
```

---

## 目录结构

```
src/main/java/com/stgd/voice/
├── StgdVoiceApplication.java      # Spring Boot 启动类（同时启动 Netty TCP/UDP）
├── config/
│   ├── ServerConfig.java          # 读取 server.port / tcp-port / udp-port
│   ├── WebSocketConfig.java
│   └── HttpSessionConfigurator.java
├── controller/
│   ├── LoginController.java       # HTTP 登录 / 会话校验 / 登出
│   ├── RoomController.java        # HTTP 房间 CRUD（需管理员登录）
│   ├── AdminUserController.java   # HTTP 管理员 CRUD
│   └── ProtectedController.java   # HTTP 受保护资源示例
├── entity/
│   ├── Message.java               # 统一消息结构（TCP 与 WebSocket 通用）
│   ├── Room.java                  # 房间
│   ├── User.java                  # 用户（运行时）
│   └── AdminUser.java             # 管理员账户
├── mapper/
│   ├── RoomMapper.java
│   └── AdminUserMapper.java
├── server/
│   ├── NettyMain.java             # Netty TCP + UDP 服务器启动
│   ├── component/
│   │   ├── ConnectManager.java    # 全局连接/房间/用户管理（含 WebSocket）
│   │   └── PortCheckListener.java
│   └── handler/
│       ├── text/TextHandler.java  # TCP 文本消息处理
│       └── voice/VoiceHandler.java# UDP 语音流处理
├── service/publish/impl/strategy/ # TCP 消息策略（按 type 分发）
│   ├── LoginMessageStrategyImpl.java
│   ├── IdleMessageStrategyImpl.java
│   ├── JoinRoomMessageStrategyImpl.java
│   ├── RoomMessageStrategyImpl.java
│   ├── PrivateMessageStrategyImpl.java
│   ├── ToAllMessageStrategyImpl.java
│   ├── LogoutMessageStrategyImpl.java
│   └── ExceptionMessageStrategyImpl.java
└── ws/
    ├── ChatEndpoint.java          # /ws/chat 聊天 WebSocket 端点
    ├── SystemLogEndpoint.java     # /ws/system-log 日志 WebSocket 端点
    └── SystemLogPublisher.java    # 日志发布器
```

---

## 协议接口文档

### 1. HTTP 接口

所有请求/响应均为 JSON。登录成功后，`HttpSession` 会保存 `id`、`username`。

#### 1.1 登录 `/login/login`（POST）

**请求体：**
```json
{ "username": "super", "password": "123456" }
```

**响应：**
```json
{ "code": 1, "username": "super" }        // 登录成功
{ "code": 0 }                                 // 登录失败
```

#### 1.2 会话校验 `/login/checkSession`（GET）

**响应：**
```json
{
  "isAuthenticated": true,
  "id": 1,
  "username": "super",
  "sessionId": "...",
  "isAdmin": true
}
```

> `isAdmin = true` 当且仅当 `username == "super"`。

#### 1.3 登出 `/login/logout`（POST）

**响应：**
```json
{ "success": true,  "message": "登出成功" }
{ "success": false, "message": "没有活跃的会话" }
```

#### 1.4 新增房间 `/room/insertRoom`（POST）

**请求体：**
```json
{ "name": "闲聊房间", "password": "" }
```

**响应：**
```json
{ "success": true, "affectedRows": 1, "message": "..." }
```

> 需要 HTTP 已登录（会话中存在 username），否则返回 `未登录，禁止操作`。

#### 1.5 删除房间 `/room/removeRoom`（POST）

**请求体：**
```json
{ "id": 3 }
```

#### 1.6 更新房间 `/room/updateRoom`（POST）

**请求体：**
```json
{ "id": 1, "name": "新名字", "password": "新密码" }
```

#### 1.7 获取全部房间 `/room/getAllRoom`（POST）

**响应：**
```json
[
  { "id": 1, "name": "闲聊房间", "password": "", "userNum": 2, "userChannelIdSet": [...] },
  ...
]
```

#### 1.8 管理员用户管理 `/adminUser/*`（POST）

| 路径 | 说明 |
|------|------|
| `/adminUser/insertAdminUser` | 新增管理员，请求体 `{username,password}`，返回受影响行数 |
| `/adminUser/removeAdminUser?id=X` | 删除管理员（URL 参数） |
| `/adminUser/updateAdminUser` | 更新管理员，请求体 `{id,username,password}` |
| `/adminUser/getAllAdminUser` | 查询全部管理员 |
| `/adminUser/login` | 另一个登录入口（返回 `1`/`0`） |
| `/adminUser/checkSession` | 同 `/login/checkSession` |
| `/adminUser/logout` | 同 `/login/logout` |

#### 1.9 受保护资源 `/api/protected/dashboard`（GET）

**响应：**
```json
{ "message": "欢迎访问仪表盘", "id": 1 }
// 未登录时返回 { "error": "未授权访问" }
```

---

### 2. WebSocket `/ws/chat` 协议

- **连接 URL**：`ws://<host>:8080/ws/chat?loginUser=<已登录HTTP用户名>`
  - `loginUser`：权限校验依据（房间增删改需要 `loginUser=super`），必须 URL-Encode。
- **消息结构**：统一使用 `Message`（JSON 对象）。
- **文本编码**：UTF-8。

#### 2.1 通用 Message 字段（客户端→服务端）

```json
{
  "type":         <number>,  // 消息类型（见下表）
  "userName":     <string>,  // 昵称（type=1 时使用）
  "roomId":       <number>,  // 当前所在房间ID
  "targetRoomId": <number>,  // 目标房间ID（加入房间时使用）
  "targetUserId": <string>,  // 目标用户ID（私聊时使用）
  "password":     <string>,  // 房间密码（加入房间/创建房间时使用）
  "roomName":     <string>,  // 房间名称（管理员新增/更新房间时使用）
  "payload":      <string>   // 消息载荷（聊天文本 / WebRTC SDP/ICE 等）
}
```

#### 2.2 消息类型总表

| type | 说明 | 主要字段 |
|------|------|----------|
| **1** | 设置昵称（登录） | `userName` |
| **2** | 心跳（Idle） | - |
| **3** | 房间消息 | `payload`（需要先加入房间） |
| **4** | 私聊消息 | `targetUserId`, `payload` |
| **5** | 全服广播 | `payload`（需要已设置昵称） |
| **6** | 加入房间 | `targetRoomId`, `password` |
| **7** | 登出 | - |
| **8** | 获取全量房间-用户信息 | - |
| **9** | 获取房间列表 | - |
| **10** | 添加房间（仅 super） | `roomName`, `password` |
| **11** | 删除房间（仅 super） | `roomId` |
| **12** | 更新房间（仅 super） | `roomId`, `roomName`, `password` |
| **13** | WebRTC 信令转发 | `targetUserId`（可空）, `payload` |

#### 2.3 服务端→客户端下发消息结构

服务端下发的 JSON 消息会携带 `type` 字符串字段，用于前端区分：

| 下发 type | 说明 | 附加字段 |
|-----------|------|----------|
| `"system"` | 系统提示 | `payload`, `timestamp` |
| `"login"` | 登录响应 | `userId`, `userName` |
| `"idle"` | 心跳响应 | `timestamp` |
| `"join"` | 加入房间响应 | `roomId`, `roomName`, `userNum` |
| `"room"` | 房间聊天 | `roomId`, `sessionId`, `userName`, `payload`, `timestamp` |
| `"private"` | 私聊 | `fromUserName`, `fromUserId`, `payload`, `timestamp` |
| `"broadcast"` | 全服广播 | `userName`, `payload`, `timestamp` |
| `"webrtc"` | WebRTC 信令转发 | `roomId`, `fromUserId`, `fromUserName`, `targetUserId`, `payload` |
| `"roomList"` | 房间列表 | `rooms: [{id, name, hasPassword, userNum}, ...]` |
| `"roomUsers"` | 全量房间-用户信息 | `rooms: [{id, name, hasPassword, userNum, users:[{userId,userName}]}]` |
| `"userJoined"` | 用户加入事件 | `roomId`, `userId`, `userName` |
| `"userLeft"` | 用户离开事件 | `roomId`, `userId`, `userName` |

#### 2.4 典型交互流程

1. **登录 / 设置昵称**（type=1）
   ```json
   发送: {"type":1,"userName":"小明"}
   接收: {"type":"login","userId":"<sessionId>","userName":"小明"}
   ```

2. **拉取房间列表**（type=9）
   ```json
   发送: {"type":9}
   接收: {"type":"roomList","rooms":[...]}
   ```

3. **加入房间**（type=6）
   ```json
   发送: {"type":6,"targetRoomId":1,"password":"可选密码"}
   接收: {"type":"join","roomId":1,"roomName":"闲聊房间","userNum":3}
   ```
   > 成功后还会收到 `userJoined`（广播给所有人）以及全量 `roomUsers`。

4. **房间聊天**（type=3）
   ```json
   发送: {"type":3,"payload":"大家好"}
   接收: {"type":"room","roomId":1,"userName":"小明","payload":"大家好","timestamp":...}
   ```

5. **私聊**（type=4）
   ```json
   发送: {"type":4,"targetUserId":"<对方sessionId>","payload":"你好"}
   接收: {"type":"private","fromUserName":"小明","fromUserId":"...","payload":"你好"}
   ```

6. **全服广播**（type=5）
   ```json
   发送: {"type":5,"payload":"公告一条"}
   接收: {"type":"broadcast","userName":"小明","payload":"公告一条"}
   ```

7. **WebRTC 信令转发**（type=13）
   ```json
   发送: {"type":13,"targetUserId":"<对方ID>","payload":"<SDP/ICE JSON>"}
   接收: {"type":"webrtc","fromUserId":"...","fromUserName":"...","targetUserId":"...","payload":"..."}
   ```
   > 若 `targetUserId` 为空，则转发给房间内除自己外的所有人。

8. **管理员添加房间**（type=10，需 `loginUser=super`）
   ```json
   发送: {"type":10,"roomName":"新房间","password":"可选密码"}
   接收: {"type":"system","payload":"房间 [新房间] 添加成功"}
   ```

---

### 3. TCP 协议（文本消息，Netty）

- **地址**：`<host>:6324`
- **报文格式**：单行 JSON（UTF-8），每条消息以换行结束。服务端回传也是 `
` 结尾的纯文本。
- **消息结构**：与 WebSocket 相同的 `Message` JSON：

```json
{ "type": <1-7>, "userName":"...", "roomId":..., "targetRoomId":..., "targetUserId":"...", "password":"...", "payload":"..." }
```

| type | 说明 | 回传示例 |
|------|------|----------|
| 1 | 登录（设置昵称） | 无固定回传（成功后可正常参与聊天） |
| 2 | 心跳 | 空回传 |
| 3 | 房间消息 | 其他用户收到 `[昵称]: 文本` |
| 4 | 私聊（需 `targetUserId`） | 目标收到 `[昵称]对你说: xxx` / 你自己会收到「服务器对你说：该用户已下线」等 |
| 5 | 全服广播 | 所有人收到 `[昵称]文本` |
| 6 | 加入房间（targetRoomId） | `1`（成功）/ `房间不存在` |
| 7 | 登出 | - |

> 房间的 userNum、成员变化等信息，通过 `/room/getAllRoom` HTTP 接口或 WebSocket 通道查询。

---

### 4. UDP 协议（语音流）

- **地址**：`<host>:6324`
- **内容**：原始音频帧（PCM / Opus 等，由客户端自行约定）。
- 服务端逻辑见 `VoiceHandler.java`：根据 `ConnectManager` 对同一房间的成员做转发。
- 本服务端不做音频编解码，仅负责数据包的转发。

---

### 5. 系统日志 WebSocket `/ws/system-log`

- **地址**：`ws://<host>:8080/ws/system-log`
- **下发格式**：JSON 对象，示例：
  ```json
  { "type": "login|join|leave|logout|room", "userName": "...", "roomName": "...", "message": "...", "timestamp": 1234567890 }
  ```
- 用于 `dashboard.html` 展示实时事件流。

---

## 开发与扩展建议

- **添加新的消息类型**：在 `Message.type` 中分配新编号，然后：
  - TCP 客户端：新增一个 `MessagePublishStrategy` 的实现类，并在 `MessageStrategyFactory` 注册。
  - WebSocket 客户端：在 `ChatEndpoint#onMessage` 的 `switch` 增加一个分支。
- **权限控制**：HTTP 与 WebSocket 的房间增删改目前仅校验 HTTP Session 中的 `username` 是否为 `"super"`；如需要更完善的 RBAC，请在 `AdminUser` 表扩展 `role` 字段并在各 `Controller` / `ChatEndpoint` 中统一校验。
- **密码存储**：当前房间密码以明文形式存储（`password` 字段），生产环境请加盐哈希。
- **数据库**：默认使用 SQLite。若需迁移到 MySQL/PostgreSQL，只需要替换 `application.yml` 中 `spring.datasource.*` 并修改 `db-type`、`id-type` 等 MyBatis-Plus 配置。

---

## License

本项目作为内部演示/学习代码使用。
