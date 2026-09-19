package com.lanlinju.server

import java.nio.file.Path
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Db {
    private lateinit var conn: Connection

    fun init(path: Path) {
        Class.forName("org.sqlite.JDBC")
        path.toFile().parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        conn.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY, salt TEXT NOT NULL, hash TEXT NOT NULL)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, username TEXT NOT NULL, created INTEGER NOT NULL)")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS history(
                username TEXT NOT NULL, source TEXT NOT NULL, anime_title TEXT NOT NULL,
                anime_url TEXT NOT NULL, img TEXT NOT NULL DEFAULT '',
                episode_name TEXT NOT NULL DEFAULT '', episode_url TEXT NOT NULL,
                position REAL NOT NULL DEFAULT 0, updated INTEGER NOT NULL,
                PRIMARY KEY(username, anime_url))""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS favourites(
                username TEXT NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL,
                url TEXT NOT NULL, img TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL,
                PRIMARY KEY(username, url))""")
        }
    }

    // ---------- 密码 ----------

    private val random = SecureRandom()

    private fun newSalt(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun hash(password: String, saltHex: String): String {
        val spec = PBEKeySpec(password.toCharArray(), saltHex.hexToByteArray(), 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    fun adminExists(): Boolean = synchronized(conn) {
        conn.prepareStatement("SELECT 1 FROM users WHERE username='admin'").use { st ->
            st.executeQuery().next()
        }
    }

    /** 设置/重置 admin 密码（首启生成随机密码、CLI `admin set` 都走这里） */
    fun setAdminPassword(password: String) = synchronized(conn) {
        val salt = newSalt()
        conn.prepareStatement("INSERT OR REPLACE INTO users(username, salt, hash) VALUES('admin', ?, ?)").use { st ->
            st.setString(1, salt)
            st.setString(2, hash(password, salt))
            st.executeUpdate()
        }
    }

    fun verifyLogin(username: String, password: String): String? = synchronized(conn) {
        conn.prepareStatement("SELECT salt, hash FROM users WHERE username=?").use { st ->
            st.setString(1, username)
            val rs = st.executeQuery()
            if (!rs.next()) return null
            val expect = rs.getString("hash")
            if (hash(password, rs.getString("salt")) == expect) username else null
        }
    }

    // ---------- 会话 ----------

    fun createSession(username: String): String = synchronized(conn) {
        val token = ByteArray(32).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        conn.prepareStatement("INSERT INTO sessions(token, username, created) VALUES(?,?,?)").use { st ->
            st.setString(1, token)
            st.setString(2, username)
            st.setLong(3, System.currentTimeMillis())
            st.executeUpdate()
        }
        token
    }

    fun sessionUser(token: String): String? = synchronized(conn) {
        conn.prepareStatement("SELECT username FROM sessions WHERE token=?").use { st ->
            st.setString(1, token)
            st.executeQuery().let { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun deleteSession(token: String) = synchronized(conn) {
        conn.prepareStatement("DELETE FROM sessions WHERE token=?").use { st ->
            st.setString(1, token)
            st.executeUpdate()
        }
    }

    // ---------- 历史 ----------

    fun upsertHistory(
        username: String, source: String, animeTitle: String, animeUrl: String,
        img: String, episodeName: String, episodeUrl: String, position: Double,
    ) = synchronized(conn) {
        conn.prepareStatement("""INSERT OR REPLACE INTO history
            (username, source, anime_title, anime_url, img, episode_name, episode_url, position, updated)
            VALUES(?,?,?,?,?,?,?,?,?)""").use { st ->
            st.setString(1, username); st.setString(2, source); st.setString(3, animeTitle)
            st.setString(4, animeUrl); st.setString(5, img); st.setString(6, episodeName)
            st.setString(7, episodeUrl); st.setDouble(8, position)
            st.setLong(9, System.currentTimeMillis())
            st.executeUpdate()
        }
    }

    data class HistoryRow(
        val source: String, val animeTitle: String, val animeUrl: String, val img: String,
        val episodeName: String, val episodeUrl: String, val position: Double, val updated: Long,
    )

    fun listHistory(username: String, limit: Int = 60): List<HistoryRow> = synchronized(conn) {
        conn.prepareStatement("SELECT * FROM history WHERE username=? ORDER BY updated DESC LIMIT ?").use { st ->
            st.setString(1, username); st.setInt(2, limit)
            val rs = st.executeQuery()
            val out = mutableListOf<HistoryRow>()
            while (rs.next()) {
                out.add(HistoryRow(
                    rs.getString("source"), rs.getString("anime_title"), rs.getString("anime_url"),
                    rs.getString("img"), rs.getString("episode_name"), rs.getString("episode_url"),
                    rs.getDouble("position"), rs.getLong("updated"),
                ))
            }
            out
        }
    }

    fun deleteHistory(username: String, animeUrl: String) = synchronized(conn) {
        conn.prepareStatement("DELETE FROM history WHERE username=? AND anime_url=?").use { st ->
            st.setString(1, username); st.setString(2, animeUrl); st.executeUpdate()
        }
    }

    fun clearHistory(username: String) = synchronized(conn) {
        conn.prepareStatement("DELETE FROM history WHERE username=?").use { st ->
            st.setString(1, username); st.executeUpdate()
        }
    }

    // ---------- 收藏 ----------

    fun upsertFavourite(username: String, source: String, title: String, url: String, img: String) =
        synchronized(conn) {
            conn.prepareStatement("""INSERT OR REPLACE INTO favourites
                (username, source, title, url, img, created) VALUES(?,?,?,?,?,?)""").use { st ->
                st.setString(1, username); st.setString(2, source); st.setString(3, title)
                st.setString(4, url); st.setString(5, img)
                st.setLong(6, System.currentTimeMillis())
                st.executeUpdate()
            }
        }

    data class FavouriteRow(val source: String, val title: String, val url: String, val img: String, val created: Long)

    fun listFavourites(username: String): List<FavouriteRow> = synchronized(conn) {
        conn.prepareStatement("SELECT * FROM favourites WHERE username=? ORDER BY created DESC").use { st ->
            st.setString(1, username)
            val rs = st.executeQuery()
            val out = mutableListOf<FavouriteRow>()
            while (rs.next()) {
                out.add(FavouriteRow(
                    rs.getString("source"), rs.getString("title"),
                    rs.getString("url"), rs.getString("img"), rs.getLong("created"),
                ))
            }
            out
        }
    }

    fun removeFavourite(username: String, url: String) = synchronized(conn) {
        conn.prepareStatement("DELETE FROM favourites WHERE username=? AND url=?").use { st ->
            st.setString(1, username); st.setString(2, url); st.executeUpdate()
        }
    }

    fun isFavourite(username: String, url: String): Boolean = synchronized(conn) {
        conn.prepareStatement("SELECT 1 FROM favourites WHERE username=? AND url=?").use { st ->
            st.setString(1, username); st.setString(2, url)
            st.executeQuery().next()
        }
    }

    /** 生成 Alist 风格的随机密码（去掉易混淆字符） */
    fun randomPassword(length: Int = 12): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        return buildString {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}
