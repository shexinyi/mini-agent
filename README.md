# 最简 AI Agent（Spring Boot）

这个工程把同目录的《手写一个最简 AI Agent（Java）.txt》拆成可运行的 Spring Boot 项目。

Agent 就是四件事：

**LLM（大脑） + Tools（手脚） + Loop（循环） + Memory（记忆）**

Spring 不决定下一步调哪个工具。那是模型在运行时选的。Spring 只负责三件事：读配置、把对象装起来、把循环接到 HTTP。

协议处理用 Jackson，HTTP 用 JDK 自带的 `HttpClient`。这里没有 Spring AI，也没有 LangChain，循环就写在 `MiniAgent.run` 里。

## 怎么跑

需要 JDK 17+ 和 Maven。

```powershell
mvn spring-boot:run
```

浏览器打开 http://localhost:8080 ，点「计算 + 时间 + RAG」。

默认是 **mock**：不访问外网。关键词规则会假装自己是大模型，返回和真实接口一样的 `tool_calls`。回答末尾会说明这段话是工具原文拼接出来的。页面下方的 `messages` 才是要看的东西。

跑测试：

```powershell
mvn test
```

### 换成真实模型

任何 OpenAI 兼容接口都可以。Key 放在环境变量里，不要写进 `application.yml`。

```powershell
$env:LLM_MODE="openai"
$env:LLM_API_KEY="sk-..."
$env:LLM_BASE_URL="https://api.deepseek.com/v1"
$env:LLM_MODEL="deepseek-chat"
mvn spring-boot:run
```

`LLM_BASE_URL` 要带到 `/v1`。本地 Ollama：

```powershell
$env:LLM_MODE="openai"
$env:LLM_BASE_URL="http://localhost:11434/v1"
$env:LLM_API_KEY="ollama"
$env:LLM_MODEL="qwen2.5"
```

用同一句话再跑一遍，对比 mock 和真实模型的 `messages`。循环没变，变的只有「谁在产生 tool_calls」。

## 先看这几个文件

| 顺序 | 文件 | 它在讲什么 |
|---|---|---|
| 1 | `agent/MiniAgent.java` | `while` 循环本身 |
| 2 | `tool/Tool.java` 和三个工具 | 手脚：名字、描述、JSON Schema、执行 |
| 3 | `llm/OpenAiLlmClient.java` | Function Calling 那一次 HTTP |
| 4 | `llm/MockLlmClient.java` | 没 Key 时的替身，返回同样形状的 message |
| 5 | `memory/ConversationMemory.java` | 短期记忆 = 按会话保存的 messages |
| 6 | `web/ChatController.java` | HTTP 入口。决策不在这里 |
| 7 | `config/AgentConfig.java` | 新增工具时，在这里注册一行 |

页面 `static/index.html` 把一轮结果拆成三块：回答、工具轨迹、messages。

## 一次请求里发生什么

```
用户输入
   │
   ▼
追加一条 role=user
   │
   ▼
┌──────────────────────────────────────┐
│  for (step = 1 .. maxSteps)          │
│    ① messages + tools → 模型         │
│    ② 没有 tool_calls → 文字就是答案   │
│    ③ 有 tool_calls → 执行工具         │
│    ④ 结果写成 role=tool，回到 ①       │
└──────────────────────────────────────┘
```

笔记里的示例「128*37-96、现在几点、什么是 RAG」在 mock 下会走两轮模型调用：

1. 模型一次返回 3 个 `tool_calls`（计算、时间、知识库）。多个工具会并行执行，回填仍按原来的顺序。
2. 模型看到 3 条 `role=tool`，给出最终文字。

真实模型也可能拆成多轮，或者中途改参数。循环允许这件事，代码不用提前写死路径。

## 和笔记的对应

| 笔记 | 本工程 |
|---|---|
| `MiniAgent.run` | `agent/MiniAgent.java` |
| `Tool` / 三个工具 | `tool` 包 |
| `LlmClient` | `LlmClient` + `OpenAiLlmClient` |
| `memory` 字段 | `ConversationMemory`，按 `conversationId` 分开，避免串话 |
| `main` 里的 Scanner | `POST /api/chat` 和学习页 |

