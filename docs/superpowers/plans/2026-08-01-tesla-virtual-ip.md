# 特斯拉虚拟 IP 兼容模式 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给手机热点网卡添加 `100.99.9.9/32` 别名，让封锁 RFC1918 网段的特斯拉车机浏览器能访问 Castla 投屏页面。

**Architecture:** 纯逻辑（接口解析、URL 生成、结果判定）放进 `TeslaVirtualIp` 单测覆盖；netd binder / shell 命令的实际调用在 Shizuku/Stellar 特权进程 `PrivilegedService` 内以级联方式实现并返回诊断日志；`MainActivity` 在投屏启动时自动设置别名、停止时清理，URL 显示优先用虚拟地址。

**Tech Stack:** Kotlin, Android (minSdk 26), JUnit4, Shizuku API (Stellar 兼容), NanoHTTPD/NanoWSD

## Global Constraints

- 遵循项目 CLAUDE.md：TDD，可测逻辑放纯工具类，测试命令 `./gradlew :app:testDebugUnitTest`
- 虚拟 IP 固定为 `100.99.9.9`，前缀 `/32`，显示 URL 沿用 sslip.io 格式
- 只在热点模式下启用（检测到 `swlan0`/`wlan1`/`ap0`/`softap0`）；WiFi 模式行为不变
- 别名设置失败必须静默回退到原有地址显示，不得影响投屏主流程
- 服务器端（MirrorServer/NanoHTTPD）零改动
- `restartTetheringWithCgnat`（方案 C 的脚手架）保持空壳，不在本计划范围内（YAGNI）
- 诊断日志同时写 `Log` 和 `FileLogger`（`com.castla.mirror.diagnostics.FileLogger`，API：`FileLogger.i(tag, msg)` / `FileLogger.w(tag, msg)`）

## 关键既有代码（实现者须知）

- `app/src/main/aidl/com/castla/mirror/shizuku/IPrivilegedService.aidl` — AIDL 已定义 `addInterfaceAddress` (=7)、`removeInterfaceAddress` (=8)、`setupTeslaNetworking` (=9)，**无需改 AIDL**
- `PrivilegedService.kt:702-705` — 三个方法的空壳实现，即本计划的替换目标
- `PrivilegedService.kt:349` — `execCommand(command: String): String`，在特权进程内执行 `sh -c`，stderr 含 SecurityException 时会抛异常
- `PrivilegedService.kt:648-650` — 现成的 ServiceManager 反射调用模式（clipboard 示例），netd binder 调用照搬此模式
- `ShizukuSetup.kt:759` — `setupTeslaNetwork(ifName, virtualIp = "100.99.9.9"): String?` 已存在，返回诊断日志；`serviceConnected: StateFlow<Boolean>` 已存在；`exec(cmd): String?` 已存在
- `MainActivity.kt:91-92` — `serverUrl` / `currentIp` 为 `mutableStateOf`
- `MainActivity.kt:216-225` — `networkMonitor.state.collect` 分支（Connected / Disconnected）
- `MainActivity.kt:643-659` — `updateServerUrl()` 现状（cellular 非 10.x 优先 → hotspotIp → sslip 包装）
- `MainActivity.kt:788-795` — `findHotspotInterface()` 现状（`shizukuSetup.exec("ip -o addr show")` + 子串匹配候选名 `swlan0/wlan1/ap0/softap0`）
- `MainActivity.kt:1175-1204` — `startMirrorService`（三条启动路径都汇聚到 `launchMirrorService`）
- `MainActivity.kt:1206-1253` — `launchMirrorService`，`isStreaming = true` 在其中设置
- `MainActivity.kt:1255-1277` — `stopMirrorService`
- `MirrorServer.DEFAULT_PORT = 9090`（`MirrorServer.kt:20`）
- 测试约定：JUnit4，反引号测试名，纯逻辑 object 风格（参考 `app/src/test/java/com/castla/mirror/policy/DisconnectPolicyTest.kt`）

---

### Task 1: 纯逻辑类 TeslaVirtualIp + 单元测试

