# 记账助手 · Android 壳（acbk-shell）

把 [acbk-panel](../../acbk-panel) 那个面板网页，装进一个**没有浏览器界面**的 Android App，
并补上裸 WebView 默认没有的三根线：**打印 / 拍照选图 / 下载**。

## 为什么需要它

| 方案 | 无浏览器界面 | 🖨 打印 | 📎 拍照 | 需要装 Chrome |
|---|---|---|---|---|
| Via 浏览器「添加到桌面」 | ❌ 有地址栏和工具栏 | ✅ | ✅ | 否 |
| 一个木函「网页转应用」 | ✅ | ❌ **实测不可用** | ✅ | 否 |
| TWA（PWABuilder） | ✅ | ✅ | ✅ | **是** |
| **本 App** | ✅ | ✅ | ✅ | **否** |

根因是：Android 的 WebView 对 `window.print()` 是**空实现**，`<input type="file">` 也必须由宿主实现
`onShowFileChooser`。各家「网页转应用」壳接不接这两根线全看作者 —— 一个木函接了选图、没接打印。
所以这个 App 的意义就是：**自己把线接上**。

## 它不做什么

- **不重写前端**：直接加载面板现有的 `index.html`，一行前端代码都不复制
- **不改后端**：面板的 `/api/run`(SSE)、`/api/upload`、`/api/history`、`/pdf-page` 全部照用
- 面板以后改版，**这个 App 不用重新编译**，自动跟着变

## 快速开始（不需要本地装任何开发环境）

### 1. 生成签名密钥（一次性，约 2 分钟）

**为什么必须配**：GitHub 的 runner 每次都是全新虚拟机。不配的话 AGP 会临时生成一个 debug 密钥，
于是**每次 CI 出来的 APK 签名都不一样** —— 首次安装没问题，但装新版覆盖旧版会失败
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，必须先卸载）。配一次固定密钥就永久稳定。

#### 1.1 生成 keystore

**有 JDK 的机器**（`keytool` 随 JDK 附带）：

```bash
keytool -genkeypair -v \
  -keystore acbk-release.p12 -storetype PKCS12 \
  -alias acbk -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=ACBK Shell, O=acbk, C=CN" \
  -storepass '你的口令' -keypass '你的口令'
```

**没有 JDK 的机器**（用 openssl，等价产物）：

```bash
openssl req -x509 -newkey rsa:2048 -keyout k.pem -out c.pem -days 10950 -nodes \
  -subj "/CN=ACBK Shell/O=acbk/C=CN"
openssl pkcs12 -export -in c.pem -inkey k.pem -name acbk -out acbk-release.p12 \
  -passout 'pass:你的口令' -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1
rm -f k.pem c.pem
```

> `-alias` / `-name` 是密钥别名，随你起名，后面要填进 Secret。
> PKCS12 **只支持一个口令**（库口令 = 密钥口令），所以上面两处填**同一个值**；
> 填成不同值会被 keytool 忽略并警告。openssl 那条刻意用 3DES/RC2 老算法，是为了最大化 Java 兼容性。
> 两条命令都是非交互的（`-dname` 已把姓名/组织等提示项都填掉了）。

#### 1.2 转成 base64

```bash
base64 -w0 acbk-release.p12        # 复制输出的那一整行
```

### 2. 配置 4 个 GitHub Secrets

仓库里**不含面板地址，也不含任何密钥、口令、别名** —— 全部在构建时注入。
缺任何一项 CI 会**直接失败**，不会静默降级（降级出来的包能装但用不了、或无法覆盖升级，比报错更难排查）。

GitHub 仓库 → **Settings → Secrets and variables → Actions → New repository secret**：

| Name | Value | 说明 |
|---|---|---|
| `ACBK_PANEL_URL` | `https://你的面板域名/` | 面板公网地址，**必须 https**、结尾带 `/` |
| `ACBK_KEYSTORE_B64` | 1.2 复制的那一整行 base64 | 签名密钥 |
| `ACBK_STORE_PASSWORD` | 1.1 里你设置的口令 | keystore 口令 |
| `ACBK_KEY_ALIAS` | 1.1 里你用的别名（示例是 `acbk`） | 密钥别名 |

> **面板地址严格来说不算机密**：它最终一定会被编译进 APK（否则 App 不知道去哪加载页面），
> 拿到 APK 的人能解出来。放进 Secret 的实际意义是**不让它出现在 git 仓库里**。
>
> CI 里另有一道前置校验 `keytool -importkeystore`：它会**真的用口令解密私钥**，
> 所以「口令错 / 别名不存在 / Java 读不出这个 keystore」都会在编译前就明确报错。

**务必备份 `acbk-release.p12` 和口令**。密钥丢了就没法再给已安装的 App 发更新，只能卸载重装。
另外签名一旦更换，手机上已装的旧版本必须先卸载才能装新的。

### 3. 推到 GitHub