在最小循环上多出来的四件事，笔记第五节都点过名：

- 多个 `tool_calls` 用 `CompletableFuture` 并行，再按 `tool_call_id` 顺序回填
- 同一个工具、同一组参数超过 2 次，就塞一条 ERROR 让模型停手
- 工具抛异常时不中断循环，错误文本写回 memory
- 每一步追加到 `logs/agent-trace.jsonl`

## 面试时可以这样讲

用 60 秒把项目讲完：

1. Agent 是模型加工具加循环加记忆。Chain 的路径在写代码时就固定了。Agent 的路径由模型在运行时选择，所以更慢、更不确定、更难测。生产上常见的做法是流程做骨架，只把开放分支交给 Agent。
2. 打开 `MiniAgent.run`。这是一个有上限的循环。模型返回文字就结束，返回 `tool_calls` 就执行。
3. Function Calling 是三次握手：请求里带工具的 JSON Schema；响应里是 `tool_calls[{id, function:{name, arguments}}]`；再追加 `{role:"tool", tool_call_id, content}`。`arguments` 在协议里是字符串，要再解析一次。
4. assistant 那条带 `tool_calls` 的消息必须原样留在 messages 里，否则下一轮接口会 400。`tool_call_id` 要一一对应。
5. 工具失败也是上下文。直接抛到循环外面，整次任务就断了。把 `ERROR: ...` 交回去，模型可以换参数。
6. `maxSteps` 是防死循环的硬上限。这里还加了「相同调用超过 2 次」的刹车。
7. 现在的 memory 是短期记忆，就是完整消息数组。它会涨，会话一重启就没了。再往下是滑窗或摘要，以及长期记忆（用户偏好、历史结论、知识库）。
8. 这个实现故意没做：流式输出、上下文压缩、重试限流、工具参数的结构化校验、记忆落库。能说出这些，说明你知道最小循环和工程化之间差在哪。

再补三个容易被追问的点：

| 问题 | 回答要点 |
|---|---|
| ReAct 和 Function Calling？ | ReAct 让模型输出 Thought/Action/Observation 文本，调用方自己解析。Function Calling 由模型直接返回结构化的 `tool_calls`。现在的主流是后者。 |
| 为什么会跑偏？ | 工具描述不清楚、上下文太长、工具返回不稳定。描述要写清何时使用、参数用 `required` 和 `enum` 收紧、返回尽量短。 |
| 怎么评测？ | 任务成功率、工具选对的比例、平均步数、token 成本。每次模型输入输出和工具调用都要落 trace，否则没法查。 |

工具描述是给模型看的 prompt。`GET /api/tools` 返回的就是塞进请求里的那份 schema。`search_knowledge` 的 `query` 带了 `enum`，这是笔记里说的「参数 schema 要严」。

## 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | `/api/chat` | 跑一轮。body：`message`，可选 `conversationId` |
| GET | `/api/conversations/{id}` | 看这份短期记忆 |
| DELETE | `/api/conversations/{id}` | 忘掉这段会话 |
| GET | `/api/tools` | 模型能看见的工具 schema |
| GET | `/api/meta` | 当前是 mock 还是 openai |

同一会话串行执行，避免两个人同时改同一份 messages。不同会话互不影响。

## 练习

1. 加一个天气工具：新建类实现 `Tool`，在 `AgentConfig.toolRegistry` 里注册。mock 模式还要在 `MockLlmClient` 里加关键词，它才会主动调用。openai 模式不用改 mock，模型会自己看 description。
2. 把「重复 2 次」改成配置项，放到 `AgentProperties`。
3. 打开 `logs/agent-trace.jsonl`，用一轮真实对话讲可观测性：用户原话、每次工具、最终回答都在。
4. 用 openai 模式跑笔记里的那句话，看模型是一次叫齐三个工具，还是拆成多轮。两种都算对，因为循环没有写死次数。