**Files:**
- Create: `app/src/main/java/com/castla/mirror/network/TeslaVirtualIp.kt`
- Test: `app/src/test/java/com/castla/mirror/network/TeslaVirtualIpTest.kt`

**Interfaces:**
- Consumes: 无
- Produces（后续任务依赖的确切签名）:
  - `TeslaVirtualIp.VIRTUAL_IP: String` = `"100.99.9.9"`
  - `TeslaVirtualIp.VIRTUAL_PREFIX: Int` = `32`
  - `TeslaVirtualIp.parseInterfaceAddresses(ipOutput: String): Map<String, List<String>>`
  - `TeslaVirtualIp.findHotspotInterface(addrs: Map<String, List<String>>): String?`
  - `TeslaVirtualIp.hasVirtualAlias(addrs: Map<String, List<String>>, ifName: String): Boolean`
  - `TeslaVirtualIp.virtualUrl(port: Int): String`
  - `TeslaVirtualIp.setupSucceeded(diagnosticLog: String?): Boolean`
  - 诊断日志结果标记：`"RESULT: OK"` / `"RESULT: FAIL"`（PrivilegedService 写入，MainActivity 判定）

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/castla/mirror/network/TeslaVirtualIpTest.kt`：

```kotlin
package com.castla.mirror.network

import org.junit.Assert.*
import org.junit.Test

class TeslaVirtualIpTest {

    private val sampleIpOutput = """
        1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
        2: rmnet_data0    inet 10.166.96.133/28 scope global rmnet_data0\       valid_lft forever preferred_lft forever
        5: swlan0    inet 10.166.96.1/24 brd 10.166.96.255 scope global swlan0\       valid_lft forever preferred_lft forever
    """.trimIndent()

    // ── parseInterfaceAddresses ──

