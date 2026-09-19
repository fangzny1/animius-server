package com.lanlinju.server

import com.lanlinju.animius.util.SettingsStore
import com.lanlinju.animius.util.SourceHolder
import com.lanlinju.animius.util.SourceMode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

const val DATA_DB = "animius.db"

fun main(args: Array<String>) {
    // CLI 子命令: admin set <密码>
    if (args.firstOrNull() == "admin") {
        val pwd = args.getOrNull(2)
        if (args.getOrNull(1) != "set" || pwd.isNullOrBlank()) {
            println("用法: java -jar animius-server.jar admin set <新密码>")
            exitProcess(1)
        }
        Db.init(dataDir(resolveDataDir(args)).resolve(DATA_DB))
        Db.setAdminPassword(pwd)
        println("admin 密码已更新。")
        exitProcess(0)
    }

    val dataDir = dataDir(resolveDataDir(args))
    val port = resolvePort(args)
    SettingsStore.file = dataDir.resolve("settings.json")
    Db.init(dataDir.resolve(DATA_DB))

    // Alist 式首次启动：没有管理员就生成随机密码并打印
    if (!Db.adminExists()) {
        val pwd = Db.randomPassword()
        Db.setAdminPassword(pwd)
        println()
        println("=========================================================")
        println("  Animius Web 首次启动，已自动创建管理员账号：")
        println()
        println("      账号: admin")
        println("      密码: $pwd")
        println()
        println("  请妥善保存密码。可用以下命令重设：")
        println("      java -jar animius-server.jar admin set <新密码>")
        println("=========================================================")
        println()
    }

    // 恢复上次使用的数据源
    SettingsStore.get("defaultSource")?.let { name ->
        runCatching { SourceHolder.switchSource(SourceMode.valueOf(name)) }
    }

    println("Animius Web 启动中: http://0.0.0.0:$port  (数据目录: $dataDir)")
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

private fun resolveDataDir(args: Array<String>): Path {
    val i = args.indexOf("--data")
    if (i >= 0 && args.size > i + 1) return Paths.get(args[i + 1])
    // 默认：工作目录下 data 目录（systemd 里用 WorkingDirectory 指到安装目录）
    return Paths.get(System.getProperty("user.dir"), "data")
}

private fun resolvePort(args: Array<String>): Int {
    val i = args.indexOf("--port")
    if (i >= 0 && args.size > i + 1) return args[i + 1].toIntOrNull() ?: 8090
    System.getenv("ANIMIUS_PORT")?.toIntOrNull()?.let { return it }
    return 8090
}

private fun dataDir(base: Path): Path {
    base.toFile().mkdirs()
    return base
}