```bash
cd acbk-shell
git init -b main
git add .
git commit -m "记账助手 Android 壳"
git remote add origin git@github.com:<你的账号>/acbk-shell.git
git push -u origin main
```

> **如果 git 报「可疑的仓库所有权」（detected dubious ownership）**：
> 这个目录在 SMB 挂载上，文件属主被强制映射成 `nobody`，和你的登录用户不一致。加一条豁免即可：
>
> ```bash
> git config --global --add safe.directory /home/zws/SMB/ac-book/acbk-shell
> ```

### 4. 等 CI 编译，下载 APK

推上去后 GitHub Actions 会自动开始编译（约 2–4 分钟）。

**Actions → 最新那次 Build APK → 页面底部 Artifacts → 下载 `acbk-shell-apk.zip`**

解压后有两个包：

| 文件 | 说明 |
|---|---|
| `app-release.apk` | **装这个**（体积小、用固定密钥签名，可直接覆盖升级） |
| `app-debug.apk` | 排错用（可连电脑看 WebView 控制台） |

也可以在 Actions 页面点 **Run workflow** 手动触发。

### 5. 安装

传到手机上点开安装，需要允许「安装未知来源应用」。首次打开会看到面板的登录页，登录一次之后保持。

## 三根线分别对应什么

| 能力 | 实现位置 | 对应面板功能 |
|---|---|---|
| 🖨 **打印** | 注入覆盖 `window.print` → JS 桥 → `createPrintDocumentAdapter` / `PrintManager` | 查看器里的「🖨 打印」按钮 |
| 📎 **拍照 / 选图** | `WebChromeClient.onShowFileChooser` | 输入框左边的图片按钮 |
| ⬇ **下载** | `setDownloadListener` + 系统 DownloadManager（**带登录 cookie**） | 查看器里的「⬇ 下载」 |

顺手还接了两根：

- **长按图片 → 保存到相册**（打印不出来时的兜底：存图后用相册打印）
- **返回键 → 网页后退**；站外链接交给系统浏览器

## 打印是怎么实现的（排错时看这段）

点面板的「🖨 打印」时，流程是：

1. 面板调用 `window.print()`；
2. App 注入的覆盖函数**抢先接住**，读取查看器里 `#vDownload` 的地址 —— 那就是**账单 PDF 的真实地址**；
3. App 用 WebView 的登录 cookie 把这个 PDF 下载下来；
4. 交给 Android 系统打印服务（`PrintManager`），**打印 PDF 本身**。

所以打出来的东西和生成的结账单**完全一致**，不受网页 CSS 影响。

如果第 2 步拿不到 PDF 地址（比如你不是在账单预览页点的打印），会自动**退回打印当前网页**。

> 系统打印对话框里选打印机 —— 前提是手机上装了对应打印机的**打印服务插件**（惠普/兄弟/爱普生等的官方 App，或系统自带的打印服务）。

## 常见问题

**构建失败在 `sdkmanager` / licenses？**
工作流里那步是 `|| true` 容错的，真失败一般是 runner 镜像变更。可以在 Actions 日志里看具体报错，或把 `compileSdk 34` 改成 35 试试。

**装完打开提示「证书校验失败」？**
`ACBK_PANEL_URL` 必须用**有效 HTTPS 证书**的域名。自签证书会被系统拒绝 —— 这是故意的，不静默放行。

**装完打开提示「还没配置面板地址」？**
说明构建时没注入 `ACBK_PANEL_URL`（或它不以 `http` 开头）。检查 Secret 是否配置、地址是否带 `https://` 前缀，然后重新构建。

**点了 🖨 打印没反应？**
先确认是在**账单预览页**点的（能看到账单图）。如果连系统打印对话框都没弹出，说明手机上没有可用的打印服务 —— 装一个打印机厂商的 App 即可。

**打印出来是网页截图而不是账单？**
说明第 2 步没拿到 PDF 地址，走了兜底路径。请在查看器打开账单后再点打印。

**点了 📎 选了图片，但输入框上什么都没出现？**
已修复。根因：壳原来用 `FileChooserParams.parseResult()` 取选中的图片，它只看
`getClipData()` / `getData()`，都取不到就返回 `null` —— 而 `onReceiveValue(null)`
在 WebView 里等于「用户取消」，**页面连 `change` 事件都收不到**，于是表现成"选了没反应"。
现在取不到时会自己兜一层 `getData()` → `EXTRA_STREAM`。**面板侧未做任何改动。**

**登录状态会丢吗？**
WebView 有独立的 cookie 存储，和手机浏览器互不影响。登录一次后保持；面板的会话有效期由服务端决定（见面板配置里的 `auth_cookie_session_ttl`）。