    @Test
    fun `parse extracts ipv4 addresses per interface`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(sampleIpOutput)
        assertEquals(listOf("10.166.96.1"), addrs["swlan0"])
        assertEquals(listOf("10.166.96.133"), addrs["rmnet_data0"])
        assertEquals(listOf("127.0.0.1"), addrs["lo"])
    }

    @Test
    fun `parse keeps multiple addresses on same interface`() {
        val out = "5: ap0    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0\n" +
                  "5: ap0    inet 100.99.9.9/32 scope global ap0"
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(out)
        assertEquals(listOf("192.168.43.1", "100.99.9.9"), addrs["ap0"])
    }

    @Test
    fun `parse skips ipv6 and malformed lines`() {
        val out = "5: swlan0    inet6 fe80::1/64 scope link\n" +
                  "garbage line\n" +
                  "5: swlan0    inet 10.0.0.1/24 scope global swlan0"
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(out)
        assertEquals(listOf("10.0.0.1"), addrs["swlan0"])
    }

    // ── findHotspotInterface ──

    @Test
    fun `finds swlan0 as hotspot interface`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(sampleIpOutput)
        assertEquals("swlan0", TeslaVirtualIp.findHotspotInterface(addrs))
    }

    @Test
    fun `finds ap0 when swlan0 absent`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(
            "3: ap0    inet 192.168.43.1/24 scope global ap0"
        )
        assertEquals("ap0", TeslaVirtualIp.findHotspotInterface(addrs))
    }

    @Test
    fun `prefers swlan0 over ap0 when both present`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(
            "3: ap0    inet 192.168.43.1/24 scope global ap0\n" +
            "5: swlan0    inet 10.0.0.1/24 scope global swlan0"
        )
        assertEquals("swlan0", TeslaVirtualIp.findHotspotInterface(addrs))
    }

    @Test
    fun `returns null when no hotspot interface`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(
            "1: lo    inet 127.0.0.1/8 scope host lo\n" +
            "2: wlan0    inet 192.168.1.5/24 scope global wlan0"
        )
        assertNull(TeslaVirtualIp.findHotspotInterface(addrs))
    }

    // ── hasVirtualAlias ──

    @Test
    fun `hasVirtualAlias true when alias present`() {
        val addrs = mapOf("swlan0" to listOf("10.166.96.1", "100.99.9.9"))
        assertTrue(TeslaVirtualIp.hasVirtualAlias(addrs, "swlan0"))
    }

    @Test
    fun `hasVirtualAlias false when alias absent or interface unknown`() {
        assertFalse(TeslaVirtualIp.hasVirtualAlias(mapOf("swlan0" to listOf("10.166.96.1")), "swlan0"))
        assertFalse(TeslaVirtualIp.hasVirtualAlias(emptyMap(), "swlan0"))
    }

    // ── virtualUrl ──

    @Test
    fun `virtualUrl uses sslip domain form`() {
        assertEquals("http://100-99-9-9.sslip.io:9090", TeslaVirtualIp.virtualUrl(9090))
    }

    // ── setupSucceeded ──

    @Test
    fun `setupSucceeded true on RESULT OK line`() {
        assertTrue(TeslaVirtualIp.setupSucceeded("=== setup ===\nsome detail\nRESULT: OK"))
    }

    @Test
    fun `setupSucceeded false on RESULT FAIL, garbage, or null`() {
        assertFalse(TeslaVirtualIp.setupSucceeded("=== setup ===\nRESULT: FAIL"))
        assertFalse(TeslaVirtualIp.setupSucceeded("random output"))
        assertFalse(TeslaVirtualIp.setupSucceeded(null))
        assertFalse(TeslaVirtualIp.setupSucceeded(""))
    }

    // ── constants ──

    @Test
    fun `virtual ip is in cgnat range which tesla does not block`() {
        assertEquals("100.99.9.9", TeslaVirtualIp.VIRTUAL_IP)
        assertEquals(32, TeslaVirtualIp.VIRTUAL_PREFIX)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.castla.mirror.network.TeslaVirtualIpTest"`
Expected: 编译失败 / 全部失败（`TeslaVirtualIp` 不存在）

- [ ] **Step 3: 实现 TeslaVirtualIp**

创建 `app/src/main/java/com/castla/mirror/network/TeslaVirtualIp.kt`：

```kotlin
package com.castla.mirror.network

/**
 * Pure logic for the Tesla virtual-IP compatibility mode.
 *
 * Tesla's browser refuses connections to RFC1918 private ranges
 * (10/8, 172.16/12, 192.168/16) with ERR_CONNECTION_REFUSED. The
 * workaround (same approach as TeslaMirror): give the phone's hotspot
 * interface an alias inside the CGNAT range 100.64.0.0/10, which Tesla
 * does not block. Everything here is pure and unit-testable; the
 * privileged netd/shell calls live in PrivilegedService.
 */
object TeslaVirtualIp {

    const val VIRTUAL_IP = "100.99.9.9"
    const val VIRTUAL_PREFIX = 32

    const val RESULT_OK = "RESULT: OK"
    const val RESULT_FAIL = "RESULT: FAIL"

    /** Hotspot interface names, highest priority first. */
    private val HOTSPOT_IFACE_CANDIDATES = listOf("swlan0", "wlan1", "ap0", "softap0")

    /**
     * Parse `ip -o addr show` output into ifName -> IPv4 addresses (without prefix).
     * In `-o` mode each record is one line like:
     *   5: swlan0    inet 10.166.96.1/24 brd 10.166.96.255 scope global swlan0\       valid_lft ...
     */
    fun parseInterfaceAddresses(ipOutput: String): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (line in ipOutput.lineSequence()) {
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 4) continue
            val ifName = tokens[1].removeSuffix(":")
            if (tokens[2] != "inet") continue
            val ip = tokens[3].substringBefore('/')
            if (ip.isEmpty()) continue
            result.getOrPut(ifName) { mutableListOf() }.add(ip)
        }
        return result
    }

    /** First hotspot interface present in the parsed map, or null (WiFi mode). */
    fun findHotspotInterface(addrs: Map<String, List<String>>): String? =
        HOTSPOT_IFACE_CANDIDATES.firstOrNull { addrs.containsKey(it) }

    /** True when the virtual alias is already present on the interface (idempotency check). */
    fun hasVirtualAlias(addrs: Map<String, List<String>>, ifName: String): Boolean =
        addrs[ifName]?.contains(VIRTUAL_IP) == true

    /**
     * URL to display when the alias is active. sslip.io domain form avoids the
     * Tesla browser's HTTPS-first upgrade applied to bare IPs.
     */
    fun virtualUrl(port: Int): String =
        "http://${VIRTUAL_IP.replace('.', '-')}.sslip.io:$port"

    /** True when a setupTeslaNetworking diagnostic log reports success. */
    fun setupSucceeded(diagnosticLog: String?): Boolean =
        diagnosticLog?.lineSequence()?.any { it.trim() == RESULT_OK } == true
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.castla.mirror.network.TeslaVirtualIpTest"`
Expected: 全部 PASS（14 个测试）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/castla/mirror/network/TeslaVirtualIp.kt app/src/test/java/com/castla/mirror/network/TeslaVirtualIpTest.kt
git commit -m "Add TeslaVirtualIp pure logic for Tesla virtual-IP mode"
```

---

### Task 2: PrivilegedService 实现别名增删与级联设置

**Files:**
- Modify: `app/src/main/java/com/castla/mirror/shizuku/PrivilegedService.kt:702-705`（替换四个空壳中的三个；`restartTetheringWithCgnat` 保持空壳）

**Interfaces:**
- Consumes: `TeslaVirtualIp.RESULT_OK` / `RESULT_FAIL` 字符串值（这里直接写字面量 `"RESULT: OK"` / `"RESULT: FAIL"`，与 Task 1 常量保持一致——PrivilegedService 跑在特权进程，不 import app 侧类以避免类加载问题，用字面量更安全）
- Produces（AIDL 契约，Task 3 的 ShizukuSetup 包装直接调用）:
  - `addInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean` — netd binder 成功返回 true
  - `removeInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean` — 尽力移除，任何一路成功即 true
  - `setupTeslaNetworking(ifName: String, virtualIp: String): String` — 返回诊断日志，末行为 `RESULT: OK` 或 `RESULT: FAIL`，幂等

**背景知识（实现者必读）：**
- 本类运行在 Shizuku/Stellar 特权进程（uid 2000 shell 或 root），不是 app 进程
- netd 服务名 `"netd"`，接口 `android.net.INetd`，方法 `interfaceAddAddress(String, String, int)` / `interfaceDelAddress(String, String, int)`（Android 9+ 存在；个别 ROM 可能缺失或拒绝 → 必须 try/catch 兜底）
- 反射调用模式照抄本文件 648-650 行 clipboard 的写法
- `execCommand(cmd)` 返回 stdout；`ip addr add` 在 shell uid 下通常 EPERM（stderr 记录但不抛），在 root 下成功。**是否成功以随后 `ip -o addr show dev <if>` 的验证为准，不信返回值**

- [ ] **Step 1: 替换空壳实现**

将 `PrivilegedService.kt:702-705` 的四个空壳替换为（`restartTetheringWithCgnat` 保留原样）：

```kotlin
    // --- Tesla virtual IP (100.99.9.9) alias management ---
    // Tesla's browser refuses RFC1918 addresses; the CGNAT alias lets it connect.

    /**
     * Add an IP alias via the netd binder (INetd.interfaceAddAddress).
     * netd runs as root and accepts this call from the shell uid on most builds.
     * Returns true only if the binder call completed without throwing —
     * callers must verify the alias with `ip -o addr show`.
     */
    override fun addInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val netdBinder = getService.invoke(null, "netd") as? android.os.IBinder
            if (netdBinder == null) {
                Log.w(TAG, "addInterfaceAddress: netd binder is null")
                return false
            }
            val stubClass = Class.forName("android.net.INetd\$Stub")
            val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val netd = asInterface.invoke(null, netdBinder)
            netd.javaClass.getMethod(
                "interfaceAddAddress",
                String::class.java, String::class.java, Int::class.javaPrimitiveType
            ).invoke(netd, ifName, address, prefixLength)
            Log.i(TAG, "addInterfaceAddress: netd accepted $address/$prefixLength on $ifName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "addInterfaceAddress: netd failed: ${e.message}")
            false
        }
    }

    /**
     * Remove an IP alias: netd binder first, `ip addr del` fallback (root).
     * Best effort — returns true if either path completed.
     */
    override fun removeInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val netdBinder = getService.invoke(null, "netd") as? android.os.IBinder
            if (netdBinder != null) {
                val stubClass = Class.forName("android.net.INetd\$Stub")
                val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                val netd = asInterface.invoke(null, netdBinder)
                netd.javaClass.getMethod(
                    "interfaceDelAddress",
                    String::class.java, String::class.java, Int::class.javaPrimitiveType
                ).invoke(netd, ifName, address, prefixLength)
                Log.i(TAG, "removeInterfaceAddress: netd removed $address/$prefixLength on $ifName")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeInterfaceAddress: netd failed: ${e.message}")
        }
        return try {
            execCommand("ip addr del $address/$prefixLength dev $ifName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "removeInterfaceAddress: ip cmd failed: ${e.message}")
            false
        }
    }

    /**
     * Full Tesla network setup, idempotent. Cascade:
     *   0. alias already present → done
     *   1. INetd binder add → verify
     *   2. `ip addr add` shell fallback (root) → verify
     * Verification always re-reads `ip -o addr show dev <ifName>`.
     * Returns a diagnostic log; final line is RESULT: OK or RESULT: FAIL.
     */
    override fun setupTeslaNetworking(ifName: String, virtualIp: String): String {
        val log = StringBuilder("=== setupTeslaNetworking($ifName, $virtualIp) ===\n")

        fun aliasPresent(): Boolean {
            val out = execCommand("ip -o addr show dev $ifName")
            return out.lineSequence().any { line ->
                val tokens = line.trim().split(Regex("\\s+"))
                tokens.size >= 4 && tokens[2] == "inet" &&
                    tokens[3].substringBefore('/') == virtualIp
            }
        }

        if (aliasPresent()) {
            log.appendLine("alias already present — nothing to do")
            log.appendLine("RESULT: OK")
            Log.i(TAG, log.toString())
            return log.toString()
        }

        log.appendLine("-- method 1: INetd binder --")
        if (addInterfaceAddress(ifName, virtualIp, 32)) {
            if (aliasPresent()) {
                log.appendLine("alias verified after netd")
                log.appendLine("RESULT: OK")
                Log.i(TAG, log.toString())
                return log.toString()
            }
            log.appendLine("netd returned success but alias missing after verify")
        } else {
            log.appendLine("netd method unavailable/failed")
        }

        log.appendLine("-- method 2: ip addr add (shell fallback) --")
        execCommand("ip addr add $virtualIp/32 dev $ifName")
        if (aliasPresent()) {
            log.appendLine("alias verified after ip command")
            log.appendLine("RESULT: OK")
        } else {
            log.appendLine("alias still missing — all methods failed")
            log.appendLine("RESULT: FAIL")
        }
        Log.i(TAG, log.toString())
        return log.toString()
    }

    override fun restartTetheringWithCgnat(): String { return "" }
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（无新警告）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/castla/mirror/shizuku/PrivilegedService.kt
git commit -m "Implement Tesla virtual-IP alias in PrivilegedService (netd + shell cascade)"
```

---

### Task 3: ShizukuSetup 清理包装 + MainActivity 接线

**Files:**
- Modify: `app/src/main/java/com/castla/mirror/shizuku/ShizukuSetup.kt`（在 759-774 行 `setupTeslaNetwork` 之后追加 `removeTeslaNetwork`）
- Modify: `app/src/main/java/com/castla/mirror/MainActivity.kt`（状态变量、updateServerUrl、launchMirrorService、stopMirrorService、网络回调、findHotspotInterface）

**Interfaces:**
- Consumes:
  - `TeslaVirtualIp.*`（Task 1 的全部签名）
  - `IPrivilegedService.removeInterfaceAddress(ifName, address, prefixLength): Boolean`（Task 2）
  - 既有：`shizukuSetup.serviceConnected.value: Boolean`、`shizukuSetup.exec(cmd): String?`、`shizukuSetup.setupTeslaNetwork(ifName, virtualIp): String?`、`MirrorServer.DEFAULT_PORT`
- Produces: 无新对外接口（纯接线）

- [ ] **Step 1: ShizukuSetup 追加 removeTeslaNetwork**

在 `ShizukuSetup.kt` 的 `setupTeslaNetwork` 函数（759-774 行）之后插入：

```kotlin
    /**
     * Remove the Tesla virtual IP alias from the hotspot interface. Best effort.
     */
    fun removeTeslaNetwork(ifName: String, virtualIp: String = "100.99.9.9"): Boolean {
        val service = privilegedService
        if (service == null) {
            Log.w(TAG, "removeTeslaNetwork: service not connected")
            return false
        }
        return try {
            service.removeInterfaceAddress(ifName, virtualIp, 32)
        } catch (e: Exception) {
            Log.e(TAG, "removeInterfaceAddress failed", e)
            false
        }
    }
