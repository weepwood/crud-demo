# PostgreSQL CRUD Console

一个基于 PostgreSQL 元数据自动生成管理界面的前后端程序。后端不需要为每张表创建实体类、Repository 或 Controller；只要数据库账号具备相应权限，前端就能列出表、查看数据，并执行新增、修改和删除。

## 功能

- 自动读取非系统 Schema、普通表和分区表
- 自动识别字段类型、默认值、NULL、Identity、Generated Column 和主键
- 动态数据表格、分页、全字段搜索和排序
- 动态新增/编辑表单
- 支持单主键和复合主键
- 无主键表通过 PostgreSQL `ctid` 提供当前快照内的修改与删除
- 根据数据库账号的 `INSERT`、`UPDATE`、`DELETE` 权限自动禁用操作
- HTTP Basic 登录保护
- Schema 白名单、只读模式、最大分页限制
- Docker Compose 一键启动 PostgreSQL、Spring Boot 和 Vue 前端
- GitHub Actions 自动测试与构建

## 技术栈

- 前端：Vue 3、TypeScript、Vite，未使用 TailwindCSS
- 后端：Java 21、Spring Boot 4.1、Spring JDBC、Spring Security
- 数据库：PostgreSQL 16
- 部署：Docker Compose、Nginx

## 一键启动

```bash
cp .env.example .env
```

首先修改 `.env` 中的以下密码：

```dotenv
POSTGRES_PASSWORD=your-strong-database-password
APP_PASSWORD=your-strong-console-password
```

然后启动：

```bash
docker compose up --build
```

Windows PowerShell 也可以运行：

```powershell
.\scripts\dev.ps1
```

访问：

- 管理前端：`http://localhost:8080`
- 后端健康检查：`http://localhost:8081/actuator/health`
- 默认用户名：`admin`
- 密码：`.env` 中的 `APP_PASSWORD`

首次启动会创建示例表：`customers`、`products`、`orders`、`order_items` 和无主键示例表 `notes_without_pk`。

## 连接已有 PostgreSQL

可以只运行前后端，或直接在本机启动后端。关键环境变量：

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/crud_demo` | JDBC 地址 |
| `DB_USERNAME` | `postgres` | 数据库账号 |
| `DB_PASSWORD` | `postgres` | 数据库密码 |
| `APP_USERNAME` | `admin` | 控制台登录名 |
| `APP_PASSWORD` | `change-me-now` | 控制台密码，生产环境必须修改 |
| `APP_ALLOWED_SCHEMAS` | 空 | Schema 白名单，逗号分隔；空值表示所有非系统 Schema |
| `APP_READ_ONLY` | `false` | 设为 `true` 后禁止写操作 |
| `APP_MAX_PAGE_SIZE` | `200` | 单页最大记录数 |
| `APP_ALLOWED_ORIGINS` | 本地开发地址 | CORS 白名单，逗号分隔 |

本地开发：

```bash
# 后端
cd backend
mvn spring-boot:run

# 前端，另开终端
cd frontend
npm install
npm run dev
```

Vite 会把 `/api` 代理到 `http://localhost:8080`。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/meta/schemas` | 列出允许访问的 Schema |
| `GET` | `/api/meta/{schema}/tables` | 列出可读取的表和写权限 |
| `GET` | `/api/meta/{schema}/{table}` | 获取字段和主键元数据 |
| `GET` | `/api/data/{schema}/{table}` | 分页、搜索、排序读取数据 |
| `POST` | `/api/data/{schema}/{table}` | 新增记录 |
| `PUT` | `/api/data/{schema}/{table}` | 按主键或 `ctid` 修改记录 |
| `DELETE` | `/api/data/{schema}/{table}` | 按主键或 `ctid` 删除记录 |

所有 API（健康检查除外）均使用 HTTP Basic Authentication。

## 安全设计

该程序本质上是数据库管理工具，不应直接暴露在公网。

1. 使用专用数据库账号，并遵循最小权限原则。
2. 通过 `APP_ALLOWED_SCHEMAS` 限制可见 Schema。
3. 只需要查询时启用 `APP_READ_ONLY=true`。
4. 生产环境必须修改默认控制台密码和数据库密码。
5. 建议放在 VPN、内网、反向代理身份认证或 IP 白名单之后。
6. 后端不接受任意 SQL。Schema、表名、字段名都必须来自数据库元数据，数据值使用 JDBC 参数绑定。
7. 无主键表的 `ctid` 不是稳定业务标识。并发更新、行迁移或 `VACUUM FULL` 后可能变化，正式数据应添加主键。
8. 全字段模糊搜索会把字段转换为文本，在大表上可能较慢；大规模生产表建议后续增加字段级筛选和索引策略。

## 类型支持

常见数字、字符串、布尔、日期时间、UUID、JSON/JSONB、枚举和数组均通过 PostgreSQL 类型转换处理。数组等高级类型在表单中可直接填写 PostgreSQL 字面量，例如：

```text
{tag-a,tag-b}
```

`bytea`、Range、复合类型等特殊字段可读取，但编辑时需要填写 PostgreSQL 可识别的文本形式。

## 项目结构

```text
crud-demo/
├─ backend/             Spring Boot 通用 CRUD API
├─ frontend/            Vue 3 动态管理界面
├─ database/init/       PostgreSQL 示例结构与数据
├─ scripts/             Windows/Linux 启动脚本
├─ .github/workflows/   CI
└─ docker-compose.yml
```

## 后续可扩展方向

- 字段级过滤器、索引提示和大表游标分页
- 外键字段下拉选择与关联数据预览
- CSV/Excel 导入导出
- 变更审计、回收站和敏感字段脱敏
- 用户、角色、表级和字段级权限
- 保存视图、批量编辑和批量删除

## License

MIT