**签名是怎么处理的？**
代码里**没有任何密钥、口令、别名**。CI 从 3 个 Secret 取值：`ACBK_KEYSTORE_B64` 解码成临时文件，
路径与 `ACBK_STORE_PASSWORD`、`ACBK_KEY_ALIAS` 一起作为环境变量传给 Gradle；
`app/build.gradle` 读不到就**直接构建失败**（不会偷偷用 debug 签名）。流程中有两道保险：
编译前 `keytool -importkeystore` 真解密一次私钥（验证口令/别名/Java 可读性），
编译后 `apksigner verify --print-certs` 打印实际签名指纹供核对。

**想换密钥？**
按 README「2.1 生成 keystore」重新生成，更新那 3 个 Secret 即可。注意**签名一换，
手机上已安装的旧版必须先卸载**才能装新版。

**想换图标？**
改 `tools/make-icons.py` 里的品牌色常量，然后 `python3 tools/make-icons.py` 重新生成。
注意**自适应图标分两层**：背景层只能有底色（`with_glyph=False`），图形放前景层 ——
两层都画图形的话，桌面上会出现大图形套小图形的**双层重影**。
`FOREGROUND_SCALE` 控制前景图形大小：自适应图标只有中央 72dp（画布的 66.7%）一定可见，
超出部分会被启动器的遮罩切掉。

**想改 App 名字？**
`app/src/main/res/values/strings.xml` 里的 `app_name`。

## 目录结构

```
acbk-shell/
├── .github/workflows/build.yml         CI：装 JDK+Gradle → 解码 keystore → 编译 → 上传 APK
├── app/
│   ├── build.gradle                    minSdk 21 / targetSdk 34 / 零第三方依赖 / 签名配置
│   └── src/main/
│       ├── AndroidManifest.xml         INTERNET + 一个 Activity
│       ├── java/cn/acbk/shell/
│       │   └── MainActivity.java       全部逻辑（约 450 行）
│       └── res/
│           ├── values/strings.xml      只有 app_name（panel_url 由构建时注入）
│           ├── values/styles.xml       浅色主题（父主题带 .Light）
│           ├── values-night/styles.xml 深色主题（跟随系统）
│           └── mipmap-*/               图标
├── tools/make-icons.py                 图标生成脚本（纯 numpy 自栅格化，可复现）
├── build.gradle / settings.gradle / gradle.properties
└── README.md
```

> 没有 `keystore/` 目录 —— 签名密钥由你自己生成并放进 GitHub Secrets，仓库里不留。

## 技术选择说明

- **纯 Java + 零第三方依赖**：`android.webkit` 与 `android.print` 都是系统 API，不用 AndroidX，
  所以 APK 很小、编译快、不会因为依赖版本互相打架（本工程 501 行 Java + 48 KB 资源，无任何第三方库）。
- **不提交 Gradle Wrapper**：避免往仓库塞二进制；CI 里直接装 Gradle 8.7。
- **代码里零密钥、零服务器地址**：面板地址与签名密钥全部来自 GitHub Secrets，构建时注入
  （地址走 `resValue` 写进 `R.string.panel_url`，keystore 只落在 runner 临时目录、随虚拟机销毁）；
  `app/build.gradle` 读不到配置就**直接失败**，避免产出装不上的 debug 签名包。
- **应用端不缓存内容**：壳只持久保留登录 cookie（WebView 自带）。下载的账单 PDF 与长按保存的图片
  只是喂给系统打印/相册 API 的临时中转文件，用完即删。图片数据只发往服务端，清理由服务端负责。
- **`launchMode=singleTask` + `configChanges`**：旋转屏幕、从桌面再次点开都不会重建 Activity，
  避免 WebView 重载把正在跑的 SSE 流断掉。但**刻意不接管 `uiMode`** —— 切换深浅色时让 Activity
  重建，主题才会重新解析（见下条）。
- **深浅色跟随系统**：`values/styles.xml` 是浅色主题、`values-night/styles.xml` 是深色主题。
  父主题带不带 `.Light` 决定 `android:isLightTheme`，而 WebView 正是靠这个属性向网页报告
  `prefers-color-scheme: light` 还是 `dark` —— 面板自带的那套配色
  （`@media (prefers-color-scheme: dark)`）随之自动切换，壳里不需要任何暗色代码。
  切换瞬间 WebView 会重载，面板的服务端历史恢复 + 断线重连会接回去。
- **`addJavascriptInterface` 的安全边界**：该桥只暴露一个 `print` 方法，且站外链接一律交给系统浏览器，
  面板页面不会被导航到第三方站点。

## 已知边界

- 需要 **Android 5.0+**（`createPrintDocumentAdapter` 要 API 19，`onShowFileChooser` 要 API 21）
- **Android 9 及以下**：下载到公共「下载」目录需要手动授予存储权限（新系统走分区存储，不需要）
- **长按存图**依赖 WebView 的长按命中测试，个别机型可能不触发；下载功能不受影响
- 只能**侧载**（不在任何应用商店）
- 以后若要加**新的原生能力**（通知、指纹锁等），需要改代码重新编译 —— 但改面板不用