```

- [ ] **Step 2: MainActivity 加状态与 setup/teardown 函数**

在 `MainActivity.kt:105`（`isHotspotActive` 声明）之后添加状态：

```kotlin
    private var teslaVirtualIpActive by mutableStateOf(false)
    private var teslaAliasIfName: String? = null
```

文件顶部 import 区添加：

```kotlin
import com.castla.mirror.network.TeslaVirtualIp
```

在 `findHotspotInterface()`（788-795 行）之后添加两个函数：

```kotlin
    /**
     * Tesla compatibility mode: alias 100.99.9.9 onto the hotspot interface so
     * the Tesla browser (which refuses RFC1918 ranges) can reach the server.
     * Idempotent; runs entirely on the IO dispatcher; never blocks mirroring.
     */
    private fun setupTeslaVirtualIp() {
        if (!shizukuSetup.serviceConnected.value) {
            Log.i(TAG, "TeslaVirtualIp: privileged service not connected, skipping")
            return
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Hotspot interface may take a few seconds to appear after enabling.
            var ifName: String? = null
            for (attempt in 1..15) {
                val out = shizukuSetup.exec("ip -o addr show")
                if (out != null) {
                    val addrs = TeslaVirtualIp.parseInterfaceAddresses(out)
                    val found = TeslaVirtualIp.findHotspotInterface(addrs)
                    if (found != null) {
                        if (TeslaVirtualIp.hasVirtualAlias(addrs, found)) {
                            runOnUiThread {
                                teslaAliasIfName = found
                                teslaVirtualIpActive = true
                                updateServerUrl()
                            }
                            return@launch
                        }
                        ifName = found
                        break
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
            val target = ifName
            if (target == null) {
                Log.i(TAG, "TeslaVirtualIp: no hotspot interface (WiFi mode) — skipping")
                return@launch
            }
            val diagLog = shizukuSetup.setupTeslaNetwork(target)
            Log.i(TAG, "TeslaVirtualIp setup:\n$diagLog")
            FileLogger.i(TAG, "TeslaVirtualIp setup on $target: ${diagLog?.lines()?.lastOrNull()}")
            if (TeslaVirtualIp.setupSucceeded(diagLog)) {
                runOnUiThread {
                    teslaAliasIfName = target
                    teslaVirtualIpActive = true
                    updateServerUrl()
                }
            } else {
                Log.w(TAG, "TeslaVirtualIp: setup failed, keeping original URL")
                FileLogger.w(TAG, "TeslaVirtualIp setup failed on $target:\n$diagLog")
            }
        }
    }

    private fun teardownTeslaVirtualIp() {
        val ifName = teslaAliasIfName ?: return
        teslaAliasIfName = null
        teslaVirtualIpActive = false
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = shizukuSetup.removeTeslaNetwork(ifName)
            Log.i(TAG, "TeslaVirtualIp teardown on $ifName: $ok")
            FileLogger.i(TAG, "TeslaVirtualIp teardown on $ifName: $ok")
        }
    }
```

确认 `FileLogger` 已 import（`MainActivity.kt:4` 已 import `com.castla.mirror.diagnostics.FileLogger`，如无则添加）。

- [ ] **Step 3: 接线 — updateServerUrl 末尾加虚拟 URL 优先**

`updateServerUrl()`（643-659 行）在现有 if/else 之后追加：

```kotlin
        // Tesla compatibility: when the virtual alias is live, prefer it —
        // the Tesla browser refuses RFC1918 addresses outright.
        if (teslaVirtualIpActive) {
            serverUrl = TeslaVirtualIp.virtualUrl(MirrorServer.DEFAULT_PORT)
        }
```

- [ ] **Step 4: 接线 — 启动/停止/网络变化**

`launchMirrorService`（1206 行）在 `isStreaming = true`（1224 行）之后插入：

```kotlin
        setupTeslaVirtualIp()
```

`stopMirrorService`（1255 行）在 `isHotspotActive = false`（1269 行）之后、`updateServerUrl()`（1270 行）之前插入：

```kotlin
        teardownTeslaVirtualIp()
```

网络回调（216-225 行）Connected 分支改为（热点中途重启后自动补别名）：

```kotlin
                        is NetworkState.Connected -> {
                            currentIp = state.ip
                            updateServerUrl()
                            if (isStreaming && !teslaVirtualIpActive) {
                                setupTeslaVirtualIp()
                            }
                        }
```

`findHotspotInterface()`（788-795 行）重构为复用纯逻辑（行为等价，解析更严格）：

```kotlin
    private fun findHotspotInterface(): String? {
        val result = shizukuSetup.exec("ip -o addr show") ?: return null
        return TeslaVirtualIp.findHotspotInterface(TeslaVirtualIp.parseInterfaceAddresses(result))
    }
```

- [ ] **Step 5: 编译 + 全量单测**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（含 Task 1 的 14 个新测试，无回归）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/castla/mirror/shizuku/ShizukuSetup.kt app/src/main/java/com/castla/mirror/MainActivity.kt
git commit -m "Auto-setup Tesla virtual-IP alias on mirror start, teardown on stop"
```

---

### Task 4: 整体构建验证

**Files:** 无改动（验证任务）

- [ ] **Step 1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS

- [ ] **Step 2: 完整构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产物 `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: 真机冒烟清单（交付给用户，上车时执行）**

1. 手机装 debug APK → Stellar 运行中 → 开热点 → 开始投屏
2. 预期：app 显示 `http://100-99-9-9.sslip.io:9090`（几秒轮询后切换）
3. 车机连热点 → 浏览器输入该地址 → 页面打开、画面正常
4. 回归：笔记本用原地址投屏仍正常
5. 停止投屏后：`adb shell ip -o addr show dev swlan0` 不含 `100.99.9.9`（别名已清理）
6. 若失败：导出 FileLogger 日志，查 `TeslaVirtualIp setup` 条目中级联各步的失败原因

---

## Self-Review 记录

- **Spec 覆盖**：热点接口选择（T1）、别名级联设置（T2）、清理（T2/T3）、URL 切换（T3）、自动启用（T3）、网络变化重试（T3）、失败静默回退+FileLogger（T3）、单测（T1）、真机验证（T4）✓
- **类型一致性**：`setupTeslaNetwork`（ShizukuSetup，既有）返回 `String?` → `setupSucceeded(String?)` 接收可空 ✓；`removeTeslaNetwork` 新增于 Task 3 Step 1，消费于 Task 3 Step 2 ✓；`VIRTUAL_PREFIX` 在测试中引用 ✓
- **AIDL 无改动**：`removeInterfaceAddress` (=8) 已存在 ✓
- **范围**：`restartTetheringWithCgnat` 保持空壳（YAGNI）✓
